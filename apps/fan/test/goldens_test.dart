import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/app.dart';
import 'package:jomhor_fan/app/locale.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/features/queue/queue_screen.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/app_theme.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'cash_order_test.dart' show CashSale, orderId;
import 'club_test.dart' show ClubBackend, loyaltyJson, memberJson;
import 'gate_test.dart' show entryJson, gatedTicket, kickoff, loadJson;
import 'goldens.dart';
import 'helpers.dart';
import 'split_pay_test.dart' show SplitBackend, buyerId, karimId, memberId, omarId, saraId;
import 'tickets_test.dart' show otherId, slotStart, ticketId, ticketJson;
import 'transfer_test.dart' show openTicketJson, transferredJson;

/// FL-17: FR and AR goldens of screens 1, 4, 5 and the cash order; FL-18: screen 8, the transfer sheet, on its form
/// and mid-hold on its confirm step, and the "Transféré à" row in Billets (DESIGN.md Lot 4, AR / RTL); the login and
/// the top bar with the Jomhor lockup (design/BRAND.md). Next to the
/// pictures, the rules the pictures show are asserted: right to left, Western digits only, codes and prices in
/// left-to-right isolates. Multi-club (FL-23..25): screen 11, the club chips on screen 1, the club line on tickets.
/// FL-19: screen 5 gate side and "Tu es dedans". FL-20: screen 7 (two member cards, a non-member, the join sheet)
/// and the member strip on screen 1. FL-21: screen 4 with a friend paying their own place, screen 9 initiator (2/4)
/// and member (to pay, released). FL-22: screen 10 and the bell with its unread dot on screen 1 and the top bar
/// (bell then avatar at the end side, the left in AR). FL-26: screen 12 (pre-queue, waiting, the turn, not eligible)
/// and screen 2 with a room. Recorded on Linux with the docker command at the top of goldens.dart.
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
      // Its own preferences (FL-22: the inbox's last visit), dropped after.
      final before = SharedPreferencesAsyncPlatform.instance;
      SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
      addTearDown(() => SharedPreferencesAsyncPlatform.instance = before);
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
        // FL-20: a member of Atlas FC only, so the strip names it.
        ('GET', '/v1/fan/me/membership') => json({
          'member': r.url.queryParameters['organizer'] == testOrganizer ? memberJson() : null,
          'loyalty': loyaltyJson(tier: locale == 'ar' ? 'وفي' : 'Fidèle'),
        }),
        // FL-22: a message newer than the last visit (none yet): the bell's dot.
        ('GET', '/v1/fan/me/inbox') => json({
          'messages': [inboxJson()],
        }),
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
      expect(find.byKey(const Key('home.member')), findsOneWidget);
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.text('الماتش الجاي · فدارنا'), findsOneWidget);
        // The CTA price is an LTR isolate: "50 DH", never "DH 50".
        expect(find.text('شري بلايصك · من \u206650\u00A0DH\u2069'), findsOneWidget);
      }
      expect(find.byKey(const Key('home.bell.dot')), findsOneWidget);
      await snap('$locale-1-accueil');
      expectLockupAtStart(tester, find.byType(BrandBar));
      // FL-22: the bell, then the avatar, at the end side (the left in AR); the dot at the bell's top-end corner.
      final (bell, avatar, lockup, dot) = (
        tester.getRect(find.byKey(const Key('home.bell'))),
        tester.getRect(find.byKey(const Key('home.profile'))),
        tester.getRect(find.byType(JomhorLockup)),
        tester.getRect(find.byKey(const Key('home.bell.dot'))),
      );
      if (locale == 'ar') {
        expect((avatar.right <= bell.left, bell.right <= lockup.left), (true, true));
        expect(dot.center.dx, lessThan(bell.center.dx));
      } else {
        expect((lockup.right <= bell.left, bell.right <= avatar.left), (true, true));
        expect(dot.center.dx, greaterThan(bell.center.dx));
      }
      expect(dot.center.dy, lessThan(bell.center.dy));
      // The same bar alone, on the ground colour it sits on in the app.
      final bar = tester.widget<BrandBar>(find.byType(BrandBar));
      // FL-20: the member strip under "Mon billet", scrolled into view above the nav.
      await tester.scrollUntilVisible(
        find.byKey(const Key('home.member')),
        300,
        scrollable: find.ancestor(of: find.byKey(const Key('home.greeting')), matching: find.byType(Scrollable)).first,
      );
      await tester.pumpAndSettle();
      expect(
        tester.getRect(find.byKey(const Key('home.member'))).bottom,
        lessThan(tester.getRect(find.byType(NavigationBar)).top),
      );
      expect(
        find.text(
          locale == 'ar'
              ? 'رقم ${lookupAppLocalizations(const Locale('ar')).ltr('000042')} · وفي · 8 ماتشات'
              : 'N° 000042 · Fidèle · 8 matchs',
        ),
        findsOneWidget,
      );
      await snap('$locale-1-accueil-membre');
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

    // FL-22: screen 10, one inbox across clubs, each row led by its club line.
    testWidgets('$locale · screen 10 Notifications', (tester) async {
      await phone(tester);
      final before = SharedPreferencesAsyncPlatform.instance;
      SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
      addTearDown(() => SharedPreferencesAsyncPlatform.instance = before);
      final ar = locale == 'ar';
      Future<http.Response> backend(http.Request r) async => switch ((r.method, r.url.path)) {
        ('GET', '/v1/fan/me/inbox') => json({
          'messages': [
            inboxJson(
              title: ar ? 'البيبان كيتحلو مع 17:00' : 'Ouverture des portes à 17 h',
              body: ar
                  ? 'أجي بكري: كاين نشاط فالمدرج الشمالي قبل الماتش.'
                  : 'Viens tôt : animations en tribune Nord avant le match.',
              sentAt: '2027-02-20T11:48:00Z',
            ),
            inboxJson(
              id: 'c0000000-0000-4000-8000-000000000002',
              club: tensiftJson,
              title: ar ? 'التذاكر ديال الديربي حلات' : 'Billetterie du derby ouverte',
              body: ar ? 'الديربي نهار 20 مارس. شري بلاصتك دابا.' : "Le derby, c'est le 20 mars. Prends ta place.",
              eventId: 3,
              sentAt: '2027-02-20T09:00:00Z',
            ),
            inboxJson(
              id: 'c0000000-0000-4000-8000-000000000003',
              title: ar ? 'شكرا على الموسم' : 'Merci pour la saison',
              body: ar ? 'نتلاقاو فغشت.' : 'Rendez-vous en août pour la reprise.',
              eventId: null,
              sentAt: '2027-02-16T18:00:00Z',
            ),
          ],
        }),
        _ => json(<Object?>[]),
      };
      final c = await pumpJomhor(
        tester,
        backend: backend,
        tokens: MemoryTokenStore('jwt'),
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      c.read(routerProvider).go('/inbox');
      await tester.pumpAndSettle();
      if (ar) {
        expectArabicRules(tester);
        expect(find.text('هادي 12 دقيقة'), findsOneWidget);
      }
      await snap('$locale-10-notifications');
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

    // FL-20: screen 7, on a phone of its own (club.seen included), after the number has rolled and the dots filled.
    for (final (name, members, follows) in [
      ('7-club', {testOrganizer, otherOrganizer}, const [testOrganizer, otherOrganizer]),
      ('7-club-non-membre', <String>{}, const [testOrganizer]),
      ('7-club-rejoindre', <String>{}, const [testOrganizer]),
    ]) {
      testWidgets('$locale · screen $name', (tester) async {
        await phone(tester);
        final before = SharedPreferencesAsyncPlatform.instance;
        SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
        addTearDown(() => SharedPreferencesAsyncPlatform.instance = before);
        final ar = locale == 'ar';
        final backend = ClubBackend(
          members: {for (final id in members) id: memberJson(number: id == testOrganizer ? 42 : 1907)},
          loyalty: {
            for (final id in follows) id: loyaltyJson(tier: ar ? 'وفي' : 'Fidèle', nextTier: ar ? 'ركيزة' : 'Pilier'),
          },
        );
        final c = await pumpJomhor(
          tester,
          backend: backend.handle,
          tokens: MemoryTokenStore('jwt'),
          locale: Locale(locale),
          overrides: [initialLocaleProvider.overrideWithValue(locale)],
          follows: (ids: follows, seen: true, chip: null),
        );
        c.read(routerProvider).go('/club');
        await tester.pumpAndSettle();
        if (name == '7-club-rejoindre') {
          await tester.tap(find.byKey(const Key('club.join.$testOrganizer')));
          await tester.pumpAndSettle();
        }
        if (members.isNotEmpty) {
          // The number reads left to right in every locale.
          expect(tester.widget<Text>(find.byKey(const Key('club.number')).first).textDirection, TextDirection.ltr);
          expect(find.text('000042'), findsOneWidget);
        }
        // The season runs in reading direction: the twelfth (amber) dot at the end, on the left in AR.
        final (first, last) = (
          tester.getCenter(find.byKey(const Key('club.dot.attended.101'))).dx,
          tester.getCenter(find.byKey(const Key('club.dot.next'))).dx,
        );
        expect(ar ? last < first : first < last, isTrue);
        if (ar) expectArabicRules(tester);
        await snap('$locale-$name');
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

    // FL-21: split pay, each on a phone of its own (group.seen included).
    Future<void> split(WidgetTester tester, String location) async {
      await phone(tester);
      final before = SharedPreferencesAsyncPlatform.instance;
      SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
      addTearDown(() => SharedPreferencesAsyncPlatform.instance = before);
      final clock = TestClock(DateTime.utc(2027, 2, 20, 12));
      final backend = SplitBackend(clock);
      if (locale == 'ar') {
        backend
          ..labels.addAll({karimId: 'كريم ب.', saraId: 'سارة ب.', omarId: 'عمر ت.'})
          ..initiatorLabel = holder;
      }
      final c = await pumpJomhor(
        tester,
        backend: backend.handle,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      c.read(routerProvider).go(location);
      await tester.pumpAndSettle();
    }

    testWidgets('$locale · screen 4 split pay', (tester) async {
      await split(tester, '/events/1/checkout?price=pc-1-0&qty=2');
      await tester.enterText(find.byKey(const Key('holder.1.name')), locale == 'ar' ? 'سارة بنعلي' : 'Sara Benali');
      await tester.enterText(find.byKey(const Key('holder.1.phone')), '06 12 34 56 78');
      await tester.pump();
      await tester.ensureVisible(find.byKey(const Key('holder.1.pays')));
      await tester.tap(find.byKey(const Key('holder.1.pays')));
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pumpAndSettle();
      // Back to the top: the split summary and the switch on one screen.
      await tester.drag(find.byType(ListView).last, const Offset(0, 2000));
      await tester.pumpAndSettle();
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.textContaining('\u206650\u00A0DH\u2069'), findsWidgets, reason: 'prices stay LTR isolates');
      }
      await snap('$locale-4-paiement-partage');
    });

    testWidgets('$locale · screen 9 group, initiator', (tester) async {
      await split(tester, '/orders/$buyerId');
      // "2/4" reads left to right in every locale: the paid count sits left of "/4".
      expect(tester.widget<Text>(find.byKey(const Key('group.total'))).textDirection, TextDirection.ltr);
      expect(
        tester.getCenter(find.byKey(const Key('group.paid'))).dx,
        lessThan(tester.getCenter(find.byKey(const Key('group.total'))).dx),
      );
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.text('ك ب'), findsOneWidget, reason: 'Arabic initials spaced so they do not join');
      }
      await snap('$locale-9-groupe');
    });

    for (final (name, status) in [('9-membre', 'RESERVED'), ('9-membre-expire', 'EXPIRED')]) {
      testWidgets('$locale · screen $name', (tester) async {
        await phone(tester);
        final before = SharedPreferencesAsyncPlatform.instance;
        SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
        addTearDown(() => SharedPreferencesAsyncPlatform.instance = before);
        final clock = TestClock(DateTime.utc(2027, 2, 20, 12));
        final backend = SplitBackend(clock)
          ..memberStatus = status
          ..initiatorLabel = holder;
        final c = await pumpJomhor(
          tester,
          backend: backend.handle,
          tokens: MemoryTokenStore('jwt'),
          clock: clock,
          locale: Locale(locale),
          overrides: [initialLocaleProvider.overrideWithValue(locale)],
        );
        c.read(routerProvider).go('/orders/$memberId');
        await tester.pumpAndSettle();
        if (locale == 'ar') {
          expectArabicRules(tester);
          if (status == 'RESERVED') expect(find.text('يزيد ع. خلا ليك بلاصة'), findsOneWidget);
        }
        await snap('$locale-$name');
      });
    }

    /// FL-26: screen 12 and screen 2 with a room, on their own preferences (the install id). [location] opens; the
    /// room's answers are set by [arrange]. The light of the pre-queue dots loops, so these never wait to settle.
    Future<void> room(WidgetTester tester, String location, void Function(RoomSale sale) arrange) async {
      await phone(tester);
      final before = SharedPreferencesAsyncPlatform.instance;
      SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
      addTearDown(() => SharedPreferencesAsyncPlatform.instance = before);
      final sale = RoomSale(TestClock(DateTime.utc(2027, 2, 20, 12)));
      arrange(sale);
      final c = await pumpJomhor(
        tester,
        backend: sale.handle,
        tokens: MemoryTokenStore('jwt'),
        clock: sale.clock,
        locale: Locale(locale),
        overrides: [
          initialLocaleProvider.overrideWithValue(locale),
          wakelockProvider.overrideWithValue(({required on}) async {}),
        ],
      );
      c.read(routerProvider).go(location);
      for (var i = 0; i < 8; i++) {
        await tester.pump();
      }
      await tester.pump(const Duration(milliseconds: 400));
    }

    const rule = {'verified_before': '2027-02-13T12:30:00Z', 'member_joined_before': null, 'min_events_attended': 3};

    testWidgets('$locale · screen 12 pre-queue', (tester) async {
      await room(tester, '/events/1/queue', (sale) {
        sale.joins.add(() => sale.answer(sale.q(inRoom: 1204), 201));
      });
      if (locale == 'ar') expectArabicRules(tester);
      await snap('$locale-12-pre-file');
    });

    testWidgets('$locale · screen 12 waiting', (tester) async {
      await room(tester, '/events/1/queue', (sale) {
        sale
          ..room = roomJson(status: 'OPEN', eligibility: rule)
          ..joins.add(() => sale.answer(sale.q(status: 'WAITING', position: 2400, rate: 500, wait: 288), 201))
          ..polls.add(() => sale.answer(sale.q(status: 'WAITING', position: 1204, rate: 500, wait: 150)));
      });
      await tester.pump(const Duration(seconds: 5));
      for (var i = 0; i < 8; i++) {
        await tester.pump();
      }
      await tester.pump(const Duration(milliseconds: 400));
      if (locale == 'ar') expectArabicRules(tester);
      await snap('$locale-12-attente');
    });

    testWidgets('$locale · screen 12 your turn', (tester) async {
      await room(tester, '/events/1/queue', (sale) {
        sale
          ..room = roomJson(status: 'OPEN')
          ..joins.add(() => sale.answer(sale.q(status: 'WAITING', position: 3, rate: 500, wait: 1), 201))
          ..polls.add(() => sale.answer(sale.q(status: 'ADMITTED', turn: const Duration(minutes: 10), pollAfter: 0)));
      });
      await tester.pump(const Duration(seconds: 5));
      for (var i = 0; i < 8; i++) {
        await tester.pump();
      }
      await tester.pump(const Duration(milliseconds: 600));
      if (locale == 'ar') expectArabicRules(tester);
      await snap('$locale-12-ton-tour');
    });

    testWidgets('$locale · screen 12 not eligible', (tester) async {
      await room(tester, '/events/1/queue', (sale) {
        sale
          ..room = roomJson(eligibility: rule)
          ..joins.add(
            () => sale.refusal(409, 'NOT_ELIGIBLE', {
              'reason': 'ACCOUNT_TOO_NEW',
              'verified_before': '2027-02-13T12:30:00Z',
            }),
          );
      });
      if (locale == 'ar') expectArabicRules(tester);
      await snap('$locale-12-non-eligible');
    });

    testWidgets('$locale · screen 2 with a room', (tester) async {
      await room(tester, '/events/1', (sale) => sale.room = roomJson(eligibility: rule));
      await tester.pumpAndSettle();
      if (locale == 'ar') expectArabicRules(tester);
      await snap('$locale-2-file-attente');
    });
  }
}
