package ma.jomhor.payments.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.payments.domain.Refund;

/** Persistence port for refunds. Scoped by organizer_id. */
public interface Refunds {

    /**
     * A REQUIRED refund requested by the staff row of {@code userId}, tied to the order's succeeding payment (null if
     * none). Returns its id.
     */
    UUID insert(UUID org, UUID userId, int eventId, UUID ticketId, UUID orderId, long amountMad, String reason);

    /** REQUIRED first, then newest; each filter optional (null). */
    List<Refund> list(UUID org, Refund.Status status, Integer eventId);

    Optional<Refund> get(UUID org, UUID refundId);

    /** The refund's status, its row locked until the end of the transaction. */
    Optional<Refund.Status> lockStatus(UUID org, UUID refundId);

    /** REQUIRED → DONE by the staff row of {@code userId}, now, with {@code reference}. */
    void done(UUID org, UUID userId, UUID refundId, String reference);
}
