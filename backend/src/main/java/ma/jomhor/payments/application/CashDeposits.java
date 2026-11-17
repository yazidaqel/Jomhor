package ma.jomhor.payments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.payments.domain.CashDeposit;

/** Persistence port for cash_deposits and pos_sessions.deposit_id, scoped by organizer. */
public interface CashDeposits {

    /** A session of the organizer as a deposit checks it, row locked: closedAt null = open. */
    record Locked(UUID id, Instant closedAt, boolean deposited) {}

    /**
     * The organizer's sessions among {@code ids}, rows locked {@code for update} in id order (two deposits of
     * overlapping sessions can't deadlock).
     */
    List<Locked> lockSessions(UUID org, List<UUID> ids);

    /** A live deposit recorded by the caller (staff row of {@code userId}) taking {@code sessionIds}; its id. */
    UUID insert(UUID org, UUID userId, LocalDate depositedOn, long amountMad, String reference, List<UUID> sessionIds);

    /** Whether the deposit is voided, its row locked; empty if unknown or another organizer's. */
    Optional<Boolean> lockVoided(UUID org, UUID depositId);

    /** Voids a live deposit and frees its sessions; the freed session ids. */
    List<UUID> voidDeposit(UUID org, UUID userId, UUID depositId, String reason);

    Optional<CashDeposit> get(UUID org, UUID depositId);

    /** Deposits with deposited_on in [from, to], newest deposited_on first. */
    List<CashDeposit> between(UUID org, LocalDate from, LocalDate to);

    /** Closed sessions of the organizer with no live deposit, oldest closed_at first, at most {@code limit}. */
    List<CashDeposit.Session> undeposited(UUID org, int limit);
}
