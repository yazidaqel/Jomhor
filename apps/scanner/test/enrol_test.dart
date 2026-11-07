import 'dart:convert';
import 'dart:typed_data';

import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/enrol/enrol_controller.dart';
import 'package:jomhor_scanner/features/enrol/enrol_screen.dart';
import 'package:jomhor_scanner/features/enrol/enrol_service.dart';
import 'package:jomhor_scanner/features/scan/scan_screen.dart' show CameraBuilder;
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/app_theme.dart';

import 'gate_fixtures.dart';
import 'helpers.dart';

const enrolCode = 'eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJlbnJvbCJ9.c2lnbmF0dXJl';
const deviceId = '0b8e7a52-0000-4000-8000-00000000d001';

/// Each [generate] makes a different key, like Keystore.
class RotatingDeviceKey extends FakeDeviceKey {
  int generated = 0;

  @override
  Future<Uint8List> generate() async {
    final n = ++generated;
    return key = Uint8List.fromList(List.generate(91, (i) => (i + 100 * n) & 0xff));
  }
}

http.Response enrolled({String status = 'PENDING', String? manifestKey}) => json({
  'device_id': deviceId,
  'status': status,
  'fingerprint': 'AB12 CD34',
  'manifest_public_key': manifestKey ?? vectorKey,
  'server_time': rfc3339(deviceNowMs),
}, 201);

