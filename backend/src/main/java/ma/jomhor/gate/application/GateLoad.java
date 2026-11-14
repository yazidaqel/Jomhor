package ma.jomhor.gate.application;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.gate.application.GateReports.GateLevel;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tickets.domain.Ticket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * GET /fan/gates/{event_id}/load (docs/04-api.md §Fan): the gate levels of an event the caller holds a ticket of,
 * and the caller's own entries. Read-only: never takes the scan advisory lock, never writes.
 */
@Service
public class GateLoad {

    /** 04: the gate part is computed once per event per 15 s. */
    static final Duration GATES_TTL = Duration.ofSeconds(15);

    public record Load(int eventId, Instant serverTime, List<GateLevel> gates, List<Scans.Entry> entries) {}

    /** One computation of an event's gate part: started at {@code computedAt}, joined by every caller until stale. */
    private record Memo(Instant computedAt, CompletableFuture<List<GateLevel>> gates) {

        boolean fresh(Instant now) {
            return now.isBefore(computedAt.plus(GATES_TTL));
        }
    }

    private final TicketService tickets;
    private final GateReports reports;
    private final Scans scans;
    private final InstantSource clock;
    // ponytail: per instance on purpose (plan lot-5 P106): 04 lets the gate part be 15 s old, so N instances just mean
    // N computations per event per 15 s. A shared cache only if those reads ever show on Postgres.
    private final Map<Integer, Memo> memo = new ConcurrentHashMap<>();

    @Autowired
    GateLoad(TicketService tickets, GateReports reports, Scans scans) {
        this(tickets, reports, scans, InstantSource.system());
    }

    GateLoad(TicketService tickets, GateReports reports, Scans scans, InstantSource clock) {
        this.tickets = tickets;
        this.reports = reports;
        this.scans = scans;
        this.clock = clock;
    }

    /**
     * 404 unless {@code holderUserId} holds a ticket of the event (any status, a ticket transferred away included).
     * The organizer comes from the ticket row; entries are read fresh on every call.
     */
    public Load load(UUID holderUserId, int eventId) {
        List<Ticket> held = tickets.heldOfEvent(holderUserId, eventId);
        if (held.isEmpty()) {
            throw ApiException.notFound("Event not found");
        }
        UUID org = held.getFirst().organizerId(); // events.id is global: one organizer per event
        List<Scans.Entry> entries = scans.entries(org, eventId, held.stream().map(Ticket::id).toList());
        return new Load(eventId, clock.instant().truncatedTo(ChronoUnit.MILLIS), gates(org, eventId), entries);
    }

    /**
     * The event's gate levels, at most 15 s old, computed once per event per 15 s on this instance: the first caller
     * past the TTL swaps in its own future (under the map's per-key lock, without computing there) and computes;
     * concurrent callers join that future, so one snapshot and one pooled connection, never one per request. A failed
     * computation is dropped from the map (the next caller retries) and rethrown to its joiners. Stale entries of
     * other events are dropped on each new computation, so the map holds live events only.
     */
    List<GateLevel> gates(UUID org, int eventId) {
        Instant now = clock.instant();
        CompletableFuture<List<GateLevel>> mine = new CompletableFuture<>();
        Memo m = memo.compute(eventId, (id, old) -> old != null && old.fresh(now) ? old : new Memo(now, mine));
        if (m.gates() == mine) {
            try {
                mine.complete(reports.fanGateLevels(org, eventId));
            } catch (RuntimeException | Error e) {
                memo.remove(eventId, m);
                mine.completeExceptionally(e);
                throw e;
            }
            memo.values().removeIf(other -> !other.fresh(now));
        }
        try {
            return m.gates().join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : e;
        }
    }

    /** Events in the memo (tests: stale entries are dropped). */
    int memoSize() {
        return memo.size();
    }
}
