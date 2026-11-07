import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/home/home_screen.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/app_theme.dart';

import 'gate_fixtures.dart';
import 'helpers.dart';

void main() {
  late ScannerDatabase db;
  late FakeGate gate;

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    db = memoryDatabase();
    addTearDown(db.close);
    gate = FakeGate();
  });

  /// The server clock is [skewMs] ahead of the phone: GateClient measures that offset from `server_time`.
  Future<void> pumpHome(WidgetTester tester, int skewMs) async {
    await tester.runAsync(() => seedActiveDevice(db, selected: false));
    gate.handler = (r) async {
      final body = r.url.path.endsWith('/manifest')
          ? envelope(manifestV1['payload']! as String, manifestV1['signature']! as String, eventKey: testEventKey)
          : eventsBody(timezone: 'Asia/Tokyo');
      return json({...body, 'server_time': rfc3339(deviceNowMs + skewMs)});
    };
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          databaseProvider.overrideWithValue(db),
          httpClientProvider.overrideWithValue(gate.client),
          deviceKeyProvider.overrideWithValue(FakeDeviceKey()),
          serverClockProvider.overrideWithValue(
            ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(deviceNowMs)),
          ),
        ],
        child: MaterialApp(
          theme: buildAppTheme(),
          locale: const Locale('fr'),
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          home: const HomeScreen(),
        ),
      ),
    );
    await settle(tester);
  }

  testWidgets('clock 21 s off: the drift check fails and the event cannot be picked', (tester) async {
    await pumpHome(tester, 21000);
    expect(find.textContaining('Horloge décalée de 21 s'), findsOneWidget);

    await tester.tap(find.text('ATLAS FC – Raja'));
    await settle(tester);
    expect((await tester.runAsync(db.readConfig))!.eventId, isNull);
    expect(gate.manifestSince, isEmpty);
    await dispose(tester);
  });

  testWidgets('clock 19 s off: pick the assignment (Tokyo times), download, verify, then the unlock screen', (
    tester,
  ) async {
    await pumpHome(tester, 19000);
    expect(find.text('Horloge vérifiée · écart 19 s'), findsOneWidget);
    expect(find.text('Porte G1 · Porte 1'), findsOneWidget);
    // manifest_available_at = 17:00 UTC = 02:00 the next day in Tokyo.
    expect(find.text('Manifeste disponible à 02:00'), findsOneWidget);

    await tester.tap(find.text('ATLAS FC – Raja'));
    await settle(tester);
    expect(gate.manifestSince, [null]);
    final config = (await tester.runAsync(db.readConfig))!;
    expect((config.eventId, config.manifestVersion, config.eventTimezone), (12, 1, 'Asia/Tokyo'));
    expect(find.text('Qui êtes-vous ?'), findsOneWidget);
    await dispose(tester);
  });
}

/// Lets the real async work (Drift, the verify isolate, secure storage) finish, then rebuilds.
Future<void> settle(WidgetTester tester) async {
  for (var i = 0; i < 5; i++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
    await tester.pump();
  }
}

Future<void> dispose(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox.shrink());
}
