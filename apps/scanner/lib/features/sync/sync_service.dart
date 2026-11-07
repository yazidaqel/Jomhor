import 'dart:async';
import 'dart:convert';
import 'dart:isolate';
import 'dart:math';

import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/event/event_controller.dart';

/// docs/04 §Gate sync rules: at most 500 rows per `POST /gate/events/{id}/scans`.
const syncBatchSize = 500;

/// Rows of `used_since` merged per transaction, so a 5,000-row answer never holds the database for long in
/// front of a scan (scan + used are one transaction too, and SQLite runs them one at a time).
const usedChunkSize = 200;

/// docs/02 §Sync step 4: 2 s, 4 s, 8 s … 60 s after [failures] (≥ 1) failures in a row, minus up to 20 %
/// jitter ([jitter] in [0, 1)) so a stand of devices that lose the network together do not retry together.
Duration backoff(int failures, double jitter) {
  final seconds = min(60, 2 << min(failures - 1, 5));
  return Duration(milliseconds: (seconds * 1000 * (1 - 0.2 * jitter)).round());
}

/// One `used_since` row (docs/04 §Gate).
typedef RemoteUse = ({String ticketId, String direction, int firstAt, String? gateCode});

/// Pushes unsynced scans, pulls the used-set of the other devices, and closes the event when the server
/// says so (docs/02 §Scan log, sync protocol; docs/05 footer). Off the scan path: it only ever touches the
/// `synced` flag of a scan row.
class SyncService {
  new(this._db, {required this._gate, required this._eventKey, required this._refreshEvents});

  static const closed = 'CLOSED';

  final ScannerDatabase _db;
  final GateClient _gate;
  final EventKeyHolder _eventKey;

  /// `GET /gate/events` + manifest delta (EventController). Called, not awaited: a download never holds the
  /// sync loop.
  final void Function() _refreshEvents;

  /// The server manifest version last asked for, and since when: a full manifest that keeps being rejected is
  /// not fetched again on every scan.
  int? _askedVersion;
  final _askedAgo = Stopwatch();
  static const refreshAgainAfter = Duration(minutes: 1);

  /// One sync round: batches until nothing is left to push and the server has no more `used_since`. Returns
  /// false when there was nothing to talk to the server about (not enrolled, no event). Throws [GateApiError]
  /// (offline, 5xx, REVOKED: the GateClient has wiped everything by then) or [StateError] on a reply that
  /// breaks the contract; nothing is lost either way, the rows stay unsynced.
  Future<bool> run() async {
    while (true) {
      final config = await _db.readConfig();
      final eventId = config.eventId;
      if (config.deviceId == null || config.deviceStatus != 'ACTIVE' || eventId == null) return false;
      final batch =
          await (_db.select(_db.scans)
                ..where((s) => s.synced.equals(0))
                ..orderBy([(s) => OrderingTerm.asc(s.deviceTime), (s) => OrderingTerm.asc(s.scanId)])
                ..limit(syncBatchSize))
              .get();
      if (config.eventStatus == closed && batch.isEmpty) {
        if (await _close(config)) return true;
        continue; // A scan landed meanwhile: push it first.
      }
      final body = await _gate.post(
        '/gate/events/$eventId/scans',
        body: {
          'cursor': config.syncCursor,
          'scans': [for (final s in batch) _row(s)],
        },
        decode: _decode,
      );
      final reply = switch (body) {
        {'reply': final _Reply r} => r,
        _ => throw StateError('scans reply'),
      };
      final acked = reply.acked.toSet();
      await _apply(config, reply, [
        for (final s in batch)
          if (acked.contains(s.scanId)) s.scanId!,
      ]);
      // docs/04: every row of the batch is acked, refused ones too. A row left out stays unsynced and the round
      // stops here (backoff), so a broken reply never makes a tight loop; same for `more` without progress.
      if (!batch.every((s) => acked.contains(s.scanId))) throw StateError('batch not fully acked');
      if (reply.more && reply.cursor <= config.syncCursor) throw StateError('more without a new cursor');
      if (reply.manifestVersion case final v? when v > config.manifestVersion && reply.eventStatus != closed) {
        _manifestAhead(v);
      }
      if (!reply.more && batch.length < syncBatchSize && reply.eventStatus != closed) return true;
    }
  }

  /// Once per server version, and again at most once a minute while the device stays behind.
  void _manifestAhead(int version) {
    if (version == _askedVersion && _askedAgo.elapsed < refreshAgainAfter) return;
    _askedVersion = version;
    _askedAgo
      ..reset()
      ..start();
    _refreshEvents();
  }

  static Map<String, Object?> _row(Scan s) => {
    'scan_id': s.scanId,
    'ticket_id': ?s.ticketId,
    'gate_id': s.gateId,
    'staff_id': s.staffId,
    'direction': s.direction,
    'result': s.result,
    'token_version': ?s.tokenVersion,
    'device_time': DateTime.fromMillisecondsSinceEpoch(s.deviceTime, isUtc: true).toIso8601String(),
    'override_reason': ?s.overrideReason,
    'supervisor_id': ?s.supervisorId,
  };

  /// A 5,000-row `used_since` is about 600 KB of JSON: decoded and parsed in another isolate so the scan path
  /// keeps the UI isolate. Small replies (every poll) decode in place.
  static Future<Object?> _decode(Uint8List bytes) async =>
      bytes.length < 64 * 1024 ? _decodeReply(bytes) : Isolate.run(() => _decodeReply(bytes));

