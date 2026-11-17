package ma.jomhor.sales.promos.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.sales.promos.domain.Discount;
import ma.jomhor.sales.promos.domain.PromoCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Promo codes (docs/04-api.md §Promo codes): the organizer's /org/events/{id}/promo-codes*, and the code step of POST
 * /fan/orders ({@link #claim}, {@link #discount}). {@code org} is always the organizer of the event.
 */
@Service
public class PromoCodeService {

    static final int PER_ACCOUNT_MAX = 100;

    /** POST or PATCH body as sent. On PATCH only active, max_uses and per_account_max may change (null = unchanged). */
    public record Input(String code, PromoCode.Kind kind, Integer percent, Long amountMad, UUID priceCategoryId,
                        Integer maxUses, Integer perAccountMax, Boolean active) {}

    /** A list row: the code, its figures and its category (null = the whole event). */
    public record View(PromoCodes.Listed listed, Event.PriceCategory category) {}

    private final PromoCodes promos;
    private final EventService events;
    private final AuditLog audit;
    private final JsonMapper json;

    PromoCodeService(PromoCodes promos, EventService events, AuditLog audit, JsonMapper json) {
        this.promos = promos;
        this.events = events;
        this.audit = audit;
        this.json = json;
    }

    /** 04 GET: by code. Unknown or another organizer's event → 404. */
    public List<View> list(UUID org, int eventId) {
        Map<UUID, Event.PriceCategory> categories = categories(org, eventId);
        return promos.list(org, eventId).stream().map(l -> view(l, categories)).toList();
    }

    /**
     * 04 POST, under the event row lock (like the price writes, so the category checked here can't be deleted or the
     * status change before the insert): event of this organizer (404); the fields (422 on each); the event DRAFT or
     * ON_SALE (409 EVENT_NOT_EDITABLE); the code unused in this event (422 TAKEN on code, by the unique key). Audit
     * promo.create.
     */
    @Transactional
    public View create(UUID org, UUID user, int eventId, Input in) {
        Event e = events.lock(org, eventId); // as the price writes: a category delete or status change waits
        Map<UUID, Event.PriceCategory> categories = categories(org, eventId);
        Map<String, Object> fields = new LinkedHashMap<>();
        String code = PromoCode.normalize(in.code());
        if (!PromoCode.wellFormed(code)) {
            fields.put("code", "must be 4-20 letters A-Z or digits");
        }
        if (in.kind() == null) {
            fields.put("kind", "is required");
        } else {
            boolean percent = in.kind() == PromoCode.Kind.PERCENT;
            need(percent, in.percent(), "percent", fields);
            need(!percent, in.amountMad(), "amount_mad", fields);
        }
        if (in.percent() != null && (in.percent() < 1 || in.percent() > 99)) {
            fields.putIfAbsent("percent", "must be 1-99");
        }
        if (in.amountMad() != null && in.amountMad() <= 0) {
            fields.putIfAbsent("amount_mad", "must be above 0");
        }
        Event.PriceCategory category = in.priceCategoryId() == null ? null : categories.get(in.priceCategoryId());
        if (in.priceCategoryId() != null && (category == null || !category.audience().sellable())) {
            fields.put("price_category_id", "must be an ALL or MEMBERS category of the event");
        }
        caps(in, fields);
        if (!fields.isEmpty()) {
            throw invalid(fields, null);
        }
        if (e.status() != Event.Status.DRAFT && e.status() != Event.Status.ON_SALE) {
            throw new ApiException("EVENT_NOT_EDITABLE", HttpStatus.CONFLICT,
                    "Event can't be changed in its current status");
        }
        PromoCode p = promos.insert(org, new PromoCode(null, org, eventId, in.priceCategoryId(), code, in.kind(),
                        in.percent(), in.amountMad(), in.maxUses(), in.perAccountMax() == null ? 1 : in.perAccountMax(),
                        !Boolean.FALSE.equals(in.active()), null))
                .orElseThrow(() -> invalid(Map.of("code", "already used in this event"), "TAKEN"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", p.code());
        payload.put("kind", p.kind().name());
        payload.put("percent", p.percent());
        payload.put("amount_mad", p.amountMad());
        payload.put("price_category_id", Objects.toString(p.priceCategoryId(), null));
        payload.putAll(mutable(p));
        audit.staff(org, user, "promo.create", p.id().toString(), json.writeValueAsString(payload));
        return new View(new PromoCodes.Listed(p, 0, 0, 0), category);
    }

    /**
     * 04 PATCH: partial; code, kind, percent, amount_mad and price_category_id are fixed (a different value → 422).
     * Applies to new orders only. Unknown event or code → 404. Audit promo.update.
     */
    @Transactional
    public View update(UUID org, UUID user, int eventId, UUID promoId, Input in) {
        Map<UUID, Event.PriceCategory> categories = categories(org, eventId);
        PromoCode p = promos.find(org, eventId, promoId).orElseThrow(() -> ApiException.notFound("Promo code not found"))
                .promo();
        Map<String, Object> fields = new LinkedHashMap<>();
        fixed(in.code() == null ? null : PromoCode.normalize(in.code()), p.code(), "code", fields);
        fixed(in.kind(), p.kind(), "kind", fields);
        fixed(in.percent(), p.percent(), "percent", fields);
        fixed(in.amountMad(), p.amountMad(), "amount_mad", fields);
        fixed(in.priceCategoryId(), p.priceCategoryId(), "price_category_id", fields);
        caps(in, fields);
        if (!fields.isEmpty()) {
            throw invalid(fields, null);
        }
        // Only the fields that change are written (null = keep the column), so concurrent PATCHes of different fields
        // both survive; nothing changed → no write and no audit.
        Boolean active = changed(in.active(), p.active());
        Integer maxUses = changed(in.maxUses(), p.maxUses());
        Integer perAccount = changed(in.perAccountMax(), p.perAccountMax());
        if (active == null && maxUses == null && perAccount == null) {
            return view(promos.find(org, eventId, promoId).orElseThrow(), categories);
        }
        promos.update(org, eventId, promoId, active, maxUses, perAccount);
        PromoCodes.Listed updated = promos.find(org, eventId, promoId).orElseThrow();
        audit.staff(org, user, "promo.update", p.id().toString(), json.writeValueAsString(mutable(updated.promo())));
        return view(updated, categories);
    }

    // ---------- orders (POST /fan/orders) ----------

    /**
     * The promo step of POST /fan/orders, in its transaction, after the per-account locks (the buyer's included) and
     * before the categories: the event's active code {@code code} (normalized) row-locked, else UNKNOWN; a discount
     * above 0 on {@code own} (the buyer's order lines at the current prices), else NOT_APPLICABLE; its uses below
     * max_uses (USED_UP), then the buyer's below per_account_max (ACCOUNT_LIMIT). Both counts are exact: every order with
     * the code takes this row lock first, and the buyer's per-account lock is held. Refused with 409 PROMO_REFUSED.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PromoCode claim(UUID org, int eventId, UUID buyer, String code, List<Order.Item> own) {
        PromoCode p = promos.lockActive(org, eventId, code).orElseThrow(() -> refused("UNKNOWN"));
        discount(p, own);
        if (p.maxUses() != null && promos.uses(org, p.id(), null) >= p.maxUses()) {
            throw refused("USED_UP");
        }
        if (promos.uses(org, p.id(), buyer) >= p.perAccountMax()) {
            throw refused("ACCOUNT_LIMIT");
        }
        return p;
    }

    /**
     * The discount of {@code p} on the buyer's order lines {@code own} (04 §Promo codes Discount): eligible = its places
     * in the code's scope (the event, or its category: one that became PASS or INVITE is never in an order, so matches
     * no place). 0 → 409 PROMO_REFUSED NOT_APPLICABLE.
     */
    public long discount(PromoCode p, List<Order.Item> own) {
        long eligible = own.stream()
                .filter(i -> p.priceCategoryId() == null || p.priceCategoryId().equals(i.priceCategoryId()))
                .mapToLong(i -> i.qty() * i.unitPriceMad()).sum();
        long discount = Discount.of(p, eligible, own.stream().mapToLong(i -> i.qty() * i.unitPriceMad()).sum());
        if (discount <= 0) {
            throw refused("NOT_APPLICABLE");
        }
        return discount;
    }

    /** 04 PROMO_REFUSED: field and reason only, the same body for every code that reads as {@code reason}. */
    private static ApiException refused(String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", "promo_code");
        details.put("reason", reason);
        return new ApiException("PROMO_REFUSED", HttpStatus.CONFLICT, "This promo code can't be used", details);
    }

    /** The event's categories by id; unknown or another organizer's event → 404. */
    private Map<UUID, Event.PriceCategory> categories(UUID org, int eventId) {
        return events.prices(org, eventId).stream()
                .collect(Collectors.toMap(Event.PriceCategory::id, Function.identity()));
    }

    private static View view(PromoCodes.Listed l, Map<UUID, Event.PriceCategory> categories) {
        UUID id = l.promo().priceCategoryId();
        return new View(l, id == null ? null : categories.get(id));
    }

    /** max_uses > 0 (absent: none, or unchanged), per_account_max 1-100. */
    private static void caps(Input in, Map<String, Object> fields) {
        if (in.maxUses() != null && in.maxUses() <= 0) {
            fields.put("max_uses", "must be above 0");
        }
        if (in.perAccountMax() != null && (in.perAccountMax() < 1 || in.perAccountMax() > PER_ACCOUNT_MAX)) {
            fields.put("per_account_max", "must be 1-100");
        }
    }

    /** promo.create / promo.update: the code's changeable fields. */
    private static Map<String, Object> mutable(PromoCode p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_uses", p.maxUses());
        m.put("per_account_max", p.perAccountMax());
        m.put("active", p.active());
        return m;
    }

    /** {@code value} is required when {@code wanted}, refused otherwise (03 promo_codes_kind). */
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

    private static ApiException invalid(Map<String, Object> fields, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", fields);
        if (reason != null) {
            details.put("reason", reason);
        }
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body", details);
    }

    /** {@code sent} when it differs from {@code current}, else null (unchanged). */
    private static <T> T changed(T sent, T current) {
        return sent == null || sent.equals(current) ? null : sent;
    }
}
