import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/group_view.dart';
import 'package:jomhor_fan/features/checkout/holder_name.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/checkout/order_status.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';

/// The initiator's (buyer's) order and the member orders of the friends who pay their own place.
const buyerId = '0b8e7a52-0000-4000-8000-0000000000d0';
const memberId = '0b8e7a52-0000-4000-8000-0000000000d1';
const karimId = '0b8e7a52-0000-4000-8000-0000000000e1';
const saraId = '0b8e7a52-0000-4000-8000-0000000000e2';
const omarId = '0b8e7a52-0000-4000-8000-0000000000e3';
const redirect = 'http://10.0.2.2:8080/v1/payments/card/p1/redirect';

/// The phones of the fixtures (the buyer's /fan/me and what the tests type): none may leave in a WhatsApp link.
const myPhone = '+212600000001';
const friendPhone = '06 12 34 56 78';

String payUrl(String id) => 'https://jomhor.ma/orders/$id';

/// A fake backend shaped on docs/04-api.md §Split pay: event 1 (Tribune Nord, 50 DH), the buyer's PAID order with
/// three friends (Karim to pay, Sara paid, Omar expired: 2 of 4 places paid), and a member order of the caller.
class SplitBackend {
  new(this.clock);

  final TestClock clock;
  final requests = <http.Request>[];

  /// Answers POST /fan/orders instead of the default 201; gets the body.
  http.Response Function(Map<String, Object?> body)? orderAnswer;

  /// Status of each friend's order in the buyer's group, by order id.
  final friends = <String, String>{karimId: 'RESERVED', saraId: 'PAID', omarId: 'EXPIRED'};

  /// Each friend's `holder_label`.
  final labels = <String, String>{karimId: 'Karim B.', saraId: 'Sara B.', omarId: 'Omar T.'};
  late DateTime friendsUntil = clock.now.add(const Duration(hours: 1, minutes: 30));

  /// The caller's member order.
  String memberStatus = 'RESERVED';
  String? memberPaymentStatus;
  String? memberPaymentMethod;
  String? initiatorLabel = 'Yazid A.';

  /// `GET /fan/orders/{id}` answers 503 while set (offline, a proxy page).
  bool ordersDown = false;

  /// `details.reason` of a 409 METHOD_NOT_AVAILABLE for the member's cash `/pay`; null = the code is issued.
  String? cashRefusal;
  late DateTime memberUntil = clock.now.add(const Duration(hours: 1, minutes: 30));

