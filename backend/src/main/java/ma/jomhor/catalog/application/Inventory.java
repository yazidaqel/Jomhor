package ma.jomhor.catalog.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event.PriceCategory;
import ma.jomhor.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quota for other modules (sales): price_categories.sold changes only here. reserve and release run inside the
 * caller's transaction (MANDATORY), so a reservation commits or rolls back with its order.
 *
 * <p>Lock order, so nothing deadlocks: (1) reserve and release touch categories in UUID order, so an order and an
 * expiry batch never take the same two rows in opposite orders. (2) Catalog writers (event PATCH, price writes,
 * publish) lock the event row {@code for no key update} and then at most one category; an order takes its
 * categories and then {@code for key share} on the event (the orders FK), which that lock doesn't block. So an
 * order never waits on the event while holding a category a catalog writer waits for.
 */
@Service
public class Inventory {

    /** What a sale needs to know about an event. */
    public record SaleEvent(UUID organizerId, boolean onSale, int perAccountLimit) {}

    /** A price category as a sale sees it; priceMad in centimes. */
    public record SalePrice(UUID id, int zoneId, String name, long priceMad) {}

    /** {@code qty} places of a category held by orders, to give back. */
    public record Hold(UUID organizerId, int eventId, UUID priceCategoryId, int qty) {}

    /** A hold that doesn't fit (sold below it): the caller's transaction rolls back. */
    public static class ReleaseFailed extends IllegalStateException {
        private final transient Hold hold;

        ReleaseFailed(Hold hold) {
            super("sold below held places: event " + hold.eventId() + ", price category " + hold.priceCategoryId());
            this.hold = hold;
        }

        public Hold hold() {
            return hold;
        }
    }

    private static final Comparator<Hold> LOCK_ORDER = Comparator.comparing(Hold::priceCategoryId);

    private final Events events;

    public Inventory(Events events) {
        this.events = events;
    }

    public Optional<SaleEvent> saleEvent(int eventId) {
        return events.forSale(eventId);
    }

    public List<SalePrice> prices(UUID org, int eventId) {
        return events.prices(org, eventId).stream().map(Inventory::sale).toList();
    }

    /**
     * Takes every {@code qty} or none: the first category that doesn't fit throws SOLD_OUT and the caller's
     * transaction rolls back the ones already taken. Returns the categories as updated (the price to snapshot).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, SalePrice> reserve(UUID org, int eventId, Map<UUID, Integer> qtyByCategory) {
        Map<UUID, SalePrice> taken = new TreeMap<>();
        new TreeMap<>(qtyByCategory).forEach((id, qty) -> taken.put(id, events.take(org, eventId, id, qty).map(Inventory::sale)
                .orElseThrow(() -> new ApiException("SOLD_OUT", HttpStatus.CONFLICT, "Not enough places left",
                        Map.of("price_category_id", id.toString())))));
        return taken;
    }

    /** Gives every hold back. Each one must fit (sold ≥ qty): anything else is a bug, ReleaseFailed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(List<Hold> holds) {
        holds.stream().sorted(LOCK_ORDER).forEach(h -> {
            if (!events.giveBack(h.organizerId(), h.eventId(), h.priceCategoryId(), h.qty())) {
                throw new ReleaseFailed(h);
            }
        });
    }

    private static SalePrice sale(PriceCategory p) {
        return new SalePrice(p.id(), p.zoneId(), p.name(), p.priceMad());
    }
}
