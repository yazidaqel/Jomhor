package ma.jomhor.catalog.application;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Event.PriceCategory;
import ma.jomhor.catalog.domain.Event.Status;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Events, their price categories and publishing (docs/04-api.md §Organizer, §Event metadata). {@code org} is
 * always OrgContext.organizerId(). Writes that read-then-check (PATCH, prices, publish) lock the event row
 * first, so they run one at a time per event.
 */
@Service
public class EventService {

    /** Refunds and cancellations freeze at T0 − 60 min (plan Q6, spec §Threat model). */
    static final Duration REFUND_FREEZE = Duration.ofMinutes(60);
    static final int METADATA_MAX_BYTES = 4096;
    static final int DEFAULT_PER_ACCOUNT_LIMIT = 4;

    /** Create or PATCH input. On PATCH a null field is unchanged; venue_id and event_type must match if sent. */
    public record EventInput(UUID venueId, Event.Type eventType, String title, Instant startsAt, Instant doorsOpenAt,
                             Event.ReentryPolicy reentryPolicy, Integer perAccountLimit, JsonNode metadata) {}

    private final Events events;
    private final KeyEncryptor keys;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public EventService(Events events, KeyEncryptor keys, AuditLog audit) {
        this.events = events;
        this.keys = keys;
        this.audit = audit;
    }

    public List<Event.Summary> list(UUID org, Status status, Instant from) {
        return events.list(org, status, from);
    }

