package ma.jomhor.sales.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory.Hold;
import ma.jomhor.sales.domain.Order;

/** Persistence port for orders and order_items. */
public interface Orders {

    /** Serialises the orders of one buyer on one event until the end of the transaction. */
    void lockBuyer(UUID userId, int eventId);

    /**
     * {@link #lockBuyer} for each account, in the order of the keys actually locked (their hash, then the id), so two
     * transactions locking overlapping sets never wait in a circle, even when two ids share a hash.
     */
    void lockAccounts(Collection<UUID> userIds, int eventId);

    /**
     * Σ qty of the buyer's RESERVED, AWAITING_CASH and PAID orders on the event, less the REFUNDED tickets of those
     * PAID orders (plan lot-5 Q175), plus the places of member orders of the buyer's split groups still RESERVED or
     * AWAITING_CASH (04 §Split pay Limits, P83).
     */
    long held(UUID org, UUID userId, int eventId);

    /**
     * Σ qty of the account's own RESERVED, AWAITING_CASH and PAID orders on the event, less the REFUNDED tickets of
     * those PAID orders (a split member's count).
     */
    long ownHeld(UUID org, UUID userId, int eventId);

    /**
     * The places the account holds on the event, whoever bought them (04 POST /fan/orders Holder limit): its places in
     * RESERVED and AWAITING_CASH orders plus its VALID and BLOCKED tickets.
     */
    long holding(UUID org, UUID holderId, int eventId);

    /** The event's RESERVED orders still held (reserved_until > now()). No lock (docs/04-api.md §On-sale Busy). */
    long activeHolds(UUID org, int eventId);

    /** The database clock (now(), the transaction's start), the clock of the sale window. */
    Instant now();

    /** The channel of an order of this organizer. */
    Optional<Order.Channel> channel(UUID org, UUID orderId);

    /**
     * A RESERVED order held 8 min from now (database clock), with its items and their holders; {@code promoCodeId} (null:
     * none) and its {@code discountMad} (04 §Promo codes), {@code totalMad} being after it.
     */
    Order insert(UUID org, int eventId, UUID userId, Order.Channel channel, long totalMad, List<Order.Item> items,
                 UUID promoCodeId, long discountMad);

    /**
     * A split-pay member order (04 §Split pay Orders): RESERVED until {@code reservedUntil} (the buyer's), with
     * group_order_id = {@code groupOrderId}.
     */
    Order insertMember(UUID org, int eventId, UUID userId, Order.Channel channel, UUID groupOrderId,
                       Instant reservedUntil, long totalMad, List<Order.Item> items);

    /**
     * A product order (04 POST /fan/product-orders): event_id null, {@code passId} (null for MEMBERSHIP), one item,
     * RESERVED and held 8 min from now (database clock); total = the item's.
     */
    Order insertProduct(UUID org, UUID userId, Order.Channel channel, UUID passId, Order.Item item);

    /**
     * A pass ticket order (04 §Passes Tickets): PAID now, channel APP, payment_method FREE, total 0, {@code passId}
     * and {@code eventId} set, the holder as its buyer, with its one item.
     */
    Order insertPassTicket(UUID org, int eventId, UUID userId, UUID passId, Order.Item item);

    /** The organizer's member orders of this buyer's order, with their items, by id. */
    List<Order> members(UUID org, UUID groupOrderId);

    /**
     * A till sale (04 POST /org/pos/orders): order {@code id} PAID now, channel POS, no buyer, payment_method POS_CASH,
     * with its items and holders (a holder's user id may be null: paper only). Empty if {@code id} is already an order.
     */
    Optional<Order> insertPos(UUID id, UUID org, int eventId, long totalMad, List<Order.Item> items);

    /**
     * An invitation (04 POST /org/events/{id}/invitations): like {@link #insertPos}, but payment_method FREE, total 0
     * and invited_by = the staff row of {@code staffUserId} in this organizer. Empty if {@code id} is already an order.
     */
    Optional<Order> insertInvitation(UUID id, UUID org, int eventId, UUID staffUserId, List<Order.Item> items);

    /**
     * The invitation_id lock (advisory, until the transaction ends): two requests with one id run one after the other,
     * so a retry sees the first one's committed order (04 POST /org/events/{id}/invitations replay).
     */
    void lockInvitationId(UUID id);

    /** The event of the organizer's invitation order {@code orderId}; empty for any other order. */
    Optional<Integer> invitationEvent(UUID org, UUID orderId);

    /** An invitation order with when and by whom it was issued (04 GET /org/events/{id}/invitations). */
    record Invitation(Order order, Instant createdAt, UUID invitedById, String invitedByName) {}

    /** The event's invitation orders with their items, newest first, at most {@code limit}. */
    List<Invitation> invitations(UUID org, int eventId, int limit);

    /** Whether any order (of any organizer) has this id. */
    boolean exists(UUID orderId);

    /** The organizer's order with its items. */
    Optional<Order> findInOrg(UUID org, UUID orderId);

    /** The buyer's own order; another user's is absent. */
    Optional<Order> find(UUID userId, UUID orderId);

