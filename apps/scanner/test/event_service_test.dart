import 'dart:convert';
import 'dart:io';

import 'package:drift/drift.dart' hide isNotNull, isNull;
import 'package:drift/native.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/event/event_service.dart';
import 'package:jomhor_scanner/features/event/manifest.dart';
import 'package:jomhor_scanner/features/home/device_status.dart';

import 'gate_fixtures.dart';
import 'helpers.dart';

const storage = FlutterSecureStorage();

/// Everything a manifest write could touch, to compare before and after a refused one.
Future<Object> snapshot(ScannerDatabase db) async => [
  for (final z in await db.select(db.zones).get()) z.toJson(),
  for (final g in await db.select(db.gates).get()) g.toJson(),
  for (final t in await db.select(db.manifestTickets).get()) t.toJson(),
  (await db.readConfig()).toJson(),
  await storage.read(key: EventKeyHolder.storageKey),
];

http.Response manifest(Map<String, Object?> body) => json(body);

void main() {
  late ScannerDatabase db;
  late FakeGate gate;

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    db = memoryDatabase();
    addTearDown(db.close);
    gate = FakeGate();
  });

  EventService service() => gateContainer(db, gate).read(eventServiceProvider);

  /// The vectors' v1 full manifest (with K_e) for a request without `since`, [delta] otherwise.
  void serve({Map<String, Object?>? delta, http.Response? full}) {
    gate.handler = (r) async {
      if (r.url.queryParameters['since'] == null) {
        return full ??
            manifest(
              envelope(manifestV1['payload']! as String, manifestV1['signature']! as String, eventKey: testEventKey),
            );
      }
      return manifest(delta!);
    };
  }

  group('golden: docs/gate-vectors.json manifest', () {
    test('every vector verifies with its key and parses; one changed byte does not verify', () async {
      final all = vectors('manifest');
      expect(all, isNotEmpty);
      for (final v in all) {
        final (key, payload, sig) = (v['public_key']! as String, v['payload']! as String, v['signature']! as String);
        expect(await verifyManifestSignature(publicKey: key, payload: payload, signature: sig), isTrue);
        final m = await openManifest(publicKey: key, payload: payload, signature: sig);
        expect(m.eventId, 12);
        final tampered = payload.replaceFirst('VALID', 'VALIX');
        expect(await verifyManifestSignature(publicKey: key, payload: tampered, signature: sig), isFalse);
      }
    });

    test('v1 full: zones, gates (an empty zone list), every status, empty initials, accents and Arabic', () async {
      final m = parseManifest(manifestV1['payload']! as String);
      expect((m.version, m.since, m.rows, m.reentry, m.full), (1, 0, 4, 'NONE', true));
      expect(m.zones.map((z) => (z.zoneId.value, z.code.value, z.name.value)), [
        (1, 'TRIB', 'Tribune Présidentielle'),
        (2, 'PEL', 'Pelouse A'),
        (3, 'VIR', 'الشمال'),
      ]);
      expect(m.gates.map((g) => (g.code.value, g.name.value, g.zoneIds.value)), [
        ('G1', 'Porte 1', '[2]'),
        ('G2', 'Porte 2  B', '[1,3]'),
        ('G9', 'Porte 9', '[]'),
      ]);
      expect(m.tickets.map((t) => (t.ticketNo.value, t.status.value, t.holderInitials.value)), [
        ('00000001', 'VALID', 'Y. A.'),
        ('E82C3301', 'REFUNDED', 'S. B.'),
        ('00000002', 'VALID', null),
        ('00000004', 'BLOCKED', 'M. E.'),
      ]);
    });

    test('a payload that is not the docs/04 format is refused', () {
      for (final bad in [
        '',
        'JMH-MANIFEST\t2\n',
        (manifestV1['payload']! as String).replaceFirst('rows\t4', 'rows\t5'),
        (manifestV1['payload']! as String).replaceFirst('Z\t1\t', 'X\t1\t'),
        '${manifestV1['payload']}T\tshort\t1\tVALID\t\n',
      ]) {
        expect(() => parseManifest(bad), throwsA(isA<ManifestRejected>()), reason: bad);
      }
    });
  });

  group('download', () {
    test('full: verified, then zones, gates, tickets and version in one go; K_e in secure storage only', () async {
      await seedActiveDevice(db);
      serve();
      final container = gateContainer(db, gate);
      final m = await container.read(eventServiceProvider).download();

      expect(m.version, 1);
      expect(gate.manifestSince, [null]);
      expect(await db.select(db.zones).get(), hasLength(3));
      expect(await db.select(db.gates).get(), hasLength(3));
      expect(await db.select(db.manifestTickets).get(), hasLength(4));
      expect((await db.readConfig()).manifestVersion, 1);
      expect(await storage.read(key: EventKeyHolder.storageKey), base64Url.encode(testEventKey));
      expect(container.read(eventKeyProvider)!.bytes, testEventKey);
    });

    test('delta from the local version (vectors v2 since 1): upserts, version 2, row count 5', () async {
      await seedActiveDevice(db);
      serve(delta: envelope(manifestV2['payload']! as String, manifestV2['signature']! as String));
      final container = gateContainer(db, gate);
      final events = container.read(eventServiceProvider);
      await events.download();
      await events.download();

      expect(gate.manifestSince, [null, '1']);
      expect((await db.readConfig()).manifestVersion, 2);
      final tickets = {for (final t in await db.select(db.manifestTickets).get()) t.ticketId: t.status};
      expect(tickets, hasLength(5));
      expect(tickets['80000000-0000-4000-8000-000000000002'], 'CANCELLED');
      expect(tickets['20000000-0000-4000-8000-000000000005'], 'VALID');
    });

    test('a tampered full payload is refused: nothing written, no K_e stored', () async {
      await seedActiveDevice(db);
      final payload = (manifestV1['payload']! as String).replaceFirst('BLOCKED', 'VALID\t');
      serve(full: manifest(envelope(payload, manifestV1['signature']! as String, eventKey: testEventKey)));
      final before = await snapshot(db);

      await expectLater(service().download(), throwsA(isA<ManifestRejected>()));
      expect(await snapshot(db), before);
      expect(await storage.read(key: EventKeyHolder.storageKey), isNull);
    });

    test('a tampered delta is refused: the last good manifest is kept (tables and version unchanged)', () async {
      await seedActiveDevice(db);
      final payload = (manifestV2['payload']! as String).replaceFirst('CANCELLED', 'VALID');
      serve(delta: envelope(payload, manifestV2['signature']! as String));
      final events = gateContainer(db, gate).read(eventServiceProvider);
      await events.download();
      final before = await snapshot(db);

      await expectLater(events.download(), throwsA(isA<ManifestRejected>()));
      expect(await snapshot(db), before);
      expect((await db.readConfig()).manifestVersion, 1);
    });

    test('a manifest signed by another key than the pinned one is refused', () async {
      await seedActiveDevice(db);
      final (_, sign) = await testSigner();
      final payload = manifestV1['payload']! as String;
      serve(full: manifest(envelope(payload, await sign(payload), eventKey: testEventKey)));
      await expectLater(service().download(), throwsA(isA<ManifestRejected>()));
      expect(await db.select(db.manifestTickets).get(), isEmpty);
    });

    test(
      'a full manifest without a 32-byte event_key, or whose envelope differs from the header, is refused',
      () async {
        await seedActiveDevice(db);
        final (payload, sig) = (manifestV1['payload']! as String, manifestV1['signature']! as String);
        for (final body in [
          envelope(payload, sig),
          envelope(payload, sig, eventKey: testEventKey.sublist(0, 16)),
          {...envelope(payload, sig, eventKey: testEventKey), 'version': 9},
          {...envelope(payload, sig, eventKey: testEventKey), 'event_id': 13},
        ]) {
          serve(full: manifest(body));
          await expectLater(service().download(), throwsA(isA<ManifestRejected>()), reason: '$body');
        }
        expect(await db.select(db.manifestTickets).get(), isEmpty);
        expect((await db.readConfig()).manifestVersion, 0);
      },
    );

    test('a delta with the wrong since → full refetch', () async {
      await seedActiveDevice(db, manifestKey: (await testSigner()).$1);
      final (_, sign) = await testSigner();
      final v1 = manifestV1['payload']! as String;
      final wrongSince = (manifestV2['payload']! as String).replaceFirst('since\t1', 'since\t3');
      gate.handler = (r) async => r.url.queryParameters['since'] == null
          ? manifest(envelope(v1, await sign(v1), eventKey: testEventKey))
          : manifest(envelope(wrongSince, await sign(wrongSince)));
      final events = gateContainer(db, gate).read(eventServiceProvider);
      await events.download();
      final m = await events.download();

      expect(gate.manifestSince, [null, '1', null]);
      expect((m.full, (await db.readConfig()).manifestVersion), (true, 1));
    });

    test('a delta whose row count does not match is rolled back whole, then the full manifest is refetched', () async {
      await seedActiveDevice(db, manifestKey: (await testSigner()).$1);
      final (_, sign) = await testSigner();
      final v1 = manifestV1['payload']! as String;
      final badRows = (manifestV2['payload']! as String).replaceFirst('rows\t5', 'rows\t6');
      var fullFails = false;
      gate.handler = (r) async {
        if (r.url.queryParameters['since'] != null) return manifest(envelope(badRows, await sign(badRows)));
        if (fullFails) return json({'code': 'INTERNAL_ERROR', 'message': 'x', 'details': <String, Object?>{}}, 500);
        return manifest(envelope(v1, await sign(v1), eventKey: testEventKey));
      };
      final events = gateContainer(db, gate).read(eventServiceProvider);
      await events.download();
      final before = await snapshot(db);

      // The refetch fails: what is left is exactly v1, so the delta's upserts and venue rows were rolled back.
      fullFails = true;
      await expectLater(events.download(), throwsA(isA<GateApiError>()));
      expect(await snapshot(db), before);
      expect(gate.manifestSince, [null, '1', null]);

      fullFails = false;
      final m = await events.download();
      expect((m.full, m.version), (true, 1));
      expect(gate.manifestSince, [null, '1', null, '1', null]);
    });

    test('without K_e in memory (restart, storage lost) the device asks for a full manifest', () async {
      await seedActiveDevice(db);
      serve();
      await gateContainer(db, gate).read(eventServiceProvider).download();
      FlutterSecureStorage.setMockInitialValues({});
      final container = gateContainer(db, gate);
      await container.read(eventServiceProvider).restoreKey();
      expect(container.read(eventKeyProvider), isNull);
      await container.read(eventServiceProvider).download();
      expect(gate.manifestSince, [null, null]);
      expect(container.read(eventKeyProvider), isNotNull);
    });

    test('restoreKey loads the stored K_e into memory at start', () async {
      await seedActiveDevice(db);
      serve();
      await gateContainer(db, gate).read(eventServiceProvider).download();
      final restarted = gateContainer(db, gate);
      expect(restarted.read(eventKeyProvider), isNull);
      await restarted.read(eventServiceProvider).restoreKey();
      expect(restarted.read(eventKeyProvider)!.bytes, testEventKey);
    });

    test('MANIFEST_NOT_READY comes back with its available_at', () async {
      await seedActiveDevice(db);
      serve(
        full: json({
          'code': 'MANIFEST_NOT_READY',
          'message': 'x',
          'details': {'available_at': '2026-11-07T17:00:00Z'},
        }, 409),
      );
      await expectLater(
        service().download(),
        throwsA(isA<GateApiError>().having((e) => e.details['available_at'], 'available_at', '2026-11-07T17:00:00Z')),
      );
    });
  });

  test('the SQLite file never contains K_e (raw, base64, base64url or hex)', () async {
    final dir = await Directory.systemTemp.createTemp('scanner_ke');
    addTearDown(() => dir.delete(recursive: true));
    final file = File('${dir.path}/scanner.sqlite');
    final fileDb = ScannerDatabase(NativeDatabase(file));
    await seedActiveDevice(fileDb);
    serve(delta: envelope(manifestV2['payload']! as String, manifestV2['signature']! as String));
    final events = gateContainer(fileDb, gate).read(eventServiceProvider);
    await events.download();
    await events.download();
    expect(await fileDb.manifestTickets.count().getSingle(), 5);
    await fileDb.close();

    final files = dir.listSync().whereType<File>().toList();
    expect(files.map((f) => f.uri.pathSegments.last), contains('scanner.sqlite'));
    expect(file.lengthSync(), greaterThan(0));
    final hex = testEventKey.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    for (final f in files) {
      final bytes = f.readAsBytesSync();
      final text = latin1.decode(bytes);
      expect(_indexOf(bytes, testEventKey), -1, reason: f.path);
      for (final form in [base64.encode(testEventKey), b64(testEventKey), hex, hex.toUpperCase()]) {
        expect(text.contains(form), isFalse, reason: '${f.path}: $form');
      }
    }
  });

  group('review: manifests that must never apply', () {
    test('a full manifest older than the local version is refused, nothing written', () async {
      await seedActiveDevice(db);
      await db.update(db.config).write(const ConfigCompanion(manifestVersion: Value(3)));
      serve();
      final before = await snapshot(db);
      await expectLater(
        service().download(),
        throwsA(isA<ManifestRejected>().having((e) => e.reason, 'reason', 'version')),
      );
      expect(await snapshot(db), before);
    });

    test('a signed delta that does not move forward (version <= since) is refused', () async {
      final (key, sign) = await testSigner();
      await seedActiveDevice(db, manifestKey: key);
      final v1 = manifestV1['payload']! as String;
      final stale = (manifestV2['payload']! as String).replaceFirst('version\t2', 'version\t1');
      gate.handler = (r) async => r.url.queryParameters['since'] == null
          ? manifest(envelope(v1, await sign(v1), eventKey: testEventKey))
          : manifest(envelope(stale, await sign(stale)));
      final events = gateContainer(db, gate).read(eventServiceProvider);
      await events.download();
      final before = await snapshot(db);
      await expectLater(
        events.download(),
        throwsA(isA<ManifestRejected>().having((e) => e.reason, 'reason', 'version')),
      );
      expect(await snapshot(db), before);
    });

    test('a correctly signed delta whose header names another event is refused', () async {
      final (key, sign) = await testSigner();
      await seedActiveDevice(db, manifestKey: key);
      final v1 = manifestV1['payload']! as String;
      final other = (manifestV2['payload']! as String).replaceFirst('event\t12', 'event\t13');
      gate.handler = (r) async => r.url.queryParameters['since'] == null
          ? manifest(envelope(v1, await sign(v1), eventKey: testEventKey))
          : manifest(envelope(other, await sign(other)));
      final events = gateContainer(db, gate).read(eventServiceProvider);
      await events.download();
      final before = await snapshot(db);
      await expectLater(events.download(), throwsA(isA<ManifestRejected>()));
      expect(await snapshot(db), before);
    });

    test('a delta answering a full request is refused (not a crash)', () async {
      await seedActiveDevice(db);
      serve(full: manifest(envelope(manifestV2['payload']! as String, manifestV2['signature']! as String)));
      await expectLater(
        service().download(),
        throwsA(isA<ManifestRejected>().having((e) => e.reason, 'reason', 'since')),
      );
      expect(await db.select(db.manifestTickets).get(), isEmpty);
    });

    test('a revocation wipe landing during a download wins: nothing applied, no K_e stored', () async {
      await seedActiveDevice(db);
      gate.handler = (r) async {
        await db.wipeAll(); // A concurrent sync got REVOKED while the manifest was in flight.
        return manifest(
          envelope(manifestV1['payload']! as String, manifestV1['signature']! as String, eventKey: testEventKey),
        );
      };
      final container = gateContainer(db, gate);
      await expectLater(container.read(eventServiceProvider).download(), throwsA(isA<ManifestRejected>()));
      expect(await db.select(db.manifestTickets).get(), isEmpty);
      expect((await db.readConfig()).manifestVersion, 0);
      expect(await storage.read(key: EventKeyHolder.storageKey), isNull);
      expect(container.read(eventKeyProvider), isNull);
    });
  });

  test('a failing revocation wipe still surfaces the original 403', () async {
    final client = GateClient(
      baseUrl: 'https://api.jomhor.test/v1',
      key: FakeDeviceKey(),
      http:
          (gate
                ..handler = (_) async => json({
                  'code': 'DEVICE_NOT_ACTIVE',
                  'message': 'x',
                  'details': {'status': 'REVOKED'},
                }, 403))
              .client,
      clock: ServerClock(),
      deviceId: () async => 'd',
      onRevoked: () async => throw StateError('disk full'),
    );
    await expectLater(
      client.get('/gate/events'),
      throwsA(isA<GateApiError>().having((e) => (e.status, e.code), 'error', (403, 'DEVICE_NOT_ACTIVE'))),
    );
  });

  group('clock drift (docs/02 §Clocks: above 20 s, event mode refused)', () {
    final assignment = Assignment.fromJson((eventsBody()['events']! as List<Object?>).first! as Map<String, Object?>);

    Future<void> withOffset(int ms) async {
      await seedActiveDevice(db, selected: false);
      await db.update(db.config).write(ConfigCompanion(serverTimeOffsetMs: Value(ms)));
    }

    test('offset 21 s: selecting the event is refused, nothing selected', () async {
      await withOffset(21000);
      await expectLater(service().select(assignment), throwsA(isA<ClockDrift>()));
      expect((await db.readConfig()).eventId, isNull);
    });

    test('offset -21 s: refused too (drift is the absolute offset)', () async {
      await withOffset(-21000);
      await expectLater(service().select(assignment), throwsA(isA<ClockDrift>()));
    });

    test('offset 19 s: allowed', () async {
      await withOffset(19000);
      await service().select(assignment);
      final config = await db.readConfig();
      expect((config.eventId, config.gateId, config.eventTimezone), (12, gate1, 'Africa/Casablanca'));
    });

    test('a loaded event drops back to the event screen when the offset goes above 20 s', () async {
      await seedActiveDevice(db);
      await db.update(db.config).write(const ConfigCompanion(manifestVersion: Value(1)));
      Future<GateScreen> screenAt(int offset) async {
        await db.update(db.config).write(ConfigCompanion(serverTimeOffsetMs: Value(offset)));
        return gateScreenOf(await db.readConfig(), keyLoaded: true);
      }

      expect(await screenAt(19000), GateScreen.unlock);
      expect(await screenAt(21000), GateScreen.event);
      expect(await screenAt(-21000), GateScreen.event);
      await db.update(db.config).write(const ConfigCompanion(lastServerTimeAt: Value(null)));
      expect(gateScreenOf(await db.readConfig(), keyLoaded: true), GateScreen.event);
    });
  });

  test('gateScreenOf: enrolment until ACTIVE, event mode until manifest + K_e, then the lock', () async {
    var c = await db.readConfig();
    expect(gateScreenOf(c, keyLoaded: false), GateScreen.enrol);
    c = c.copyWith(deviceId: const Value('d'), deviceStatus: const Value('PENDING'));
    expect(gateScreenOf(c, keyLoaded: false), GateScreen.enrol);
    c = c.copyWith(deviceStatus: const Value('ACTIVE'), lastServerTimeAt: const Value(1));
    expect(gateScreenOf(c, keyLoaded: true), GateScreen.event);
    c = c.copyWith(eventId: const Value(12), gateId: const Value(gate1), manifestVersion: 1);
    expect(gateScreenOf(c, keyLoaded: false), GateScreen.event);
    expect(gateScreenOf(c, keyLoaded: true), GateScreen.unlock);
    expect(gateScreenOf(c.copyWith(staffId: const Value('s-1')), keyLoaded: true), GateScreen.scan);
  });

  group('GET /gate/events', () {
    test('marks the device ACTIVE, replaces staff with their verifiers, refreshes the loaded event', () async {
      await seedActiveDevice(db);
      await db.update(db.config).write(const ConfigCompanion(deviceStatus: Value('PENDING')));
      await db
          .into(db.staff)
          .insert(StaffCompanion.insert(staffId: const Value('old'), label: 'Old', role: 'GATE', pinVerifier: 'x'));
      gate.handler = (_) async => json(eventsBody(timezone: 'Asia/Tokyo'));

      final events = await service().refresh();
      expect(events.single.gateCode, 'G1');
      final config = await db.readConfig();
      expect(
        (config.deviceStatus, config.eventTimezone, config.eventTitle),
        ('ACTIVE', 'Asia/Tokyo', 'ATLAS FC – Raja'),
      );
      final staff = await db.select(db.staff).get();
      expect(staff.map((s) => (s.staffId, s.role)), unorderedEquals([('s-1', 'GATE'), ('s-2', 'SUPERVISOR')]));
      expect(staff.first.pinVerifier, startsWith(r'pbkdf2-sha256$10000$'));
    });

    test('PENDING: 403 DEVICE_NOT_ACTIVE, nothing changes', () async {
      await seedActiveDevice(db, selected: false);
      await db.update(db.config).write(const ConfigCompanion(deviceStatus: Value('PENDING')));
      gate.handler = (_) async => json({
        'code': 'DEVICE_NOT_ACTIVE',
        'message': 'x',
        'details': {'status': 'PENDING'},
      }, 403);
      await expectLater(service().refresh(), throwsA(isA<GateApiError>()));
      final config = await db.readConfig();
      expect((config.deviceId, config.deviceStatus), isNot((null, null)));
    });
  });

  test('REVOKED on any gate call: K_e, manifest, staff, scans and config wiped; back to enrolment', () async {
    await seedActiveDevice(db);
    await storage.write(key: 'pin_lock', value: '3:');
    gate.handler = (r) async => r.url.path.endsWith('/manifest')
        ? manifest(
            envelope(manifestV1['payload']! as String, manifestV1['signature']! as String, eventKey: testEventKey),
          )
        : json(eventsBody());
    final container = gateContainer(db, gate);
    final events = container.read(eventServiceProvider);
    await events.refresh();
    await events.download();
    await db
        .into(db.scans)
        .insert(
          ScansCompanion.insert(
            scanId: const Value('0192f0c1-7a3e-7c4d-9b1a-2f3e4d5c6b7a'),
            gateId: gate1,
            staffId: 's-1',
            direction: 'IN',
            result: 'OK',
            deviceTime: deviceNowMs,
          ),
        );
    await db
        .into(db.used)
        .insert(UsedCompanion.insert(ticketId: 't', direction: 'IN', firstAt: deviceNowMs, source: 'LOCAL'));
    expect(container.read(eventKeyProvider), isNotNull);

    gate.handler = (_) async => json({
      'code': 'DEVICE_NOT_ACTIVE',
      'message': 'x',
      'details': {'status': 'REVOKED'},
    }, 403);
    await expectLater(events.refresh(), throwsA(isA<GateApiError>()));

    for (final count in [
      db.zones.count(),
      db.gates.count(),
      db.staff.count(),
      db.manifestTickets.count(),
      db.used.count(),
      db.scans.count(),
    ]) {
      expect(await count.getSingle(), 0);
    }
    final config = await db.readConfig();
    expect(
      (config.deviceId, config.deviceStatus, config.manifestPublicKey, config.eventId, config.manifestVersion),
      (null, null, null, null, 0),
    );
    expect(await storage.readAll(), isEmpty);
    expect(container.read(eventKeyProvider), isNull);
    expect(gateScreenOf(config, keyLoaded: false), GateScreen.enrol);
  });
}

int _indexOf(List<int> haystack, List<int> needle) {
  outer:
  for (var i = 0; i + needle.length <= haystack.length; i++) {
    for (var j = 0; j < needle.length; j++) {
      if (haystack[i + j] != needle[j]) continue outer;
    }
    return i;
  }
  return -1;
}
