import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:drift/drift.dart' hide isNotNull, isNull;
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
import 'package:jomhor_scanner/features/enrol/enrol_service.dart';
import 'package:jomhor_scanner/features/event/event_service.dart';
import 'package:jomhor_scanner/features/home/device_status.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/features/staff/staff_session.dart';
import 'package:jomhor_scanner/features/supervisor/supervisor_service.dart';
import 'package:jomhor_scanner/features/sync/sync_service.dart';

import 'gate_fixtures.dart';
import 'helpers.dart';
import 'scan_fixtures.dart' show rotatingToken, slotOf, staticToken;

/// The scanner exit test of plan lot-2 §1 / FL-14, end to end against a fake gate API serving the backend's
/// vectors-signed manifests (docs/gate-vectors.json): enrol → approve → event + manifest → unlock → airplane
/// mode (app QR, PDF QR, wrong gate, supervisor override and gate change) → reconnect → sync → close → drain,
/// wipe, ack.

const devA = '0b8e7a52-0000-4000-8000-00000000d001';
const devB = '0b8e7a52-0000-4000-8000-00000000d002';
const gate2 = 'a1a1a1a1-0000-4000-8000-000000000002';
const Map<String, String> gateCodes = {gate1: 'G1', gate2: 'G2', 'a1a1a1a1-0000-4000-8000-000000000009': 'G9'};

/// Tickets of the vectors' manifest v1 (event 12): G1 serves zone 2, G2 zones 1 and 3.
const yassine = '10000000-0000-4000-8000-000000000001'; // zone 1, VALID, Y. A.
const pelouse = '80000000-0000-4000-8000-000000000002'; // zone 2, VALID
const blockedTicket = 'c0000000-0000-4000-8000-000000000004'; // zone 1, BLOCKED, M. E.
/// Added by the v2 delta: zone 3, VALID, K. O.
const kO = '20000000-0000-4000-8000-000000000005';

Uint8List bytesOf(String uuid) {
  final hex = uuid.replaceAll('-', '');
  return Uint8List.fromList([for (var i = 0; i < 32; i += 2) int.parse(hex.substring(i, i + 2), radix: 16)]);
}

/// docs/04 §Gate in memory: enrol, `GET /gate/events` (403 until approved), the manifest (v1 full with K_e, v2
/// delta since 1), scans (`ON CONFLICT DO NOTHING`, every row acked, `used_since` = other devices' admits after
/// the cursor) and closed-ack. [offline] = airplane mode: every request fails at the socket.
class GateServer {
  bool offline = false;
  bool approved = false;
  bool closed = false;
  int manifestVersion = 1;

  /// Stored scan rows in `seq` order, with the posting device under `device`.
  final rows = <Map<String, Object?>>[];

  /// `METHOD /path` of every request the device tried, reachable or not.
  final calls = <String>[];
  final closedAcks = <String>{};

  http.Client get client => MockClient(_handle);

  Future<http.Response> _handle(http.Request r) async {
    final path = r.url.path.replaceFirst(RegExp('^.*?/gate/'), '/gate/');
    calls.add('${r.method} $path');
    if (offline) throw const SocketException('airplane mode');
    // Echoing the signed device time keeps the device's clock offset at 0.
    final serverTime = rfc3339(int.parse(r.headers['X-Device-Time']!));
    final device = r.headers['X-Device-Id'];
    final body = r.body.isEmpty ? null : jsonDecode(r.body) as Map<String, Object?>;
    switch ((r.method, path)) {
      case ('POST', '/gate/devices/enrol'):
        expect(device, isNull);
        return json({
          'device_id': devA,
          'status': 'PENDING',
          'fingerprint': 'AB12 CD34',
          'manifest_public_key': vectorKey,
          'server_time': serverTime,
        }, 201);
      case ('GET', '/gate/events'):
        if (!approved) {
          return json({
            'code': 'DEVICE_NOT_ACTIVE',
            'message': 'x',
            'details': {'status': 'PENDING'},
            'server_time': serverTime,
          }, 403);
        }
        return json({...eventsBody(manifestVersion: manifestVersion), 'server_time': serverTime});
      case ('GET', '/gate/events/12/manifest'):
        final m = r.url.queryParameters['since'] == null ? manifestV1 : manifestV2;
        return json({
          ...envelope(
            m['payload']! as String,
            m['signature']! as String,
            eventKey: m == manifestV1 ? testEventKey : null,
          ),
          'server_time': serverTime,
        });
      case ('POST', '/gate/events/12/closed-ack'):
        if (!closed) return json({'code': 'EVENT_NOT_CLOSED', 'message': 'x', 'details': <String, Object?>{}}, 409);
        closedAcks.add(device!);
        return http.Response('', 204);
      case ('POST', '/gate/events/12/scans'):
        final scans = [for (final s in body!['scans']! as List<Object?>) s! as Map<String, Object?>];
        expect(scans.length, lessThanOrEqualTo(syncBatchSize));
        for (final s in scans) {
          if (!rows.any((x) => x['scan_id'] == s['scan_id'])) rows.add({...s, 'device': device});
        }
        final cursor = body['cursor']! as int;
        final used = [
          for (final (i, x) in rows.indexed)
            if (i + 1 > cursor && x['device'] != device && (x['result'] == 'OK' || x['result'] == 'OVERRIDE'))
              (i + 1, x),
        ];
        return json({
          'acked': [for (final s in scans) s['scan_id']],
          'errors': const <Object?>[],
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
          'more': false,
          'manifest_version': manifestVersion,
          'event_status': closed ? 'CLOSED' : 'ON_SALE',
          'server_time': serverTime,
        });
    }
    return json({'code': 'NOT_FOUND', 'message': path, 'details': <String, Object?>{}}, 404);
  }

