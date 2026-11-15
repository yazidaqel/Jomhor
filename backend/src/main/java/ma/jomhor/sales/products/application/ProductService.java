package ma.jomhor.sales.products.application;

import java.time.Instant;
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
import ma.jomhor.sales.products.domain.Pass;
import ma.jomhor.sales.products.domain.Product;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.domain.Organizer;
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

    /**
     * POST or PATCH body. On PATCH a null field is unchanged; kind, series_id and zone_id are fixed after create (a
     * different value → 422).
     */
    public record Input(Product.Kind kind, String name, Long priceMad, Integer quota, UUID seriesId, Integer zoneId,
                        Instant salesCloseAt, Instant validUntil, Boolean onSale) {}

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
    public record PassFigures(PassCounts passes, long revenueMad, List<EventFigures> events) implements Figures {}

    public record PassCounts(long pending, long active, long suspended) {}

    public record EventFigures(int eventId, String title, Instant startsAt, Event.Status status, long issued,
                               long expected, long passRemaining) {}

    /** MEMBERSHIP: live members paid past now, Σ total_mad of its PAID orders. */
    public record MembershipFigures(long paidMembers, long revenueMad) implements Figures {}

    /**
     * The public row: its organizer; {@code timezone} the zone venue's ({@code Africa/Casablanca} for MEMBERSHIP);
     * {@code pass} and {@code events} (the series' published, upcoming events at the zone's venue) null for
     * MEMBERSHIP; {@code payment} as an event's, closing at the product's sales_close_at.
     */
    public record FanView(Product product, Organizer organizer, String timezone, PassScope pass,
                          List<Event.SeriesEvent> events, PaymentMethods.Options payment) {}

    private final Products products;
    private final Passes passes;
    private final Orders orders;
    private final EventService events;
    private final Inventory inventory;
    private final MembershipService memberships;
    private final OrganizerService organizers;
    private final AuditLog audit;
    private final JsonMapper json;

    ProductService(Products products, Passes passes, Orders orders, EventService events, Inventory inventory,
                   MembershipService memberships, OrganizerService organizers, AuditLog audit, JsonMapper json) {
        this.products = products;
        this.passes = passes;
        this.orders = orders;
        this.events = events;
        this.inventory = inventory;
        this.memberships = memberships;
        this.organizers = organizers;
        this.audit = audit;
        this.json = json;
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
                    byStatus.getOrDefault(Pass.Status.SUSPENDED, 0L)), revenue, list);
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
        if (!fields.isEmpty()) {
            throw invalid(fields);
        }
        Product p = products.insert(org, new Product(null, org, in.kind(), name, in.priceMad(),
                Boolean.TRUE.equals(in.onSale()), in.salesCloseAt(), in.quota(), 0, in.seriesId(), in.zoneId(),
                in.validUntil(), null));
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
        if (!fields.isEmpty()) {
            throw invalid(fields);
        }
        Product updated = products.update(org, new Product(p.id(), org, p.kind(), name, or(in.priceMad(), p.priceMad()),
                        or(in.onSale(), p.onSale()), close, or(in.quota(), p.quota()), p.sold(), p.seriesId(),
                        p.zoneId(), validUntil, p.createdAt()))
                .orElseThrow(() -> new ApiException("QUOTA_BELOW_SOLD", HttpStatus.UNPROCESSABLE_CONTENT,
                        "Quota is below the passes already sold"));
        audit.staff(org, userId, "product.update", p.id().toString(), payload(updated));
        return view(org, updated);
    }

    // ---------- public ----------

    /** 04 GET /fan/products: the organizer (listed or not; unknown → 404)'s products on sale. */
    public List<FanView> onSale(UUID org, Product.Kind kind) {
        Organizer organizer = organizers.get(org);
        return products.onSale(org, kind).stream().map(p -> fanView(p, organizer)).toList();
    }

    /** 04 GET /fan/products/{id}: unknown or not on sale → 404. */
    public FanView onSale(UUID productId) {
        Product p = products.onSale(productId).orElseThrow(ProductService::notFound);
        return fanView(p, organizers.get(p.organizerId()));
    }

    // ---------- orders (sales) ----------

    /** The organizer's product, on sale or not (a product order's, which keeps it by FK). */
    public Product product(UUID org, UUID productId) {
        return products.find(org, productId).orElseThrow();
    }

    /** A product order's product as the fan sees it, read even once it is off sale (04 GET /fan/orders/{id}). */
    public FanView ofOrder(UUID org, UUID productId) {
        return fanView(product(org, productId), organizers.get(org));
    }

    // ponytail: 2 catalog reads per product (3 on the fan side); batch them by series and zone if a club sells many.
    private View view(UUID org, Product p) {
        return new View(p, scope(org, p));
    }

    private FanView fanView(Product p, Organizer organizer) {
        UUID org = p.organizerId();
        PassScope scope = scope(org, p);
        return new FanView(p, organizer, scope == null ? Venue.DEFAULT_TIMEZONE : scope.zone().timezone(), scope,
                scope == null ? null : events.seriesEvents(org, p.seriesId(), scope.zone().venueId(), true),
                events.paymentOptions(org, p.salesCloseAt()));
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
