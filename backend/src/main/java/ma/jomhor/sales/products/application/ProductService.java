package ma.jomhor.sales.products.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.catalog.application.PaymentMethods;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.crm.application.MembershipService;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.products.domain.Installment;
import ma.jomhor.sales.products.domain.Installments;
import ma.jomhor.sales.products.domain.Pass;
import ma.jomhor.sales.products.domain.Product;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.domain.Organizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Products (docs/04-api.md §Products, /org/products*, GET /fan/products*): what an order sells besides event places,
 * a season pass (PASS) or the club's paid membership (MEMBERSHIP). A PASS product's series and zone come from catalog.
 * {@code org} is always OrgContext.organizerId() on the organizer side.
 */
@Service
public class ProductService {

    static final int NAME_MAX = 60;
    /** JdbcOrders.awaitCash's 24 h: an installment order's cash hold, with no sales close (04 POST /pay CASH_AGENCY). */
    public static final Duration INSTALLMENT_CASH_HOLD = Duration.ofHours(24);

    /**
     * POST or PATCH body. On PATCH a null field is unchanged; kind, series_id and zone_id are fixed after create (a
     * different value → 422).
     */
    public record Input(Product.Kind kind, String name, Long priceMad, Integer quota, UUID seriesId, Integer zoneId,
                        Instant salesCloseAt, Instant validUntil, Boolean onSale, Integer maxInstallments) {}

    /** A PASS product's series and zone (with its venue). */
    public record PassScope(Event.Series series, Event.ZoneAt zone) {}

    /** The organizer's row: {@code pass} null for MEMBERSHIP. */
    public record View(Product product, PassScope pass) {}

    /** 04 GET /org/products/{id}: the row, its figures and its refund_required flags (newest first). */
    public record Detail(View view, Figures figures, List<Orders.RefundRequired> refundsRequired) {}

    /** A product's figures, by kind. */
    public sealed interface Figures permits PassFigures, MembershipFigures {}

    /**
     * PASS: passes by status, Σ total_mad of its PAID purchase orders, and per series event at the zone's venue that
     * isn't CLOSED (by starts_at) its ticket orders, the ACTIVE passes and Σ remaining of its PASS categories in the
     * zone.
     */
    public record PassFigures(PassCounts passes, long revenueMad, List<EventFigures> events,
                              Passes.InstallmentFigures installments) implements Figures {}

    public record PassCounts(long pending, long active, long suspended) {}

    public record EventFigures(int eventId, String title, Instant startsAt, Event.Status status, long issued,
                               long expected, long passRemaining) {}

    /** MEMBERSHIP: live members paid past now, Σ total_mad of its PAID orders. */
    public record MembershipFigures(long paidMembers, long revenueMad) implements Figures {}

    /**
     * One way to pay (04 §Passes Installments): {@code installments} payments of {@code amountsMad}, every
     * {@code interval} (null for the in-full plan).
     */
    public record Plan(int installments, List<Long> amountsMad, Duration interval) {}

    /**
     * The public row: its organizer; {@code timezone} the zone venue's ({@code Africa/Casablanca} for MEMBERSHIP);
     * {@code pass} and {@code events} (the series' published, upcoming events at the zone's venue) null for
     * MEMBERSHIP; {@code payment} as an event's, closing at the product's sales_close_at; {@code plans} as
     * {@link #plans}; {@code installment} on an installment order only (04 GET /fan/orders/{id}), else null.
     */
    public record FanView(Product product, Organizer organizer, String timezone, PassScope pass,
                          List<Event.SeriesEvent> events, PaymentMethods.Options payment, List<Plan> plans,
                          InstallmentOf installment) {}

    /** Which installment an installment order is: {@code seq} of {@code of}. */
    public record InstallmentOf(int seq, int of) {}

    private final Products products;
    private final Passes passes;
    private final Orders orders;
    private final EventService events;
    private final Inventory inventory;
    private final MembershipService memberships;
    private final OrganizerService organizers;
    private final AuditLog audit;
    private final JsonMapper json;
    private final Duration interval;

    ProductService(Products products, Passes passes, Orders orders, EventService events, Inventory inventory,
                   MembershipService memberships, OrganizerService organizers, AuditLog audit, JsonMapper json,
                   @Value("${passes.installment-interval}") Duration interval) {
        this.products = products;
        this.passes = passes;
        this.orders = orders;
        this.events = events;
        this.inventory = inventory;
        this.memberships = memberships;
        this.organizers = organizers;
        this.audit = audit;
        this.json = json;
        this.interval = interval;
    }

