package ma.jomhor.payments.application;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.sales.domain.Order.PaymentMethod;
import org.springframework.http.ResponseEntity;

/**
 * A card gateway (plan §4.5): the simulated one now, Payzone in BE-7. It serves the WALLET method too (plan Q47). The adapter owns the wire format; payments
 * owns verify → lock → idempotent → effects.
 */
public interface CardGateway {

    /** payments.provider for the attempts it handles, e.g. FAKE; the callback path uses it in lower case. */
    String provider();

    /** Where /payments/card/{id}/redirect sends the browser. */
    Checkout checkout(PaymentRef payment);

    /** Checks the signature first; nothing else in the verdict counts unless signatureOk. */
    CallbackVerdict verify(HttpCallback request);

    /** The answer the gateway expects to a callback that was verified (and applied, or already applied). */
    ResponseEntity<String> acknowledge(CallbackVerdict verdict);

    /** {@code method} CARD or WALLET: one gateway serves both for now (plan Q47). */
    // ponytail: one provider for card and wallet; split the port when a wallet provider differs from the card one.
    record PaymentRef(UUID paymentId, PaymentMethod method, long amountMad) {}

    /** No postFields: a 302 to {@code url}. Otherwise an auto-submitting form POST of the fields to it. */
    record Checkout(URI url, Map<String, String> postFields) {}

    // ponytail: form/query parameters only; add headers or the raw body when Payzone's format needs them.
    record HttpCallback(Map<String, String> params) {}

    record CallbackVerdict(boolean signatureOk, UUID paymentId, boolean approved, String providerRef, long amountMad) {

        public static CallbackVerdict rejected() {
            return new CallbackVerdict(false, null, false, null, 0);
        }
    }
}
