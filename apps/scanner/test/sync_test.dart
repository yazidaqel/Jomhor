import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:drift/drift.dart' hide isNotNull, isNull;
import 'package:drift/native.dart';
import 'package:fake_async/fake_async.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/features/sync/sync_controller.dart';
import 'package:jomhor_scanner/features/sync/sync_service.dart';

import 'helpers.dart';
import 'scan_fixtures.dart';

const devA = 'd0000000-0000-4000-8000-00000000000a';
const devB = 'd0000000-0000-4000-8000-00000000000b';
final String gateV1 = gates[1].gateId;
final String gateV2 = gates[2].gateId;
final Map<String, String> gateCodes = {for (final g in gates) g.gateId: g.code};

String rfc3339(int ms) => DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true).toIso8601String();

/// The server side of docs/04 §Gate sync rules, in memory: `ON CONFLICT (scan_id) DO NOTHING`, `seq` in arrival
/// order, `used_since` = admitted rows of other devices after the cursor, at most [limit], `closed-ack`.
class FakeServer {
  new({this.limit = 5000});

  final int limit;

  /// Stored rows in `seq` order (seq = index + 1), with the posting device under `device`.
  final rows = <Map<String, Object?>>[];

  /// `(device, last path segment, scan_ids posted, cursor sent)` of every request.
  final calls = <(String, String, List<String>, int?)>[];
  final acked = <String>{};

  /// scan_ids the server refuses (not stored, but acked and listed in `errors`).
  final refuse = <String>{};
  bool closed = false;
  int? manifestVersion = 1;

  /// The next answer is computed (rows stored) and then lost on the way back.
  bool dropNextReply = false;

  /// Runs first; a non-null response replaces the normal answer (failures, lost acks).
  Future<http.Response?> Function(http.Request r)? intercept;

  http.Client get client => MockClient(_handle);

  Future<http.Response> _handle(http.Request r) async {
    final body = r.body.isEmpty ? null : jsonDecode(r.body) as Map<String, Object?>;
    calls.add((
      r.headers['X-Device-Id']!,
      r.url.pathSegments.last,
      [
        for (final s in (body?['scans'] as List<Object?>?) ?? const [])
          (s! as Map<String, Object?>)['scan_id']! as String,
      ],
      body?['cursor'] as int?,
    ));
    if (await intercept?.call(r) case final response?) return response;
    final response = await _answer(r);
    if (dropNextReply) {
      dropNextReply = false;
      throw const SocketException('connection reset');
    }
    return response;
  }

  /// The normal answer, without recording or intercepting.
  Future<http.Response> _answer(http.Request r) async {
    final device = r.headers['X-Device-Id']!;
    final body = r.body.isEmpty ? null : jsonDecode(r.body) as Map<String, Object?>;
    final scans = [for (final s in (body?['scans'] as List<Object?>?) ?? const []) s! as Map<String, Object?>];
    // Echoing the signed device time keeps the device's offset where it is.
    final serverTime = rfc3339(int.parse(r.headers['X-Device-Time']!));
    if (r.url.path.endsWith('/closed-ack')) {
      if (!closed) return json({'code': 'EVENT_NOT_CLOSED', 'message': 'x', 'details': <String, Object?>{}}, 409);
      acked.add(device);
      return http.Response('', 204);
    }
    for (final s in scans) {
      if (refuse.contains(s['scan_id']) || rows.any((x) => x['scan_id'] == s['scan_id'])) continue;
      rows.add({...s, 'device': device});
    }
    final cursor = body!['cursor']! as int;
    final pending = [
      for (final (i, x) in rows.indexed)
        if (i + 1 > cursor && x['device'] != device && (x['result'] == 'OK' || x['result'] == 'OVERRIDE')) (i + 1, x),
    ];
    final used = pending.take(limit).toList();
    return json({
      'acked': [for (final s in scans) s['scan_id']],
      'errors': [
        for (final s in scans)
          if (refuse.contains(s['scan_id'])) {'scan_id': s['scan_id'], 'field': 'ticket_id'},
      ],
      'used_since': [
        for (final (_, x) in used)
          {
            'ticket_id': x['ticket_id'],
            'direction': x['direction'],
            'first_at': x['device_time'],
            'gate_code': gateCodes[x['gate_id']],
          },
      ],
      'cursor': used.isEmpty ? cursor : used.last.$1,
      'more': pending.length > limit,
      'manifest_version': manifestVersion,
      'event_status': closed ? 'CLOSED' : 'ON_SALE',
      'server_time': serverTime,
    });
  }

