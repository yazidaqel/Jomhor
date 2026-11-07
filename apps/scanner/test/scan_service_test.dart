import 'dart:async';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/scan/scan_controller.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';

import 'helpers.dart';
import 'scan_fixtures.dart';

final Uint8List valid = ticketBytes(0x11);
final Uint8List second = ticketBytes(0x12);
final Uint8List virage = ticketBytes(0x55);

final RegExp uuidV7Pattern = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

void main() {
  late ScannerDatabase db;
  late ServerClock clock;
  late ScanService service;
  EventKey? key;

  setUp(() async {
    db = memoryDatabase();
    clock = ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(nowMs));
    key = EventKey(testKe);
    service = ScanService(db, clock: clock, eventKey: () => key, random: Random(1));
    await seedEventMode(db, {
      ticketId(valid): (zoneNord, 'VALID', 'Y. A.'),
      ticketId(second): (zoneNord, 'VALID', null),
      ticketId(virage): (zoneVirage, 'VALID', null),
    });
  });
  tearDown(() => db.close());

  Future<List<Scan>> scans() => db.select(db.scans).get();

  test('OK writes the scan row (UUIDv7, append-only fields) and the used row', () async {
    final o = await service.scan(staticToken(valid));
    expect(o.verdict.result, ScanResult.OK);
    expect(o.zoneName, 'Tribune Nord');
    expect(o.atMs, nowMs);

    final row = (await scans()).single;
    expect(row.scanId, o.scanId);
    expect(row.scanId, matches(uuidV7Pattern));
    expect(row.ticketId, ticketId(valid));
    expect(row.gateId, gateNord);
    expect(row.staffId, staffId);
    expect(row.direction, 'IN');
    expect(row.result, 'OK');
    expect(row.tokenVersion, versionStatic);
    expect(row.deviceTime, nowMs);
    expect(row.synced, 0);
    expect(row.overrideReason, isNull);

    final used = (await db.select(db.used).get()).single;
    expect(
      (used.ticketId, used.direction, used.firstAt, used.gateCode, used.source),
      (ticketId(valid), 'IN', nowMs, 'N1', 'LOCAL'),
    );
  });

  test('a second OK of a ticket on the same device is DUPLICATE with the first gate and time', () async {
    await service.scan(staticToken(valid));
    clock.offsetMs = 45000;
    final o = await service.scan(rotatingToken(valid, slot: slotOf(nowMs + 45000)));
    expect(o.verdict.result, ScanResult.DUPLICATE);
    expect(o.verdict.firstAt, nowMs);
    expect(o.verdict.firstGateCode, 'N1');
    expect([for (final s in await scans()) s.result], ['OK', 'DUPLICATE']);
    expect(await db.select(db.used).get(), hasLength(1));
  });

  test('every outcome is logged, red included; ticket_id only after authentication', () async {
    await service.scan('not a ticket');
    await service.scan(staticToken(valid, event: otherEventId));
    await service.scan(staticToken(ticketBytes(0x77)));
    await service.scan(staticToken(virage, zone: zoneVirage));
    final rows = await scans();
    expect(
      [for (final r in rows) (r.result, r.ticketId, r.tokenVersion)],
      [
        ('BAD_FORMAT', null, null),
        ('WRONG_EVENT', null, versionStatic),
        ('UNKNOWN', ticketId(ticketBytes(0x77)), versionStatic),
        ('WRONG_ZONE', ticketId(virage), versionStatic),
      ],
    );
    expect(await db.select(db.used).get(), isEmpty);
    expect({for (final r in rows) r.scanId}, hasLength(4));
  });

  test('scan + used in one transaction: a failing used insert leaves neither row', () async {
    await db.customStatement("CREATE TRIGGER fail_used BEFORE INSERT ON used BEGIN SELECT RAISE(ABORT, 'forced'); END");
    await expectLater(service.scan(staticToken(valid)), throwsA(anything));
    expect(await scans(), isEmpty);
    expect(await db.select(db.used).get(), isEmpty);
  });

  test('the slot uses the corrected clock: a device 2 min slow with its offset accepts -1..+1 only', () async {
    // Device wall clock 120 s behind the server; the measured offset fixes it.
    clock = ServerClock(offsetMs: 120000, deviceNow: () => DateTime.fromMillisecondsSinceEpoch(nowMs - 120000));
    service = ScanService(db, clock: clock, eventKey: () => key, random: Random(2));
    final serverSlot = slotOf(nowMs);
    expect((await service.scan(rotatingToken(valid, slot: serverSlot + 1))).verdict.result, ScanResult.OK);
    expect((await service.scan(rotatingToken(second, slot: serverSlot - 1))).verdict.result, ScanResult.OK);
    expect(
      (await service.scan(rotatingToken(virage, slot: serverSlot + 2, zone: zoneVirage))).verdict.result,
      ScanResult.EXPIRED_SLOT,
    );
    expect((await scans()).first.deviceTime, nowMs);

    clock.offsetMs = 0;
    final t = ticketBytes(0x13);
    expect((await service.scan(rotatingToken(t, slot: serverSlot))).verdict.result, ScanResult.EXPIRED_SLOT);
  });

  test('outside event mode (no K_e, locked, no gate) nothing is validated or written', () async {
    key = null;
    await expectLater(service.scan(staticToken(valid)), throwsA(isA<EventModeInactive>()));
    expect(await service.desk(), isNull);
    key = EventKey(testKe);
    await db.update(db.config).write(const ConfigCompanion(staffId: Value(null)));
    await expectLater(service.scan(staticToken(valid)), throwsA(isA<EventModeInactive>()));
    expect(await scans(), isEmpty);
  });

  test('desk: counters for this gate (entered, refused, rate over 5 min) and the unsynced count', () async {
    await service.scan(staticToken(valid));
    await service.scan(staticToken(valid));
    clock.offsetMs = 6 * 60000;
    await service.scan(staticToken(second));
    final desk = (await service.desk())!;
    expect((desk.gateCode, desk.gateName), ('N1', 'Nord'));
    expect((desk.entered, desk.refused, desk.unsynced), (2, 1, 3));
    expect(desk.ratePerMin, 0); // 1 admitted in the last 5 min, ÷ 5
  });

  test('uuidV7: version 7, variant 10, the ms timestamp first, so ids sort by time', () {
    final r = Random(3);
    final a = uuidV7(nowMs, r);
    final b = uuidV7(nowMs + 1, r);
    expect(a, matches(uuidV7Pattern));
    expect(a.replaceAll('-', '').substring(0, 12), nowMs.toRadixString(16).padLeft(12, '0'));
    expect(a.compareTo(b), lessThan(0));
  });

  group('no network on the scan path', () {
    test('scanning through the providers with an HTTP client that throws: works, zero calls', () async {
      var calls = 0;
      final container = ProviderContainer(
        overrides: [
          databaseProvider.overrideWithValue(db),
          serverClockProvider.overrideWithValue(clock),
          httpClientProvider.overrideWithValue(
            MockClient((request) async {
              calls++;
              throw StateError('network call on the scan path: ${request.url}');
            }),
          ),
          scanFeedbackProvider.overrideWithValue((_) async {}),
        ],
      );
      addTearDown(container.dispose);
      container.read(eventKeyProvider.notifier).load(testKe);
      final sub = container.listen(scanControllerProvider, (_, _) {});
      addTearDown(sub.close);

      await container.read(scanControllerProvider.notifier).submit(staticToken(valid));
      expect(container.read(scanControllerProvider).outcome!.verdict.result, ScanResult.OK);
      expect((await container.read(scanServiceProvider).scan(staticToken(valid))).verdict.result, ScanResult.DUPLICATE);
      expect(calls, 0);
    });

    test('nothing under lib/features/scan imports an HTTP client, even transitively', () {
      final reached = <String>{};
      void walk(String path) {
        if (!reached.add(path)) return;
        for (final m in RegExp(
          '''^(?:import|export) '([^']+)';''',
          multiLine: true,
        ).allMatches(File(path).readAsStringSync())) {
          final uri = m[1]!;
          expect(uri, isNot(anyOf(startsWith('package:http'), startsWith('dart:io'))), reason: '$path imports $uri');
          if (uri.startsWith('package:jomhor_scanner/')) walk('lib/${uri.substring('package:jomhor_scanner/'.length)}');
        }
      }

      for (final f in Directory('lib/features/scan').listSync().whereType<File>()) {
        walk(f.path.replaceAll(r'\', '/'));
      }
      expect(reached, contains('lib/features/scan/validator.dart'));
    });
  });

  group('controller', () {
    ProviderContainer container0({ScanService? service}) {
      final container = ProviderContainer(
        overrides: [
          databaseProvider.overrideWithValue(db),
          serverClockProvider.overrideWithValue(clock),
          scanFeedbackProvider.overrideWithValue((_) async {}),
          if (service != null) scanServiceProvider.overrideWithValue(service),
        ],
      );
      addTearDown(container.dispose);
      container.read(eventKeyProvider.notifier).load(testKe);
      final sub = container.listen(scanControllerProvider, (_, _) {});
      addTearDown(sub.close);
      return container;
    }

    test('a wedge read during a scan is queued and then scanned; a camera read is dropped', () async {
      final c = container0();
      final controller = c.read(scanControllerProvider.notifier);
      final first = controller.submit(staticToken(valid), wedge: true);
      unawaited(controller.submit(staticToken(virage, zone: zoneVirage))); // camera: busy, dropped
      unawaited(controller.submit(staticToken(second), wedge: true)); // wedge: queued
      await first;
      expect([for (final s in await scans()) s.ticketId], [ticketId(valid), ticketId(second)]);
    });

    test('any failure, Error included, shows ERREUR (failed), never a stale screen', () async {
      final c = container0(service: _Throwing(db, clock));
      await c.read(scanControllerProvider.notifier).submit(staticToken(valid));
      expect(c.read(scanControllerProvider).failed, isTrue);
      expect(c.read(scanControllerProvider).holding, isTrue);
    });

    test('a failed counters read after a committed OK keeps VALIDE on screen and flags only the counters', () async {
      final service = _FlakyDesk(db, clock, () => key);
      final c = container0(service: service);
      final controller = c.read(scanControllerProvider.notifier);
      await controller.refresh();
      service.failDesk = true;
      await controller.submit(staticToken(valid));
      final s = c.read(scanControllerProvider);
      expect(s.outcome!.verdict.result, ScanResult.OK);
      expect((s.failed, s.countersStale), (false, true));
      expect(s.desk, isNotNull); // last values kept
      await controller.refresh(); // the 30 s ticker
      expect(c.read(scanControllerProvider).outcome!.verdict.result, ScanResult.OK);
      service.failDesk = false;
      await controller.refresh();
      expect(c.read(scanControllerProvider).countersStale, isFalse);
    });

    test('repeatKey: the ticket id for a token (any slot), the raw string otherwise', () {
      expect(repeatKey(rotatingToken(valid, slot: 5)), repeatKey(rotatingToken(valid, slot: 6)));
      expect(repeatKey(rotatingToken(valid, slot: 5)), repeatKey(staticToken(valid)));
      expect(repeatKey(staticToken(valid)), isNot(repeatKey(staticToken(second))));
      expect(repeatKey('garbage'), 'garbage');
    });
  });

  test('EventKeyHolder.load refuses a K_e that is not 32 bytes', () {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    expect(() => container.read(eventKeyProvider.notifier).load(Uint8List(16)), throwsArgumentError);
    expect(container.read(eventKeyProvider), isNull);
  });

  test('K_e never shows in toString, and wipe zeroes it', () {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final holder = container.read(eventKeyProvider.notifier)..load(testKe);
    final held = container.read(eventKeyProvider)!;
    expect('$held', isNot(contains('0, 1, 2')));
    holder.wipe();
    expect(held.bytes.every((b) => b == 0), isTrue);
    expect(container.read(eventKeyProvider), isNull);
    expect(testKe[1], 1); // load copied the caller's bytes
  });
}

class _Throwing extends ScanService {
  // ignore: use_super_parameters -- the base parameter is private to its library
  new(ScannerDatabase db, ServerClock clock) : super(db, clock: clock, eventKey: () => null);

  @override
  Future<ScanOutcome> scan(String raw) => throw StateError('not an Exception');
}

class _FlakyDesk extends ScanService {
  // ignore: use_super_parameters -- the base parameter is private to its library
  new(ScannerDatabase db, ServerClock clock, EventKey? Function() key) : super(db, clock: clock, eventKey: key);

  bool failDesk = false;

  @override
  Future<GateDesk?> desk() => failDesk ? throw StateError('counters') : super.desk();
}
