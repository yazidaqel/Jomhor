package ma.jomhor.sales.products.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.application.OrderService.Settlement;
import ma.jomhor.sales.products.domain.Pass;
import ma.jomhor.sales.products.domain.Product;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.domain.Organizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The season pass purchase lifecycle (docs/04-api.md §Passes Lifecycle): PENDING with its purchase order, ACTIVE when
 * it is PAID, CANCELLED (sold − 1) when it expires, re-taken by a late success.
 *
 * <p>Locks: a purchase and a late success's re-take take the account's series lock ({@link Passes#lockAccount}), then
 * the product row, then the pass; the expiry holds its order rows, then the products (id order), then the passes. So
 * one live pass per account per series is decided under a lock, and the unique index never fails a settle.
 */
@Service
public class PassService {

    /** A pass row of GET /fan/passes: an event of its series with the holder's ticket for it (null until issued). */
    public record PassEvent(Event.SeriesEvent event, UUID ticketId) {}

    /** {@code orderId}: the purchase order while PENDING, else null. */
    public record View(Pass pass, Product product, Organizer organizer, ProductService.PassScope scope, UUID orderId,
                       List<PassEvent> events) {}

    private final Passes passes;
    private final Products products;
    private final ProductService productService;
    private final EventService events;
    private final OrganizerService organizers;

    PassService(Passes passes, Products products, ProductService productService, EventService events,
                OrganizerService organizers) {
        this.passes = passes;
        this.products = products;
        this.productService = productService;
        this.events = events;
        this.organizers = organizers;
    }

    /**
     * 04 POST /fan/product-orders step 4 for a PASS, in the caller's transaction: no live pass of the series for the
     * buyer (409 PASS_HELD {pass_id, status}), then the product's sold + 1 within its quota (409 SOLD_OUT
     * {product_id}), then the PENDING pass held by the buyer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Pass reserve(Product p, UUID userId, String holderName) {
        UUID org = p.organizerId();
        passes.lockAccount(userId, p.seriesId());
        passes.live(org, p.seriesId(), userId).ifPresent(held -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("pass_id", held.id().toString());
            details.put("status", held.status().name());
            throw new ApiException("PASS_HELD", HttpStatus.CONFLICT, "A pass of this series is already held", details);
        });
        if (!products.take(org, p.id())) {
            throw new ApiException("SOLD_OUT", HttpStatus.CONFLICT, "No pass left",
                    Map.of("product_id", p.id().toString()));
        }
        return passes.insert(org, p.id(), p.seriesId(), userId, holderName);
    }

    /**
     * Its purchase order is being PAID (settle, under the order row lock). A held order: PENDING → ACTIVE. A late
     * success ({@code expired}, the expiry CANCELLED the pass and gave its place back): under the account's series
     * lock, another live pass of the series → PER_ACCOUNT_LIMIT, else the product's place again (SOLD_OUT when the
     * quota is gone), and CANCELLED → ACTIVE. Null when done; a refusal writes nothing. A pass not in the status
     * expected (never, while orders and passes move together) → NOT_PAYABLE, the place taken back, so no order is PAID
     * with a pass that isn't ACTIVE.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement paid(UUID org, UUID passId, boolean expired) {
        if (!expired) {
            return passes.activate(org, passId, Pass.Status.PENDING) ? null : Settlement.NOT_PAYABLE;
        }
        Pass pass = passes.find(org, passId).orElseThrow();
        passes.lockAccount(pass.userId(), pass.seriesId());
        if (passes.live(org, pass.seriesId(), pass.userId()).isPresent()) {
            return Settlement.PER_ACCOUNT_LIMIT;
        }
        if (!products.take(org, pass.productId())) {
            return Settlement.SOLD_OUT;
        }
        if (!passes.activate(org, passId, Pass.Status.CANCELLED)) {
            products.giveBack(org, pass.productId());
            return Settlement.NOT_PAYABLE;
        }
        return null;
    }

    /** The expiry's hook, in its batch transaction: see {@link Passes#cancelExpired}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelExpired(Collection<UUID> productOrderIds) {
        passes.cancelExpired(productOrderIds);
    }

    /**
     * 04 GET /fan/passes: the caller's passes not CANCELLED, newest first, each with the series' published events at
     * the zone's venue (read even once the product is off sale).
     */
    // ponytail: a few reads per pass (a fan holds a handful); batch them if that changes.
    public List<View> owned(UUID userId) {
        return passes.owned(userId).stream().map(o -> {
            Pass pass = o.pass();
            UUID org = pass.organizerId();
            Product product = productService.product(org, pass.productId());
            ProductService.PassScope scope = productService.scope(org, product);
            Map<Integer, UUID> tickets = passes.tickets(org, pass.id(), pass.userId());
            List<PassEvent> list = events.seriesEvents(org, pass.seriesId(), scope.zone().venueId(), false).stream()
                    .map(e -> new PassEvent(e, tickets.get(e.id()))).toList();
            return new View(pass, product, organizers.get(org), scope, o.orderId(), list);
        }).toList();
    }
}