  /// A 2xx reply becomes `{server_time, reply}` (what GateClient needs, and the parsed reply); an error body
  /// stays the 04 error JSON.
  static Object? _decodeReply(Uint8List bytes) {
    final json = jsonDecode(utf8.decode(bytes));
    if (json is! Map<String, Object?> || !json.containsKey('acked')) return json;
    return {'server_time': json['server_time'], 'reply': _parse(json)};
  }

  static _Reply _parse(Map<String, Object?> json) {
    if (json case {
      'acked': final List<Object?> acked,
      'used_since': final List<Object?> used,
      'cursor': final int cursor,
      'more': final bool more,
      'event_status': final String eventStatus,
    }) {
      return (
        acked: [for (final a in acked) a! as String],
        used: [
          for (final u in used)
            switch (u) {
              {'ticket_id': final String id, 'direction': final String dir, 'first_at': final String at} => (
                ticketId: id,
                direction: dir,
                firstAt: DateTime.parse(at).millisecondsSinceEpoch,
                gateCode: u['gate_code'] as String?,
              ),
              _ => throw StateError('used_since row'),
            },
        ],
        cursor: cursor,
        more: more,
        manifestVersion: json['manifest_version'] as int?,
        eventStatus: eventStatus,
      );
    }
    throw StateError('scans reply');
  }

  /// The `used_since` rows first, in short transactions (re-merging is harmless: the earliest `first_at`
  /// wins), then acks + cursor in one. Every transaction re-checks that the device was not wiped or switched
  /// meanwhile (REVOKED from another call, the event cleared): nothing is written back into a wiped database.
  Future<void> _apply(ConfigData started, _Reply reply, List<String> synced) async {
    for (var i = 0; i < reply.used.length; i += usedChunkSize) {
      final chunk = reply.used.sublist(i, min(i + usedChunkSize, reply.used.length));
      await _db.transaction(() async {
        if (!await _same(started)) throw StateError('device changed');
        await _db.batch((b) {
          for (final u in chunk) {
            b.insert(
              _db.used,
              UsedCompanion.insert(
                ticketId: u.ticketId,
                direction: u.direction,
                firstAt: u.firstAt,
                gateCode: Value(u.gateCode),
                source: 'REMOTE',
              ),
              // docs/02: first scan wins by device time, wherever it was made.
              onConflict: DoUpdate<Used, UsedData>(
                (old) => UsedCompanion.custom(
                  firstAt: Variable.withInt(u.firstAt),
                  gateCode: Variable(u.gateCode),
                  source: const Variable('REMOTE'),
                ),
                where: (old) => old.firstAt.isBiggerThanValue(u.firstAt),
              ),
            );
          }
        });
      });
    }
    await _db.transaction(() async {
      if (!await _same(started)) throw StateError('device changed');
      // Only the synced flag (scans are append-only), and only rows this batch sent that the server acked.
      await (_db.update(_db.scans)..where((s) => s.scanId.isIn(synced))).write(const ScansCompanion(synced: Value(1)));
      await _db
          .update(_db.config)
          .write(
            ConfigCompanion(
              syncCursor: Value(reply.cursor),
              eventStatus: reply.eventStatus == closed ? const Value(closed) : const Value.absent(),
            ),
          );
    });
  }

  Future<bool> _same(ConfigData started) async {
    final now = await _db.readConfig();
    return now.deviceId == started.deviceId && now.eventId == started.eventId;
  }

  /// docs/04 `event_status: CLOSED`, once nothing is left unsynced: wipe the event (manifest, used-set,
  /// venue, synced scans, K_e), then `closed-ack`, then forget the event (back to the Événement screen). The
  /// event id stays until the ack went through, so an ack lost offline is sent again by the next round.
  /// False when a scan is still unsynced (nothing wiped).
  Future<bool> _close(ConfigData started) async {
    final wiped = await _db.transaction(() async {
      if (!await _same(started)) throw StateError('device changed');
      final unsynced = await _db.scans.count(where: (s) => s.synced.equals(0)).getSingle();
      if (unsynced > 0) return false;
      for (final table in <TableInfo<Table, Object?>>[_db.manifestTickets, _db.used, _db.zones, _db.gates]) {
        await _db.delete(table).go();
      }
      await (_db.delete(_db.scans)..where((s) => s.synced.equals(1))).go();
      await _db
          .update(_db.config)
          .write(
            const ConfigCompanion(
              gateId: Value(null),
              staffId: Value(null),
              manifestVersion: Value(0),
              reentryPolicy: Value('NONE'),
              syncCursor: Value(0),
              mode: Value('IN'),
            ),
          );
      return true;
    });
    if (!wiped) return false;
    await _eventKey.erase();
    await _gate.post('/gate/events/${started.eventId}/closed-ack');
    await _db.transaction(() async {
      if (!await _same(started)) return;
      await _db
          .update(_db.config)
          .write(
            const ConfigCompanion(
              eventId: Value(null),
              eventTitle: Value(null),
              startsAt: Value(null),
              eventTimezone: Value(null),
              eventStatus: Value(null),
            ),
          );
    });
    _refreshEvents();
    return true;
  }
}

typedef _Reply = ({
  List<String> acked,
  List<RemoteUse> used,
  int cursor,
  bool more,
  int? manifestVersion,
  String eventStatus,
});

final syncServiceProvider = Provider<SyncService>(
  (ref) => SyncService(
    ref.watch(databaseProvider),
    gate: ref.watch(gateClientProvider),
    eventKey: ref.read(eventKeyProvider.notifier),
    refreshEvents: () => unawaited(ref.read(eventControllerProvider.notifier).refresh(force: true)),
  ),
);
