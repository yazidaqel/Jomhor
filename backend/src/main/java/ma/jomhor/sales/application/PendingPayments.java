package ma.jomhor.sales.application;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.sales.domain.Order.PaymentMethod;

/** What sales needs from payments (plan Q8, P38, 04 GET /fan/orders). Implemented by the payments module: sales never reads its tables. */
public interface PendingPayments {

    /** A payment attempt's status as the order API reports it (04 `payment_status`). */
    enum AttemptStatus { PENDING, SUCCEEDED, FAILED }

    /** The attempt an order reports: its method (04 `payment_method`) and status (04 `payment_status`). */
    record Attempt(PaymentMethod method, AttemptStatus status) {}

    /**
     * Orders with a PENDING CARD/WALLET payment whose hosted page was opened less than {@code age} ago (redirected_at,
     * database clock). An attempt never redirected, or a cash code, doesn't hold its order (plan P38).
     */
    Set<UUID> ordersRedirectedWithin(Duration age);

    /**
     * The SUCCEEDED attempt if any (it wins over a later PENDING or FAILED one); otherwise the latest attempt (by
     * created_at); empty before any /pay. A PENDING CARD/WALLET attempt redirected (else created) {@code
     * pendingFailsAfter} ago or more is reported FAILED (database clock, the same clock as the expiry grace); the row stays PENDING, so a late callback for it is handled as any other
     * (plan Q8). A PENDING cash code stays PENDING.
     */
    Optional<Attempt> latestAttempt(UUID org, UUID orderId, Duration pendingFailsAfter);

    /** The method of {@link #latestAttempt} for each order (order id → its organizer); orders with no attempt are absent. */
    Map<UUID, PaymentMethod> latestMethods(Map<UUID, UUID> organizerByOrder);

    /** The PENDING cash code (6 symbols) of each order that has one (order id → its organizer); others are absent. */
    Map<UUID, String> pendingCodes(Map<UUID, UUID> organizerByOrder);

    /** Σ refunds.amount_mad of the event's refunded tickets, any refund status (04 /sales {@code refunded_mad}). */
    long refundedMad(UUID org, int eventId);
}
