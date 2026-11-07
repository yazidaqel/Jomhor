import 'dart:convert';
import 'dart:math';

import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/server_clock.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/scan/validator.dart';

/// A scan as the result screen shows it.
final class ScanOutcome {
  const new({
    required this.verdict,
    required this.scanId,
    required this.atMs,
    required this.elapsedMs,
    this.zoneName,
    this.timeZone,
  });

  final Verdict verdict;
  final String scanId;

  /// Corrected device time of the scan (epoch ms).
  final int atMs;

  /// From the raw string to the committed scan row ("Validé hors ligne en {ms} ms").
  final int elapsedMs;
  final String? zoneName;

  /// The event's IANA zone (docs/05 `config.event_timezone`): result times are shown in it.
  final String? timeZone;
}

/// Top line and counters card of screen 6, for the device's current gate.
final class GateDesk {
  const new({
    required this.gateCode,
    required this.gateName,
    required this.entered,
    required this.refused,
    required this.ratePerMin,
    required this.unsynced,
  });

  final String gateCode;
  final String gateName;

  /// Admitted (OK, OVERRIDE) and refused scans at this gate on this device.
  final int entered;
  final int refused;

  /// Admitted scans in the last 5 min ÷ 5, as `/live` counts it (docs/04 §Gate reports).
  final int ratePerMin;

  /// Every scan row not yet acked by the server.
  final int unsynced;
}

/// Thrown when the scan path runs outside event mode (no event, gate, unlocked staff or K_e).
final class EventModeInactive implements Exception {
  const new();
}

/// UUIDv7 (RFC 9562): 48-bit ms timestamp, version 7, variant 10, 74 random bits.
String uuidV7(int ms, Random random) {
  final b = Uint8List(16);
  for (var i = 0; i < 6; i++) {
    b[i] = (ms >> (8 * (5 - i))) & 0xff;
  }
  for (var i = 6; i < 16; i++) {
    b[i] = random.nextInt(256);
  }
  b[6] = 0x70 | (b[6] & 0x0f);
  b[8] = 0x80 | (b[8] & 0x3f);
  return uuidString(b);
}

/// The scan path: validator + step 8, all on the device. Imports no HTTP client (test/scan_service_test.dart, group
/// 'no network on the scan path').
class ScanService {
  new(this._db, {required this._clock, required this._eventKey, Random? random}) : _random = random ?? Random.secure();

  final ScannerDatabase _db;
  final ServerClock _clock;
  final EventKey? Function() _eventKey;
  final Random _random;

  /// Validates [raw] and writes its scan row (and, when admitted, the used row) in ONE transaction, so a
  /// failure leaves neither and two scans of one ticket cannot both pass step 7. Every outcome is logged.
  Future<ScanOutcome> scan(String raw) async {
    final watch = Stopwatch()..start();
    final key = _eventKey();
    final now = _clock.nowMs();
    final outcome = await _db.transaction(() async {
      final config = await _db.readConfig();
      final (eventId, gateId, staffId) = (config.eventId, config.gateId, config.staffId);
      if (key == null || eventId == null || gateId == null || staffId == null) throw const EventModeInactive();
      final gates = await _gates();
      final verdict = await validate(
        raw,
        eventId: eventId,
        eventKey: key.bytes,
        nowMs: now,
        gateId: gateId,
        gates: gates,
        manifest: (id) async {
          final row = await (_db.select(_db.manifestTickets)..where((t) => t.ticketId.equals(id))).getSingleOrNull();
          return row == null
              ? null
              : ManifestEntry(zoneId: row.zoneId, status: row.status, initials: row.holderInitials);
        },
        used: (id) async {
          final row = await (_db.select(
            _db.used,
          )..where((u) => u.ticketId.equals(id) & u.direction.equals('IN'))).getSingleOrNull();
          return row == null ? null : UsedEntry(firstAt: row.firstAt, gateCode: row.gateCode);
        },
      );
      final scanId = uuidV7(now, _random);
      await _db
          .into(_db.scans)
          .insert(
            ScansCompanion.insert(
              scanId: Value(scanId),
              ticketId: Value(verdict.ticketId),
              gateId: gateId,
              staffId: staffId,
              direction: 'IN',
              result: verdict.result.name,
              tokenVersion: Value(verdict.tokenVersion),
              deviceTime: now,
            ),
          );
      if (verdict.result == ScanResult.OK) {
        await _db
            .into(_db.used)
            .insert(
              UsedCompanion.insert(
                ticketId: verdict.ticketId!,
                direction: 'IN',
                firstAt: now,
                gateCode: Value(gates.where((g) => g.gateId == gateId).firstOrNull?.code),
                source: 'LOCAL',
              ),
            );
      }
      final zone = verdict.zoneId == null
          ? null
          : await (_db.select(_db.zones)..where((z) => z.zoneId.equals(verdict.zoneId!))).getSingleOrNull();
      return ScanOutcome(
        verdict: verdict,
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

  /// Null outside event mode.
  Future<GateDesk?> desk() async {
    final config = await _db.readConfig();
    final gateId = config.gateId;
    if (_eventKey() == null || config.eventId == null || gateId == null || config.staffId == null) return null;
    final gate = await (_db.select(_db.gates)..where((g) => g.gateId.equals(gateId))).getSingleOrNull();
    final since = _clock.nowMs() - const Duration(minutes: 5).inMilliseconds;
    final row = await _db
        .customSelect(
          'SELECT '
          "coalesce(sum(gate_id = ?1 AND result IN ('OK','OVERRIDE')), 0) AS entered, "
          "coalesce(sum(gate_id = ?1 AND result NOT IN ('OK','OVERRIDE')), 0) AS refused, "
          "coalesce(sum(gate_id = ?1 AND result IN ('OK','OVERRIDE') AND device_time >= ?2), 0) AS recent, "
          'coalesce(sum(synced = 0), 0) AS unsynced FROM scans',
          variables: [Variable.withString(gateId), Variable.withInt(since)],
          readsFrom: {_db.scans},
        )
        .getSingle();
    return GateDesk(
      gateCode: gate?.code ?? '',
      gateName: gate?.name ?? '',
      entered: row.read<int>('entered'),
      refused: row.read<int>('refused'),
      ratePerMin: row.read<int>('recent') ~/ 5,
      unsynced: row.read<int>('unsynced'),
    );
  }

  Future<List<GateRule>> _gates() async => [
    for (final g in await (_db.select(_db.gates)..orderBy([(g) => OrderingTerm.asc(g.code)])).get())
      GateRule(
        gateId: g.gateId!,
        code: g.code,
        zoneIds: {for (final z in jsonDecode(g.zoneIds) as List<Object?>) z! as int},
      ),
  ];
}

final scanServiceProvider = Provider<ScanService>(
  (ref) => ScanService(
    ref.watch(databaseProvider),
    clock: ref.watch(serverClockProvider),
    eventKey: () => ref.read(eventKeyProvider),
  ),
);
