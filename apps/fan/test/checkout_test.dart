import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/features/auth/login_screen.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/checkout_controller.dart';
import 'package:jomhor_fan/features/checkout/order_screen.dart';

import 'helpers.dart';

const orderId = '0b8e7a52-0000-4000-8000-0000000000aa';

/// A fake backend for one sale: event 1, zone "Tribune Nord" with category pc-1-0 at 50 DH.
class Sale {
  new(this.clock, {this.serverSkew = Duration.zero});

  final TestClock clock;

  /// Server clock minus device clock.
  final Duration serverSkew;
  final requests = <http.Request>[];

  /// Queued answers for POST /fan/orders (default: 201 held 8 min from server now).
  final orderAnswers = <FutureOr<http.Response> Function()>[];

  /// Statuses GET /fan/orders/{id} returns in turn (the last one repeats).
  List<String> statuses = ['RESERVED', 'PAID'];

  /// `payment_status` in GET /fan/orders/{id}; [absent] = the key is not sent (before BE-12).
  Object? paymentStatus = absent;
  static const absent = Object();
  String? displayName = 'Yazid Aqel';
  int perAccountLimit = 4;
  int remaining = 100;

  /// `reserved_until` minus server now in every order answer (negative = the hold is over).
  Duration hold = const Duration(minutes: 8);

  /// `redirect_url` of POST /pay; [payGate] holds that answer back until completed.
  String redirectUrl = 'http://10.0.2.2:8080/v1/payments/card/p1/redirect';
  Completer<void>? payGate;

  DateTime get serverNow => clock.now.add(serverSkew);

  Map<String, Object?> order(String status) => {
    'id': orderId,
    'event_id': 1,
    'channel': 'APP',
    'status': status,
    'total_mad': 10000,
    'reserved_until': serverNow.add(hold).toIso8601String(),
    'paid_at': null,
    'items': [
      {
        'price_category_id': 'pc-1-0',
        'zone_id': 1,
        'name': 'Tarif 0',
        'qty': 2,
        'unit_price_mad': 5000,
        'holders': [
          {'label': 'Yazid A.'},
          {'label': 'Sara B.'},
        ],
      },
    ],
    'ticket_ids': status == 'PAID' ? ['t1'] : <String>[],
    if (paymentStatus != absent) 'payment_status': paymentStatus,
  };

  http.Response answer(Object? body, [int status = 200]) => http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    status,
    headers: {'content-type': 'application/json', 'date': HttpDate.format(serverNow)},
  );

  var _reads = 0;

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    return switch ((r.method, r.url.path)) {
      ('GET', '/v1/fan/events') => answer(<Object?>[]),
      ('GET', '/v1/fan/events/1') => answer(
        eventJson(
          perAccountLimit: perAccountLimit,
          zones: [
            zoneJson(1, 'Tribune Nord', [(5000, remaining)]),
          ],
        ),
      ),
      ('GET', '/v1/fan/me') => answer({
        'id': 'u1',
        'phone': '+212600000001',
        'display_name': displayName,
        'locale': 'fr',
      }),
      ('PATCH', '/v1/fan/me') => answer({'id': 'u1', 'phone': '+212600000001', 'display_name': 'x', 'locale': 'fr'}),
      ('POST', '/v1/fan/orders') =>
        orderAnswers.isEmpty ? answer(order('RESERVED'), 201) : await orderAnswers.removeAt(0)(),
      ('POST', '/v1/fan/orders/$orderId/pay') => await () async {
        await payGate?.future;
        return answer({'payment_id': 'p1', 'redirect_url': redirectUrl});
      }(),
      ('GET', '/v1/fan/orders/$orderId') => answer(order(statuses[(_reads++).clamp(0, statuses.length - 1)])),
      _ => apiError(404, 'NOT_FOUND'),
    };
  }

  Iterable<http.Request> calls(String method, String path) =>
      requests.where((r) => r.method == method && r.url.path == '/v1$path');
}

