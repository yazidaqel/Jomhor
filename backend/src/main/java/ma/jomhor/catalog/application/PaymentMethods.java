package ma.jomhor.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the public event needs from payments (docs/04-api.md GET /fan/events/{id} payment_methods). Implemented by
 * the payments module: catalog never reads its configuration.
 */
public interface PaymentMethods {

    /**
     * {@code methods}: a subset of CARD, WALLET, CASH_AGENCY in that order. {@code cashUntil}: sales close −
     * payments.cash.min-window when cash is set up for the organizer, else null (whether or not it has passed).
     */
    record Options(List<String> methods, Instant cashUntil) {}

    /** For an event of {@code org} whose sales close at {@code salesCloseAt}. */
    Options available(UUID org, Instant salesCloseAt, Instant now);
}
