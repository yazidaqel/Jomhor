package ma.jomhor.sales.waitlist.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.notifications.application.FanMessageQueue;
import ma.jomhor.sales.rooms.application.WaitingRoomService;
import ma.jomhor.sales.waitlist.application.Waitlist.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The sold-out waitlist (docs/04-api.md §Waitlist, plan lot-5 Q176-Q179): GET, PUT and DELETE /fan/waitlist/{event_id},
 * and the notify job. Notify only: no hold, no priority, notified fans buy like anyone. Not audited.
 */
@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    /** Entries notified per event per run at most (04 §Waitlist Notify). */
    static final int MAX_PER_RUN = 1_000;

    private final Waitlist waitlist;
    private final Inventory inventory;
    private final WaitingRoomService rooms;
    private final FanMessageQueue messages;
    private final Duration cooldown;
    private final Duration retention;
    private final TransactionTemplate tx;
    private volatile Instant lastPurge;

    WaitlistService(Waitlist waitlist, Inventory inventory, WaitingRoomService rooms,
                    FanMessageQueue messages, @Value("${waitlist.cooldown}") Duration cooldown,
                    @Value("${waitlist.retention}") Duration retention, PlatformTransactionManager transactions) {
        this.waitlist = waitlist;
        this.inventory = inventory;
        this.rooms = rooms;
        this.messages = messages;
        this.cooldown = cooldown;
        this.retention = retention;
        this.tx = new TransactionTemplate(transactions);
    }

    // ---------- fan ----------

    /** The caller's entry, or empty. Event not visible (DRAFT or unknown) → 404. */
    public Optional<Entry> get(UUID userId, int eventId) {
        return waitlist.find(visible(eventId).organizerId(), eventId, userId);
    }

    /**
     * Checks in 04 order: visible (404), on sale (CLOSED), no waiting-room phase left that isn't ENDED (ROOM), sold out
     * (NOT_SOLD_OUT). Then one entry per account: new or moved back → created, still waiting → unchanged.
     */
    public Waitlist.Joined join(UUID userId, int eventId) {
        Inventory.Availability e = visible(eventId);
        if (!e.onSale()) {
            throw closed("CLOSED");
        }
        if (rooms.anyPhaseLeft(e.organizerId(), eventId)) {
            throw closed("ROOM");
        }
        if (!e.soldOut()) {
            throw closed("NOT_SOLD_OUT");
        }
        return waitlist.join(e.organizerId(), eventId, userId);
    }

    /** Leave: the row is deleted (idempotent). Event not visible → 404. */
    public void leave(UUID userId, int eventId) {
        waitlist.delete(visible(eventId).organizerId(), eventId, userId);
    }

    private Inventory.Availability visible(int eventId) {
        return inventory.availability(eventId).orElseThrow(() -> ApiException.notFound("Event not found"));
    }

    /** The fan's entry still exists (the send's stale rule: a fan who left isn't notified). */
    public boolean listed(UUID org, int eventId, UUID userId) {
        return waitlist.find(org, eventId, userId).isPresent();
    }

    private static ApiException closed(String reason) {
        return new ApiException("WAITLIST_CLOSED", HttpStatus.CONFLICT, "The waitlist is not open for this event",
                Map.of("reason", reason));
    }

    // ---------- the job (04 §Waitlist Notify, Retention) ----------

    /** The job on the database clock (clock_timestamp(), read once the event's lock is held), then the daily purge. */
    public void run() {
        notifyEach(waitlist::clock);
        Instant now = waitlist.clock();
        Instant last = lastPurge;
        if (last == null || !now.isBefore(last.plus(Duration.ofDays(1)))) {
            purge(now);
            lastPurge = now; // only once it succeeded: a failed purge runs again on the next tick
        }
    }

    /** One run over every event with waiting entries at {@code now} (tests: a fixed clock). */
    public void notifyAt(Instant now) {
        notifyEach(() -> now);
    }

    /** Deletes entries {@code waitlist.retention} (30 days) after their event's starts_at; rows deleted. */
    public int purge(Instant now) {
        return waitlist.purge(retention, now);
    }

    /** One transaction per event; an event that fails is logged and the others still run. */
    private void notifyEach(Supplier<Instant> clock) {
        for (Waitlist.EventRef e : waitlist.waiting()) {
            try {
                tx.executeWithoutResult(s -> {
                    if (waitlist.tryLock(e.eventId())) { // held: another instance handles the event this run
                        notifyEvent(e.organizerId(), e.eventId(), clock.get());
                    }
                });
            } catch (RuntimeException ex) {
                log.error("Waitlist run failed for event {}", e.eventId(), ex);
            }
        }
    }

    /**
     * Skipped when the event isn't on sale at {@code now}, a phase isn't ENDED, or a WAITLIST_OPEN was queued within
     * the cooldown (read from the outbox under the lock, so two instances never notify one event twice in a period,
     * and a rejoin or a leave doesn't reset it). R = Σ remaining of the categories fans can buy (no PASS),
     * read without a lock; the R oldest waiting entries (at most 1,000) get notified_at and their messages in this
     * transaction. ponytail: R counts every category, MEMBERS ones too, so a member-only leftover notifies non-members
     * as well (plan Q172); filter by audience if the club complains.
     */
    private void notifyEvent(UUID org, int eventId, Instant now) {
        Inventory.SaleEvent sale = inventory.saleEvent(eventId).filter(s -> s.organizerId().equals(org)).orElse(null);
        if (sale == null || !onSale(sale, now) || rooms.anyPhaseLeft(org, eventId)) {
            return;
        }
        Optional<Instant> last = messages.lastWaitlistOpen(org, eventId);
        if (last.isPresent() && now.isBefore(last.get().plus(cooldown))) {
            return;
        }
        long remaining = inventory.salePrices(org, eventId).stream().mapToLong(Inventory.SalePrice::remaining).sum();
        int n = (int) Math.min(remaining, MAX_PER_RUN);
        if (n > 0) {
            List<UUID> users = waitlist.notifyOldest(org, eventId, n, now);
            messages.waitlistOpen(org, eventId, users, now);
        }
    }

    /** 04 on_sale at {@code now}: ON_SALE, past sale_starts_at (when set), before refund_cutoff_at. */
    private static boolean onSale(Inventory.SaleEvent s, Instant now) {
        return s.window() != Inventory.SaleWindow.NOT_ON_SALE
                && (s.saleStartsAt() == null || !now.isBefore(s.saleStartsAt()))
                && s.refundCutoffAt() != null && now.isBefore(s.refundCutoffAt());
    }
}
