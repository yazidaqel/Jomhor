package ma.jomhor.payments.adapters.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import ma.jomhor.common.Csv;
import ma.jomhor.payments.adapters.web.PosController.ClosedSession;
import ma.jomhor.payments.adapters.web.PosController.StaffRef;
import ma.jomhor.payments.application.CashReport;
import ma.jomhor.payments.application.CashReport.Report;
import ma.jomhor.payments.application.DepositService;
import ma.jomhor.payments.domain.CashDeposit;
import ma.jomhor.tenancy.domain.OrgContext;
import ma.jomhor.tenancy.domain.StaffRole;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /org/finance/cash and /org/finance/cash.csv (docs/04-api.md §Organizer), /org/finance/deposits* (§Finance
 * Deposits). GETs: ADMIN, OPS, FINANCE; a deposit: ADMIN, FINANCE; a void: ADMIN. CASHIER, GATE and SUPERVISOR get 403
 * from the org interceptor. Money in centimes in JSON, DH with a comma in the CSV.
 */
@RestController
class OrgCashController {

    static final String CSV_HEADER =
            "kind;jour;encaisseur;moyen;paiements;montant;caisse;caissier;ouverture;cloture;attendu;declare;ecart;depot;"
            + "depose_le";
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    record DayDto(LocalDate day, String collector, String collectorName, String method, long payments,
                  long amountMad) {}

    record TotalsDto(long collectedMad, long declaredMad, long varianceMad, long openSessions, long undepositedMad) {}

    record CashDto(LocalDate from, LocalDate to, String timezone, List<DayDto> days, List<ClosedSession> sessions,
                   TotalsDto totals) {}

    record DepositBody(LocalDate depositedOn, Long amountMad, String reference, List<UUID> sessionIds) {}

    record VoidBody(String reason) {}

    /** A session as a deposit lists it (04 deposits[].sessions[] and undeposited[]). */
    record DepositSession(UUID id, String label, StaffRef staff, Instant closedAt, long declaredMad) {
        static DepositSession of(CashDeposit.Session s) {
            return new DepositSession(s.id(), s.label(), new StaffRef(s.staffId(), s.staffName()), s.closedAt(),
                    s.declaredMad());
        }
    }

    /** 04 deposits[] row; the POST and the void return it too. */
    record DepositDto(UUID id, LocalDate depositedOn, long amountMad, String reference, StaffRef recordedBy,
                      Instant recordedAt, Instant voidedAt, StaffRef voidedBy, String voidReason,
                      List<DepositSession> sessions, Long declaredMad, Long varianceMad) {
        static DepositDto of(CashDeposit d) {
            return new DepositDto(d.id(), d.depositedOn(), d.amountMad(), d.reference(),
                    new StaffRef(d.recordedBy(), d.recordedByName()), d.recordedAt(), d.voidedAt(),
                    d.voidedBy() == null ? null : new StaffRef(d.voidedBy(), d.voidedByName()), d.voidReason(),
                    d.sessions().stream().map(DepositSession::of).toList(), d.declaredMad(), d.varianceMad());
        }
    }

    record DepositsDto(List<DepositDto> deposits, List<DepositSession> undeposited) {}

    private final CashReport report;
    private final DepositService deposits;

    OrgCashController(CashReport report, DepositService deposits) {
        this.report = report;
        this.deposits = deposits;
    }

    @GetMapping("/org/finance/cash")
    CashDto cash(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @RequestParam(required = false) String from,
                 @RequestParam(required = false) String to, @RequestParam(required = false) String timezone) {
        Report r = report.of(org.organizerId(), from, to, timezone);
        var t = r.totals();
        return new CashDto(r.from(), r.to(), r.timezone(),
                r.days().stream().map(d -> new DayDto(d.day(), d.collector(), d.collectorName(), d.method(),
                        d.payments(), d.amountMad())).toList(),
                r.sessions().stream().map(ClosedSession::of).toList(),
                new TotalsDto(t.collectedMad(), t.declaredMad(), t.varianceMad(), t.openSessions(), t.undepositedMad()));
    }

    @GetMapping("/org/finance/cash.csv")
    ResponseEntity<byte[]> csv(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                               @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                               @RequestParam(required = false) String timezone) {
        Report r = report.of(org.organizerId(), from, to, timezone);
        return ResponseEntity.ok().contentType(Csv.MEDIA_TYPE).header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"encaissements-" + r.from() + "-" + r.to() + ".csv\"").body(Csv.withBom(csv(r)));
    }

    @GetMapping("/org/finance/deposits")
    DepositsDto deposits(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                         @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        org.only(StaffRole.ADMIN, StaffRole.OPS, StaffRole.FINANCE);
        DepositService.Listing l = deposits.list(org.organizerId(), from, to);
        return new DepositsDto(l.deposits().stream().map(DepositDto::of).toList(),
                l.undeposited().stream().map(DepositSession::of).toList());
    }

    @PostMapping("/org/finance/deposits")
    ResponseEntity<DepositDto> deposit(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                                       @RequestBody DepositBody body) {
        org.only(StaffRole.ADMIN, StaffRole.FINANCE);
        return ResponseEntity.status(HttpStatus.CREATED).body(DepositDto.of(deposits.create(org.organizerId(),
                org.userId(), body.depositedOn(), body.amountMad(), body.reference(), body.sessionIds())));
    }

    @PostMapping("/org/finance/deposits/{id}/void")
    DepositDto voidDeposit(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                           @RequestBody VoidBody body) {
        org.only(StaffRole.ADMIN);
        return DepositDto.of(deposits.voidDeposit(org.organizerId(), org.userId(), id, body.reason()));
    }

    /**
     * jour rows (day × collector × method), then caisse rows (sessions, with their live deposit's reference and slip
     * date); CRLF; local times in the report's zone.
     */
    static String csv(Report r) {
        ZoneId zone = ZoneId.of(r.timezone());
        StringBuilder out = new StringBuilder(CSV_HEADER).append("\r\n");
        r.days().forEach(d -> row(out, "jour", d.day().toString(), Csv.text(d.collectorName()), d.method(),
                Long.toString(d.payments()), CashReport.dh(d.amountMad()), "", "", "", "", "", "", "", "", ""));
        r.sessions().forEach(s -> row(out, "caisse", "", "", "", "", "", Csv.text(s.label()), Csv.text(s.staffName()),
                LOCAL.format(s.openedAt().atZone(zone)), s.closedAt() == null ? "" : LOCAL.format(s.closedAt().atZone(zone)),
                CashReport.dh(s.expectedSoFar()), s.declaredMad() == null ? "" : CashReport.dh(s.declaredMad()),
                s.varianceMad() == null ? "" : CashReport.dh(s.varianceMad()),
                s.deposit() == null ? "" : Csv.text(s.deposit().reference()),
                s.deposit() == null ? "" : s.deposit().depositedOn().toString()));
        return out.toString();
    }

    private static void row(StringBuilder out, String... cells) {
        out.append(String.join(";", cells)).append("\r\n");
    }

}
