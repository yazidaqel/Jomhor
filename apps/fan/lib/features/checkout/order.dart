// Read-only DTOs for `POST /fan/orders`, `GET /fan/orders/{id}` and `POST /fan/orders/{id}/pay`
// (docs/04-api.md §Fan). Hand-written like the event DTOs (the freezed decision is pending).

/// One holder row as the fan typed it, already normalised. `phone` null = the buyer holds it. `pays`: the friend
/// pays their own place (split pay, docs/04-api.md §Split pay); only with a phone.
typedef HolderInput = ({String name, String? phone, bool pays});

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
    this.paymentMethod,
    this.cash,
    this.group,
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
    paymentMethod: PayMethod.of(j['payment_method']),
    cash: switch (j['cash']) {
      final Map<String, Object?> c => CashCode.fromJson(c),
      _ => null,
    },
    group: switch (j['group']) {
      // A role this app does not know: the plain order screen.
      final Map<String, Object?> g when g['role'] == 'INITIATOR' || g['role'] == 'MEMBER' => OrderGroup.fromJson(g),
      _ => null,
    },
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

  /// The SUCCEEDED attempt's method, else the latest attempt's; null before `/pay` (or a method this app
  /// does not know).
  final PayMethod? paymentMethod;

  /// The pay code while the order is `AWAITING_CASH` with a PENDING code, else null.
  final CashCode? cash;

  bool get awaitingCash => status == 'AWAITING_CASH';

  /// AWAITING_CASH: card or wallet may be offered, unless the latest attempt is a card or wallet one still
  /// PENDING (a second hosted payment could charge twice; docs/04-api.md GET /fan/orders/{id}).
  bool get hostedPaymentPending => paymentMethod != null && paymentMethod!.hosted && paymentStatus == 'PENDING';

  bool get paid => status == 'PAID';

  /// Split pay (Lot 4): this order started a group (INITIATOR) or is a friend's place in one (MEMBER); else null.
  final OrderGroup? group;

  /// Places on this order.
  int get places => items.fold(0, (n, i) => n + i.qty);

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

/// `payment_methods` of `/fan/events/{id}` and `method` of `/pay`.
enum PayMethod {
  card,
  wallet,
  cashAgency;

  String get wire => switch (this) {
    card => 'CARD',
    wallet => 'WALLET',
    cashAgency => 'CASH_AGENCY',
  };

  /// Card and wallet go through the hosted page (card_payment.dart); cash gives a pay code.
  bool get hosted => this != cashAgency;

  static PayMethod? of(Object? wire) => switch (wire) {
    'CARD' => card,
    'WALLET' => wallet,
    'CASH_AGENCY' => cashAgency,
    _ => null,
  };
}

/// `POST /fan/orders/{id}/pay` answer. [redirectUrl] is null for CASH_AGENCY (the code is read from
/// `GET /fan/orders/{id}`).
class PayStart {
  new({required this.paymentId, required this.method, this.redirectUrl});

  factory fromJson(Map<String, Object?> j, PayMethod method) => PayStart(
    paymentId: j['payment_id']! as String,
    method: method,
    redirectUrl: method.hosted ? j['redirect_url']! as String : null,
  );

  final String paymentId;
  final PayMethod method;
  final String? redirectUrl;
}

/// `cash` of `GET /fan/orders/{id}`: the code to pay at a guichet or an agency.
class CashCode {
  new({required this.payCode, required this.amountMad, required this.points, required this.networks});

  factory fromJson(Map<String, Object?> j) => CashCode(
    payCode: j['pay_code']! as String,
    amountMad: j['amount_mad']! as int,
    points: [
      for (final p in (j['points'] as List<Object?>? ?? const []).cast<Map<String, Object?>>())
        (name: p['name']! as String, address: p['address'] as String?, hours: p['hours'] as String?),
    ],
    networks: [for (final n in j['networks'] as List<Object?>? ?? const []) n! as String],
  );

  final String payCode;
  final int amountMad;
  final List<CashPoint> points;

  /// Display names of the enabled cash networks.
  final List<String> networks;

  /// As the fan reads it out: `JMH 4F7K 29`.
  String get display => displayPayCode(payCode);
}

typedef CashPoint = ({String name, String? address, String? hours});

/// One row of `GET /fan/orders`: only what screen 1's pending-payment strip reads.
class OrderRow {
  new({
    required this.id,
    required this.status,
    required this.eventTitle,
    required this.eventStartsAt,
    required this.timezone,
    this.reservedUntil,
    this.payCode,
    this.groupRole,
  });