  /// docs/02 duplicate resolution (BE-21 computes it on read): per ticket, the admitted row with the earliest
  /// device time is the entry; the later admitted rows are late duplicates.
  (String device, int late) resolve(String ticketId) {
    final admitted = rows.where((x) => x['ticket_id'] == ticketId && x['result'] == 'OK').toList()
      ..sort((a, b) => (a['device_time']! as String).compareTo(b['device_time']! as String));
    return (admitted.first['device']! as String, admitted.length - 1);
  }

  List<String> get paths => [for (final c in calls) c.$2];
}

/// One enrolled scanner in event mode (event 12, manifest v1, K_e loaded), from [device].
final class Device {
  new(this.db, this.container, this.sync, this.scanner);

  final ScannerDatabase db;
  final ProviderContainer container;
  final SyncService sync;
  final ScanService scanner;
  int eventRefreshes = 0;

  Future<List<Scan>> scans() => db.select(db.scans).get();

  Future<UsedData?> used(String ticketId) =>
      (db.select(db.used)..where((u) => u.ticketId.equals(ticketId))).getSingleOrNull();
}

Future<Device> device(
  FakeServer server, {
  String id = devA,
  String? gateId,
  int clockMs = nowMs,
  Map<String, (int, String, String?)> tickets = const {},
  ScannerDatabase? db,
  bool seed = true,
}) async {
  final database = db ?? memoryDatabase();
  if (seed) {
    await seedEventMode(database, tickets);
    await database
        .update(database.config)
        .write(
          ConfigCompanion(
            deviceId: Value(id),
            deviceStatus: const Value('ACTIVE'),
            manifestVersion: const Value(1),
            eventStatus: const Value('ON_SALE'),
            gateId: Value(gateId ?? gateV1),
            lastServerTimeAt: const Value(nowMs),
          ),
        );
  }
  final clock = ServerClock(measured: true, deviceNow: () => DateTime.fromMillisecondsSinceEpoch(clockMs));
  final container = ProviderContainer(
    overrides: [
      databaseProvider.overrideWithValue(database),
      httpClientProvider.overrideWithValue(server.client),
      deviceKeyProvider.overrideWithValue(FakeDeviceKey()),
      serverClockProvider.overrideWithValue(clock),
    ],
  );
  addTearDown(container.dispose);
  await container.read(eventKeyProvider.notifier).store(testKe);
  late final Device d;
  final sync = SyncService(
    database,
    gate: container.read(gateClientProvider),
    eventKey: container.read(eventKeyProvider.notifier),
    refreshEvents: () => d.eventRefreshes++,
  );
  return d = Device(
    database,
    container,
    sync,
    ScanService(database, clock: clock, eventKey: () => container.read(eventKeyProvider), random: Random(id.hashCode)),
  );
}

/// [n] scan rows as the scan path writes them (refused, no ticket), one per second from [fromMs].
Future<List<String>> addScans(ScannerDatabase db, int n, {int fromMs = nowMs, int seed = 1}) async {
  final random = Random(seed);
  final ids = [for (var i = 0; i < n; i++) uuidV7(fromMs + i * 1000, random)];
  await db.batch(
    (b) => b.insertAll(db.scans, [
      for (final (i, id) in ids.indexed)
        ScansCompanion.insert(
          scanId: Value(id),
          gateId: gateV1,
          staffId: staffId,
          direction: 'IN',
          result: 'BAD_SIG',
          deviceTime: fromMs + i * 1000,
        ),
    ]),
  );
  return ids;
}

Future<int> unsynced(ScannerDatabase db) =>
    (db.select(db.scans)..where((s) => s.synced.equals(0))).get().then((r) => r.length);

http.Response serverError() => json({'code': 'INTERNAL_ERROR', 'message': 'x', 'details': <String, Object?>{}}, 500);

http.Response revoked() => json({
  'code': 'DEVICE_NOT_ACTIVE',
  'message': 'x',
  'details': {'status': 'REVOKED'},
}, 403);

/// No jitter: the backoff is exactly 2, 4, 8 … 60 s.
class _NoJitter implements Random {
  @override
  double nextDouble() => 0;

