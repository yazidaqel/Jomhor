package ma.jomhor.finance.application;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.finance.domain.Invoice;
import ma.jomhor.finance.domain.Payout;
import ma.jomhor.finance.domain.Settlement;
import ma.jomhor.finance.domain.Statement;
import ma.jomhor.payments.application.CashReport;
import ma.jomhor.payments.application.Payments.SettledPayment;
import ma.jomhor.payments.application.RefundService;
import ma.jomhor.sales.application.Orders.Subject;
import ma.jomhor.sales.application.SalesReport;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.domain.FinanceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Daily settlement statements and fee invoices (docs/04-api.md §Finance Statement and Invoice, plan lot-5 Q225–Q232):
 * the statement job and the /org/finance/settlements reads. Money in centimes, the figures from {@link Statement}.
 *
 * <p>The job: per organizer with valid settings.finance, one statement per transaction, in day order, under the
 * organizer's try-lock (10, hashtext(organizer id)), so another instance holding it builds that organizer this run;
 * unique (organizer_id, day) as a backstop. Each transaction: the statement, its lines, its invoice (number under the
 * yearly lock) and the audit row.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    /** collector_name of card and wallet lines: the gateway's name, for the simulated FAKE too (04). */
    static final String CARD_GATEWAY = "Payzone";

    /** Read model of GET /org/finance/settlements. {@code settings} and {@code open} null when not activated. */
    public record Listing(LocalDate from, LocalDate to, String timezone, FinanceSettings settings, Open open,
                          RefundsRequired refundsRequired, Due due, int vatRateBp, List<Row> settlements) {}

    /** SUCCEEDED payments not yet in a statement: completed_at ≥ {@code since}. */
    public record Open(Instant since, long collectedMad, long tillMad, long payments) {}

    public record RefundsRequired(long count, long amountMad) {}

    /** Every DUE statement of the organizer, whatever the range ("À recevoir"); overdue: due_on before today. */
    public record Due(long count, long amountMad, long overdue) {}

    /** A statement with overdue computed on the local today. */
    public record Row(Settlement settlement, boolean overdue) {}

    /** A line with its collector's display name (as /finance/cash). */
    public record Line(Settlement.Line line, String collectorName) {}

    public record Detail(Row row, List<Line> lines) {}

    /** The CSV read: the statements of the range and all their lines, by statement id. */
    public record Export(LocalDate from, LocalDate to, List<Row> settlements, Map<UUID, List<Line>> lines) {}

    private final Settlements settlements;
    private final OrganizerService organizers;
    private final CashReport payments;
    private final SalesReport sales;
    private final RefundService refunds;
    private final AuditLog audit;
    private final JsonMapper json;
    private final FinanceProperties config;
    private final ZoneId zone;
    private final TransactionTemplate tx;

    SettlementService(Settlements settlements, OrganizerService organizers, CashReport payments, SalesReport sales,
                      RefundService refunds, AuditLog audit, JsonMapper json, FinanceProperties config,
                      PlatformTransactionManager transactions, Environment env) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            config.checkIssuer();
        }
        this.settlements = settlements;
        this.organizers = organizers;
        this.payments = payments;
        this.sales = sales;
        this.refunds = refunds;
        this.audit = audit;
        this.json = json;
        this.config = config;
        this.zone = ZoneId.of(config.timezone());
        this.tx = new TransactionTemplate(transactions);
    }

    // ---------- the job ----------

    /** The job, on the database's wall clock, read in each statement's transaction and after the invoice lock. */
    public void run() {
        runEach(settlements::now);
    }

    /** One run at a fixed {@code now} (tests). */
    public void runAt(Instant now) {
        runEach(() -> now);
    }

    /**
     * Every activated organizer, statement after statement while one was built. An organizer that fails is logged
     * (exception class and SQL state; the message only for this class's own IllegalStateException, which names ids,
     * never data) and the others still run.
     */
    private void runEach(Supplier<Instant> clock) {
        organizers.withFinance().forEach((org, settings) -> {
            try {
                while (Boolean.TRUE.equals(tx.execute(s -> buildNext(org, settings, clock)))) {
                    // one statement issued: the next day
                }
            } catch (RuntimeException ex) {
                log.error("Settlement statement failed for organizer {}: {} (SQL state {}){}", org,
                        ex.getClass().getName(), sqlState(ex),
                        ex instanceof IllegalStateException ? ": " + ex.getMessage() : "");
            }
        });
    }

    /**
     * The organizer's next statement, if a day is due: the first local day d from the day after the latest statement
     * (or start_on) with a SUCCEEDED payment, once {@code now} ≥ start of d + 1 + issue-delay. False when none is due
     * or another instance holds the organizer's lock.
     */
    private boolean buildNext(UUID org, FinanceSettings settings, Supplier<Instant> clock) {
        if (!settlements.tryLock(org)) {
            return false;
        }
        Instant now = clock.get();
        Optional<Settlement> last = settlements.latest(org);
        LocalDate from = since(last, settings);
        LocalDate until = now.minus(config.issueDelay()).atZone(zone).toLocalDate(); // days before it are due
        if (!from.isBefore(until)) {
            return false;
        }
        Optional<Instant> first = payments.firstSucceeded(org, start(from), start(until));
        if (first.isEmpty()) {
            return false;
        }
        LocalDate day = first.get().atZone(zone).toLocalDate();
        List<SettledPayment> paid = payments.succeeded(org, start(day), start(day.plusDays(1)));
        Map<UUID, Subject> subjects = sales.subjects(org, paid.stream().map(SettledPayment::orderId).distinct().toList());
        List<Statement.Payment> rows = paid.stream().map(p -> {
            Subject s = subjects.get(p.orderId());
            if (s == null || (s.eventId() == null) == (s.productId() == null)) {
                throw new IllegalStateException("No event or product for order " + p.orderId() + " of organizer "
                        + org);
            }
            return new Statement.Payment(s.eventId(), s.productId(), p.method().name(), p.provider(), p.amountMad());
        }).toList();
        long carriedIn = last.map(l -> Statement.carry(l.collectedMad(), l.feeHtMad(), l.feeVatMad(), l.carriedInMad()))
                .orElse(0L);
        Statement st = Statement.build(rows, settings.feeRateBp(), config.vatRateBp(), carriedIn);
        UUID id = settlements.insert(org, day, st, Payout.dueOn(day, config.payoutDays()), now);
        String number = st.feeHtMad() > 0 ? invoice(org, id, settings, st, clock) : null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("day", day.toString());
        payload.put("payout_mad", st.payoutMad());
        payload.put("invoice", number);
        audit.system(org, "settlement.issue", id.toString(), json.writeValueAsString(payload));
        return true;
    }

    /**
     * The invoice of a statement with a fee: numbered in this transaction, details copied in. The issue date is read
     * after the year's lock is held, so two instances crossing midnight never give a later number an earlier date; if
     * that read crosses New Year, the new year's lock is taken too (years in increasing order: no deadlock). Returns
     * its number.
     */
    private String invoice(UUID org, UUID settlementId, FinanceSettings settings, Statement st,
                           Supplier<Instant> clock) {
        int locked = clock.get().atZone(zone).getYear();
        settlements.lockInvoiceYear(locked);
        LocalDate issuedOn = clock.get().atZone(zone).toLocalDate();
        int year = issuedOn.getYear();
        if (year != locked) {
            settlements.lockInvoiceYear(year);
        }
        int seq = settlements.nextInvoiceSeq(year);
        String number = "JMH-%d-%06d".formatted(year, seq);
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("legal_name", settings.legalName());
        customer.put("address", settings.address());
        customer.put("ice", settings.ice());
        settlements.insertInvoice(org, settlementId, year, seq, number, issuedOn,
                json.writeValueAsString(config.issuer()), json.writeValueAsString(customer), st.feeHtMad(),
                config.vatRateBp(), st.feeVatMad());
        return number;
    }

    /** The first day not yet covered: the day after the latest statement, never before start_on. */
    private static LocalDate since(Optional<Settlement> last, FinanceSettings settings) {
        return last.map(l -> l.day().plusDays(1)).filter(d -> d.isAfter(settings.startOn()))
                .orElse(settings.startOn());
    }

    private Instant start(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    // ---------- payout and invoice (04 POST /org/finance/settlements/{id}/paid, GET /org/finance/invoices/{id}.pdf) ----------

    /**
     * "Versement reçu": {@code reference} trimmed, 1–100 (else 422). Under the row lock: DUE → PAID now by the caller's
     * staff row, audit settlement.paid {reference}; PAID → unchanged, nothing audited; NONE → 409 SETTLEMENT_STATUS.
     * Unknown or another organizer's → 404. Returns the row.
     */
    @Transactional
    public Row paid(UUID org, UUID userId, UUID id, String reference) {
        String ref = reference == null ? "" : reference.strip();
        if (ref.isEmpty() || ref.length() > 100) {
            throw new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                    Map.of("fields", Map.of("reference", "1 to 100 characters")));
        }
        String status = settlements.lockStatus(org, id).orElseThrow(() -> ApiException.notFound("Settlement not found"));
        switch (status) {
            case "NONE" -> throw new ApiException("SETTLEMENT_STATUS", HttpStatus.CONFLICT, "Nothing to pay out",
                    Map.of("status", status));
            case "DUE" -> {
                settlements.markPaid(org, userId, id, ref);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("reference", ref);
                audit.staff(org, userId, "settlement.paid", id.toString(), json.writeValueAsString(payload));
            }
            default -> { } // PAID: a replay, unchanged
        }
        return row(settlements.get(org, id).orElseThrow(), today());
    }

    /** The invoice as stored, for its PDF; unknown or another organizer's → 404. */
    @Transactional(readOnly = true)
    public Invoice invoice(UUID org, UUID invoiceId) {
        Settlements.StoredInvoice i = settlements.invoice(org, invoiceId)
                .orElseThrow(() -> ApiException.notFound("Invoice not found"));
        return new Invoice(i.id(), i.number(), i.issuedOn(), strings(i.issuerJson()), strings(i.customerJson()), i.day(),
                i.baseMad(), i.feeRateBp(), i.amountHtMad(), i.vatRateBp(), i.vatMad(), i.amountTtcMad());
    }

    /** A stored snapshot's string fields (other values are left out). */
    private Map<String, String> strings(String jsonObject) {
        Map<String, String> out = new LinkedHashMap<>();
        json.readTree(jsonObject).properties().forEach(e -> {
            if (e.getValue().isString()) {
                out.put(e.getKey(), e.getValue().asString());
            }
        });
        return out;
    }

    // ---------- reads (04 /org/finance/settlements*) ----------

    /** GET /org/finance/settlements: the range as /finance/cash (both days included, ≤ 92, else 422). One snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Listing list(UUID org, String from, String to) {
        CashReport.Days days = CashReport.days(from, to);
        FinanceSettings settings = organizers.finance(org).orElse(null);
        Open open = null;
        if (settings != null) {
            Instant since = start(since(settlements.latest(org), settings));
            long[] t = payments.succeededSince(org, since);
            open = new Open(since, t[0], t[1], t[2]);
        }
        long[] required = refunds.requiredTotals(org);
        long[] due = settlements.due(org, today());
        return new Listing(days.from(), days.to(), config.timezone(), settings, open,
                new RefundsRequired(required[0], required[1]),
                new Due(due[0], due[1], due[2]), config.vatRateBp(), rows(org, days));
    }

    /** GET /org/finance/settlements/{id}: unknown or another organizer's → 404. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail get(UUID org, UUID id) {
        Settlement s = settlements.get(org, id).orElseThrow(() -> ApiException.notFound("Settlement not found"));
        return new Detail(row(s, today()), lines(org, List.of(id)));
    }

    /** GET /org/finance/settlements.csv: the range rules of the list. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Export export(UUID org, String from, String to) {
        CashReport.Days days = CashReport.days(from, to);
        List<Row> rows = rows(org, days);
        Map<UUID, List<Line>> lines = new LinkedHashMap<>();
        lines(org, rows.stream().map(r -> r.settlement().id()).toList())
                .forEach(l -> lines.computeIfAbsent(l.line().settlementId(), k -> new ArrayList<>()).add(l));
        return new Export(days.from(), days.to(), rows, lines);
    }

    private List<Row> rows(UUID org, CashReport.Days days) {
        LocalDate today = today();
        return settlements.between(org, days.from(), days.to()).stream().map(s -> row(s, today)).toList();
    }

    private List<Line> lines(UUID org, List<UUID> ids) {
        return ids.isEmpty() ? List.of() : settlements.lines(org, ids).stream()
                .map(l -> new Line(l, collectorName(l))).toList();
    }

    /** 04: tills "Guichet" and networks by display name (as /finance/cash); card and wallet "Payzone" (FAKE too). */
    private String collectorName(Settlement.Line l) {
        return l.method().equals("CARD") || l.method().equals("WALLET") ? CARD_GATEWAY
                : payments.collectorName(l.collector());
    }

    private static Row row(Settlement s, LocalDate today) {
        return new Row(s, Payout.overdue(s.status(), s.dueOn(), today));
    }

    private LocalDate today() {
        return settlements.now().atZone(zone).toLocalDate();
    }
}