  factory fromJson(Map<String, Object?> j) {
    final e = j['event']! as Map<String, Object?>;
    return OrderRow(
      id: j['id']! as String,
      status: j['status']! as String,
      eventTitle: e['title']! as String,
      eventStartsAt: _instant(e['starts_at'])!,
      timezone: e['timezone']! as String,
      reservedUntil: _instant(j['reserved_until']),
      payCode: j['pay_code'] as String?,
      groupRole: j['group_role'] as String?,
    );
  }

  final String id;
  final String status;
  final String eventTitle;
  final DateTime eventStartsAt;

  /// The venue's zone (IANA), for the deadline.
  final String timezone;
  final DateTime? reservedUntil;

  /// The PENDING code of an AWAITING_CASH order (6 symbols), else null: nothing to pay at a counter.
  final String? payCode;

  /// `INITIATOR`, `MEMBER` (a friend kept this place for the caller) or null.
  final String? groupRole;
}

/// `group` of `POST /fan/orders` and `GET /fan/orders/{id}` (docs/04-api.md §Split pay). INITIATOR: the member
/// orders of the friends who pay their own place. MEMBER: who kept the place (a label, or null).
class OrderGroup {
  new({required this.initiator, this.members = const [], this.initiatorLabel, this.initiatorStatus});

  factory fromJson(Map<String, Object?> j) => OrderGroup(
    initiator: j['role'] == 'INITIATOR',
    members: [
      for (final m in (j['members'] as List<Object?>? ?? const []).cast<Map<String, Object?>>())
        GroupMember.fromJson(m),
    ],
    initiatorLabel: j['initiator_label'] as String?,
    initiatorStatus: j['initiator_status'] as String?,
  );

  /// INITIATOR (else MEMBER; any other role parses as no group).
  final bool initiator;
  final List<GroupMember> members;
  final String? initiatorLabel;
  final String? initiatorStatus;

  /// A friend can still pay: the initiator's screen keeps polling.
  bool get payable => members.any((m) => !m.settled);
}

/// One friend's order in the initiator's group: labels only, never a phone or full name.
class GroupMember {
  new({
    required this.orderId,
    required this.holderLabel,
    required this.places,
    required this.totalMad,
    required this.status,
    required this.payUrl,
    this.reservedUntil,
  });

  factory fromJson(Map<String, Object?> j) => GroupMember(
    orderId: j['order_id']! as String,
    holderLabel: j['holder_label']! as String,
    places: j['places']! as int,
    totalMad: j['total_mad']! as int,
    status: j['status']! as String,
    reservedUntil: _instant(j['reserved_until']),
    payUrl: j['pay_url']! as String,
  );

  final String orderId;
  final String holderLabel;
  final int places;
  final int totalMad;
  final String status;
  final DateTime? reservedUntil;

  /// The shop's `/orders/{id}` of this member order: what the friend opens to pay.
  final String payUrl;

  bool get paid => status == 'PAID';
  bool get settled => status != 'RESERVED' && status != 'AWAITING_CASH';
}

/// "{paid}/{total} payées" of screen 9 (lot-4 Q107: places, not people): every place of the group, expired ones
/// included, and the places of PAID orders.
({int paid, int total}) groupPlaces(FanOrder buyer) {
  final members = buyer.group?.members ?? const <GroupMember>[];
  return (
    paid: (buyer.paid ? buyer.places : 0) + members.where((m) => m.paid).fold(0, (n, m) => n + m.places),
    total: buyer.places + members.fold(0, (n, m) => n + m.places),
  );
}

const _crockford = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/// `JMH 4F7K 29` (docs/04-api.md §Cash codes) from the 6 symbols, with or without the `JMH` prefix, spaces or
/// hyphens. Anything else is shown as sent. The prefix is only dropped from 9 characters: `J`, `M` and `H` are
/// symbols too, so a bare code may start with `JMH`.
String displayPayCode(String raw) {
  var c = raw.toUpperCase().replaceAll(RegExp('[ -]'), '');
  if (c.length == 9 && c.startsWith('JMH')) c = c.substring(3);
  if (c.length != 6 || c.split('').any((s) => !_crockford.contains(s))) return raw;
  return 'JMH ${c.substring(0, 4)} ${c.substring(4)}';
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
