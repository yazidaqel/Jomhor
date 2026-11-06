import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/features/checkout/checkout_controller.dart';
import 'package:jomhor_fan/features/events/event.dart';

/// Screen 3 selection: one zone, one of its price categories, a number of places.
typedef ZonePickState = ({Zone? zone, Price? price, int qty});

class ZonePick extends Notifier<ZonePickState> {
  new(this.eventId);

  final int eventId;

  @override
  ZonePickState build() => (zone: null, price: null, qty: 0);

  /// The cheapest category still open in [zone], one place.
  void selectZone(Zone zone, {required int perAccountLimit}) {
    final open = [...zone.prices.where((p) => p.remaining > 0)]..sort((a, b) => a.priceMad.compareTo(b.priceMad));
    if (open.isEmpty) return;
    state = (zone: zone, price: open.first, qty: _clamp(1, open.first, perAccountLimit));
  }

  void selectPrice(Price price, {required int perAccountLimit}) {
    if (price.remaining == 0) return;
    state = (zone: state.zone, price: price, qty: _clamp(state.qty, price, perAccountLimit));
  }

  void step(int by, {required int perAccountLimit}) {
    final price = state.price;
    if (price == null) return;
    state = (zone: state.zone, price: price, qty: _clamp(state.qty + by, price, perAccountLimit));
  }

  static int _clamp(int qty, Price price, int limit) {
    final most = maxPlaces(perAccountLimit: limit, remaining: price.remaining);
    return qty < 1 ? (most == 0 ? 0 : 1) : (qty > most ? most : qty);
  }
}

final NotifierProviderFamily<ZonePick, ZonePickState, int> zonePickProvider = NotifierProvider.autoDispose
    .family<ZonePick, ZonePickState, int>(ZonePick.new);
