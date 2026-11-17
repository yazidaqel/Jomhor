import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/checkout_screen.dart';
import 'package:jomhor_fan/features/checkout/order.dart';

import 'cash_order_test.dart' show CashSale, orderId, redirect;
import 'helpers.dart';

/// A contract answer of test/fixtures/promo (docs/04-api.md Lot 5 step 4), as `(status, body)`.
({int status, Map<String, Object?> body}) promoAnswer(String name) => realAnswer(name, dir: 'promo');

/// [CashSale] (event 1, Tribune Nord, 2 x 50 DH) whose `POST /fan/orders` takes a `promo_code`: with one it answers
/// test/fixtures/promo/order_201_promo.json (ATLAS20, -20 DH, 80 DH to pay), and the order reads carry its
/// `promo_code`, `discount_mad` and `total_mad`. [refusals]: the next POSTs answer these fixtures instead.
class PromoSale extends CashSale {
  new(super.clock);

  final refusals = <String>[];

  /// The order was placed with a code (or a test says so for an order screen).
  bool coded = false;

  /// The 201 the server sends for a coded order; a test may change its amounts.
  final Map<String, Object?> placed = promoAnswer('order_201_promo').body;

  @override
  Map<String, Object?> order() {
    final o = super.order();
    if (!coded) return o;
    return {
      ...o,
      'total_mad': placed['total_mad'],
      'promo_code': placed['promo_code'],
      'discount_mad': placed['discount_mad'],
      if (o['cash'] case final Map<String, Object?> cash) 'cash': {...cash, 'amount_mad': placed['total_mad']},
    };
  }

  @override
  Future<http.Response> handle(http.Request r) async {
    if (r.method != 'POST' || r.url.path != '/v1/fan/orders') return await super.handle(r);
    requests.add(r);
    if (refusals.isNotEmpty) {
      final (:status, :body) = promoAnswer(refusals.removeAt(0));
      return answer(body, status);
    }
    coded = (jsonDecode(r.body) as Map<String, Object?>)['promo_code'] != null;
    if (!coded) return answer(order(), 201);
    return answer({...placed, 'reserved_until': reservedUntil.toIso8601String()}, 201);
  }

  List<Map<String, Object?>> placedBodies() => [
    for (final r in calls('POST', '/fan/orders')) jsonDecode(r.body) as Map<String, Object?>,
  ];
}

