package ma.jomhor.sales.application;

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

    /** Σ qty of the buyer's RESERVED, AWAITING_CASH and PAID orders on the event. */
    long held(UUID org, UUID userId, int eventId);

    /** The event's RESERVED orders still held (reserved_until > now()). No lock (docs/04-api.md §On-sale Busy). */
    long activeHolds(UUID org, int eventId);

    /** The channel of an order of this organizer. */
    Optional<Order.Channel> channel(UUID org, UUID orderId);

    /** A RESERVED order held 8 min from now (database clock), with its items and their holders. */
    Order insert(UUID org, int eventId, UUID userId, Order.Channel channel, long totalMad, List<Order.Item> items);

    /** The buyer's own order; another user's is absent. */
    Optional<Order> find(UUID userId, UUID orderId);

    /**
     * The order with its items, its row locked ({@code for no key update}) until the end of the transaction:
     * the same strength as the expiry claim, so pay, settle and expiry on one order run one after the other.
     */
    Optional<Locked> lock(UUID orderId);

    /** A locked order, and whether its hold still runs by the database clock (reserved_until > now()). */
    record Locked(Order order, boolean holding) {}

    void markPaid(UUID org, UUID orderId, String paymentRef);

    /**
     * Locks up to {@code limit} RESERVED orders past reserved_until, oldest first, leaving out {@code exclude}
     * and rows another transaction holds (skip locked).
     */
    List<UUID> claimDue(int limit, Collection<UUID> exclude);

    /** RESERVED → EXPIRED for these (already locked) orders; their items summed per price category. */
    List<Expired> expire(Collection<UUID> orderIds);

    /** The places of one price category held by the orders just expired. */
    record Expired(Hold hold, List<UUID> orderIds) {}
}
