import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/order.dart';

import 'helpers.dart';
import 'tickets_test.dart' show ticketId, ticketJson;

const orderId = '0b8e7a52-0000-4000-8000-0000000000cc';
const redirect = 'http://10.0.2.2:8080/v1/payments/card/p1/redirect';

/// A fake backend for one order on event 1 (Tribune Nord, 2 x 50 DH) that walks RESERVED -> AWAITING_CASH -> PAID
/// as docs/04-api.md says: `/pay` CASH_AGENCY moves the hold to 24 h and the order shows `cash`.
class CashSale {
  new(this.clock);

  final TestClock clock;
  final requests = <http.Request>[];
  List<String> methods = ['CARD', 'WALLET', 'CASH_AGENCY'];

  /// `cash_until` of the event: sales close (2027-03-13T18:00Z) minus the 2 h window; null = no cash set up.
  String? cashUntil = '2027-03-13T16:00:00Z';
  String status = 'RESERVED';
  String? paymentMethod;
  String? paymentStatus;
  late DateTime reservedUntil = clock.now.add(const Duration(minutes: 8));

  /// `details.reason` of a 409 METHOD_NOT_AVAILABLE for CASH_AGENCY; null = the code is issued.
  String? cashRefusal;

  /// Holds `/pay` back until completed.
  Completer<void>? payGate;

  /// Holds the next `GET /fan/orders/{id}` answers back until completed.
  Completer<void>? orderGate;

  /// The next order reads that answer this status (502, 404) instead of the order.
  final orderFailures = <int>[];
  String payCode = '4F7K29';
  List<Map<String, Object?>> points = [
    {'name': 'Guichet du stade', 'address': "Stade de l'Atlas, porte 2", 'hours': '9 h - 18 h'},
  ];
  List<String> networks = ['Wafacash', 'Cash Plus'];

  http.Response answer(Object? body, [int code = 200]) => http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    code,
    headers: {'content-type': 'application/json', 'date': HttpDate.format(clock.now)},
  );

  Map<String, Object?> order() => {
    'id': orderId,
    'event_id': 1,
    'channel': 'APP',
    'status': status,
    'payment_status': paymentStatus,
    'payment_method': paymentMethod,
    'total_mad': 10000,
    'reserved_until': reservedUntil.toIso8601String(),
    'paid_at': null,
    'items': [
      {
        'price_category_id': 'pc-1-0',
        'zone_id': 1,
        'zone_name': 'Tribune Nord',
        'name': 'Tarif 0',
        'qty': 2,
        'unit_price_mad': 5000,
        'holders': [
          {'label': 'Yazid A.'},
          {'label': 'Sara B.'},
        ],
      },
    ],
    'ticket_ids': status == 'PAID' ? [ticketId] : <String>[],
    'cash': status == 'AWAITING_CASH'
        ? {'pay_code': payCode, 'amount_mad': 10000, 'points': points, 'networks': networks}
        : null,
  };

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
        'payment_methods': methods,
        'cash_until': cashUntil,
      }),
      ('GET', '/v1/fan/me') => answer({
        'id': 'u1',
        'phone': '+212600000001',
        'display_name': 'Yazid Aqel',
        'locale': 'fr',
      }),
      ('POST', '/v1/fan/orders') => answer(order(), 201),
      ('POST', '/v1/fan/orders/$orderId/pay') => await () async {
        await payGate?.future;
        return pay(jsonDecode(r.body) as Map<String, Object?>);
      }(),
      ('GET', '/v1/fan/orders/$orderId') => await () async {
        await orderGate?.future;
        if (orderFailures.isNotEmpty) {
          final code = orderFailures.removeAt(0);
          return apiError(code, code == 404 ? 'NOT_FOUND' : 'INTERNAL_ERROR');
        }
        return answer(order());
      }(),
      ('GET', '/v1/fan/tickets') => answer({
        'server_time': clock.now.toIso8601String(),
        'tickets': status == 'PAID' ? [ticketJson()] : <Object?>[],
      }),
      _ => apiError(404, 'NOT_FOUND'),
    };
  }

  http.Response pay(Map<String, Object?> body) {
    final method = body['method']! as String;
    if (method != 'CASH_AGENCY') {
      paymentMethod = method;
      paymentStatus = 'PENDING';
      return answer({'payment_id': 'p1', 'method': method, 'redirect_url': redirect});
    }
    if (cashRefusal case final reason?) {
      return answer({
        'code': 'METHOD_NOT_AVAILABLE',
        'message': 'x',
        'details': {'reason': reason},
      }, 409);
    }
    status = 'AWAITING_CASH';
    paymentMethod = 'CASH_AGENCY';
    paymentStatus = 'PENDING';
    reservedUntil = clock.now.add(const Duration(hours: 24));
    return answer({
      'payment_id': 'p2',
      'method': 'CASH_AGENCY',
      'pay_code': payCode,
      'reserved_until': reservedUntil.toIso8601String(),
      'amount_mad': 10000,
    });
  }

  Iterable<http.Request> calls(String method, String path) =>
      requests.where((r) => r.method == method && r.url.path == '/v1$path');

  List<Object?> paidWith() => [
    for (final r in calls('POST', '/fan/orders/$orderId/pay')) (jsonDecode(r.body) as Map<String, Object?>)['method'],
  ];
}