  @override
  bool nextBool() => false;

  @override
  int nextInt(int max) => 0;
}

void main() {
  late FakeServer server;

  // Two scanners means two databases in one test (each on its own in-memory executor).
  driftRuntimeOptions.dontWarnAboutMultipleDatabases = true;

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    server = FakeServer();
  });

  group('batches', () {
    test('1,200 offline scans go in 3 batches, oldest first; none lost, none resent after ack', () async {
      final a = await device(server);
      addTearDown(a.db.close);
      final ids = await addScans(a.db, 1200);

      expect(await a.sync.run(), isTrue);
      expect([for (final c in server.calls) c.$3.length], [500, 500, 200]);
      expect([for (final c in server.calls) ...c.$3], ids, reason: 'oldest first, each once');
      expect(server.rows.length, 1200);
      expect(await unsynced(a.db), 0);

      await a.sync.run();
      expect(server.calls.last.$3, isEmpty, reason: 'the next round is a poll');
      expect(server.rows.length, 1200);
    });

    test('a lost ack: the batch is sent again, stored once, then marked synced', () async {
      final a = await device(server);
      addTearDown(a.db.close);
      await addScans(a.db, 3);
      server.dropNextReply = true; // Stored on the server, the answer never arrives.
      await expectLater(a.sync.run(), throwsA(isA<GateApiError>()));
      expect((server.rows.length, await unsynced(a.db)), (3, 3));

      await a.sync.run();
      expect(server.calls.last.$3, server.calls.first.$3, reason: 'the same scan_ids');
      expect((server.rows.length, await unsynced(a.db)), (3, 0));
    });

    test('a refused row (in errors) is acked too: marked synced, never resent', () async {
      final a = await device(server);
      addTearDown(a.db.close);
      final ids = await addScans(a.db, 3);
      server.refuse.add(ids[1]);
      await a.sync.run();
      expect((server.rows.length, await unsynced(a.db)), (2, 0));
      await a.sync.run();
      expect(server.calls.last.$3, isEmpty);
    });

    test('an acked id outside the sent batch is not marked synced: it goes out with its own batch', () async {
      final a = await device(server);
      addTearDown(a.db.close);
      final ids = await addScans(a.db, 501);
      server.intercept = (r) async {
        server.intercept = null;
        final reply = jsonDecode(utf8.decode((await server._answer(r)).bodyBytes)) as Map<String, Object?>;
        return json({
          ...reply,
          'acked': [...reply['acked']! as List<Object?>, ids.last],
        });
      };
      await a.sync.run();
      expect(server.calls[1].$3, [ids.last]);
      expect(await unsynced(a.db), 0);
    });

    test('more: true without a new cursor stops the round (backoff), no back-to-back loop', () async {
      final a = await device(server);
      addTearDown(a.db.close);
      server.intercept = (r) async {
        final reply = jsonDecode(utf8.decode((await server._answer(r)).bodyBytes)) as Map<String, Object?>;
        return json({...reply, 'more': true});
      };
      await expectLater(a.sync.run(), throwsA(isA<StateError>()));
      expect(server.calls.length, 1);
    });

    test('a reply that leaves a row out: the acked ones are synced, the other stays, the round stops', () async {
      final a = await device(server);
      addTearDown(a.db.close);
      final ids = await addScans(a.db, 3);
      server.intercept = (r) async {
        final reply = jsonDecode(utf8.decode((await server._answer(r)).bodyBytes)) as Map<String, Object?>;
        return json({...reply, 'acked': ids.take(2).toList()});
      };
      await expectLater(a.sync.run(), throwsA(isA<StateError>()));
      expect([for (final s in await a.scans()) s.synced], [1, 1, 0]);
      expect(server.calls.length, 1, reason: 'no tight loop');
    });

    test('marking synced touches only the synced flag; the posted row carries every 04 field', () async {
      final a = await device(server);
      addTearDown(a.db.close);
      await a.db
          .into(a.db.scans)
          .insert(
            ScansCompanion.insert(
              scanId: const Value('0192f0c1-7a3e-7c4d-9b1a-2f3e4d5c6b7a'),
              ticketId: const Value('11111111-1111-1111-1111-111111111100'),
              gateId: gateV1,
              staffId: staffId,
              direction: 'IN',
              result: 'OVERRIDE',
              tokenVersion: const Value(1),
              deviceTime: nowMs,
              overrideReason: const Value('LOST_PHONE'),
              supervisorId: const Value('s-2'),
            ),
          );
      final before = (await a.scans()).single;
      await a.sync.run();
      final after = (await a.scans()).single;
      expect(after.synced, 1);
      expect(after.copyWith(synced: 0), before);
      expect(server.rows.single..remove('device'), {
        'scan_id': '0192f0c1-7a3e-7c4d-9b1a-2f3e4d5c6b7a',
        'ticket_id': '11111111-1111-1111-1111-111111111100',
        'gate_id': gateV1,
        'staff_id': staffId,
        'direction': 'IN',
        'result': 'OVERRIDE',
        'token_version': 1,
        'device_time': '2026-11-07T20:00:10.000Z',
        'override_reason': 'LOST_PHONE',
        'supervisor_id': 's-2',
      });
    });

    test('kill and restart: unsynced rows survive in the database file and go out after', () async {
      final dir = Directory.systemTemp.createTempSync('scanner_sync');
      addTearDown(() => dir.deleteSync(recursive: true));
      final file = File('${dir.path}/scanner.sqlite');
      var db = ScannerDatabase(NativeDatabase(file));
      var a = await device(server, db: db);
      final ids = await addScans(db, 7);
      server.intercept = (_) async => throw const SocketException('offline');
      await expectLater(a.sync.run(), throwsA(isA<GateApiError>()));
      await db.close();

      server.intercept = null;
      db = ScannerDatabase(NativeDatabase(file));
      addTearDown(db.close);
      a = await device(server, db: db, seed: false);
      expect(await unsynced(db), 7);
      await a.sync.run();
      expect(await unsynced(db), 0);
      expect([for (final r in server.rows) r['scan_id']], ids);
    });
  });

  group('used_since', () {
    test('more: the device calls again at once with the new cursor until the server has no more', () async {
      server = FakeServer(limit: 2);
      for (var i = 0; i < 5; i++) {
        server.rows.add({
          'scan_id': 'remote-$i',
          'ticket_id': ticketId(ticketBytes(0x30 + i)),
          'gate_id': gateV2,
          'direction': 'IN',
          'result': 'OK',
          'device_time': rfc3339(nowMs - 60000 + i),
          'device': devB,
        });
      }
      final a = await device(server);
      addTearDown(a.db.close);
      await a.sync.run();
      expect([for (final c in server.calls) c.$4], [0, 2, 4]);
      expect((await a.db.readConfig()).syncCursor, 5);
      final used = await a.db.select(a.db.used).get();
      expect(used.length, 5);
      expect(used.every((u) => u.source == 'REMOTE' && u.gateCode == 'V2'), isTrue);

      await a.sync.run();
      expect(server.calls.last.$4, 5, reason: 'the cursor is kept in config');
    });

    test('a newer manifest_version asks for the events refresh + delta; the same one does not', () async {
      final a = await device(server);
      addTearDown(a.db.close);
      await a.sync.run();
      expect(a.eventRefreshes, 0);
      server.manifestVersion = 2;
      await a.sync.run();
      expect(a.eventRefreshes, 1);
      // Still behind (say the full manifest keeps being rejected): not asked again on every round.
      await a.sync.run();
      await a.sync.run();
      expect(a.eventRefreshes, 1);
      server.manifestVersion = 3;
      await a.sync.run();
      expect(a.eventRefreshes, 2, reason: 'a newer server version asks again');
    });

    test('two offline devices admit the same PDF; after sync the earlier device time wins everywhere', () async {
      final t = ticketBytes(0x21);
      final u = ticketBytes(0x22);
      final tickets = {ticketId(t): (zoneVirage, 'VALID', 'Y. A.'), ticketId(u): (zoneVirage, 'VALID', null)};
      // A at V1 at 20:00:10, B at V2 five seconds earlier by its clock.
      final a = await device(server, gateId: gateV1, tickets: tickets);
      final b = await device(server, id: devB, gateId: gateV2, clockMs: nowMs - 5000, tickets: tickets);
      addTearDown(a.db.close);
      addTearDown(b.db.close);
      final pdf = staticToken(t, zone: zoneVirage);

      // Airplane mode: both green.
      expect((await a.scanner.scan(pdf)).verdict.result, ScanResult.OK);
      expect((await b.scanner.scan(pdf)).verdict.result, ScanResult.OK);

      // Reconnect: A syncs first, B second, then A polls.
      await a.sync.run();
      await b.sync.run();
      await a.sync.run();

      // Server: 1 entered (B, the earlier device time, though it synced second) + 1 late duplicate.
      expect(server.resolve(ticketId(t)), (devB, 1));
      // Both devices now hold B's entry as the first scan of T.
      final onA = (await a.used(ticketId(t)))!;
      expect((onA.firstAt, onA.gateCode, onA.source), (nowMs - 5000, 'V2', 'REMOTE'));
      final onB = (await b.used(ticketId(t)))!;
      expect((onB.firstAt, onB.gateCode, onB.source), (nowMs - 5000, 'V2', 'LOCAL'));

      // Once A has synced an admit, B scanning that PDF gets DÉJÀ UTILISÉ with A's gate and time.
      expect((await a.scanner.scan(staticToken(u, zone: zoneVirage))).verdict.result, ScanResult.OK);
      await a.sync.run();
      await b.sync.run();
      final dup = (await b.scanner.scan(staticToken(u, zone: zoneVirage))).verdict;
      expect((dup.result, dup.firstGateCode, dup.firstAt), (ScanResult.DUPLICATE, 'V1', nowMs));
    });
  });

  group('close', () {
    Future<Device> closing() async {
      final d = await device(server, tickets: {ticketId(ticketBytes(0x11)): (zoneVirage, 'VALID', null)});
      addTearDown(d.db.close);
      await d.db
          .into(d.db.staff)
          .insert(StaffCompanion.insert(staffId: const Value(staffId), label: 'Y. A.', role: 'GATE', pinVerifier: 'v'));
      await d.db
          .into(d.db.used)
          .insert(UsedCompanion.insert(ticketId: 'x', direction: 'IN', firstAt: nowMs, source: 'LOCAL'));
      return d;
    }

    Future<void> expectWiped(Device d) async {
      for (final count in [
        d.db.manifestTickets.count(),
        d.db.used.count(),
        d.db.zones.count(),
        d.db.gates.count(),
        d.db.scans.count(),
      ]) {
        expect(await count.getSingle(), 0);
      }
      expect(await const FlutterSecureStorage().read(key: EventKeyHolder.storageKey), isNull);
      expect(d.container.read(eventKeyProvider), isNull);
    }

    test(
      'CLOSED: drain every unsynced row, then wipe (tables + K_e), then closed-ack, then forget the event',
      () async {
        final d = await closing();
        await addScans(d.db, 1200);
        server.closed = true;
        var checkedAtAck = false;
        server.intercept = (r) async {
          if (r.url.path.endsWith('/closed-ack')) {
            await expectWiped(d);
            checkedAtAck = true;
          }
          return null;
        };

        expect(await d.sync.run(), isTrue);
        expect(server.paths, ['scans', 'scans', 'scans', 'closed-ack']);
        expect(server.rows.length, 1200);
        expect(server.acked, {devA});
        expect(checkedAtAck, isTrue);
        final config = await d.db.readConfig();
        expect(
          (
            config.eventId,
            config.eventStatus,
            config.gateId,
            config.staffId,
            config.manifestVersion,
            config.syncCursor,
          ),
          (null, null, null, null, 0, 0),
        );
        expect(config.deviceId, devA, reason: 'still enrolled');
        expect(await d.db.staff.count().getSingle(), 1, reason: 'staff are per organizer');
        expect(d.eventRefreshes, 1, reason: 'back to the Événement screen with a fresh list');
      },
    );

    test('wipe only once unsynced = 0: a scan written during the drain goes out before the wipe', () async {
      final d = await closing();
      await addScans(d.db, 2);
      server.closed = true;
      var late = <String>[];
      server.intercept = (r) async {
        if (late.isEmpty && r.url.path.endsWith('/scans')) {
          late = await addScans(d.db, 1, fromMs: nowMs + 9000, seed: 9);
        }
        return null;
      };
      await d.sync.run();
      expect(server.paths, ['scans', 'scans', 'closed-ack']);
      expect(server.calls[1].$3, late);
      expect(server.rows.length, 3);
    });

    test('a lost closed-ack: the event stays wiped and is acked by the next round, without posting scans', () async {
      final d = await closing();
      await addScans(d.db, 2);
      server
        ..closed = true
        ..intercept = (r) async => r.url.path.endsWith('/closed-ack') ? throw const SocketException('offline') : null;
      await expectLater(d.sync.run(), throwsA(isA<GateApiError>()));
      await expectWiped(d);
      final config = await d.db.readConfig();
      expect((config.eventId, config.eventStatus), (eventId, 'CLOSED'));

      server.intercept = null;
      await d.sync.run();
      expect(server.paths, ['scans', 'closed-ack', 'closed-ack']);
      expect((await d.db.readConfig()).eventId, isNull);
    });
  });

  group('revoked', () {
    test('403 REVOKED mid-sync: everything wiped (K_e, tables, config), the loop stops calling', () async {
      final d = await device(server);
      addTearDown(d.db.close);
      await addScans(d.db, 1200);
      server.intercept = (r) async => server.calls.length == 2 ? revoked() : null;

      await expectLater(
        d.sync.run(),
        throwsA(isA<GateApiError>().having((e) => e.code, 'code', GateApiError.notActive)),
      );
      expect(server.calls.length, 2);
      for (final count in [d.db.scans.count(), d.db.used.count(), d.db.manifestTickets.count()]) {
        expect(await count.getSingle(), 0);
      }
      final config = await d.db.readConfig();
      expect((config.deviceId, config.eventId), (null, null));
      expect(await const FlutterSecureStorage().readAll(), isEmpty);
      expect(d.container.read(eventKeyProvider), isNull);

      expect(await d.sync.run(), isFalse);
      expect(server.calls.length, 2, reason: 'no call once wiped');
    });

    test('a wipe landing while a batch is in flight: the reply writes nothing back', () async {
      final d = await device(server);
      addTearDown(d.db.close);
      await addScans(d.db, 3);
      server.rows.add({
        'scan_id': 'remote',
        'ticket_id': ticketId(ticketBytes(0x40)),
        'gate_id': gateV2,
        'direction': 'IN',
        'result': 'OK',
        'device_time': rfc3339(nowMs),
        'device': devB,
      });
      server.intercept = (r) async {
        await d.db.wipeAll(); // Another gate call got REVOKED meanwhile.
        return null;
      };
      await expectLater(d.sync.run(), throwsA(isA<StateError>()));
      expect(await d.db.used.count().getSingle(), 0);
      expect((await d.db.readConfig()).syncCursor, 0);
    });
  });

  group('loop', () {
    test('backoff: 2, 4, 8 … 60 s with jitter of at most 20 %', () {
      expect([for (var n = 1; n <= 8; n++) backoff(n, 0).inSeconds], [2, 4, 8, 16, 32, 60, 60, 60]);
      expect(backoff(1, 0.999).inMilliseconds, inInclusiveRange(1600, 2000));
      expect(backoff(40, 0.5).inSeconds, 54);
    });

    test('repeated 500s retry at 2, 4, 8 … 60 s; back online, every 10 s and right after a scan', () {
      fakeAsync((async) {
        final times = <int>[];
        server.intercept = (r) async {
          times.add(async.elapsed.inSeconds);
          return serverError();
        };
        late Device d;
        unawaited(device(server).then((v) => d = v));
        async.flushMicrotasks();
        final container = ProviderContainer(
          overrides: [
            databaseProvider.overrideWithValue(d.db),
            syncServiceProvider.overrideWithValue(d.sync),
            syncControllerProvider.overrideWith(() => SyncController(random: _NoJitter())),
          ],
        )..listen(syncControllerProvider, (_, _) {});
        async.elapse(const Duration(seconds: 200));
        expect(times, [0, 2, 6, 14, 30, 62, 122, 182]);
        expect(container.read(syncControllerProvider).online, isFalse);

        server.intercept = (r) async {
          times.add(async.elapsed.inSeconds);
          return null;
        };
        times.clear();
        async.elapse(const Duration(seconds: 61));
        expect(times, [242, 252], reason: 'the 60 s retry finds the network, then every 10 s');
        expect(container.read(syncControllerProvider).online, isTrue);

        times.clear();
        unawaited(addScans(d.db, 1, seed: 3));
        async.elapse(Duration.zero);
        expect(times, [261], reason: 'right after the scan, not at the next tick');
        expect(server.calls.last.$3.length, 1);

        container.dispose();
        unawaited(d.db.close());
        async.flushMicrotasks();
      });
    });
  });
}