    // ---------- organizer ----------

    public List<View> list(UUID org, Product.Kind kind) {
        return products.list(org, kind).stream().map(p -> view(org, p)).toList();
    }

    public View get(UUID org, UUID productId) {
        return view(org, products.find(org, productId).orElseThrow(ProductService::notFound));
    }

    /** 04 GET /org/products/{id}: the row with its figures and refund_required flags. Read without locks. */
    // ponytail: one prices read per series event (a season has a few dozen); batch by event ids if a series grows.
    public Detail detail(UUID org, UUID productId) {
        View v = get(org, productId);
        Product p = v.product();
        long revenue = products.revenue(org, p.id());
        Figures figures;
        if (v.pass() == null) {
            figures = new MembershipFigures(memberships.paidMembers(org), revenue);
        } else {
            Map<Pass.Status, Long> byStatus = passes.countByStatus(org, p.id());
            long active = byStatus.getOrDefault(Pass.Status.ACTIVE, 0L);
            Map<Integer, Long> issued = passes.ticketOrdersByEvent(org, p.id());
            List<EventFigures> list = events.seriesEventsNotClosed(org, p.seriesId(), v.pass().zone().venueId())
                    .stream().map(e -> new EventFigures(e.id(), e.title(), e.startsAt(), e.status(),
                            issued.getOrDefault(e.id(), 0L), active, inventory.prices(org, e.id()).stream()
                                    .filter(c -> c.audience() == Event.Audience.PASS && c.zoneId() == p.zoneId())
                                    .mapToLong(Inventory.SalePrice::remaining).sum()))
                    .toList();
            figures = new PassFigures(new PassCounts(byStatus.getOrDefault(Pass.Status.PENDING, 0L), active,
                    byStatus.getOrDefault(Pass.Status.SUSPENDED, 0L)), revenue, list,
                    passes.installmentFigures(org, p.id()));
        }
        return new Detail(v, figures, orders.productRefundsRequired(org, p.id()));
    }

    /**
     * 04 POST /org/products: every field of the kind is required and the other kind's are refused (422 on each, the
     * products_kind rule); series and zone of this organizer; sales_close_at in the future; valid_until after it.
     */
    @Transactional
    public View create(UUID org, UUID userId, Input in) {
        Map<String, Object> fields = new LinkedHashMap<>();
        String name = name(in.name(), fields);
        Instant now = events.now(); // database clock
        if (!in.salesCloseAt().isAfter(now)) {
            fields.put("sales_close_at", "must be in the future");
        }
        boolean pass = in.kind() == Product.Kind.PASS;
        need(pass, in.quota(), "quota", fields);
        need(pass, in.seriesId(), "series_id", fields);
        need(pass, in.zoneId(), "zone_id", fields);
        need(!pass, in.validUntil(), "valid_until", fields);
        if (pass && in.seriesId() != null && events.findSeries(org, in.seriesId()).isEmpty()) {
            fields.put("series_id", "unknown series");
        }
        if (pass && in.zoneId() != null && events.zoneAt(org, in.zoneId()).isEmpty()) {
            fields.put("zone_id", "unknown zone");
        }
        checkValidUntil(in.validUntil(), in.salesCloseAt(), fields);
        int installments = maxInstallments(pass, in.maxInstallments(), 1, fields);
        if (!fields.isEmpty()) {
            throw invalid(fields);
        }
        Product p = products.insert(org, new Product(null, org, in.kind(), name, in.priceMad(),
                Boolean.TRUE.equals(in.onSale()), in.salesCloseAt(), in.quota(), 0, in.seriesId(), in.zoneId(),
                in.validUntil(), installments, null));
        audit.staff(org, userId, "product.create", p.id().toString(), payload(p));
        return view(org, p);
    }