void main() {
  late TestClock clock;
  late CashSale sale;
  late List<String> launched;
  late List<String> clipboard;

  setUp(() {
    clock = TestClock(DateTime.utc(2027, 2, 20, 12));
    sale = CashSale(clock);
    launched = [];
    clipboard = [];
  });

  Future<ProviderContainer> open(WidgetTester tester, String location, {bool tall = true}) async {
    if (tall) {
      // Every row of screen 4 and of the cash screen built at once (ListView children are lazy).
      tester.view
        ..physicalSize = const Size(800, 2400)
        ..devicePixelRatio = 1;
      addTearDown(tester.view.reset);
    }
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        clipboard.add((call.arguments as Map<Object?, Object?>)['text']! as String);
      }
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    final container = await pumpJomhor(
      tester,
      backend: sale.handle,
      tokens: MemoryTokenStore('jwt'),
      clock: clock,
      overrides: [
        cardPaymentLauncherProvider.overrideWithValue((context, url, id) async {
          launched.add(url);
          return false; // The fan closes the hosted page: the payment stays PENDING.
        }),
      ],
    );
    container.read(routerProvider).go(location);
    await tester.pumpAndSettle();
    return container;
  }

  const checkout = '/events/1/checkout?price=pc-1-0&qty=2';

  Future<void> pick(WidgetTester tester, String method) async {
    final row = find.byKey(Key('method.$method'));
    await tester.ensureVisible(row);
    await tester.tap(row);
    await tester.pump();
  }

  Future<void> pay(WidgetTester tester) async {
    await tester.enterText(find.byKey(const Key('holder.1.name')), 'Sara Benali');
    await tester.ensureVisible(find.byKey(const Key('checkout.pay')));
    await tester.tap(find.byKey(const Key('checkout.pay')));
    await tester.pump();
    await tester.pump();
  }

  String cta(WidgetTester tester) =>
      (tester.widget<FilledButton>(find.byKey(const Key('checkout.pay'))).child! as Text).data!;

  test('the pay code is shown JMH 4F7K 29, whatever spacing or prefix the API sends', () {
    for (final raw in ['4F7K29', '4f7k29', 'JMH 4F7K 29', 'JMH-4F7K-29', 'jmh4f7k29']) {
      expect(displayPayCode(raw), 'JMH 4F7K 29', reason: raw);
    }
    // J, M and H are symbols: a bare code may start with them.
    expect(displayPayCode('JMH4F7'), 'JMH JMH4 F7');
    expect(displayPayCode('4F7K2'), '4F7K2', reason: 'not 6 symbols: shown as sent');
    expect(displayPayCode('4F7KU9'), '4F7KU9', reason: 'U is not a Crockford symbol');
  });

  testWidgets('method rows come from payment_methods; the CTA follows the method; /pay sends it', (tester) async {
    await open(tester, checkout);
    for (final m in ['CARD', 'WALLET', 'CASH_AGENCY']) {
      expect(find.byKey(Key('method.$m')), findsOneWidget);
    }
    expect(cta(tester), 'Payer ${formatDh(10000)}');
    final deadline = venueWhen(clock.now.add(const Duration(hours: 24)), 'Africa/Casablanca', 'fr');
    expect(find.text('Avant $deadline'), findsOneWidget);

    await pick(tester, 'WALLET');
    expect(cta(tester), 'Payer ${formatDh(10000)} par wallet');
    await pick(tester, 'CASH_AGENCY');
    expect(cta(tester), 'Obtenir mon code cash');
    await pick(tester, 'WALLET');
    await pay(tester);
    await tester.pump();
    expect(sale.paidWith(), ['WALLET']);
    expect(launched, [redirect], reason: 'the wallet goes through the same hosted page as the card');
  });

  testWidgets('/pay body: CARD by default', (tester) async {
    await open(tester, checkout);
    await pay(tester);
    await tester.pump();
    expect(sale.paidWith(), ['CARD']);
  });

  testWidgets('no WALLET in payment_methods: no wallet row; no CASH_AGENCY past cash_until: off, "Trop tard"', (
    tester,
  ) async {
    sale
      ..methods = ['CARD']
      ..cashUntil = '2027-02-20T11:00:00Z';
    await open(tester, checkout);
    expect(find.byKey(const Key('method.WALLET')), findsNothing);
    expect(find.text('Trop tard pour le cash'), findsOneWidget);
    await pick(tester, 'CASH_AGENCY');
    expect(cta(tester), 'Payer ${formatDh(10000)}', reason: 'the off row cannot be picked');
  });

  testWidgets('cash_until null (no cash set up): no cash row at all', (tester) async {
    sale
      ..methods = ['CARD']
      ..cashUntil = null;
    await open(tester, checkout);
    expect(find.byKey(const Key('method.CASH_AGENCY')), findsNothing);
    expect(find.text('Trop tard pour le cash'), findsNothing);
    expect(find.byKey(const Key('method.CARD')), findsOneWidget);
  });

  for (final (reason, copy) in [
    ('TOO_LATE', 'Trop tard pour payer en cash'),
    ('CASH_CAP', "Le paiement cash n'est plus possible"),
    ('NOT_CONFIGURED', "Ce moyen de paiement n'est pas disponible"),
  ]) {
    testWidgets('METHOD_NOT_AVAILABLE $reason: its copy, the cash row goes off, card takes over', (tester) async {
      sale.cashRefusal = reason;
      await open(tester, checkout);
      await pick(tester, 'CASH_AGENCY');
      await pay(tester);
      await tester.pumpAndSettle();
      expect(find.textContaining(copy), findsOneWidget);
      expect(find.byKey(const Key('cash.code')), findsNothing);
      expect(cta(tester), 'Payer ${formatDh(10000)}');
      await pick(tester, 'CASH_AGENCY');
      expect(cta(tester), 'Payer ${formatDh(10000)}', reason: 'refused: the row stays off');
    });
  }

  testWidgets('reserve -> cash -> the code screen -> poll every 10 s -> PAID -> the ticket', (tester) async {
    await open(tester, checkout);
    await pick(tester, 'CASH_AGENCY');
    await pay(tester);
    await tester.pumpAndSettle();
    expect(sale.paidWith(), ['CASH_AGENCY']);
    expect(launched, isEmpty);

    // The code on paper, the exact amount, the deadline in the venue zone, the time left.
    expect(tester.widget<Text>(find.byKey(const Key('cash.code'))).data, 'JMH 4F7K 29');
    expect(find.text('Montant exact à régler : ${formatDh(10000)}'), findsOneWidget);
    final until = venueWhen(sale.reservedUntil, 'Africa/Casablanca', 'fr');
    expect(find.text('Places réservées · paie avant $until'), findsOneWidget);
    expect(find.descendant(of: find.byKey(const Key('cash.hours')), matching: find.text('24')), findsOneWidget);
    // Where to pay comes from cash.points and cash.networks; no WhatsApp button (Q53).
    expect(find.text('Guichet du stade'), findsOneWidget);
    expect(find.text('En agence : Wafacash, Cash Plus'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, 'Recevoir sur WhatsApp'), findsNothing);

    await tester.tap(find.byKey(const Key('cash.copy')));
    await tester.pump();
    expect(clipboard, ['JMH 4F7K 29']);
    expect(find.text('Code copié'), findsOneWidget);

    final reads = sale.calls('GET', '/fan/orders/$orderId').length;
    await tester.pump(const Duration(seconds: 9));
    expect(sale.calls('GET', '/fan/orders/$orderId'), hasLength(reads), reason: 'not every 2 s');
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(sale.calls('GET', '/fan/orders/$orderId'), hasLength(reads + 1));

    sale
      ..status = 'PAID'
      ..paymentStatus = 'SUCCEEDED';
    await tester.pump(const Duration(seconds: 10));
    await tester.pumpAndSettle();
    expect(find.text("C'est validé !"), findsOneWidget);
    final settled = sale.calls('GET', '/fan/orders/$orderId').length;
    await tester.pump(const Duration(seconds: 30));
    expect(sale.calls('GET', '/fan/orders/$orderId'), hasLength(settled), reason: 'PAID stops the poll');

    final ticketsBefore = sale.calls('GET', '/fan/tickets').length;
    await tester.tap(find.byKey(const Key('order.seeTickets')));
    await tester.pumpAndSettle();
    expect(sale.calls('GET', '/fan/tickets'), hasLength(greaterThan(ticketsBefore)));
    expect(find.text('Atlas FC vs Rapid Oued'), findsWidgets);
  });

  testWidgets('pay now by card or wallet while the code waits; hidden while that attempt is PENDING', (tester) async {
    sale
      ..status = 'AWAITING_CASH'
      ..paymentMethod = 'CASH_AGENCY'
      ..paymentStatus = 'PENDING'
      ..reservedUntil = clock.now.add(const Duration(hours: 20));
    await open(tester, '/orders/$orderId');
    expect(find.byKey(const Key('cash.pay.CARD')), findsOneWidget);
    expect(find.byKey(const Key('cash.pay.WALLET')), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('cash.pay.WALLET')));
    await tester.tap(find.byKey(const Key('cash.pay.WALLET')));
    await tester.pumpAndSettle();
    expect(sale.paidWith(), ['WALLET']);
    expect(launched, [redirect]);

    // The latest attempt is a WALLET still PENDING: no second hosted payment, the code stays.
    expect(find.byKey(const Key('cash.pay.CARD')), findsNothing);
    expect(find.byKey(const Key('cash.pay.WALLET')), findsNothing);
    expect(find.byKey(const Key('cash.hostedPending')), findsOneWidget);
    expect(find.byKey(const Key('cash.code')), findsOneWidget);

    // Past the late-success window the API reports it FAILED: the alternatives come back.
    sale.paymentStatus = 'FAILED';
    await tester.pump(const Duration(seconds: 10));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('cash.pay.CARD')), findsOneWidget);
  });

  testWidgets('EXPIRED: "Code expiré · places libérées", back to the event, and the poll stops', (tester) async {
    sale
      ..status = 'AWAITING_CASH'
      ..paymentMethod = 'CASH_AGENCY'
      ..paymentStatus = 'PENDING'
      ..reservedUntil = clock.now.add(const Duration(hours: 1));
    final container = await open(tester, '/orders/$orderId');
    expect(find.byKey(const Key('cash.code')), findsOneWidget);

    sale.status = 'EXPIRED';
    await tester.pump(const Duration(seconds: 10));
    await tester.pumpAndSettle();
    expect(find.text('Code expiré · places libérées'), findsOneWidget);
    expect(find.byKey(const Key('cash.code')), findsNothing);
    final reads = sale.calls('GET', '/fan/orders/$orderId').length;
    await tester.pump(const Duration(seconds: 30));
    expect(sale.calls('GET', '/fan/orders/$orderId'), hasLength(reads));

    await tester.tap(find.text("Retour à l'événement"));
    await tester.pumpAndSettle();
    expect(container.read(routerProvider).routerDelegate.currentConfiguration.uri.path, '/events/1');
  });

  testWidgets('the deadline passing on server time shows the expired copy before the sweep', (tester) async {
    sale
      ..status = 'AWAITING_CASH'
      ..paymentMethod = 'CASH_AGENCY'
      ..paymentStatus = 'PENDING'
      ..reservedUntil = clock.now.add(const Duration(seconds: 5));
    await open(tester, '/orders/$orderId');
    expect(find.byKey(const Key('cash.code')), findsOneWidget);
    clock.advance(const Duration(seconds: 10));
    await tester.pump(const Duration(seconds: 10));
    await tester.pumpAndSettle();
    expect(find.text('Code expiré · places libérées'), findsOneWidget);
  });

  void waitingCode() {
    sale
      ..status = 'AWAITING_CASH'
      ..paymentMethod = 'CASH_AGENCY'
      ..paymentStatus = 'PENDING'
      ..reservedUntil = clock.now.add(const Duration(hours: 20));
  }

  testWidgets('after a hosted attempt, a slow or failing read never brings pay-now back from the old order', (
    tester,
  ) async {
    waitingCode();
    await open(tester, '/orders/$orderId');
    expect(find.byKey(const Key('cash.pay.WALLET')), findsOneWidget);

    sale.orderGate = Completer();
    await tester.tap(find.byKey(const Key('cash.pay.WALLET')));
    await tester.pump();
    await tester.pump();
    expect(launched, [redirect]);
    // The read after the attempt is slow: the old order (CASH pending, pay-now allowed) is still on screen.
    expect(find.byKey(const Key('cash.code')), findsOneWidget);
    expect(find.byKey(const Key('cash.pay.CARD')), findsNothing);
    expect(find.byKey(const Key('cash.hostedPending')), findsOneWidget);

    // That read fails (502): the old order stays, pay-now stays hidden.
    sale.orderFailures.add(502);
    final gate = sale.orderGate!;
    sale.orderGate = null;
    gate.complete();
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('cash.code')), findsOneWidget);
    expect(find.byKey(const Key('cash.pay.CARD')), findsNothing);

    // The next read answers: the WALLET attempt is PENDING, still hidden; once reported FAILED, back.
    await tester.pump(const Duration(seconds: 10));
    await tester.pump();
    expect(find.byKey(const Key('cash.pay.CARD')), findsNothing);
    sale.paymentStatus = 'FAILED';
    await tester.pump(const Duration(seconds: 10));
    await tester.pump();
    expect(find.byKey(const Key('cash.pay.CARD')), findsOneWidget);
    expect(sale.paidWith(), ['WALLET']);
  });

  testWidgets('a 502 keeps the poll going; the next read is PAID', (tester) async {
    waitingCode();
    await open(tester, '/orders/$orderId');
    sale.orderFailures.add(502);
    await tester.pump(const Duration(seconds: 10));
    await tester.pump();
    expect(find.byKey(const Key('cash.code')), findsOneWidget, reason: 'the last good read stays');
    sale
      ..status = 'PAID'
      ..paymentStatus = 'SUCCEEDED';
    await tester.pump(const Duration(seconds: 10));
    await tester.pumpAndSettle();
    expect(find.text("C'est validé !"), findsOneWidget);
  });

  testWidgets('a 404 stops the poll; leaving the screen stops it too', (tester) async {
    waitingCode();
    final container = await open(tester, '/orders/$orderId');
    container.read(routerProvider).go('/');
    await tester.pumpAndSettle();
    final afterLeave = sale.calls('GET', '/fan/orders/$orderId').length;
    await tester.pump(const Duration(seconds: 60));
    expect(sale.calls('GET', '/fan/orders/$orderId'), hasLength(afterLeave), reason: 'disposed: no poll');

    container.read(routerProvider).go('/orders/$orderId');
    await tester.pumpAndSettle();
    sale.orderFailures.add(404);
    await tester.pump(const Duration(seconds: 10));
    await tester.pump();
    final after404 = sale.calls('GET', '/fan/orders/$orderId').length;
    await tester.pump(const Duration(seconds: 60));
    expect(sale.calls('GET', '/fan/orders/$orderId'), hasLength(after404));
  });

  testWidgets('a cash /pay answered after the local hold hit 0: the code screen, not "Réservation expirée"', (
    tester,
  ) async {
    sale
      ..payGate = Completer()
      ..reservedUntil = clock.now.add(const Duration(seconds: 10));
    await open(tester, checkout);
    await pick(tester, 'CASH_AGENCY');
    await pay(tester);
    await tester.pump(const Duration(seconds: 11)); // The hold (10 s here) ends; /pay is still in flight.
    await tester.pump();
    expect(find.text('Réservation expirée'), findsNothing);
    sale.payGate!.complete();
    await tester.pumpAndSettle();
    expect(find.text('Réservation expirée'), findsNothing);
    expect(tester.widget<Text>(find.byKey(const Key('cash.code'))).data, 'JMH 4F7K 29');
  });

  testWidgets('a cash /pay refused after the local hold hit 0: then the modal', (tester) async {
    sale
      ..payGate = Completer()
      ..reservedUntil = clock.now.add(const Duration(seconds: 10))
      ..cashRefusal = 'TOO_LATE';
    await open(tester, checkout);
    await pick(tester, 'CASH_AGENCY');
    await pay(tester);
    await tester.pump(const Duration(seconds: 11)); // The hold (10 s here) ends; /pay is still in flight.
    await tester.pump();
    expect(find.text('Réservation expirée'), findsNothing);
    sale.payGate!.complete();
    await tester.pumpAndSettle();
    expect(find.text('Réservation expirée'), findsOneWidget);
  });

  testWidgets('the cash screen lays out at 200 % text, and right to left (AR) with the code still LTR', (tester) async {
    tester.view
      ..physicalSize = const Size(390, 844)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    sale
      ..status = 'AWAITING_CASH'
      ..paymentMethod = 'CASH_AGENCY'
      ..paymentStatus = 'PENDING'
      ..reservedUntil = clock.now.add(const Duration(hours: 20));
    await open(tester, '/orders/$orderId', tall: false);
    expect(tester.takeException(), isNull);
    final list = find.byType(Scrollable).first;
    await tester.scrollUntilVisible(find.byKey(const Key('cash.code')), 200, scrollable: list);
    expect(tester.widget<Text>(find.byKey(const Key('cash.code'))).data, 'JMH 4F7K 29');
    await tester.scrollUntilVisible(find.byKey(const Key('cash.minutes')), 200, scrollable: list);
    // 20 h minus the first frames: 19 h 59 min.
    expect(find.descendant(of: find.byKey(const Key('cash.hours')), matching: find.text('19')), findsOneWidget);
    expect(find.descendant(of: find.byKey(const Key('cash.minutes')), matching: find.text('59')), findsOneWidget);
    expect(tester.takeException(), isNull);
    // Every block down to the status line, scrolled into view one by one (the list is lazy).
    await tester.scrollUntilVisible(
      find.byKey(const Key('cash.status')),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(tester.takeException(), isNull);
    tester.platformDispatcher.localesTestValue = [const Locale('ar')];
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.byKey(const Key('cash.code')),
      -200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(tester.takeException(), isNull);
    expect(Directionality.of(tester.element(find.byKey(const Key('cash.code')))), TextDirection.rtl);
    expect(tester.widget<Text>(find.byKey(const Key('cash.code'))).textDirection, TextDirection.ltr);
  });

  test('no hard-coded copy in the checkout widgets: every Text reads l10n', () {
    final literal = RegExp(r'''\b(Text|SnackBar|Tooltip)\(\s*['"][^'"$]*[A-Za-z]''');
    final offenders = <String>[];
    for (final file in Directory('lib/features/checkout').listSync().whereType<File>()) {
      final lines = file.readAsLinesSync();
      for (var i = 0; i < lines.length; i++) {
        if (literal.hasMatch(lines[i])) offenders.add('${file.path}:${i + 1}');
      }
    }
    expect(offenders, isEmpty);
  });
}
