import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/passes/pass.dart';

class PassesRepository {
  new(this._api, this._now);

  final ApiClient _api;
  final DateTime Function() _now;

  /// `GET /fan/products/{id}` (public: no token sent); 404 when unknown or off sale.
  Future<Product> product(String id) async {
    final res = await _api.getPublicResponse('/fan/products/${Uri.encodeComponent(id)}');
    return Product.fromJson(res.body! as Map<String, Object?>, fetchedAt: res.date ?? _now());
  }

  /// The caller's passes that aren't `CANCELLED`, newest first.
  Future<List<FanPass>> passes() async => [
    for (final p in ((await _api.get('/fan/passes'))! as Map<String, Object?>)['passes']! as List<Object?>)
      FanPass.fromJson(p! as Map<String, Object?>),
  ];

  /// `POST /fan/product-orders`: a `RESERVED` order held 8 min, paid like any order (`/fan/orders/{id}/pay`).
  Future<FanOrder> order(String productId, String holderName) async => FanOrder.fromJson(
    (await _api.post(
          '/fan/product-orders',
          body: {'product_id': productId, 'channel': 'APP', 'holder_name': holderName},
        ))!
        as Map<String, Object?>,
  );
}

final passesRepositoryProvider = Provider<PassesRepository>(
  (ref) => PassesRepository(ref.watch(apiClientProvider), ref.watch(nowProvider)),
);

/// Screen 13 buy, the product variant of screen 4 and the owned view's announced events.
final FutureProviderFamily<Product, String> productProvider = FutureProvider.autoDispose.family<Product, String>(
  (ref, id) => ref.watch(passesRepositoryProvider).product(id),
);

/// Billets "Mes abonnements" and screen 13 owned. Signed out: none, with no call. Read on open and on pull to refresh.
final FutureProvider<List<FanPass>> passesProvider = FutureProvider.autoDispose<List<FanPass>>((ref) async {
  if (!ref.watch(sessionProvider)) return const [];
  return await ref.watch(passesRepositoryProvider).passes();
});
