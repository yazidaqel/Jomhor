package ma.jomhor.catalog.application;

import java.time.Instant;
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

    /**
     * What a sale needs to know about an event. {@code window} is computed on the database clock (now()):
     * OPEN = ON_SALE, now >= sale_starts_at (if set) and now < refund_cutoff_at (plan Q19, Q20). {@code quota} =
     * Σ quota of its price categories.
     */
    public record SaleEvent(UUID organizerId, SaleWindow window, Instant saleStartsAt, Instant refundCutoffAt,
                            int perAccountLimit, long quota) {}

    /** What a fan's order list shows of its event. */
    public record EventLabel(int id, String title, Instant startsAt, String timezone) {}

    /** docs/04-api.md EVENT_NOT_ON_SALE details.reason, plus OPEN. */
    public enum SaleWindow { OPEN, NOT_ON_SALE, NOT_STARTED, CLOSED }

    /** A price category as a sale sees it, with its zone's name; priceMad in centimes. */
    public record SalePrice(UUID id, int zoneId, String zoneName, String name, long priceMad) {}

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

    /** Public: the event by id alone (not DRAFT). */
    public Optional<EventLabel> label(int eventId) {
        return events.listing(eventId).map(e -> new EventLabel(e.id(), e.title(), e.startsAt(), e.timezone()));
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
        UUID full = take(org, eventId, qtyByCategory, taken);
        if (full != null) {
            throw new ApiException("SOLD_OUT", HttpStatus.CONFLICT, "Not enough places left",
                    Map.of("price_category_id", full.toString()));
        }
        return taken;
    }

    /**
     * Like reserve, but a category that doesn't fit gives back what was already taken and returns empty instead
     * of throwing, so a caller that must commit anyway (a late card success, plan Q8) keeps its transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Map<UUID, SalePrice>> tryReserve(UUID org, int eventId, Map<UUID, Integer> qtyByCategory) {
        Map<UUID, SalePrice> taken = new TreeMap<>();
        if (take(org, eventId, qtyByCategory, taken) == null) {
            return Optional.of(taken);
        }
        release(taken.keySet().stream().map(id -> new Hold(org, eventId, id, qtyByCategory.get(id))).toList());
        return Optional.empty();
    }

    /** Takes in UUID order into {@code taken}; returns the first category that doesn't fit, or null. */
    private UUID take(UUID org, int eventId, Map<UUID, Integer> qtyByCategory, Map<UUID, SalePrice> taken) {
        for (var e : new TreeMap<>(qtyByCategory).entrySet()) {
            Optional<PriceCategory> row = events.take(org, eventId, e.getKey(), e.getValue());
            if (row.isEmpty()) {
                return e.getKey();
            }
            taken.put(e.getKey(), sale(row.get()));
        }
        return null;
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
        return new SalePrice(p.id(), p.zoneId(), p.zoneName(), p.name(), p.priceMad());
    }
}
