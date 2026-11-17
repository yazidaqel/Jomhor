import 'dart:convert';
import 'dart:io';

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

/// An answer of test/fixtures/promo, captured in the FL-32 real run (2026-11-17), as `(status, body)`; [dir]
/// `goldens/promo`: the design answer in the same shape.
({int status, Map<String, Object?> body}) promoAnswer(String name, {String dir = 'promo'}) =>
    realAnswer(name, dir: dir);

/// [CashSale] (event 1, Tribune Nord, 2 x 50 DH) whose `POST /fan/orders` takes a `promo_code`: with one it answers
/// [dir]'s order_201_promo.json (the real run's FL32PCT20: -60 DH, 240 DH to pay; or the goldens' ATLAS20: -20 DH,
/// 80 DH), served as this fake's order, and the order reads carry its `promo_code`, `discount_mad` and `total_mad`.
/// [refusals]: the next POSTs answer these real-run captures instead.
class PromoSale extends CashSale {
  new(super.clock, {this.dir = 'promo'});

  final String dir;
  final refusals = <String>[];

  /// The order was placed with a code (or a test says so for an order screen).
  bool coded = false;

  /// The 201 the server sends for a coded order, as this fake's order id; a test may change its amounts.
  late final Map<String, Object?> placed = promoAnswer('order_201_promo', dir: dir).body..['id'] = orderId;

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

  /// The real run's line: FL32PCT20 took 60 DH off 2 x 150 DH.
  final realLine = 'Code FL32PCT20 · −${formatDh(6000)}';

  Future<ProviderContainer> open(
    WidgetTester tester,
    String location, {
    Size size = const Size(800, 2400),
    Future<http.Response> Function(http.Request)? backend,
  }) async {
    // Default: every row of screen 4 built at once (ListView children are lazy).
    tester.view
      ..physicalSize = size
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final container = await pumpJomhor(
      tester,
      backend: backend ?? sale.handle,
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

  testWidgets('with a code (real 201): hold only, the server discount and total, then "Payer {total}" pays', (
    tester,
  ) async {
    await open(tester, checkout);
    await typeCode(tester, ' fl32pct20 ');
    expect(cta(tester), 'Réserver avec le code');
    await tapCta(tester);

    expect(sale.placedBodies().single['promo_code'], 'fl32pct20', reason: 'trimmed; the server uppercases');
    expect(sale.calls('POST', '/fan/orders/$orderId/pay'), isEmpty, reason: 'no /pay before the fan sees the total');
    expect(launched, isEmpty);
    expect(find.byKey(const Key('checkout.hold')), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const Key('promo.line'))).data, realLine);
    expect(find.text(formatDh(24000)), findsOneWidget, reason: "the total line is the response's total_mad");
    expect(cta(tester), 'Payer ${formatDh(24000)}');
    expect(find.byKey(const Key('promo.field')), findsNothing, reason: 'the code is on the order now');

    await tapCta(tester);
    expect(sale.placedBodies(), hasLength(1), reason: 'the held order is paid, not placed again');
    // The server charges the order's total_mad (the real run's gateway charged 24 000): /pay never sends an amount.
    final pay = sale.calls('POST', '/fan/orders/$orderId/pay').single;
    expect(jsonDecode(pay.body), {'method': 'CARD', 'channel': 'APP'});
    expect(launched, [redirect]);
  });

