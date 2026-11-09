import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/app.dart';
import 'package:jomhor_fan/app/locale.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/theme/app_theme.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'cash_order_test.dart' show CashSale, orderId;
import 'gate_test.dart' show entryJson, gatedTicket, kickoff, loadJson;
import 'goldens.dart';
import 'helpers.dart';
import 'tickets_test.dart' show otherId, slotStart, ticketId, ticketJson;
import 'transfer_test.dart' show openTicketJson, transferredJson;

/// FL-17: FR and AR goldens of screens 1, 4, 5 and the cash order; FL-18: screen 8, the transfer sheet, on its form
/// and mid-hold on its confirm step, and the "Transféré à" row in Billets (DESIGN.md Lot 4, AR / RTL); the login and
/// the top bar with the Jomhor lockup (design/BRAND.md). Next to the
/// pictures, the rules the pictures show are asserted: right to left, Western digits only, codes and prices in
/// left-to-right isolates. Multi-club (FL-23..25): screen 11, the club chips on screen 1, the club line on tickets.
/// FL-19: screen 5 gate side and "Tu es dedans". Recorded on Linux with the docker command at the top of goldens.dart.
void main() {
  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    await loadAppFonts();
    LinuxGoldenComparator.install('goldens_test.dart');
  });

  /// Every string on screen (Text and RichText).
  Iterable<String> shownText(WidgetTester tester) =>
      tester.widgetList<RichText>(find.byType(RichText)).map((r) => r.text.toPlainText(includeSemanticsLabels: false));

  /// The rules every AR golden also checks in code.
  void expectArabicRules(WidgetTester tester) {
    expect(Directionality.of(tester.element(find.byType(Scaffold).last)), TextDirection.rtl);
    final text = shownText(tester).join('\n');
    expect(text, isNot(matches(RegExp('[٠-٩۰-۹]'))), reason: 'Western digits only');
  }

  Future<void> phone(WidgetTester tester) async {
    tester.view
      ..physicalSize = const Size(JomhorSize.phoneWidth, JomhorSize.phoneHeight)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }

  /// The lockup sits at the start of [row]: the left in FR, the right in AR (a picture, never mirrored).
  void expectLockupAtStart(WidgetTester tester, Finder row) {
    final rtl = Directionality.of(tester.element(row)) == TextDirection.rtl;
    final (box, lockup) = (
      tester.getRect(row),
      tester.getRect(find.descendant(of: row, matching: find.byType(JomhorLockup))),
    );
    expect(rtl ? box.right - lockup.right : lockup.left - box.left, lessThanOrEqualTo(JomhorSpace.xl));
  }

  Future<void> snap(String name) => expectLater(find.byType(JomhorApp), matchesGoldenFile('goldens/$name.png'));

  for (final locale in ['fr', 'ar']) {
    final holder = locale == 'ar' ? 'يزيد ع.' : 'Yazid A.';

    testWidgets('$locale · screen 1 Accueil', (tester) async {
      await phone(tester);
      final clock = TestClock(DateTime.utc(2027, 2, 20, 12));
      Future<http.Response> backend(http.Request r) async => switch ((r.method, r.url.path)) {
        ('GET', '/v1/fan/organizers') => json([clubJson(), tensiftJson]),
        ('GET', '/v1/fan/events') => json([
          eventJson(),
          eventJson(
            id: 3,
            title: 'Olympique Tensift vs Union Rif',
            startsAt: '2027-03-20T19:00:00Z',
            metadata: {'opponent': 'Union Rif', 'competition': 'Championnat', 'matchday': 12},
            club: tensiftJson,
          ),
          eventJson(
            id: 2,
            title: 'Atlas FC vs Union Sahel',
            startsAt: '2027-04-03T19:00:00Z',
            metadata: {'opponent': 'Union Sahel', 'competition': 'Championnat', 'matchday': 13},
          ),
        ]),
        ('GET', '/v1/fan/me') => json({'id': 'u1', 'phone': '+212600000001', 'display_name': 'Yazid Aqel'}),
        ('GET', '/v1/fan/tickets') => json({
          'server_time': clock.now.toIso8601String(),
          'tickets': [
            {...ticketJson(), 'holder_label': holder},
          ],
        }),
        ('GET', '/v1/fan/orders') => json({'orders': <Object?>[]}),
        _ => apiError(404, 'NOT_FOUND'),
      };
      await pumpJomhor(
        tester,
        backend: backend,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
        follows: (ids: const [testOrganizer, otherOrganizer], seen: true, chip: null),
      );
      // Mes clubs · Atlas FC · Olympique Tensift · Tout, Mes clubs selected (DESIGN.md §Multi-club adjustments).
      expect(find.byKey(const Key('home.chip.mine')), findsOneWidget);
      expect(find.byKey(const Key('home.chip.$otherOrganizer')), findsOneWidget);
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.text('الماتش الجاي · فدارنا'), findsOneWidget);
        // The CTA price is an LTR isolate: "50 DH", never "DH 50".
        expect(find.text('شري بلايصك · من \u206650\u00A0DH\u2069'), findsOneWidget);
      }
      await snap('$locale-1-accueil');
      expectLockupAtStart(tester, find.byType(BrandBar));
      // The same bar alone, on the ground colour it sits on in the app.
      final bar = tester.widget<BrandBar>(find.byType(BrandBar));
      await tester.pumpWidget(
        MaterialApp(
          theme: buildAppTheme(),
          home: Directionality(
            textDirection: locale == 'ar' ? TextDirection.rtl : TextDirection.ltr,
            child: Align(
              alignment: Alignment.topCenter,
              child: RepaintBoundary(
                key: const Key('topbar'),
                child: ColoredBox(
                  color: JomhorColors.ground,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.xl),
                    child: bar,
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await expectLater(find.byKey(const Key('topbar')), matchesGoldenFile('goldens/$locale-topbar.png'));
    });

    testWidgets('$locale · screen 11 Tes clubs (first launch, one club followed)', (tester) async {
      await phone(tester);
      final c = await pumpJomhor(
        tester,
        backend: (r) async => r.url.path == '/v1/fan/organizers'
            ? json([
                clubJson(),
                tensiftJson,
                clubJson(id: 'org-3', slug: 'grand-theatre', name: 'Grand Théâtre', kind: 'VENUE'),
              ])
            : json(<Object?>[]),
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
        follows: (ids: const [otherOrganizer], seen: false, chip: null),
      );
      expect(c.read(routerProvider).state.uri.path, '/clubs');
      expect(tester.widget<FilledButton>(find.byKey(const Key('clubs.continue'))).onPressed, isNotNull);
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.text('فرقك'), findsOneWidget);
      }
      expectLockupAtStart(tester, find.byType(CustomScrollView));
      await snap('$locale-11-clubs');
    });

    testWidgets('$locale · login', (tester) async {
      await phone(tester);
      final c = await pumpJomhor(
        tester,
        backend: (_) async => json(<Object?>[]),
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      c.read(routerProvider).go('/login');
      await tester.pumpAndSettle();
      expect(find.text(locale == 'ar' ? 'جمهور. الرجل الثاني عشر.' : 'Jomhor. Le 12e homme.'), findsOneWidget);
      expect(find.textContaining('Atlas'), findsNothing); // The club is content, not the app's identity.
      if (locale == 'ar') expectArabicRules(tester);
      expectLockupAtStart(tester, find.byType(ListView));
      await snap('$locale-login');
    });

    testWidgets('$locale · screen 4 Paiement', (tester) async {
      await phone(tester);
      final clock = TestClock(DateTime.utc(2027, 2, 20, 12));
      final sale = CashSale(clock);
      final c = await pumpJomhor(
        tester,
        backend: sale.handle,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      c.read(routerProvider).go('/events/1/checkout?price=pc-1-0&qty=2');
      await tester.pumpAndSettle();
      if (locale == 'ar') {
        expectArabicRules(tester);
        // The back arrow follows the reading direction (it points right in AR).
        expect(Directionality.of(tester.element(find.byType(BackButtonIcon))), TextDirection.rtl);
        expect(find.text('\u2066100\u00A0DH\u2069'), findsWidgets);
      }
      await snap('$locale-4-paiement');
    });

    testWidgets('$locale · screen 5 Mon billet', (tester) async {
      await phone(tester);
      final clock = TestClock(slotStart.add(const Duration(seconds: 2)));
      Future<http.Response> backend(http.Request r) async => switch ((r.method, r.url.path)) {
        ('GET', '/v1/fan/tickets') => json({
          'server_time': clock.now.toIso8601String(),
          'tickets': [
            {...openTicketJson(), 'holder_label': holder},
          ],
        }),
        _ => json({'orders': <Object?>[]}),
      };
      final c = await pumpJomhor(
        tester,
        backend: backend,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      unawaited(c.read(routerProvider).push('/tickets/$ticketId'));
      await tester.pumpAndSettle();
      expect(find.text('EEEE EEEE'), findsOneWidget); // The ticket number, LTR in every locale.
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.text('يزيد ع.'), findsOneWidget); // An Arabic label needs no isolate in AR.
        expect(tester.widget<Text>(find.text('EEEE EEEE')).textDirection, TextDirection.ltr);
      }
      await snap('$locale-5-billet');
    });

    // FL-19: the gate side (live chips) and "Tu es dedans", on matchday inside the load window.
    for (final (face, entered) in [('porte', false), ('dedans', true)]) {
      testWidgets('$locale · screen 5 $face', (tester) async {
        await phone(tester);
        // Its own preferences, dropped after: the entry saved here must not reach the next tests.
        final before = SharedPreferencesAsyncPlatform.instance;
        SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
        addTearDown(() => SharedPreferencesAsyncPlatform.instance = before);
        final open = kickoff.subtract(const Duration(minutes: 90));
        final clock = TestClock(open);
        Future<http.Response> backend(http.Request r) async => switch ((r.method, r.url.path)) {
          ('GET', '/v1/fan/tickets') => json({
            'server_time': clock.now.toIso8601String(),
            'tickets': [
              {...gatedTicket(), 'holder_label': holder},
            ],
          }),
          ('GET', '/v1/fan/gates/7/load') => json(loadJson(open, entries: [if (entered) entryJson()])),
          _ => json(<Object?>[]),
        };
        final c = await pumpJomhor(
          tester,
          backend: backend,
          tokens: MemoryTokenStore('jwt'),
          clock: clock,
          locale: Locale(locale),
          overrides: [initialLocaleProvider.overrideWithValue(locale)],
        );
        expect(c.read(routerProvider).state.uri.path, '/tickets/$ticketId'); // matchday: straight on the ticket
        if (!entered) {
          await tester.tap(find.byKey(const Key('ticket.gate')));
        }
        await tester.pumpAndSettle();
        if (locale == 'ar') {
          expectArabicRules(tester);
          if (entered) expect(find.text('راك داخل'), findsOneWidget);
        }
        await snap('$locale-5-billet-$face');
      });
    }

    testWidgets('$locale · screen 8 Transférer', (tester) async {
      await phone(tester);
      final clock = TestClock(slotStart.add(const Duration(seconds: 2)));
      Future<http.Response> backend(http.Request r) async => switch ((r.method, r.url.path)) {
        ('GET', '/v1/fan/tickets') => json({
          'server_time': clock.now.toIso8601String(),
          'tickets': [
            {...openTicketJson(), 'holder_label': holder},
          ],
        }),
        _ => json({'orders': <Object?>[]}),
      };
      final c = await pumpJomhor(
        tester,
        backend: backend,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      unawaited(c.read(routerProvider).push('/tickets/$ticketId'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('ticket.transfer')));
      await tester.pumpAndSettle();
      if (locale == 'ar') expectArabicRules(tester);
      await snap('$locale-8-transfert');

      await tester.enterText(find.byKey(const Key('transfer.phone')), '06 61 23 45 67');
      await tester.enterText(find.byKey(const Key('transfer.name')), locale == 'ar' ? 'كريم بنعلي' : 'Karim Benali');
      await tester.ensureVisible(find.byKey(const Key('transfer.next')));
      await tester.tap(find.byKey(const Key('transfer.next')));
      await tester.pumpAndSettle();
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pumpAndSettle();
      // The phone stays left to right inside the mirrored sheet.
      expect(tester.widget<Text>(find.byKey(const Key('transfer.confirmPhone'))).textDirection, TextDirection.ltr);
      if (locale == 'ar') expectArabicRules(tester);
      await tester.ensureVisible(find.byKey(const Key('transfer.hold')));
      await tester.pumpAndSettle();
      // Mid-hold: the fill has covered about half of the button, from the reading start.
      final finger = await tester.startGesture(tester.getCenter(find.byKey(const Key('transfer.hold'))));
      for (var i = 0; i < 20; i++) {
        await tester.pump(const Duration(milliseconds: 20));
      }
      await snap('$locale-8-transfert-hold');
      await finger.cancel();
      await tester.pumpAndSettle();
    });

    testWidgets('$locale · Billets with a transferred row', (tester) async {
      await phone(tester);
      final clock = TestClock(slotStart.add(const Duration(seconds: 2)));
      Future<http.Response> backend(http.Request r) async => switch ((r.method, r.url.path)) {
        ('GET', '/v1/fan/tickets') => json({
          'server_time': clock.now.toIso8601String(),
          'tickets': [
            {...openTicketJson(), 'holder_label': holder},
            transferredJson(id: otherId, toLabel: locale == 'ar' ? 'كريم ب.' : 'Karim B.'),
          ],
        }),
        _ => json({'orders': <Object?>[]}),
      };
      final c = await pumpJomhor(
        tester,
        backend: backend,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      c.read(routerProvider).go('/tickets');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('ticket.transferred.$otherId')), findsOneWidget);
      expect(find.text('FFFF FFFF'), findsOneWidget); // The ticket number, LTR in every locale.
      expect(tester.widget<Text>(find.text('FFFF FFFF')).textDirection, TextDirection.ltr);
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.textContaining('تحولات ل كريم ب. · '), findsOneWidget); // An Arabic name needs no isolate in AR.
      } else {
        expect(find.textContaining('Transféré à Karim B. · '), findsOneWidget);
      }
      await snap('$locale-billets-transfere');
    });

    testWidgets('$locale · cash order', (tester) async {
      await phone(tester);
      final clock = TestClock(DateTime.utc(2027, 2, 20, 12));
      final sale = CashSale(clock)
        ..status = 'AWAITING_CASH'
        ..paymentMethod = 'CASH_AGENCY'
        ..paymentStatus = 'PENDING'
        ..reservedUntil = clock.now.add(const Duration(hours: 24));
      final c = await pumpJomhor(
        tester,
        backend: sale.handle,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      c.read(routerProvider).go('/orders/$orderId');
      await tester.pumpAndSettle();
      expect(tester.widget<Text>(find.byKey(const Key('cash.code'))).textDirection, TextDirection.ltr);
      expect(tester.widget<Text>(find.byKey(const Key('cash.code'))).data, 'JMH 4F7K 29');
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.text('المبلغ بالضبط: \u2066100\u00A0DH\u2069'), findsOneWidget);
      }
      await snap('$locale-cash-order');
    });
  }
}
