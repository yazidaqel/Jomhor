import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/ui/bidi.dart';

import 'helpers.dart';
import 'tickets_test.dart' show otherId, ticketId, ticketJson;

const productId = 'b1000000-0000-4000-8000-000000000001';
const passId = 'c1000000-0000-4000-8000-000000000001';
const productOrderId = 'd1000000-0000-4000-8000-000000000001';
const cardPage = 'http://10.0.2.2:8080/v1/payments/card/p1/redirect';

/// A contract-first answer of test/fixtures/passes (each carries its `source` line), as `(status, body)`; the body
/// is a fresh copy a test may change.
({int status, Map<String, Object?> body}) passAnswer(String name) => realAnswer(name, dir: 'passes');

/// A fake backend for one PASS product (Tribune Est, two announced matches), its purchase order and the caller's
/// passes and tickets, shaped as docs/04-api.md §Products, §Passes.
class PassSale {
  new(this.clock);

  final TestClock clock;
  final requests = <http.Request>[];

  /// `GET /fan/products/{id}` body; null = 404 (off sale).
  Map<String, Object?>? product = passAnswer('product_200_pass').body;

  /// Queued answers of `POST /fan/product-orders` (default: the 201).
  final productOrders = <String>[];

  /// The purchase order's status and latest attempt.
  String orderStatus = 'RESERVED';
  String? paymentStatus;
  String? paymentMethod;

  /// A whole `GET /fan/orders/{id}` body to serve instead of the fixture.
  Map<String, Object?>? productOrderBody;

  /// `GET /fan/passes` fixture; null = no pass.
  String? passes;

  /// `GET /fan/tickets` rows.
  List<Map<String, Object?>> tickets = [];

  /// `GET /fan/orders` fixture; null = no order.
  String? orders;

  http.Response answer(Object? body, [int status = 200]) => http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    status,
    headers: {'content-type': 'application/json', 'date': HttpDate.format(clock.now)},
  );

  http.Response fixture(String name) {
    final (:status, :body) = passAnswer(name);
    return answer(body, status);
  }

  Map<String, Object?> order() => {
    ...passAnswer(orderStatus == 'PAID' ? 'order_200_paid' : 'order_200_reserved').body,
    'status': orderStatus,
    'payment_status': paymentStatus,
    'payment_method': paymentMethod,
    'reserved_until': clock.now.add(const Duration(minutes: 8)).toIso8601String(),
    if (orderStatus == 'AWAITING_CASH') ...{
      'payment_method': 'CASH_AGENCY',
      'payment_status': 'PENDING',
      'reserved_until': clock.now.add(const Duration(hours: 24)).toIso8601String(),
      'cash': {
        'pay_code': '4F7K29',
        'amount_mad': 150000,
        'points': <Object?>[],
        'networks': ['Wafacash'],
      },
    },
  };

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    return switch ((r.method, r.url.path)) {
      ('GET', '/v1/fan/events') => answer(<Object?>[]),
      ('GET', '/v1/fan/products/$productId') => product == null ? apiError(404, 'NOT_FOUND') : answer(product),
      ('GET', '/v1/fan/me') => answer({'id': 'u1', 'phone': '+212600000001', 'display_name': 'Yazid Aqel'}),
      ('POST', '/v1/fan/product-orders') => fixture(
        productOrders.isEmpty ? 'product_order_201' : productOrders.removeAt(0),
      ),
      ('GET', '/v1/fan/orders/$productOrderId') => answer(productOrderBody ?? order()),
      ('POST', '/v1/fan/orders/$productOrderId/pay') => () {
        paymentStatus = 'PENDING';
        return answer({'payment_id': 'p1', 'method': 'CARD', 'redirect_url': cardPage});
      }(),
      ('GET', '/v1/fan/orders') => orders == null ? answer({'orders': <Object?>[]}) : fixture(orders!),
      ('GET', '/v1/fan/passes') => passes == null ? answer({'passes': <Object?>[]}) : fixture(passes!),
      ('GET', '/v1/fan/tickets') => answer({'server_time': clock.now.toIso8601String(), 'tickets': tickets}),
      _ => apiError(404, 'NOT_FOUND'),
    };
  }

  List<http.Request> calls(String method, String path) => [
    ...requests.where((r) => r.method == method && r.url.path == '/v1$path'),
  ];
}

/// A pass ticket of match 7 (`pass_id` set), as `GET /fan/tickets` lists it.
Map<String, Object?> passTicketJson({String id = ticketId}) => {...ticketJson(id: id), 'pass_id': passId};

