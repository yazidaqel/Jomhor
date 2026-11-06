// Read-only DTOs for `GET /fan/events` and `/fan/events/{id}` (docs/04-api.md §Fan, snake_case).
// Hand-written: a few read-only shapes, no copyWith or unions needed, and nothing to keep in sync.

/// The sale state shown on screens 1 and 2. [opening]: the opening time has passed on this device
/// but the data was read before it, so we wait for a fresh read (same as the web shop's OPENING).
enum SaleState { onSale, notStarted, opening, soldOut, closed }

class FanEvent {
  new({
    required this.id,
    required this.organizerId,
    required this.fetchedAt,
    required this.eventType,
    required this.title,
    required this.startsAt,
    required this.timezone,
    required this.venueName,
    required this.venueCity,
    required this.metadata,
    required this.salesCloseAt,
    required this.onSale,
    required this.soldOut,
    required this.perAccountLimit,
    this.doorsOpenAt,
    this.saleStartsAt,
    this.fromPriceMad,
    this.status,
  });

  /// [fetchedAt]: server time of this read (the response `Date`, else the device clock).
  factory fromJson(Map<String, Object?> j, {required DateTime fetchedAt}) {
    final venue = j['venue']! as Map<String, Object?>;
    return FanEvent(
      id: j['id']! as int,
      organizerId: j['organizer_id']! as String,
      fetchedAt: fetchedAt,
      status: j['status'] as String?,
      eventType: j['event_type']! as String,
      title: j['title']! as String,
      startsAt: _instant(j['starts_at'])!,
      doorsOpenAt: _instant(j['doors_open_at']),
      timezone: j['timezone']! as String,
      venueName: venue['name']! as String,
      venueCity: venue['city'] as String?,
      metadata: j['metadata'] as Map<String, Object?>? ?? const {},
      saleStartsAt: _instant(j['sale_starts_at']),
      salesCloseAt: _instant(j['sales_close_at'])!,
      onSale: j['on_sale']! as bool,
      soldOut: j['sold_out']! as bool,
      fromPriceMad: j['from_price_mad'] as int?,
      perAccountLimit: j['per_account_limit']! as int,
    );
  }

  final int id;
  final String organizerId;
  final DateTime fetchedAt;

  /// `ON_SALE` / `GATES_OPEN` / `CLOSED` once the backend sends it (BE-8); optional until then.
  final String? status;
  final String eventType;
  final String title;
  final DateTime startsAt;
  final DateTime? doorsOpenAt;

  /// IANA zone of the venue (e.g. `Africa/Casablanca`): every displayed time goes through it.
  final String timezone;
  final String venueName;
  final String? venueCity;
  final Map<String, Object?> metadata;
  final DateTime? saleStartsAt;
  final DateTime salesCloseAt;
  final bool onSale;
  final bool soldOut;

  /// Centimes; null when nothing is left.
  final int? fromPriceMad;
  final int perAccountLimit;

  /// The server's `on_sale` decides "open". "Closed" needs proof: the cutoff has passed, or the status
  /// says so. Not on sale with the opening behind us is [SaleState.opening] (as the web shop's OPENING):
  /// a read from before the opening, or a server whose clock is a little behind the device's.
  SaleState saleState(DateTime now) {
    if (!now.isBefore(salesCloseAt)) return SaleState.closed;
    if (status != null && status != 'ON_SALE') return SaleState.closed;
    if (onSale) return soldOut ? SaleState.soldOut : SaleState.onSale;
    final opens = saleStartsAt;
    if (opens == null) return SaleState.closed;
    return now.isBefore(opens) ? SaleState.notStarted : SaleState.opening;
  }