    /**
     * The order with its items, its row locked ({@code for no key update}) until the end of the transaction:
     * the same strength as the expiry claim, so pay, settle and expiry on one order run one after the other.
     */
    Optional<Locked> lock(UUID orderId);

    /** A locked order, and whether its hold still runs by the database clock (reserved_until > now()). */
    record Locked(Order order, boolean holding) {}

    /** → CANCELLED (a product order that can't be paid, 04 §Products Paid membership). */
    void cancel(UUID org, UUID orderId);

    /** → PAID with the succeeding payment's method and reference (for a cash code, the code). */
    void markPaid(UUID org, UUID orderId, Order.PaymentMethod method, String paymentRef);

    /**
     * RESERVED → AWAITING_CASH with reserved_until = least(now() + 24 h, {@code salesCloseAt}), only if that leaves
     * at least {@code minWindow} (database clock). The new reserved_until, or empty (nothing written: too late).
     */
    Optional<Instant> awaitCash(UUID org, UUID orderId, Instant salesCloseAt, Duration minWindow);

    /** Its member orders still RESERVED get reserved_until = {@code until} (the buyer's new hold, P78). */
    void mirrorMembers(UUID org, UUID orderId, Instant until);

    /**
     * Its member orders still RESERVED get reserved_until = least(now() + {@code hold}, {@code salesCloseAt}) (the
     * buyer just paid, 04 §Split pay Hold). Returns their ids.
     */
    List<UUID> extendMembers(UUID org, UUID orderId, Duration hold, Instant salesCloseAt);

    /**
     * Locks its member orders still RESERVED ({@code for no key update}, waiting for them) until the end of the
     * transaction: settle takes them after the order row and before any price category (lock order).
     */
    void lockMembers(UUID org, UUID orderId);

    /** Whether the order is RESERVED or AWAITING_CASH and reserved_until is at least {@code left} away (database clock). */
    boolean holdsFor(UUID org, UUID orderId, Duration left);

    /** Places in the event's AWAITING_CASH orders still held (reserved_until > now()). No lock (04 §Cash codes Cap). */
    long cashHeld(UUID org, int eventId);

    /** A fan's order as GET /fan/orders lists it: {@code eventId} null and {@code productId} set on a product order. */
    record Listed(UUID id, UUID organizerId, Integer eventId, UUID productId, Order.Channel channel, Order.Status status, long totalMad,
                  Instant reservedUntil, Instant createdAt, Order.GroupRole groupRole) {}

    /**
     * The buyer's orders: AWAITING_CASH ones and member orders still RESERVED and held first, then newest first, at
     * most {@code limit}. Pass ticket orders (pass_id and event_id set) are left out.
     */
    List<Listed> listed(UUID userId, int limit);

    /**
     * Locks up to {@code limit} RESERVED or AWAITING_CASH orders past reserved_until, oldest first, leaving out
     * {@code exclude}, rows another transaction holds (skip locked), and member orders whose buyer's order is still
     * RESERVED or AWAITING_CASH (04 §Split pay Hold).
     */
    List<UUID> claimDue(int limit, Collection<UUID> exclude);

    /**
     * Locks the RESERVED member orders of these buyer orders (they expire with them), leaving out {@code exclude} and
     * rows another transaction holds (skip locked).
     */
    List<UUID> claimMembers(Collection<UUID> buyerOrderIds, Collection<UUID> exclude);

    /**
     * RESERVED or AWAITING_CASH → EXPIRED for these (already locked) orders; their places summed per price category,
     * and the product orders in one row per organizer with no hold.
     */
    List<Expired> expire(Collection<UUID> orderIds);

    /**
     * The places of one price category held by the orders just expired; {@code hold} null: product orders, which hold
     * no category place (a product's own count is the product's to give back).
     */
    record Expired(Hold hold, List<UUID> orderIds) {}

    /**
     * Per price category, channel and payment_method (null before payment) of the event: places in PAID orders,
     * places in RESERVED and in AWAITING_CASH orders still held (reserved_until > now(), swept or not), and
     * Σ qty × unit_price_mad (the snapshot) of the PAID ones.
     */
    List<SalesLine> salesLines(UUID org, int eventId);

    /** {@code free}: PASS (pass_id set) or INVITE (invited_by set) for pass ticket and invitation orders, else null. */
    record SalesLine(UUID priceCategoryId, String channel, String paymentMethod, String free, long paid, long held,
                     long awaitingCash, long revenueMad) {}

    /** Σ discount_mad of the event's PAID orders (04 GET /org/events/{id}/sales {@code discount_mad}). */
    long discountMad(UUID org, int eventId);

    /** The event's payment.refund_required audit rows (matched on the order they name), newest first. */
    List<RefundRequired> refundsRequired(UUID org, int eventId);

    /** The payment.refund_required audit rows of the product's orders (04 GET /org/products/{id}), newest first. */
    List<RefundRequired> productRefundsRequired(UUID org, UUID productId);

    /** amountMad is what the gateway charged; reason SOLD_OUT, PER_ACCOUNT_LIMIT, NOT_PAYABLE or AMOUNT_MISMATCH. */
    record RefundRequired(UUID paymentId, UUID orderId, long amountMad, String reason, Instant at) {}
}
