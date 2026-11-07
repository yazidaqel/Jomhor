import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/event/manifest.dart';

/// docs/02 §Clocks: drift = abs(server offset); above 20 s the app refuses event mode.
const maxDriftMs = 20000;

/// Drift check passed: an offset was measured and it is within [maxDriftMs].
bool driftOk(ConfigData c) => c.lastServerTimeAt != null && c.serverTimeOffsetMs.abs() <= maxDriftMs;

/// docs/02 §Manifest step 3: the server builds versions until T0 + 30 min, so the 5-min refresh stops there.
const refreshUntilAfterStart = Duration(minutes: 30);

bool refreshDue(ConfigData c, int nowMs) =>
    c.startsAt == null || nowMs <= c.startsAt! + refreshUntilAfterStart.inMilliseconds;

/// Event mode refused because of the device clock.
final class ClockDrift implements Exception {
  const new(this.offsetMs);

  final int offsetMs;
}

/// One assignment from `GET /gate/events` (docs/04 §Gate).
final class Assignment {
  const new({
    required this.id,
    required this.title,
    required this.status,
    required this.startsAt,
    required this.timezone,
    required this.venueName,
    required this.gateId,
    required this.gateCode,
    required this.gateName,
    required this.reentryPolicy,
    required this.manifestAvailableAt,
    required this.manifestVersion,
  });

  new fromJson(Map<String, Object?> j)
    : this(
        id: j['id']! as int,
        title: j['title']! as String,
        status: j['status']! as String,
        startsAt: DateTime.parse(j['starts_at']! as String).millisecondsSinceEpoch,
        timezone: j['timezone']! as String,
        venueName: (j['venue']! as Map<String, Object?>)['name']! as String,
        gateId: (j['gate']! as Map<String, Object?>)['id']! as String,
        gateCode: (j['gate']! as Map<String, Object?>)['code']! as String,
        gateName: (j['gate']! as Map<String, Object?>)['name']! as String,
        reentryPolicy: j['reentry_policy']! as String,
        manifestAvailableAt: DateTime.parse(j['manifest_available_at']! as String).millisecondsSinceEpoch,
        manifestVersion: j['manifest_version'] as int?,
      );

  final int id;
  final String title;
  final String status;

  /// Epoch ms.
  final int startsAt;

  /// IANA zone of the venue.
  final String timezone;
  final String venueName;
  final String gateId;
  final String gateCode;
  final String gateName;
  final String reentryPolicy;

  /// Epoch ms.
  final int manifestAvailableAt;

  /// Latest built, null before v1.
  final int? manifestVersion;
}

/// Where a manifest download is, for the Événement screen.
enum ManifestStage { downloading, verifying, saving }

/// Enrolment status, assignments, staff, manifest and K_e: everything the device needs before the gate goes
/// offline. Every call here is online; none is on the scan path.
class EventService {
  new(this._db, {required this._gate, required this._eventKey, required this._loadedKey});

  final ScannerDatabase _db;
  final GateClient _gate;
  final EventKeyHolder _eventKey;

  /// K_e currently in memory.
  final EventKey? Function() _loadedKey;

