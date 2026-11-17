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
import ma.jomhor.sales.promos.domain.PromoCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Promo codes, organizer side (docs/04-api.md §Promo codes, /org/events/{id}/promo-codes*). {@code org} is always
 * OrgContext.organizerId(), {@code user} the caller. The order path (a code on POST /fan/orders) is not here.
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
     * 04 POST: event of this organizer (404); the fields (422 on each); the event DRAFT or ON_SALE (409
     * EVENT_NOT_EDITABLE); the code unused in this event (422 TAKEN on code, by the unique key). Audit promo.create.
     */
    @Transactional
    public View create(UUID org, UUID user, int eventId, Input in) {
        Event e = events.get(org, eventId);
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
        PromoCode updated = new PromoCode(p.id(), org, eventId, p.priceCategoryId(), p.code(), p.kind(), p.percent(),
                p.amountMad(), or(in.maxUses(), p.maxUses()), or(in.perAccountMax(), p.perAccountMax()),
                or(in.active(), p.active()), p.createdAt());
        promos.update(org, updated);
        audit.staff(org, user, "promo.update", p.id().toString(), json.writeValueAsString(mutable(updated)));
        return view(promos.find(org, eventId, promoId).orElseThrow(), categories);
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

    private static <T> T or(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
