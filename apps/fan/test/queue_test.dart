import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/queue/queue.dart';
import 'package:jomhor_fan/features/queue/queue_screen.dart';
import 'package:jomhor_fan/features/queue/queue_store.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';

/// The sale opens at 12:30 (UTC) on the test day; its room takes joins from 12:00, the test clock's start.
const saleStartsAt = '2027-02-20T12:30:00Z';
const opensAt = '2027-02-20T12:00:00Z';
const zone = 'Africa/Casablanca';

/// `room` of `GET /fan/events*` (docs/04-api.md §Fan, Lot 5).
Map<String, Object?> roomJson({String status = 'PRE_QUEUE', String at = opensAt, Map<String, Object?>? eligibility}) =>
    {'status': status, 'opens_at': at, 'eligibility': eligibility};

/// A fake backend for one event (id 1) with a waiting room; `/fan/queue` shaped as docs/04-api.md.
class RoomSale {
  new(this.clock, {this.serverSkew = Duration.zero});

  final TestClock clock;

  /// Server clock minus device clock.
  final Duration serverSkew;
  final requests = <http.Request>[];
  Map<String, Object?>? room = roomJson();

  /// Queued answers of POST /fan/queue (default: 201 in the pre-queue).
  final joins = <http.Response Function()>[];

  /// Answers of GET /fan/queue in turn; the last one repeats (default: the pre-queue).
  final polls = <http.Response Function()>[];

  /// Queued answers of POST /fan/orders (default: 409 ADMISSION_REQUIRED).
  final orders = <http.Response Function()>[];

  DateTime get serverNow => clock.now.add(serverSkew);

  /// The `GET /fan/queue` shape.
  Map<String, Object?> q({
    String status = 'PRE_QUEUE',
    int? position,
    int inRoom = 120,
    int rate = 0,
    int? wait,
    bool soldOut = false,
    Duration? turn,
    String token = 'qt-1',
    int pollAfter = 5,
  }) => {
    'event_id': 1,
    'status': status,
    'sale_starts_at': saleStartsAt,
    'position': position,
    'in_room': inRoom,
    'rate_per_min': rate,
    'estimated_wait_s': wait,
    'sold_out': soldOut,
    'admission': turn == null ? null : {'token': 'adm-1', 'expires_at': serverNow.add(turn).toIso8601String()},
    'queue_token': token,
    'poll_after_s': pollAfter,
    'server_time': serverNow.toIso8601String(),
  };

  http.Response answer(Object? body, [int status = 200]) => http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    status,
    headers: {'content-type': 'application/json', 'date': HttpDate.format(serverNow)},
  );

  http.Response refusal(int status, String code, Map<String, Object?> details) =>
      answer({'code': code, 'message': code, 'details': details}, status);

  Map<String, Object?> event() => eventJson(
    onSale: false,
    saleStartsAt: saleStartsAt,
    zones: [
      zoneJson(1, 'Tribune Nord', [(5000, 300)]),
      zoneJson(2, 'Virage Sud', [(3000, 120)], seated: false),
    ],
  )..['room'] = room;

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    return switch ((r.method, r.url.path)) {
      ('GET', '/v1/fan/events') => answer([event()..remove('zones')]),
      ('GET', '/v1/fan/events/1') => answer(event()),
      ('POST', '/v1/fan/queue') => joins.isEmpty ? answer(q(), 201) : joins.removeAt(0)(),
      ('GET', '/v1/fan/queue') => switch (polls.length) {
        0 => answer(q()),
        1 => polls.first(),
        _ => polls.removeAt(0)(),
      },
      ('GET', '/v1/fan/me') => answer({'id': 'u1', 'phone': '+212600000001', 'display_name': 'Yazid Aqel'}),
      ('POST', '/v1/fan/orders') =>
        orders.isEmpty ? refusal(409, 'ADMISSION_REQUIRED', {'reason': 'EXPIRED'}) : orders.removeAt(0)(),
      _ => apiError(404, 'NOT_FOUND'),
    };
  }

  List<http.Request> calls(String method, String path) => [
    ...requests.where((r) => r.method == method && r.url.path == '/v1$path'),
  ];
}

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
  }) async {
    final container = await pumpJomhor(
      tester,
      backend: sale.handle,
      tokens: MemoryTokenStore(signedIn ? 'jwt' : null),
      clock: clock,
      keepSecureStorage: keepSecureStorage,
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

    testWidgets('ENDED goes straight to screen 3 and stops', (tester) async {
      sale.polls.add(() => sale.answer(sale.q(status: 'ENDED', pollAfter: 0)));
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
        ..add(() => sale.refusal(429, 'QUEUE_RATE_LIMITED', {'reason': 'POLL', 'retry_after_s': 2}))
        ..add(() => sale.answer(sale.q()));
      await start(tester);
      await tester.pump(const Duration(seconds: 5));
      await flush(tester);
      expect(find.byKey(const Key('queue.rateLimited')), findsNothing);
      expect(find.byKey(const Key('queue.draw')), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 1900));
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

    testWidgets('POST /fan/orders carries X-Admission-Token; ADMISSION_REQUIRED goes back to screen 12', (
      tester,
    ) async {
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
    for (final (reason, details, copy) in [
      ('ACCOUNT_TOO_NEW', {'verified_before': '2027-02-13T12:30:00Z'}, (String d) => 'Compte vérifié après le $d'),
      (
        'NOT_MEMBER',
        {'member_joined_before': '2027-01-01T00:00:00Z'},
        (String d) => 'Réservée aux membres inscrits avant le $d',
      ),
      ('NOT_ENOUGH_EVENTS', {'min_events_attended': 3, 'attended': 2}, (_) => 'Il faut 3 matchs au stade · tu en as 2'),
    ]) {
      testWidgets('NOT_ELIGIBLE $reason', (tester) async {
        sale.joins.add(() => sale.refusal(409, 'NOT_ELIGIBLE', {'reason': reason, ...details}));
        await start(tester);
        await tester.pump(const Duration(milliseconds: 300));
        final date = details.values.whereType<String>().firstOrNull;
        expect(find.text('Vente réservée aux fans vérifiés'), findsOneWidget);
        expect(find.text(copy(date == null ? '' : venueDay(DateTime.parse(date), zone, 'fr'))), findsOneWidget);
        await tester.pump(const Duration(minutes: 1));
        expect(sale.calls('POST', '/fan/queue'), hasLength(1));
        expect(sale.calls('GET', '/fan/queue'), isEmpty);
      });
    }

    testWidgets('QUEUE_DEVICE_LIMIT', (tester) async {
      sale.joins.add(() => sale.refusal(409, 'QUEUE_DEVICE_LIMIT', {'limit': 3}));
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
}
