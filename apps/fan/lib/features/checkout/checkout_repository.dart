import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/checkout/order.dart';

class CheckoutRepository {
  new(this._api);

  final ApiClient _api;

  /// Holds the places for 8 min. Returns the order and the server time of the answer (`Date`), so the
  /// countdown runs on server time.
  Future<(FanOrder, DateTime?)> reserve({
    required int eventId,
    required String priceCategoryId,
    required List<HolderInput> holders,
  }) async {
    final res = await _api.postResponse(
      '/fan/orders',
      body: {
        'event_id': eventId,
        'channel': 'APP',
        'items': [
          {
            'price_category_id': priceCategoryId,
            'qty': holders.length,
            'holders': [
              for (final h in holders) {'name': h.name, 'phone': ?h.phone},
            ],
          },
        ],
      },
    );
    return (FanOrder.fromJson(res.body! as Map<String, Object?>), res.date);
  }

  /// CARD and WALLET answer the hosted page; CASH_AGENCY moves the order to AWAITING_CASH with a pay code.
  Future<PayStart> pay(String orderId, PayMethod method) async => PayStart.fromJson(
    (await _api.post('/fan/orders/${Uri.encodeComponent(orderId)}/pay', body: {'method': method.wire}))!
        as Map<String, Object?>,
    method,
  );

  /// The order and the server time of the answer (`Date`): the hold is judged on server time.
  Future<(FanOrder, DateTime?)> order(String id) async {
    final res = await _api.getResponse('/fan/orders/${Uri.encodeComponent(id)}');
    return (FanOrder.fromJson(res.body! as Map<String, Object?>), res.date);
  }

  /// The buyer's orders (AWAITING_CASH first, then newest, at most 20) and the server time of the answer.
  Future<(List<OrderRow>, DateTime?)> orders() async {
    final res = await _api.getResponse('/fan/orders');
    final rows = (res.body! as Map<String, Object?>)['orders']! as List<Object?>;
    return ([for (final o in rows) OrderRow.fromJson(o! as Map<String, Object?>)], res.date);
  }
}

final checkoutRepositoryProvider = Provider<CheckoutRepository>(
  (ref) => CheckoutRepository(ref.watch(apiClientProvider)),
);

/// The AWAITING_CASH order of an upcoming event whose PENDING code runs out first, judged on server time [now].
/// An AWAITING_CASH order without a code (`pay_code` null, e.g. after a network amount mismatch) is left out.
OrderRow? pendingCash(List<OrderRow> rows, DateTime now) {
  final waiting = [
    for (final o in rows)
      if (o.status == 'AWAITING_CASH' &&
          o.payCode != null &&
          o.eventStartsAt.isAfter(now) &&
          (o.reservedUntil?.isAfter(now) ?? false))
        o,
  ]..sort((a, b) => a.reservedUntil!.compareTo(b.reservedUntil!));
  return waiting.firstOrNull;
}

/// Screen 1's pending cash code: null when signed out, when there is none, offline or on any error (the strip
/// never blocks the home). Re-read on resume, on pull-to-refresh and after a payment (invalidate it).
final pendingCashProvider = FutureProvider<OrderRow?>((ref) async {
  if (!ref.watch(sessionProvider)) return null;
  try {
    final (rows, serverDate) = await ref.watch(checkoutRepositoryProvider).orders();
    return pendingCash(rows, serverDate ?? ref.read(nowProvider)());
  } on Object {
    return null;
  }
});
