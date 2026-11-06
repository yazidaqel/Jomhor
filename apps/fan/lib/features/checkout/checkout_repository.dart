import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
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

  Future<PayStart> payByCard(String orderId) async => PayStart.fromJson(
    (await _api.post('/fan/orders/${Uri.encodeComponent(orderId)}/pay', body: {'method': 'CARD'}))!
        as Map<String, Object?>,
  );

  /// The order and the server time of the answer (`Date`): the hold is judged on server time.
  Future<(FanOrder, DateTime?)> order(String id) async {
    final res = await _api.getResponse('/fan/orders/${Uri.encodeComponent(id)}');
    return (FanOrder.fromJson(res.body! as Map<String, Object?>), res.date);
  }
}

final checkoutRepositoryProvider = Provider<CheckoutRepository>(
  (ref) => CheckoutRepository(ref.watch(apiClientProvider)),
);
