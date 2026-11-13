package ma.jomhor.sales.rooms.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.application.Orders.SalesLine;
import ma.jomhor.sales.rooms.domain.WaitingRoom;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Config;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Rule;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * /org/events/{id}/room* (docs/04-api.md §Organizer, §Waiting room): the organizer's side of the room. {@code org} is
 * always OrgContext.organizerId(); another organizer's event is 404. Writes lock the event row, then the room row (as
 * the event PATCH does), and invalidate this instance's memo after the commit.
 */
@Service
public class WaitingRoomService {

    /** The room's live figures (04 GET /org/events/{id}/room {@code figures}). */
    public record Figures(long joined, long waiting, long admitted, long admittedLastMin, long activeHolds,
                          long maxActiveHolds, long paid, long quota, long remaining, boolean soldOut,
                          Long estimatedDrainS) {}

    public record View(WaitingRoom room, Figures figures) {}

    /** PUT input; null = the default (30, 500, no rule). */
    public record Input(Integer preQueueMin, Integer admissionRatePerMin, Rule rule) {}

    /** A PUT's result: {@code created} → 201. */
    public record Put(boolean created, View view) {}

    private final WaitingRooms rooms;
    private final QueueEntries entries;
    private final RoomMemo memo;
    private final EventService events;
    private final Orders orders;
    private final AuditLog audit;
    private final long maxActiveHolds;
    private final TransactionTemplate tx;
    private final TransactionTemplate snapshot;