  /// How long after [fetchedAt] to read this event again to see its sale open; null when no re-read helps.
  /// Before the opening (server time): the time left. After it but still not on sale: back off with the
  /// time already waited, 2 s minimum and 30 s maximum (2, 2, 4, 8, 16, 30, 30 s...), until on sale or closed.
  Duration? reopenDelay() {
    final opens = saleStartsAt;
    if (onSale || opens == null || (status != null && status != 'ON_SALE')) return null;
    if (!fetchedAt.isBefore(salesCloseAt)) return null;
    if (fetchedAt.isBefore(opens)) return opens.difference(fetchedAt);
    final waited = fetchedAt.difference(opens);
    return waited < _minBackoff ? _minBackoff : (waited > _maxBackoff ? _maxBackoff : waited);
  }

  static const _minBackoff = Duration(seconds: 2);
  static const _maxBackoff = Duration(seconds: 30);

  EventFacts get facts => EventFacts.of(eventType, metadata);
}

class EventDetail {
  new({required this.event, required this.zones});

  factory fromJson(Map<String, Object?> j, {required DateTime fetchedAt}) => EventDetail(
    event: FanEvent.fromJson(j, fetchedAt: fetchedAt),
    zones: [for (final z in j['zones']! as List<Object?>) Zone.fromJson(z! as Map<String, Object?>)],
  );

  final FanEvent event;
  final List<Zone> zones;
}

class Zone {
  new({
    required this.id,
    required this.code,
    required this.name,
    required this.seated,
    required this.gateCodes,
    required this.prices,
  });

  factory fromJson(Map<String, Object?> j) => Zone(
    id: j['id']! as int,
    code: j['code']! as String,
    name: j['name']! as String,
    seated: j['seated']! as bool,
    gateCodes: [for (final g in j['gate_codes']! as List<Object?>) g! as String],
    prices: [for (final p in j['prices']! as List<Object?>) Price.fromJson(p! as Map<String, Object?>)],
  );

  /// DESIGN.md Lot 1 adjustments, screen 2.
  static const lastSeatsThreshold = 50;

  final int id;
  final String code;
  final String name;
  final bool seated;
  final List<String> gateCodes;
  final List<Price> prices;

  int get remaining => prices.fold(0, (sum, p) => sum + p.remaining);
  bool get soldOut => remaining == 0;
  bool get lastSeats => !soldOut && remaining <= lastSeatsThreshold;

  /// Cheapest price still available, in centimes; null when sold out.
  int? get fromPriceMad {
    final open = [
      for (final p in prices)
        if (p.remaining > 0) p.priceMad,
    ];
    return open.isEmpty ? null : open.reduce((a, b) => a < b ? a : b);
  }
}

class Price {
  new({required this.priceCategoryId, required this.name, required this.priceMad, required this.remaining});

  factory fromJson(Map<String, Object?> j) => Price(
    priceCategoryId: j['price_category_id']! as String,
    name: j['name']! as String,
    priceMad: j['price_mad']! as int,
    remaining: j['remaining']! as int,
  );

  final String priceCategoryId;
  final String name;
  final int priceMad;
  final int remaining;
}

/// `metadata` read by `event_type` (docs/04-api.md §Event metadata). A key with the wrong type is ignored.
sealed class EventFacts {
  const new();

  factory of(String eventType, Map<String, Object?> m) => switch (eventType) {
    'FOOTBALL_MATCH' => FootballFacts(
      opponent: _as<String>(m['opponent']),
      competition: _as<String>(m['competition']),
      matchday: _as<int>(m['matchday']),
    ),
    'CONCERT' || 'FESTIVAL_DAY' => HeadlinerFacts(headliner: _as<String>(m['headliner'])),
    _ => const OtherFacts(),
  };
}

class FootballFacts extends EventFacts {
  const new({this.opponent, this.competition, this.matchday});
  final String? opponent;
  final String? competition;
  final int? matchday;
}

class HeadlinerFacts extends EventFacts {
  const new({this.headliner});
  final String? headliner;
}

class OtherFacts extends EventFacts {
  const new();
}

T? _as<T>(Object? v) => v is T ? v : null;

DateTime? _instant(Object? v) => v == null ? null : DateTime.parse(v as String).toUtc();
