import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/club/dues.dart' show duesWouldUnlock;
import 'package:jomhor_fan/features/club/membership.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/queue/queue.dart';
import 'package:jomhor_fan/features/queue/queue_screen.dart';
import 'package:jomhor_fan/features/queue/queue_store.dart';
import 'package:jomhor_fan/features/queue/room_cta.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';

const zone = 'Africa/Casablanca';

void main() {
  late TestClock clock;
  late RoomSale sale;
  late List<bool> wake;
  final l = lookupAppLocalizations(const Locale('fr'));

  setUp(() {
    SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
    clock = TestClock(DateTime.utc(2027, 2, 20, 12));
    sale = RoomSale(clock);
    wake = [];
  });

  /// Answers and storage resolve without moving the fake clock (polls are timed to the second).
  Future<void> flush(WidgetTester tester) async {
    for (var i = 0; i < 8; i++) {
      await tester.pump();
    }
  }

  Future<ProviderContainer> start(
    WidgetTester tester, {
    String location = '/events/1/queue',
    bool signedIn = true,
    bool keepSecureStorage = false,
    List<Override> overrides = const [],
    FollowsState follows = (ids: const [], seen: true, chip: null),
  }) async {
    final container = await pumpJomhor(
      tester,
      backend: sale.handle,
      tokens: MemoryTokenStore(signedIn ? 'jwt' : null),
      clock: clock,
      keepSecureStorage: keepSecureStorage,
      follows: follows,
      overrides: [wakelockProvider.overrideWithValue(({required on}) async => wake.add(on)), ...overrides],
    );
    container.read(routerProvider).go(location);
    await flush(tester);
    return container;
  }

  Map<String, Object?> body(http.Request r) => jsonDecode(r.body) as Map<String, Object?>;
  String text(WidgetTester tester, String key) => tester.widget<Text>(find.byKey(Key(key))).data!;
  int litCount(WidgetTester tester) => find
      .byWidgetPredicate((w) => w.key is ValueKey<String> && (w.key! as ValueKey<String>).value.endsWith('.lit'))
      .evaluate()
      .length;

  /// Recorded haptics (`HapticFeedback.vibrate` calls on the platform channel).
  List<String> haptics(WidgetTester tester) {
    final calls = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'HapticFeedback.vibrate') calls.add('${call.arguments}');
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    return calls;
  }

  group('join', () {
    testWidgets('POST {event_id, device_id} with the session; the same install id after a restart', (tester) async {
      await start(tester);
      final first = sale.calls('POST', '/fan/queue').single;
      expect(first.headers['authorization'], 'Bearer jwt');
      final id = body(first)['device_id']! as String;
      expect(body(first), {'event_id': 1, 'device_id': id});
      expect(id, matches(RegExp(r'^[A-Za-z0-9_-]{16,64}$')));

      // A restart with the same preferences (the secure store emptied, so the app joins again).
      await tester.pumpWidget(const SizedBox());
      sale.requests.clear();
      await start(tester);
      expect(body(sale.calls('POST', '/fan/queue').single)['device_id'], id);
    });

    testWidgets('signed out: the room needs a login first', (tester) async {
      await start(tester, signedIn: false);
      expect(find.text(l.loginTitle), findsWidgets);
      expect(sale.calls('POST', '/fan/queue'), isEmpty);
    });
  });

  group('pre-queue', () {
    testWidgets('the draw countdown runs on server time and the crowd figure rolls', (tester) async {
      // The server is 2 min ahead of the phone: 28 min to the draw, not 30.
      sale = RoomSale(clock, serverSkew: const Duration(minutes: 2));
      sale.polls.add(() => sale.answer(sale.q(inRoom: 150)));
      await start(tester);
      expect(find.byKey(const Key('queue.draw')), findsOneWidget);
      final tiles = find.descendant(of: find.byKey(const Key('queue.countdown')), matching: find.byType(Text));
      expect([for (final t in tiles.evaluate()) (t.widget as Text).data], ['00', 'HEURES', '28', 'MIN', '00', 'SEC']);
      expect(text(tester, 'queue.inRoom'), '120 fans dans la file');
      expect(find.textContaining('Tous ceux qui sont là avant'), findsOneWidget);
      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(text(tester, 'queue.inRoom'), '150 fans dans la file');
    });

    testWidgets('a join refused as NOT_STARTED shows the opening time on a disabled CTA, then joins at it', (
      tester,
    ) async {
      sale.joins.add(
        () => sale.refusal(409, 'QUEUE_NOT_OPEN', {'reason': 'NOT_STARTED', 'opens_at': '2027-02-20T12:05:00Z'}),
      );
      await start(tester);
      final at = venueClock(DateTime.parse('2027-02-20T12:05:00Z'), zone, 'fr');
      final cta = tester.widget<FilledButton>(find.byKey(const Key('queue.opensAt')));
      expect(cta.onPressed, isNull);
      expect(find.text("File d'attente dès $at"), findsOneWidget);
      await tester.pump(const Duration(minutes: 5));
      await flush(tester);
      expect(sale.calls('POST', '/fan/queue'), hasLength(2));
      expect(find.byKey(const Key('queue.draw')), findsOneWidget);
    });
  });

  group('polling', () {
    testWidgets('waits exactly poll_after_s, without the session token or anything in the URL', (tester) async {
      await start(tester);
      expect(sale.calls('GET', '/fan/queue'), isEmpty);
      await tester.pump(const Duration(milliseconds: 4900));
      expect(sale.calls('GET', '/fan/queue'), isEmpty);
      await tester.pump(const Duration(milliseconds: 100));
      final poll = sale.calls('GET', '/fan/queue').single;
      expect(poll.headers['x-queue-token'], 'qt-1');
      expect(poll.headers.containsKey('authorization'), isFalse);
      expect(poll.url.hasQuery, isFalse);
    });

    testWidgets('stops on ADMITTED', (tester) async {
      sale.polls.add(() => sale.answer(sale.q(status: 'ADMITTED', turn: const Duration(minutes: 10), pollAfter: 0)));
      await start(tester);
      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      await tester.pump(const Duration(minutes: 2));
      expect(sale.calls('GET', '/fan/queue'), hasLength(1));
    });

    testWidgets('ENDED (without sale_starts_at), no phase left: screen 3, and polling stops', (tester) async {
      sale.polls.add(() {
        sale.room = null;
        return sale.answer(sale.q(status: 'ENDED', pollAfter: 0)..['sale_starts_at'] = null);
      });
      await start(tester);
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('zones.continue')), findsOneWidget);
      await tester.pump(const Duration(minutes: 1));
      expect(sale.calls('GET', '/fan/queue'), hasLength(1));
    });

    testWidgets('stops in the background and polls once on resume', (tester) async {
      await start(tester);
      [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
      ].forEach(tester.binding.handleAppLifecycleStateChanged);
      await tester.pump(const Duration(minutes: 1));
      expect(sale.calls('GET', '/fan/queue'), isEmpty);
      expect(wake.last, isFalse);
      [
        AppLifecycleState.hidden,
        AppLifecycleState.inactive,
        AppLifecycleState.resumed,
      ].forEach(tester.binding.handleAppLifecycleStateChanged);
      await flush(tester);
      expect(sale.calls('GET', '/fan/queue'), hasLength(1));
      expect(wake.last, isTrue);
      // Then the server's rhythm again.
      await tester.pump(const Duration(seconds: 5));
      expect(sale.calls('GET', '/fan/queue'), hasLength(2));
    });

    testWidgets('QUEUE_RATE_LIMITED POLL is silent and the next poll waits retry_after_s', (tester) async {
      sale.polls
        ..add(() => sale.real('poll_rate_limited'))
        ..add(() => sale.answer(sale.q()));
      await start(tester);
      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      expect(find.byKey(const Key('queue.rateLimited')), findsNothing);
      expect(find.byKey(const Key('queue.draw')), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 900));
      expect(sale.calls('GET', '/fan/queue'), hasLength(1));
      await tester.pump(const Duration(milliseconds: 100));
      expect(sale.calls('GET', '/fan/queue'), hasLength(2));
    });

    testWidgets('offline: the last state stays with "Hors ligne · ta place est gardée"', (tester) async {
      sale.polls.add(() => throw const SocketException('offline'));
      await start(tester);
      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      expect(find.text(l.queueOffline), findsOneWidget);
      expect(find.byKey(const Key('queue.draw')), findsOneWidget);
    });

    testWidgets('a reply that cannot be read: offline after a first answer, an error panel before it', (tester) async {
      sale.polls.add(() => sale.answer({'status': 'WAITING'}));
      await start(tester);
      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      expect(find.text(l.queueOffline), findsOneWidget);

      await tester.pumpWidget(const SizedBox());
      FlutterSecureStorage.setMockInitialValues({
        'queue_passes': jsonEncode({'1': const QueuePass(queueToken: 'qt-9').toJson()}),
      });
      await start(tester, keepSecureStorage: true);
      expect(find.byType(ErrorPanel), findsOneWidget);
    });

    testWidgets('a 401 joins again and the dots start over from the new first position', (tester) async {
      sale.joins
        ..add(() => sale.answer(sale.q(status: 'WAITING', position: 100, rate: 500, wait: 12)))
        ..add(() => sale.answer(sale.q(status: 'WAITING', position: 40, rate: 500, wait: 5)));
      sale.polls
        ..add(() => sale.answer(sale.q(status: 'WAITING', position: 50, rate: 500, wait: 6)))
        ..add(() => sale.real('poll_unauthorized'))
        ..add(() => sale.answer(sale.q(status: 'WAITING', position: 40, rate: 500, wait: 5)));
      await start(tester);
      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      expect(litCount(tester), 6);
      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(sale.calls('POST', '/fan/queue'), hasLength(2));
      expect(text(tester, 'queue.position'), 'N° 40');
      expect(litCount(tester), 0);
    });
  });

  group('background', () {
    void background(WidgetTester tester) => [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
    ].forEach(tester.binding.handleAppLifecycleStateChanged);
    void foreground(WidgetTester tester) => [
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ].forEach(tester.binding.handleAppLifecycleStateChanged);

    testWidgets('no timed join at opens_at in the background; one on resume', (tester) async {
      sale.joins.add(
        () => sale.refusal(409, 'QUEUE_NOT_OPEN', {'reason': 'NOT_STARTED', 'opens_at': '2027-02-20T12:05:00Z'}),
      );
      await start(tester);
      background(tester);
      await tester.pump(const Duration(minutes: 10));
      expect(sale.calls('POST', '/fan/queue'), hasLength(1));
      foreground(tester);
      await flush(tester);
      expect(sale.calls('POST', '/fan/queue'), hasLength(2));
    });

    testWidgets('the IP retry countdown stops in the background and goes on after', (tester) async {
      sale.joins.add(() => sale.refusal(429, 'QUEUE_RATE_LIMITED', {'reason': 'IP', 'retry_after_s': 3}));
      await start(tester);
      await tester.pump(const Duration(seconds: 1));
      background(tester);
      await tester.pump(const Duration(minutes: 1));
      expect(sale.calls('POST', '/fan/queue'), hasLength(1));
      foreground(tester);
      await tester.pump();
      expect(text(tester, 'queue.rateLimited'), 'Beaucoup de fans sur ce réseau · nouvel essai dans 2 s');
      await tester.pump(const Duration(seconds: 2));
      await flush(tester);
      expect(sale.calls('POST', '/fan/queue'), hasLength(2));
    });
  });

  testWidgets('the join at opens_at runs on server time', (tester) async {
    // The server is 2 min ahead: opens_at 12:07 server time is 5 min away on the phone.
    sale = RoomSale(clock, serverSkew: const Duration(minutes: 2));
    sale.joins
      ..add(() => sale.answer(sale.q(), 201))
      ..add(() => sale.refusal(409, 'QUEUE_NOT_OPEN', {'reason': 'NOT_STARTED', 'opens_at': '2027-02-20T12:07:00Z'}));
    sale.polls.add(() => sale.real('poll_unauthorized'));
    await start(tester);
    await tester.pump(const Duration(seconds: 5));
    await flush(tester);
    expect(sale.calls('POST', '/fan/queue'), hasLength(2));
    await tester.pump(const Duration(minutes: 5));
    await flush(tester);
    expect(sale.calls('POST', '/fan/queue'), hasLength(3));
  });

  group('waiting', () {
    testWidgets('position, fans ahead, the wait, and the dots lit from the first position seen', (tester) async {
      sale.joins.add(() => sale.answer(sale.q(status: 'WAITING', position: 120, rate: 500, wait: 15)));
      sale.polls
        ..add(() => sale.answer(sale.q(status: 'WAITING', position: 60, rate: 500, wait: 480)))
        ..add(() => sale.answer(sale.q(status: 'WAITING', position: 1, rate: 500, wait: 1)))
        ..add(() => sale.answer(sale.q(status: 'ADMITTED', turn: const Duration(minutes: 10), pollAfter: 0)));
      await start(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(text(tester, 'queue.position'), 'N° 120');
      expect(text(tester, 'queue.ahead'), '119 fans devant toi');
      expect(text(tester, 'queue.wait'), "Attente estimée · moins d'une minute");
      expect(find.text('Il reste 420 places'), findsOneWidget);
      expect(litCount(tester), 0);

      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(text(tester, 'queue.position'), 'N° 60');
      expect(text(tester, 'queue.wait'), 'Attente estimée · environ 8 min');
      expect(litCount(tester), 6);

      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(text(tester, 'queue.ahead'), 'Personne devant toi');
      expect(litCount(tester), 11, reason: 'the twelfth waits for the turn');
      expect(find.byKey(const Key('queue.dot.11.off')), findsOneWidget);

      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byKey(const Key('queue.dot.11.lit')), findsOneWidget);
      expect(litCount(tester), 12);
    });

    testWidgets('paused: "Admission en pause", sold out copy, and the verified chip with a rule', (tester) async {
      sale.room = roomJson(status: 'PAUSED', eligibility: {'verified_before': '2027-02-13T12:30:00Z'});
      sale.joins.add(() => sale.answer(sale.q(status: 'PAUSED', position: 40, soldOut: true, pollAfter: 15)));
      await start(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(text(tester, 'queue.wait'), l.queuePaused);
      expect(find.byKey(const Key('queue.soldOut')), findsOneWidget);
      expect(find.byKey(const Key('queue.verified')), findsOneWidget);
    });

    testWidgets('paused before the rank: "Admission en pause", not "Placement en cours"', (tester) async {
      sale.joins.add(() => sale.answer(sale.q(status: 'PAUSED', pollAfter: 15)));
      await start(tester);
      expect(text(tester, 'queue.wait'), l.queuePaused);
      expect(find.text(l.queueRankingTitle), findsNothing);
    });

    test('wait copy: under a minute, n minutes, over an hour, none', () {
      expect(waitCopy(l, 59), "moins d'une minute");
      expect(waitCopy(l, 60), 'environ 1 min');
      expect(waitCopy(l, 150), 'environ 3 min');
      expect(waitCopy(l, 3600), 'environ 60 min');
      expect(waitCopy(l, 3601), "plus d'une heure");
      expect(waitCopy(l, null), isNull);
    });

    test('dots: from the first position seen, capped at 11 until the turn', () {
      expect(litDots(position: 100, first: 100), 0);
      expect(litDots(position: 50, first: 100), 6);
      expect(litDots(position: 1, first: 100), 11);
      expect(litDots(position: 1, first: 1), 0);
      expect(litDots(position: 1, first: 100, admitted: true), 12);
    });
  });

  group('the turn', () {
    Future<void> admit(WidgetTester tester) async {
      sale.joins.add(() => sale.answer(sale.q(status: 'WAITING', position: 3, rate: 500, wait: 1)));
      sale.polls.add(() => sale.answer(sale.q(status: 'ADMITTED', turn: const Duration(minutes: 10), pollAfter: 0)));
      await start(tester);
      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
    }

    double twelfthScale(WidgetTester tester) => tester
        .widget<Transform>(
          find.ancestor(of: find.byKey(const Key('queue.dot.11.lit')), matching: find.byType(Transform)).first,
        )
        .transform
        .getMaxScaleOnAxis();

    testWidgets('the twelfth swells once with a haptic; the countdown runs to expires_at; CTA → screen 3', (
      tester,
    ) async {
      final felt = haptics(tester);
      await admit(tester);
      await tester.pump(const Duration(milliseconds: 210));
      expect(twelfthScale(tester), greaterThan(1.3));
      await tester.pump(const Duration(milliseconds: 300));
      expect(twelfthScale(tester), 1);
      expect(felt, hasLength(1));
      expect(text(tester, 'queue.turn'), "C'EST TON TOUR");
      expect(text(tester, 'queue.turnLeft'), 'Tu as 10:00 pour réserver');
      clock.advance(const Duration(seconds: 75));
      await tester.pump(const Duration(seconds: 1));
      expect(text(tester, 'queue.turnLeft'), 'Tu as 08:45 pour réserver');

      await tester.tap(find.byKey(const Key('queue.choose')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('zones.continue')), findsOneWidget);
      expect(find.text('Ton tour · 08:45'), findsOneWidget);
      expect(felt, hasLength(1));
    });

    testWidgets('no swell and no haptic under reduced motion', (tester) async {
      tester.platformDispatcher.accessibilityFeaturesTestValue = const FakeAccessibilityFeatures(
        disableAnimations: true,
      );
      addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
      final felt = haptics(tester);
      await admit(tester);
      await tester.pump(const Duration(milliseconds: 210));
      expect(twelfthScale(tester), 1);
      expect(felt, isEmpty);
    });

    testWidgets('opened on a turn already running: no swell and no haptic', (tester) async {
      final felt = haptics(tester);
      sale.joins.add(() => sale.answer(sale.q(status: 'ADMITTED', turn: const Duration(minutes: 10), pollAfter: 0)));
      await start(tester);
      expect(find.byKey(const Key('queue.turn')), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 210));
      expect(twelfthScale(tester), 1);
      expect(felt, isEmpty);
    });

    // invalid_presale: phase 1's token once phase 2's pre-queue opened (FL-27 real run).
    for (final reason in ['missing', 'invalid', 'invalid_other_user', 'expired', 'invalid_presale']) {
      testWidgets('POST /fan/orders carries X-Admission-Token; ADMISSION_REQUIRED ($reason, the real answer) goes back '
          'to screen 12', (tester) async {
        sale.orders.add(() => sale.real('admission_required_$reason'));
        await admit(tester);
        // The next look at the room finds the turn over.
        sale.polls
          ..clear()
          ..add(() => sale.answer(sale.q(status: 'EXPIRED', pollAfter: 0)));
        final container = ProviderScope.containerOf(tester.element(find.byType(QueueScreen)));
        container.read(routerProvider).go('/events/1/checkout?price=pc-1-0&qty=1');
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.byKey(const Key('checkout.pay')));
        await tester.tap(find.byKey(const Key('checkout.pay')));
        await flush(tester);
        final order = sale.calls('POST', '/fan/orders').single;
        expect(order.headers['x-admission-token'], 'adm-1');
        expect(order.url.hasQuery, isFalse);
        await tester.pumpAndSettle();
        // Screen 12 asks the room again with the queue token: the turn is over.
        expect(find.byType(QueueScreen), findsOneWidget);
        expect(sale.calls('GET', '/fan/queue').last.headers['x-queue-token'], 'qt-1');
        expect(find.byKey(const Key('queue.expired')), findsOneWidget);
        final pass = (await container.read(queuePassesProvider.future))[1]!;
        expect((pass.queueToken, pass.admissionToken), ('qt-1', null));
      });
    }

    testWidgets('EXPIRED: "Ton tour est passé" and "Reprendre une place" joins again', (tester) async {
      sale.joins.add(() => sale.answer(sale.q(status: 'EXPIRED', pollAfter: 0)));
      await start(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(text(tester, 'queue.expired'), 'Ton tour est passé');
      await tester.tap(find.byKey(const Key('queue.rejoin')));
      await flush(tester);
      expect(sale.calls('POST', '/fan/queue'), hasLength(2));
      expect(find.byKey(const Key('queue.draw')), findsOneWidget);
    });
  });

  group('refusals', () {
    for (final (reason, copy) in [
      ('account_too_new', (String d) => 'Compte vérifié après le $d'),
      ('not_member', (String d) => 'Réservée aux membres inscrits avant le $d'),
      ('not_enough_events', (_) => 'Il faut 1 match au stade · tu en as 0'),
    ]) {
      testWidgets('NOT_ELIGIBLE ${reason.toUpperCase()} (the real answer)', (tester) async {
        sale.joins.add(() => sale.real('not_eligible_$reason'));
        await start(tester);
        await tester.pump(const Duration(milliseconds: 300));
        final details = realAnswer('not_eligible_$reason').body['details']! as Map<String, Object?>;
        expect(details['reason'], reason.toUpperCase());
        final date = details.values.whereType<String>().where((v) => DateTime.tryParse(v) != null).firstOrNull;
        expect(find.text('Vente réservée aux fans vérifiés'), findsOneWidget);
        expect(find.text(copy(date == null ? '' : venueDay(DateTime.parse(date), zone, 'fr'))), findsOneWidget);
        await tester.pump(const Duration(minutes: 1));
        expect(sale.calls('POST', '/fan/queue'), hasLength(1));
        expect(sale.calls('GET', '/fan/queue'), isEmpty);
      });
    }

    test('NOT_ENOUGH_EVENTS copy is a plural', () {
      expect(l.queueNotEnoughEvents(1, 0), 'Il faut 1 match au stade · tu en as 0');
      expect(l.queueNotEnoughEvents(3, 2), 'Il faut 3 matchs au stade · tu en as 2');
    });

    testWidgets('QUEUE_DEVICE_LIMIT (the real answer)', (tester) async {
      sale.joins.add(() => sale.real('queue_device_limit'));
      await start(tester);
      expect(find.text('Trop de comptes sur ce téléphone pour cette vente'), findsOneWidget);
    });

    testWidgets('QUEUE_RATE_LIMITED IP: the countdown, one automatic retry, then a manual one', (tester) async {
      http.Response limited() => sale.refusal(429, 'QUEUE_RATE_LIMITED', {'reason': 'IP', 'retry_after_s': 3});
      sale.joins
        ..add(limited)
        ..add(limited);
      await start(tester);
      expect(text(tester, 'queue.rateLimited'), 'Beaucoup de fans sur ce réseau · nouvel essai dans 3 s');
      await tester.pump(const Duration(seconds: 1));
      expect(text(tester, 'queue.rateLimited'), 'Beaucoup de fans sur ce réseau · nouvel essai dans 2 s');
      await tester.pump(const Duration(seconds: 2));
      await flush(tester);
      expect(sale.calls('POST', '/fan/queue'), hasLength(2));
      expect(text(tester, 'queue.rateLimited'), l.queueRateLimitedAgain);
      await tester.pump(const Duration(minutes: 1));
      expect(sale.calls('POST', '/fan/queue'), hasLength(2), reason: 'one automatic retry only');
      await tester.tap(find.byKey(const Key('queue.retry')));
      await flush(tester);
      expect(sale.calls('POST', '/fan/queue'), hasLength(3));
      expect(find.byKey(const Key('queue.draw')), findsOneWidget);
    });
  });

  group('tokens', () {
    testWidgets('survive a restart: the room is resumed by GET, not joined again', (tester) async {
      await start(tester);
      await tester.pumpWidget(const SizedBox());
      sale.requests.clear();
      await start(tester, keepSecureStorage: true);
      expect(sale.calls('POST', '/fan/queue'), isEmpty);
      expect(sale.calls('GET', '/fan/queue').single.headers['x-queue-token'], 'qt-1');
    });

    testWidgets('two puts in the same turn are both kept', (tester) async {
      final container = await start(tester);
      final passes = container.read(queuePassesProvider.notifier);
      await Future.wait([
        passes.put(7, const QueuePass(queueToken: 'qt-7')),
        passes.put(8, const QueuePass(queueToken: 'qt-8')),
      ]);
      expect((await container.read(queuePassesProvider.future)).keys, containsAll([1, 7, 8]));
      final saved = jsonDecode((await const FlutterSecureStorage().read(key: 'queue_passes'))!) as Map<String, Object?>;
      expect(saved.keys, containsAll(['1', '7', '8']));
    });

    testWidgets('are wiped on logout', (tester) async {
      final container = await start(tester);
      expect(await const FlutterSecureStorage().read(key: 'queue_passes'), contains('qt-1'));
      await container.read(sessionProvider.notifier).signOut();
      await flush(tester);
      expect(await const FlutterSecureStorage().read(key: 'queue_passes'), isNull);
      expect(await container.read(queuePassesProvider.future), isEmpty);
    });
  });

  testWidgets('the screen stays awake while visible, not after leaving', (tester) async {
    await start(tester);
    expect(wake, [true]);
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    expect(wake, [true, false]);
    expect(find.text(l.queuePlaceKept), findsOneWidget);
  });

  group('screens 1 and 2', () {
    testWidgets('SCHEDULED: "File d\'attente dès" disabled, "Connecte-toi maintenant" signed out, flips at opens_at', (
      tester,
    ) async {
      const later = '2027-02-20T12:10:00Z';
      sale.room = roomJson(status: 'SCHEDULED', at: later);
      await start(tester, location: '/', signedIn: false);
      await tester.pumpAndSettle();
      final at = venueClock(DateTime.parse(later), zone, 'fr');
      final cta = tester.widget<FilledButton>(find.byKey(const Key('hero.cta')));
      expect((cta.onPressed, (cta.child! as Text).data), (null, "File d'attente dès $at"));
      expect(find.byKey(const Key('hero.login')), findsOneWidget);
      clock.advance(const Duration(minutes: 10));
      await tester.pumpAndSettle();
      final open = tester.widget<FilledButton>(find.byKey(const Key('hero.cta')));
      expect(((open.child! as Text).data, open.onPressed != null), ('Rejoindre la file', true));
    });

    testWidgets('"Reprendre ma place" when the phone holds a queue token; it opens screen 12', (tester) async {
      FlutterSecureStorage.setMockInitialValues({
        'queue_passes': jsonEncode({'1': const QueuePass(queueToken: 'qt-9').toJson()}),
      });
      await start(tester, location: '/', keepSecureStorage: true);
      await tester.pumpAndSettle();
      expect(find.text('Reprendre ma place'), findsOneWidget);
      // Under the bottom bar in the 800×600 test view: pressed directly.
      tester.widget<FilledButton>(find.byKey(const Key('hero.cta'))).onPressed!();
      await flush(tester);
      expect(find.byType(QueueScreen), findsOneWidget);
      expect(sale.calls('GET', '/fan/queue').single.headers['x-queue-token'], 'qt-9');
    });

    testWidgets('screen 2: the high-demand banner with the rule, CTA "Rejoindre la file"', (tester) async {
      sale.room = roomJson(eligibility: {'verified_before': '2027-02-13T12:30:00Z', 'min_events_attended': 3});
      await start(tester, location: '/events/1');
      await tester.pumpAndSettle();
      final day = venueDay(DateTime.parse('2027-02-13T12:30:00Z'), zone, 'fr');
      expect(
        tester.widget<Text>(find.descendant(of: find.byKey(const Key('event.room')), matching: find.byType(Text))).data,
        "Vente à forte demande · file d'attente\nRéservée aux fans vérifiés: compte vérifié avant le $day · 3 matchs au stade",
      );
      final cta = tester.widget<FilledButton>(find.byKey(const Key('event.cta')));
      expect(((cta.child! as Text).data, cta.onPressed != null), ('Rejoindre la file', true));
    });
  });

  group('sale phases, members prices, holder limits (FL-27 real run, test/fixtures/queue)', () {
    // The real run (2026-11-15): phase 1 "Prévente membres" drew at presaleStart (also the members cutoff); phase 2
    // "Vente générale" opened at generalOpens and drew at generalStart. Phase 1 was ended by hand right after the
    // presale order: the gap, then phase 2's pre-queue.
    const presaleStart = '2026-11-15T15:28:49Z';
    const generalOpens = '2026-11-15T15:30:49Z';
    const generalStart = '2026-11-15T15:33:49Z';
    Map<String, Object?> presale() => realAnswer('event_room_presale_next').body;
    Map<String, Object?> roomOf(Map<String, Object?> event) => {...event['room']! as Map<String, Object?>};
    List<Map<String, Object?>> pricesOf(Map<String, Object?> event) => [
      for (final p in ((event['zones']! as List<Object?>).single! as Map<String, Object?>)['prices']! as List<Object?>)
        p! as Map<String, Object?>,
    ];
    String priceId(String audience) =>
        pricesOf(presale()).firstWhere((p) => p['audience'] == audience)['price_category_id']! as String;

    /// The phone's clock as the server's at that captured answer.
    void at(String instant) => clock.now = DateTime.parse(instant);
    String day(String at) => venueDay(DateTime.parse(at), zone, 'fr');
    String when(String at) => venueWhen(DateTime.parse(at), zone, 'fr');
    String clockAt(String at) => venueClock(DateTime.parse(at), zone, 'fr');
    String roomBanner(WidgetTester tester) => tester
        .widget<Text>(find.descendant(of: find.byKey(const Key('event.room')), matching: find.byType(Text)))
        .data!;
    String ctaText(WidgetTester tester) =>
        (tester.widget<FilledButton>(find.byKey(const Key('event.cta'))).child! as Text).data!;

    test('the real answers read: phase, label, next, members prices; "dès" is a price anyone can pay', () {
      final d = EventDetail.fromJson(presale(), fetchedAt: clock.now);
      final room = d.event.room!;
      expect((room.phase, room.label, room.status), (1, 'Prévente membres', 'PRE_QUEUE'));
      expect(
        (room.saleStartsAt, room.eligibility!.memberJoinedBefore),
        (DateTime.parse(presaleStart), DateTime.parse(presaleStart)),
      );
      expect(
        (room.next!.phase, room.next!.label, room.next!.opensAt),
        (2, 'Vente générale', DateTime.parse(generalOpens)),
      );
      final est = d.zones.single;
      expect([for (final p in est.prices) p.membersOnly], [false, true]);
      expect((est.hasMembersPrice, est.firstOpen!.priceCategoryId, est.fromPriceMad), (true, priceId('ALL'), 5000));
      // Phase 1 ended by hand: the gap reads phase 2 still SCHEDULED; then its pre-queue, with no phase after it.
      for (final (name, status) in [('event_room_gap', 'SCHEDULED'), ('event_room_general', 'PRE_QUEUE')]) {
        final r = EventDetail.fromJson(realAnswer(name).body, fetchedAt: clock.now).event.room!;
        expect(
          (r.phase, r.label, r.status, r.opensAt, r.saleStartsAt, r.eligibility, r.next),
          (2, 'Vente générale', status, DateTime.parse(generalOpens), DateTime.parse(generalStart), null, null),
          reason: name,
        );
      }
      // The presale queue token, in the gap and once phase 2 opened: ENDED, nothing more to poll.
      for (final name in ['poll_ended_gap', 'poll_ended_presale']) {
        final q = QueueStatus.fromJson(realAnswer(name).body);
        expect((q.phase, q.pollAfterS, q.admissionToken), (QueuePhase.ended, 0, null), reason: name);
      }
      // An order in the gap: the next phase's sale hasn't started.
      final (:status, :body) = realAnswer('order_409_not_started_gap');
      final details = body['details']! as Map<String, Object?>;
      final gap = ApiError(status: status, code: body['code']! as String, message: '', details: details);
      expect((apiErrorText(l, gap), details['sale_starts_at']), (l.errorNotStarted, generalStart));
      // A step-1 room (no phase fields) still reads as phase 1 without a next.
      final old = Room.fromJson(roomJson());
      expect((old.phase, old.label, old.next), (1, null, null));
    });

    test("the goldens' design answers (test/fixtures/goldens) have the real answers' shape", () {
      for (final name in ['event_room_presale_next', 'order_409_members_only']) {
        expect(keyPaths(realAnswer(name, dir: 'goldens').body), keyPaths(realAnswer(name).body), reason: name);
      }
    });

    test('"dès" counts only prices anyone can pay: members prices alone show none, screen 3 still starts on one', () {
      final zone = Zone.fromJson({
        ...((presale()['zones']! as List<Object?>).first! as Map<String, Object?>),
        'prices': [
          {'price_category_id': 'a', 'name': 'Plein tarif', 'price_mad': 8000, 'remaining': 0, 'audience': 'ALL'},
          {'price_category_id': 'm', 'name': 'Tarif membres', 'price_mad': 5000, 'remaining': 9, 'audience': 'MEMBERS'},
        ],
      });
      expect((zone.fromPriceMad, zone.firstOpen!.priceCategoryId, zone.soldOut), (null, 'm', false));
    });

    testWidgets('screen 2: "{label} · tirage à", the next phase, the members chip and caption; the CTA has the label', (
      tester,
    ) async {
      at('2026-11-15T15:26:52Z');
      sale
        ..detail = presale()
        ..room = roomOf(presale());
      await start(tester, location: '/events/1');
      await tester.pumpAndSettle();
      expect(
        roomBanner(tester),
        'Prévente membres · tirage à ${clockAt(presaleStart)}\n'
        'Réservée aux fans vérifiés: membre inscrit avant le ${day(presaleStart)}',
      );
      expect(text(tester, 'event.next'), 'Ensuite : Vente générale · file dès ${clockAt(generalOpens)}');
      expect(find.byKey(const Key('zone.2.members')), findsOneWidget);
      expect(text(tester, 'event.members'), 'Tarif membres · inscrits avant le ${day(presaleStart)}');
      expect(ctaText(tester), 'Prévente membres · rejoindre la file');
    });

    testWidgets('screen 2: an unnamed next phase is "Prochaine vente"; an unnamed room keeps the step-1 line', (
      tester,
    ) async {
      sale
        ..detail = presale()
        ..room = (roomOf(presale())
          ..['label'] = null
          ..['next'] = {
            'phase': 2,
            'label': null,
            'opens_at': '2027-02-21T09:30:00Z',
            'sale_starts_at': '2027-02-21T10:00:00Z',
          });
      await start(tester, location: '/events/1');
      await tester.pumpAndSettle();
      expect(text(tester, 'event.next'), 'Ensuite : Prochaine vente · file dès ${when('2027-02-21T09:30:00Z')}');
      expect(roomBanner(tester), startsWith("Vente à forte demande · file d'attente\n"));
      expect(ctaText(tester), 'Rejoindre la file');
    });

    testWidgets('NOT_ELIGIBLE with a next phase: the panel, then "Ensuite : …"', (tester) async {
      at('2026-11-15T15:26:52Z');
      sale
        ..detail = presale()
        ..room = roomOf(presale())
        ..joins.add(() => sale.real('not_eligible_not_member'));
      await start(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byKey(const Key('queue.notEligible')), findsOneWidget);
      expect(text(tester, 'queue.next'), 'Ensuite : Vente générale · file dès ${clockAt(generalOpens)}');
    });

    /// Screen 12 in the presale, whose next poll answers [ended] while the event has become [event] (phase 2).
    Future<ProviderContainer> presaleEnds(WidgetTester tester, String ended, String event) {
      at(realAnswer(ended).body['server_time']! as String);
      sale
        ..detail = presale()
        ..room = roomOf(presale())
        ..polls.add(() {
          sale
            ..detail = realAnswer(event).body
            ..room = roomOf(sale.detail!);
          return sale.real(ended);
        });
      return start(tester);
    }

    testWidgets('poll ENDED once phase 2 opened: the event is read again, screen 2 offers its queue, no join', (
      tester,
    ) async {
      final container = await presaleEnds(tester, 'poll_ended_presale', 'event_room_general');
      final reads = sale.calls('GET', '/fan/events/1').length;
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
      expect(find.byType(QueueScreen), findsNothing);
      expect(sale.calls('GET', '/fan/events/1').length, greaterThan(reads));
      final cta = tester.widget<FilledButton>(find.byKey(const Key('event.cta')));
      expect(((cta.child! as Text).data, cta.onPressed != null), ('Vente générale · rejoindre la file', true));
      expect(sale.calls('POST', '/fan/queue'), hasLength(1), reason: 'the first join only: no automatic join');
      expect((await container.read(queuePassesProvider.future))[1], isNull);
      await tester.pump(const Duration(minutes: 1));
      expect(sale.calls('GET', '/fan/queue'), hasLength(1));
    });

    testWidgets('poll ENDED in the gap (phase 1 ended by hand, phase 2 SCHEDULED): "{label} · file dès" disabled', (
      tester,
    ) async {
      await presaleEnds(tester, 'poll_ended_gap', 'event_room_gap');
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
      final cta = tester.widget<FilledButton>(find.byKey(const Key('event.cta')));
      expect(((cta.child! as Text).data, cta.onPressed), ('Vente générale · file dès ${clockAt(generalOpens)}', null));
      expect(sale.calls('POST', '/fan/queue'), hasLength(1));
    });

    testWidgets('poll ENDED and the event cannot be read again: screen 2, which offers its own retry', (tester) async {
      sale.polls.add(() {
        sale.detailFails = true;
        return sale.answer(sale.q(status: 'ENDED', pollAfter: 0));
      });
      final container = await start(tester);
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
      expect(find.byType(QueueScreen), findsNothing);
      expect(container.read(routerProvider).routerDelegate.currentConfiguration.uri.path, '/events/1');
      expect(find.byType(ErrorPanel), findsOneWidget);
      sale.detailFails = false;
      await tester.tap(find.text(l.retry));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('event.cta')), findsOneWidget);
      expect(sale.calls('POST', '/fan/queue'), hasLength(1));
    });

    test('"matchs au stade" for a match, "événements" otherwise (Q182)', () {
      FanEvent event(String type) => FanEvent.fromJson(
        eventJson(eventType: type)..['room'] = roomJson(eligibility: {'min_events_attended': 3}),
        fetchedAt: clock.now,
      );
      expect(eligibilityLine(l, event('FOOTBALL_MATCH')), 'Réservée aux fans vérifiés: 3 matchs au stade');
      expect(eligibilityLine(l, event('CONCERT')), 'Réservée aux fans vérifiés: 3 événements');
      expect(l.queueNotEnoughEventsAny(1, 0), 'Il faut 1 événement · tu en as 0');
      expect(l.queueNotEnoughEventsAny(3, 2), 'Il faut 3 événements · tu en as 2');
    });

    testWidgets('NOT_ENOUGH_EVENTS on a concert says "événements"', (tester) async {
      sale
        ..detail = eventJson(
          eventType: 'CONCERT',
          metadata: const {'headliner': 'Hoba Hoba Spirit'},
          onSale: false,
          zones: [
            zoneJson(1, 'Fosse', [(20000, 300)], seated: false),
          ],
        )
        ..joins.add(() => sale.real('not_eligible_not_enough_events'));
      await start(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.text('Il faut 1 événement · tu en as 0'), findsOneWidget);
    });

    for (final (fixture, row, copy) in [
      ('order_409_members_only', 0, (String day) => 'Tarif réservé aux membres inscrits avant le $day'),
      ('order_409_holder_limit', 1, (String _) => 'Ce numéro a atteint la limite de 4 billets pour cet événement'),
    ]) {
      testWidgets('screen 4: $fixture is said under holder row ${row + 1}, picked by details.field', (tester) async {
        sale
          ..detail = presale()
          ..room = null
          ..orders.add(() => sale.real(fixture));
        await start(tester, location: '/events/1/checkout?price=${priceId('MEMBERS')}&qty=2');
        await tester.pumpAndSettle();
        await tester.enterText(find.byKey(const Key('holder.1.name')), 'Sara Benali');
        await tester.enterText(find.byKey(const Key('holder.1.phone')), '0612345678');
        await tester.dragUntilVisible(
          find.byKey(const Key('checkout.consent')),
          find.byType(ListView).last,
          const Offset(0, -200),
        );
        await tester.tap(find.byKey(const Key('checkout.consent')));
        await tester.ensureVisible(find.byKey(const Key('checkout.pay')));
        await tester.tap(find.byKey(const Key('checkout.pay')));
        await tester.pumpAndSettle();
        expect(sale.calls('POST', '/fan/orders'), hasLength(1));
        await tester.scrollUntilVisible(
          find.byKey(Key('holder.$row.refusal')),
          -200,
          scrollable: find.byType(Scrollable).last,
        );
        expect(text(tester, 'holder.$row.refusal'), copy(day(presaleStart)));
        expect(find.byKey(Key('holder.${1 - row}.refusal'), skipOffstage: false), findsNothing);
        expect(find.byType(ErrorPanel), findsNothing);
      });
    }

    test('holder copy: a members price without a sale start, the limit as a plural, the buyer limit untouched', () {
      String? copy(String code, Map<String, Object?> details) =>
          holderRefusalText(l, ApiError(status: 409, code: code, message: code, details: details), zone);
      expect(
        copy('MEMBERS_ONLY', {'field': 'items[0].holders[0]', 'member_joined_before': null}),
        'Tarif réservé aux membres',
      );
      expect(
        copy('PER_ACCOUNT_LIMIT', {'reason': 'HOLDER', 'field': 'to_phone', 'limit': 1}),
        'Ce numéro a atteint la limite de 1 billet pour cet événement',
      );
      expect(copy('PER_ACCOUNT_LIMIT', {'limit': 4, 'held': 4}), isNull);
      // No row to put it on: the banner still says why.
      expect(
        apiErrorText(l, const ApiError(status: 409, code: 'MEMBERS_ONLY', message: 'x')),
        'Tarif réservé aux membres',
      );
    });
    // FL-30 (Lot 5 step 3, Q189): the club sells a membership, so "members" means paid up (contract-first,
    // test/fixtures/club).
    final presaleOrg = presale()['organizer_id']! as String;

    /// The fan follows Atlas FC then the event's club, a member of the latter since [joinedAt], not paid up.
    /// `details.member_joined_before` of a refusal captured in the FL-30 real run (test/fixtures/club).
    String cutoffOf(String name) =>
        (realAnswer(name, dir: 'club').body['details']! as Map<String, Object?>)['member_joined_before']! as String;

    FollowsState memberOfPresaleClub({String joinedAt = '2026-11-03T10:00:00Z'}) {
      sale
        ..organizers = [clubJson(), clubJson(id: presaleOrg, slug: 'atlas-presale', name: 'Club Presale')]
        ..memberships[presaleOrg] = {
          'member': {
            'number': 42,
            'joined_at': joinedAt,
            'marketing_push': false,
            'marketing_whatsapp': false,
            'paid_until': null,
          },
          'loyalty': {
            'events_attended': 0,
            'tier': {'code': 'T1', 'name': 'Supporter'},
            'next_tier': null,
            'last_events': <Object?>[],
          },
          'paid_required': true,
        };
      return (ids: [testOrganizer, presaleOrg], seen: true, chip: chipAll);
    }

    /// The card of [org] is the one in view on Club.
    void expectClubInView(WidgetTester tester, String org) => expect(
      tester.getCenter(find.byKey(Key('club.card.$org'))).dx,
      moreOrLessEquals(tester.getCenter(find.byKey(const Key('club.pager'))).dx),
    );

    testWidgets('screen 12: NOT_MEMBER with paid_required says "à jour de cotisation" and links to Club', (
      tester,
    ) async {
      at('2026-11-15T15:26:52Z');
      sale
        ..detail = presale()
        ..room = roomOf(presale())
        ..joins.add(() => sale.real('not_eligible_not_member_paid', dir: 'club'));
      final c = await start(tester, follows: memberOfPresaleClub());
      await tester.pump(const Duration(milliseconds: 300));
      expect(
        find.text(
          'Réservée aux membres à jour de cotisation, inscrits avant le '
          '${day(cutoffOf('not_eligible_not_member_paid'))}',
        ),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('dues.seeClub')));
      await tester.pumpAndSettle();
      expect(c.read(routerProvider).state.uri.path, '/club');
      expectClubInView(tester, presaleOrg); // the event's club, not the first followed
    });

    testWidgets('screen 12: no cotisation link when paying would not lift the refusal', (tester) async {
      // Joined after the room's cutoff: paying never moves joined_at.
      at('2026-11-15T15:26:52Z');
      sale
        ..detail = presale()
        ..room = roomOf(presale())
        ..joins.add(() => sale.real('not_eligible_not_member_paid', dir: 'club'));
      await start(tester, follows: memberOfPresaleClub(joinedAt: cutoffOf('not_eligible_not_member_paid')));
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.textContaining('à jour de cotisation'), findsOneWidget);
      expect(find.byKey(const Key('dues.seeClub')), findsNothing);
    });

    test('duesWouldUnlock: followed, a member who joined before the cutoff, paid_required', () {
      ApiError refusal({bool paid = true, String? before = '2026-11-15T12:00:00Z'}) => ApiError(
        status: 409,
        code: 'MEMBERS_ONLY',
        message: 'x',
        details: {'paid_required': paid, 'member_joined_before': before},
      );
      Membership member(String? joinedAt) => Membership.fromJson({
        'member': joinedAt == null
            ? null
            : {'number': 1, 'joined_at': joinedAt, 'marketing_push': false, 'marketing_whatsapp': false},
        'loyalty': {
          'events_attended': 0,
          'tier': {'code': 'T1', 'name': 'x'},
          'next_tier': null,
          'last_events': <Object?>[],
        },
        'paid_required': true,
      });
      final early = member('2026-11-01T00:00:00Z');
      expect(duesWouldUnlock(refusal(), early, followed: true), isTrue);
      expect(duesWouldUnlock(refusal(), early, followed: false), isFalse); // Club couldn't show its card
      expect(duesWouldUnlock(refusal(paid: false), early, followed: true), isFalse);
      expect(duesWouldUnlock(refusal(), member(null), followed: true), isFalse); // not a member
      expect(duesWouldUnlock(refusal(), null, followed: true), isFalse); // membership unknown
      expect(duesWouldUnlock(refusal(), member('2026-11-15T12:00:00Z'), followed: true), isFalse); // at the cutoff
      expect(duesWouldUnlock(refusal(before: null), member('2026-11-15T12:00:00Z'), followed: true), isTrue);
    });

    testWidgets('screen 12: NOT_MEMBER without paid_required keeps the step-2 copy and no Club link', (tester) async {
      at('2026-11-15T15:26:52Z');
      sale
        ..detail = presale()
        ..room = roomOf(presale())
        ..joins.add(() => sale.real('not_eligible_not_member'));
      await start(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.text('Réservée aux membres inscrits avant le ${day('2026-11-15T12:07:33Z')}'), findsOneWidget);
      expect(find.byKey(const Key('dues.seeClub')), findsNothing);
    });

    for (final (row, link) in [(0, true), (1, false)]) {
      testWidgets('screen 4: MEMBERS_ONLY with paid_required on row ${row + 1}, Club link: $link', (tester) async {
        final (:status, :body) = realAnswer('order_409_members_only_paid', dir: 'club');
        (body['details']! as Map<String, Object?>)['field'] = 'items[0].holders[$row]';
        sale
          ..detail = presale()
          ..room = null
          ..orders.add(() => sale.answer(body, status));
        final c = await start(
          tester,
          location: '/events/1/checkout?price=${priceId('MEMBERS')}&qty=2',
          follows: memberOfPresaleClub(),
        );
        await tester.pumpAndSettle();
        await tester.enterText(find.byKey(const Key('holder.1.name')), 'Sara Benali');
        await tester.enterText(find.byKey(const Key('holder.1.phone')), '0612345678');
        await tester.dragUntilVisible(
          find.byKey(const Key('checkout.consent')),
          find.byType(ListView).last,
          const Offset(0, -200),
        );
        await tester.tap(find.byKey(const Key('checkout.consent')));
        await tester.ensureVisible(find.byKey(const Key('checkout.pay')));
        await tester.tap(find.byKey(const Key('checkout.pay')));
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.byKey(Key('holder.$row.refusal'), skipOffstage: false));
        await tester.pumpAndSettle();
        expect(
          text(tester, 'holder.$row.refusal'),
          'Tarif réservé aux membres à jour de cotisation, inscrits avant le '
          '${day(cutoffOf('order_409_members_only_paid'))}',
        );
        // Only the fan's own place links to their cotisation; a friend's is theirs to pay.
        final seeClub = find.byKey(const Key('dues.seeClub'), skipOffstage: false);
        expect(seeClub, link ? findsOneWidget : findsNothing);
        if (link) {
          await tester.ensureVisible(seeClub);
          await tester.tap(seeClub);
          await tester.pumpAndSettle();
          expect(c.read(routerProvider).state.uri.path, '/club');
          expectClubInView(tester, presaleOrg);
          // Pushed: back on screen 4, the form is as the fan left it.
          c.read(routerProvider).pop();
          await tester.pumpAndSettle();
          expect(c.read(routerProvider).state.uri.path, '/events/1/checkout');
          expect(tester.widget<TextField>(find.byKey(const Key('holder.1.name'))).controller!.text, 'Sara Benali');
        }
      });
    }

    test('paid-up copy in fr, ar and en; without the flag the step-2 copy stays', () {
      final ar = lookupAppLocalizations(const Locale('ar'));
      final en = lookupAppLocalizations(const Locale('en'));
      ApiError e(Map<String, Object?> details) =>
          ApiError(status: 409, code: 'MEMBERS_ONLY', message: 'x', details: details);
      // Real run: an event with no sale start reads member_joined_before null.
      final paid = e(
        realAnswer('order_409_members_only_paid_any', dir: 'club').body['details']! as Map<String, Object?>,
      );
      expect(paid.details['member_joined_before'], isNull);
      expect(holderRefusalText(l, paid, zone), 'Tarif réservé aux membres à jour de cotisation');
      expect(holderRefusalText(ar, paid, zone), 'هاد السعر غير للأعضاء اللي خالصين واجب الانخراط');
      expect(holderRefusalText(en, paid, zone), 'Members-only price: membership fee paid up');
      expect(needsDues(paid), isTrue);
      // No row to put it on: the banner says it too.
      expect(apiErrorText(l, e({'paid_required': true})), 'Tarif réservé aux membres à jour de cotisation');
      expect(apiErrorText(l, e({'paid_required': false})), 'Tarif réservé aux membres');
      expect(needsDues(e(const {})), isFalse);
      expect(
        (l.queueNotMemberPaidAny, ar.queueNotMemberPaidAny, en.queueNotMemberPaidAny),
        (
          'Réservée aux membres à jour de cotisation',
          'خاص بالأعضاء اللي خالصين واجب الانخراط',
          'Reserved for paid-up members',
        ),
      );
    });
  });

  group('the real API (FL-26 real run, test/fixtures/queue)', () {
    // The phone's clock as the server's at the first captured answer.
    setUp(() {
      clock = TestClock(DateTime.utc(2026, 11, 15, 11, 7, 37));
      sale = RoomSale(clock);
    });

    QueueStatus status(String name) => QueueStatus.fromJson(realAnswer(name).body);

    Future<void> next(WidgetTester tester, String answered) async {
      await tester.pump(Duration(seconds: status(answered).pollAfterS));
      await flush(tester);
      await tester.pump(const Duration(milliseconds: 300));
    }

    test('every 2xx answer of /fan/queue parses with the phase the server meant', () {
      expect(
        [
          for (final n in ['join_201_pre_queue', 'join_200_pre_queue', 'join_201_rejoin']) realAnswer(n).status,
        ],
        [201, 200, 201],
      );
      for (final (name, phase) in [
        ('join_201_pre_queue', QueuePhase.preQueue),
        ('join_200_pre_queue', QueuePhase.preQueue),
        ('poll_pre_queue', QueuePhase.preQueue),
        ('poll_waiting', QueuePhase.waiting),
        ('poll_paused', QueuePhase.paused),
        ('poll_admitted', QueuePhase.admitted),
        ('poll_expired', QueuePhase.expired),
        ('join_201_rejoin', QueuePhase.waiting),
        ('poll_ended', QueuePhase.ended),
      ]) {
        expect(status(name).phase, phase, reason: name);
      }
      expect(status('poll_waiting').position, isNotNull);
      expect(status('poll_pre_queue').pollAfterS, inInclusiveRange(1, 30));
      expect(
        [
          for (final n in ['poll_admitted', 'poll_expired', 'poll_ended']) status(n).pollAfterS,
        ],
        [0, 0, 0],
      );
      final turn = status('poll_admitted');
      expect(turn.admissionToken, 'adm-real');
      expect(turn.admissionExpiresAt!.difference(turn.serverTime).inSeconds, inInclusiveRange(599, 600));
      for (final n in ['poll_waiting', 'poll_expired', 'poll_ended']) {
        expect(status(n).admissionToken, isNull, reason: n);
      }
    });

    test('the event reads its room; the order held with the token reads', () {
      EventDetail read(String n) => EventDetail.fromJson(realAnswer(n).body, fetchedAt: clock.now);
      final pre = read('event_room_pre_queue').event.room!;
      expect(
        (pre.status, pre.eligibility!.verifiedBefore, pre.eligibility!.memberJoinedBefore),
        ('PRE_QUEUE', DateTime.utc(2026, 11, 8, 11, 11, 33), null),
      );
      final scheduled = read('event_room_scheduled').event.room!;
      expect((scheduled.status, scheduled.eligibility), ('SCHEDULED', null));
      expect(read('event_room_ended').event.room, isNull);
      final order = FanOrder.fromJson(realAnswer('order_201_with_admission').body);
      expect((order.status, order.totalMad, order.items.single.qty), ('RESERVED', 5000, 1));
      final notStarted = realAnswer('queue_not_open_not_started').body['details']! as Map<String, Object?>;
      expect(notStarted['reason'], 'NOT_STARTED');
      expect(DateTime.tryParse(notStarted['opens_at']! as String), isNotNull);
    });

    testWidgets('join 201 → pre-queue → POLL 429 → waiting → paused → the turn → an order with the token', (
      tester,
    ) async {
      sale.joins.add(() => sale.real('join_201_pre_queue'));
      sale.polls
        ..add(() => sale.real('poll_pre_queue'))
        ..add(() => sale.real('poll_rate_limited'))
        ..add(() => sale.real('poll_waiting'))
        ..add(() => sale.real('poll_paused'))
        ..add(() => sale.real('poll_admitted'));
      sale.orders.add(() => sale.real('order_201_with_admission'));
      await start(tester);
      expect(find.byKey(const Key('queue.draw')), findsOneWidget);
      // The first join answers in_room 0 (the memo is a second behind it): the fan still counts.
      expect(text(tester, 'queue.inRoom'), '1 fan dans la file');

      await next(tester, 'join_201_pre_queue');
      expect(sale.calls('GET', '/fan/queue'), hasLength(1));
      expect(text(tester, 'queue.inRoom'), '${status('poll_pre_queue').inRoom} fans dans la file');
      await next(tester, 'poll_pre_queue');
      expect(sale.calls('GET', '/fan/queue'), hasLength(2));
      expect(find.byKey(const Key('queue.rateLimited')), findsNothing);
      await tester.pump(const Duration(seconds: 1));
      await flush(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(sale.calls('GET', '/fan/queue'), hasLength(3));
      expect(text(tester, 'queue.position'), 'N° 2');
      expect(text(tester, 'queue.ahead'), '1 fan devant toi');
      // rate_per_min stays 0 until the first measured minute: no estimate rather than a wrong one.
      expect(find.byKey(const Key('queue.wait')), findsNothing);

      await next(tester, 'poll_waiting');
      expect(text(tester, 'queue.wait'), l.queuePaused);
      expect(text(tester, 'queue.position'), 'N° 2');

      await next(tester, 'poll_paused');
      expect(text(tester, 'queue.turnLeft'), 'Tu as 09:59 pour réserver');
      await tester.pump(const Duration(minutes: 1));
      expect(sale.calls('GET', '/fan/queue'), hasLength(5), reason: 'poll_after_s 0: no more polls');

      final container = ProviderScope.containerOf(tester.element(find.byType(QueueScreen)));
      container.read(routerProvider).go('/events/1/checkout?price=pc-1-0&qty=1');
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const Key('checkout.pay')));
      await tester.tap(find.byKey(const Key('checkout.pay')));
      await flush(tester);
      expect(sale.calls('POST', '/fan/orders').single.headers['x-admission-token'], 'adm-real');
      await tester.pumpAndSettle();
      expect(find.byType(QueueScreen), findsNothing);
    });

    testWidgets('the turn passed: EXPIRED → "Reprendre une place" (201) → ENDED → screen 3', (tester) async {
      FlutterSecureStorage.setMockInitialValues({
        'queue_passes': jsonEncode({'1': const QueuePass(queueToken: 'qt-real', admissionToken: 'adm-real').toJson()}),
      });
      sale.polls.add(() => sale.real('poll_expired'));
      sale.joins.add(() => sale.real('join_201_rejoin'));
      final container = await start(tester, keepSecureStorage: true);
      await tester.pump(const Duration(milliseconds: 300));
      expect(text(tester, 'queue.expired'), 'Ton tour est passé');
      expect((await container.read(queuePassesProvider.future))[1]!.admissionToken, isNull);

      sale.polls
        ..clear()
        ..add(() => sale.real('poll_after_rejoin'))
        ..add(() {
          sale.room = null; // The real run ended its only room.
          return sale.real('poll_ended');
        });
      await tester.tap(find.byKey(const Key('queue.rejoin')));
      await flush(tester);
      await tester.pump(const Duration(milliseconds: 300));
      expect(sale.calls('POST', '/fan/queue'), hasLength(1));
      expect(find.byKey(const Key('queue.expired')), findsNothing);
      // At the back, not ranked yet; in_room 0 again from the memo.
      expect(find.text(l.queueRankingTitle), findsOneWidget);
      expect(text(tester, 'queue.inRoom'), '1 fan dans la file');
      await next(tester, 'join_201_rejoin');
      expect(text(tester, 'queue.position'), 'N° 1');
      expect(text(tester, 'queue.ahead'), 'Personne devant toi');
      await next(tester, 'poll_after_rejoin');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('zones.continue')), findsOneWidget);
      expect(sale.calls('GET', '/fan/queue'), hasLength(3));
    });
  });
}