  /// `GET /gate/events`: marks the device ACTIVE, replaces the staff list (PIN verifiers included) and keeps
  /// the loaded event's details current. Throws [GateApiError] (403 `DEVICE_NOT_ACTIVE` while PENDING).
  Future<List<Assignment>> refresh() async {
    final json = await _gate.get('/gate/events');
    if (json is! Map<String, Object?>) {
      throw const GateApiError(status: 200, code: GateApiError.unexpectedResponse, message: 'events');
    }
    final events = [for (final e in json['events']! as List<Object?>) Assignment.fromJson(e! as Map<String, Object?>)];
    final staff = [
      for (final s in json['staff']! as List<Object?>)
        if (s case {
          'staff_id': final String id,
          'label': final String label,
          'role': final String role,
          'pin_verifier': final String verifier,
        })
          StaffCompanion.insert(staffId: Value(id), label: label, role: role, pinVerifier: verifier),
    ];
    await _db.transaction(() async {
      final config = await _db.readConfig();
      await _db.delete(_db.staff).go();
      await _db.batch((b) => b.insertAll(_db.staff, staff));
      final current = events.where((e) => e.id == config.eventId).firstOrNull;
      await _db
          .update(_db.config)
          .write(
            ConfigCompanion(
              deviceStatus: const Value('ACTIVE'),
              eventTitle: current == null ? const Value.absent() : Value(current.title),
              startsAt: current == null ? const Value.absent() : Value(current.startsAt),
              eventTimezone: current == null ? const Value.absent() : Value(current.timezone),
              // CLOSED is final: an older list (fetched before the close) never reopens the event.
              eventStatus: current == null || config.eventStatus == 'CLOSED'
                  ? const Value.absent()
                  : Value(current.status),
            ),
          );
      // Unassigned before anything was downloaded: forget it so another assignment can be picked.
      if (config.eventId != null && current == null && config.manifestVersion == 0) await _clearEvent();
    });
    return events;
  }

  /// Picks [a] as the device's event and gate. Refused with [ClockDrift] when the clock is off by > 20 s.
  Future<void> select(Assignment a) async {
    final config = await _db.readConfig();
    if (!driftOk(config)) throw ClockDrift(config.serverTimeOffsetMs);
    if (config.eventId != null && config.eventId != a.id) throw StateError('another event is loaded');
    await _db
        .update(_db.config)
        .write(
          ConfigCompanion(
            eventId: Value(a.id),
            eventTitle: Value(a.title),
            startsAt: Value(a.startsAt),
            eventTimezone: Value(a.timezone),
            eventStatus: Value(a.status),
            gateId: Value(a.gateId),
            reentryPolicy: Value(a.reentryPolicy),
          ),
        );
  }

  /// App start: K_e back into memory for the loaded event. Without a stored K_e the next download is a full one.
  Future<void> restoreKey() async {
    final config = await _db.readConfig();
    if (config.eventId != null && config.manifestVersion > 0) await _eventKey.restore();
  }

  /// Brings the manifest up to date: a delta from the local version, or a full one (with K_e) when there is
  /// none, K_e is missing, or the delta does not apply (wrong `since`, row count mismatch). Throws
  /// [ManifestRejected] (nothing written), [ClockDrift] or [GateApiError] (`MANIFEST_NOT_READY`, offline).
  Future<Manifest> download({void Function(ManifestStage stage)? onStage}) async {
    final config = await _db.readConfig();
    if (!driftOk(config)) throw ClockDrift(config.serverTimeOffsetMs);
    final local = config.manifestVersion;
    if (local > 0 && _loadedKey() != null) {
      final delta = await _fetchAndApply(config, since: local, onStage: onStage);
      if (delta != null) return delta;
    }
    return (await _fetchAndApply(config, since: null, onStage: onStage))!;
  }

  /// Null when a delta does not apply (the caller refetches the full manifest).
  Future<Manifest?> _fetchAndApply(
    ConfigData config, {
    required int? since,
    void Function(ManifestStage stage)? onStage,
  }) async {
    final eventId = config.eventId ?? (throw StateError('no event selected'));
    final pinned = config.manifestPublicKey ?? (throw StateError('no pinned manifest key'));
    onStage?.call(ManifestStage.downloading);
    final json = await _gate.get(
      '/gate/events/$eventId/manifest',
      query: since == null ? null : {'since': '$since'},
      // JSON decode, signature check and parse in one background isolate (45,000 rows: about 2.6 MB).
      decode: (bytes) {
        onStage?.call(ManifestStage.verifying);
        return decodeManifestBody(bytes, pinned);
      },
    );
    if (json is! Map<String, Object?>) throw const ManifestRejected('envelope');
    if (json['rejected'] case final String reason) throw ManifestRejected(reason);
    final m = json['manifest'];
    if (m is! Manifest) throw const ManifestRejected('envelope');
    // The envelope must agree with the signed header, and the header with this device's event.
    if (m.eventId != eventId || json['event_id'] != m.eventId || json['version'] != m.version) {
      throw const ManifestRejected('event or version');
    }
    if (json['since'] != m.since || json['rows'] != m.rows) throw const ManifestRejected('since or rows');
    // A full manifest was asked for: a delta is not an answer.
    if (since == null && !m.full) throw const ManifestRejected('since');
    // A delta from another base: the caller refetches the full manifest.
    if (!m.full && m.since != since) return null;
    // Never backwards: a full older than the local version, or a delta that does not move forward.
    if (m.full ? m.version < config.manifestVersion : m.version <= m.since) throw const ManifestRejected('version');
    onStage?.call(ManifestStage.saving);
    if (!m.full) return await _applyDelta(m, config) ? m : null;

    final key = json['event_key'];
    final bytes = key is String ? _keyBytes(key) : null;
    if (bytes == null) throw const ManifestRejected('event_key');
    await _applyFull(m, config);
    // K_e only after the commit; a wipe (revocation) that landed meanwhile wins.
    await _eventKey.store(bytes);
    if (!_same(config, await _db.readConfig())) {
      await _eventKey.erase();
      throw const ManifestRejected('device changed');
    }
    return m;
  }

