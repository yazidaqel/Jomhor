package ma.jomhor.sales.products.application;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.tickets.application.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pass job (docs/04-api.md §Passes Tickets and Suspension, plan lot-5 Q188, Q197): the suspension of passes with
 * an installment overdue past its grace, then one ticket per ACTIVE pass for each published event of its series at its
 * zone's venue, until the event's transfer_until.
 *
 * <p>Per event, batches of {@link #BATCH} passes, each in its own transaction: the event's class-8 try-lock (another
 * instance holding it issues the event this run), then {@code for share} on the event row (so an event PATCH changing
 * series_id waits, and its PASS_TICKETS check sees what was issued), then per pass one PASS place (plain take), a
 * PAID FREE order and one VALID ticket. Exactly once per pass and event: the lock, the "no ticket order yet" read and
 * the unique (pass_id, event_id) index. No delivery is queued; no limit is checked.
 */
@Service
public class PassTicketService {

    private static final Logger log = LoggerFactory.getLogger(PassTicketService.class);

    /** Passes per transaction (04: at most 500). */
    static final int BATCH = 500;

    private final Passes passes;
    private final Orders orders;
    private final EventService events;
    private final Inventory inventory;
    private final TicketService tickets;
    private final TransactionTemplate tx;
    private final PassService passService;

    PassTicketService(Passes passes, Orders orders, EventService events, Inventory inventory, TicketService tickets,
                      PlatformTransactionManager transactions, PassService passService) {
        this.passes = passes;
        this.passService = passService;
        this.orders = orders;
        this.events = events;
        this.inventory = inventory;
        this.tickets = tickets;
        this.tx = new TransactionTemplate(transactions);
    }

    /** The job, on the database clock, read again in each batch's transaction (its now()). */
    public void run() {
        runEach(events::now);
    }

    /** One run at a fixed {@code now} (tests). */
    public void runAt(Instant now) {
        runEach(() -> now);
    }

    /**
     * First the suspension (04 §Passes Suspension), so a pass suspended now gets no ticket in this run; then every
     * candidate event, batch after batch while a full batch issued something. A step that fails is logged (exception
     * class and SQL state only: a constraint message can quote holder_name) and the others still run.
     */
    private void runEach(Supplier<Instant> clock) {
        try {
            passService.suspend(clock.get());
        } catch (RuntimeException ex) {
            log.error("Pass suspension failed: {} (SQL state {})", ex.getClass().getName(), sqlState(ex));
        }
        for (Event.Ref e : events.withSeriesOnSale(clock.get())) {
            try {
                while (Boolean.TRUE.equals(tx.execute(s -> batch(e, clock.get())))) {
                    // a full batch went out: the next one
                }
            } catch (RuntimeException ex) {
                log.error("Pass tickets failed for event {}: {} (SQL state {})", e.id(), ex.getClass().getName(),
                        sqlState(ex));
            }
        }
    }

    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /**
     * One batch; true when it read {@link #BATCH} passes and issued at least one (more may be due; a zone that filled
     * up is left out of the next one, so the loop ends).
     */
    private boolean batch(Event.Ref ref, Instant now) {
        UUID org = ref.organizerId();
        int eventId = ref.id();
        if (!passes.tryLockEvent(eventId)) {
            return false;
        }
        Event e = events.share(org, eventId).orElse(null);
        if (e == null || e.seriesId() == null
                || (e.status() != Event.Status.ON_SALE && e.status() != Event.Status.GATES_OPEN)) {
            return false;
        }
        Instant until = tickets.transferUntil(e.refundCutoffAt());
        if (until == null || !now.isBefore(until)) {
            return false;
        }
        // The zones with PASS room: each is at the event's venue, so a pass of another venue's zone never matches.
        Set<Integer> zones = inventory.prices(org, eventId).stream()
                .filter(p -> p.audience() == Event.Audience.PASS && p.remaining() > 0)
                .map(Inventory.SalePrice::zoneId).collect(Collectors.toSet());
        if (zones.isEmpty()) {
            return false;
        }
        List<Passes.Due> due = passes.due(org, e.seriesId(), eventId, zones, BATCH);
        int issued = 0;
        for (Passes.Due d : due) {
            Optional<Inventory.SalePrice> place = inventory.takePass(org, eventId, d.zoneId());
            if (place.isEmpty()) {
                continue; // the zone filled up in this batch: the next batch leaves it out
            }
            UUID category = place.get().id();
            Order o = orders.insertPassTicket(org, eventId, d.userId(), d.passId(), new Order.Item(category, null, 1, 0,
                    List.of(new Order.Holder(d.holderName(), d.userId()))));
            tickets.issue(org, o.id(), eventId,
                    List.of(new TicketService.Seat(d.zoneId(), category, d.userId(), d.holderName())));
            issued++;
        }
        return due.size() == BATCH && issued > 0;
    }
}
