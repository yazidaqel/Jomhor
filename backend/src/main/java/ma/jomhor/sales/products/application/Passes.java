package ma.jomhor.sales.products.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.products.domain.Installment;
import ma.jomhor.sales.products.domain.Pass;

/** Persistence port for passes. Every organizer method is scoped by organizer_id. */
public interface Passes {

    /**
     * Serialises what can make a pass of the series live for the account (a purchase, a late success's re-take) until
     * the end of the transaction.
     */
    void lockAccount(UUID userId, UUID seriesId);

    /** The account's pass of the series that isn't CANCELLED (at most one, the unique index). */
    Optional<Pass> live(UUID org, UUID seriesId, UUID userId);

    Optional<Pass> find(UUID org, UUID passId);

    /** A PENDING pass of the PASS product's series, held by {@code userId}. */
    Pass insert(UUID org, UUID productId, UUID seriesId, UUID userId, String holderName);

    /** The pass row, locked ({@code for no key update}) until the end of the transaction. */
    Optional<Pass> lock(UUID org, UUID passId);

    /** Like {@link #lock}, for its holder only (POST /fan/passes/{id}/installments); another account's is absent. */
    Optional<Pass> lockHeld(UUID userId, UUID passId);

    /** {@code from} → ACTIVE; false if the pass wasn't {@code from} (nothing written). */
    boolean activate(UUID org, UUID passId, Pass.Status from);

    /**
     * The PENDING passes bought by these just-expired product orders → CANCELLED, and their products' sold − 1 each, in
     * the caller's transaction. The products are locked first, in id order, then the passes.
     */
    void cancelExpired(Collection<UUID> orderIds);

    /** A fan's pass with its purchase order while PENDING (else null). */
    record Owned(Pass pass, UUID orderId) {}

    /** The account's passes that aren't CANCELLED, newest first. */
    List<Owned> owned(UUID userId);

    /** Event id → the holder's ticket from the pass's ticket orders (CANCELLED ones left out). */
    Map<Integer, UUID> tickets(UUID org, UUID passId, UUID holderId);

    // ---------- installments (04 §Passes Installments) ----------

    /**
     * The pass's schedule: seq k (from 1) of {@code amounts.get(k − 1)}, due at the pass's created_at + (k − 1) ×
     * {@code interval}.
     */
    void schedule(UUID org, UUID passId, List<Long> amounts, Duration interval);

    /** The pass's schedule, by seq. */
    List<Installment> installments(UUID org, UUID passId);

    /** The installment paid by {@code orderId} now; false if it was paid already (nothing written). */
    boolean pay(UUID org, UUID installmentId, UUID orderId);

    /** The pass's purchase order: its first product order. */
    Optional<UUID> purchaseOrder(UUID org, UUID passId);

    /** The pass's product order still held (RESERVED or AWAITING_CASH, reserved_until > now()), newest first. */
    Optional<UUID> liveOrder(UUID org, UUID passId);

    /**
     * SUSPENDED → ACTIVE (suspended_at null) when no unpaid installment is past {@code due_at + grace} (database
     * clock); false if nothing was written.
     */
    boolean reactivate(UUID org, UUID passId, Duration grace);

    /** A pass just SUSPENDED: {@code seq} is its earliest installment overdue past its grace. */
    record Suspended(UUID organizerId, UUID passId, int seq) {}

    /**
     * 04 §Passes Suspension, every organizer: ACTIVE passes with an unpaid installment whose {@code due_at + grace} ≤
     * {@code now} → SUSPENDED, suspended_at = {@code now}. A pass row locked by a payment is skipped (the next run sees
     * it), and the overdue check is read again once the rows are locked, so a payment that just committed wins.
     */
    List<Suspended> suspend(Instant now, Duration grace);

    /** An installment of this pass just reminded. */
    record Reminded(UUID organizerId, UUID passId) {}

    /**
     * 04 §Passes Reminder, every organizer: per ACTIVE or SUSPENDED pass, its earliest unpaid installment with seq > 1,
     * reminded_at null and {@code now} < due_at ≤ {@code now} + {@code before} → reminded_at = {@code now}; a later one
     * waits for a later run (its own reminded_at). A date already past is never reminded (suspension handles it). Rows
     * locked by another transaction are skipped (the next run sees them), so each installment is reminded once across
     * instances.
     */
    List<Reminded> remind(Instant now, Duration before);

    /** The pass's earliest unpaid installment reminded at {@code at}; empty when none is (paid since). */
    Optional<Installment> remindedAt(UUID org, UUID passId, Instant at);

    /** 04 GET /org/products/{id} {@code installments}, over the product's ACTIVE and SUSPENDED passes. */
    record InstallmentFigures(long overdue, long dueMad, long collectedMad) {}

    InstallmentFigures installmentFigures(UUID org, UUID productId);

    // ---------- the pass tickets job (04 §Passes Tickets) ----------

    /**
     * The event's pass-ticket try-lock (common.EventLock class 8), until the end of the transaction: false when
     * another transaction holds it (another instance is issuing the event's tickets).
     */
    boolean tryLockEvent(int eventId);

    /** An ACTIVE pass due a ticket: its holder and its product's zone. {@code holderName} stays out of logs. */
    record Due(UUID passId, UUID userId, String holderName, int zoneId) {
        @Override
        public String toString() {
            return "Due[" + passId + "]";
        }
    }

    /**
     * Up to {@code limit} ACTIVE passes of the series whose product zone is one of {@code zoneIds} and that have no
     * ticket order for the event, oldest first.
     */
    List<Due> due(UUID org, UUID seriesId, int eventId, Collection<Integer> zoneIds, int limit);

    // ---------- the product figures (04 GET /org/products/{id}) ----------

    /** The product's passes by status (CANCELLED left out). */
    Map<Pass.Status, Long> countByStatus(UUID org, UUID productId);

    /** Event id → the ticket orders of the product's passes. */
    Map<Integer, Long> ticketOrdersByEvent(UUID org, UUID productId);
}
