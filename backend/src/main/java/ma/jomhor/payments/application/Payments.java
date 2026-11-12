package ma.jomhor.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.payments.domain.Payment;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.sales.domain.Order.PaymentMethod;

/** Persistence port for payments. */
public interface Payments {

    /** A PENDING CARD or WALLET attempt; /return lands on {@code returnChannel} (APP or WEB, null = the order's). */
    Payment insertPending(UUID org, UUID orderId, PaymentMethod method, String provider, long amountMad,
                          Order.Channel returnChannel);

    /** A PENDING CASH_AGENCY attempt (provider CASH) with this code; empty if the code is taken (draw again). */
    Optional<Payment> insertCashPending(UUID org, UUID orderId, String payCode, long amountMad);

    /** The order's PENDING CASH_AGENCY attempt, if any (at most one: issued under the order lock). */
    Optional<Payment> pendingCode(UUID org, UUID orderId);

    // ---------- club till (04 §Point of sale) ----------

    /** A SUCCEEDED POS_CASH payment (provider POS) of a till sale, tied to the till session. */
    void insertPos(UUID org, UUID orderId, long amountMad, UUID sessionId);

    /** The organizer's CASH_AGENCY payment with this code (6 normalised symbols). */
    Optional<Payment> findCode(UUID org, String payCode);

    /** Like findCode, and locks the row until the end of the transaction. */
    Optional<Payment> lockCode(UUID org, String payCode);

    /** By code alone (codes are unique across organizers): a cash network's callback and terminal carry no organizer. */
    Optional<Payment> findCode(String payCode);

    /** Like findCode(payCode), and locks the row until the end of the transaction. */
    Optional<Payment> lockCode(String payCode);

    /** The payment that already carries this provider reference, if any (unique per provider). */
    Optional<UUID> withRef(String provider, String providerRef);

    /** A PENDING code collected at a till: SUCCEEDED, provider POS, the session, completed_at. */
    void collect(UUID org, UUID paymentId, UUID sessionId);

    /** The till session of the order's SUCCEEDED payment taken at a till of the organizer, if any. */
    Optional<UUID> tillSession(UUID org, UUID orderId);

    /** By id alone: a gateway callback or a browser page carries no organizer. */
    Optional<Payment> find(UUID paymentId);

    /** Like find, and locks the row until the end of the transaction. */
    Optional<Payment> lock(UUID paymentId);

    /** Sets redirected_at the first time the hosted page of a PENDING attempt is opened (plan P38). */
    void markRedirected(UUID org, UUID paymentId);

    /**
     * PENDING → SUCCEEDED or FAILED, with the provider that answered (a cash code: the network that collected it), its
     * reference and the verified callback (no card data).
     */
    void complete(UUID org, UUID paymentId, Payment.Status status, String provider, String providerRef,
                  String callbackJson);

    // ---------- cash report (04 GET /org/finance/cash) ----------

    /**
     * SUCCEEDED POS_CASH and CASH_AGENCY payments with completed_at in [from, to), one row each. The caller assigns
     * the local day with its own ZoneId (one tzdata for bounds and days). Money in centimes.
     */
    List<CashPayment> cashPayments(UUID org, Instant from, Instant to);

    record CashPayment(Instant completedAt, String collector, PaymentMethod method, long amountMad) {}
}