  testWidgets('no money maths: discount and total are shown as sent', (tester) async {
    sale.placed
      ..['total_mad'] = 7777
      ..['discount_mad'] = 1234;
    await open(tester, checkout);
    await typeCode(tester, 'FL32PCT20');
    await tapCta(tester);
    expect(tester.widget<Text>(find.byKey(const Key('promo.line'))).data, 'Code FL32PCT20 · −${formatDh(1234)}');
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
    testWidgets('$fixture (real): "$copy" under the field, no order screen, no hold', (tester) async {
      sale.refusals.add(fixture);
      await open(tester, checkout);
      await typeCode(tester, 'FL32PCT20');
      await tapCta(tester);
      expect(fieldError(tester), copy);
      expect(find.byKey(const Key('checkout.error')), findsNothing, reason: 'said on the field, not at the CTA');
      expect(find.byKey(const Key('checkout.hold')), findsNothing);
      expect(find.byType(CheckoutScreen), findsOneWidget);
      expect(sale.calls('POST', '/fan/orders/$orderId/pay'), isEmpty);
      expect(cta(tester), 'Réserver avec le code');
    });
  }

  testWidgets('a refusal scrolls the field back into view when the fan was down at the holder rows', (tester) async {
    sale.refusals.add('order_409_promo_used_up');
    await open(tester, checkout, size: const Size(390, 560)); // A short phone: the list scrolls.
    await tester.tap(find.byKey(const Key('promo.open')));
    await tester.pump();
    await tester.enterText(find.byKey(const Key('promo.field')), 'FL32ONE');
    final list = find.byType(ListView).last;
    await tester.dragUntilVisible(find.byKey(const Key('holder.1.name')), list, const Offset(0, -100));
    await tester.enterText(find.byKey(const Key('holder.1.name')), 'Sara Benali');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.drag(list, const Offset(0, -3000)); // Down to the payment methods.
    await tester.pumpAndSettle();
    final view = tester.getRect(list);
    bool fieldInView() {
      final field = find.byKey(const Key('promo.field'));
      if (field.evaluate().isEmpty) return false;
      final r = tester.getRect(field);
      return r.top >= view.top && r.bottom <= view.bottom;
    }

    expect(fieldInView(), isFalse);
    await tester.tap(find.byKey(const Key('checkout.pay')));
    await tester.pumpAndSettle();
    expect(fieldError(tester), 'Ce code a atteint sa limite');
    expect(fieldInView(), isTrue);
  });

  testWidgets('tapping into the refused field keeps its refusal; typing a new code clears it', (tester) async {
    sale.refusals.add('order_409_promo_unknown');
    await open(tester, checkout);
    await typeCode(tester, 'FL32PCT2O');
    await tapCta(tester);
    expect(fieldError(tester), 'Code inconnu ou expiré');
    await tester.tap(find.byKey(const Key('promo.field'))); // A selection change, same text.
    await tester.pump();
    expect(fieldError(tester), 'Code inconnu ou expiré');
    await tester.enterText(find.byKey(const Key('promo.field')), 'FL32PCT20');
    await tester.pump();
    expect(fieldError(tester), isNull);
  });

  testWidgets('"Retirer" clears the code and its refusal before the hold: the order goes without it', (tester) async {
    sale.refusals.add('order_409_promo_used_up');
    await open(tester, checkout);
    await typeCode(tester, 'FL32ONE');
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

  /// A backend serving one captured `GET /fan/orders/{id}` (its event is unknown here: 404, the slots stay empty).
  Future<http.Response> Function(http.Request) serving(Map<String, Object?> order) =>
      (r) async => switch ((r.method, r.url.path)) {
        ('GET', final p) when p == '/v1/fan/orders/${order['id']}' => http.Response.bytes(
          utf8.encode(jsonEncode(order)),
          200,
          headers: {'content-type': 'application/json', 'date': HttpDate.format(clock.now)},
        ),
        ('GET', '/v1/fan/tickets') => json({'server_time': clock.now.toIso8601String(), 'tickets': <Object?>[]}),
        _ => apiError(404, 'NOT_FOUND'),
      };

  testWidgets('real cash capture (AMOUNT): the line under the code card, the amount is total_mad', (tester) async {
    final order = promoAnswer('order_200_cash_promo').body;
    // Read on the capture's day, inside its 24 h hold.
    clock = TestClock(DateTime.parse(order['reserved_until']! as String).subtract(const Duration(hours: 2)));
    await open(tester, '/orders/${order['id']}', backend: serving(order));
    expect(tester.widget<Text>(find.byKey(const Key('promo.line'))).data, 'Code FL32AMT50 · −${formatDh(5000)}');
    expect(tester.widget<Text>(find.byKey(const Key('cash.amount'))).data, contains(formatDh(25000)));
    expect(find.text('JMH 116X HP'), findsOneWidget);
  });

  testWidgets('real paid capture: the line, centred under the success text', (tester) async {
    final order = promoAnswer('order_200_paid_promo').body;
    await open(tester, '/orders/${order['id']}', backend: serving(order));
    expect(find.byKey(const Key('order.paid')), findsOneWidget);
    final line = find.byKey(const Key('promo.line'));
    expect(tester.widget<Text>(line).data, realLine);
    final card = tester.getRect(find.byKey(const Key('order.paid')));
    expect(tester.getCenter(line).dx, moreOrLessEquals(card.center.dx, epsilon: 1));
  });

  for (final (name, coded, discount) in [('paid with a code', true, 6000), ('no code', false, 0), ('0', true, 0)]) {
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

  test('every capture reads: the 201s, the GETs before and after paying, the cash code', () {
    final placed = FanOrder.fromJson(promoAnswer('order_201_promo').body);
    expect(
      (placed.status, placed.promoCode, placed.discountMad, placed.totalMad),
      ('RESERVED', 'FL32PCT20', 6000, 24000),
    );
    final amount = FanOrder.fromJson(promoAnswer('order_201_promo_amount').body);
    expect((amount.promoCode, amount.discountMad, amount.totalMad), ('FL32AMT50', 5000, 25000));
    final held = FanOrder.fromJson(promoAnswer('order_200_reserved_promo').body);
    expect(
      (held.status, held.paymentStatus, held.paymentStatusKnown, held.discountMad),
      ('RESERVED', null, true, 6000),
    );
    expect(payAgain(held, waitedLong: false, holdOver: false), PayAgain.notStarted);
    final paid = FanOrder.fromJson(promoAnswer('order_200_paid_promo').body);
    expect(
      (paid.paid, paid.paymentMethod, paid.totalMad, paid.discountMad, paid.ticketIds.length),
      (true, PayMethod.card, 24000, 6000, 2),
    );
    final cash = FanOrder.fromJson(promoAnswer('order_200_cash_promo').body);
    expect((cash.awaitingCash, cash.cash!.amountMad, cash.totalMad, cash.promoCode), (true, 25000, 25000, 'FL32AMT50'));
    final plain = FanOrder.fromJson(sale.order());
    expect((plain.promoCode, plain.discountMad), (null, 0));
  });

  test('the goldens design answer has the shape of the real 201', () {
    expect(
      keyPaths(promoAnswer('order_201_promo', dir: 'goldens/promo').body),
      keyPaths(promoAnswer('order_201_promo').body),
    );
  });

  test('captures carry no JWT and no phone number', () {
    for (final f in Directory('test/fixtures/promo').listSync().whereType<File>()) {
      final text = f.readAsStringSync();
      expect(text, isNot(contains('eyJ')), reason: f.path);
      expect(text, isNot(matches(RegExp(r'\+?212\d{9}|0[67]\d{8}'))), reason: f.path);
    }
  });
}