  http.Response answer(Object? body, [int code = 200]) => http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    code,
    headers: {'content-type': 'application/json', 'date': HttpDate.format(clock.now)},
  );

  static Map<String, Object?> item(int qty, List<String> labels) => {
    'price_category_id': 'pc-1-0',
    'zone_id': 1,
    'zone_name': 'Tribune Nord',
    'name': 'Tarif 0',
    'qty': qty,
    'unit_price_mad': 5000,
    'holders': [
      for (final l in labels) {'label': l},
    ],
  };

  Map<String, Object?> friend(String id) => {
    'order_id': id,
    'holder_label': labels[id],
    'places': 1,
    'total_mad': 5000,
    'status': friends[id],
    'reserved_until': friendsUntil.toIso8601String(),
    'pay_url': payUrl(id),
  };

  Map<String, Object?> buyer() => {
    'id': buyerId,
    'event_id': 1,
    'channel': 'APP',
    'status': 'PAID',
    'payment_status': 'SUCCEEDED',
    'payment_method': 'CARD',
    'total_mad': 5000,
    'reserved_until': clock.now.add(const Duration(minutes: 5)).toIso8601String(),
    'paid_at': clock.now.toIso8601String(),
    'items': [
      item(1, ['Yazid A.']),
    ],
    'ticket_ids': ['t1'],
    'cash': null,
    'group': {
      'role': 'INITIATOR',
      'members': [friend(karimId), friend(saraId), friend(omarId)],
    },
  };

  Map<String, Object?> member() => {
    'id': memberId,
    'event_id': 1,
    'channel': 'WEB',
    'status': memberStatus,
    'payment_status': memberPaymentStatus,
    'payment_method': memberPaymentMethod,
    'total_mad': 5000,
    'reserved_until': memberUntil.toIso8601String(),
    'paid_at': null,
    'items': [
      item(1, ['Karim B.']),
    ],
    'ticket_ids': memberStatus == 'PAID' ? ['t2'] : <String>[],
    'cash': memberStatus == 'AWAITING_CASH'
        ? {'pay_code': '4F7K29', 'amount_mad': 5000, 'points': <Object?>[], 'networks': <Object?>[]}
        : null,
    'group': {'role': 'MEMBER', 'initiator_label': initiatorLabel, 'initiator_status': 'PAID'},
  };

  /// The default POST /fan/orders answer: the buyer keeps the places without `pays`, one member order per friend who
  /// pays.
  Map<String, Object?> reserved(Map<String, Object?> body) {
    final holders = ((body['items']! as List<Object?>).single! as Map<String, Object?>)['holders']! as List<Object?>;
    final paying = holders.cast<Map<String, Object?>>().where((h) => h['pays'] == true).toList();
    final mine = holders.length - paying.length;
    return {
      'id': buyerId,
      'status': 'RESERVED',
      'reserved_until': clock.now.add(const Duration(minutes: 8)).toIso8601String(),
      'total_mad': 5000 * mine,
      'items': [item(mine, List.filled(mine, 'Yazid A.'))],
      'group': paying.isEmpty
          ? null
          : {
              'role': 'INITIATOR',
              'members': [
                for (final (i, _) in paying.indexed)
                  {
                    'order_id': '0b8e7a52-0000-4000-8000-0000000000f$i',
                    'holder_label': 'Sara B.',
                    'places': 1,
                    'total_mad': 5000,
                    'status': 'RESERVED',
                    'reserved_until': clock.now.add(const Duration(minutes: 8)).toIso8601String(),
                    'pay_url': payUrl('0b8e7a52-0000-4000-8000-0000000000f$i'),
                  },
              ],
            },
    };
  }

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    return switch ((r.method, r.url.path)) {
      ('GET', '/v1/fan/events') => answer(<Object?>[]),
      ('GET', '/v1/fan/events/1') => answer({
        ...eventJson(
          zones: [
            zoneJson(1, 'Tribune Nord', [(5000, 100)]),
          ],
        ),
        'payment_methods': ['CARD', 'WALLET', 'CASH_AGENCY'],
        'cash_until': '2027-03-13T16:00:00Z',
      }),
      ('GET', '/v1/fan/me') => answer({'id': 'u1', 'phone': myPhone, 'display_name': 'Yazid Aqel', 'locale': 'fr'}),
      ('POST', '/v1/fan/orders') => () {
        final body = jsonDecode(r.body) as Map<String, Object?>;
        return orderAnswer?.call(body) ?? answer(reserved(body), 201);
      }(),
      ('POST', '/v1/fan/orders/$buyerId/pay') => answer({
        'payment_id': 'p1',
        'method': 'CARD',
        'redirect_url': redirect,
      }),
      ('POST', '/v1/fan/orders/$memberId/pay') => payMember(jsonDecode(r.body) as Map<String, Object?>),
      ('GET', '/v1/fan/orders/$buyerId') => ordersDown ? apiError(503, 'INTERNAL_ERROR') : answer(buyer()),
      ('GET', '/v1/fan/orders/$memberId') => ordersDown ? apiError(503, 'INTERNAL_ERROR') : answer(member()),
      ('GET', '/v1/fan/orders') => answer({'orders': <Object?>[]}),
      ('GET', '/v1/fan/tickets') => answer({'server_time': clock.now.toIso8601String(), 'tickets': <Object?>[]}),
      _ => apiError(404, 'NOT_FOUND'),
    };
  }

  http.Response payMember(Map<String, Object?> body) {
    final method = body['method']! as String;
    memberPaymentMethod = method;
    memberPaymentStatus = 'PENDING';
    if (method != 'CASH_AGENCY') return answer({'payment_id': 'p2', 'method': method, 'redirect_url': redirect});
    if (cashRefusal case final reason?) {
      memberPaymentMethod = null;
      memberPaymentStatus = null;
      return answer({
        'code': 'METHOD_NOT_AVAILABLE',
        'message': 'x',
        'details': {'reason': reason},
      }, 409);
    }
    memberStatus = 'AWAITING_CASH';
    memberUntil = clock.now.add(const Duration(hours: 24));
    return answer({
      'payment_id': 'p3',
      'method': method,
      'pay_code': '4F7K29',
      'reserved_until': memberUntil.toIso8601String(),
      'amount_mad': 5000,
    });
  }

  Iterable<http.Request> calls(String method, String path) =>
      requests.where((r) => r.method == method && r.url.path == '/v1$path');

  List<Map<String, Object?>> bodies(String method, String path) => [
    for (final r in calls(method, path)) jsonDecode(r.body) as Map<String, Object?>,
  ];
}

