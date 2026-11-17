package ma.jomhor.finance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An issued statement as stored (settlements, with its payer's name and its invoice). Money in centimes.
 * {@code paidById}/{@code paidByName}: the staff row that recorded "Versement reçu"; invoice fields null when none.
 */
public record Settlement(UUID id, LocalDate day, String status, long collectedMad, long tillMad, int feeRateBp,
                         long feeHtMad, long feeVatMad, long carriedInMad, long payoutMad, LocalDate dueOn,
                         Instant paidAt, UUID paidById, String paidByName, String payoutReference, UUID invoiceId,
                         String invoiceNumber) {

    /** A stored line, with its subject's title (event title or product name). */
    public record Line(UUID settlementId, Integer eventId, UUID productId, String title, String method,
                       String collector, int payments, long amountMad) {}
}
