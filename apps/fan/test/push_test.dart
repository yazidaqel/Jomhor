import 'package:firebase_messaging/firebase_messaging.dart' show AuthorizationStatus;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/push/push.dart';
import 'package:jomhor_fan/core/push/push_banner.dart';
import 'package:jomhor_fan/features/checkout/order_screen.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/features/events/event_screen.dart';
import 'package:jomhor_fan/features/events/home_screen.dart';
import 'package:jomhor_fan/features/inbox/inbox.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'cash_order_test.dart' show CashSale, orderId;
import 'club_test.dart' show ClubBackend;
import 'helpers.dart';
import 'split_pay_test.dart' show SplitBackend, buyerId;
import 'tickets_test.dart' show ticketId, ticketJson;

/// FL-22: push registration, permission timing, deep links and the foreground banner (docs/04-api.md
/// `PUT /fan/me/push-token` and §Notifications push wire; lot-4 Q119-Q126), with a fake push provider.
void main() {
  setUp(() => SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty());

  Future<http.Response> base(http.Request r) async => switch ((r.method, r.url.path)) {
    ('GET', '/v1/fan/events') => json([eventJson()]),
    ('GET', '/v1/fan/events/12') => json({
      ...eventJson(
        id: 12,
        zones: [
          zoneJson(1, 'Tribune Nord', [(5000, 100)]),
        ],
      ),
      'payment_methods': ['CARD'],
      'cash_until': null,
    }),
    ('GET', '/v1/fan/tickets') => json({
      'server_time': '2027-02-20T12:00:00Z',
      'tickets': [ticketJson()],
    }),
    ('GET', '/v1/fan/orders') => json({'orders': <Object?>[]}),
    ('POST', '/v1/fan/me/logout') => http.Response('', 204),
    _ => apiError(404, 'NOT_FOUND'),
  };

  Future<ProviderContainer> open(
    WidgetTester tester,
    PushBackend backend, {
    FakePushMessaging? push,
    bool signedIn = true,
  }) => pumpJomhor(
    tester,
    backend: backend.handle,
    tokens: MemoryTokenStore(signedIn ? 'jwt' : null),
    overrides: [if (push != null) pushMessagingProvider.overrideWithValue(push)],
  );

  String location(ProviderContainer c) => c.read(routerProvider).state.uri.toString();

  group('push off', () {
    test('without PUSH_ENABLED the Firebase initializer is never called', () async {
      var inits = 0;
      expect(await startPush(init: () async => inits++), isNull);
      expect(inits, 0);
    });

    test('an initializer that throws starts the app with push off', () async {
      expect(await startPush(enabled: true, init: () => throw StateError('no google services')), isNull);
      expect(await startPush(enabled: true, init: () async {}), isA<FirebasePushMessaging>());
    });

    test('this build has push off (no --dart-define=PUSH_ENABLED=true)', () {
      expect(const bool.fromEnvironment('PUSH_ENABLED'), isFalse);
    });

    testWidgets('no PUT, no permission; the bell and the inbox still work', (tester) async {
      final backend = PushBackend(base)..inbox = [inboxJson()];
      final c = await open(tester, backend);
      expect(c.read(pushMessagingProvider), isNull);
      expect(backend.tokenPuts, isEmpty);
      expect(find.byKey(const Key('home.bell.dot')), findsOneWidget);
      await tester.tap(find.byKey(const Key('home.bell')));
      await tester.pumpAndSettle();
      expect(find.text('Ouverture des portes à 17 h'), findsOneWidget);
    });
  });

  group('registration', () {
    testWidgets('login → exactly one PUT {token, platform}; a relaunch with the same token sends nothing', (
      tester,
    ) async {
      final backend = PushBackend(base);
      final push = FakePushMessaging();
      final c = await open(tester, backend, push: push, signedIn: false);
      expect(backend.tokenPuts, isEmpty);
      await c.read(sessionProvider.notifier).signIn('jwt');
      await tester.pumpAndSettle();
      expect(backend.tokenPuts, hasLength(1));
      expect(backend.tokenPuts.single.keys.toSet(), {'token', 'platform'});
      expect(backend.tokenPuts.single, {'token': 'fcm-token-1', 'platform': 'android'});
      expect(await SharedPreferencesAsync().getString(pushSentKey), 'fcm-token-1');

      await tester.pumpWidget(const SizedBox());
      await open(tester, backend, push: FakePushMessaging());
      expect(backend.tokenPuts, hasLength(1));
    });

    testWidgets('a refreshed token → one PUT with it; follows never send one', (tester) async {
      final backend = PushBackend(base);
      final push = FakePushMessaging();
      final c = await open(tester, backend, push: push);
      expect(backend.tokenPuts, hasLength(1));
      push.refresh.add('fcm-token-2');
      await tester.pumpAndSettle();
      expect(backend.tokenPuts.map((b) => b['token']), ['fcm-token-1', 'fcm-token-2']);

      await c.read(followsProvider.notifier).toggle(testOrganizer);
      await tester.pumpAndSettle();
      await c.read(followsProvider.notifier).toggle(testOrganizer);
      await tester.pumpAndSettle();
      expect(backend.tokenPuts, hasLength(2));
    });

    testWidgets('logout: POST /fan/me/logout, then deleteToken; a later refresh sends nothing; re-login sends again', (
      tester,
    ) async {
      final log = <String>[];
      final backend = PushBackend(base, log: log);
      final push = FakePushMessaging(log: log);
      final c = await open(tester, backend, push: push);
      expect(backend.tokenPuts, hasLength(1));

      await c.read(sessionProvider.notifier).logout();
      await tester.pumpAndSettle();
      expect(log.indexOf('POST /v1/fan/me/logout'), lessThan(log.indexOf('push.deleteToken')));
      expect(await SharedPreferencesAsync().getString(pushSentKey), isNull);

      push.refresh.add('fcm-token-2');
      await tester.pumpAndSettle();
      expect(backend.tokenPuts, hasLength(1));

      push.currentToken = 'fcm-token-3';
      await c.read(sessionProvider.notifier).signIn('jwt2');
      await tester.pumpAndSettle();
      expect(backend.tokenPuts.map((b) => b['token']), ['fcm-token-1', 'fcm-token-3']);
    });

    testWidgets('a 401 takes the same path: signed out, token deleted, no more PUT', (tester) async {
      final backend = PushBackend(base);
      final push = FakePushMessaging();
      final c = await open(tester, backend, push: push);
      backend.inboxStatus = 401;
      c.invalidate(inboxProvider);
      await tester.pumpAndSettle();
      expect(c.read(sessionProvider), isFalse);
      expect(push.count('deleteToken'), 1);
      expect(await SharedPreferencesAsync().getString(pushSentKey), isNull);
      push.refresh.add('fcm-token-2');
      await tester.pumpAndSettle();
      expect(backend.tokenPuts, hasLength(1));
    });
  });

  group('permission', () {
    testWidgets('never at launch; on the first PAID order screen only, once per install', (tester) async {
      final sale = CashSale(TestClock(DateTime.utc(2027, 2, 20, 12)))..status = 'PAID';
      final push = FakePushMessaging();
      final c = await open(tester, PushBackend(sale.handle), push: push);
      expect(push.count('requestPermission'), 0);

      c.read(routerProvider).go('/orders/$orderId');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('order.paid')), findsOneWidget);
      expect(push.count('requestPermission'), 1);

      c.read(routerProvider).go('/');
      await tester.pumpAndSettle();
      c.read(routerProvider).go('/orders/$orderId');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('order.paid')), findsOneWidget);
      expect(push.count('requestPermission'), 1);
    });

    testWidgets('a paid split initiator (screen 9, never the paid card) is asked too', (tester) async {
      final push = FakePushMessaging();
      final backend = SplitBackend(TestClock(DateTime.utc(2027, 2, 20, 12)));
      final c = await pumpJomhor(
        tester,
        backend: PushBackend(backend.handle).handle,
        tokens: MemoryTokenStore('jwt'),
        overrides: [pushMessagingProvider.overrideWithValue(push)],
      );
      c.read(routerProvider).go('/orders/$buyerId');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('group.total')), findsOneWidget);
      expect(find.byKey(const Key('order.paid')), findsNothing);
      expect(push.count('requestPermission'), 1);
      // Screen 9 polls every 10 s: later reads of the same PAID order do not ask again.
      await tester.pump(const Duration(seconds: 25));
      await tester.pumpAndSettle();
      expect(push.count('requestPermission'), 1);
    });

    for (final pushOn in [true, false]) {
      testWidgets('a join with push ${pushOn ? 'on asks' : 'off does not ask'}', (tester) async {
        tester.view
          ..physicalSize = const Size(390, 1600)
          ..devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final push = FakePushMessaging();
        final c = await pumpJomhor(
          tester,
          backend: PushBackend(ClubBackend().handle).handle,
          tokens: MemoryTokenStore('jwt'),
          follows: (ids: const [testOrganizer], seen: true, chip: chipAll),
          overrides: [pushMessagingProvider.overrideWithValue(push)],
        );
        c.read(routerProvider).go('/club');
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('club.join.$testOrganizer')));
        await tester.pumpAndSettle();
        if (pushOn) await tester.tap(find.byKey(const Key('club.optin.push')));
        await tester.pump();
        await tester.tap(find.byKey(const Key('club.joinConfirm')));
        await tester.pumpAndSettle();
        expect(push.count('requestPermission'), pushOn ? 1 : 0);
      });
    }

    testWidgets('a join with push on after a purchase does not ask again', (tester) async {
      await SharedPreferencesAsync().setBool(pushAskedKey, true);
      final push = FakePushMessaging();
      await maybeAskPermission(push);
      await maybeAskPermission(null);
      expect(push.count('requestPermission'), 0);
    });
  });

  group('Profil row (lot-4 Q123)', () {
    Future<void> profile(WidgetTester tester, FakePushMessaging? push) async {
      final c = await open(tester, PushBackend(base), push: push);
      c.read(routerProvider).go('/profile');
      await tester.pumpAndSettle();
    }

    testWidgets('not asked: "Activer" asks, then "Activées"', (tester) async {
      final push = FakePushMessaging();
      await profile(tester, push);
      expect(find.text('Billets reçus, paiements et messages de tes clubs'), findsOneWidget);
      await tester.ensureVisible(find.byKey(const Key('profile.push.enable')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('profile.push.enable')));
      await tester.pumpAndSettle();
      expect(push.count('requestPermission'), 1);
      expect(find.text('Activées'), findsOneWidget);
      expect(find.byKey(const Key('profile.push.enable')), findsNothing);
    });

    testWidgets('the prompt refused (or Android below 13, where none exists): the phone settings, no button', (
      tester,
    ) async {
      final push = FakePushMessaging()..grant = false;
      await profile(tester, push);
      await tester.ensureVisible(find.byKey(const Key('profile.push.enable')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('profile.push.enable')));
      await tester.pumpAndSettle();
      expect(find.text('Active-les dans les réglages du téléphone'), findsOneWidget);
      expect(find.byKey(const Key('profile.push.enable')), findsNothing);
    });

    test("firebase_messaging's statuses", () {
      PushPermission of(AuthorizationStatus s, {bool ios = false, bool refused = false}) =>
          permissionOf(s, ios: ios, refused: refused);
      expect(of(AuthorizationStatus.authorized), PushPermission.granted);
      expect(of(AuthorizationStatus.provisional, ios: true), PushPermission.granted);
      expect(of(AuthorizationStatus.notDetermined), PushPermission.notAsked);
      // Android 13+ after one refusal, the OS can still prompt: "Activer".
      expect(of(AuthorizationStatus.denied), PushPermission.notAsked);
      // Android below 13 (notifications off, no prompt exists) once "Activer" came back refused: the settings.
      expect(of(AuthorizationStatus.denied, refused: true), PushPermission.denied);
      expect(of(AuthorizationStatus.deniedPermanently), PushPermission.denied);
      expect(of(AuthorizationStatus.denied, ios: true), PushPermission.denied);
    });

    testWidgets('refused for good: the phone settings, no button', (tester) async {
      await profile(tester, FakePushMessaging(status: PushPermission.denied));
      expect(find.text('Active-les dans les réglages du téléphone'), findsOneWidget);
      expect(find.byKey(const Key('profile.push.enable')), findsNothing);
    });

    testWidgets('push off: no row', (tester) async {
      await profile(tester, null);
      expect(find.byKey(const Key('profile.push')), findsNothing);
    });
  });

  group('tap routing', () {
    test('only the push wire paths open', () {
      const uuid = '0b8e7a52-0000-4000-8000-0000000000cc';
      for (final ok in ['/tickets/$uuid', '/orders/$uuid', '/events/12', '/']) {
        expect(pushPathOf({'path': ok}), ok);
      }
      for (final bad in [
        '/profile',
        '//evil',
        'https://x',
        '/events/12/checkout',
        '/tickets/../profile',
        '/tickets/${uuid.toUpperCase()}',
        '/events/abc',
        '',
        'junk',
        null,
        12,
      ]) {
        expect(pushPathOf({'path': bad}), isNull, reason: '$bad');
      }
      expect(pushPathOf({}), isNull);
    });

    testWidgets('/tickets, /orders, /events, / open their screen; the ticket is read again first', (tester) async {
      final sale = CashSale(TestClock(DateTime.utc(2027, 2, 20, 12)))..status = 'PAID';
      Future<http.Response> backend(http.Request r) =>
          r.url.path.startsWith('/v1/fan/orders/') ? sale.handle(r) : base(r);
      final requests = <String>[];
      final push = FakePushMessaging();
      final c = await open(
        tester,
        PushBackend((r) {
          requests.add(r.url.path);
          return backend(r);
        }),
        push: push,
      );
      Future<void> tap(String path) async {
        push.opened.add(PushMessage(title: 't', body: 'b', data: {'path': path}));
        await tester.pumpAndSettle();
      }

      final reads = requests.where((p) => p == '/v1/fan/tickets').length;
      await tap('/tickets/$ticketId');
      expect(find.byType(TicketScreen), findsOneWidget);
      expect(requests.where((p) => p == '/v1/fan/tickets').length, greaterThan(reads));

      await tap('/orders/$orderId');
      expect(find.byType(OrderScreen), findsOneWidget);
      await tap('/events/12');
      expect(find.byType(EventScreen), findsOneWidget);
      expect(location(c), '/events/12');
      await tap('/');
      expect(location(c), '/');
      expect(find.byType(HomeScreen), findsOneWidget);

      for (final bad in ['/profile', '//evil', 'https://x', 'junk']) {
        await tap(bad);
        expect(location(c), '/', reason: bad);
      }
    });

    testWidgets('a cold start from a notification opens its path', (tester) async {
      final push = FakePushMessaging(launch: const PushMessage(data: {'path': '/events/12'}));
      final c = await open(tester, PushBackend(base), push: push);
      expect(location(c), '/events/12');
      expect(push.count('initialMessage'), 1);
    });

    testWidgets('signed out: the login, then the path', (tester) async {
      final push = FakePushMessaging();
      final c = await open(tester, PushBackend(base), push: push, signedIn: false);
      push.opened.add(const PushMessage(data: {'path': '/tickets/$ticketId'}));
      await tester.pumpAndSettle();
      expect(location(c), Uri(path: '/login', queryParameters: {'from': '/tickets/$ticketId'}).toString());
    });
  });

  group('foreground', () {
    // BE-30's ORDER_PAID copy (fr); a campaign path, so the tap lands on a screen the test backend serves.
    const message = PushMessage(title: 'Paiement reçu', body: 'Tes billets sont prêts', data: {'path': '/events/12'});

    testWidgets('a banner with title and body; the inbox is read again; gone after 4 s', (tester) async {
      final backend = PushBackend(base);
      final push = FakePushMessaging();
      await open(tester, backend, push: push);
      final reads = backend.inboxReads;
      push.foreground.add(message);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('push.banner')), findsOneWidget);
      expect(find.text(message.title!), findsOneWidget);
      expect(find.text(message.body!), findsOneWidget);
      expect(backend.inboxReads, reads + 1);
      await tester.pump(PushBannerHost.shownFor);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('push.banner')), findsNothing);
    });

    testWidgets('ar copy shows as sent: FSI/PDI isolates kept, Western digits', (tester) async {
      final push = FakePushMessaging();
      await open(tester, PushBackend(base), push: push);
      const title = '\u2068يزيد ع.\u2069 خلا ليك بلاصة';
      const body = 'أطلس ضد الرابيد · \u2068250,50\u00A0DH\u2069 · قبل \u20681 ماي 21:30\u2069';
      push.foreground.add(const PushMessage(title: title, body: body, data: {'path': '/orders/$orderId'}));
      await tester.pumpAndSettle();
      expect(find.text(title), findsOneWidget);
      expect(find.text(body), findsOneWidget);
      await tester.pump(PushBannerHost.shownFor);
      await tester.pumpAndSettle();
    });

    testWidgets('a tap opens its path', (tester) async {
      final push = FakePushMessaging();
      final c = await open(tester, PushBackend(base), push: push);
      push.foreground.add(message);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('push.banner')));
      await tester.pumpAndSettle();
      expect(location(c), '/events/12');
      expect(find.byKey(const Key('push.banner')), findsNothing);
    });

    testWidgets('never over screen 5 (the QR); the inbox still refreshes', (tester) async {
      final backend = PushBackend(base);
      final push = FakePushMessaging();
      final c = await open(tester, backend, push: push);
      c.read(routerProvider).go('/tickets/$ticketId');
      await tester.pumpAndSettle();
      final reads = backend.inboxReads;
      push.foreground.add(message);
      await tester.pump();
      await tester.pump();
      expect(find.byKey(const Key('push.banner')), findsNothing);
      // Nothing shows the bell under screen 5: the inbox is read again when it does.
      c.read(routerProvider).go('/');
      await tester.pumpAndSettle();
      expect(backend.inboxReads, reads + 1);
    });

    testWidgets('screen 5 opening while the banner shows: the banner goes', (tester) async {
      final push = FakePushMessaging();
      final c = await open(tester, PushBackend(base), push: push);
      push.foreground.add(message);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('push.banner')), findsOneWidget);
      c.read(routerProvider).go('/tickets/$ticketId');
      await tester.pumpAndSettle();
      expect(find.byType(TicketScreen), findsOneWidget);
      expect(find.byKey(const Key('push.banner')), findsNothing);
    });

    testWidgets('slides in, but not under reduced motion', (tester) async {
      Offset first() => tester.widget<FractionalTranslation>(find.byKey(const Key('push.banner.slide'))).translation;
      final push = FakePushMessaging();
      await open(tester, PushBackend(base), push: push);
      push.foreground.add(message);
      await tester.pump();
      await tester.pump();
      expect(first(), isNot(Offset.zero));
      await tester.pump(PushBannerHost.shownFor);
      await tester.pumpAndSettle();

      tester.platformDispatcher.accessibilityFeaturesTestValue = const FakeAccessibilityFeatures(
        disableAnimations: true,
      );
      addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
      await tester.pump();
      push.foreground.add(message);
      await tester.pump();
      await tester.pump();
      expect(first(), Offset.zero);
      await tester.pump(PushBannerHost.shownFor);
      await tester.pumpAndSettle();
    });
  });
}
