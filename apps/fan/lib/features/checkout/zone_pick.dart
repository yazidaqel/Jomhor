import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/features/checkout/checkout_controller.dart';
import 'package:jomhor_fan/features/checkout/layouts.g.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/queue/queue_store.dart';
import 'package:jomhor_fan/features/queue/room_cta.dart';

/// Screen 3 selection: one zone, one of its price categories, a number of places.
typedef ZonePickState = ({Zone? zone, Price? price, int qty});

class ZonePick extends Notifier<ZonePickState> {
  new(this.eventId);

  final int eventId;

  @override
  ZonePickState build() => (zone: null, price: null, qty: 0);

  /// [Zone.firstOpen] of [zone], one place.
  void selectZone(Zone zone, {required int perAccountLimit}) {
    final first = zone.firstOpen;
    if (first == null) return;
    state = (zone: zone, price: first, qty: _clamp(1, first, perAccountLimit));
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

/// A slot's look on the venue map (DESIGN.md §Venue map adjustments, screen 3).
enum BlockState {
  /// No listed zone on it: drawn, never focusable, hidden from semantics.
  inert,
  open,
  selected,
  soldOut,

  /// The sale isn't open, or the room hasn't given this fan a turn: not tappable, colours kept.
  closed,
}

/// One slot: `zone` and its `index` among the event's zones (the colour), or null and -1 when inert.
typedef MapBlock = ({String slot, MapRect rect, Zone? zone, int index, BlockState state});

/// Screen 3's venue map; null when the screen is the list only: the layout is null or unknown to this build, or no
/// listed zone sits on one of its slots (docs/04-api.md §Venue map, "unknown means unmapped"). Blocks in the JSON order.
({VenueLayout layout, List<MapBlock> blocks})? mapView(
  EventDetail detail, {
  required int? selectedZoneId,
  required DateTime now,
  QueuePass? pass,
}) {
  final layout = VenueLayouts.all[detail.layout];
  if (layout == null) return null;
  final open =
      detail.event.saleState(now) == SaleState.onSale &&
      switch (roomCta(detail.event, now, pass)) {
        null || RoomCta.choose => true,
        _ => false,
      };
  final blocks = <MapBlock>[
    for (final (slot, rect) in layout.slots)
      if (detail.zones.indexWhere((z) => z.mapSlot == slot) case final i)
        (
          slot: slot,
          rect: rect,
          zone: i < 0 ? null : detail.zones[i],
          index: i,
          state: switch (i < 0 ? null : detail.zones[i]) {
            null => BlockState.inert,
            final z when z.soldOut => BlockState.soldOut,
            _ when !open => BlockState.closed,
            final z when z.id == selectedZoneId => BlockState.selected,
            _ => BlockState.open,
          },
        ),
  ];
  return blocks.every((b) => b.zone == null) ? null : (layout: layout, blocks: blocks);
}
