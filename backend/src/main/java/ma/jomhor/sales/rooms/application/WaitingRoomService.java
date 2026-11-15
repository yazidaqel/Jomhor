package ma.jomhor.sales.rooms.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * /org/events/{id}/rooms* (docs/04-api.md §Organizer, §Waiting room Phases): the organizer's side of the rooms, one
 * per sale phase. {@code org} is always OrgContext.organizerId(); another organizer's event is 404. Writes lock the
 * event row, then the room row (as the event PATCH does), so the phase order is checked against rows nobody else
 * writes meanwhile, and invalidate this instance's memo after the commit.
 */
@Service
public class WaitingRoomService {

    /** The room's live figures (04 GET /org/events/{id}/rooms {@code figures}). */
    public record Figures(long joined, long waiting, long admitted, long admittedLastMin, long activeHolds,
                          long maxActiveHolds, long paid, long quota, long remaining, boolean soldOut,
                          Long estimatedDrainS) {}

    public record View(WaitingRoom room, Figures figures) {}

    /**
     * PUT input; null = the default (30, 500, no rule). {@code label} untrimmed, null = none; {@code startsAt} the
     * phase's sale start, phase 2 and up only.
     */
    public record Input(String label, Instant startsAt, Integer preQueueMin, Integer admissionRatePerMin, Rule rule) {}

    /** A PUT's result: {@code created} → 201. */
    public record Put(boolean created, View view) {}

    /** A phase starts at least this long before the event (04 PUT …/rooms/{phase} {@code starts_at}). */
    private static final Duration PHASE_LEAD = Duration.ofMinutes(60);

    private final WaitingRooms rooms;
    private final QueueEntries entries;
    private final RoomMemo memo;
    private final EventService events;
    private final Orders orders;
    private final AuditLog audit;
    private final JsonMapper json;
    private final long maxActiveHolds;
    private final TransactionTemplate tx;
    private final TransactionTemplate snapshot;