void main() {
  late TestClock clock;
  late PassSale sale;
  final l = lookupAppLocalizations(const Locale('fr'));

  setUp(() {
    clock = TestClock(DateTime.utc(2027, 2, 20, 12));
    sale = PassSale(clock);
  });

  Future<ProviderContainer> start(
    WidgetTester tester,
    String location, {
    bool signedIn = true,
    Future<bool> Function()? card,
  }) async {
    tester.view
      ..physicalSize = const Size(800, 2400)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final c = await pumpJomhor(
      tester,
      backend: sale.handle,
      tokens: MemoryTokenStore(signedIn ? 'jwt' : null),
      clock: clock,
      overrides: [
        cardPaymentLauncherProvider.overrideWithValue((context, url, id) async => await card?.call() ?? false),
      ],
    );
    if (location != '/') {
      c.read(routerProvider).go(location);
      await tester.pumpAndSettle();
    }
    return c;
  }

  String location(ProviderContainer c) => c.read(routerProvider).state.uri.toString();

  Future<void> tap(WidgetTester tester, String key) async {
    await tester.ensureVisible(find.byKey(Key(key)));
    await tester.tap(find.byKey(Key(key)));
    await tester.pumpAndSettle();
  }

  String textOf(WidgetTester tester, String key) => tester
      .widget<Text>(find.descendant(of: find.byKey(Key(key)), matching: find.byType(Text), matchRoot: true).first)
      .data!;

  Map<String, Object?> sent() =>
      jsonDecode(sale.calls('POST', '/fan/product-orders').last.body) as Map<String, Object?>;

  group('screen 13 buy', () {
    testWidgets('zone, venue, the announced events, the crest; public read (no token)', (tester) async {
      await start(tester, '/products/$productId');
      expect(textOf(tester, 'buy.name'), 'Abonnement Tribune Est');
      expect(find.byKey(const Key('buy.crest')), findsOneWidget);
      expect(textOf(tester, 'buy.zone'), 'Tribune Est');
      expect(textOf(tester, 'buy.venue'), 'Grand Stade · Casablanca');
      expect(textOf(tester, 'buy.count'), '2 matchs annoncés');
      expect(find.text(l.passOneTicketEach), findsOneWidget);
      expect(find.byKey(const Key('buy.event.7')), findsOneWidget);
      expect(find.byKey(const Key('buy.event.8')), findsOneWidget);
      expect(textOf(tester, 'buy.cta'), l.passBuy(l.dh(150000)));
      expect(sale.calls('GET', '/fan/products/$productId').single.headers.containsKey('authorization'), isFalse);
    });

    testWidgets("amounts as the server sent them: the CTA says the plan's first amount", (tester) async {
      sale.product = {
        ...sale.product!,
        'plans': [
          {
            'installments': 1,
            'amounts_mad': [149999],
          },
        ],
      };
      await start(tester, '/products/$productId');
      expect(textOf(tester, 'buy.cta'), l.passBuy(l.dh(149999)));
    });

    testWidgets('sold_out → "Complet", the CTA off', (tester) async {
      sale.product = {...sale.product!, 'remaining': 0, 'sold_out': true};
      await start(tester, '/products/$productId');
      expect(textOf(tester, 'buy.cta'), l.soldOut);
      expect(tester.widget<FilledButton>(find.byKey(const Key('buy.cta'))).onPressed, isNull);
      expect(find.byKey(const Key('buy.soldOut')), findsOneWidget);
    });

    testWidgets('holder name prefilled from /fan/me; a refused name is said before any POST', (tester) async {
      await start(tester, '/products/$productId');
      expect(tester.widget<TextField>(find.byKey(const Key('buy.holder'))).controller!.text, 'Yazid Aqel');

      await tester.enterText(find.byKey(const Key('buy.holder')), 'Yazid يزيد');
      await tap(tester, 'buy.cta');
      expect(find.text(l.holderNameError), findsOneWidget);
      expect(sale.calls('POST', '/fan/product-orders'), isEmpty);
    });

    testWidgets('buy → screen 4 product variant → card → PAID → screen 13 owned', (tester) async {
      final c = await start(
        tester,
        '/products/$productId',
        card: () async {
          sale
            ..orderStatus = 'PAID'
            ..paymentStatus = 'SUCCEEDED'
            ..passes = 'passes_200_active';
          return true;
        },
      );
      await tester.enterText(find.byKey(const Key('buy.holder')), '  Yazid   Aqel ');
      await tap(tester, 'buy.cta');
      expect(sent(), {'product_id': productId, 'channel': 'APP', 'holder_name': 'Yazid Aqel'});
      expect(location(c), '/orders/$productOrderId');

      // Screen 4, product variant: the product's name, the order's total, the holder; no zone, quantity or friends.
      expect(find.byKey(const Key('product.summary')), findsOneWidget);
      expect(find.text('Abonnement Tribune Est'), findsOneWidget);
      expect(find.text(l.dh(150000)), findsWidgets);
      expect(textOf(tester, 'product.holder'), l.productHolder('Yazid A.'));
      expect(find.byKey(const Key('holder.1.name')), findsNothing);
      expect(find.byKey(const Key('method.CARD')), findsOneWidget);
      expect(find.byKey(const Key('method.CASH_AGENCY')), findsOneWidget);
      expect(textOf(tester, 'product.caption'), l.productCaptionPass);
      expect(textOf(tester, 'product.pay'), l.payCta(l.dh(150000)));

      await tap(tester, 'product.pay');
      expect(jsonDecode(sale.calls('POST', '/fan/orders/$productOrderId/pay').single.body), {
        'method': 'CARD',
        'channel': 'APP',
      });
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('order.paid')), findsOneWidget);
      expect(find.text(l.orderPaidPassBody), findsOneWidget);

      await tap(tester, 'order.seePass');
      expect(location(c), '/passes/$passId');
      expect(find.byKey(const Key('pass.card')), findsOneWidget);
      expect(textOf(tester, 'pass.holder'), 'Yazid A.');
    });

    testWidgets('PASS_HELD → the owned view of that pass', (tester) async {
      sale
        ..productOrders.add('product_order_409_pass_held')
        ..passes = 'passes_200_active';
      final c = await start(tester, '/products/$productId');
      await tap(tester, 'buy.cta');
      expect(location(c), '/passes/$passId');
      expect(find.byKey(const Key('pass.card')), findsOneWidget);
    });

    testWidgets('PRODUCT_NOT_ON_SALE: its copy under the CTA', (tester) async {
      sale.productOrders.add('product_order_409_not_on_sale');
      await start(tester, '/products/$productId');
      await tap(tester, 'buy.cta');
      expect(textOf(tester, 'buy.error'), l.errorProductNotOnSale);
      expect(tester.widget<FilledButton>(find.byKey(const Key('buy.cta'))).onPressed, isNull);
    });

    testWidgets('SOLD_OUT with product_id → "Complet", the product read again', (tester) async {
      sale.productOrders.add('product_order_409_sold_out');
      await start(tester, '/products/$productId');
      final reads = sale.calls('GET', '/fan/products/$productId').length;
      await tap(tester, 'buy.cta');
      expect(textOf(tester, 'buy.cta'), l.soldOut);
      expect(sale.calls('GET', '/fan/products/$productId').length, reads + 1);
    });

    testWidgets('422 on holder_name → the name field says it', (tester) async {
      sale.productOrders.add('product_order_422_holder_name');
      await start(tester, '/products/$productId');
      await tap(tester, 'buy.cta');
      expect(find.text(l.holderNameError), findsOneWidget);
    });

    testWidgets('signed out: "Acheter" asks for the login, which pops back to the same screen, name prefilled', (
      tester,
    ) async {
      final c = await start(tester, '/products/$productId', signedIn: false);
      expect(sale.calls('GET', '/fan/me'), isEmpty);
      expect(tester.widget<TextField>(find.byKey(const Key('buy.holder'))).controller!.text, isEmpty);
      await tap(tester, 'buy.cta');
      expect(location(c), '/login');
      expect(sale.calls('POST', '/fan/product-orders'), isEmpty);

      await c.read(sessionProvider.notifier).signIn('jwt');
      await tester.pumpAndSettle();
      expect(location(c), '/products/$productId');
      expect(find.byKey(const Key('buy.cta')), findsOneWidget); // One screen 13, not a second one pushed.
      expect(tester.widget<TextField>(find.byKey(const Key('buy.holder'))).controller!.text, 'Yazid Aqel');
      await tap(tester, 'buy.cta');
      expect(location(c), '/orders/$productOrderId');
      c.read(routerProvider).pop();
      await tester.pumpAndSettle();
      expect(location(c), '/products/$productId');
    });

    testWidgets('a MEMBERSHIP product is titled as the member card, not "Abonnement"', (tester) async {
      sale.product = {
        ...sale.product!,
        'kind': 'MEMBERSHIP',
        'name': 'Carte membre 2026-27',
        'remaining': null,
        'pass': null,
        'valid_until': '2027-06-30T22:59:59Z',
      };
      await start(tester, '/products/$productId');
      expect(find.widgetWithText(AppBar, l.clubMemberCard), findsOneWidget);
      expect(find.widgetWithText(AppBar, l.passChip), findsNothing);
    });

    testWidgets('a malformed id never reaches the API', (tester) async {
      final c = await start(tester, '/products/..%2Fx');
      expect(location(c), '/');
      expect(sale.requests.where((r) => r.url.path.startsWith('/v1/fan/products')), isEmpty);
    });
  });

  group('screen 13 owned', () {
    testWidgets('the pass card, the season with its tickets as they arrive ("Bientôt" before)', (tester) async {
      sale
        ..passes = 'passes_200_active'
        ..tickets = [passTicketJson()];
      final c = await start(tester, '/passes/$passId');
      expect(textOf(tester, 'pass.title'), 'Abonnement Tribune Est');
      expect(textOf(tester, 'pass.zone'), 'TRIBUNE EST');
      expect(find.byKey(const Key('pass.crest')), findsOneWidget);
      // "Abonnement" once: the screen title (the product's name), not again on the card.
      expect(
        find.descendant(of: find.byKey(const Key('pass.card')), matching: find.textContaining('ABONNEMENT')),
        findsNothing,
      );
      // The season comes with the pass: no product read.
      expect(find.byKey(const Key('pass.ticket.7')), findsOneWidget);
      expect(find.byKey(const Key('pass.soon.8')), findsOneWidget);
      expect(find.byKey(const Key('pass.resume')), findsNothing);
      expect(sale.calls('GET', '/fan/products/$productId'), isEmpty);

      await tap(tester, 'pass.ticket.7');
      expect(location(c), '/tickets/$ticketId');
    });

    testWidgets('PENDING: "Paiement en attente" and "Reprendre le paiement" opens the order', (tester) async {
      sale.passes = 'passes_200_pending';
      final c = await start(tester, '/passes/$passId');
      expect(find.byKey(const Key('pass.pending')), findsOneWidget);
      await tap(tester, 'pass.resume');
      expect(location(c), '/orders/$productOrderId');
      expect(find.byKey(const Key('product.pay')), findsOneWidget);
    });

    testWidgets('PENDING: every event "Bientôt" (no ticket_id yet)', (tester) async {
      sale.passes = 'passes_200_pending';
      await start(tester, '/passes/$passId');
      expect(find.byKey(const Key('pass.soon.7')), findsOneWidget);
      expect(find.byKey(const Key('pass.soon.8')), findsOneWidget);
      expect(find.byKey(const Key('pass.ticket.7')), findsNothing);
    });

    testWidgets('signed out: the login first', (tester) async {
      final c = await start(tester, '/passes/$passId', signedIn: false);
      expect(location(c), startsWith('/login'));
    });
  });

  group('screen 4 product variant', () {
    testWidgets('the hold, the methods and cash_until from the order (product off sale: 404)', (tester) async {
      sale.product = null;
      await start(tester, '/orders/$productOrderId');
      expect(textOf(tester, 'product.hold'), l.productHoldLeft(8));
      expect(find.byKey(const Key('method.CARD')), findsOneWidget);
      expect(find.byKey(const Key('method.WALLET')), findsOneWidget);
      expect(find.byKey(const Key('method.CASH_AGENCY')), findsOneWidget);
      expect(textOf(tester, 'product.pay'), l.payCta(l.dh(150000)));
    });

    testWidgets('past cash_until: the cash row off, "Trop tard pour le cash"', (tester) async {
      final order = passAnswer('order_200_reserved').body;
      final product = order['product']! as Map<String, Object?>;
      sale.productOrderBody = {
        ...order,
        'product': {
          ...product,
          'payment_methods': ['CARD', 'WALLET'],
        },
      };
      await start(tester, '/orders/$productOrderId');
      expect(textOf(tester, 'method.CASH_AGENCY.hint'), l.methodCashTooLate);
    });

    testWidgets('a cash code expired: "Réessayer" opens the product again', (tester) async {
      sale
        ..orderStatus = 'EXPIRED'
        ..paymentMethod = 'CASH_AGENCY'
        ..paymentStatus = 'FAILED';
      final c = await start(tester, '/orders/$productOrderId');
      expect(find.byKey(const Key('cash.expired')), findsOneWidget);
      await tap(tester, 'cash.retryProduct');
      expect(location(c), '/products/$productId');
    });
  });

  test('a MEMBERSHIP order parses: no event, no zone, no category, no pass', () {
    final order = FanOrder.fromJson({
      'id': productOrderId,
      'event_id': null,
      'channel': 'APP',
      'status': 'RESERVED',
      'payment_status': null,
      'total_mad': 20000,
      'reserved_until': '2027-02-20T12:08:00Z',
      'paid_at': null,
      'items': [
        {
          'product_id': 'b1000000-0000-4000-8000-000000000002',
          'price_category_id': null,
          'zone_id': null,
          'zone_name': null,
          'name': 'Carte membre 2026-27',
          'qty': 1,
          'unit_price_mad': 20000,
          'holders': [
            {'label': 'Yazid A.'},
          ],
        },
      ],
      'ticket_ids': <Object?>[],
      'payment_method': null,
      'cash': null,
      'group': null,
      'product': {
        'id': 'b1000000-0000-4000-8000-000000000002',
        'kind': 'MEMBERSHIP',
        'name': 'Carte membre 2026-27',
        'organizer': {'slug': 'atlas-fc', 'name': 'Atlas FC', 'crest_url': null},
        'timezone': 'Africa/Casablanca',
        'payment_methods': ['CARD', 'CASH_AGENCY'],
        'cash_until': '2027-06-01T21:00:00Z',
      },
      'pass_id': null,
    });
    expect(order.eventId, isNull);
    expect(order.passId, isNull);
    expect(order.product!.isPass, isFalse);
    expect(order.product!.paymentMethods, [PayMethod.card, PayMethod.cashAgency]);
    expect(order.product!.cashUntil, DateTime.utc(2027, 6, 1, 21));
    expect(order.items.single.zoneId, isNull);
    expect(order.items.single.priceCategoryId, isNull);
    expect(order.items.single.holderLabels, ['Yazid A.']);
  });

  group('Billets and orders', () {
    testWidgets('"Mes abonnements" above the tickets; the "Abonnement" chip on pass tickets and screen 5', (
      tester,
    ) async {
      sale
        ..passes = 'passes_200_active'
        ..tickets = [passTicketJson(), ticketJson(id: otherId)];
      final c = await start(tester, '/tickets');
      expect(find.text(l.passesTitle), findsOneWidget);
      expect(
        tester.getTopLeft(find.byKey(const Key('passes.row.$passId'))).dy,
        lessThan(tester.getTopLeft(find.text(l.ticketsUpcoming)).dy),
      );
      expect(textOf(tester, 'passes.status.$passId'), l.passActive);
      expect(find.byKey(const Key('ticket.passChip.$ticketId')), findsOneWidget);
      expect(find.byKey(const Key('ticket.passChip.$otherId')), findsNothing);

      c.read(routerProvider).go('/tickets/$ticketId');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('ticket.band.badge')), findsWidgets);

      c.read(routerProvider).go('/tickets');
      await tester.pumpAndSettle();
      await tap(tester, 'passes.row.$passId');
      expect(location(c), '/passes/$passId');
    });

    testWidgets('no pass: no section, the tickets as before', (tester) async {
      sale.tickets = [ticketJson()];
      await start(tester, '/tickets');
      expect(find.text(l.passesTitle), findsNothing);
      expect(find.byKey(const Key('ticket.passChip.$ticketId')), findsNothing);
    });

    testWidgets("an order with event null: the product name on screen 1's strip and the cash screen", (tester) async {
      sale
        ..orders = 'orders_200_product_cash'
        ..orderStatus = 'AWAITING_CASH';
      await start(tester, '/');
      expect(find.textContaining('Abonnement Tribune Est'), findsOneWidget);

      await tap(tester, 'home.cashOrder');
      expect(textOf(tester, 'cash.product'), 'Abonnement Tribune Est');
      expect(find.byKey(const Key('cash.deadline')), findsOneWidget);
      expect(find.byKey(const Key('cash.pay.CARD')), findsOneWidget);
    });

    testWidgets('signed in, then out: the passes are not read without a session', (tester) async {
      final c = await start(tester, '/tickets', signedIn: false);
      expect(location(c), startsWith('/login'));
      expect(sale.calls('GET', '/fan/passes'), isEmpty);
      await c.read(sessionProvider.notifier).signIn('jwt');
      await tester.pumpAndSettle();
      expect(sale.calls('GET', '/fan/passes'), hasLength(1));
    });
  });
}
