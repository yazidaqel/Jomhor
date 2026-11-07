import 'dart:math';

import 'package:drift/drift.dart' hide isNotNull, isNull;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/scan/scan_controller.dart';
import 'package:jomhor_scanner/features/scan/scan_screen.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/features/staff/staff_session.dart';
import 'package:jomhor_scanner/features/supervisor/supervisor_service.dart';
import 'package:jomhor_scanner/features/supervisor/supervisor_sheet.dart';
import 'package:jomhor_scanner/features/sync/sync_controller.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/app_theme.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

import 'gate_fixtures.dart' show vectors;
import 'helpers.dart';
import 'scan_fixtures.dart';

final Uint8List nord = ticketBytes(0x11);
final Uint8List virage = ticketBytes(0x55);
final Uint8List blocked = ticketBytes(0x66);

/// Two tickets whose last 8 hex digits are the same (`77777777`): a typed number lists both (plan Q40).
const twinA = '10000000-0000-4000-8000-000077777777';
const twinB = '20000000-0000-4000-8000-000077777777';

final Map<String, (int, String, String?)> manifest = {
  ticketId(nord): (zoneNord, 'VALID', 'Y. A.'),
  ticketId(virage): (zoneVirage, 'VALID', null),
  ticketId(blocked): (zoneNord, 'BLOCKED', 'M. E.'),
  twinA: (zoneNord, 'VALID', 'K. O.'),
  twinB: (zoneVirage, 'REFUNDED', 'S. B.'),
};

/// s-1 GATE (PIN 4821), s-2 SUPERVISOR (PIN 0000): the `pins` vectors.
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

/// [n] OVERRIDE rows, as earlier overrides on this device left them.
Future<void> seedOverrides(ScannerDatabase db, int n) => db.batch(
  (b) => b.insertAll(db.scans, [
    for (var i = 0; i < n; i++)
      ScansCompanion.insert(
        scanId: Value(uuidV7(nowMs - 60000 + i, Random(i))),
        ticketId: Value(ticketId(ticketBytes(0x80 + i))),
        gateId: gateNord,
        staffId: staffId,
        direction: 'IN',
        result: 'OVERRIDE',
        deviceTime: nowMs - 60000 + i,
        overrideReason: const Value('OTHER'),
        supervisorId: const Value('s-2'),
      ),
  ]),
);

class _Loaded extends EventKeyHolder {
  @override
  EventKey? build() => EventKey(testKe);
}

class _Offline extends SyncController {
  @override
  SyncState build() => const SyncState(online: false);
}

