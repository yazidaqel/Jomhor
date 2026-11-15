package ma.jomhor.sales.products.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A season pass (docs/04-api.md §Passes): held by its buyer {@code userId} under {@code holderName} (§Holder names),
 * one not CANCELLED per account per series.
 */
public record Pass(UUID id, UUID organizerId, UUID productId, UUID seriesId, UUID userId, String holderName,
                   Status status, Instant createdAt) {

    /** PENDING until its purchase order is PAID; CANCELLED if that expires. SUSPENDED is plan lot-5 3b. */
    public enum Status { PENDING, ACTIVE, SUSPENDED, CANCELLED }
}
