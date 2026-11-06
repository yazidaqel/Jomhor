package ma.jomhor.sales.application;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** What sales needs from payments (plan Q8, 04 GET /fan/orders/{id}). Implemented by the payments module: sales never reads its tables. */
public interface PendingPayments {

    /** A payment attempt's status as the order API reports it (04 `payment_status`). */
    enum AttemptStatus { PENDING, SUCCEEDED, FAILED }

    /** Orders with a PENDING payment created less than {@code age} ago (database clock). */
    Set<UUID> ordersWithPendingYoungerThan(Duration age);

    /**
     * SUCCEEDED if any attempt of the order succeeded (it wins over a later PENDING or FAILED one); otherwise the
     * status of the latest attempt (by created_at); empty before any /pay. A PENDING attempt created
     * {@code pendingFailsAfter} ago or more is reported FAILED (database clock); the row stays PENDING, so a late
     * callback for it is handled as any other (plan Q8).
     */
    Optional<AttemptStatus> latestAttempt(UUID org, UUID orderId, Duration pendingFailsAfter);
}