  /// Same device, event and event status as when the download started (a revocation wipe resets the first two;
  /// the sync loop sets CLOSED before it wipes the event, so a download in flight cannot bring it back).
  static bool _same(ConfigData a, ConfigData b) =>
      a.deviceId == b.deviceId && a.eventId == b.eventId && a.eventStatus == b.eventStatus;

  /// Inside the write transaction: nothing is applied over a device that was wiped or switched meanwhile.
  Future<void> _guard(ConfigData started) async {
    if (!_same(started, await _db.readConfig())) throw const ManifestRejected('device changed');
  }

  static Uint8List? _keyBytes(String s) {
    try {
      final b = base64UrlBytes(s);
      return b.length == 32 ? b : null;
    } on FormatException {
      return null;
    }
  }

  /// docs/05: zones, gates and tickets replaced and the version set in ONE transaction. `used` is kept.
  Future<void> _applyFull(Manifest m, ConfigData started) => _db.transaction(() async {
    await _guard(started);
    await _db.delete(_db.manifestTickets).go();
    await _replaceVenue(m);
    await _db.batch((b) => b.insertAll(_db.manifestTickets, m.tickets));
    await _setVersion(m);
  });

  /// One transaction; rolled back (false) when the row count afterwards is not the header's `rows`.
  Future<bool> _applyDelta(Manifest m, ConfigData started) async {
    try {
      await _db.transaction(() async {
        await _guard(started);
        await _replaceVenue(m);
        await _db.batch((b) => b.insertAllOnConflictUpdate(_db.manifestTickets, m.tickets));
        await _setVersion(m);
        final count = await _db.manifestTickets.count().getSingle();
        if (count != m.rows) throw const _RowsMismatch();
      });
      return true;
    } on _RowsMismatch {
      return false;
    }
  }

  Future<void> _replaceVenue(Manifest m) async {
    await _db.delete(_db.zones).go();
    await _db.delete(_db.gates).go();
    await _db.batch((b) {
      b
        ..insertAll(_db.zones, m.zones)
        ..insertAll(_db.gates, m.gates);
    });
  }

  Future<void> _setVersion(Manifest m) =>
      _db.update(_db.config).write(ConfigCompanion(manifestVersion: Value(m.version), reentryPolicy: Value(m.reentry)));

  Future<void> _clearEvent() => _db
      .update(_db.config)
      .write(
        const ConfigCompanion(
          eventId: Value(null),
          eventTitle: Value(null),
          startsAt: Value(null),
          eventTimezone: Value(null),
          eventStatus: Value(null),
          gateId: Value(null),
        ),
      );
}

final class _RowsMismatch implements Exception {
  const new();
}

final eventServiceProvider = Provider<EventService>(
  (ref) => EventService(
    ref.watch(databaseProvider),
    gate: ref.watch(gateClientProvider),
    eventKey: ref.read(eventKeyProvider.notifier),
    loadedKey: () => ref.read(eventKeyProvider),
  ),
);