void main() {
  late ScannerDatabase db;
  late FakeGate gate;

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    db = memoryDatabase();
    addTearDown(db.close);
    gate = FakeGate();
  });

  test('golden: docs/gate-vectors.json fingerprints', () {
    final all = vectors('fingerprints');
    expect(all, isNotEmpty);
    for (final v in all) {
      final spki = base64Url.decode(base64Url.normalize(v['public_key']! as String));
      expect(fingerprint(spki), v['fingerprint'], reason: '$v');
    }
  });

  test('the enrol QR must be a JWT: a ticket QR or text is refused before any request', () {
    expect(enrolCodePattern.hasMatch(enrolCode), isTrue);
    for (final bad in ['AQAAAAwRERERERERERERERERERERAAFz', 'hello', 'a.b', 'a.b.c.d', 'a b.c.d']) {
      expect(enrolCodePattern.hasMatch(bad), isFalse, reason: bad);
    }
    expect(
      [validLabel(''), validLabel('   '), validLabel('a' * 40), validLabel('a' * 41)],
      [false, false, true, false],
    );
  });

  test(
    'enrol: new Keystore key, POST {public_key, label, enrol_code} unsigned by id, device PENDING, key pinned',
    () async {
      final key = RotatingDeviceKey();
      gate.handler = (_) async => enrolled();
      await gateContainer(db, gate, key: key).read(enrolServiceProvider).enrol(enrolCode, '  Tablette Bâb 3 ');

      final r = gate.requests.single;
      expect((r.method, r.url.path), ('POST', '/v1/gate/devices/enrol'));
      expect(r.headers.containsKey('X-Device-Id'), isFalse);
      expect(jsonDecode(r.body), {'public_key': b64(key.key!), 'label': 'Tablette Bâb 3', 'enrol_code': enrolCode});
      final config = await db.readConfig();
      expect((config.deviceId, config.deviceStatus, config.manifestPublicKey), (deviceId, 'PENDING', vectorKey));
      expect(key.generated, 1);
    },
  );

  test('a retry reuses the existing key (enrol is idempotent per key)', () async {
    final key = RotatingDeviceKey();
    var fail = true;
    gate.handler = (_) async {
      if (fail) throw http.ClientException('offline');
      return enrolled();
    };
    final enrol = gateContainer(db, gate, key: key).read(enrolServiceProvider);
    await expectLater(enrol.enrol(enrolCode, 'T'), throwsA(isA<GateApiError>()));
    fail = false;
    await enrol.enrol(enrolCode, 'T');
    expect(key.generated, 1);
    expect(gate.requests.map((r) => (jsonDecode(r.body) as Map)['public_key']).toSet(), hasLength(1));
  });

  test('a key the server already revoked is replaced by a new one, once', () async {
    final key = RotatingDeviceKey();
    await key.generate();
    gate.handler = (r) async =>
        (jsonDecode(r.body) as Map)['public_key'] == b64(List.generate(91, (i) => (i + 100) & 0xff))
        ? enrolled(status: 'REVOKED')
        : enrolled();
    await gateContainer(db, gate, key: key).read(enrolServiceProvider).enrol(enrolCode, 'T');
    expect(key.generated, 2);
    expect(gate.requests, hasLength(2));
    expect((await db.readConfig()).deviceStatus, 'PENDING');
  });

  test('an unexpected failure (Keystore) ends the busy state with an error', () async {
    final container = gateContainer(db, gate, key: _BrokenKey())..listen(enrolControllerProvider, (_, _) {});
    final controller = container.read(enrolControllerProvider.notifier)
      ..openCamera()
      ..onCode(enrolCode);
    await controller.submit('Tablette 3');
    final state = container.read(enrolControllerProvider);
    expect((state.busy, state.error, state.code), (false, GateApiError.unexpectedResponse, enrolCode));
  });

  test('a manifest_public_key that is not 32 bytes is refused and nothing is stored', () async {
    gate.handler = (_) async => enrolled(manifestKey: b64(List.filled(31, 1)));
    await expectLater(
      gateContainer(db, gate).read(enrolServiceProvider).enrol(enrolCode, 'T'),
      throwsA(isA<GateApiError>()),
    );
    expect((await db.readConfig()).deviceId, isNull);
  });

  Future<void> pumpEnrol(WidgetTester tester, {required bool pending, CameraBuilder? camera, DeviceKey? key}) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          databaseProvider.overrideWithValue(db),
          httpClientProvider.overrideWithValue(gate.client),
          deviceKeyProvider.overrideWithValue(key ?? FakeDeviceKey()),
          serverClockProvider.overrideWithValue(
            ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(deviceNowMs)),
          ),
        ],
        child: MaterialApp(
          theme: buildAppTheme(),
          locale: const Locale('fr'),
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          home: EnrolScreen(pending: pending, camera: camera),
        ),
      ),
    );
  }

  testWidgets('enrolment screen: scan the QR, type a label, enrol, then the fingerprint', (tester) async {
    late ValueChanged<String> onCode;
    final key = FakeDeviceKey();
    gate.handler = (_) async => enrolled();
    await pumpEnrol(
      tester,
      pending: false,
      key: key,
      camera: (cb) {
        onCode = cb;
        return const SizedBox.expand();
      },
    );
    await tester.tap(find.text("Scanner le QR d'enrôlement"));
    await tester.pump();

    onCode('AQAAAAwRERERERERERERERERERERAAFz');
    await tester.pump();
    expect(find.text("Ce QR n'est pas un QR d'enrôlement Jomhor."), findsOneWidget);
    onCode(enrolCode);
    await tester.pump();
    expect(find.text("QR d'enrôlement lu"), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'Tablette 3');
    await tester.runAsync(() async {
      await tester.tap(find.text("Enrôler l'appareil"));
      await Future<void>.delayed(const Duration(milliseconds: 200));
    });
    expect((await tester.runAsync(db.readConfig))!.deviceStatus, 'PENDING');
    expect(gate.requests, hasLength(1));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('PENDING: fingerprint at 28 px, then GET /gate/events is polled until approved', (tester) async {
    final key = FakeDeviceKey();
    await key.generate();
    await tester.runAsync(
      () =>
          db.update(db.config).write(const ConfigCompanion(deviceId: Value(deviceId), deviceStatus: Value('PENDING'))),
    );
    var approved = false;
    gate.handler = (_) async => approved
        ? json(eventsBody())
        : json({
            'code': 'DEVICE_NOT_ACTIVE',
            'message': 'x',
            'details': {'status': 'PENDING'},
          }, 403);
    await pumpEnrol(tester, pending: true, key: key);
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
    await tester.pump();

    final fp = fingerprint(key.key!);
    expect(find.text("En attente d'approbation"), findsOneWidget);
    expect(tester.widget<Text>(find.text(fp)).style!.fontSize, 28);
    expect(gate.requests.map((r) => r.url.path), ['/v1/gate/events']);

    approved = true;
    await tester.pump(const Duration(seconds: 10));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
    expect(gate.requests, hasLength(2));
    expect((await tester.runAsync(db.readConfig))!.deviceStatus, 'ACTIVE');
    await tester.pumpWidget(const SizedBox.shrink());
  });
}

class _BrokenKey extends FakeDeviceKey {
  @override
  Future<Uint8List?> publicKey() async => throw StateError('keystore unavailable');
}