    /** 04 PATCH /org/products/{id}, under the product row lock; quota ≥ sold is checked by the write itself. */
    @Transactional
    public View update(UUID org, UUID userId, UUID productId, Input in) {
        Product p = products.lock(org, productId).orElseThrow(ProductService::notFound);
        Map<String, Object> fields = new LinkedHashMap<>();
        fixed(in.kind(), p.kind(), "kind", fields);
        fixed(in.seriesId(), p.seriesId(), "series_id", fields);
        fixed(in.zoneId(), p.zoneId(), "zone_id", fields);
        boolean pass = p.kind() == Product.Kind.PASS;
        if (!pass && in.quota() != null) {
            fields.put("quota", "only for PASS");
        }
        if (pass && in.validUntil() != null) {
            fields.put("valid_until", "only for MEMBERSHIP");
        }
        String name = in.name() == null ? p.name() : name(in.name(), fields);
        Instant close = or(in.salesCloseAt(), p.salesCloseAt());
        if (in.salesCloseAt() != null && !in.salesCloseAt().equals(p.salesCloseAt())
                && !in.salesCloseAt().isAfter(events.now())) {
            fields.put("sales_close_at", "must be in the future");
        }
        Instant validUntil = or(in.validUntil(), p.validUntil());
        checkValidUntil(validUntil, close, fields);
        int installments = maxInstallments(pass, in.maxInstallments(), p.maxInstallments(), fields);
        if (!fields.isEmpty()) {
            throw invalid(fields);
        }
        Product updated = products.update(org, new Product(p.id(), org, p.kind(), name, or(in.priceMad(), p.priceMad()),
                        or(in.onSale(), p.onSale()), close, or(in.quota(), p.quota()), p.sold(), p.seriesId(),
                        p.zoneId(), validUntil, installments, p.createdAt()))
                .orElseThrow(() -> new ApiException("QUOTA_BELOW_SOLD", HttpStatus.UNPROCESSABLE_CONTENT,
                        "Quota is below the passes already sold"));
        audit.staff(org, userId, "product.update", p.id().toString(), payload(updated));
        return view(org, updated);
    }

    // ---------- public ----------

    /** 04 GET /fan/products: the organizer (listed or not; unknown → 404)'s products on sale. */
    public List<FanView> onSale(UUID org, Product.Kind kind) {
        Organizer organizer = organizers.get(org);
        Instant now = events.now();
        return products.onSale(org, kind).stream().map(p -> fanView(p, organizer, now)).toList();
    }

    /** 04 GET /fan/products/{id}: unknown or not on sale → 404. */
    public FanView onSale(UUID productId) {
        Product p = products.onSale(productId).orElseThrow(ProductService::notFound);
        return fanView(p, organizers.get(p.organizerId()), events.now());
    }

    // ---------- orders (sales) ----------

    /** The organizer's product, on sale or not (a product order's, which keeps it by FK). */
    public Product product(UUID org, UUID productId) {
        return products.find(org, productId).orElseThrow();
    }

    /** A product order's product as the fan sees it, read even once it is off sale (04 GET /fan/orders/{id}). */
    public FanView ofOrder(UUID org, UUID productId) {
        return fanView(product(org, productId), organizers.get(org), events.now());
    }

    /**
     * {@link #ofOrder(UUID, UUID)} for the order {@code orderId} of the pass {@code passId} (null: no pass). On a pass
     * paid in installments (a schedule of 2 or more), every order that pays one gets {@code installment}: the purchase
     * order {@code {1, n}}; any other order {@code seq} = the installment it paid, else the earliest unpaid one, else the
     * last. {@code of} = the schedule's length. For {@code seq > 1} (not the purchase), the payment methods ignore the
     * product's sales close (cash is held now + 24 h, as on /pay), so {@code cash_until} is null. A pass paid in full:
     * null, as any product order.
     */
    public FanView ofOrder(UUID org, UUID productId, UUID passId, UUID orderId) {
        FanView v = ofOrder(org, productId);
        List<Installment> schedule = passId == null ? List.of() : passes.installments(org, passId);
        if (schedule.size() < 2) {
            return v;
        }
        if (passes.purchaseOrder(org, passId).orElseThrow().equals(orderId)) {
            return new FanView(v.product(), v.organizer(), v.timezone(), v.pass(), v.events(), v.payment(), v.plans(),
                    new InstallmentOf(1, schedule.size()));
        }
        int seq = schedule.stream().filter(i -> orderId.equals(i.orderId())).findFirst()
                .or(() -> schedule.stream().filter(i -> !i.paid()).findFirst())
                .map(Installment::seq).orElse(schedule.size());
        PaymentMethods.Options cash = events.paymentOptions(org, events.now().plus(INSTALLMENT_CASH_HOLD));
        return new FanView(v.product(), v.organizer(), v.timezone(), v.pass(), v.events(),
                new PaymentMethods.Options(cash.methods(), null), v.plans(), new InstallmentOf(seq, schedule.size()));
    }