void main() {
  late TestClock clock;
  late SplitBackend backend;
  late List<String> launched;
  late List<Uri> opened;
  late List<String> clipboard;

  setUp(() {
    clock = TestClock(DateTime.utc(2027, 2, 20, 12));
    backend = SplitBackend(clock);
    launched = [];
    opened = [];
    clipboard = [];
    SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
  });

  Future<ProviderContainer> open(WidgetTester tester, String location, {bool settle = true}) async {
    tester.view
      ..physicalSize = const Size(800, 2400)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        clipboard.add((call.arguments as Map<Object?, Object?>)['text']! as String);
      }
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    final c = await pumpJomhor(
      tester,
      backend: backend.handle,
      tokens: MemoryTokenStore('jwt'),
      clock: clock,
      overrides: [
        cardPaymentLauncherProvider.overrideWithValue((context, url, id) async {
          launched.add(url);
          return false;
        }),
        openExternalProvider.overrideWithValue((url) async => opened.add(url)),
      ],
    );
    c.read(routerProvider).go(location);
    if (settle) await tester.pumpAndSettle();
    return c;
  }

  Future<void> tap(WidgetTester tester, Finder f) async {
    await tester.ensureVisible(f);
    await tester.tap(f);
    await tester.pump();
    await tester.pump();
  }

  String text(WidgetTester tester, String key) => tester.widget<Text>(find.byKey(Key(key))).data!;
  String cta(WidgetTester tester) =>
      (tester.widget<FilledButton>(find.byKey(const Key('checkout.pay'))).child! as Text).data!;

  group('helpers', () {
    test('initials: first letter of the first and last words; Arabic letters spaced so they do not join', () {
      expect(labelInitials('Karim B.'), 'KB');
      expect(labelInitials('yazid a.'), 'YA');
      expect(labelInitials('Karim'), 'K');
      expect(labelInitials('يزيد ع.'), 'ي ع');
      expect(holderLabel('Sara Benali'), 'Sara B.');
      expect(holderLabel('Sara'), 'Sara');
      expect(holderLabel('يزيد العلوي'), 'يزيد ا.');
    });

    test('{paid}/{total} counts places: the buyer + every friend (expired too), paid = places of PAID orders', () {
      final order = FanOrder.fromJson(SplitBackend(clock).buyer());
      expect(groupPlaces(order), (paid: 2, total: 4));
    });

    test('a group role this app does not know is no group: the plain order screen', () {
      final json = {
        ...SplitBackend(clock).member(),
        'group': {'role': 'SPONSOR'},
      };
      expect(FanOrder.fromJson(json).group, isNull);
    });

    test('polling: 10 s while a friend can pay, none once all are settled; a member waits 10 s before any attempt', () {
      final b = SplitBackend(clock);
      expect(pollAfter(FanOrder.fromJson(b.buyer())), OrderStatus.everyCash);
      b.friends[karimId] = 'EXPIRED';
      expect(pollAfter(FanOrder.fromJson(b.buyer())), isNull);
      expect(pollAfter(FanOrder.fromJson(b.member())), OrderStatus.everyCash);
      b.memberPaymentStatus = 'PENDING';
      expect(pollAfter(FanOrder.fromJson(b.member())), OrderStatus.every);
    });
  });

  group('screen 4', () {
    const checkout = '/events/1/checkout?price=pc-1-0&qty=3';

    Future<void> fill(WidgetTester tester) async {
      await tester.enterText(find.byKey(const Key('holder.1.name')), 'Sara Benali');
      await tester.enterText(find.byKey(const Key('holder.2.name')), 'Omar Tazi');
    }

    Future<void> consent(WidgetTester tester) async {
      await tester.ensureVisible(find.byKey(const Key('checkout.consent')));
      await tester.tap(find.byKey(const Key('checkout.consent')));
      await tester.pump();
    }

    testWidgets('the switch shows only on a friend row with a phone; pays: true only there; split summary', (
      tester,
    ) async {
      await open(tester, checkout);
      await fill(tester);
      expect(find.byKey(const Key('holder.1.pays')), findsNothing, reason: 'no phone yet');
      expect(find.byKey(const Key('holder.0.pays')), findsNothing);
      await tester.enterText(find.byKey(const Key('holder.1.phone')), friendPhone);
      await tester.enterText(find.byKey(const Key('holder.2.phone')), '0698765432');
      await tester.pump();
      expect(find.byKey(const Key('holder.1.pays')), findsOneWidget);
      expect(tester.widget<SwitchListTile>(find.byKey(const Key('holder.1.pays'))).value, isFalse, reason: 'off');
      expect(cta(tester), 'Payer ${formatDh(15000)}');

      await tap(tester, find.byKey(const Key('holder.1.pays')));
      expect(text(tester, 'split.mine'), 'Tu paies 2 places · ${formatDh(10000)}');
      expect(text(tester, 'split.friends'), '1 place payée par tes amis');
      expect(find.text('Tes amis ont 2 h après ton paiement'), findsOneWidget);
      expect(cta(tester), 'Payer ${formatDh(10000)}', reason: 'the CTA is the own total, in integer centimes');

      await consent(tester);
      await tap(tester, find.byKey(const Key('checkout.pay')));
      await tester.pumpAndSettle();
      final holders =
          ((backend.bodies('POST', '/fan/orders').single['items']! as List<Object?>).single!
                  as Map<String, Object?>)['holders']!
              as List<Object?>;
      expect(holders, [
        {'name': 'Yazid Aqel'},
        {'name': 'Sara Benali', 'phone': '+212612345678', 'pays': true},
        {'name': 'Omar Tazi', 'phone': '+212698765432'},
      ]);
      expect(backend.bodies('POST', '/fan/orders/$buyerId/pay').single, {'method': 'CARD', 'channel': 'APP'});
    });

    testWidgets('a switch left on after its phone is cleared does not count', (tester) async {
      await open(tester, checkout);
      await fill(tester);
      await tester.enterText(find.byKey(const Key('holder.1.phone')), friendPhone);
      await tester.pump();
      await tap(tester, find.byKey(const Key('holder.1.pays')));
      await tester.enterText(find.byKey(const Key('holder.1.phone')), '');
      await tester.pump();
      expect(find.byKey(const Key('split.mine')), findsNothing);
      expect(cta(tester), 'Payer ${formatDh(15000)}');
    });

    Future<void> splitOne(WidgetTester tester) async {
      await fill(tester);
      await tester.enterText(find.byKey(const Key('holder.1.phone')), friendPhone);
      await tester.pump();
      await tap(tester, find.byKey(const Key('holder.1.pays')));
      await consent(tester);
      await tap(tester, find.byKey(const Key('checkout.pay')));
      await tester.pumpAndSettle();
    }

    http.Response refused(String path, String reason) => backend.answer({
      'code': 'VALIDATION_FAILED',
      'message': 'x',
      'details': {
        'fields': {path: reason},
        'reason': reason,
      },
    }, 422);

    testWidgets(
      '422 TOO_LATE: every switch off and disabled with "Trop tard pour partager"; paying again splits nothing',
      (tester) async {
        backend.orderAnswer = (_) => refused('items[0].holders[1].pays', 'TOO_LATE');
        await open(tester, checkout);
        await splitOne(tester);
        final s = tester.widget<SwitchListTile>(find.byKey(const Key('holder.1.pays')));
        expect((s.value, s.onChanged), (false, null));
        expect(find.text('Trop tard pour partager'), findsOneWidget);
        expect(find.byKey(const Key('split.mine')), findsNothing);
        expect(cta(tester), 'Payer ${formatDh(15000)}');

        backend.orderAnswer = null;
        await tap(tester, find.byKey(const Key('checkout.pay')));
        await tester.pumpAndSettle();
        final second = jsonEncode(backend.bodies('POST', '/fan/orders').last);
        expect(second, isNot(contains('pays')));
      },
    );

    testWidgets('422 PHONE_REQUIRED with the buyer\'s own number: "Ce numéro est le tien" on that row', (tester) async {
      backend.orderAnswer = (_) => refused('items[0].holders[1].pays', 'PHONE_REQUIRED');
      await open(tester, checkout);
      await fill(tester);
      await tester.enterText(find.byKey(const Key('holder.1.phone')), '06 00 00 00 01');
      await tester.pump();
      await tap(tester, find.byKey(const Key('holder.1.pays')));
      await consent(tester);
      await tap(tester, find.byKey(const Key('checkout.pay')));
      await tester.pumpAndSettle();
      expect(find.text('Ce numéro est le tien'), findsOneWidget);
    });

    testWidgets('409 PER_ACCOUNT_LIMIT with details.field: "{label} a déjà atteint sa limite" on that row', (
      tester,
    ) async {
      backend.orderAnswer = (_) => backend.answer({
        'code': 'PER_ACCOUNT_LIMIT',
        'message': 'x',
        'details': {'limit': 4, 'held': 4, 'field': 'items[0].holders[1]'},
      }, 409);
      await open(tester, checkout);
      await splitOne(tester);
      expect(find.text('Sara B. a déjà atteint sa limite'), findsOneWidget);
      expect(find.byKey(const Key('checkout.error')), findsNothing, reason: 'said on the row, not in the footer');
    });
  });

  group('screen 9 initiator', () {
    testWidgets('2/4 payées, one row card per friend with initials, amount and chip', (tester) async {
      await open(tester, '/orders/$buyerId');
      expect(text(tester, 'group.paid'), '2');
      expect(text(tester, 'group.total'), '/4');
      expect(find.text('Ta bande · Atlas FC vs Rapid Oued'), findsOneWidget);
      expect(text(tester, 'group.initials.$karimId'), 'KB');
      final due = venueClock(backend.friendsUntil, 'Africa/Casablanca', 'fr');
      Finder chip(String id, String copy) =>
          find.descendant(of: find.byKey(Key('group.chip.$id')), matching: find.text(copy));
      expect(chip(karimId, 'À payer · avant $due'), findsOneWidget);
      expect(chip(saraId, 'Payé'), findsOneWidget);
      expect(chip(omarId, 'Expiré'), findsOneWidget);
      // Nudges only for the friend still to pay.
      expect(find.byKey(const Key('group.remind.$karimId')), findsOneWidget);
      expect(find.byKey(const Key('group.remind.$saraId')), findsNothing);
      expect(find.byKey(const Key('group.copy.$omarId')), findsNothing);
    });

    testWidgets("Relancer opens wa.me with the copy and the friend's pay_url, never a phone", (tester) async {
      await open(tester, '/orders/$buyerId');
      await tap(tester, find.byKey(const Key('group.remind.$karimId')));
      final url = opened.single;
      expect((url.scheme, url.host, url.path), ('https', 'wa.me', '/'));
      final message = url.queryParameters['text']!;
      expect(message, contains(payUrl(karimId)));
      expect(message, contains('Atlas FC vs Rapid Oued'));
      final digits = url.toString().replaceAll(RegExp(r'\D'), '');
      for (final phone in [myPhone, friendPhone, '+212612345678']) {
        final tail = phone.replaceAll(RegExp(r'\D'), '').substring(phone.replaceAll(RegExp(r'\D'), '').length - 9);
        expect(digits, isNot(contains(tail)), reason: phone);
      }
    });

    testWidgets('Copier le lien copies the pay_url and says so', (tester) async {
      await open(tester, '/orders/$buyerId');
      await tap(tester, find.byKey(const Key('group.copy.$karimId')));
      expect(clipboard, [payUrl(karimId)]);
      expect(find.text('Lien copié'), findsOneWidget);
    });

    testWidgets('polls every 10 s while a friend can pay and stops once all are settled; the figure rolls to 3/4', (
      tester,
    ) async {
      await open(tester, '/orders/$buyerId');
      int reads() => backend.calls('GET', '/fan/orders/$buyerId').length;
      final first = reads();
      await tester.pump(const Duration(seconds: 9));
      expect(reads(), first);
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(reads(), first + 1);

      backend.friends[karimId] = 'PAID';
      await tester.pump(const Duration(seconds: 10));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.byKey(const Key('group.paid')), findsWidgets, reason: 'the old and new figures cross while rolling');
      await tester.pumpAndSettle();
      expect(text(tester, 'group.paid'), '3');
      final settled = reads();
      await tester.pump(const Duration(seconds: 60));
      expect(reads(), settled, reason: 'every friend settled: no more reads');
    });

    testWidgets('a failed read keeps the 10 s cadence of the group view (offline: no read every 2 s)', (tester) async {
      await open(tester, '/orders/$buyerId');
      int reads() => backend.calls('GET', '/fan/orders/$buyerId').length;
      backend.ordersDown = true;
      await tester.pump(const Duration(seconds: 10));
      await tester.pump();
      final failed = reads();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump(const Duration(seconds: 2));
      expect(reads(), failed, reason: 'not the 2 s of a card payment');
      await tester.pump(const Duration(seconds: 6));
      await tester.pump();
      expect(reads(), failed + 1);
      expect(find.byKey(const Key('group.figure')), findsOneWidget, reason: 'the last answer stays on screen');
    });

    testWidgets('each paid check settles in once: not again on re-open; group.seen keeps member ids only', (
      tester,
    ) async {
      final c = await open(tester, '/orders/$buyerId', settle: false);
      var settled = false;
      for (var i = 0; i < 60 && !settled; i++) {
        await tester.pump(const Duration(milliseconds: 16));
        settled = find.byKey(const Key('group.settling.$saraId')).evaluate().isNotEmpty;
      }
      expect(settled, isTrue, reason: 'Sara paid: her check settles in');
      await tester.pumpAndSettle();
      expect(await SharedPreferencesAsync().getStringList('group.seen'), [saraId]);

      c.read(routerProvider).go('/');
      await tester.pumpAndSettle();
      c.read(routerProvider).go('/orders/$buyerId');
      for (var i = 0; i < 60; i++) {
        await tester.pump(const Duration(milliseconds: 16));
        expect(find.byKey(const Key('group.settling.$saraId')), findsNothing);
      }
      expect(find.byKey(const Key('group.chip.$saraId')), findsOneWidget);
    });

    testWidgets('signing out wipes group.seen', (tester) async {
      final c = await open(tester, '/orders/$buyerId');
      expect(await SharedPreferencesAsync().getStringList('group.seen'), isNotNull);
      await c.read(sessionProvider.notifier).signOut();
      await tester.pumpAndSettle();
      expect(await SharedPreferencesAsync().getStringList('group.seen'), isNull);
    });
  });

  group('screen 9 member', () {
    testWidgets('a fan who follows no club opens /orders/{id}: event hero, "Yazid A. t\'a gardé une place", amount', (
      tester,
    ) async {
      await open(tester, '/orders/$memberId');
      expect(find.text("Yazid A. t'a gardé une place"), findsOneWidget);
      expect(find.text('ATLAS FC VS RAPID OUED'), findsOneWidget);
      expect(find.text('Atlas FC'), findsWidgets, reason: 'the club as crest + name');
      expect(text(tester, 'member.amount'), formatDh(5000));
      expect(find.byKey(const Key('method.CASH_AGENCY')), findsOneWidget);
    });

    testWidgets('no initiator label: "Un ami t\'a gardé une place"', (tester) async {
      backend.initiatorLabel = null;
      await open(tester, '/orders/$memberId');
      expect(find.text("Un ami t'a gardé une place"), findsOneWidget);
    });

    testWidgets('pays by card through the shared method rows: /pay {CARD, APP}, the hosted page opens', (tester) async {
      await open(tester, '/orders/$memberId');
      await tap(tester, find.byKey(const Key('member.pay')));
      await tester.pump(const Duration(milliseconds: 500)); // "Paiement en cours" spins: no settle.
      expect(backend.bodies('POST', '/fan/orders/$memberId/pay'), [
        {'method': 'CARD', 'channel': 'APP'},
      ]);
      expect(launched, [redirect]);
      expect(find.byKey(const Key('order.pending')), findsOneWidget, reason: 'PENDING: paiement en cours');
    });

    testWidgets('pays by cash: the code screen', (tester) async {
      await open(tester, '/orders/$memberId');
      await tap(tester, find.byKey(const Key('method.CASH_AGENCY')));
      expect(
        (tester.widget<FilledButton>(find.byKey(const Key('member.pay'))).child! as Text).data,
        'Obtenir mon code cash',
      );
      await tap(tester, find.byKey(const Key('member.pay')));
      await tester.pumpAndSettle();
      expect(backend.bodies('POST', '/fan/orders/$memberId/pay'), [
        {'method': 'CASH_AGENCY', 'channel': 'APP'},
      ]);
      expect(find.byKey(const Key('cash.code')), findsOneWidget);
      expect(launched, isEmpty);
    });

    testWidgets('a FAILED attempt shows the member screen again, with the failed line', (tester) async {
      await open(tester, '/orders/$memberId');
      expect(find.byKey(const Key('member.failed')), findsNothing);
      backend
        ..memberPaymentStatus = 'FAILED'
        ..memberPaymentMethod = 'CARD';
      await tester.pump(const Duration(seconds: 10));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('member.kept')), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(const Key('member.failed'))).data,
        "Ton paiement n'a pas abouti. Tu peux réessayer tant que tes places sont réservées.",
      );
    });

    testWidgets('a method /pay refuses (METHOD_NOT_AVAILABLE) goes off; the selection falls back to card', (
      tester,
    ) async {
      backend.cashRefusal = 'CASH_CAP';
      await open(tester, '/orders/$memberId');
      await tap(tester, find.byKey(const Key('method.CASH_AGENCY')));
      await tap(tester, find.byKey(const Key('member.pay')));
      await tester.pumpAndSettle();
      expect(backend.bodies('POST', '/fan/orders/$memberId/pay').single['method'], 'CASH_AGENCY');
      expect(tester.widget<InkWell>(find.byKey(const Key('method.CASH_AGENCY'))).onTap, isNull);
      expect(
        (tester.widget<FilledButton>(find.byKey(const Key('member.pay'))).child! as Text).data,
        'Payer ${formatDh(5000)}',
      );
      await tester.pump(const Duration(seconds: 5)); // The error snackbar over the CTA goes.
      await tester.pumpAndSettle();
      await tap(tester, find.byKey(const Key('method.CASH_AGENCY')));
      await tap(tester, find.byKey(const Key('member.pay')));
      await tester.pump(const Duration(milliseconds: 500));
      expect(backend.bodies('POST', '/fan/orders/$memberId/pay').last['method'], 'CARD', reason: 'cash stays off');
    });

    testWidgets('at 00:00 the pay button goes off before the next read', (tester) async {
      backend.memberUntil = clock.now.add(const Duration(seconds: 5));
      await open(tester, '/orders/$memberId');
      FilledButton cta() => tester.widget<FilledButton>(find.byKey(const Key('member.pay')));
      expect(cta().onPressed, isNotNull);
      final reads = backend.calls('GET', '/fan/orders/$memberId').length;
      clock.advance(const Duration(seconds: 6));
      await tester.pump(const Duration(seconds: 6));
      expect(backend.calls('GET', '/fan/orders/$memberId'), hasLength(reads), reason: 'the 10 s read is not due yet');
      expect(cta().onPressed, isNull);
    });

    testWidgets('expired: "La place a été libérée"', (tester) async {
      backend.memberStatus = 'EXPIRED';
      await open(tester, '/orders/$memberId');
      expect(find.text('La place a été libérée'), findsOneWidget);
      expect(find.byKey(const Key('member.pay')), findsNothing);
    });

    testWidgets('the hold runs out on screen: released, no pay button', (tester) async {
      backend.memberUntil = clock.now.add(const Duration(seconds: 5));
      await open(tester, '/orders/$memberId');
      expect(find.byKey(const Key('member.pay')), findsOneWidget);
      clock.advance(const Duration(seconds: 10));
      await tester.pump(const Duration(seconds: 10));
      await tester.pumpAndSettle();
      expect(find.text('La place a été libérée'), findsOneWidget);
    });

    testWidgets('paid: the existing success card', (tester) async {
      backend.memberStatus = 'PAID';
      await open(tester, '/orders/$memberId');
      expect(find.byKey(const Key('order.paid')), findsOneWidget);
    });
  });
}