void main() {
  late ScannerDatabase db;
  late ServerClock clock;
  late int now;
  late StaffSession session;
  late SupervisorService service;

  setUp(() async {
    FlutterSecureStorage.setMockInitialValues({});
    db = memoryDatabase();
    now = nowMs;
    clock = ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(now));
    session = StaffSession(db, const FlutterSecureStorage(), clock);
    service = SupervisorService(db, clock: clock, random: Random(1));
  });

  group('service', () {
    setUp(() async {
      addTearDown(db.close);
      addTearDown(session.dispose);
      await seedEventMode(db, manifest);
      await seedStaff(db);
    });

    Future<SupervisorGrant> grant() async => (await session.verifySupervisor('s-2', '0000')).$2!;

    test('the supervisor PIN grants without touching the unlocked staff; a wrong one counts to the lockout', () async {
      final (wrong, none) = await session.verifySupervisor('s-2', '1111');
      expect(none, isNull);
      expect(wrong, (ok: false, attemptsLeft: 4, lockedUntilMs: null));

      final (ok, granted) = await session.verifySupervisor('s-2', '0000');
      expect(ok.ok, isTrue);
      expect(granted!.staffId, 's-2');
      expect((await db.readConfig()).staffId, staffId, reason: 'the gate agent stays unlocked');
    });

    test('GATE staff get no grant, even with their right PIN', () async {
      final (outcome, granted) = await session.verifySupervisor('s-1', '4821');
      expect(granted, isNull);
      expect(outcome.ok, isFalse);
      expect(outcome.attemptsLeft, 4);
    });

    test('the OVERRIDE row carries supervisor_id, reason, the unlocked staff and the gate, and marks used', () async {
      final g = await grant();
      final outcome = await service.override(g, OverrideReason.idChecked, ticketId(blocked));

      expect(outcome.verdict.result, ScanResult.OVERRIDE);
      expect(outcome.verdict.initials, 'M. E.');
      expect(outcome.zoneName, 'Tribune Nord');
      final row = (await db.select(db.scans).get()).single;
      expect(row.scanId, outcome.scanId);
      expect(row.result, 'OVERRIDE');
      expect(row.ticketId, ticketId(blocked));
      expect(row.overrideReason, 'ID_CHECKED');
      expect(row.supervisorId, 's-2');
      expect(row.staffId, staffId);
      expect(row.gateId, gateNord);
      expect(row.deviceTime, nowMs);
      expect(row.synced, 0);
      final used = (await db.select(db.used).get()).single;
      expect((used.ticketId, used.firstAt, used.gateCode, used.source), (ticketId(blocked), nowMs, 'N1', 'LOCAL'));
    });

    test('an override of a ticket already used keeps its first entry in the used-set', () async {
      final g = await grant();
      await db
          .into(db.used)
          .insert(
            UsedCompanion.insert(
              ticketId: ticketId(nord),
              direction: 'IN',
              firstAt: nowMs - 1000,
              gateCode: const Value('V2'),
              source: 'REMOTE',
            ),
          );
      await service.override(g, OverrideReason.damagedTicket, ticketId(nord));
      final used = (await db.select(db.used).get()).single;
      expect((used.firstAt, used.gateCode, used.source), (nowMs - 1000, 'V2', 'REMOTE'));
      expect((await db.select(db.scans).get()).single.result, 'OVERRIDE');
    });

    test('never for a ticket that is not in the manifest: refused, nothing written', () async {
      final g = await grant();
      await expectLater(
        service.override(g, OverrideReason.other, ticketId(ticketBytes(0x99))),
        throwsA(isA<TicketNotInManifest>()),
      );
      expect(await db.select(db.scans).get(), isEmpty);
      expect(await db.select(db.used).get(), isEmpty);
    });

    test('the 21st override on the device is refused (cap 20), nothing written', () async {
      final g = await grant();
      await seedOverrides(db, 19);
      await service.override(g, OverrideReason.other, ticketId(nord));
      expect(await service.overrideCount(), 20);
      await expectLater(
        service.override(g, OverrideReason.other, ticketId(virage)),
        throwsA(isA<OverrideCapReached>()),
      );
      expect(await service.overrideCount(), 20);
      expect(await (db.select(db.used)..where((u) => u.ticketId.equals(ticketId(virage)))).get(), isEmpty);
    });

    test('find: typed numbers (any case, spaces, dash) list every match; a scanned token finds its ticket', () async {
      final g = await grant();
      final twins = await service.find(g, '7777 7777');
      expect([for (final m in twins) m.ticketId], [twinA, twinB]);
      expect(
        [for (final m in twins) (m.zoneName, m.status, m.initials)],
        [('Tribune Nord', 'VALID', 'K. O.'), ('Virage', 'REFUNDED', 'S. B.')],
      );
      final no = ticketId(nord).substring(28);
      expect((await service.find(g, no.toLowerCase())).single.ticketId, ticketId(nord));
      expect((await service.find(g, '${no.substring(0, 4)}-${no.substring(4)}')).single.ticketId, ticketId(nord));
      // A PDF QR and a rotating app QR: decoded, no tag check (a damaged ticket may not verify).
      expect((await service.find(g, staticToken(virage, zone: zoneVirage))).single.ticketId, ticketId(virage));
      final rotating = rotatingToken(blocked, slot: slotOf(nowMs));
      expect((await service.find(g, tampered(rotating))).single.ticketId, ticketId(blocked));
      expect(await service.find(g, 'ABCD'), isEmpty);
      expect(await service.find(g, '0000 0000'), isEmpty);
      expect(await service.find(g, 'not a ticket'), isEmpty);
    });

    test('gate change persists in config.gate_id and moves the zone check (WRONG_ZONE → OK)', () async {
      final g = await grant();
      final scanner = ScanService(db, clock: clock, eventKey: () => EventKey(testKe), random: Random(2));
      final token = staticToken(virage, zone: zoneVirage);
      final wrong = await scanner.scan(token);
      expect(wrong.verdict.result, ScanResult.WRONG_ZONE);
      expect(wrong.verdict.correctGates, ['V1', 'V2']);

      final v1 = gates[1].gateId;
      await service.changeGate(g, v1);
      expect((await db.readConfig()).gateId, v1);
      final ok = await scanner.scan(token);
      expect(ok.verdict.result, ScanResult.OK);
      expect((await (db.select(db.scans)..where((s) => s.scanId.equals(ok.scanId))).getSingle()).gateId, v1);
      // And back: a Nord ticket is now the wrong zone here.
      expect((await scanner.scan(staticToken(nord))).verdict.result, ScanResult.WRONG_ZONE);
    });

    test('gate change to a gate outside the manifest is refused, the gate stays', () async {
      final g = await grant();
      await expectLater(service.changeGate(g, 'a0000000-0000-4000-8000-0000000000ff'), throwsArgumentError);
      expect((await db.readConfig()).gateId, gateNord);
    });

    test('the grant ends 60 s after the PIN: override and gate change refused, nothing written', () async {
      final g = await grant();
      now += SupervisorGrant.ttl.inMilliseconds;
      await service.override(g, OverrideReason.other, ticketId(nord));
      now += 1000;
      await expectLater(
        service.override(g, OverrideReason.other, ticketId(virage)),
        throwsA(isA<SupervisorGrantInvalid>()),
      );
      await expectLater(service.changeGate(g, gates[1].gateId), throwsA(isA<SupervisorGrantInvalid>()));
      expect(await service.overrideCount(), 1);
      expect((await db.readConfig()).gateId, gateNord);
      // A clock moved back before the PIN ends it too.
      final early = await grant();
      now -= 5000;
      await expectLater(
        service.override(early, OverrideReason.other, ticketId(virage)),
        throwsA(isA<SupervisorGrantInvalid>()),
      );
    });

    test(
      'a supervisor removed or demoted since the PIN cannot override (staff row re-read in the transaction)',
      () async {
        final g = await grant();
        await (db.update(
          db.staff,
        )..where((s) => s.staffId.equals('s-2'))).write(const StaffCompanion(role: Value('GATE')));
        await expectLater(
          service.override(g, OverrideReason.other, ticketId(nord)),
          throwsA(isA<SupervisorGrantInvalid>()),
        );
        await (db.delete(db.staff)..where((s) => s.staffId.equals('s-2'))).go();
        await expectLater(
          service.override(g, OverrideReason.other, ticketId(nord)),
          throwsA(isA<SupervisorGrantInvalid>()),
        );
        expect(await db.select(db.scans).get(), isEmpty);
        expect(await db.select(db.used).get(), isEmpty);
      },
    );

    test('two concurrent overrides at 19: one row, one OverrideCapReached', () async {
      final g = await grant();
      await seedOverrides(db, 19);
      final results = await Future.wait([
        for (final t in [nord, virage])
          service.override(g, OverrideReason.other, ticketId(t)).then<Object>((o) => o, onError: (Object e) => e),
      ]);
      expect(results.whereType<ScanOutcome>(), hasLength(1));
      expect(results.whereType<OverrideCapReached>(), hasLength(1));
      expect(await service.overrideCount(), 20);
      expect(await db.select(db.used).get(), hasLength(1));
    });

    test('a manifest status this build does not know is shown as sent, not as Bloqué', () async {
      final l = lookupAppLocalizations(const Locale('fr'));
      expect(statusLabel(l, 'BLOCKED'), 'Bloqué');
      expect(statusLabel(l, 'FROZEN'), 'FROZEN');
    });
  });

  group('sheet', () {
    late List<ScanResult> feedback;
    late int httpCalls;

    Future<void> pump(WidgetTester tester, {int overrides = 0}) async {
      feedback = [];
      httpCalls = 0;
      await tester.runAsync(() async {
        await seedEventMode(db, manifest);
        await seedStaff(db);
        await seedOverrides(db, overrides);
      });
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            databaseProvider.overrideWithValue(db),
            serverClockProvider.overrideWithValue(clock),
            eventKeyProvider.overrideWith(_Loaded.new),
            scanFeedbackProvider.overrideWithValue((r) async => feedback.add(r)),
            syncControllerProvider.overrideWith(_Offline.new),
            staffSessionProvider.overrideWithValue(session),
            supervisorServiceProvider.overrideWithValue(service),
            httpClientProvider.overrideWithValue(
              MockClient((_) async {
                httpCalls++;
                throw StateError('no network at the gate');
              }),
            ),
          ],
          child: MaterialApp(
            theme: buildAppTheme(),
            locale: const Locale('fr'),
            supportedLocales: AppLocalizations.supportedLocales,
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            home: ScanScreen(camera: (_) => const SizedBox.expand()),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    Future<void> settle(WidgetTester tester) async {
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
      await tester.pumpAndSettle();
    }

    /// Superviseur → [name] → [pin] (PBKDF2 runs in an isolate: real time).
    Future<void> openSheet(WidgetTester tester, {String name = 'Salma B.', String pin = '0000'}) async {
      // The last one: on a result, the result screen's button (the scanning view's is under it).
      await tester.tap(find.text('Superviseur').last);
      await settle(tester);
      await tester.tap(find.text(name));
      await tester.pumpAndSettle();
      for (final d in pin.substring(0, 3).split('')) {
        await tester.tap(find.widgetWithText(OutlinedButton, d));
        await tester.pump();
      }
      await tester.runAsync(() async {
        await tester.tap(find.widgetWithText(OutlinedButton, pin[3]));
        await Future<void>.delayed(const Duration(seconds: 2));
      });
      await settle(tester);
    }

    Future<void> done(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox.shrink());
      session.dispose();
      await tester.runAsync(db.close);
    }

    Future<void> typeNumber(WidgetTester tester, String number) async {
      await tester.enterText(find.byType(TextField), number);
      await tester.tap(find.text('Chercher'));
      await settle(tester);
    }

    testWidgets('only SUPERVISOR staff are listed; a wrong PIN keeps the sheet locked', (tester) async {
      await pump(tester);
      await tester.tap(find.text('Superviseur'));
      await settle(tester);
      expect(find.text('Quel superviseur ?'), findsOneWidget);
      expect(find.text('Salma B.'), findsOneWidget);
      expect(find.text('Yassine A.'), findsNothing);
      await tester.tap(find.byTooltip('Fermer'));
      await settle(tester);

      await openSheet(tester, pin: '1234');
      expect(find.text('PIN incorrect · 4 essais restants'), findsOneWidget);
      expect(find.text('Dérogation'), findsNothing);
      expect((await tester.runAsync(db.readConfig))!.staffId, staffId);
      await done(tester);
    });

    testWidgets('Dérogation: reason → typed number with 2 matches → pick one → OVERRIDE blue, offline', (tester) async {
      await pump(tester, overrides: 3);
      await openSheet(tester);
      expect(find.text('Dérogations 3 / 20'), findsOneWidget);
      expect(find.text('Changer de porte'), findsOneWidget);
      expect(find.text('Recherche billet'), findsOneWidget);

      await tester.tap(find.text('Dérogation'));
      await tester.pumpAndSettle();
      for (final reason in ['Identité vérifiée', "Panne d'appareil", 'Billet abîmé', 'Autre motif']) {
        expect(find.text(reason), findsOneWidget);
      }
      await tester.tap(find.text('Billet abîmé'));
      await tester.pumpAndSettle();
      await typeNumber(tester, '7777 7777');
      expect(find.text('2 billets portent ce numéro : touchez le bon'), findsOneWidget);
      expect(find.text('Tribune Nord'), findsOneWidget);
      expect(find.text('Valide · K. O.'), findsOneWidget);
      expect(find.text('Virage'), findsOneWidget);
      expect(find.text('Remboursé · S. B.'), findsOneWidget);

      await tester.tap(find.text('Virage'));
      await settle(tester);
      expect(find.text('DÉROGATION'), findsOneWidget);
      expect(find.text('Entrée accordée par un superviseur.'), findsOneWidget);
      expect(
        tester.widgetList<ColoredBox>(find.byType(ColoredBox)).any((b) => b.color == JomhorColors.scanOverride),
        isTrue,
      );
      expect(feedback, [ScanResult.OVERRIDE]);
      final row = (await tester.runAsync(
        () => (db.select(db.scans)..where((s) => s.ticketId.equals(twinB))).getSingle(),
      ))!;
      expect(
        (row.result, row.overrideReason, row.supervisorId, row.staffId),
        ('OVERRIDE', 'DAMAGED_TICKET', 's-2', staffId),
      );
      expect(httpCalls, 0);
      // Blue clears like green.
      await tester.pump(const Duration(milliseconds: JomhorMotion.greenAutoClearMs));
      await tester.pumpAndSettle();
      expect(find.text('DÉROGATION'), findsNothing);
      await done(tester);
    });

    testWidgets('a number not in the manifest offers nothing to admit', (tester) async {
      await pump(tester);
      await openSheet(tester);
      await tester.tap(find.text('Dérogation'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Autre motif'));
      await tester.pumpAndSettle();
      await typeNumber(tester, '0000 0000');
      expect(find.text('Aucun billet de ce numéro dans le manifeste.'), findsOneWidget);
      expect(find.byType(ListTile), findsNothing);
      await done(tester);
    });

    testWidgets('at 20 overrides the count is 20 / 20, Dérogation is off and the copy says why', (tester) async {
      await pump(tester, overrides: 20);
      await openSheet(tester);
      expect(find.text('Dérogations 20 / 20'), findsOneWidget);
      expect(find.text('Plafond atteint : 20 dérogations sur cet appareil pour cet événement.'), findsOneWidget);
      final button = tester.widget<ButtonStyleButton>(
        find.ancestor(of: find.text('Dérogation'), matching: find.bySubtype<ButtonStyleButton>()),
      );
      expect(button.onPressed, isNull);
      await done(tester);
    });

    testWidgets('a red result opens the override on its ticket number, no rescan', (tester) async {
      await pump(tester);
      await tester.runAsync(
        () =>
            ProviderScope.containerOf(tester.element(find.byType(ScanScreen)))
                .read(scanControllerProvider.notifier)
                .submit(staticToken(blocked)),
      );
      await tester.pumpAndSettle();
      expect(find.text('BLOQUÉ'), findsOneWidget);
      await openSheet(tester);
      await tester.tap(find.text('Dérogation'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Identité vérifiée'));
      await tester.pumpAndSettle();
      expect(find.text(ticketId(blocked).substring(28).toUpperCase()), findsOneWidget);
      await tester.tap(find.text('Chercher'));
      await settle(tester);
      await tester.tap(find.text('Tribune Nord'));
      await settle(tester);
      expect(find.text('DÉROGATION'), findsOneWidget);
      await done(tester);
    });

    testWidgets('Changer de porte: the manifest gates, the current one marked; the choice moves the top line', (
      tester,
    ) async {
      await pump(tester);
      expect(find.text('PORTE N1 · Nord · ENTRÉE'), findsOneWidget);
      await openSheet(tester);
      await tester.tap(find.text('Changer de porte'));
      await settle(tester);
      expect(find.text('Porte N1 · Nord'), findsOneWidget);
      expect(find.text('Actuelle'), findsOneWidget);
      await tester.tap(find.text('Porte V2 · Virage'));
      await settle(tester);
      expect(find.text('PORTE V2 · Virage · ENTRÉE'), findsOneWidget);
      expect((await tester.runAsync(db.readConfig))!.gateId, gates[2].gateId);
      expect(httpCalls, 0);
      await done(tester);
    });

    testWidgets('Recherche billet: zone, status and initials only, nothing to admit', (tester) async {
      await pump(tester);
      await openSheet(tester);
      await tester.tap(find.text('Recherche billet'));
      await tester.pumpAndSettle();
      await typeNumber(tester, ticketId(nord).substring(28));
      expect(find.text('Tribune Nord'), findsOneWidget);
      expect(find.text('Valide · Y. A.'), findsOneWidget);
      final shown = [for (final t in tester.widgetList<Text>(find.byType(Text))) ?t.data];
      expect(shown.where((s) => s.contains(ticketId(nord))), isEmpty, reason: 'never the ticket id');
      expect(shown.where((s) => s.contains('billets portent') || s.contains('touchez-le')), isEmpty);
      expect(tester.widget<ListTile>(find.byType(ListTile)).onTap, isNull);
      await done(tester);
    });

    testWidgets('ending a lookup drops the grant: Retour → the supervisor list, Dérogation needs the PIN again', (
      tester,
    ) async {
      await pump(tester);
      await openSheet(tester);
      await tester.tap(find.text('Recherche billet'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Retour'));
      await tester.pumpAndSettle();
      expect(find.text('Quel superviseur ?'), findsOneWidget);
      expect(find.text('Dérogation'), findsNothing);
      expect(find.text('Dérogations 0 / 20'), findsNothing);
      await tester.tap(find.text('Salma B.'));
      await tester.pumpAndSettle();
      expect(find.text('PIN de Salma B.'), findsOneWidget);
      await done(tester);
    });

    testWidgets('an override 61 s after the PIN is refused: nothing written, back to the PIN', (tester) async {
      await pump(tester);
      await openSheet(tester);
      await tester.tap(find.text('Dérogation'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Autre motif'));
      await tester.pumpAndSettle();
      await typeNumber(tester, ticketId(nord).substring(28));
      now += 61000;
      await tester.tap(find.text('Tribune Nord'));
      await settle(tester);
      expect(find.text('Session superviseur terminée : saisissez à nouveau le PIN.'), findsOneWidget);
      expect(find.text('Quel superviseur ?'), findsOneWidget);
      expect(find.text('DÉROGATION'), findsNothing);
      expect(await tester.runAsync(() => db.select(db.scans).get()), isEmpty);
      await done(tester);
    });

    testWidgets('an action button after 60 s asks for the PIN again', (tester) async {
      await pump(tester);
      await openSheet(tester);
      now += 61000;
      await tester.tap(find.text('Changer de porte'));
      await settle(tester);
      expect(find.text('Session superviseur terminée : saisissez à nouveau le PIN.'), findsOneWidget);
      expect(find.text('Porte N1 · Nord'), findsNothing);
      await done(tester);
    });

    testWidgets('the idle lock while the sheet is open removes it; the next unlock starts on the camera', (
      tester,
    ) async {
      await pump(tester);
      await tester.runAsync(() => session.unlock('s-1', '4821'));
      session.touch(); // The idle countdown on the test's fake clock.
      await openSheet(tester);
      expect(find.text('Dérogation'), findsOneWidget);

      await tester.pump(StaffSession.idleAfter);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
      await tester.pump(const Duration(seconds: 30)); // The scan screen's counters tick.
      await settle(tester);
      expect(find.text('Dérogation'), findsNothing);
      expect(find.text('Mode événement inactif'), findsOneWidget);

      await tester.runAsync(() => session.unlock('s-1', '4821'));
      await tester.pump(const Duration(seconds: 30));
      await settle(tester);
      expect(find.text('Présentez le QR du billet'), findsOneWidget);
      expect(find.text('Quel superviseur ?'), findsNothing);
      await done(tester);
    });

    testWidgets('a keyboard-wedge burst while the sheet shows its actions submits nothing', (tester) async {
      await pump(tester);
      await openSheet(tester);
      for (final char in staticToken(nord).split('')) {
        await tester.sendKeyEvent(LogicalKeyboardKey.keyA, character: char);
      }
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await settle(tester);
      expect(find.text('Dérogation'), findsOneWidget);
      expect(await tester.runAsync(() => db.select(db.scans).get()), isEmpty);
      expect(feedback, isEmpty);
      await done(tester);
    });

    testWidgets('Android back closes the sheet, not the app', (tester) async {
      await pump(tester);
      await openSheet(tester);
      await tester.binding.handlePopRoute();
      await settle(tester);
      expect(find.text('Dérogation'), findsNothing);
      expect(find.text('Présentez le QR du billet'), findsOneWidget);
      await done(tester);
    });
  });
}
