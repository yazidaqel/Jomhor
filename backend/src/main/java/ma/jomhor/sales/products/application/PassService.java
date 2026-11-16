package ma.jomhor.sales.products.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.application.OrderService.Settlement;
import ma.jomhor.sales.products.domain.Installment;
import ma.jomhor.sales.products.domain.Pass;
import ma.jomhor.sales.products.domain.Product;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.domain.Organizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>Installments (04 §Passes Installments): the purchase creates the schedule; its order pays seq 1. Any other product
 * order of the pass pays the earliest unpaid installment under the pass row lock, taken after the order row (settle)
 * or first (POST /fan/passes/{id}/installments, which then only inserts its order), so they never wait in a circle.
 */
@Service
public class PassService {

    private static final Logger log = LoggerFactory.getLogger(PassService.class);

    /** A pass row of GET /fan/passes: an event of its series with the holder's ticket for it (null until issued). */
    public record PassEvent(Event.SeriesEvent event, UUID ticketId) {}

    /** The earliest unpaid installment; {@code overdue}: its due_at has passed. */
    public record NextDue(Installment installment, boolean overdue) {}

    /**
     * {@code orderId}: the purchase order while PENDING, else null. {@code installments} by seq; {@code next} null when
     * paid up; {@code payOrderId} the live installment order (ACTIVE or SUSPENDED passes only), else null.
     */
    public record View(Pass pass, Product product, Organizer organizer, ProductService.PassScope scope, UUID orderId,
                       List<PassEvent> events, List<Installment> installments, NextDue next, UUID payOrderId) {}

    private final Passes passes;
    private final Products products;
    private final ProductService productService;
    private final EventService events;
    private final OrganizerService organizers;
    private final AuditLog audit;
    private final Duration grace;

    PassService(Passes passes, Products products, ProductService productService, EventService events,
                OrganizerService organizers, AuditLog audit, @Value("${passes.grace}") Duration grace) {
        this.passes = passes;
        this.products = products;
        this.productService = productService;
        this.events = events;
        this.organizers = organizers;
        this.audit = audit;
        this.grace = grace;
    }

    /**
     * 04 POST /fan/product-orders step 4 for a PASS, in the caller's transaction: no live pass of the series for the
     * buyer (409 PASS_HELD {pass_id, status}), then the product's sold + 1 within its quota (409 SOLD_OUT
     * {product_id}), then the PENDING pass held by the buyer with its schedule ({@code plan}, 04 §Passes Installments).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Pass reserve(Product p, UUID userId, String holderName, ProductService.Plan plan) {
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
        Pass pass = passes.insert(org, p.id(), p.seriesId(), userId, holderName);
        passes.schedule(org, pass.id(), plan.amountsMad(), plan.interval() == null ? Duration.ZERO : plan.interval());
        return pass;
    }

    /** Whether this product order of the pass is an installment order: not its purchase order (its first). */
    public boolean isInstallment(UUID org, UUID passId, UUID orderId) {
        return !passes.purchaseOrder(org, passId).orElseThrow().equals(orderId);
    }

    /**
     * Its purchase order is being PAID (settle, under the order row lock). A held order: PENDING → ACTIVE. A late
     * success ({@code expired}, the expiry CANCELLED the pass and gave its place back): under the account's series
     * lock, another live pass of the series → PER_ACCOUNT_LIMIT, else the product's place again (SOLD_OUT when the
     * quota is gone), and CANCELLED → ACTIVE. Null when done; a refusal writes nothing. A pass not in the status
     * expected (never, while orders and passes move together) → NOT_PAYABLE, the place taken back, so no order is PAID
     * with a pass that isn't ACTIVE. Done: seq 1 is paid by {@code orderId}. A pass with no seq 1 (never, since V32's
     * backfill) → NOT_PAYABLE, logged, before anything is written: no pass goes ACTIVE without its schedule.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement paid(UUID org, UUID passId, UUID orderId, boolean expired) {
        Installment first = passes.installments(org, passId).stream().filter(i -> i.seq() == 1).findFirst()
                .orElse(null);
        if (first == null) {
            log.error("Pass {} has no installment 1: purchase order {} not paid (refund required)", passId, orderId);
            return Settlement.NOT_PAYABLE;
        }
        Settlement refused = purchasePaid(org, passId, expired);
        if (refused == null) {
            passes.pay(org, first.id(), orderId);
        }
        return refused;
    }

    private Settlement purchasePaid(UUID org, UUID passId, boolean expired) {
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

    /**
     * An installment order is being PAID (settle, under the order row lock, held or a late success): under the pass row
     * lock, it pays the earliest unpaid installment when its total equals that amount, and a SUSPENDED pass with no
     * other installment past its grace goes ACTIVE (audit pass.reactivate). Null when done; else NOT_PAYABLE, nothing
     * written: paid up, another amount, or a pass neither ACTIVE nor SUSPENDED.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement installmentPaid(UUID org, UUID passId, UUID orderId, long totalMad) {
        Pass pass = passes.lock(org, passId).orElseThrow();
        if (pass.status() != Pass.Status.ACTIVE && pass.status() != Pass.Status.SUSPENDED) {
            return Settlement.NOT_PAYABLE;
        }
        Installment next = passes.installments(org, passId).stream().filter(i -> !i.paid()).findFirst().orElse(null);
        if (next == null || next.amountMad() != totalMad || !passes.pay(org, next.id(), orderId)) {
            return Settlement.NOT_PAYABLE;
        }
        if (pass.status() == Pass.Status.SUSPENDED && passes.reactivate(org, passId, grace)) {
            audit.system(org, "pass.reactivate", passId.toString(), "{\"seq\": " + next.seq() + "}");
        }
        return null;
    }

    /**
     * 04 §Passes Suspension, the pass job's first step at {@code now}: ACTIVE passes with an installment unpaid
     * {@code passes.grace} past its due_at → SUSPENDED (audit pass.suspend {seq}), in one transaction. Once only:
     * another instance running at the same time skips or re-reads the rows (see {@link Passes#suspend}).
     */
    @Transactional
    public int suspend(Instant now) {
        List<Passes.Suspended> suspended = passes.suspend(now, grace);
        suspended.forEach(s -> audit.system(s.organizerId(), "pass.suspend", s.passId().toString(),
                "{\"seq\": " + s.seq() + "}"));
        return suspended.size();
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
        Instant now = events.now();
        return passes.owned(userId).stream().map(o -> {
            Pass pass = o.pass();
            UUID org = pass.organizerId();
            Product product = productService.product(org, pass.productId());
            ProductService.PassScope scope = productService.scope(org, product);
            Map<Integer, UUID> tickets = passes.tickets(org, pass.id(), pass.userId());
            List<PassEvent> list = events.seriesEvents(org, pass.seriesId(), scope.zone().venueId(), false).stream()
                    .map(e -> new PassEvent(e, tickets.get(e.id()))).toList();
            List<Installment> schedule = passes.installments(org, pass.id());
            NextDue next = schedule.stream().filter(i -> !i.paid()).findFirst()
                    .map(i -> new NextDue(i, i.dueAt().isBefore(now))).orElse(null);
            boolean paying = pass.status() == Pass.Status.ACTIVE || pass.status() == Pass.Status.SUSPENDED;
            return new View(pass, product, organizers.get(org), scope, o.orderId(), list, schedule, next,
                    paying ? passes.liveOrder(org, pass.id()).orElse(null) : null);
        }).toList();
    }
}
