package ma.jomhor.payments.application;

import java.util.Optional;
import java.util.UUID;
import ma.jomhor.payments.domain.Payment;

/** Persistence port for payments. */
public interface Payments {

    Payment insertPending(UUID org, UUID orderId, String provider, long amountMad);

    /** By id alone: a gateway callback or a browser page carries no organizer. */
    Optional<Payment> find(UUID paymentId);

    /** Like find, and locks the row until the end of the transaction. */
    Optional<Payment> lock(UUID paymentId);

    /** PENDING → SUCCEEDED or FAILED, with the gateway's reference and the verified callback (no card data). */
    void complete(UUID org, UUID paymentId, Payment.Status status, String providerRef, String callbackJson);
}
