package ma.jomhor.finance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.finance.domain.Settlement;
import ma.jomhor.finance.domain.Statement;

/** Persistence port for settlements, settlement_lines and invoices. Scoped by organizer_id. */
public interface Settlements {

    /** The statement try-lock (10, hashtext(organizer id)) until the transaction ends; false if another holds it. */
    boolean tryLock(UUID org);

    /** The database's wall clock, clock_timestamp() (not the transaction start): one clock for every instance. */
    Instant now();

    /** The organizer's latest statement (by day). */
    Optional<Settlement> latest(UUID org);

    /** The statement and its lines, issued at {@code issuedAt}; returns its id. */
    UUID insert(UUID org, LocalDate day, Statement s, LocalDate dueOn, Instant issuedAt);

    /** pg_advisory_xact_lock((11 << 32) | year), held until the transaction ends: invoice numbers of that year. */
    void lockInvoiceYear(int year);

    /** max(seq) + 1 of {@code year}; call it under {@link #lockInvoiceYear}, so numbers have no gap. */
    int nextInvoiceSeq(int year);

    /** An invoice row; {@code issuerJson}/{@code customerJson} are JSON objects (the snapshots). Returns its id. */
    UUID insertInvoice(UUID org, UUID settlementId, int year, int seq, String number, LocalDate issuedOn,
                       String issuerJson, String customerJson, long htMad, int vatRateBp, long vatMad);

    /** Statements with day in [from, to], newest day first. */
    List<Settlement> between(UUID org, LocalDate from, LocalDate to);

    Optional<Settlement> get(UUID org, UUID id);

    /** Every DUE statement of the organizer, whatever its day: {count, Σ payout_mad, count with due_on < today}. */
    long[] due(UUID org, LocalDate today);

    /** The statement's status, its row locked until the transaction ends; empty if not the organizer's. */
    Optional<String> lockStatus(UUID org, UUID id);

    /** DUE → PAID now by the staff row of {@code userId}, with the bank reference. */
    void markPaid(UUID org, UUID userId, UUID id, String reference);

    /** The invoice and its statement's day, base and rate; issuer and customer as stored JSON text. */
    Optional<StoredInvoice> invoice(UUID org, UUID invoiceId);

    record StoredInvoice(UUID id, String number, LocalDate issuedOn, String issuerJson, String customerJson,
                         LocalDate day, long baseMad, int feeRateBp, long amountHtMad, int vatRateBp, long vatMad,
                         long amountTtcMad) {}

    /** The lines of these statements, by title, then method, then collector. */
    List<Settlement.Line> lines(UUID org, List<UUID> settlementIds);
}
