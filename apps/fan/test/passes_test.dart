import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/passes/pass.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/ui/bidi.dart';

import 'helpers.dart';
import 'tickets_test.dart' show otherId, ticketId, ticketJson;

const productId = 'b1000000-0000-4000-8000-000000000001';
const passId = 'c1000000-0000-4000-8000-000000000001';
const productOrderId = 'd1000000-0000-4000-8000-000000000001';
const installmentOrderId = 'd1000000-0000-4000-8000-000000000002';
const cardPage = 'http://10.0.2.2:8080/v1/payments/card/p1/redirect';

/// A design answer of test/fixtures/goldens/passes (contract-first content, in the real run's shape), as
/// `(status, body)`; the body is a fresh copy a test may change.
({int status, Map<String, Object?> body}) passAnswer(String name) => realAnswer(name, dir: 'goldens/passes');

/// A fake backend for one PASS product (two announced matches), its purchase order and the caller's passes and
/// tickets, shaped as docs/04-api.md §Products, §Passes. [dir]: the answers it serves, the design ones (Tribune Est,
/// the goldens) or `passes`, the FL-29 real run's; the product and order ids are the answers' own.
class PassSale {
  new(this.clock, {this.dir = 'goldens/passes'});

  final TestClock clock;
  final String dir;
  final requests = <http.Request>[];

  /// [name] in [dir], or `{dir}/{name}` in another one (the 3b answers: [installments]).
  ({int status, Map<String, Object?> body}) load(String name) {
    final i = name.lastIndexOf('/');
    return i < 0 ? realAnswer(name, dir: dir) : realAnswer(name.substring(i + 1), dir: name.substring(0, i));
  }

  /// Where the 3b answers come from: the design ones (the goldens) or `installments`, the FL-31 real run's; the pass
  /// and installment order ids are their `installments_201`'s.
  String installments = 'goldens/installments';
  late final Map<String, Object?> installmentOrder = load('$installments/installments_201').body;

  /// `GET /fan/products/{id}` body; null = 404 (off sale).
  late Map<String, Object?>? product = load('product_200_pass').body;

  late String productId = load('product_200_pass').body['id']! as String;
  late String orderId = load('product_order_201').body['id']! as String;

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

  /// A whole `GET /fan/passes` body to serve instead of the fixture.
  Map<String, Object?>? passesBody;

  /// `GET /fan/tickets` rows.
  List<Map<String, Object?>> tickets = [];

  /// `GET /fan/orders` fixture; null = no order.
  String? orders;

  /// Queued answers of `POST /fan/passes/{id}/installments` (default: the 201).
  final installmentAnswers = <String>[];

