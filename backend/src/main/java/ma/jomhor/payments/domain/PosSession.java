package ma.jomhor.payments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A till session (docs/03-schema.sql pos_sessions) with its running figures: orders and tickets paid in it,
 * {@code pos.print} calls, and {@code collectedMad} = sum of its SUCCEEDED payments. {@code expectedMad} and
 * {@code declaredMad} are set at close (expected frozen then). {@code deposit}: the live bank deposit that took a closed
 * session's cash, else null (Lot 5 step 5). Money in centimes.
 */
public record PosSession(UUID id, String label, UUID staffId, String staffName, Instant openedAt, Instant closedAt,
                         long orders, long tickets, long prints, long collectedMad, Long expectedMad,
                         Long declaredMad, Deposit deposit) {

    /** cash_deposits row that holds the session (04 /org/finance/cash sessions[].deposit). */
    public record Deposit(UUID id, String reference, LocalDate depositedOn) {}

    /** The frozen expected_mad once closed; while open, what the session collected so far. */
    public long expectedSoFar() {
        return expectedMad == null ? collectedMad : expectedMad;
    }

    /** declared - expected once closed, else null. */
    public Long varianceMad() {
        return closedAt == null ? null : declaredMad - expectedMad;
    }
}