void main() {
  late TestClock clock;
  late PromoSale sale;
  late List<String> launched;

  setUp(() {
    clock = TestClock(DateTime.utc(2027, 2, 20, 12));
    sale = PromoSale(clock);
    launched = [];
  });

  Future<ProviderContainer> open(WidgetTester tester, String location) async {
    // Every row of screen 4 built at once (ListView children are lazy).
    tester.view
      ..physicalSize = const Size(800, 2400)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final container = await pumpJomhor(
      tester,
      backend: sale.handle,
      tokens: MemoryTokenStore('jwt'),
      clock: clock,
      overrides: [
        cardPaymentLauncherProvider.overrideWithValue((context, url, id) async {
          launched.add(url);
          return false; // The fan closes the hosted page.
        }),
      ],
    );
    container.read(routerProvider).go(location);
    await tester.pumpAndSettle();
    return container;
  }

  const checkout = '/events/1/checkout?price=pc-1-0&qty=2';

  String cta(WidgetTester tester) =>
      (tester.widget<FilledButton>(find.byKey(const Key('checkout.pay'))).child! as Text).data!;

  Future<void> tapCta(WidgetTester tester) async {
    await tester.ensureVisible(find.byKey(const Key('checkout.pay')));
    await tester.tap(find.byKey(const Key('checkout.pay')));
    await tester.pumpAndSettle();
  }

  Future<void> typeCode(WidgetTester tester, String code) async {
    await tester.enterText(find.byKey(const Key('holder.1.name')), 'Sara Benali');
    await tester.tap(find.byKey(const Key('promo.open')));
    await tester.pump();
    await tester.enterText(find.byKey(const Key('promo.field')), code);
    await tester.pump();
  }

  String? fieldError(WidgetTester tester) =>
      tester.widget<TextField>(find.byKey(const Key('promo.field'))).decoration!.errorText;

  testWidgets("no code: today's flow, place and pay in one tap, no promo_code sent", (tester) async {
    await open(tester, checkout);
    expect(find.byKey(const Key('promo.field')), findsNothing);
    await tester.enterText(find.byKey(const Key('holder.1.name')), 'Sara Benali');
    expect(cta(tester), 'Payer ${formatDh(10000)}');
    await tapCta(tester);
    expect(sale.placedBodies().single.containsKey('promo_code'), isFalse);
    expect(sale.paidWith(), ['CARD']);
    expect(launched, [redirect]);
    expect(find.byKey(const Key('promo.line')), findsNothing);
  });

  testWidgets('with a code: hold only, the server\'s discount and total, then "Payer {total}" pays', (tester) async {
    await open(tester, checkout);
    await typeCode(tester, ' atlas20 ');
    expect(cta(tester), 'Réserver avec le code');
    await tapCta(tester);

    expect(sale.placedBodies().single['promo_code'], 'atlas20', reason: 'trimmed; the server uppercases');
    expect(sale.calls('POST', '/fan/orders/$orderId/pay'), isEmpty, reason: 'no /pay before the fan sees the total');
    expect(launched, isEmpty);
    expect(find.byKey(const Key('checkout.hold')), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const Key('promo.line'))).data, 'Code ATLAS20 · −${formatDh(2000)}');
    expect(find.text(formatDh(8000)), findsOneWidget, reason: "the total line is the response's total_mad");
    expect(cta(tester), 'Payer ${formatDh(8000)}');
    expect(find.byKey(const Key('promo.field')), findsNothing, reason: 'the code is on the order now');

    await tapCta(tester);
    expect(sale.paidWith(), ['CARD']);
    expect(sale.placedBodies(), hasLength(1), reason: 'the held order is paid, not placed again');
    expect(launched, [redirect]);
  });

  testWidgets('no money maths: discount and total are shown as sent', (tester) async {
    sale.placed
      ..['total_mad'] = 7777
      ..['discount_mad'] = 1234;
    await open(tester, checkout);
    await typeCode(tester, 'ATLAS20');
    await tapCta(tester);
    expect(tester.widget<Text>(find.byKey(const Key('promo.line'))).data, 'Code ATLAS20 · −${formatDh(1234)}');
    expect(find.text(formatDh(7777)), findsOneWidget);
    expect(cta(tester), 'Payer ${formatDh(7777)}');
  });

  for (final (fixture, copy) in [
    ('order_409_promo_unknown', 'Code inconnu ou expiré'),
    ('order_409_promo_not_applicable', "Ce code ne s'applique pas à ces places"),
    ('order_409_promo_used_up', 'Ce code a atteint sa limite'),
    ('order_409_promo_account_limit', 'Tu as déjà utilisé ce code'),
    ('order_422_promo_code', 'Code inconnu ou expiré'),
  ]) {
    testWidgets('$fixture: "$copy" under the field, no order screen, no hold', (tester) async {
      sale.refusals.add(fixture);
      await open(tester, checkout);
      await typeCode(tester, 'ATLAS20');
      await tapCta(tester);
      expect(fieldError(tester), copy);
      expect(find.byKey(const Key('checkout.error')), findsNothing, reason: 'said on the field, not at the CTA');
      expect(find.byKey(const Key('checkout.hold')), findsNothing);
      expect(find.byType(CheckoutScreen), findsOneWidget);
      expect(sale.calls('POST', '/fan/orders/$orderId/pay'), isEmpty);
      expect(cta(tester), 'Réserver avec le code');
    });
  }

  testWidgets('"Retirer" clears the code and its refusal before the hold: the order goes without it', (tester) async {
    sale.refusals.add('order_409_promo_used_up');
    await open(tester, checkout);
    await typeCode(tester, 'ATLAS20');
    await tapCta(tester);
    expect(fieldError(tester), 'Ce code a atteint sa limite');

    await tester.tap(find.byKey(const Key('promo.remove')));
    await tester.pump();
    expect(find.byKey(const Key('promo.field')), findsNothing);
    expect(cta(tester), 'Payer ${formatDh(10000)}');
    await tester.tap(find.byKey(const Key('promo.open')));
    await tester.pump();
    expect(tester.widget<TextField>(find.byKey(const Key('promo.field'))).controller!.text, isEmpty);
    expect(fieldError(tester), isNull);

    await tester.tap(find.byKey(const Key('promo.remove')));
    await tester.pump();
    await tapCta(tester);
    expect(sale.placedBodies().last.containsKey('promo_code'), isFalse);
    expect(sale.paidWith(), ['CARD'], reason: 'no code: place and pay in one tap');
  });

  testWidgets('the order screen shows the line when discount_mad > 0; the cash amount is total_mad', (tester) async {
    sale
      ..coded = true
      ..status = 'AWAITING_CASH'
      ..paymentMethod = 'CASH_AGENCY'
      ..paymentStatus = 'PENDING'
      ..reservedUntil = clock.now.add(const Duration(hours: 24));
    await open(tester, '/orders/$orderId');
    expect(tester.widget<Text>(find.byKey(const Key('promo.line'))).data, 'Code ATLAS20 · −${formatDh(2000)}');
    expect(tester.widget<Text>(find.byKey(const Key('cash.amount'))).data, contains(formatDh(8000)));
  });

  for (final (name, coded, discount) in [('paid with a code', true, 2000), ('no code', false, 0), ('0', true, 0)]) {
    testWidgets('PAID order screen, $name: the line only when discount_mad > 0', (tester) async {
      sale
        ..coded = coded
        ..status = 'PAID'
        ..paymentMethod = 'CARD'
        ..paymentStatus = 'SUCCEEDED'
        ..placed['discount_mad'] = discount;
      await open(tester, '/orders/$orderId');
      expect(find.byKey(const Key('order.paid')), findsOneWidget);
      expect(find.byKey(const Key('promo.line')), discount > 0 ? findsOneWidget : findsNothing);
    });
  }

  test('FanOrder reads promo_code and discount_mad, absent on older answers', () {
    final o = FanOrder.fromJson(promoAnswer('order_201_promo').body);
    expect((o.promoCode, o.discountMad, o.totalMad), ('ATLAS20', 2000, 8000));
    final plain = FanOrder.fromJson(sale.order());
    expect((plain.promoCode, plain.discountMad), (null, 0));
  });
}
