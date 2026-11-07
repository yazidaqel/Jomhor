package ma.jomhor.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Money owed back for a refunded ticket (docs/04-api.md GET /org/refunds): its unit price snapshot in centimes, the
 * order's succeeding payment method (null if none), paid back by hand and then marked DONE with a reference.
 * {@code requestedBy} and {@code doneBy} are staff ids; {@code doneBy} null until DONE.
 */
public record Refund(UUID id, int eventId, UUID ticketId, UUID orderId, long amountMad, String method, String reason,
                     Status status, UUID requestedBy, String requestedByName, Instant requestedAt, UUID doneBy,
                     String doneByName, Instant doneAt, String reference) {

    public enum Status { REQUIRED, DONE }
}
