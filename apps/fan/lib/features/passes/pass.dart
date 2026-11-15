// Read-only DTOs for docs/04-api.md §Products and §Passes (`GET /fan/products*`, `GET /fan/passes`), hand-written
// like events/event.dart. Amounts are the server's centimes, shown as sent: no money maths here.

import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/clubs/club.dart';

/// A product id or pass id as the API writes it (lowercase UUID). Route parameters are checked against it.
final _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

bool isUuid(String id) => _uuid.hasMatch(id);

/// `Africa/Casablanca`: a membership's zone (docs/04-api.md §Notifications), and any product read that has no venue.
const homeZone = 'Africa/Casablanca';

typedef Series = ({String id, String name, DateTime startsOn, DateTime endsOn});
typedef PassZone = ({int id, String code, String name});

/// One `pass.events` row: a published, upcoming event of the series at the zone's venue.
typedef PassEvent = ({int id, String title, String eventType, DateTime startsAt});

/// One `events` row of `GET /fan/passes`: an event of the season not `CLOSED`, and the holder's pass ticket for it
/// (null until issued).
typedef SeasonEvent = ({int id, String title, String eventType, DateTime startsAt, String? ticketId});

/// A `GET /fan/products*` row. [fetchedAt]: server time of the read (the response `Date`).
class Product {
  new({
    required this.id,
    required this.organizerId,
    required this.club,
    required this.timezone,
    required this.kind,
    required this.name,
    required this.priceMad,
    required this.salesCloseAt,
    required this.soldOut,
    required this.fetchedAt,
    this.remaining,
    this.validUntil,
    this.pass,
    this.firstAmountMad,
    this.paymentMethods = const [PayMethod.card],
    this.cashUntil,
  });

  factory fromJson(Map<String, Object?> j, {required DateTime fetchedAt}) => Product(
    id: j['id']! as String,
    organizerId: j['organizer_id']! as String,
    club: Club.fromJson(j['organizer']! as Map<String, Object?>, id: j['organizer_id']! as String),
    timezone: j['timezone'] as String? ?? homeZone,
    kind: j['kind']! as String,
    name: j['name']! as String,
    priceMad: j['price_mad']! as int,
    salesCloseAt: DateTime.parse(j['sales_close_at']! as String).toUtc(),
    remaining: j['remaining'] as int?,
    soldOut: j['sold_out'] as bool? ?? false,
    validUntil: _instant(j['valid_until']),
    pass: switch (j['pass']) {
      final Map<String, Object?> p => ProductPass.fromJson(p),
      _ => null,
    },
    // `plans[0]` is "en une fois" (3a); its first amount is what the CTA says.
    firstAmountMad: switch (j['plans']) {
      [{'amounts_mad': [final int first, ...]}, ...] => first,
      _ => null,
    },
    paymentMethods: switch (j['payment_methods']) {
      final List<Object?> list => [...list.map(PayMethod.of).nonNulls],
      _ => const [PayMethod.card],
    },
    cashUntil: _instant(j['cash_until']),
    fetchedAt: fetchedAt,
  );

  final String id;
  final String organizerId;

  /// The selling club: crest and name only (content, never a colour).
  final Club club;

  /// The zone venue's time zone for a pass, Morocco's for a membership: every date of the product shows in it.
  final String timezone;

  /// `PASS` or `MEMBERSHIP`.
  final String kind;
  final String name;
  final int priceMad;
  final DateTime salesCloseAt;

  /// `PASS`: `quota − sold`; null for `MEMBERSHIP`.
  final int? remaining;
  final bool soldOut;

  /// `MEMBERSHIP`: the `paid_until` it grants.
  final DateTime? validUntil;

  /// `PASS` only.
  final ProductPass? pass;

  /// The first amount of the one-payment plan, as the server sent it.
  final int? firstAmountMad;

  /// What `/pay` takes for this product now, as `GET /fan/events/{id}`.
  final List<PayMethod> paymentMethods;
  final DateTime? cashUntil;
  final DateTime fetchedAt;

