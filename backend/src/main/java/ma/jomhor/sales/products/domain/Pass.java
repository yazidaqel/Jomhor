package ma.jomhor.sales.products.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A season pass (docs/04-api.md §Passes): held by its buyer {@code userId} under {@code holderName} (§Holder names),
 * one not CANCELLED per account per series.
 */
public record Pass(UUID id, UUID organizerId, UUID productId, UUID seriesId, UUID userId, String holderName,
                   Status status, Instant suspendedAt, Instant createdAt) {

    /**
     * PENDING until its purchase order is PAID; CANCELLED if that expires; SUSPENDED (with {@code suspendedAt}) while an
     * installment is overdue past passes.grace.
     */
    public enum Status { PENDING, ACTIVE, SUSPENDED, CANCELLED }
}
