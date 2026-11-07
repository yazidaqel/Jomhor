package ma.jomhor.payments.adapters.web;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import ma.jomhor.common.Csv;
import ma.jomhor.payments.adapters.web.PosController.ClosedSession;
import ma.jomhor.payments.adapters.web.PosController.StaffRef;
import ma.jomhor.payments.application.CashReport;
import ma.jomhor.payments.application.CashReport.Report;
import ma.jomhor.payments.domain.PosSession;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /org/finance/cash and /org/finance/cash.csv (docs/04-api.md §Organizer). ADMIN, OPS, FINANCE (GETs); CASHIER,
 * GATE and SUPERVISOR get 403 from the org interceptor. Money in centimes in JSON, DH with a comma in the CSV.
 */
@RestController
class OrgCashController {

    static final String CSV_HEADER =
            "kind;jour;encaisseur;moyen;paiements;montant;caisse;caissier;ouverture;cloture;attendu;declare;ecart";
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    record DayDto(LocalDate day, String collector, String collectorName, String method, long payments,
                  long amountMad) {}

    record TotalsDto(long collectedMad, long declaredMad, long varianceMad, long openSessions) {}

    record CashDto(LocalDate from, LocalDate to, String timezone, List<DayDto> days, List<ClosedSession> sessions,
                   TotalsDto totals) {}

    private final CashReport report;

    OrgCashController(CashReport report) {
        this.report = report;
    }

    @GetMapping("/org/finance/cash")
    CashDto cash(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @RequestParam(required = false) String from,
                 @RequestParam(required = false) String to, @RequestParam(required = false) String timezone) {
        Report r = report.of(org.organizerId(), from, to, timezone);
        var t = r.totals();
        return new CashDto(r.from(), r.to(), r.timezone(),
                r.days().stream().map(d -> new DayDto(d.day(), d.collector(), d.collectorName(), d.method(),
                        d.payments(), d.amountMad())).toList(),
                r.sessions().stream().map(OrgCashController::session).toList(),
                new TotalsDto(t.collectedMad(), t.declaredMad(), t.varianceMad(), t.openSessions()));
    }

    @GetMapping("/org/finance/cash.csv")
    ResponseEntity<byte[]> csv(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                               @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                               @RequestParam(required = false) String timezone) {
        Report r = report.of(org.organizerId(), from, to, timezone);
        return ResponseEntity.ok().contentType(Csv.MEDIA_TYPE).header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"encaissements-" + r.from() + "-" + r.to() + ".csv\"").body(Csv.withBom(csv(r)));
    }

    private static ClosedSession session(PosSession s) {
        return new ClosedSession(s.id(), s.label(), new StaffRef(s.staffId(), s.staffName()), s.openedAt(),
                s.closedAt(), s.orders(), s.tickets(), s.prints(), s.expectedSoFar(), s.declaredMad(), s.varianceMad());
    }

    /** jour rows (day × collector × method), then caisse rows (sessions); CRLF; local times in the report's zone. */
    static String csv(Report r) {
        ZoneId zone = ZoneId.of(r.timezone());
        StringBuilder out = new StringBuilder(CSV_HEADER).append("\r\n");
        r.days().forEach(d -> row(out, "jour", d.day().toString(), Csv.text(d.collectorName()), d.method(),
                Long.toString(d.payments()), CashReport.dh(d.amountMad()), "", "", "", "", "", "", ""));
        r.sessions().forEach(s -> row(out, "caisse", "", "", "", "", "", Csv.text(s.label()), Csv.text(s.staffName()),
                LOCAL.format(s.openedAt().atZone(zone)), s.closedAt() == null ? "" : LOCAL.format(s.closedAt().atZone(zone)),
                CashReport.dh(s.expectedSoFar()), s.declaredMad() == null ? "" : CashReport.dh(s.declaredMad()),
                s.varianceMad() == null ? "" : CashReport.dh(s.varianceMad())));
        return out.toString();
    }

    private static void row(StringBuilder out, String... cells) {
        out.append(String.join(";", cells)).append("\r\n");
    }

}