  http.Response answer(Object? body, [int status = 200]) => http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    status,
    headers: {'content-type': 'application/json', 'date': HttpDate.format(clock.now)},
  );

  http.Response fixture(String name) {
    final (:status, :body) = load(name);
    return answer(body, status);
  }

  Map<String, Object?> order() => {
    ...load(orderStatus == 'PAID' ? 'order_200_paid' : 'order_200_reserved').body,
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
      ('GET', final p) when p == '/v1/fan/products/$productId' =>
        product == null ? apiError(404, 'NOT_FOUND') : answer(product),
      ('GET', '/v1/fan/me') => answer({'id': 'u1', 'phone': '+212600000001', 'display_name': 'Yazid Aqel'}),
      ('POST', '/v1/fan/product-orders') => fixture(
        productOrders.isEmpty ? 'product_order_201' : productOrders.removeAt(0),
      ),
      ('GET', final p) when p == '/v1/fan/orders/$orderId' => answer(productOrderBody ?? order()),
      ('POST', final p) when p == '/v1/fan/orders/$orderId/pay' => () {
        paymentStatus = 'PENDING';
        return answer({'payment_id': 'p1', 'method': 'CARD', 'redirect_url': cardPage});
      }(),
      ('POST', final p) when p == '/v1/fan/passes/${installmentOrder['pass_id']}/installments' => fixture(
        installmentAnswers.isEmpty ? '$installments/installments_201' : installmentAnswers.removeAt(0),
      ),
      ('GET', final p) when p == '/v1/fan/orders/${installmentOrder['id']}' => answer({
        ...load('$installments/order_200_installment').body,
        'reserved_until': clock.now.add(const Duration(minutes: 8)).toIso8601String(),
      }),
      ('GET', '/v1/fan/orders') => orders == null ? answer({'orders': <Object?>[]}) : fixture(orders!),
      ('GET', '/v1/fan/passes') => switch ((passesBody, passes)) {
        (final body?, _) => answer(body),
        (_, final name?) => fixture(name),
        _ => answer({'passes': <Object?>[]}),
      },
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
      expect(find.byKey(const Key('buy.plan.1')), findsNothing, reason: 'one plan: no chips');
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
      expect(sent(), {'product_id': productId, 'channel': 'APP', 'holder_name': 'Yazid Aqel', 'installments': 1});
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

    testWidgets("a pass at a Europe/Paris venue shows its events in Paris time, not Morocco's", (tester) async {
      final body = passAnswer('passes_200_active').body;
      final pass = (body['passes']! as List<Object?>).single! as Map<String, Object?>;
      pass['timezone'] = 'Europe/Paris';
      sale.passesBody = body;
      await start(tester, '/passes/$passId');
      // Event 8, 2027-04-03T19:00Z: 21:00 in Paris (CEST), 20:00 in Casablanca.
      final at = DateTime.parse('2027-04-03T19:00:00Z');
      final clock = venueClock(at, 'Europe/Paris', 'fr');
      expect(clock, isNot(venueClock(at, 'Africa/Casablanca', 'fr')));
      expect(
        find.descendant(
          of: find.byKey(const Key('pass.event.8')),
          matching: find.text(l.dotPair(venueDate(at, 'Europe/Paris', 'fr'), l.ltr(clock))),
        ),
        findsOneWidget,
      );
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

  group('FL-29 real run (test/fixtures/passes, captured from the dev API)', () {
    // 20:38Z on the run's day: the pass bought at 20:37, both matches' tickets issued by the pass job.
    PassSale real({DateTime? at}) =>
        sale = PassSale(clock = TestClock(at ?? DateTime.utc(2026, 11, 15, 20, 38)), dir: 'passes');
    Map<String, Object?> body(String name) => realAnswer(name, dir: 'passes').body;
    Map<String, Object?> realPass(String name) =>
        (body(name)['passes']! as List<Object?>).single! as Map<String, Object?>;
    List<int> eventIds(Map<String, Object?> m) => [
      for (final e in (m['events']! as List<Object?>).cast<Map<String, Object?>>()) e['id']! as int,
    ];

    test("the design answers (test/fixtures/goldens/passes) have the real answers' shape", () {
      for (final f in Directory('test/fixtures/goldens/passes').listSync().whereType<File>()) {
        final name = f.uri.pathSegments.last.replaceAll('.json', '');
        expect(keyPaths(passAnswer(name).body), keyPaths(body(name)), reason: name);
      }
    });

    test('GET /fan/orders/{id} of a pass: timezone, payment_methods, cash_until, pass_id', () {
      final order = FanOrder.fromJson(body('order_200_paid'));
      final product = order.product!;
      expect((order.eventId, order.passId), (null, realPass('passes_200_active')['id']));
      expect(product.isPass, isTrue);
      expect(product.timezone, 'Africa/Casablanca');
      expect(product.paymentMethods, [PayMethod.card, PayMethod.wallet, PayMethod.cashAgency]);
      expect(product.cashUntil, DateTime.utc(2027, 6, 30, 10));
      expect(order.items.single.zoneId, 2);
      expect(order.items.single.holderLabels, ['Yazid A.']);
    });

    testWidgets('screen 13 buy reads the captured product; buy → screen 4 on the captured order', (tester) async {
      final p = real().product!;
      final pass = p['pass']! as Map<String, Object?>;
      final c = await start(tester, '/products/${sale.productId}');
      expect(textOf(tester, 'buy.name'), p['name']);
      expect(textOf(tester, 'buy.zone'), (pass['zone']! as Map<String, Object?>)['name']);
      expect(textOf(tester, 'buy.venue'), 'Grand Stade · Casablanca');
      expect(textOf(tester, 'buy.count'), '2 matchs annoncés');
      for (final id in eventIds(pass)) {
        expect(find.byKey(Key('buy.event.$id')), findsOneWidget);
      }
      expect(textOf(tester, 'buy.cta'), l.passBuy(l.dh(150000)));

      await tap(tester, 'buy.cta');
      expect(sent()['product_id'], sale.productId);
      expect(location(c), '/orders/${sale.orderId}');
      expect(find.text(p['name']! as String), findsOneWidget);
      expect(textOf(tester, 'product.holder'), l.productHolder('Yazid A.'));
      expect(find.byKey(const Key('method.CASH_AGENCY')), findsOneWidget);
    });

    testWidgets('a product past its sales_close_at: the CTA off, "Les ventes sont closes" before any POST', (
      tester,
    ) async {
      real(at: DateTime.utc(2027, 6, 30, 12));
      await start(tester, '/products/${sale.productId}');
      expect(tester.widget<FilledButton>(find.byKey(const Key('buy.cta'))).onPressed, isNull);
      expect(textOf(tester, 'buy.error'), l.errorProductNotOnSale);
      expect(sale.calls('POST', '/fan/product-orders'), isEmpty);
    });

    testWidgets('PASS_HELD → the owned view: the pass job issued a ticket for both matches', (tester) async {
      real()
        ..productOrders.add('product_order_409_pass_held')
        ..passes = 'passes_200_active';
      final held = realPass('passes_200_active');
      final c = await start(tester, '/products/${sale.productId}');
      await tap(tester, 'buy.cta');
      expect(location(c), '/passes/${held['id']}');
      expect(textOf(tester, 'pass.holder'), 'Yazid A.');
      for (final id in eventIds(held)) {
        expect(find.byKey(Key('pass.ticket.$id')), findsOneWidget);
      }
    });

    testWidgets('ACTIVE before the pass job: every match "Bientôt"', (tester) async {
      real().passes = 'passes_200_active_no_tickets';
      final pass = realPass('passes_200_active_no_tickets');
      await start(tester, '/passes/${pass['id']}');
      expect(find.byKey(const Key('pass.pending')), findsNothing);
      for (final id in eventIds(pass)) {
        expect(find.byKey(Key('pass.soon.$id')), findsOneWidget);
      }
    });

    testWidgets('PENDING: "Reprendre le paiement" opens the purchase order', (tester) async {
      real().passes = 'passes_200_pending';
      final pass = realPass('passes_200_pending');
      final c = await start(tester, '/passes/${pass['id']}');
      expect(find.byKey(const Key('pass.pending')), findsOneWidget);
      await tap(tester, 'pass.resume');
      expect(location(c), '/orders/${sale.orderId}');
    });

    for (final (name, check) in <(String, void Function(WidgetTester))>[
      ('product_order_409_sold_out', (t) => expect(find.byKey(const Key('buy.soldOut')), findsOneWidget)),
      ('product_order_409_not_on_sale', (t) => expect(find.text(l.errorProductNotOnSale), findsOneWidget)),
      ('product_order_422_holder_name', (t) => expect(find.text(l.holderNameError), findsOneWidget)),
    ]) {
      testWidgets('$name → the CTA says it', (tester) async {
        real().productOrders.add(name);
        await start(tester, '/products/${sale.productId}');
        await tap(tester, 'buy.cta');
        check(tester);
      });
    }

    testWidgets('/fan/tickets pass_id → the "Abonnement" chip; /fan/orders product row on screen 1', (tester) async {
      real()
        ..passes = 'passes_200_active'
        ..orders = 'orders_200_product_cash'
        ..orderStatus = 'AWAITING_CASH'
        ..tickets = [
          for (final t in (body('tickets_200_pass')['tickets']! as List<Object?>).cast<Map<String, Object?>>()) t,
        ];
      final c = await start(tester, '/');
      expect(find.textContaining(sale.product!['name']! as String), findsOneWidget);
      c.read(routerProvider).go('/tickets');
      await tester.pumpAndSettle();
      for (final t in sale.tickets) {
        expect(t['pass_id'], realPass('passes_200_active')['id']);
        expect(find.byKey(Key('ticket.passChip.${t['id']}')), findsOneWidget);
      }
    });
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

  group('FL-31 installments (design answers, test/fixtures/goldens/installments)', () {
    Map<String, Object?> body(String name) => realAnswer(name, dir: 'goldens/installments').body;
    Map<String, Object?> passRow(Map<String, Object?> b) =>
        (b['passes']! as List<Object?>).single! as Map<String, Object?>;
    String chip(WidgetTester tester, int seq) => textOf(tester, 'pass.installment.$seq.state');
    String day(String at) => venueDay(DateTime.parse(at), 'Africa/Casablanca', 'fr');
    Map<String, Object?> blocked(String by) =>
        {...passTicketJson(), 'status': 'BLOCKED', 'blocked_by': by}..remove('k_t');

    testWidgets('screen 13 buy: plan chips from plans, amounts as sent; the CTA follows the plan; installments sent', (
      tester,
    ) async {
      sale
        ..product = body('product_200_plans')
        ..productOrders.add('goldens/installments/product_order_201_plan3')
        ..productOrderBody = body('order_200_plan3_purchase');
      final c = await start(tester, '/products/$productId');
      expect(textOf(tester, 'buy.plan.1'), l.planOnce(l.dh(100000)));
      expect(textOf(tester, 'buy.plan.3'), l.planSplit(3, l.dh(33334), l.dh(33333), 30));
      expect(textOf(tester, 'buy.plan.3'), 'En 3 fois · 333,34\u00A0DH puis 333,33\u00A0DH tous les 30 jours');
      expect(textOf(tester, 'buy.cta'), l.passBuy(l.dh(100000)));

      await tap(tester, 'buy.plan.3');
      expect(textOf(tester, 'buy.cta'), l.passBuy(l.dh(33334)));
      await tap(tester, 'buy.cta');
      expect(sent(), {'product_id': productId, 'channel': 'APP', 'holder_name': 'Yazid Aqel', 'installments': 3});
      expect(location(c), '/orders/$productOrderId');
      expect(
        find.descendant(of: find.byKey(const Key('product.summary')), matching: find.text(l.installmentOf(1, 3))),
        findsOneWidget,
      );
      expect(textOf(tester, 'product.pay'), l.payCta(l.dh(33334)));
    });

    testWidgets("screen 4, a plan's purchase (installment 1/3): the product's cash path, capped by its sales close", (
      tester,
    ) async {
      sale
        ..product = {...body('product_200_plans'), 'sales_close_at': '2027-02-20T13:00:00Z'}
        ..productOrderBody = body('order_200_plan3_purchase');
      await start(tester, '/orders/$productOrderId');
      expect(find.text(l.installmentOf(1, 3)), findsOneWidget);
      expect(sale.calls('GET', '/fan/products/$productId'), isNotEmpty);
      expect(
        textOf(tester, 'method.CASH_AGENCY.hint'),
        l.methodCashBefore(venueWhen(DateTime.utc(2027, 2, 20, 13), 'Africa/Casablanca', 'fr')),
      );
    });

    testWidgets('no client maths: uneven amounts and another interval show exactly as sent', (tester) async {
      sale.product = {
        ...body('product_200_plans'),
        'plans': [
          {
            'installments': 1,
            'amounts_mad': [150000],
            'interval_days': null,
          },
          {
            'installments': 4,
            'amounts_mad': [37503, 37499, 37499, 37499],
            'interval_days': 28,
          },
        ],
      };
      await start(tester, '/products/$productId');
      expect(textOf(tester, 'buy.plan.4'), l.planSplit(4, l.dh(37503), l.dh(37499), 28));
      await tap(tester, 'buy.plan.4');
      expect(textOf(tester, 'buy.cta'), l.passBuy(l.dh(37503)));
    });

    testWidgets('422 NOT_OFFERED on installments: its copy, the plans read again', (tester) async {
      sale
        ..product = body('product_200_plans')
        ..productOrders.add('goldens/installments/product_order_422_not_offered');
      await start(tester, '/products/$productId');
      final reads = sale.calls('GET', '/fan/products/$productId').length;
      await tap(tester, 'buy.plan.3');
      await tap(tester, 'buy.cta');
      expect(textOf(tester, 'buy.error'), l.errorPlanNotOffered);
      expect(sale.calls('GET', '/fan/products/$productId').length, reads + 1);
    });

    testWidgets("screen 13 owned: the schedule Payée / À payer and \"Payer l'échéance · {amount}\"", (tester) async {
      sale.passes = 'goldens/installments/passes_200_schedule';
      await start(tester, '/passes/$passId');
      expect(textOf(tester, 'pass.installment.1'), l.installmentRow(1, 3, l.dh(33334), day('2027-01-25T10:00:00Z')));
      expect(textOf(tester, 'pass.installment.2'), l.installmentRow(2, 3, l.dh(33333), day('2027-02-24T10:00:00Z')));
      expect(
        (chip(tester, 1), chip(tester, 2), chip(tester, 3)),
        (l.installmentPaid, l.installmentDue, l.installmentDue),
      );
      expect(textOf(tester, 'pass.payInstallment'), l.passPayInstallment(l.dh(33333)));
      expect(find.byKey(const Key('pass.suspended')), findsNothing);
    });

    testWidgets('SUSPENDED: the danger banner with the due day; the overdue one "En retard"; "Suspendu" on Billets', (
      tester,
    ) async {
      sale.passes = 'goldens/installments/passes_200_suspended';
      final c = await start(tester, '/passes/$passId');
      expect(textOf(tester, 'pass.suspended'), l.passSuspendedBanner(day('2027-01-09T10:00:00Z')));
      expect(
        (chip(tester, 1), chip(tester, 2), chip(tester, 3)),
        (l.installmentPaid, l.installmentLate, l.installmentDue),
      );
      expect(find.byKey(const Key('pass.payInstallment')), findsOneWidget);

      c.read(routerProvider).go('/tickets');
      await tester.pumpAndSettle();
      expect(textOf(tester, 'passes.status.$passId'), l.passSuspended);
    });

    for (final (answer, status) in [('installments_201', 201), ('installments_200', 200)]) {
      testWidgets("\"Payer l'échéance\" → POST …/installments → $status → screen 4 \"Échéance 2/3\"", (tester) async {
        sale
          ..passes = 'goldens/installments/passes_200_schedule'
          ..installmentAnswers.add('goldens/installments/$answer');
        final c = await start(tester, '/passes/$passId');
        await tap(tester, 'pass.payInstallment');
        expect(sale.calls('POST', '/fan/passes/$passId/installments').single.body, isEmpty);
        expect(location(c), '/orders/$installmentOrderId');
        expect(
          find.descendant(of: find.byKey(const Key('product.summary')), matching: find.text(l.installmentOf(2, 3))),
          findsOneWidget,
        );
        expect(textOf(tester, 'product.pay'), l.payCta(l.dh(33333)));
      });
    }

    testWidgets(
      'screen 4, installment order: cash from the order alone (24 h, cash_until null), the product not read',
      (tester) async {
        // The pass's sales are closed: an installment is still payable in cash.
        sale.product = {...body('product_200_plans'), 'sales_close_at': '2027-02-20T13:00:00Z'};
        await start(tester, '/orders/$installmentOrderId');
        expect(find.text(l.installmentOf(2, 3)), findsOneWidget);
        expect(find.byKey(const Key('method.CASH_AGENCY')), findsOneWidget);
        // 24 h from now on server time, not the product's sales close an hour away.
        expect(
          textOf(tester, 'method.CASH_AGENCY.hint'),
          l.methodCashBefore(venueWhen(clock.now.add(const Duration(hours: 24)), 'Africa/Casablanca', 'fr')),
        );
        expect(sale.calls('GET', '/fan/products/$productId'), isEmpty);
      },
    );

    testWidgets('an installment paid: the neutral line (the pass may stay suspended), "Voir mon abonnement"', (
      tester,
    ) async {
      sale.productOrderBody = {
        ...body('order_200_installment'),
        'id': productOrderId,
        'status': 'PAID',
        'payment_status': 'SUCCEEDED',
        'payment_method': 'CARD',
        'paid_at': '2027-02-20T11:59:00Z',
      };
      final c = await start(tester, '/orders/$productOrderId');
      expect(textOf(tester, 'order.paidBody'), l.orderPaidInstallmentBody(2, 3));
      expect(find.text(l.orderPaidPassBody), findsNothing);
      await tap(tester, 'order.seePass');
      expect(location(c), '/passes/$passId');
    });

    test('a K_t next to a status other than VALID never shows a QR', () {
      final t = FanTicket.fromJson({...passTicketJson(), 'status': 'BLOCKED', 'blocked_by': 'PASS'});
      expect((t.hasQr, t.rotatingToken(DateTime.utc(2027, 2, 20, 12))), (false, null));
      expect(FanTicket.fromJson(passTicketJson()).hasQr, isTrue);
    });

    testWidgets('PASS_STATUS PAID_UP (paid elsewhere): the passes read again, nothing to say, no CTA left', (
      tester,
    ) async {
      sale
        ..passes = 'goldens/installments/passes_200_schedule'
        ..installmentAnswers.add('goldens/installments/installments_409_paid_up');
      final c = await start(tester, '/passes/$passId');
      final paid = body('passes_200_schedule');
      final row = passRow(paid);
      for (final i in (row['installments']! as List<Object?>).cast<Map<String, Object?>>()) {
        i['paid_at'] ??= '2027-02-20T11:00:00Z';
      }
      row['next_due'] = null;
      sale.passesBody = paid;
      final reads = sale.calls('GET', '/fan/passes').length;

      await tap(tester, 'pass.payInstallment');
      expect(location(c), '/passes/$passId');
      expect(sale.calls('GET', '/fan/passes').length, greaterThan(reads));
      expect(find.byKey(const Key('pass.payError')), findsNothing);
      expect(find.byKey(const Key('pass.payInstallment')), findsNothing);
      expect((chip(tester, 2), chip(tester, 3)), (l.installmentPaid, l.installmentPaid));
    });

    testWidgets('PASS_STATUS (not payable): its copy under the CTA', (tester) async {
      sale
        ..passes = 'goldens/installments/passes_200_schedule'
        ..installmentAnswers.add('goldens/installments/installments_409_status');
      await start(tester, '/passes/$passId');
      await tap(tester, 'pass.payInstallment');
      expect(textOf(tester, 'pass.payError'), l.errorPassStatus);
    });

    testWidgets("a ticket BLOCKED by its suspended pass: the copy and \"Payer l'échéance\" → screen 13 (Billets, 5)", (
      tester,
    ) async {
      sale
        ..passes = 'goldens/installments/passes_200_suspended'
        ..tickets = [blocked('PASS')];
      final c = await start(tester, '/tickets');
      expect(textOf(tester, 'ticket.status.$ticketId'), l.ticketBlockedPass);
      await tap(tester, 'ticket.payInstallment.$ticketId');
      expect(location(c), '/passes/$passId');

      c.read(routerProvider).go('/tickets/$ticketId');
      await tester.pumpAndSettle();
      expect(textOf(tester, 'ticket.status'), l.ticketBlockedPass);
      await tap(tester, 'ticket.payInstallment');
      expect(location(c), '/passes/$passId');
    });

    testWidgets('a pass ticket blocked by the club (blocked_by STAFF): the plain blocked copy, no CTA', (tester) async {
      sale
        ..passes = 'goldens/installments/passes_200_suspended'
        ..tickets = [blocked('STAFF')];
      final c = await start(tester, '/tickets');
      expect(textOf(tester, 'ticket.status.$ticketId'), l.ticketBlocked);
      expect(find.byKey(const Key('ticket.payInstallment.$ticketId')), findsNothing);
      c.read(routerProvider).go('/tickets/$ticketId');
      await tester.pumpAndSettle();
      expect(textOf(tester, 'ticket.status'), l.ticketBlocked);
      expect(find.byKey(const Key('ticket.payInstallment')), findsNothing);
    });

    test('the 3b answers parse: plans, schedule, next_due, suspended_at, installment', () {
      final pass = FanPass.fromJson(passRow(body('passes_200_suspended')));
      expect(pass.suspended, isTrue);
      expect(pass.suspendedAt, DateTime.utc(2027, 1, 16, 10, 0, 30));
      expect([for (final i in pass.installments) i.seq], [1, 2, 3]);
      expect(pass.nextDue, (seq: 2, amountMad: 33333, dueAt: DateTime.utc(2027, 1, 9, 10), overdue: true));
      expect(pass.canPay, isTrue);
      final order = FanOrder.fromJson(body('order_200_installment'));
      expect((order.installment, order.passId, order.product!.cashUntil), ((seq: 2, of: 3), passId, null));
      expect(FanOrder.fromJson(body('order_200_plan3_purchase')).installment, (seq: 1, of: 3));
      expect(FanOrder.fromJson(passAnswer('order_200_reserved').body).installment, isNull, reason: 'paid in full');
      final product = Product.fromJson(body('product_200_plans'), fetchedAt: DateTime.utc(2027, 2, 20));
      List<String> plans(Product p) => [for (final x in p.plans) '${x.installments} ${x.amountsMad} ${x.intervalDays}'];
      expect(plans(product), ['1 [100000] null', '3 [33334, 33333, 33333] 30']);
      // A 3a answer (one plan, no interval_days) still reads.
      expect(plans(Product.fromJson(passAnswer('product_200_pass').body, fetchedAt: DateTime.utc(2027, 2, 20))), [
        '1 [150000] null',
      ]);
    });
  });

  group('FL-31 real run (test/fixtures/installments, captured from the dev API, fast clock)', () {
    // The run's day: bought at 19:14Z, installment k due (k - 1) x 10 min later, suspended 2 min past its due date.
    Map<String, Object?> body(String name) => realAnswer(name, dir: 'installments').body;
    Map<String, Object?> passRow(String name) =>
        (body(name)['passes']! as List<Object?>).single! as Map<String, Object?>;
    List<Map<String, Object?>> ticketRows(String name) => (body(name)['tickets']! as List<Object?>).cast();
    final pass = passRow('passes_200_schedule');
    final realPassId = pass['id']! as String;
    final instOrderId = body('installments_201')['id']! as String;
    String day(Object? at) => venueDay(DateTime.parse(at! as String), 'Africa/Casablanca', 'fr');
    String chip(WidgetTester tester, int seq) => textOf(tester, 'pass.installment.$seq.state');

    PassSale real({DateTime? at}) =>
        sale = PassSale(clock = TestClock(at ?? DateTime.utc(2026, 11, 16, 19, 16)), dir: 'installments')
          ..installments = 'installments'
          ..product = body('product_200_plans')
          ..productId = body('product_200_plans')['id']! as String
          ..orderId = body('product_order_201_plan3')['id']! as String;

    test("the design answers (test/fixtures/goldens/installments) have the real answers' shape", () {
      for (final f in Directory('test/fixtures/goldens/installments').listSync().whereType<File>()) {
        final name = f.uri.pathSegments.last.replaceAll('.json', '');
        expect(keyPaths(realAnswer(name, dir: 'goldens/installments').body), keyPaths(body(name)), reason: name);
      }
    });

    test('the captured answers parse: plans, orders {k, n}, schedule, next_due, suspended_at, the blocked ticket', () {
      final product = Product.fromJson(body('product_200_plans'), fetchedAt: DateTime.utc(2026, 11, 16, 19, 14));
      // interval_days is in whole days: the fast clock's 10 min reads 0 (30 in production).
      expect(
        [for (final x in product.plans) '${x.installments} ${x.amountsMad} ${x.intervalDays}'],
        ['1 [100000] null', '3 [33334, 33333, 33333] 0'],
      );
      final purchase = FanOrder.fromJson(body('order_200_plan3_purchase'));
      expect((purchase.installment, purchase.passId, purchase.totalMad), ((seq: 1, of: 3), realPassId, 33334));
      final inst = FanOrder.fromJson(body('order_200_installment'));
      expect((inst.installment, inst.passId, inst.eventId), ((seq: 2, of: 3), realPassId, null));
      expect(inst.product!.cashUntil, isNull);
      expect(inst.product!.paymentMethods, contains(PayMethod.cashAgency));

      final active = FanPass.fromJson(pass);
      expect((active.status, active.suspendedAt, active.timezone), ('ACTIVE', null, 'Africa/Casablanca'));
      expect(
        [for (final i in active.installments) (i.seq, i.amountMad, i.paidAt != null)],
        [(1, 33334, true), (2, 33333, false), (3, 33333, false)],
      );
      expect((active.nextDue!.seq, active.nextDue!.overdue, active.canPay), (2, false, true));
      expect(active.installments[1].dueAt.difference(active.installments[0].dueAt), const Duration(minutes: 10));

      final suspended = FanPass.fromJson(passRow('passes_200_suspended'));
      expect((suspended.suspended, suspended.suspendedAt != null, suspended.canPay), (true, true, true));
      expect((suspended.nextDue!.seq, suspended.nextDue!.overdue), (2, true));
      expect(suspended.stateOf(suspended.installments[1]), InstallmentState.late);

      final reactivated = FanPass.fromJson(passRow('passes_200_reactivated'));
      expect((reactivated.status, reactivated.suspendedAt, reactivated.nextDue!.seq), ('ACTIVE', null, 3));
      final paidUp = FanPass.fromJson(passRow('passes_200_paid_up'));
      expect((paidUp.nextDue, paidUp.canPay, paidUp.installments.every((i) => i.paidAt != null)), (null, false, true));

      final now = DateTime.utc(2026, 11, 16, 19, 30);
      expect(ticketRows('tickets_200_blocked').every((t) => !t.containsKey('k_t')), isTrue);
      for (final t in ticketRows('tickets_200_blocked').map(FanTicket.fromJson)) {
        expect((t.status, t.blockedBy, t.blockedByPass, t.passId), ('BLOCKED', 'PASS', true, realPassId));
        expect((t.hasQr, t.rotatingToken(now)), (false, null));
      }
      for (final t in ticketRows('tickets_200_reactivated').map(FanTicket.fromJson)) {
        expect((t.status, t.blockedBy, t.hasQr), ('VALID', null, true));
        expect(t.rotatingToken(now), isNotNull);
      }
    });

    testWidgets('screen 13 buy: the captured plans; the 3-payment plan → installments 3 → screen 4 "Échéance 1/3"', (
      tester,
    ) async {
      real()
        ..productOrders.add('product_order_201_plan3')
        ..productOrderBody = body('order_200_plan3_purchase');
      final c = await start(tester, '/products/${sale.productId}');
      expect(textOf(tester, 'buy.plan.1'), l.planOnce(l.dh(100000)));
      expect(textOf(tester, 'buy.plan.3'), l.planSplit(3, l.dh(33334), l.dh(33333), 0));
      // The fast clock's interval_days 0: the plan stays, without "tous les 0 jours".
      expect(textOf(tester, 'buy.plan.3'), 'En 3 fois · 333,34\u00A0DH puis 333,33\u00A0DH');
      await tap(tester, 'buy.plan.3');
      expect(textOf(tester, 'buy.cta'), l.passBuy(l.dh(33334)));
      await tap(tester, 'buy.cta');
      expect(sent(), {'product_id': sale.productId, 'channel': 'APP', 'holder_name': 'Yazid Aqel', 'installments': 3});
      expect(location(c), '/orders/${sale.orderId}');
      expect(
        find.descendant(of: find.byKey(const Key('product.summary')), matching: find.text(l.installmentOf(1, 3))),
        findsOneWidget,
      );
      expect(textOf(tester, 'product.pay'), l.payCta(l.dh(33334)));
    });

    testWidgets('422 NOT_OFFERED (captured): its copy, the plans read again', (tester) async {
      real().productOrders.add('product_order_422_not_offered');
      await start(tester, '/products/${sale.productId}');
      final reads = sale.calls('GET', '/fan/products/${sale.productId}').length;
      await tap(tester, 'buy.plan.3');
      await tap(tester, 'buy.cta');
      expect(textOf(tester, 'buy.error'), l.errorPlanNotOffered);
      expect(sale.calls('GET', '/fan/products/${sale.productId}').length, reads + 1);
    });

    testWidgets('screen 13 owned: the captured schedule and its pay CTA', (tester) async {
      real().passes = 'passes_200_schedule';
      final rows = (pass['installments']! as List<Object?>).cast<Map<String, Object?>>();
      await start(tester, '/passes/$realPassId');
      expect(textOf(tester, 'pass.installment.2'), l.installmentRow(2, 3, l.dh(33333), day(rows[1]['due_at'])));
      expect(
        (chip(tester, 1), chip(tester, 2), chip(tester, 3)),
        (l.installmentPaid, l.installmentDue, l.installmentDue),
      );
      expect(textOf(tester, 'pass.payInstallment'), l.passPayInstallment(l.dh(33333)));
      expect(find.byKey(const Key('pass.suspended')), findsNothing);
    });

    for (final (answer, status) in [('installments_201', 201), ('installments_200', 200)]) {
      testWidgets("SUSPENDED (captured): the banner; \"Payer l'échéance\" → $status → screen 4 \"Échéance 2/3\"", (
        tester,
      ) async {
        real(at: DateTime.utc(2026, 11, 16, 19, 28))
          ..passes = 'passes_200_suspended'
          ..installmentAnswers.add(answer);
        final next = passRow('passes_200_suspended')['next_due']! as Map<String, Object?>;
        final c = await start(tester, '/passes/$realPassId');
        expect(textOf(tester, 'pass.suspended'), l.passSuspendedBanner(day(next['due_at'])));
        expect((chip(tester, 1), chip(tester, 2)), (l.installmentPaid, l.installmentLate));
        await tap(tester, 'pass.payInstallment');
        expect(sale.calls('POST', '/fan/passes/$realPassId/installments').single.body, isEmpty);
        expect(location(c), '/orders/$instOrderId');
        expect(
          find.descendant(of: find.byKey(const Key('product.summary')), matching: find.text(l.installmentOf(2, 3))),
          findsOneWidget,
        );
        expect(textOf(tester, 'product.pay'), l.payCta(l.dh(33333)));
      });
    }

    testWidgets('screen 4, the captured installment order after the sales close: cash held 24 h, product not read', (
      tester,
    ) async {
      real(at: DateTime.utc(2026, 11, 16, 19, 28)).product = {
        ...body('product_200_plans'),
        'sales_close_at': '2026-11-16T19:27:00Z',
      };
      await start(tester, '/orders/$instOrderId');
      expect(find.text(l.installmentOf(2, 3)), findsOneWidget);
      expect(
        textOf(tester, 'method.CASH_AGENCY.hint'),
        l.methodCashBefore(venueWhen(clock.now.add(const Duration(hours: 24)), 'Africa/Casablanca', 'fr')),
      );
      expect(sale.calls('GET', '/fan/products/${sale.productId}'), isEmpty);
    });

    testWidgets("/fan/tickets BLOCKED by PASS (captured): the copy, no QR, \"Payer l'échéance\" → screen 13", (
      tester,
    ) async {
      real(at: DateTime.utc(2026, 11, 16, 19, 28))
        ..passes = 'passes_200_suspended'
        ..tickets = ticketRows('tickets_200_blocked');
      final id = sale.tickets.first['id']! as String;
      final c = await start(tester, '/tickets/$id');
      expect(textOf(tester, 'ticket.status'), l.ticketBlockedPass);
      await tap(tester, 'ticket.payInstallment');
      expect(location(c), '/passes/$realPassId');
    });

    testWidgets('409 PAID_UP (captured): the passes read again (paid up), no CTA left', (tester) async {
      real(at: DateTime.utc(2026, 11, 16, 19, 33))
        ..passes = 'passes_200_reactivated'
        ..installmentAnswers.add('installments_409_paid_up');
      final c = await start(tester, '/passes/$realPassId');
      sale.passes = 'passes_200_paid_up';
      await tap(tester, 'pass.payInstallment');
      expect(location(c), '/passes/$realPassId');
      expect(find.byKey(const Key('pass.payError')), findsNothing);
      expect(find.byKey(const Key('pass.payInstallment')), findsNothing);
      expect((chip(tester, 2), chip(tester, 3)), (l.installmentPaid, l.installmentPaid));
    });

    testWidgets('PENDING in 3 (captured, next_due seq 1 overdue true): installment 1 "À payer", never "En retard"', (
      tester,
    ) async {
      real(at: DateTime.utc(2026, 11, 16, 19, 33)).passes = 'passes_200_pending';
      final pending = passRow('passes_200_pending');
      expect((pending['next_due']! as Map<String, Object?>)['overdue'], isTrue);
      await start(tester, '/passes/${pending['id']}');
      expect(find.byKey(const Key('pass.pending')), findsOneWidget);
      expect(
        (chip(tester, 1), chip(tester, 2), chip(tester, 3)),
        (l.installmentDue, l.installmentDue, l.installmentDue),
      );
      expect(find.text(l.installmentLate), findsNothing);
    });

    testWidgets('409 PASS_STATUS on a PENDING pass (captured): its copy under the CTA', (tester) async {
      real()
        ..passes = 'passes_200_schedule'
        ..installmentAnswers.add('installments_409_status');
      await start(tester, '/passes/$realPassId');
      await tap(tester, 'pass.payInstallment');
      expect(textOf(tester, 'pass.payError'), l.errorPassStatus);
    });
  });
}
