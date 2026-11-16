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

  /// `GET /fan/products?organizer=` (public): the club's products on sale, passes first, then its membership, and
  /// the server time of the read (the response `Date`). A product whose sales have closed is left out.
  Future<ClubProducts> products(String organizerId) async {
    final res = await _api.getPublicResponse('/fan/products', query: {'organizer': organizerId});
    final at = res.date ?? _now();
    return (
      list: [
        for (final j in res.body! as List<Object?>)
          if (Product.fromJson(j! as Map<String, Object?>, fetchedAt: at) case final p when !p.closed) p,
      ],
      serverAt: at,
    );
  }

  /// The caller's passes that aren't `CANCELLED`, newest first.
  Future<List<FanPass>> passes() async => [
    for (final p in ((await _api.get('/fan/passes'))! as Map<String, Object?>)['passes']! as List<Object?>)
      FanPass.fromJson(p! as Map<String, Object?>),
  ];

  /// `POST /fan/product-orders`: a `RESERVED` order held 8 min, paid like any order (`/fan/orders/{id}/pay`).
  /// [installments]: one of the product's `plans` (3b); its total is that plan's first amount.
  Future<FanOrder> order(String productId, String holderName, {int installments = 1}) async => FanOrder.fromJson(
    (await _api.post(
          '/fan/product-orders',
          body: {'product_id': productId, 'channel': 'APP', 'holder_name': holderName, 'installments': installments},
        ))!
        as Map<String, Object?>,
  );

  /// `POST /fan/passes/{id}/installments` (3b): the order of the earliest unpaid installment, new (201) or the live
  /// one (200); paid like any order.
  Future<FanOrder> installmentOrder(String passId) async => FanOrder.fromJson(
    (await _api.post('/fan/passes/${Uri.encodeComponent(passId)}/installments'))! as Map<String, Object?>,
  );
}

final passesRepositoryProvider = Provider<PassesRepository>(
  (ref) => PassesRepository(ref.watch(apiClientProvider), ref.watch(nowProvider)),
);

/// Screen 13 buy, the product variant of screen 4 and the owned view's announced events.
final FutureProviderFamily<Product, String> productProvider = FutureProvider.autoDispose.family<Product, String>(
  (ref, id) => ref.watch(passesRepositoryProvider).product(id),
);

/// One club's products on sale and the server time they were read at.
typedef ClubProducts = ({List<Product> list, DateTime serverAt});

/// Screen 7 for one club: its Abonnements cards and the cotisation's product. Read while the club is shown.
final FutureProviderFamily<ClubProducts, String> clubProductsProvider = FutureProvider.autoDispose
    .family<ClubProducts, String>((ref, organizerId) => ref.watch(passesRepositoryProvider).products(organizerId));

/// Billets "Mes abonnements" and screen 13 owned. Signed out: none, with no call. Read on open and on pull to refresh.
final FutureProvider<List<FanPass>> passesProvider = FutureProvider.autoDispose<List<FanPass>>((ref) async {
  if (!ref.watch(sessionProvider)) return const [];
  return await ref.watch(passesRepositoryProvider).passes();
});
