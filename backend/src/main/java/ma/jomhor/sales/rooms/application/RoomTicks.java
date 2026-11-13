package ma.jomhor.sales.rooms.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.common.AuditLog;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.rooms.application.WaitingRooms.Tick;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The room tick (docs/04-api.md §Waiting room Tick, Lifecycle): one transaction per live room, its row taken
 * {@code for no key update skip locked}, so with N instances one of them ticks a room at a time. The event row is never
 * locked here: an event PATCH holds it, then waits on the room row (for share), so the tick must never wait on it.
 * Stock and active holds are read without locks.
 */
@Service
public class RoomTicks {

    private static final Logger log = LoggerFactory.getLogger(RoomTicks.class);

    /** Later joins ranked per tick (04 §Waiting room Tick). */
    static final int RANK_BATCH = 5_000;

    private static final long MICROS_PER_MIN = 60_000_000L;

    private final WaitingRooms rooms;
    private final QueueEntries entries;
    private final Orders orders;
    private final EventService events;
    private final AuditLog audit;
    private final long maxActiveHolds;
    private final Duration retention;
    private final TransactionTemplate tx;
    private volatile Instant lastPurge;

    RoomTicks(WaitingRooms rooms, QueueEntries entries, Orders orders, EventService events, AuditLog audit,
              @Value("${sales.max-active-holds-per-event}") long maxActiveHolds,
              @Value("${rooms.retention}") Duration retention, PlatformTransactionManager transactions) {
        this.rooms = rooms;
        this.entries = entries;
        this.orders = orders;
        this.events = events;
        this.audit = audit;
        this.maxActiveHolds = maxActiveHolds;
        this.retention = retention;
        this.tx = new TransactionTemplate(transactions);
    }

    /** The job: every live room on the database clock (one clock for every instance), and the purge once a day. */
    public void run() {
        Instant now = orders.now();
        tick(now);
        Instant last = lastPurge;
        if (last == null || !now.isBefore(last.plus(Duration.ofDays(1)))) {
            lastPurge = now;
            purge(now);
        }
    }

    /** One tick of every live room at {@code now}; a room that fails is logged and the others still tick. */
    public void tick(Instant now) {
        for (UUID id : rooms.liveIds()) {
            try {
                tx.executeWithoutResult(s -> rooms.lockForTick(id).ifPresent(r -> tick(r, now)));
            } catch (RuntimeException e) {
                log.error("Room tick failed for room {}", id, e);
            }
        }
    }

    /** Deletes queue entries {@code rooms.retention} (30 days) after their event's starts_at. */
    public int purge(Instant now) {
        return entries.purge(retention, now);
    }

    private void tick(Tick r, Instant now) {
        UUID org = r.organizerId();
        Status status = r.status();
        if (r.refundCutoffAt() != null && !now.isBefore(r.refundCutoffAt())) {
            rooms.saveTick(with(r, Status.ENDED, r.joinedCount(), r.rankedCount(), r.admittedThrough(),
                    r.measuredRatePerMin(), r.rateMarkAt(), r.rateMarkThrough(), r.soldOut(), now, r.drawnAt()));
            return;
        }
        int ranked = r.rankedCount();
        Instant last = r.lastTickAt();
        Instant drawnAt = r.drawnAt();
        if (status == Status.SCHEDULED && !now.isBefore(r.saleStartsAt().minus(Duration.ofMinutes(r.preQueueMin())))) {
            status = r.preQueueMin() == 0 ? Status.OPEN : Status.PRE_QUEUE; // 0 = no draw: first come, first served
            last = now;
        }
        if (status == Status.PRE_QUEUE && !now.isBefore(r.saleStartsAt())) {
            int drawn = entries.draw(org, r.id(), ranked);
            ranked += drawn;
            drawnAt = now;
            status = Status.OPEN;
            last = now;
            audit.system(org, "room.draw", String.valueOf(r.eventId()), "{\"count\": %d}".formatted(drawn));
        }
        boolean ranking = status == Status.OPEN || status == Status.PAUSED;
        if (ranking) {
            ranked += entries.rankArrivals(org, r.id(), ranked, RANK_BATCH);
        }
        boolean soldOut = events.prices(org, r.eventId()).stream().allMatch(p -> p.sold() >= p.quota());
        int admitted = r.admittedThrough();
        if (status == Status.OPEN && last != null && !soldOut
                && orders.activeHolds(org, r.eventId()) * 10 < maxActiveHolds * 9) {
            // floor(rate × elapsed ÷ 60 s), elapsed counted from the time already turned into admissions: the
            // remainder carries to the next tick, so a 1 s tick still admits 500/min (or 1/min), not floor(8.3) × 60.
            long elapsed = Math.max(0, ChronoUnit.MICROS.between(last, now));
            long n = r.admissionRatePerMin() * elapsed / MICROS_PER_MIN;
            if (admitted + n >= ranked) {
                admitted = ranked; // everyone in is admitted: no time is banked for later joins
                last = now;
            } else {
                admitted += (int) n;
                last = last.plus(n * MICROS_PER_MIN / r.admissionRatePerMin(), ChronoUnit.MICROS);
            }
        } else {
            last = now;
        }
        int measured = r.measuredRatePerMin();
        Instant markAt = r.rateMarkAt();
        int markThrough = r.rateMarkThrough();
        if (ranking) { // admitted over the last full minute, re-marked every 60 s
            long ms = markAt == null ? 0 : Duration.between(markAt, now).toMillis();
            if (markAt == null || ms >= 60_000) {
                measured = markAt == null ? measured : (int) ((admitted - markThrough) * 60_000L / ms);
                markAt = now;
                markThrough = admitted;
            }
        }
        rooms.saveTick(with(r, status, (int) entries.count(org, r.id()), ranked, admitted, measured, markAt,
                markThrough, soldOut, last, drawnAt));
    }

    private static Tick with(Tick r, Status status, int joined, int ranked, int admitted, int measured,
                             Instant markAt, int markThrough, boolean soldOut, Instant lastTickAt, Instant drawnAt) {
        return new Tick(r.id(), r.organizerId(), r.eventId(), status, r.preQueueMin(), r.admissionRatePerMin(),
                r.saleStartsAt(), r.refundCutoffAt(), joined, ranked, admitted, measured, markAt, markThrough, soldOut,
                lastTickAt, drawnAt);
    }
}