    /**
     * New DRAFT with a fresh K_e (32 random bytes), encrypted and stored by the same insert, never returned.
     * The id is taken first: the ciphertext is bound to it.
     */
    @Transactional
    public Event create(UUID org, EventInput in) {
        String metadata = metadata(in.eventType(), in.metadata());
        checkDoors(in.doorsOpenAt(), in.startsAt());
        byte[] key = new byte[32];
        random.nextBytes(key);
        int eventId = events.nextId();
        byte[] keyEnc;
        try {
            keyEnc = keys.encrypt(key, org, eventId);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        events.insert(org, eventId, in.venueId(), in.eventType(), in.title().trim(), in.startsAt(), in.doorsOpenAt(),
                        in.reentryPolicy() == null ? Event.ReentryPolicy.NONE : in.reentryPolicy(),
                        in.perAccountLimit() == null ? DEFAULT_PER_ACCOUNT_LIMIT : in.perAccountLimit(), metadata, keyEnc)
                .orElseThrow(() -> invalid("venue_id", "unknown venue"));
        // Re-read: the insert's RETURNING sees its statement snapshot, so a venue timezone change it waited for
        // (venue lock vs FK key share) would be missing from the 201 but present in GET.
        return events.find(org, eventId).orElseThrow();
    }

    public Event get(UUID org, int eventId) {
        return events.find(org, eventId).orElseThrow(notFound("Event"));
    }

    /**
     * The only way to K_e for other modules (tickets: K_t, gate: manifest). The 32-byte key is valid only
     * inside {@code fn} and zeroed when it returns or throws: don't keep a reference to it. Unknown event or
     * another organizer's → 404.
     */
    public <T> T withEventKey(UUID org, int eventId, Function<byte[], T> fn) {
        byte[] enc = events.eventKeyEnc(org, eventId).orElseThrow(notFound("Event"));
        byte[] key = keys.decrypt(enc, org, eventId);
        try {
            return fn.apply(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Transactional
    public Event update(UUID org, int eventId, EventInput in) {
        Event e = events.lock(org, eventId).orElseThrow(notFound("Event"));
        if (e.status() != Status.DRAFT && e.status() != Status.ON_SALE) {
            throw notEditable();
        }
        if (in.venueId() != null && !in.venueId().equals(e.venueId())) {
            throw invalid("venue_id", "is fixed after create");
        }
        if (in.eventType() != null && in.eventType() != e.eventType()) {
            throw invalid("event_type", "is fixed after create");
        }
        Instant startsAt = or(in.startsAt(), e.startsAt());
        Instant doors = or(in.doorsOpenAt(), e.doorsOpenAt());
        checkDoors(doors, startsAt);
        String metadata = in.metadata() == null || in.metadata().isNull()
                ? e.metadata() : metadata(e.eventType(), in.metadata());
        Instant cutoff = e.status() == Status.ON_SALE ? startsAt.minus(REFUND_FREEZE) : e.refundCutoffAt();
        return events.update(org, new Event(e.id(), e.venueId(), e.timezone(), e.seriesId(), e.eventType(),
                in.title() == null ? e.title() : in.title().trim(), startsAt, doors, e.status(), cutoff,
                or(in.reentryPolicy(), e.reentryPolicy()), or(in.perAccountLimit(), e.perAccountLimit()), metadata,
                e.createdAt()));
    }

    /** DRAFT → ON_SALE under the event lock: of two concurrent publishes, the second sees ON_SALE and gets 409. */
    @Transactional
    public Event publish(UUID org, UUID userId, int eventId) {
        Event e = events.lock(org, eventId).orElseThrow(notFound("Event"));
        if (e.status() != Status.DRAFT) {
            throw notEditable();
        }
        if (!e.startsAt().isAfter(Instant.now())) {
            throw publishRefused("STARTS_IN_PAST", "starts_at", "must be in the future to publish");
        }
        if (!events.hasQuota(org, eventId)) {
            throw publishRefused("NO_QUOTA", "prices", "at least one price category with quota > 0 is needed to publish");
        }
        Event published = events.update(org, e.withStatus(Status.ON_SALE, e.startsAt().minus(REFUND_FREEZE)));
        audit.staff(org, userId, "event.publish", String.valueOf(eventId));
        return published;
    }

    // ---------- prices ----------

    public List<PriceCategory> prices(UUID org, int eventId) {
        get(org, eventId);
        return events.prices(org, eventId);
    }

    @Transactional
    public PriceCategory createPrice(UUID org, int eventId, int zoneId, String name, long priceMad, int quota) {
        Event e = lockForPrices(org, eventId);
        int capacity = events.zoneCapacity(org, e.venueId(), zoneId)
                .orElseThrow(() -> invalid("zone_id", "must be a zone of the event's venue"));
        checkCapacity(capacity, events.quotaOnZone(org, eventId, zoneId, null) + quota);
        return nameUnique(() -> events.insertPrice(org, eventId, zoneId, name.trim(), priceMad, quota));
    }

    @Transactional
    public PriceCategory updatePrice(UUID org, int eventId, UUID priceId, Integer zoneId, String name, Long priceMad,
                                     Integer quota) {
        Event e = lockForPrices(org, eventId);
        PriceCategory p = events.findPrice(org, eventId, priceId).orElseThrow(notFound("Price category"));
        if (zoneId != null && zoneId != p.zoneId()) {
            throw invalid("zone_id", "is fixed after create");
        }
        if (quota != null) {
            // The zone can't be gone: the category's FK keeps it.
            int capacity = events.zoneCapacity(org, e.venueId(), p.zoneId()).orElseThrow();
            checkCapacity(capacity, events.quotaOnZone(org, eventId, p.zoneId(), priceId) + quota);
        }
        return nameUnique(() -> events.updatePrice(org, eventId, priceId, name == null ? null : name.trim(), priceMad, quota))
                .orElseThrow(() -> new ApiException("QUOTA_BELOW_SOLD", HttpStatus.UNPROCESSABLE_CONTENT,
                        "Quota is below the places already sold"));
    }

    /** Only unsold categories; one referenced by order items (even expired ones) is in use too. */
    @Transactional
    public void deletePrice(UUID org, int eventId, UUID priceId) {
        lockForPrices(org, eventId);
        events.findPrice(org, eventId, priceId).orElseThrow(notFound("Price category"));
        boolean deleted;
        try {
            deleted = events.deletePriceIfUnsold(org, eventId, priceId);
        } catch (DataIntegrityViolationException ex) {
            deleted = false;
        }
        if (!deleted) {
            throw new ApiException("PRICE_IN_USE", HttpStatus.CONFLICT, "Price category has sales");
        }
    }

    private Event lockForPrices(UUID org, int eventId) {
        Event e = events.lock(org, eventId).orElseThrow(notFound("Event"));
        if (e.status() != Status.DRAFT && e.status() != Status.ON_SALE) {
            throw notEditable();
        }
        return e;
    }

    private static void checkCapacity(int capacity, long totalQuota) {
        if (totalQuota > capacity) {
            throw new ApiException("QUOTA_EXCEEDS_CAPACITY", HttpStatus.UNPROCESSABLE_CONTENT,
                    "Total quota of the zone exceeds its capacity");
        }
    }

    // ponytail: 04 has no code for a duplicate (event, zone, name); 422 on `name` until the contract names one.
    private static <T> T nameUnique(Supplier<T> write) {
        try {
            return write.get();
        } catch (DuplicateKeyException e) {
            throw invalid("name", "already used for this zone");
        }
    }

    // ---------- validation ----------

    /**
     * 04 §Event metadata: a JSON object of at most 4 KB, known keys type-checked per event_type, unknown keys
     * kept. Returns the JSON to store; absent → {}.
     */
    static String metadata(Event.Type type, JsonNode m) {
        if (m == null || m.isNull()) {
            return "{}";
        }
        if (!m.isObject()) {
            throw invalid("metadata", "must be a JSON object");
        }
        String json = m.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > METADATA_MAX_BYTES) {
            throw invalid("metadata", "must be at most 4 KB");
        }
        Predicate<JsonNode> string = JsonNode::isString;
        Predicate<JsonNode> integer = v -> v.isIntegralNumber() && v.canConvertToInt();
        switch (type) {
            case FOOTBALL_MATCH -> {
                check(m, "opponent", string, "must be a string");
                check(m, "competition", string, "must be a string");
                check(m, "matchday", integer, "must be an integer");
            }
            case CONCERT, FESTIVAL_DAY -> check(m, "headliner", string, "must be a string");
            case CONFERENCE, OTHER -> { }
        }
        return json;
    }

    private static void check(JsonNode m, String key, Predicate<JsonNode> ok, String message) {
        JsonNode v = m.get(key);
        if (v != null && !ok.test(v)) {
            throw invalid("metadata." + key, message);
        }
    }

    private static void checkDoors(Instant doorsOpenAt, Instant startsAt) {
        if (doorsOpenAt != null && doorsOpenAt.isAfter(startsAt)) {
            throw invalid("doors_open_at", "must not be after starts_at");
        }
    }

    // ---------- errors ----------

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }

    // ponytail: 04 names no code for unmet publish preconditions; VALIDATION_FAILED + details.reason meanwhile.
    private static ApiException publishRefused(String reason, String field, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("fields", Map.of(field, message));
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Event can't be published yet",
                details);
    }

    private static ApiException notEditable() {
        return new ApiException("EVENT_NOT_EDITABLE", HttpStatus.CONFLICT, "Event can't be changed in its current status");
    }

    private static Supplier<ApiException> notFound(String what) {
        return () -> ApiException.notFound(what + " not found");
    }

    private static <T> T or(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
