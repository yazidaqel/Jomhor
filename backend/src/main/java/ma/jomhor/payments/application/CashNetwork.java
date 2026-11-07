package ma.jomhor.payments.application;

import ma.jomhor.payments.application.CardGateway.HttpCallback;
import org.springframework.http.ResponseEntity;

/**
 * A third-party cash network (plan Q44, §4.3): its agents take the cash on their own terminal and confirm a pay code
 * to /payments/cash/{name}/callback, server to server. The simulated network now, a real one with BE-27. The adapter
 * owns the wire format and the acknowledgement; payments owns verify → lock → idempotent → effects.
 */
public interface CashNetwork {

    /** The {network} path segment and its entry in payments.cash.networks, e.g. fake. */
    String name();

    /** payments.provider once the network collected a code, e.g. FAKE_CASH. */
    String provider();

    /** Fans see it in the order's cash.networks. */
    String displayName();

    /** Checks the signature first; nothing else in the verdict counts unless signatureOk. */
    CashVerdict verify(HttpCallback request);

    /** The answer the network expects to a verified callback. */
    ResponseEntity<String> acknowledge(CashVerdict verdict, Outcome outcome);

    /** {@code payCode} as the network sent it (normalised by payments); money in centimes. */
    record CashVerdict(boolean signatureOk, String payCode, long amountMad, String networkRef) {

        public static CashVerdict rejected() {
            return new CashVerdict(false, null, 0, null);
        }
    }

    /**
     * ACCEPTED: the cash is recorded (the code paid, or a refund flagged when it can't be kept). ALREADY: this
     * confirmation was applied before. UNKNOWN: no such code, nothing written. REFUSED: the confirmation isn't taken
     * (a network ref already used on another code; an amount that isn't the code's).
     */
    enum Outcome { ACCEPTED, ALREADY, UNKNOWN, REFUSED }
}
