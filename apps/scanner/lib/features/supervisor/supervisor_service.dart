import 'dart:math';

import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/core/server_clock.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/features/scan/validator.dart';
import 'package:jomhor_scanner/features/staff/staff_session.dart';

/// docs/04 `override_reason` (docs/06 labels: Identité vérifiée, Panne d'appareil, Billet abîmé, Autre motif).
enum OverrideReason {
  idChecked('ID_CHECKED'),
  deviceFault('DEVICE_FAULT'),
  damagedTicket('DAMAGED_TICKET'),
  other('OTHER');

  new(this.code);

  final String code;
}

/// A manifest ticket as the supervisor sheet shows it: zone, status and holder initials only (docs/02 §Manual
/// lookup). [ticketId] is kept for the override row, never shown.
final class TicketMatch {
  const new({
    required this.ticketId,
    required this.ticketNo,
    required this.zoneId,
    required this.status,
    this.zoneName,
    this.initials,
  });

  final String ticketId;
  final String ticketNo;
  final int zoneId;
  final String? zoneName;

  /// VALID | CANCELLED | REFUNDED | BLOCKED.
  final String status;
  final String? initials;
}

/// docs/02: at most 20 overrides per device per event. The server stores every valid one (docs/04): the cap
/// is this device's.
final class OverrideCapReached implements Exception {
  const new();
}

/// The supervisor PIN is older than [SupervisorGrant.ttl], or the supervisor is no longer a SUPERVISOR of this
/// device's staff list: the PIN is asked again.
final class SupervisorGrantInvalid implements Exception {
  const new();
}

/// docs/02 / plan lot-2 Q40: an override needs the ticket in the manifest.
final class TicketNotInManifest implements Exception {
  const new();
}

/// The supervisor actions of docs/02 §Gate, zone & re-entry rules: override, gate change, lookup. All on the
/// device, no network (the gate has no signal from T0 − 60).
class SupervisorService {
  new(this._db, {required this._clock, Random? random}) : _random = random ?? Random.secure();

  static const overrideCap = 20;

  final ScannerDatabase _db;
  final ServerClock _clock;
  final Random _random;

  /// OVERRIDE rows on this device. The scans table only ever holds the current event (closing deletes them
  /// all once synced), so this is the per-device, per-event count.
  Future<int> overrideCount() => _db.scans.count(where: (s) => s.result.equals(ScanResult.OVERRIDE.name)).getSingle();

  /// [input]: a printed ticket number (8 hex, spaces or dashes allowed, any case) → every manifest ticket with
  /// it (they collide, Q40); or a scanned token (camera or wedge) → its ticket, without the tag check (a damaged
  /// ticket may not verify, and a typed number has none either). Empty when nothing matches.
  Future<List<TicketMatch>> find(SupervisorGrant grant, String input) async {
    final typed = input.replaceAll(RegExp(r'[\s-]'), '').toUpperCase();
    final Expression<bool> Function(ManifestTickets t) where;
    if (RegExp(r'^[0-9A-F]{8}$').hasMatch(typed)) {
      where = (t) => t.ticketNo.equals(typed);
    } else {
      try {
        final id = uuidString(decode(input.trim()).ticketId);
        where = (t) => t.ticketId.equals(id);
      } on TokenFormatException {
        return const [];
      }
    }
    final query =
        _db.select(_db.manifestTickets).join([
            leftOuterJoin(_db.zones, _db.zones.zoneId.equalsExp(_db.manifestTickets.zoneId)),
          ])
          ..where(where(_db.manifestTickets))
          ..orderBy([OrderingTerm.asc(_db.manifestTickets.ticketId)]);
    TicketMatch match(TypedResult row) {
      final t = row.readTable(_db.manifestTickets);
      return TicketMatch(
        ticketId: t.ticketId!,
        ticketNo: t.ticketNo,
        zoneId: t.zoneId,
        zoneName: row.readTableOrNull(_db.zones)?.name,
        status: t.status,
        initials: t.holderInitials,
      );
    }

    return [for (final row in await query.get()) match(row)];
  }

