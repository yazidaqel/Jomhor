// Read-only DTOs for `POST /fan/orders`, `GET /fan/orders/{id}` and `POST /fan/orders/{id}/pay`
// (docs/04-api.md §Fan). Hand-written like the event DTOs (the freezed decision is pending).

/// One holder row as the fan typed it, already normalised. `phone` null = the buyer holds it.
typedef HolderInput = ({String name, String? phone});

class FanOrder {
  new({
    required this.id,
    required this.status,
    required this.totalMad,
    required this.items,
    this.eventId,
    this.reservedUntil,
    this.paidAt,
    this.ticketIds = const [],
    this.paymentStatusKnown = false,
    this.paymentStatus,
  });

  factory fromJson(Map<String, Object?> j) => FanOrder(
    id: j['id']! as String,
    eventId: j['event_id'] as int?,
    status: j['status']! as String,
    totalMad: j['total_mad']! as int,
    reservedUntil: _instant(j['reserved_until']),
    paidAt: _instant(j['paid_at']),
    items: [for (final i in j['items']! as List<Object?>) OrderItem.fromJson(i! as Map<String, Object?>)],
    ticketIds: [for (final t in j['ticket_ids'] as List<Object?>? ?? const []) t! as String],
    paymentStatusKnown: j.containsKey('payment_status'),
    paymentStatus: j['payment_status'] as String?,
  );

  final String id;
  final int? eventId;

  /// `RESERVED`, `PAID`, `EXPIRED`, `CANCELLED` (`AWAITING_CASH` in Lot 3).
  final String status;
  final int totalMad;
  final DateTime? reservedUntil;
  final DateTime? paidAt;
  final List<OrderItem> items;

  /// Only the tickets the caller holds (a friend's ticket is never listed to the buyer).
  final List<String> ticketIds;

  /// `payment_status` is in the answer (the backend adds it in BE-12; older answers lack the key).
  final bool paymentStatusKnown;

  /// The latest payment attempt: `PENDING`, `SUCCEEDED`, `FAILED`, or null before any `/pay`.
  final String? paymentStatus;

  bool get paid => status == 'PAID';

  /// Nothing more will happen to this order: stop polling.
  bool get settled => status != 'RESERVED' && status != 'AWAITING_CASH';
}

class OrderItem {
  new({
    required this.priceCategoryId,
    required this.zoneId,
    required this.name,
    required this.qty,
    required this.unitPriceMad,
    required this.holderLabels,
  });

  factory fromJson(Map<String, Object?> j) => OrderItem(
    priceCategoryId: j['price_category_id']! as String,
    zoneId: j['zone_id']! as int,
    name: j['name']! as String,
    qty: j['qty']! as int,
    unitPriceMad: j['unit_price_mad']! as int,
    holderLabels: [
      for (final h in j['holders'] as List<Object?>? ?? const []) (h! as Map<String, Object?>)['label']! as String,
    ],
  );

  final String priceCategoryId;
  final int zoneId;
  final String name;
  final int qty;
  final int unitPriceMad;
  final List<String> holderLabels;
}

/// `POST /fan/orders/{id}/pay` answer.
class PayStart {
  new({required this.paymentId, required this.redirectUrl});

  factory fromJson(Map<String, Object?> j) =>
      PayStart(paymentId: j['payment_id']! as String, redirectUrl: j['redirect_url']! as String);

  final String paymentId;
  final String redirectUrl;
}

DateTime? _instant(Object? v) => v == null ? null : DateTime.parse(v as String).toUtc();

/// What the order screen offers for an order not PAID (docs/04-api.md GET /fan/orders/{id}, lot-1.md §7).
enum PayAgain {
  /// Not now: paid, settled, or a payment that may still succeed (a second one could charge twice).
  no,

  /// The last attempt failed: safe to pay again.
  failed,

  /// No attempt was made yet (`payment_status` null): safe to pay.
  notStarted,

  /// `payment_status` not reported (before BE-12) after a long wait: allowed, with copy saying to retry
  /// only if the card was refused and not debited.
  withCaution,

  /// The hold is over on server time and no payment can still succeed: "reservation expired", new order.
  holdOver,
}

/// Same rule as the web shop's `canRetryPay`, plus the hold: only a RESERVED order whose hold still runs on
/// server time, and when the server reports `payment_status`, only FAILED or null (PENDING can still succeed
/// for 20 min, docs/04-api.md §Payments; SUCCEEDED wins). The wait applies only when the key is absent.
PayAgain payAgain(FanOrder order, {required bool waitedLong, required bool holdOver}) {
  if (order.status != 'RESERVED') return PayAgain.no;
  final known = order.paymentStatusKnown;
  final canPay = known && (order.paymentStatus == 'FAILED' || order.paymentStatus == null);
  // A PENDING (or unreported) payment past the hold may still land (late success): keep waiting for it.
  if (holdOver) return canPay ? PayAgain.holdOver : PayAgain.no;
  if (known) {
    return switch (order.paymentStatus) {
      'FAILED' => PayAgain.failed,
      null => PayAgain.notStarted,
      _ => PayAgain.no,
    };
  }
  return waitedLong ? PayAgain.withCaution : PayAgain.no;
}
