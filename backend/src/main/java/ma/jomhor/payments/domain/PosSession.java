package ma.jomhor.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A till session (docs/03-schema.sql pos_sessions) with its running figures: orders and tickets paid in it,
 * {@code pos.print} calls, and {@code collectedMad} = sum of its SUCCEEDED payments. {@code expectedMad} and
 * {@code declaredMad} are set at close (expected frozen then). Money in centimes.
 */
public record PosSession(UUID id, String label, UUID staffId, String staffName, Instant openedAt, Instant closedAt,
                         long orders, long tickets, long prints, long collectedMad, Long expectedMad,
                         Long declaredMad) {

    /** The frozen expected_mad once closed; while open, what the session collected so far. */
    public long expectedSoFar() {
        return expectedMad == null ? collectedMad : expectedMad;
    }

    /** declared - expected once closed, else null. */
    public Long varianceMad() {
        return closedAt == null ? null : declaredMad - expectedMad;
    }
}
