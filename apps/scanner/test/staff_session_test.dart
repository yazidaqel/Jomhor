import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/staff/staff_session.dart';
import 'package:jomhor_scanner/features/staff/unlock_screen.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/app_theme.dart';

import 'gate_fixtures.dart';
import 'helpers.dart';

/// PIN 4821 for s-1 (GATE), 0000 for s-2 (SUPERVISOR): the first and third `pins` vectors.
Future<void> seedStaff(ScannerDatabase db) => db.batch(
  (b) => b.insertAll(db.staff, [
    StaffCompanion.insert(
      staffId: const Value('s-1'),
      label: 'Yassine A.',
      role: 'GATE',
      pinVerifier: vectors('pins')[0]['verifier']! as String,
    ),
    StaffCompanion.insert(
      staffId: const Value('s-2'),
      label: 'Salma B.',
      role: 'SUPERVISOR',
      pinVerifier: vectors('pins')[2]['verifier']! as String,
    ),
  ]),
);

void main() {
  late ScannerDatabase db;
  late int now;
  late ServerClock clock;

  setUp(() async {
    FlutterSecureStorage.setMockInitialValues({});
    db = memoryDatabase();
    addTearDown(db.close);
    now = deviceNowMs;
    clock = ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(now));
    await seedStaff(db);
  });

  StaffSession session() {
    final s = StaffSession(db, const FlutterSecureStorage(), clock);
    addTearDown(s.dispose);
    return s;
  }

  test('golden: docs/gate-vectors.json pins', () async {
    final pins = vectors('pins');
    expect(pins.where((v) => v['ok'] == true), isNotEmpty);
    expect(pins.where((v) => v['ok'] == false), isNotEmpty);
    for (final v in pins) {
      expect(await verifyPin(v['pin']! as String, v['verifier']! as String), v['ok'], reason: '$v');
    }
  });

  test('a verifier that does not parse never matches', () async {
    final good = vectors('pins')[0]['verifier']! as String;
    for (final bad in [
      '',
      good.replaceFirst('pbkdf2-sha256', 'pbkdf2-sha1'),
      good.replaceFirst(r'$10000$', r'$0$'),
      good.replaceFirst(r'$10000$', r'$x$'),
      '${good.substring(0, good.lastIndexOf(r'$'))}\$!!',
      good.substring(0, good.lastIndexOf(r'$')),
      // docs/04: exactly 10,000 rounds, a 16-byte salt and a 32-byte hash.
      good.replaceFirst(r'$10000$', r'$1000$'),
      good.replaceFirst(r'$10000$', r'$010000$'),
      good.replaceFirst('AAECAwQFBgcICQoLDA0ODw', 'AAECAwQFBgcICQoLDA0O'),
      good.substring(0, good.length - 2),
    ]) {
      expect(await verifyPin('4821', bad), isFalse, reason: bad);
    }
  });

  test('unlock with the right PIN sets config.staff_id; lock clears it', () async {
    final s = session();
    expect((await s.unlock('s-1', '4821')).ok, isTrue);
    expect((await db.readConfig()).staffId, 's-1');
    await s.lock();
    expect((await db.readConfig()).staffId, isNull);
  });

  test('a PIN of another staff member, or an unknown staff id, does not unlock', () async {
    final s = session();
    expect((await s.unlock('s-1', '0000')).ok, isFalse);
    expect((await s.unlock('nobody', '4821')).ok, isFalse);
    expect((await db.readConfig()).staffId, isNull);
  });

  test('5 wrong PINs lock the device for 5 min, across an app restart; then the counter starts again', () async {
    final s = session();
    for (var left = 4; left >= 1; left--) {
      expect(await s.unlock('s-1', '1111'), (ok: false, attemptsLeft: left, lockedUntilMs: null));
    }
    final until = deviceNowMs + const Duration(minutes: 5).inMilliseconds;
    expect(await s.unlock('s-1', '1111'), (ok: false, attemptsLeft: 0, lockedUntilMs: until));

    // The right PIN is refused while locked, by this session and by a new one (app restart).
    now += const Duration(minutes: 4, seconds: 59).inMilliseconds;
    expect((await s.unlock('s-1', '4821')).lockedUntilMs, until);
    expect((await session().unlock('s-2', '0000')).lockedUntilMs, until);
    expect((await db.readConfig()).staffId, isNull);

    now = until;
    expect(await s.unlock('s-1', '1111'), (ok: false, attemptsLeft: 4, lockedUntilMs: null));
    expect((await s.unlock('s-1', '4821')).ok, isTrue);
    // A success resets the count.
    expect((await s.unlock('s-1', '1111')).attemptsLeft, 4);
  });

  testWidgets('idle lock: 10 min without a touch or a scan locks (fake clock)', (tester) async {
    final s = session();
    await tester.runAsync(() => s.unlock('s-1', '4821'));
    s.touch(); // Restarts the countdown on the test's fake clock.

    await tester.pump(const Duration(minutes: 9));
    s.touch();
    await tester.pump(const Duration(minutes: 9, seconds: 59));
    expect((await tester.runAsync(db.readConfig))!.staffId, 's-1');

    await tester.pump(const Duration(seconds: 1));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    expect((await tester.runAsync(db.readConfig))!.staffId, isNull);

    // Locked: touches no longer start a countdown.
    s.touch();
    await tester.pump(const Duration(minutes: 20));
  });

  testWidgets('a failing PIN check shows an error and leaves the pad usable', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          databaseProvider.overrideWithValue(db),
          staffSessionProvider.overrideWithValue(_ThrowingSession(db, const FlutterSecureStorage(), clock)),
        ],
        child: MaterialApp(
          theme: buildAppTheme(),
          locale: const Locale('fr'),
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          home: const UnlockScreen(timeZone: 'Africa/Casablanca'),
        ),
      ),
    );
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await tester.pump();
    await tester.tap(find.text('Yassine A.'));
    await tester.pump();
    for (final d in ['1', '2', '3', '4']) {
      await tester.tap(find.widgetWithText(OutlinedButton, d));
      await tester.pump();
    }
    await tester.pump();
    expect(find.text('Erreur du serveur. Réessayez.'), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('unlock screen works offline: name, then PIN, no network call', (tester) async {
    var calls = 0;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          databaseProvider.overrideWithValue(db),
          serverClockProvider.overrideWithValue(clock),
          httpClientProvider.overrideWithValue(
            MockClient((_) async {
              calls++;
              throw StateError('no network at the gate');
            }),
          ),
        ],
        child: MaterialApp(
          theme: buildAppTheme(),
          locale: const Locale('fr'),
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          home: const UnlockScreen(timeZone: 'Africa/Casablanca'),
        ),
      ),
    );
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await tester.pump();
    expect(find.text('Qui êtes-vous ?'), findsOneWidget);
    expect(find.text('Superviseur'), findsOneWidget);

    await tester.tap(find.text('Yassine A.'));
    await tester.pump();
    expect(find.text('PIN de Yassine A.'), findsOneWidget);
    final key = tester.getSize(find.widgetWithText(OutlinedButton, '1'));
    expect(key.height, greaterThanOrEqualTo(44));
    expect(key.width, greaterThanOrEqualTo(44));

    // A wrong PIN first: the message, and the pad is cleared.
    for (final d in ['1', '1', '1']) {
      await tester.tap(find.widgetWithText(OutlinedButton, d));
      await tester.pump();
    }
    await tester.runAsync(() async {
      await tester.tap(find.widgetWithText(OutlinedButton, '1'));
      await Future<void>.delayed(const Duration(seconds: 2));
    });
    await tester.pump();
    expect(find.text('PIN incorrect · 4 essais restants'), findsOneWidget);

    for (final d in ['4', '8', '2']) {
      await tester.tap(find.widgetWithText(OutlinedButton, d));
      await tester.pump();
    }
    await tester.runAsync(() async {
      await tester.tap(find.widgetWithText(OutlinedButton, '1'));
      await Future<void>.delayed(const Duration(seconds: 2));
    });
    expect((await tester.runAsync(db.readConfig))!.staffId, 's-1');
    expect(calls, 0);

    await tester.pumpWidget(const SizedBox.shrink());
  });
}

class _ThrowingSession extends StaffSession {
  new(super._db, super._storage, super._clock);

  @override
  Future<UnlockOutcome> unlock(String staffId, String pin) async => throw StateError('keystore unavailable');
}