  bool get isPass => kind == 'PASS';

  /// What the buy CTA charges now: the plan's first amount, else the price (both from the server).
  int get chargeMad => firstAmountMad ?? priceMad;
}

/// `pass` of a PASS product: the series, the zone, its venue and the series' events announced there.
class ProductPass {
  new({
    required this.series,
    required this.zone,
    required this.venueName,
    required this.timezone,
    required this.events,
    this.venueCity,
  });

  factory fromJson(Map<String, Object?> j) {
    final venue = j['venue']! as Map<String, Object?>;
    return ProductPass(
      series: _series(j['series']! as Map<String, Object?>),
      zone: _zone(j['zone']! as Map<String, Object?>),
      venueName: venue['name']! as String,
      venueCity: venue['city'] as String?,
      timezone: venue['timezone'] as String? ?? homeZone,
      events: [
        for (final e in (j['events'] as List<Object?>? ?? const []).cast<Map<String, Object?>>())
          (
            id: e['id']! as int,
            title: e['title']! as String,
            eventType: e['event_type']! as String,
            startsAt: DateTime.parse(e['starts_at']! as String).toUtc(),
          ),
      ],
    );
  }

  final Series series;
  final PassZone zone;
  final String venueName;
  final String? venueCity;
  final String timezone;
  final List<PassEvent> events;

  /// "matchs" rather than "événements" (UI copy only): every announced event is a match.
  bool get matches => events.every((e) => e.eventType == 'FOOTBALL_MATCH');
}

/// A `GET /fan/passes` row: one of the caller's passes that isn't `CANCELLED`. Labels only, never a phone.
class FanPass {
  new({
    required this.id,
    required this.status,
    required this.productId,
    required this.productName,
    required this.club,
    required this.series,
    required this.zone,
    required this.holderLabel,
    this.orderId,
    this.events = const [],
  });

  factory fromJson(Map<String, Object?> j) {
    final product = j['product']! as Map<String, Object?>;
    return FanPass(
      id: j['id']! as String,
      status: j['status']! as String,
      productId: product['id']! as String,
      productName: product['name']! as String,
      club: Club.fromJson(j['organizer']! as Map<String, Object?>),
      series: _series(j['series']! as Map<String, Object?>),
      zone: _zone(j['zone']! as Map<String, Object?>),
      holderLabel: j['holder_label']! as String,
      orderId: j['order_id'] as String?,
      events: [
        for (final e in (j['events'] as List<Object?>? ?? const []).cast<Map<String, Object?>>())
          (
            id: e['id']! as int,
            title: e['title']! as String,
            eventType: e['event_type']! as String,
            startsAt: DateTime.parse(e['starts_at']! as String).toUtc(),
            ticketId: e['ticket_id'] as String?,
          ),
      ],
    );
  }

  final String id;

  /// `PENDING`, `ACTIVE` (3b: `SUSPENDED`).
  final String status;
  final String productId;
  final String productName;
  final Club club;
  final Series series;
  final PassZone zone;
  final String holderLabel;

  /// The purchase order while `PENDING` (to resume payment), else null.
  final String? orderId;

  /// The season's events at the zone's venue, by start, each with its ticket once issued.
  final List<SeasonEvent> events;

  bool get pending => status == 'PENDING';

  /// "matchs" rather than "événements" (UI copy only).
  bool get matches => events.every((e) => e.eventType == 'FOOTBALL_MATCH');
}

Series _series(Map<String, Object?> s) => (
  id: s['id']! as String,
  name: s['name']! as String,
  startsOn: DateTime.parse(s['starts_on']! as String),
  endsOn: DateTime.parse(s['ends_on']! as String),
);

PassZone _zone(Map<String, Object?> z) => (id: z['id']! as int, code: z['code']! as String, name: z['name']! as String);

DateTime? _instant(Object? v) => v is String ? DateTime.parse(v).toUtc() : null;