    /**
     * 04 §Passes Installments {@code plans}: in full, plus a PASS's {@code max_installments} payments every
     * passes.installment-interval when that is above 1, its last due date ({@code now + (n − 1) × interval}) falls on
     * or before the series' ends_on (to the end of that day in the zone venue's zone), and the price splits into
     * amounts above 0 (price ≥ n).
     */
    List<Plan> plans(Product p, PassScope scope, Instant now) {
        List<Plan> plans = new ArrayList<>(List.of(new Plan(1, List.of(p.priceMad()), null)));
        int n = p.maxInstallments();
        if (scope != null && n > 1 && p.priceMad() >= n) {
            Instant seasonEnd = scope.series().endsOn().plusDays(1).atStartOfDay(ZoneId.of(scope.zone().timezone()))
                    .toInstant();
            if (now.plus(interval.multipliedBy(n - 1L)).isBefore(seasonEnd)) {
                plans.add(new Plan(n, Installments.split(p.priceMad(), n), interval));
            }
        }
        return plans;
    }

    // ponytail: 2 catalog reads per product (3 on the fan side); batch them by series and zone if a club sells many.
    private View view(UUID org, Product p) {
        return new View(p, scope(org, p));
    }

    private FanView fanView(Product p, Organizer organizer, Instant now) {
        UUID org = p.organizerId();
        PassScope scope = scope(org, p);
        return new FanView(p, organizer, scope == null ? Venue.DEFAULT_TIMEZONE : scope.zone().timezone(), scope,
                scope == null ? null : events.seriesEvents(org, p.seriesId(), scope.zone().venueId(), true),
                events.paymentOptions(org, p.salesCloseAt()), plans(p, scope, now), null);
    }

    /** The FKs and the create checks keep both of this organizer, so they are always found. */
    PassScope scope(UUID org, Product p) {
        return p.kind() != Product.Kind.PASS ? null : new PassScope(events.findSeries(org, p.seriesId()).orElseThrow(),
                events.zoneAt(org, p.zoneId()).orElseThrow());
    }

    // ---------- rules ----------

    private static String name(String name, Map<String, Object> fields) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > NAME_MAX) {
            fields.put("name", "must be 1-60 characters");
        }
        return n;
    }

    /** {@code value} is required when {@code wanted}, refused otherwise (03 products_kind). */
    private static void need(boolean wanted, Object value, String field, Map<String, Object> fields) {
        if (wanted && value == null) {
            fields.put(field, "is required for this kind");
        } else if (!wanted && value != null) {
            fields.put(field, "is not used by this kind");
        }
    }

    private static <T> void fixed(T sent, T current, String field, Map<String, Object> fields) {
        if (sent != null && !sent.equals(current)) {
            fields.put(field, "is fixed after create");
        }
    }

    /**
     * {@code max_installments}: 1-10 on a PASS (absent = {@code current}); a MEMBERSHIP keeps 1 (another value → 422).
     */
    private static int maxInstallments(boolean pass, Integer sent, int current, Map<String, Object> fields) {
        if (sent == null) {
            return current;
        }
        if (!pass && sent != 1) {
            fields.put("max_installments", "only for PASS");
        } else if (sent < 1 || sent > Installments.MAX) {
            fields.put("max_installments", "must be 1-10");
        }
        return sent;
    }

    private static void checkValidUntil(Instant validUntil, Instant salesCloseAt, Map<String, Object> fields) {
        if (validUntil != null && !validUntil.isAfter(salesCloseAt)) {
            fields.putIfAbsent("valid_until", "must be after sales_close_at");
        }
    }

    /** product.create / product.update: the product's fields, no fan data. */
    private String payload(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", p.kind().name());
        m.put("name", p.name());
        m.put("price_mad", p.priceMad());
        m.put("on_sale", p.onSale());
        m.put("sales_close_at", p.salesCloseAt().toString());
        m.put("quota", p.quota());
        m.put("series_id", Objects.toString(p.seriesId(), null));
        m.put("zone_id", p.zoneId());
        m.put("valid_until", Objects.toString(p.validUntil(), null));
        m.put("max_installments", p.maxInstallments());
        return json.writeValueAsString(m);
    }

    private static ApiException invalid(Map<String, Object> fields) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", fields));
    }

    private static ApiException notFound() {
        return ApiException.notFound("Product not found");
    }

    private static <T> T or(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