  /// Writes the OVERRIDE scan row (unlocked staff, supervisor, reason) and marks the ticket used, in ONE
  /// transaction, like the scan path. Any status is allowed (Q40); a ticket already used keeps its first entry.
  /// Throws [SupervisorGrantInvalid], [OverrideCapReached] at 20, [TicketNotInManifest], [EventModeInactive].
  Future<ScanOutcome> override(SupervisorGrant grant, OverrideReason reason, String ticketId) async {
    final watch = Stopwatch()..start();
    final now = _clock.nowMs();
    final outcome = await _db.transaction(() async {
      final config = await _db.readConfig();
      final (eventId, gateId, staffId) = (config.eventId, config.gateId, config.staffId);
      if (eventId == null || gateId == null || staffId == null) throw const EventModeInactive();
      await _checkGrant(grant, now);
      if (await overrideCount() >= overrideCap) throw const OverrideCapReached();
      final ticket = await (_db.select(
        _db.manifestTickets,
      )..where((t) => t.ticketId.equals(ticketId))).getSingleOrNull();
      if (ticket == null) throw const TicketNotInManifest();
      final scanId = uuidV7(now, _random);
      await _db
          .into(_db.scans)
          .insert(
            ScansCompanion.insert(
              scanId: Value(scanId),
              ticketId: Value(ticketId),
              gateId: gateId,
              staffId: staffId,
              direction: 'IN',
              result: ScanResult.OVERRIDE.name,
              deviceTime: now,
              overrideReason: Value(reason.code),
              supervisorId: Value(grant.staffId),
            ),
          );
      final gate = await (_db.select(_db.gates)..where((g) => g.gateId.equals(gateId))).getSingleOrNull();
      await _db
          .into(_db.used)
          .insert(
            UsedCompanion.insert(
              ticketId: ticketId,
              direction: 'IN',
              firstAt: now,
              gateCode: Value(gate?.code),
              source: 'LOCAL',
            ),
            mode: InsertMode.insertOrIgnore,
          );
      final zone = await (_db.select(_db.zones)..where((z) => z.zoneId.equals(ticket.zoneId))).getSingleOrNull();
      return ScanOutcome(
        verdict: Verdict(
          ScanResult.OVERRIDE,
          ticketNo: ticket.ticketNo,
          ticketId: ticketId,
          zoneId: ticket.zoneId,
          initials: ticket.holderInitials,
        ),
        scanId: scanId,
        atMs: now,
        elapsedMs: 0,
        zoneName: zone?.name,
        timeZone: config.eventTimezone,
      );
    });
    return ScanOutcome(
      verdict: outcome.verdict,
      scanId: outcome.scanId,
      atMs: outcome.atMs,
      elapsedMs: watch.elapsedMilliseconds,
      zoneName: outcome.zoneName,
      timeZone: outcome.timeZone,
    );
  }

  /// Inside the action's transaction: the PIN is recent and the staff row is still a SUPERVISOR (a staff refresh
  /// may have removed or demoted them since).
  Future<void> _checkGrant(SupervisorGrant grant, int nowMs) async {
    if (!grant.validAt(nowMs)) throw const SupervisorGrantInvalid();
    final row = await (_db.select(_db.staff)..where((s) => s.staffId.equals(grant.staffId))).getSingleOrNull();
    if (row?.role != 'SUPERVISOR') throw const SupervisorGrantInvalid();
  }

  /// The venue gates from the manifest, by code.
  Future<List<Gate>> gates() => (_db.select(_db.gates)..orderBy([(g) => OrderingTerm.asc(g.code)])).get();

  /// Moves the device to [gateId] (docs/02: supervisor PIN, offline, gates from the manifest). The next scan's
  /// zone check and its `gate_id` follow it.
  Future<void> changeGate(SupervisorGrant grant, String gateId) => _db.transaction(() async {
    await _checkGrant(grant, _clock.nowMs());
    final gate = await (_db.select(_db.gates)..where((g) => g.gateId.equals(gateId))).getSingleOrNull();
    if (gate == null) throw ArgumentError.value(gateId, 'gateId', 'not a gate of the manifest');
    await _db.update(_db.config).write(ConfigCompanion(gateId: Value(gateId)));
  });
}

final supervisorServiceProvider = Provider<SupervisorService>(
  (ref) => SupervisorService(ref.watch(databaseProvider), clock: ref.watch(serverClockProvider)),
);