    WaitingRoomService(WaitingRooms rooms, QueueEntries entries, RoomMemo memo, EventService events, Orders orders,
                       AuditLog audit,
                       @Value("${sales.max-active-holds-per-event}") long maxActiveHolds,
                       PlatformTransactionManager transactions) {
        this.rooms = rooms;
        this.entries = entries;
        this.memo = memo;
        this.events = events;
        this.orders = orders;
        this.audit = audit;
        this.maxActiveHolds = maxActiveHolds;
        this.tx = new TransactionTemplate(transactions);
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** GET: empty when the event has no room. One snapshot for the room, holds, sales and stock. */
    public Optional<View> get(UUID org, int eventId) {
        return snapshot.execute(s -> {
            events.get(org, eventId);
            return rooms.find(org, eventId).map(r -> new View(r, figures(org, r)));
        });
    }

    /**
     * Create or replace. The event must be DRAFT or ON_SALE; at creation sale_starts_at must be set and in the future
     * (database clock). Once the room has left SCHEDULED only the rate may change; ENDED refuses everything.
     */
    public Put put(UUID org, UUID userId, int eventId, Input in) {
        return write(() -> {
            Event e = events.lock(org, eventId);
            if (e.status() != Event.Status.DRAFT && e.status() != Event.Status.ON_SALE) {
                throw new ApiException("EVENT_NOT_EDITABLE", HttpStatus.CONFLICT,
                        "Event can't be changed in its current status");
            }
            Config c = validate(in, e.saleStartsAt());
            Optional<Status> status = rooms.lock(org, eventId);
            if (status.isEmpty()) {
                if (e.saleStartsAt() == null || !e.saleStartsAt().isAfter(orders.now())) {
                    throw saleStartRequired();
                }
                rooms.insert(org, eventId, c);
                audit.staff(org, userId, "room.create", String.valueOf(eventId), payload(c));
                return new Put(true, view(org, eventId));
            }
            WaitingRoom current = rooms.find(org, eventId).orElseThrow();
            boolean onlyRate = current.config().preQueueMin() == c.preQueueMin()
                    && current.config().rule().equals(c.rule());
            if (status.get() == Status.ENDED || (status.get() != Status.SCHEDULED && !onlyRate)) {
                throw roomStatus(status.get());
            }
            rooms.update(org, eventId, c);
            audit.staff(org, userId, "room.update", String.valueOf(eventId), payload(c));
            return new Put(false, view(org, eventId));
        });
    }

    /** OPEN → PAUSED. */
    public View pause(UUID org, UUID userId, int eventId) {
        return transition(org, userId, eventId, "room.pause", Status.PAUSED, Status.OPEN);
    }

    /** PAUSED → OPEN. */
    public View resume(UUID org, UUID userId, int eventId) {
        return transition(org, userId, eventId, "room.resume", Status.OPEN, Status.PAUSED);
    }

    /** Any but ENDED → ENDED, irreversible: orders need no admission token afterwards. */
    public View end(UUID org, UUID userId, int eventId) {
        return transition(org, userId, eventId, "room.end", Status.ENDED,
                Status.SCHEDULED, Status.PRE_QUEUE, Status.OPEN, Status.PAUSED);
    }

    private View transition(UUID org, UUID userId, int eventId, String action, Status to, Status... from) {
        return write(() -> {
            events.get(org, eventId);
            Status status = rooms.lock(org, eventId).orElseThrow(() -> ApiException.notFound("Room not found"));
            if (!List.of(from).contains(status)) {
                throw roomStatus(status);
            }
            rooms.setStatus(org, eventId, to);
            audit.staff(org, userId, action, String.valueOf(eventId));
            return view(org, eventId);
        });
    }

    // ---------- reads ----------

    private View view(UUID org, int eventId) {
        WaitingRoom r = rooms.find(org, eventId).orElseThrow();
        return new View(r, figures(org, r));
    }

    /**
     * {@code waiting} = the ranked fans not yet admitted (counts of the last tick) plus the entries not ranked yet.
     * {@code paid} as GET /sales (places in PAID orders); {@code quota} and {@code remaining} over the price categories.
     */
    private Figures figures(UUID org, WaitingRoom r) {
        long paid = orders.salesLines(org, r.eventId()).stream().mapToLong(SalesLine::paid).sum();
        long quota = 0;
        long sold = 0;
        for (Event.PriceCategory p : events.prices(org, r.eventId())) {
            quota += p.quota();
            sold += p.sold();
        }
        long remaining = Math.max(0, quota - sold);
        long waiting = Math.max(0, r.rankedCount() - r.admittedThrough()) + entries.unranked(org, r.id());
        int rate = r.measuredRatePerMin();
        Long drain = rate == 0 ? null : (waiting * 60 + rate - 1) / rate;
        return new Figures(r.joinedCount(), waiting, r.admittedThrough(), rate, orders.activeHolds(org, r.eventId()),
                maxActiveHolds, paid, quota, remaining, remaining == 0, drain);
    }

    // ---------- rules ----------

    /** Ranges per docs/03-schema.sql; member_joined_before ≤ sale_starts_at. Every field error at once. */
    private static Config validate(Input in, Instant saleStartsAt) {
        int preQueue = in.preQueueMin() == null ? Config.DEFAULT_PRE_QUEUE_MIN : in.preQueueMin();
        int rate = in.admissionRatePerMin() == null ? Config.DEFAULT_ADMISSION_RATE_PER_MIN : in.admissionRatePerMin();
        Rule rule = in.rule() == null ? Rule.NONE : in.rule();
        Instant joinedBefore = rule.memberJoinedBefore();
        if (joinedBefore != null) { // stored to the microsecond: a re-sent value then compares equal to the stored one
            rule = new Rule(rule.minAccountAgeDays(), joinedBefore.truncatedTo(ChronoUnit.MICROS), rule.minEventsAttended());
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (preQueue < 0 || preQueue > 1440) {
            fields.put("pre_queue_min", "0 to 1440");
        }
        if (rate < 1 || rate > 6000) {
            fields.put("admission_rate_per_min", "1 to 6000");
        }
        if (rule.minAccountAgeDays() != null && (rule.minAccountAgeDays() < 1 || rule.minAccountAgeDays() > 365)) {
            fields.put("eligibility.min_account_age_days", "1 to 365");
        }
        if (rule.memberJoinedBefore() != null && saleStartsAt != null
                && rule.memberJoinedBefore().isAfter(saleStartsAt)) {
            fields.put("eligibility.member_joined_before", "at or before sale_starts_at");
        }
        if (rule.minEventsAttended() != null && (rule.minEventsAttended() < 1 || rule.minEventsAttended() > 50)) {
            fields.put("eligibility.min_events_attended", "1 to 50");
        }
        if (!fields.isEmpty()) {
            throw new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                    Map.of("fields", fields));
        }
        return new Config(preQueue, rate, rule);
    }

    /** {pre_queue_min, admission_rate_per_min} and the rule; no fan data. */
    private static String payload(Config c) {
        Rule r = c.rule();
        return ("{\"pre_queue_min\": %d, \"admission_rate_per_min\": %d, \"min_account_age_days\": %s, "
                + "\"member_joined_before\": %s, \"min_events_attended\": %s}").formatted(c.preQueueMin(),
                        c.admissionRatePerMin(), r.minAccountAgeDays(),
                        r.memberJoinedBefore() == null ? null : "\"" + r.memberJoinedBefore() + "\"",
                        r.minEventsAttended());
    }

    /** One transaction, then this instance's memo is invalidated (after the commit, so a reload sees the write). */
    private <T> T write(Supplier<T> work) {
        T result = tx.execute(s -> work.get());
        memo.invalidate();
        return result;
    }

    private static ApiException roomStatus(Status status) {
        return new ApiException("ROOM_STATUS", HttpStatus.CONFLICT, "Room is " + status,
                Map.of("status", status.name()));
    }

    private static ApiException saleStartRequired() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "SALE_START_REQUIRED");
        details.put("fields", Map.of("sale_starts_at", "must be set and in the future to create a room"));
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body", details);
    }
}
