import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/checkout_repository.dart';
import 'package:jomhor_fan/features/checkout/order.dart';

import 'cash_order_test.dart' show CashSale, orderId;
import 'helpers.dart';

/// FL-16: screen 1's amber strip from `GET /fan/orders` (docs/04-api.md §Fan) → the cash order screen.
void main() {
  late TestClock clock;
  late CashSale sale;

  /// `GET /fan/orders` answers these rows after [sale]'s own order; null = the network is down for that path.
  late List<Map<String, Object?>>? others;
  late bool offline;

  /// How far the device clock runs ahead of the server's `Date` on `GET /fan/orders`.
  late Duration deviceAhead;

  setUp(() {
    clock = TestClock(DateTime.utc(2027, 2, 20, 12));
    sale = CashSale(clock);
    others = [];
    offline = false;
    deviceAhead = Duration.zero;
  });

  Map<String, Object?> row({
    String id = orderId,
    String status = 'AWAITING_CASH',
    String title = 'Atlas FC vs Rapid Oued',
    String startsAt = '2027-03-13T19:00:00Z',
    DateTime? until,
    bool code = true,
  }) => {
    'id': id,
    'event': {'id': 1, 'title': title, 'starts_at': startsAt, 'timezone': 'Africa/Casablanca'},
    'channel': 'APP',
    'status': status,
    'total_mad': 10000,
    'reserved_until': (until ?? sale.reservedUntil).toIso8601String(),
    'payment_method': status == 'AWAITING_CASH' ? 'CASH_AGENCY' : null,
    'pay_code': status == 'AWAITING_CASH' && code ? sale.payCode : null,
    'created_at': '2027-02-20T11:00:00Z',
  };

  Future<http.Response> backend(http.Request r) async {
    if (r.method == 'GET' && r.url.path == '/v1/fan/orders') {
      sale.requests.add(r);
      if (offline) throw http.ClientException('offline');
      return http.Response.bytes(
        utf8.encode(
          jsonEncode({
            'orders': [row(status: sale.status), ...?others],
          }),
        ),
        200,
        headers: {'content-type': 'application/json', 'date': HttpDate.format(clock.now.subtract(deviceAhead))},
      );
    }
    return await sale.handle(r);
  }

  Future<ProviderContainer> open(WidgetTester tester, {bool signedIn = true}) => pumpJomhor(
    tester,
    backend: backend,
    tokens: MemoryTokenStore(signedIn ? 'jwt' : null),
    clock: clock,
    overrides: [cardPaymentLauncherProvider.overrideWithValue((context, url, id) async => false)],
  );

  Finder strip() => find.byKey(const Key('home.cashOrder'));
  int listReads() => sale.calls('GET', '/fan/orders').length;
  String deadline(DateTime until) => venueWhen(until, 'Africa/Casablanca', 'fr');

  void awaitingCash() {
    sale
      ..status = 'AWAITING_CASH'
      ..paymentMethod = 'CASH_AGENCY'
      ..paymentStatus = 'PENDING'
      ..reservedUntil = clock.now.add(const Duration(hours: 24));
  }

  test('only AWAITING_CASH of an upcoming event with time left; the soonest deadline wins', () {
    final now = DateTime.utc(2027, 2, 20, 12);
    OrderRow r(
      String id,
      String status, {
      Duration startsIn = const Duration(days: 20),
      Duration? left,
      bool code = true,
    }) => OrderRow.fromJson({
      'id': id,
      'event': {'id': 1, 'title': 'E', 'starts_at': now.add(startsIn).toIso8601String(), 'timezone': 'UTC'},
      'status': status,
      'reserved_until': now.add(left ?? const Duration(hours: 10)).toIso8601String(),
      'pay_code': code ? '4F7K29' : null,
    });
    expect(pendingCash([r('a', 'RESERVED'), r('b', 'PAID'), r('c', 'EXPIRED'), r('d', 'CANCELLED')], now), isNull);
    expect(pendingCash([r('past', 'AWAITING_CASH', startsIn: const Duration(hours: -1))], now), isNull);
    expect(pendingCash([r('over', 'AWAITING_CASH', left: Duration.zero)], now), isNull, reason: 'the expiry job lags');
    expect(pendingCash([r('nocode', 'AWAITING_CASH', code: false)], now), isNull, reason: 'no PENDING code to pay');
    final soonest = pendingCash([
      r('late', 'AWAITING_CASH', left: const Duration(hours: 20)),
      r('soon', 'AWAITING_CASH', left: const Duration(hours: 3)),
      r('reserved', 'RESERVED', left: const Duration(minutes: 5)),
    ], now);
    expect(soonest?.id, 'soon');
  });

  testWidgets('an AWAITING_CASH order shows the amber strip with its deadline in the venue zone', (tester) async {
    awaitingCash();
    others = [row(id: 'other', status: 'PAID')];
    await open(tester);
    expect(strip(), findsOneWidget);
    expect(find.text('Commande en attente de paiement'), findsOneWidget);
    expect(
      find.text('Atlas FC vs Rapid Oued · Code cash à payer avant ${deadline(sale.reservedUntil)}'),
      findsOneWidget,
    );
  });

  testWidgets('a device clock 2 days ahead: the deadline is judged on the server Date, the strip shows', (
    tester,
  ) async {
    awaitingCash(); // 24 h left on the server.
    deviceAhead = const Duration(days: 2);
    clock.now = clock.now.add(deviceAhead);
    await open(tester);
    expect(strip(), findsOneWidget);
  });

  testWidgets('the strip lays out at 200 % text on a small phone and announces itself as a button', (tester) async {
    tester.view
      ..physicalSize = const Size(390, 844)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final semantics = tester.ensureSemantics();
    awaitingCash();
    await open(tester);
    expect(tester.takeException(), isNull);
    expect(strip(), findsOneWidget);
    expect(tester.getSemantics(strip()), isSemantics(isButton: true, hasTapAction: true));
    semantics.dispose();
  });

  testWidgets('no strip for a RESERVED order, a past event, a deadline passed, or no code', (tester) async {
    others = [
      row(id: 'nocode', code: false),
      row(id: 'past', startsAt: '2027-02-19T19:00:00Z', until: clock.now.add(const Duration(hours: 1))),
      row(id: 'over', until: clock.now.subtract(const Duration(minutes: 1))),
    ];
    await open(tester); // sale.status is RESERVED
    expect(listReads(), 1);
    expect(strip(), findsNothing);
  });

  testWidgets('tapping the strip opens the cash order screen with the code', (tester) async {
    awaitingCash();
    final c = await open(tester);
    await tester.tap(strip());
    await tester.pumpAndSettle();
    expect(c.read(routerProvider).state.uri.path, '/orders/$orderId');
    expect(find.byKey(const Key('cash.code')), findsOneWidget);
    expect(find.text('JMH 4F7K 29'), findsOneWidget);
  });

  testWidgets('offline or a server error: no strip, the home still renders', (tester) async {
    awaitingCash();
    offline = true;
    await open(tester);
    expect(listReads(), 1);
    expect(strip(), findsNothing);
    expect(find.byKey(const Key('home.greeting')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('signed out: no orders read, no strip', (tester) async {
    awaitingCash();
    await open(tester, signedIn: false);
    expect(listReads(), 0);
    expect(strip(), findsNothing);
  });

  testWidgets('back from the background, the list is read again: a code paid meanwhile drops the strip', (
    tester,
  ) async {
    awaitingCash();
    await open(tester);
    expect(strip(), findsOneWidget);

    sale.status = 'PAID'; // Paid at the guichet while the app slept.
    [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ].forEach(tester.binding.handleAppLifecycleStateChanged);
    await tester.pumpAndSettle();
    expect(listReads(), 2);
    expect(strip(), findsNothing);
  });

  testWidgets('the order screen seeing the code paid re-reads the list: home has no strip after', (tester) async {
    awaitingCash();
    final c = await open(tester);
    await tester.tap(strip());
    await tester.pumpAndSettle();
    final before = listReads();

    sale.status = 'PAID';
    await tester.pump(const Duration(seconds: 10)); // The cash screen polls every 10 s.
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('cash.code')), findsNothing, reason: 'the order screen shows PAID');
    // The home under the order screen is paused (not visible): it reads the list again once shown.
    c.read(routerProvider).go('/');
    await tester.pumpAndSettle();
    expect(listReads(), greaterThan(before));
    expect(strip(), findsNothing);
  });

  testWidgets('leaving screen 4 while the cash /pay is in flight: the strip still refreshes', (tester) async {
    final c = await open(tester);
    tester.view
      ..physicalSize = const Size(800, 2400)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    c.read(routerProvider).go('/events/1/checkout?price=pc-1-0&qty=2');
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('holder.1.name')), 'Sara Benali');
    await tester.tap(find.byKey(const Key('method.CASH_AGENCY')));
    await tester.pump();
    sale.payGate = Completer();
    await tester.ensureVisible(find.byKey(const Key('checkout.pay')));
    await tester.tap(find.byKey(const Key('checkout.pay')));
    await tester.pump();
    await tester.pump();
    expect(sale.paidWith(), ['CASH_AGENCY']);

    c.read(routerProvider).go('/'); // Screen 4 (and its controller) goes while /pay waits.
    await tester.pumpAndSettle();
    expect(strip(), findsNothing);
    final before = listReads();
    sale.payGate!.complete(); // The code is issued.
    await tester.pumpAndSettle();
    expect(listReads(), before + 1);
    expect(strip(), findsOneWidget);
  });

  testWidgets('a cash /pay whose answer is lost still leaves the code reachable from the home', (tester) async {
    Future<http.Response> lostAnswer(http.Request r) async {
      if (r.method == 'POST' && r.url.path == '/v1/fan/orders/$orderId/pay') {
        sale
          ..requests.add(r)
          ..pay(jsonDecode(r.body) as Map<String, Object?>); // The server issued the code...
        throw http.ClientException('timeout'); // ...but the answer never came back.
      }
      return await backend(r);
    }

    final c = await pumpJomhor(
      tester,
      backend: lostAnswer,
      tokens: MemoryTokenStore('jwt'),
      clock: clock,
      overrides: [cardPaymentLauncherProvider.overrideWithValue((context, url, id) async => false)],
    );
    tester.view
      ..physicalSize = const Size(800, 2400)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    c.read(routerProvider).go('/events/1/checkout?price=pc-1-0&qty=2');
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('holder.1.name')), 'Sara Benali');
    await tester.tap(find.byKey(const Key('method.CASH_AGENCY')));
    await tester.pump();
    await tester.ensureVisible(find.byKey(const Key('checkout.pay')));
    await tester.tap(find.byKey(const Key('checkout.pay')));
    await tester.pumpAndSettle();
    expect(sale.paidWith(), ['CASH_AGENCY']);

    c.read(routerProvider).go('/');
    await tester.pumpAndSettle();
    expect(strip(), findsOneWidget);
    await tester.tap(strip());
    await tester.pumpAndSettle();
    expect(find.text('JMH 4F7K 29'), findsOneWidget);
  });
}