    WaitingRoomService(WaitingRooms rooms, QueueEntries entries, RoomMemo memo, EventService events, Orders orders,
                       AuditLog audit, JsonMapper json,
                       @Value("${sales.max-active-holds-per-event}") long maxActiveHolds,
                       PlatformTransactionManager transactions) {
        this.rooms = rooms;
        this.entries = entries;
        this.memo = memo;
        this.events = events;
        this.orders = orders;
        this.audit = audit;
        this.json = json;
        this.maxActiveHolds = maxActiveHolds;
        this.tx = new TransactionTemplate(transactions);
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** GET: the event's rooms by phase (empty when none). One snapshot for the rooms, holds, sales and stock. */
    public List<View> list(UUID org, int eventId) {
        return snapshot.execute(s -> {
            events.get(org, eventId);
            return views(org, eventId, rooms.list(org, eventId));
        });
    }

    /**
     * Create or replace one phase. The event must be DRAFT or ON_SALE. Phase 1 starts with the event's sale_starts_at
     * (set and in the future at creation); phase n ≥ 2 needs phase n − 1 and its own starts_at, in the future (when
     * set or changed) and before the event's starts_at − 60 min. Then the phase order (04 §Waiting room Phases). Once
     * the phase has left SCHEDULED only the rate may change; ENDED refuses everything.
     */
    public Put put(UUID org, UUID userId, int eventId, int phase, Input in) {
        checkPhase(phase);
        return write(() -> {
            Event e = events.lock(org, eventId);
            if (e.status() != Event.Status.DRAFT && e.status() != Event.Status.ON_SALE) {
                throw new ApiException("EVENT_NOT_EDITABLE", HttpStatus.CONFLICT,
                        "Event can't be changed in its current status");
            }
            Optional<Status> locked = rooms.lock(org, eventId, phase); // event row, then room row, as the PATCH
            List<WaitingRoom> all = rooms.list(org, eventId);
            Optional<WaitingRoom> current = locked.flatMap(s -> byPhase(all, phase));
            Instant now = orders.now();
            Instant startsAt = in.startsAt() == null ? null : in.startsAt().truncatedTo(ChronoUnit.MICROS);
            String label = in.label() == null ? null : in.label().trim();
            boolean startChanged = current.isEmpty() || !Objects.equals(current.get().startsAt(), startsAt);
            Config c = validate(in, phase, label, startsAt, phase == 1 ? e.saleStartsAt() : startsAt,
                    startChanged ? now : null, e.startsAt());
            if (current.isEmpty()) {
                if (phase >= 2 && byPhase(all, phase - 1).isEmpty()) {
                    throw phaseOrder("phase", "phase " + (phase - 1) + " must exist first");
                }
                if (phase == 1 && (e.saleStartsAt() == null || !e.saleStartsAt().isAfter(now))) {
                    throw saleStartRequired();
                }
                checkOrder(all, phase, phase == 1 ? e.saleStartsAt() : startsAt, c.preQueueMin());
                rooms.insert(org, eventId, phase, label, startsAt, c);
                audit.staff(org, userId, "room.create", String.valueOf(eventId), payload(phase, label, startsAt, c));
                return new Put(true, view(org, eventId, phase));
            }
            WaitingRoom r = current.get();
            boolean onlyRate = r.config().preQueueMin() == c.preQueueMin() && r.config().rule().equals(c.rule())
                    && Objects.equals(r.label(), label) && !startChanged;
            Status status = r.statusAt(now); // a late tick doesn't reopen the rules once joins are open
            if (status == Status.ENDED || (status != Status.SCHEDULED && !onlyRate)) {
                throw roomStatus(status);
            }
            if (status == Status.SCHEDULED) {
                checkOrder(all, phase, phase == 1 ? r.saleStartsAt() : startsAt, c.preQueueMin());
            }
            rooms.update(org, eventId, phase, label, startsAt, c);
            audit.staff(org, userId, "room.update", String.valueOf(eventId), payload(phase, label, startsAt, c));
            return new Put(false, view(org, eventId, phase));
        });
    }

    /** OPEN → PAUSED. */
    public View pause(UUID org, UUID userId, int eventId, int phase) {
        return transition(org, userId, eventId, phase, "room.pause", Status.PAUSED, Status.OPEN);
    }

    /** PAUSED → OPEN. */
    public View resume(UUID org, UUID userId, int eventId, int phase) {
        return transition(org, userId, eventId, phase, "room.resume", Status.OPEN, Status.PAUSED);
    }

    /**
     * Any but ENDED → ENDED, irreversible. With a later phase not ENDED, orders then wait for it (04 Q169); with none,
     * they need no admission token afterwards.
     */
    public View end(UUID org, UUID userId, int eventId, int phase) {
        return transition(org, userId, eventId, phase, "room.end", Status.ENDED,
                Status.SCHEDULED, Status.PRE_QUEUE, Status.OPEN, Status.PAUSED);
    }

    private View transition(UUID org, UUID userId, int eventId, int phase, String action, Status to,
                            Status... from) {
        checkPhase(phase);
        return write(() -> {
            events.get(org, eventId);
            Status status = rooms.lock(org, eventId, phase)
                    .orElseThrow(() -> ApiException.notFound("Room not found"));
            if (!List.of(from).contains(status)) {
                throw roomStatus(status);
            }
            rooms.setStatus(org, eventId, phase, to);
            audit.staff(org, userId, action, String.valueOf(eventId), "{\"phase\": %d}".formatted(phase));
            return view(org, eventId, phase);
        });
    }

    // ---------- reads ----------

    private View view(UUID org, int eventId, int phase) {
        return views(org, eventId, List.of(rooms.find(org, eventId, phase).orElseThrow())).getFirst();
    }

    /**
     * Per room: {@code waiting} = its ranked fans not yet admitted (counts of the last tick) plus its entries not ranked
     * yet. Event-wide, read once: {@code paid} as GET /sales (places in PAID orders), {@code quota} and
     * {@code remaining} over the price categories, the active holds.
     */
    private List<View> views(UUID org, int eventId, List<WaitingRoom> phases) {
        if (phases.isEmpty()) {
            return List.of();
        }
        long paid = orders.salesLines(org, eventId).stream().mapToLong(SalesLine::paid).sum();
        long quota = 0;
        long sold = 0;
        for (Event.PriceCategory p : events.prices(org, eventId)) {
            quota += p.quota();
            sold += p.sold();
        }
        long remaining = Math.max(0, quota - sold);
        long holds = orders.activeHolds(org, eventId);
        List<View> views = new ArrayList<>();
        for (WaitingRoom r : phases) {
            long waiting = Math.max(0, r.rankedCount() - r.admittedThrough()) + entries.unranked(org, r.id());
            int rate = r.measuredRatePerMin();
            Long drain = rate == 0 ? null : (waiting * 60 + rate - 1) / rate;
            views.add(new View(r, new Figures(r.joinedCount(), waiting, r.admittedThrough(), rate, holds,
                    maxActiveHolds, paid, quota, remaining, remaining == 0, drain)));
        }
        return views;
    }

    // ---------- rules ----------

    /** 04: {@code phase} outside 1-5 is no such resource. */
    private static void checkPhase(int phase) {
        if (phase < 1 || phase > WaitingRoom.MAX_PHASES) {
            throw ApiException.notFound("Room not found");
        }
    }

    private static Optional<WaitingRoom> byPhase(List<WaitingRoom> all, int phase) {
        return all.stream().filter(r -> r.phase() == phase).findFirst();
    }

    /**
     * 04 §Waiting room Phases, for phase {@code phase} (sale start {@code saleStart}, pre-queue {@code preQueueMin})
     * against its nearest phases not ENDED: it opens after the sale start of the highest earlier one, and the lowest
     * later one opens after its sale start. Refused as 422 PHASE_ORDER on {@code starts_at} when the sale start itself
     * is out of order (phase 1: on {@code sale_starts_at}, the event's), else on {@code pre_queue_min}.
     */
    private static void checkOrder(List<WaitingRoom> all, int phase, Instant saleStart, int preQueueMin) {
        Instant opensAt = saleStart.minus(Duration.ofMinutes(preQueueMin));
        Optional<WaitingRoom> prev = all.stream().filter(r -> r.phase() < phase && r.status() != Status.ENDED)
                .max(Comparator.comparingInt(WaitingRoom::phase));
        if (prev.isPresent() && !opensAt.isAfter(prev.get().saleStartsAt())) {
            String message = "the phase must open after phase " + prev.get().phase() + "'s sale start";
            throw phaseOrder(saleStart.isAfter(prev.get().saleStartsAt()) ? "pre_queue_min" : "starts_at", message);
        }
        Optional<WaitingRoom> next = WaitingRoom.next(all, phase);
        if (next.isPresent() && !next.get().opensAt().isAfter(saleStart)) {
            throw phaseOrder(phase == 1 ? "sale_starts_at" : "starts_at",
                    "must be before phase " + next.get().phase() + " opens");
        }
    }

    /**
     * Ranges per docs/03-schema.sql, the label (1-40 once trimmed), starts_at (absent for phase 1; else required and,
     * when {@code now} is set (created or moved), in the future and before the event's starts_at − 60 min),
     * member_joined_before ≤ the phase's sale start. Every field error at once.
     */
    private static Config validate(Input in, int phase, String label, Instant startsAt, Instant saleStart, Instant now,
                                   Instant eventStartsAt) {
        int preQueue = in.preQueueMin() == null ? Config.DEFAULT_PRE_QUEUE_MIN : in.preQueueMin();
        int rate = in.admissionRatePerMin() == null ? Config.DEFAULT_ADMISSION_RATE_PER_MIN : in.admissionRatePerMin();
        Rule rule = in.rule() == null ? Rule.NONE : in.rule();
        Instant joinedBefore = rule.memberJoinedBefore();
        if (joinedBefore != null) { // stored to the microsecond: a re-sent value then compares equal to the stored one
            rule = new Rule(rule.minAccountAgeDays(), joinedBefore.truncatedTo(ChronoUnit.MICROS), rule.minEventsAttended());
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (label != null && (label.isEmpty() || label.codePointCount(0, label.length()) > 40)) { // 03 char_length
            fields.put("label", "1 to 40 characters");
        }
        if (phase == 1 && startsAt != null) {
            fields.put("starts_at", "must be absent for phase 1 (it starts with the event's sale_starts_at)");
        } else if (phase >= 2 && startsAt == null) {
            fields.put("starts_at", "required for phase 2 and up");
        } else if (phase >= 2 && now != null && !startsAt.isAfter(now)) {
            fields.put("starts_at", "must be in the future");
        } else if (phase >= 2 && now != null && !startsAt.isBefore(eventStartsAt.minus(PHASE_LEAD))) {
            fields.put("starts_at", "must be before the event's starts_at - 60 min");
        }
        if (preQueue < 0 || preQueue > 1440) {
            fields.put("pre_queue_min", "0 to 1440");
        }
        if (rate < 1 || rate > 6000) {
            fields.put("admission_rate_per_min", "1 to 6000");
        }
        if (rule.minAccountAgeDays() != null && (rule.minAccountAgeDays() < 1 || rule.minAccountAgeDays() > 365)) {
            fields.put("eligibility.min_account_age_days", "1 to 365");
        }
        if (rule.memberJoinedBefore() != null && saleStart != null && rule.memberJoinedBefore().isAfter(saleStart)) {
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

    /** {phase, label, starts_at, pre_queue_min, admission_rate_per_min} and the rule; no fan data. */
    private String payload(int phase, String label, Instant startsAt, Config c) {
        Rule r = c.rule();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("phase", phase);
        p.put("label", label);
        p.put("starts_at", startsAt == null ? null : startsAt.toString());
        p.put("pre_queue_min", c.preQueueMin());
        p.put("admission_rate_per_min", c.admissionRatePerMin());
        p.put("min_account_age_days", r.minAccountAgeDays());
        p.put("member_joined_before", r.memberJoinedBefore() == null ? null : r.memberJoinedBefore().toString());
        p.put("min_events_attended", r.minEventsAttended());
        return json.writeValueAsString(p);
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

    private static ApiException phaseOrder(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("reason", "PHASE_ORDER", "fields", Map.of(field, message)));
    }

    private static ApiException saleStartRequired() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "SALE_START_REQUIRED");
        details.put("fields", Map.of("sale_starts_at", "must be set and in the future to create a room"));
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body", details);
    }
}
