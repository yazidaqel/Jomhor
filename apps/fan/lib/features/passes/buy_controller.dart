import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/checkout/checkout_controller.dart' show meProvider;
import 'package:jomhor_fan/features/checkout/holder_name.dart';
import 'package:jomhor_fan/features/checkout/order_status.dart' show unexpectedAnswer;
import 'package:jomhor_fan/features/club/club_repository.dart' show membershipProvider;
import 'package:jomhor_fan/features/passes/passes_repository.dart';

/// Screen 13 buy: the holder name refused ([badName]), the product sold out ([soldOut]) or its sales closed
/// ([closed]) since it was read, or the error to say under the CTA.
class BuyState {
  const new({this.busy = false, this.badName = false, this.soldOut = false, this.closed = false, this.error});

  final bool busy;
  final bool badName;
  final bool soldOut;

  /// `PRODUCT_NOT_ON_SALE`: the CTA goes off, its copy stays under it.
  final bool closed;
  final ApiError? error;
}

/// "Acheter": checks the name as the server will (§Holder names), then `POST /fan/product-orders`.
class BuyController extends Notifier<BuyState> {
  new(this.productId);

  final String productId;

  @override
  BuyState build() => const BuyState();

  /// Screen 7's "Payer ma cotisation", "Renouveler" and the join sheet's cotisation: [buy] in the fan's own name
  /// (`/fan/me` `display_name`). With no name the server would take, screen 13 (`/products/{id}`) asks for one.
  Future<String?> buyAsMe() async {
    if (state.busy) return null;
    final name = await ref.read(meProvider.future).then((me) => me.displayName, onError: (Object _) => null);
    if (!ref.mounted) return null;
    if (name == null || normalizeHolderName(name) == null) return '/products/$productId';
    return await buy(name);
  }

  /// Where to go next: the order to pay (`/orders/{id}`, screen 4 product variant), or the pass already held
  /// (`PASS_HELD` → `/passes/{id}`, screen 13 owned); null when the state says what is wrong.
  Future<String?> buy(String rawName) async {
    if (state.busy || state.soldOut || state.closed) return null;
    final name = normalizeHolderName(rawName);
    if (name == null) {
      state = const BuyState(badName: true);
      return null;
    }
    state = const BuyState(busy: true);
    try {
      final order = await ref.read(passesRepositoryProvider).order(productId, name);
      if (!ref.mounted) return null;
      ref.invalidate(passesProvider); // A PENDING pass now: Billets lists it.
      state = const BuyState();
      return '/orders/${order.id}';
    } on ApiError catch (e) {
      if (!ref.mounted) return null;
      switch ((e.code, e.details)) {
        case ('PASS_HELD', {'pass_id': final String passId}):
          ref.invalidate(passesProvider);
          state = const BuyState();
          return '/passes/$passId';
        case ('SOLD_OUT', {'product_id': _}):
          ref.invalidate(productProvider(productId));
          state = const BuyState(soldOut: true);
        case ('MEMBERSHIP_PAID', _):
          // Paid elsewhere (another phone, the shop): the club shows it once read again.
          ref.invalidate(membershipProvider);
          state = BuyState(error: e);
        case ('PRODUCT_NOT_ON_SALE', _):
          state = BuyState(closed: true, error: e);
        case ('VALIDATION_FAILED', {'fields': {'holder_name': _}}):
          state = const BuyState(badName: true);
        default:
          state = BuyState(error: e);
      }
      return null;
    } on Object {
      if (ref.mounted) state = const BuyState(error: unexpectedAnswer);
      return null;
    }
  }
}

final NotifierProviderFamily<BuyController, BuyState, String> buyProvider = NotifierProvider.autoDispose
    .family<BuyController, BuyState, String>(BuyController.new);
