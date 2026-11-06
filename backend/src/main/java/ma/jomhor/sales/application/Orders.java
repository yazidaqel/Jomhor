package ma.jomhor.sales.application;

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

    /** A RESERVED order held 8 min from now (database clock), with its items. */
    Order insert(UUID org, int eventId, UUID userId, Order.Channel channel, long totalMad, List<Order.Item> items);

    /** The buyer's own order; another user's is absent. */
    Optional<Order> find(UUID userId, UUID orderId);

    /**
     * Moves up to {@code limit} RESERVED orders past reserved_until to EXPIRED, skipping those with a PENDING
     * payment younger than 20 min and rows another instance is working on. Returns their items, summed per
     * price category, to release in the same transaction.
     */
    List<Expired> expireDue(int limit);

    /** The places of one price category held by the orders just expired. */
    record Expired(Hold hold, List<UUID> orderIds) {}
}