void main() {
  late TestClock clock;
  late Sale sale;
  late List<String> launched;
  var cardReturns = true;

  /// When set, the card page stays open until this completes (with whether it reached our return URL).
  Completer<bool>? cardPage;

  setUp(() {
    clock = TestClock(DateTime.utc(2027, 2, 20, 12));
    sale = Sale(clock);
    launched = [];
    cardReturns = true;
    cardPage = null;
  });

  Future<ProviderContainer> open(WidgetTester tester, String location, {bool signedIn = true}) async {
    final container = await pumpJomhor(
      tester,
      backend: sale.handle,
      tokens: MemoryTokenStore(signedIn ? 'jwt' : null),
      clock: clock,
      overrides: [
        cardPaymentLauncherProvider.overrideWithValue((context, url, id) async {
          launched.add(url);
          return await cardPage?.future ?? cardReturns;
        }),
      ],
    );
    container.read(routerProvider).go(location);
    await tester.pumpAndSettle();
    return container;
  }

  Future<void> fillFriend(WidgetTester tester, {String name = 'Sara Benali', String phone = ''}) async {
    await tester.enterText(find.byKey(const Key('holder.1.name')), name);
    await tester.enterText(find.byKey(const Key('holder.1.phone')), phone);
  }

  Future<void> consent(WidgetTester tester) async {
    await tester.dragUntilVisible(
      find.byKey(const Key('checkout.consent')),
      find.byType(ListView).last,
      const Offset(0, -200),
    );
    await tester.tap(find.byKey(const Key('checkout.consent')));
    await tester.pump();
  }

  Future<void> pay(WidgetTester tester) async {
    await tester.ensureVisible(find.byKey(const Key('checkout.pay')));
    await tester.tap(find.byKey(const Key('checkout.pay')));
    await tester.pump();
    await tester.pump();
  }

  Map<String, Object?> body(http.Request r) => jsonDecode(r.body) as Map<String, Object?>;

  const checkout = '/events/1/checkout?price=pc-1-0&qty=2';

  testWidgets('buying needs a session: screen 3 goes to login', (tester) async {
    await open(tester, '/events/1/zones', signedIn: false);
    expect(find.byType(LoginScreen), findsOneWidget);
  });

  testWidgets('screen 3: the stepper stops at min(per_account_limit, remaining), then Continuer opens screen 4', (
    tester,
  ) async {
    sale.remaining = 3;
    await open(tester, '/events/1/zones');
    await tester.tap(find.text('Tribune Nord'));
    await tester.pumpAndSettle();
    expect(find.text('Billets · max 3'), findsOneWidget);
    for (var i = 0; i < 5; i++) {
      await tester.tap(find.byTooltip('Un billet de plus'));
      await tester.pump();
    }
    expect(tester.widget<Text>(find.byKey(const Key('zones.qty'))).data, '3');
    expect(tester.widget<Text>(find.byKey(const Key('zones.total'))).data, formatDh(15000));

    await tester.tap(find.byKey(const Key('zones.continue')));
    await tester.pumpAndSettle();
    expect(find.text('Billets nominatifs'.toUpperCase()), findsOneWidget);
    // 3 places = 3 holder rows.
    await tester.dragUntilVisible(
      find.byKey(const Key('holder.2.name')),
      find.byType(ListView).last,
      const Offset(0, -200),
    );
    expect(find.byKey(const Key('holder.3.name'), skipOffstage: false), findsNothing);
  });

  testWidgets('the order nests holders per item; row 1 = me without phone; friend phone normalised', (tester) async {
    await open(tester, checkout);
    expect(find.text('Yazid Aqel'), findsOneWidget); // row 1 prefilled from /fan/me
    await fillFriend(tester, name: 'Sara  Benali', phone: '06 12 34 56 78');
    await consent(tester);
    await pay(tester);

    final order = body(sale.calls('POST', '/fan/orders').single);
    expect(order, {
      'event_id': 1,
      'channel': 'APP',
      'items': [
        {
          'price_category_id': 'pc-1-0',
          'qty': 2,
          'holders': [
            {'name': 'Yazid Aqel'},
            {'name': 'Sara Benali', 'phone': '+212612345678'},
          ],
        },
      ],
    });
    expect(sale.calls('PATCH', '/fan/me'), isEmpty, reason: 'row 1 unchanged');
    expect(body(sale.calls('POST', '/fan/orders/$orderId/pay').single), {'method': 'CARD', 'channel': 'APP'});
  });

  testWidgets('an edited row 1 is saved on /fan/me before the order', (tester) async {
    sale.displayName = null;
    await open(tester, checkout);
    await tester.enterText(find.byKey(const Key('holder.0.name')), 'Yazid Aqel');
    await fillFriend(tester);
    await pay(tester);
    expect(body(sale.calls('PATCH', '/fan/me').single), {'display_name': 'Yazid Aqel'});
    final patchAt = sale.requests.indexOf(sale.calls('PATCH', '/fan/me').single);
    expect(patchAt, lessThan(sale.requests.indexOf(sale.calls('POST', '/fan/orders').single)));
  });

  testWidgets('invalid names and phones stay on the form; nothing is sent', (tester) async {
    await open(tester, checkout);
    await fillFriend(tester, name: 'Sara 2', phone: '+33 6 12 34 56 78');
    await pay(tester);
    expect(
      find.text('Prénom et nom en lettres latines ou arabes, sans mélanger les deux, de 2 à 60 caractères.'),
      findsOneWidget,
    );
    expect(find.text('Numéro marocain invalide. Essaie 06 12 34 56 78.'), findsOneWidget);
    expect(sale.calls('POST', '/fan/orders'), isEmpty);
  });

  testWidgets('a 422 on a holder field marks that row', (tester) async {
    sale.orderAnswers.add(
      () => sale.answer({
        'code': 'VALIDATION_FAILED',
        'message': 'x',
        'details': {
          'fields': {'items[0].holders[1].name': 'bad'},
        },
      }, 422),
    );
    await open(tester, checkout);
    await fillFriend(tester);
    await pay(tester);
    await tester.pumpAndSettle();
    expect(
      find.text('Prénom et nom en lettres latines ou arabes, sans mélanger les deux, de 2 à 60 caractères.'),
      findsOneWidget,
    );
  });

  testWidgets('SALE_BUSY: shows the wait, retries the same request after retry_after_s (fake clock)', (tester) async {
    sale.orderAnswers.add(
      () => sale.answer({
        'code': 'SALE_BUSY',
        'message': 'x',
        'details': {'retry_after_s': 7},
      }, 429),
    );
    final container = await open(tester, checkout);
    await fillFriend(tester);
    await pay(tester);
    expect(find.text('Beaucoup de monde en ce moment · nouvel essai dans 7 s'), findsOneWidget);
    expect(find.textContaining('position'), findsNothing);
    // A second submit while waiting is ignored (the button is off; the controller checks too).
    const args = (eventId: 1, priceCategoryId: 'pc-1-0', qty: 2);
    expect(
      await container
          .read(checkoutControllerProvider(args).notifier)
          .submit(['Yazid Aqel', 'Sara Benali'], ['', ''], friendsConsent: false),
      isNull,
    );

    await tester.pump(const Duration(seconds: 6));
    expect(find.text('Beaucoup de monde en ce moment · nouvel essai dans 1 s'), findsOneWidget);
    expect(sale.calls('POST', '/fan/orders'), hasLength(1));

    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    final posts = sale.calls('POST', '/fan/orders').toList();
    expect(posts, hasLength(2));
    expect(posts[1].body, posts[0].body);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('checkout.busy')), findsNothing);
    expect(launched, hasLength(1));
  });

  testWidgets('PER_ACCOUNT_LIMIT and SOLD_OUT get their copy', (tester) async {
    sale.orderAnswers.add(
      () => sale.answer({
        'code': 'PER_ACCOUNT_LIMIT',
        'message': 'x',
        'details': {'limit': 4, 'held': 3},
      }, 409),
    );
    await open(tester, checkout);
    await fillFriend(tester);
    await pay(tester);
    await tester.pumpAndSettle();
    expect(find.text('Limite atteinte : 4 billets par compte pour cet événement, tu en as déjà 3.'), findsOneWidget);
  });

  testWidgets('the hold countdown runs on server time; expiring during /pay: modal, never the card page', (
    tester,
  ) async {
    sale =
        Sale(clock, serverSkew: const Duration(minutes: -2)) // device 2 min ahead
          ..payGate = Completer();
    await open(tester, checkout);
    await fillFriend(tester);
    await pay(tester);
    await tester.pump();
    expect(find.text('Places réservées · 08:00'), findsOneWidget);

    // A device clock change does not move the countdown (monotonic stopwatch).
    clock.advance(const Duration(hours: 1));
    await tester.pump(const Duration(minutes: 7, seconds: 59));
    expect(find.text('Places réservées · 00:01'), findsOneWidget);
    expect(find.text('Réservation expirée'), findsNothing);

    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(find.text('Réservation expirée'), findsOneWidget);

    sale.payGate!.complete(); // /pay answers after the end of the hold
    await tester.pumpAndSettle();
    expect(launched, isEmpty);
    await tester.tap(find.text('Choisir à nouveau'));
    await tester.pumpAndSettle();
    expect(find.text('Touche une zone pour choisir tes places.'), findsOneWidget);
  });

  testWidgets('the hold runs out while the card page is open: no modal, the order screen when it closes', (
    tester,
  ) async {
    sale
      ..statuses = ['RESERVED']
      ..paymentStatus = 'PENDING';
    cardPage = Completer();
    await open(tester, checkout);
    await fillFriend(tester);
    await pay(tester);
    await tester.pump();
    expect(launched, hasLength(1));

    await tester.pump(const Duration(minutes: 8, seconds: 1));
    await tester.pump();
    expect(find.text('Réservation expirée'), findsNothing);
    expect(find.byType(OrderScreen), findsNothing, reason: 'the card page is not taken away mid-3DS');

    cardPage!.complete(false); // the fan closes it
    await tester.pump();
    await tester.pump();
    expect(find.text('Réservation expirée'), findsNothing);
    expect(find.byType(OrderScreen), findsOneWidget);
    expect(find.byKey(const Key('order.pending')), findsOneWidget);
  });

  testWidgets('a redirect_url outside our API: an error, no card page, the card is not marked opened', (tester) async {
    sale.redirectUrl = 'https://evil.example/pay';
    await open(tester, checkout);
    await fillFriend(tester);
    await pay(tester);
    await tester.pumpAndSettle();
    expect(launched, isEmpty);
    expect(find.byKey(const Key('checkout.error')), findsOneWidget);
    expect(find.text("Voir l'état du paiement"), findsNothing);
  });

  testWidgets('happy path: card page returns, the order screen polls to PAID and slides in the success', (
    tester,
  ) async {
    final container = await open(tester, checkout);
    await fillFriend(tester, phone: '0612345678');
    await consent(tester);
    await pay(tester);
    await tester.pump();
    expect(launched, ['http://10.0.2.2:8080/v1/payments/card/p1/redirect']);

    await tester.pump();
    expect(find.byType(OrderScreen), findsOneWidget);
    expect(find.byKey(const Key('order.pending')), findsOneWidget); // first read: RESERVED

    await tester.pump(const Duration(seconds: 2)); // poll
    await tester.pumpAndSettle();
    expect(find.text("C'est validé !"), findsOneWidget);
    expect(sale.calls('GET', '/fan/orders/$orderId'), hasLength(2));

    await tester.tap(find.text('Voir mes billets'));
    await tester.pumpAndSettle();
    expect(container.read(routerProvider).routerDelegate.currentConfiguration.uri.path, '/tickets');
  });

  testWidgets('the jomhor-fan://orders/{id} deep link opens the order screen (Custom Tabs fallback)', (tester) async {
    sale.statuses = ['PAID'];
    final container = await open(tester, '/');
    container.read(routerProvider).go('jomhor-fan://orders/$orderId');
    await tester.pumpAndSettle();
    expect(find.text("C'est validé !"), findsOneWidget);
  });

  for (final link in [
    'jomhor-fan://orders/..%2Fevents%2F1%2Fcheckout%3Fprice%3Dpc-1-0%26qty%3D4',
    'jomhor-fan://orders/..%252Fevents%252F1%252Fcheckout%253Fprice%253Dpc-1-0%2526qty%253D4',
    '/orders/..%2Fevents%2F1%2Fcheckout%3Fprice%3Dpc-1-0%26qty%3D4',
    '/orders/..%252Fevents%252F1%252Fcheckout',
  ]) {
    testWidgets('a deep link whose order id is not a lowercase UUID goes home: $link', (tester) async {
      final container = await open(tester, '/');
      container.read(routerProvider).go(link);
      await tester.pumpAndSettle();
      expect(container.read(routerProvider).routerDelegate.currentConfiguration.uri.path, '/');
      expect(find.byType(OrderScreen), findsNothing);
      expect(sale.requests.where((r) => r.url.path.startsWith('/v1/fan/orders/')), isEmpty);
    });
  }

  testWidgets('Q21: a friend phone needs the WhatsApp confirmation before anything is sent', (tester) async {
    await open(tester, checkout);
    expect(find.byKey(const Key('checkout.consent')), findsNothing); // no friend phone yet
    await fillFriend(tester, phone: '0612345678');
    await tester.pump();
    await tester.dragUntilVisible(
      find.byKey(const Key('checkout.consent')),
      find.byType(ListView).last,
      const Offset(0, -200),
    );
    expect(find.text('Je confirme que ces personnes acceptent de recevoir leur billet sur WhatsApp'), findsOneWidget);
    await pay(tester);
    expect(find.text("Coche la case pour confirmer que tes amis sont d'accord."), findsOneWidget);
    expect(sale.calls('POST', '/fan/orders'), isEmpty);

    await consent(tester);
    expect(find.text("Billets envoyés dans l'app et sur WhatsApp"), findsOneWidget);
    await pay(tester);
    expect(sale.calls('POST', '/fan/orders'), hasLength(1));
  });

  testWidgets('leaving screen 4 while the order is in flight: no /pay, no card page', (tester) async {
    final answer = Completer<http.Response>();
    sale.orderAnswers.add(() => answer.future);
    final container = await open(tester, checkout);
    await fillFriend(tester);
    await pay(tester);
    expect(sale.calls('POST', '/fan/orders'), hasLength(1));

    container.read(routerProvider).go('/'); // Cancel / back
    await tester.pumpAndSettle();
    answer.complete(sale.answer(sale.order('RESERVED'), 201));
    await tester.pumpAndSettle();
    expect(sale.calls('POST', '/fan/orders/$orderId/pay'), isEmpty);
    expect(launched, isEmpty);
  });

  testWidgets('after the card page closes, screen 4 never pays twice: it sends to the order screen', (tester) async {
    cardReturns = false;
    sale
      ..statuses = ['RESERVED']
      ..paymentStatus = 'PENDING';
    await open(tester, checkout);
    await fillFriend(tester);
    await pay(tester);
    await tester.pump();
    expect(launched, hasLength(1));
    expect(find.text("Voir l'état du paiement"), findsOneWidget);

    await tester.tap(find.byKey(const Key('checkout.pay')));
    await tester.pump();
    await tester.pump();
    expect(find.byType(OrderScreen), findsOneWidget);
    expect(sale.calls('POST', '/fan/orders/$orderId/pay'), hasLength(1));
  });

  group('order screen: another card attempt only when it cannot double-charge', () {
    Future<void> openOrder(WidgetTester tester) async {
      sale.statuses = ['RESERVED'];
      final container = await pumpJomhor(
        tester,
        backend: sale.handle,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        overrides: [
          cardPaymentLauncherProvider.overrideWithValue((context, url, id) async {
            launched.add(url);
            return true;
          }),
        ],
      );
      container.read(routerProvider).go('/orders/$orderId');
      await tester.pump();
      await tester.pump();
    }

    testWidgets('PENDING: never a button, even after 60 s (the first payment may still succeed)', (tester) async {
      sale.paymentStatus = 'PENDING';
      await openOrder(tester);
      expect(find.byKey(const Key('order.payAgain')), findsNothing);
      await tester.pump(const Duration(seconds: 61));
      expect(find.byKey(const Key('order.payAgain')), findsNothing);
      expect(find.textContaining('Ne paie pas une deuxième fois'), findsOneWidget);
    });

    testWidgets('SUCCEEDED (not PAID yet): never a button', (tester) async {
      sale.paymentStatus = 'SUCCEEDED';
      await openOrder(tester);
      await tester.pump(const Duration(seconds: 61));
      expect(find.byKey(const Key('order.payAgain')), findsNothing);
    });

    testWidgets('no attempt yet (null): pay right away, and the copy does not say it failed', (tester) async {
      sale.paymentStatus = null;
      await openOrder(tester);
      expect(find.byKey(const Key('order.payAgain')), findsOneWidget);
      expect(find.text('Payer ${formatDh(10000)}'), findsOneWidget);
      expect(find.textContaining("n'a pas abouti"), findsNothing);
      expect(find.textContaining('Aucun paiement'), findsOneWidget);
    });

    testWidgets('FAILED: the button right away', (tester) async {
      sale.paymentStatus = 'FAILED';
      await openOrder(tester);
      expect(find.byKey(const Key('order.payAgain')), findsOneWidget);
      expect(find.textContaining("n'a pas abouti"), findsOneWidget);
      await tester.tap(find.byKey(const Key('order.payAgain')));
      await tester.pump();
      await tester.pump();
      expect(sale.calls('POST', '/fan/orders/$orderId/pay'), hasLength(1));
      expect(launched, hasLength(1));
    });

    testWidgets('FAILED but the hold is over on server time: no button, expired with a new order', (tester) async {
      sale
        ..paymentStatus = 'FAILED'
        ..hold = const Duration(seconds: -1);
      await openOrder(tester);
      expect(find.byKey(const Key('order.payAgain')), findsNothing);
      expect(find.text('Cette réservation a expiré.'), findsOneWidget);
      await tester.tap(find.text('Choisir à nouveau'));
      await tester.pumpAndSettle();
      expect(find.text('Touche une zone pour choisir tes places.'), findsOneWidget);
    });

    testWidgets('a double tap sends one /pay; the button is off while it is in flight', (tester) async {
      sale
        ..paymentStatus = 'FAILED'
        ..payGate = Completer();
      await openOrder(tester);
      await tester.tap(find.byKey(const Key('order.payAgain')));
      await tester.tap(find.byKey(const Key('order.payAgain')));
      await tester.pump();
      await tester.pump();
      expect(sale.calls('POST', '/fan/orders/$orderId/pay'), hasLength(1));
      expect(tester.widget<OutlinedButton>(find.byKey(const Key('order.payAgain'))).onPressed, isNull);

      sale.payGate!.complete();
      await tester.pump();
      await tester.pump();
      expect(launched, hasLength(1));
      expect(sale.calls('POST', '/fan/orders/$orderId/pay'), hasLength(1));
    });

    testWidgets('payment_status not sent yet (before BE-12): unknown, button only after 60 s', (tester) async {
      await openOrder(tester);
      expect(find.byKey(const Key('order.payAgain')), findsNothing);
      await tester.pump(const Duration(seconds: 59));
      expect(find.byKey(const Key('order.payAgain')), findsNothing);
      await tester.pump(const Duration(seconds: 2));
      expect(find.byKey(const Key('order.payAgain')), findsOneWidget);
      expect(find.textContaining('seulement si ta carte a été refusée'), findsOneWidget);
    });

    testWidgets('not reported: the 60 s wait restarts with each new /pay', (tester) async {
      await openOrder(tester);
      await tester.pump(const Duration(seconds: 61));
      await tester.tap(find.byKey(const Key('order.payAgain')));
      await tester.pump();
      await tester.pump();
      expect(launched, hasLength(1));
      expect(find.byKey(const Key('order.payAgain')), findsNothing);
      await tester.pump(const Duration(seconds: 59));
      expect(find.byKey(const Key('order.payAgain')), findsNothing);
      await tester.pump(const Duration(seconds: 2));
      expect(find.byKey(const Key('order.payAgain')), findsOneWidget);
    });
  });
}
