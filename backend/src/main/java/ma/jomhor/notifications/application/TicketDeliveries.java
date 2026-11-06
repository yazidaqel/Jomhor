package ma.jomhor.notifications.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.notifications.domain.Delivery;

/** Persistence port for the ticket_deliveries outbox. Scoped by organizer_id. */
public interface TicketDeliveries {

    /** One PENDING row per ticket, due now. */
    void enqueue(UUID org, List<UUID> ticketIds);

    /**
     * Up to {@code limit} PENDING rows due now, locked for the caller's transaction ({@code for update skip
     * locked}: rows another run holds are skipped, never waited for).
     */
    List<Delivery> claimDue(int limit);

    void markSent(UUID org, UUID id);

    /** One more failed try: PENDING again after {@code retryIn}, or FAILED when it is null. */
    void markFailed(UUID org, UUID id, String error, Duration retryIn);

    /** Serializes re-send requests of one ticket until the caller's transaction ends. */
    void lockTicket(UUID org, UUID ticketId);

    /**
     * Rows of the ticket created in the last {@code window}, not counting the automatic one: it is queued by the
     * transaction that issues the ticket, so its created_at = {@code issuedAt} (both now()); a re-send is a later
     * transaction. A Lot 0 ticket (no automatic row) gets 3 re-sends too.
     */
    int resendsWithin(UUID org, UUID ticketId, Instant issuedAt, Duration window);
}
