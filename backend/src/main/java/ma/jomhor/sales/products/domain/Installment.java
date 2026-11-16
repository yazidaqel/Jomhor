package ma.jomhor.sales.products.domain;

import java.time.Instant;
import java.util.UUID;

/** One payment of a pass's schedule (docs/04-api.md §Passes Installments); {@code orderId} the PAID order that paid it. */
public record Installment(UUID id, UUID passId, int seq, long amountMad, Instant dueAt, UUID orderId, Instant paidAt) {

    public boolean paid() {
        return paidAt != null;
    }
}
