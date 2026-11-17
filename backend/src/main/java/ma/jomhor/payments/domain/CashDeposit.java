package ma.jomhor.payments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Till cash taken to the bank (docs/03-schema.sql cash_deposits, docs/04-api.md §Finance Deposits): the slip's date,
 * amount and reference, and the closed sessions it took. A voided deposit holds no session any more (they were freed).
 * Money in centimes; staff ids are staff.id.
 */
public record CashDeposit(UUID id, LocalDate depositedOn, long amountMad, String reference, UUID recordedBy,
                          String recordedByName, Instant recordedAt, Instant voidedAt, UUID voidedBy,
                          String voidedByName, String voidReason, List<Session> sessions) {

    /** A closed till session as a deposit shows it. */
    public record Session(UUID id, String label, UUID staffId, String staffName, Instant closedAt, long declaredMad) {}

    public boolean voided() {
        return voidedAt != null;
    }

    /** Σ declared of its sessions; null once voided. */
    public Long declaredMad() {
        return voided() ? null : sessions.stream().mapToLong(Session::declaredMad).sum();
    }

    /** amount − Σ declared (negative: less cash on the slip than counted at the tills); null once voided. */
    public Long varianceMad() {
        return voided() ? null : amountMad - declaredMad();
    }
}
