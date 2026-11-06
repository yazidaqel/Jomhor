package ma.jomhor.sales.application;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.catalog.application.Inventory.SalePrice;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.application.Orders.Expired;
import ma.jomhor.sales.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fan orders (docs/04-api.md §Fan). Quota goes through catalog's Inventory in this module's transaction, so an
 * order, its items and the places it takes commit together or not at all.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final int EXPIRY_BATCH = 1000;

    public record ItemRequest(UUID priceCategoryId, int qty) {}

    /** An order with what the fan sees of each category. */
    public record View(Order order, Map<UUID, SalePrice> categories) {}

    private final Orders orders;
    private final Inventory inventory;
    private final TransactionTemplate tx;

    public OrderService(Orders orders, Inventory inventory, PlatformTransactionManager transactions) {
        this.orders = orders;
        this.inventory = inventory;
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
        return new View(orders.insert(org, eventId, userId, channel, total, lines), taken);
    }

    public View get(UUID userId, UUID orderId) {
        Order order = orders.find(userId, orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        return new View(order, byId(inventory.prices(order.organizerId(), order.eventId())));
    }

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
        int expired;
        do {
            expired = tx.execute(s -> expireBatch(batchSize));
            total += expired;
        } while (expired == batchSize);
        return total;
    }

    private int expireBatch(int batchSize) {
        List<Expired> due = orders.expireDue(batchSize);
        try {
            inventory.release(due.stream().map(Expired::hold).toList());
        } catch (Inventory.ReleaseFailed e) {
            List<UUID> orderIds = due.stream().filter(d -> d.hold().equals(e.hold())).findFirst()
                    .map(Expired::orderIds).orElse(List.of());
            log.error("Expiry batch rolled back: event {}, price category {}, orders {}", e.hold().eventId(),
                    e.hold().priceCategoryId(), orderIds, e);
            throw e;
        }
        return (int) due.stream().flatMap(d -> d.orderIds().stream()).distinct().count();
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