  List<Map<String, Object?>> rowsOf(String device) => [
    for (final x in rows)
      if (x['device'] == device) x,
  ];
}

void main() {
  test('exit: enrol → approve → manifest → unlock → airplane mode (app QR, PDF QR, wrong gate, override, gate '
      'change) → reconnect → sync incl. the override → close → drain, wipe, ack', () async {
    FlutterSecureStorage.setMockInitialValues({});
    final db = memoryDatabase();
    addTearDown(db.close);
    final server = GateServer();
    var now = deviceNowMs;
    final container = ProviderContainer(
      overrides: [
        databaseProvider.overrideWithValue(db),
        httpClientProvider.overrideWithValue(server.client),
        deviceKeyProvider.overrideWithValue(FakeDeviceKey()),
        serverClockProvider.overrideWithValue(ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(now))),
      ],
    );
    addTearDown(container.dispose);
    final events = container.read(eventServiceProvider);
    final scanner = container.read(scanServiceProvider);
    final supervisor = container.read(supervisorServiceProvider);
    final staff = container.read(staffSessionProvider);
    var manifestRefreshes = 0;
    final sync = SyncService(
      db,
      gate: container.read(gateClientProvider),
      eventKey: container.read(eventKeyProvider.notifier),
      refreshEvents: () => manifestRefreshes++,
    );
    Future<GateScreen> screen() async =>
        gateScreenOf(await db.readConfig(), keyLoaded: container.read(eventKeyProvider) != null);

    // Device B, already synced, admitted K. O. at G2 one minute before anything here.
    const bTime = deviceNowMs - 60000;
    server.rows.add({
      'scan_id': uuidV7(bTime, Random(9)),
      'ticket_id': kO,
      'gate_id': gate2,
      'staff_id': 's-1',
      'direction': 'IN',
      'result': 'OK',
      'token_version': 1,
      'device_time': rfc3339(bTime),
      'device': devB,
    });

    // 1. Enrol (B6 QR), PENDING until the admin approves.
    await container.read(enrolServiceProvider).enrol('eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJlbnJvbCJ9.c2ln', 'Tablette 3');
    expect((await db.readConfig()).deviceStatus, 'PENDING');
    expect((await db.readConfig()).manifestPublicKey, vectorKey);
    await expectLater(
      events.refresh(),
      throwsA(isA<GateApiError>().having((e) => e.code, 'code', 'DEVICE_NOT_ACTIVE')),
    );
    expect(await screen(), GateScreen.enrol);

    // 2. Approved: assignment (event 12, G1), staff with PIN verifiers, then the signed manifest + K_e.
    server.approved = true;
    final assignments = await events.refresh();
    expect((await db.readConfig()).deviceStatus, 'ACTIVE');
    await events.select(assignments.single);
    expect((await events.download()).version, 1);
    expect(container.read(eventKeyProvider)!.bytes, testEventKey);
    expect(await screen(), GateScreen.unlock);

    // 3. The gate agent unlocks (PIN on the device).
    expect((await staff.unlock('s-1', '4821')).ok, isTrue);
    expect(await screen(), GateScreen.scan);

    // 4. Airplane mode.
    server.offline = true;
    final callsBefore = server.calls.length;

    now += 1000;
    final app = await scanner.scan(rotatingToken(bytesOf(pelouse), slot: slotOf(now), zone: 2, key: testEventKey));
    expect((app.verdict.result, app.verdict.tokenVersion), (ScanResult.OK, versionRotating));

    now += 1000;
    final wrongGate = await scanner.scan(staticToken(bytesOf(yassine), key: testEventKey));
    expect(wrongGate.verdict.result, ScanResult.WRONG_ZONE);
    expect(wrongGate.verdict.correctGates, ['G2']);

    now += 1000;
    final (_, grant) = await staff.verifySupervisor('s-2', '0000');
    final matches = await supervisor.find(grant!, '0000 0004');
    expect([for (final m in matches) (m.ticketId, m.status, m.initials)], [(blockedTicket, 'BLOCKED', 'M. E.')]);
    final override = await supervisor.override(grant, OverrideReason.idChecked, matches.single.ticketId);
    expect(override.verdict.result, ScanResult.OVERRIDE);

    await supervisor.changeGate(grant, gate2);
    now += 1000;
    final pdf = await scanner.scan(staticToken(bytesOf(yassine), key: testEventKey));
    expect((pdf.verdict.result, pdf.verdict.tokenVersion), (ScanResult.OK, versionStatic));

    expect(server.calls.length, callsBefore, reason: 'no network call on the scan or supervisor path');
    await expectLater(sync.run(), throwsA(isA<GateApiError>()));
    expect(await db.scans.count(where: (s) => s.synced.equals(0)).getSingle(), 4, reason: 'nothing dropped');

    // 5. Reconnect: the batch goes up, B's admit comes down, the server announces manifest v2.
    server
      ..offline = false
      ..manifestVersion = 2;
    expect(await sync.run(), isTrue);
    expect(manifestRefreshes, 1);
    final posted = server.rowsOf(devA);
    expect([for (final x in posted) x['result']], ['OK', 'WRONG_ZONE', 'OVERRIDE', 'OK']);
    expect(posted[2], {
      'scan_id': override.scanId,
      'ticket_id': blockedTicket,
      'gate_id': gate1,
      'staff_id': 's-1',
      'direction': 'IN',
      'result': 'OVERRIDE',
      'device_time': rfc3339(deviceNowMs + 3000),
      'override_reason': 'ID_CHECKED',
      'supervisor_id': 's-2',
      'device': devA,
    });
    expect(
      [for (final x in posted) (x['gate_id'], x['token_version'])],
      [(gate1, 2), (gate1, 1), (gate1, null), (gate2, 1)],
    );
    expect(await db.scans.count(where: (s) => s.synced.equals(0)).getSingle(), 0);

    // The v2 delta brings K. O.'s ticket; B's admit makes it a DUPLICATE here, with B's gate and time.
    expect((await events.download()).version, 2);
    now += 1000;
    final dup = await scanner.scan(staticToken(bytesOf(kO), zone: 3, key: testEventKey));
    expect((dup.verdict.result, dup.verdict.firstGateCode, dup.verdict.firstAt), (ScanResult.DUPLICATE, 'G2', bTime));

    // 6. The admin closes the event: drain, wipe, ack.
    server.closed = true;
    expect(await sync.run(), isTrue);
    expect([for (final x in server.rowsOf(devA)) x['result']], ['OK', 'WRONG_ZONE', 'OVERRIDE', 'OK', 'DUPLICATE']);
    expect(server.closedAcks, {devA});
    expect(server.calls.last, 'POST /gate/events/12/closed-ack');
    for (final table in <TableInfo<Table, Object?>>[db.manifestTickets, db.used, db.zones, db.gates, db.scans]) {
      expect(await db.select(table).get(), isEmpty, reason: table.actualTableName);
    }
    expect(container.read(eventKeyProvider), isNull);
    expect(await const FlutterSecureStorage().read(key: EventKeyHolder.storageKey), isNull);
    final config = await db.readConfig();
    expect((config.eventId, config.gateId, config.staffId, config.manifestVersion), (null, null, null, 0));
    expect(await screen(), GateScreen.event);
  });
}
