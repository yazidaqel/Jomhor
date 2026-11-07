import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:jomhor_scanner/app/app.dart';
import 'package:jomhor_scanner/app/config.dart';
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';

import 'helpers.dart';

void main() {
  group('l10n', () {
    final fr = lookupAppLocalizations(const Locale('fr'));
    final ar = lookupAppLocalizations(const Locale('ar'));

    test('FR is the first supported locale (fallback for an unsupported device locale)', () {
      expect(AppLocalizations.supportedLocales.first, const Locale('fr'));
    });

    test('AR uses its own strings, and FR for a key app_ar.arb lacks', () {
      expect(ar.devicePending, isNot(fr.devicePending));
      expect(ar.deviceNotEnrolledHint, fr.deviceNotEnrolledHint);
    });
  });

  test('the API must be https, except a local dev backend, in every build mode', () {
    for (final ok in ['https://api.jomhor.ma/v1', 'http://10.0.2.2:8080/v1', 'http://localhost:8080/v1']) {
      expect(() => AppConfig.checkApiUrl(baseUrl: ok), returnsNormally, reason: ok);
    }
    for (final bad in ['http://api.jomhor.ma/v1', 'http://192.168.1.10:8080/v1', 'ftp://x', '']) {
      expect(() => AppConfig.checkApiUrl(baseUrl: bad), throwsStateError, reason: bad);
    }
    expect(AppConfig.checkApiUrl, returnsNormally);
  });

  Future<ScannerDatabase> pumpApp(WidgetTester tester, Locale locale) async {
    final db = memoryDatabase();
    tester.platformDispatcher.localesTestValue = [locale];
    addTearDown(tester.platformDispatcher.clearLocalesTestValue);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          databaseProvider.overrideWithValue(db),
          deviceKeyProvider.overrideWithValue(FakeDeviceKey()),
          // A PENDING device polls GET /gate/events: the server keeps answering 403 PENDING.
          httpClientProvider.overrideWithValue(
            MockClient(
              (_) async => json({
                'code': 'DEVICE_NOT_ACTIVE',
                'message': 'pending',
                'details': {'status': 'PENDING'},
              }, 403),
            ),
          ),
        ],
        child: const ScannerApp(),
      ),
    );
    await tester.pumpAndSettle();
    return db;
  }

  testWidgets('a new device opens on "Appareil non enrôlé" (FR), and follows config', (tester) async {
    final db = await pumpApp(tester, const Locale('fr', 'MA'));
    expect(find.text('Enrôlement'), findsOneWidget);
    expect(find.text('Appareil non enrôlé'), findsOneWidget);

    await tester.runAsync(
      () => db.update(db.config).write(const ConfigCompanion(deviceId: Value('d-1'), deviceStatus: Value('PENDING'))),
    );
    await tester.pumpAndSettle();
    expect(find.text("En attente d'approbation"), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.runAsync(db.close);
  });

  testWidgets('Arabic is laid out right to left; an unsupported locale gets FR', (tester) async {
    var db = await pumpApp(tester, const Locale('ar', 'MA'));
    expect(tester.widget<Text>(find.text('الجهاز غير مسجل')), isNotNull);
    expect(Directionality.of(tester.element(find.text('الجهاز غير مسجل'))), TextDirection.rtl);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.runAsync(db.close);

    db = await pumpApp(tester, const Locale('de', 'DE'));
    expect(find.text('Appareil non enrôlé'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.runAsync(db.close);
  });
}
