package ma.jomhor.sales.application;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.catalog.application.Inventory.SalePrice;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.application.Orders.Expired;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.tickets.application.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fan orders (docs/04-api.md §Fan). Quota goes through catalog's Inventory in this module's transaction, so an
 * order, its items and the places it takes commit together or not at all.
 *
 * <p>Lock order on one order, everywhere: the payment row (payments' callback) → the order row → the buyer lock
 * → price categories in UUID order. Placing an order takes the buyer lock → categories; the expiry claims order
 * rows with skip locked (it never waits for one) → categories. So none of them waits in a circle.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final int EXPIRY_BATCH = 1000;
    /** Plan Q8: an order with a PENDING payment younger than this is not expired. */
    static final Duration PAYMENT_GRACE = Duration.ofMinutes(20);

    public record ItemRequest(UUID priceCategoryId, int qty) {}

    /** An order with what the fan sees of each category, and its tickets. */
    public record View(Order order, Map<UUID, SalePrice> categories, List<UUID> ticketIds) {}

    /** What became of an order whose card payment succeeded. Anything but PAID needs a manual refund. */
    public enum Settlement { PAID, SOLD_OUT, PER_ACCOUNT_LIMIT, NOT_PAYABLE }

    private final Orders orders;
    private final Inventory inventory;
    private final TicketService tickets;
    private final PendingPayments pendingPayments;
    private final TransactionTemplate tx;

    public OrderService(Orders orders, Inventory inventory, TicketService tickets, PendingPayments pendingPayments,
                        PlatformTransactionManager transactions) {
        this.orders = orders;
        this.inventory = inventory;
        this.tickets = tickets;
        this.pendingPayments = pendingPayments;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Checks in this order: event (404, 409 EVENT_NOT_ON_SALE), items (422), per-account limit (409), quota
     * (409 SOLD_OUT). The buyer lock is taken before the limit is read, so two parallel orders of one buyer
     * are counted one after the other.
     */
    @Transactional
    public View place(UUID userId, int eventId, Order.Channel channel, List<ItemRequest> items) {
        Inventory.SaleEvent event = inventory.saleEvent(eventId)
                .orElseThrow(() -> ApiException.notFound("Event not found"));
        if (!event.onSale()) {
            throw new ApiException("EVENT_NOT_ON_SALE", HttpStatus.CONFLICT, "Event is not on sale");
        }
        UUID org = event.organizerId();
        Map<UUID, SalePrice> ofEvent = byId(inventory.prices(org, eventId));
        Map<UUID, Integer> qty = new LinkedHashMap<>();
        long requested = 0;
        for (int i = 0; i < items.size(); i++) {
            ItemRequest item = items.get(i);
            if (!ofEvent.containsKey(item.priceCategoryId())) {
                throw invalid("items[%d].price_category_id".formatted(i), "not a price category of this event");
            }
            if (qty.put(item.priceCategoryId(), item.qty()) != null) {
                throw invalid("items[%d].price_category_id".formatted(i), "duplicate price category");
            }
            requested += item.qty();
        }

        orders.lockBuyer(userId, eventId);
        long held = orders.held(org, userId, eventId);
        if (held + requested > event.perAccountLimit()) {
            throw new ApiException("PER_ACCOUNT_LIMIT", HttpStatus.CONFLICT, "Per-account limit reached",
                    Map.of("limit", event.perAccountLimit(), "held", held));
        }

        Map<UUID, SalePrice> taken = inventory.reserve(org, eventId, qty);
        List<Order.Item> lines = qty.entrySet().stream()
                .map(e -> new Order.Item(e.getKey(), e.getValue(), taken.get(e.getKey()).priceMad()))
                .toList();
        long total = lines.stream().mapToLong(l -> l.qty() * l.unitPriceMad()).sum();
        return new View(orders.insert(org, eventId, userId, channel, total, lines), taken, List.of());
    }

    public View get(UUID userId, UUID orderId) {
        Order order = orders.find(userId, orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        return new View(order, byId(inventory.prices(order.organizerId(), order.eventId())),
                tickets.ofOrder(order.organizerId(), order.id()));
    }

    // ---------- payment (called by payments) ----------

    /**
     * The buyer's order, locked for the caller's transaction, if it can take a payment now: RESERVED and still
     * held. The lock makes a pay and an expiry pass on the same order run one after the other, so a payment is
     * never accepted for an order the expiry is expiring. Another user's order → 404.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Order lockForPayment(UUID userId, UUID orderId) {
        Orders.Locked locked = orders.lock(orderId).filter(x -> userId.equals(x.order().userId()))
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        Order o = locked.order();
        // Database clock, like the expiry claim (reserved_until < now()): a pay that passes here can't be claimed.
        boolean expired = o.status() == Order.Status.EXPIRED || (o.status() == Order.Status.RESERVED && !locked.holding());
        if (expired) {
            throw new ApiException("ORDER_EXPIRED", HttpStatus.CONFLICT, "The hold on this order has expired");
        }
        if (o.status() != Order.Status.RESERVED) {
            throw new ApiException("ORDER_NOT_PAYABLE", HttpStatus.CONFLICT, "Order can't be paid in its current status");
        }
        return o;
    }

    /**
     * A card payment of the order succeeded (payments' callback, inside its transaction): RESERVED → PAID with
     * one ticket per place. EXPIRED (a late success, plan Q8) re-reserves the places under the buyer lock with
     * the per-account limit checked again. Never throws for a business outcome: the payment commits as
     * SUCCEEDED either way, and anything but PAID is for a manual refund.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement settle(UUID orderId, String paymentRef) {
        Order o = orders.lock(orderId).orElseThrow().order();
        if (o.status() == Order.Status.EXPIRED) {
            Settlement refused = reReserve(o);
            if (refused != null) {
                return refused;
            }
        } else if (o.status() != Order.Status.RESERVED) {
            return Settlement.NOT_PAYABLE;
        }
        Map<UUID, SalePrice> categories = byId(inventory.prices(o.organizerId(), o.eventId()));
        orders.markPaid(o.organizerId(), o.id(), paymentRef);
        tickets.issue(o.organizerId(), o.id(), o.eventId(), o.userId(), o.items().stream()
                .map(i -> new TicketService.Line(categories.get(i.priceCategoryId()).zoneId(), i.priceCategoryId(), i.qty()))
                .toList());
        return Settlement.PAID;
    }

    /** Takes the expired order's places again; null if done, else why not. */
    private Settlement reReserve(Order o) {
        orders.lockBuyer(o.userId(), o.eventId());
        int limit = inventory.saleEvent(o.eventId()).orElseThrow().perAccountLimit();
        if (orders.held(o.organizerId(), o.userId(), o.eventId()) + o.places() > limit) {
            return Settlement.PER_ACCOUNT_LIMIT;
        }
        Map<UUID, Integer> qty = o.items().stream()
                .collect(Collectors.toMap(Order.Item::priceCategoryId, Order.Item::qty));
        return inventory.tryReserve(o.organizerId(), o.eventId(), qty).isPresent() ? null : Settlement.SOLD_OUT;
    }

    // ---------- expiry ----------

    /** One expiry pass in batches of 1000. Returns the number of orders expired. */
    public int expireDue() {
        return expireDue(EXPIRY_BATCH);
    }

    /**
     * Expires due orders batch after batch, each in its own transaction, until a batch comes back short. In a
     * batch the status flip and the release commit together, so places come back exactly once (a later pass
     * finds nothing); rows claimed by another instance are skipped. A release that doesn't fit rolls its batch
     * back and is logged with the orders involved; the next pass meets the same orders again.
     */
    public int expireDue(int batchSize) {
        int total = 0;
        Batch batch;
        do {
            batch = tx.execute(s -> expireBatch(batchSize));
            total += batch.expired();
        } while (batch.claimed() == batchSize);
        return total;
    }

    private record Batch(int claimed, int expired) {}

    /**
     * Orders with a young PENDING payment are left out of the claim (so they can't fill every batch), and read
     * again once the claimed rows are locked: a pay that committed in between (it locks the order first) is
     * seen then, and its order is kept.
     */
    private Batch expireBatch(int batchSize) {
        List<UUID> due = orders.claimDue(batchSize, pendingPayments.ordersWithPendingYoungerThan(PAYMENT_GRACE));
        Set<UUID> paying = pendingPayments.ordersWithPendingYoungerThan(PAYMENT_GRACE);
        List<Expired> expired = orders.expire(due.stream().filter(id -> !paying.contains(id)).toList());
        try {
            inventory.release(expired.stream().map(Expired::hold).toList());
        } catch (Inventory.ReleaseFailed e) {
            List<UUID> orderIds = expired.stream().filter(d -> d.hold().equals(e.hold())).findFirst()
                    .map(Expired::orderIds).orElse(List.of());
            log.error("Expiry batch rolled back: event {}, price category {}, orders {}", e.hold().eventId(),
                    e.hold().priceCategoryId(), orderIds, e);
            throw e;
        }
        return new Batch(due.size(), (int) expired.stream().flatMap(d -> d.orderIds().stream()).distinct().count());
    }

    private static Map<UUID, SalePrice> byId(List<SalePrice> prices) {
        Map<UUID, SalePrice> map = new HashMap<>();
        prices.forEach(p -> map.put(p.id(), p));
        return map;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }
}
