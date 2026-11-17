package ma.jomhor.finance.adapters.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ma.jomhor.common.Csv;
import ma.jomhor.finance.application.SettlementService;
import ma.jomhor.finance.application.SettlementService.Export;
import ma.jomhor.finance.application.SettlementService.Line;
import ma.jomhor.finance.application.SettlementService.Listing;
import ma.jomhor.finance.application.SettlementService.Row;
import ma.jomhor.finance.adapters.pdf.InvoicePdf;
import ma.jomhor.finance.domain.Invoice;
import ma.jomhor.finance.domain.Settlement;
import ma.jomhor.payments.application.CashReport;
import ma.jomhor.tenancy.domain.FinanceSettings;
import ma.jomhor.tenancy.domain.OrgContext;
import ma.jomhor.tenancy.domain.StaffRole;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /org/finance/settlements, /org/finance/settlements/{id}, /org/finance/settlements.csv, POST
 * /org/finance/settlements/{id}/paid and GET /org/finance/invoices/{id}.pdf (docs/04-api.md §Organizer, §Finance):
 * ADMIN, FINANCE; OPS 403 here, CASHIER, GATE and SUPERVISOR from the interceptor.
 * Money in centimes in JSON, DH with a comma in the CSV. Only the RIB's last 4 digits leave the server.
 */
@RestController
class OrgFinanceSettlementController {

    static final String CSV_HEADER = "kind;jour;libelle;moyen;encaisseur;paiements;montant;en_ligne;guichets;frais_ht;"
            + "tva;report;a_verser;echeance;statut;facture;reference";

    record SettingsDto(String legalName, String ice, String ribLast4, int feeRateBp, LocalDate startOn) {
        static SettingsDto of(FinanceSettings f) {
            return f == null ? null : new SettingsDto(f.legalName(), f.ice(), f.ribLast4(), f.feeRateBp(), f.startOn());
        }
    }

    record OpenDto(Instant since, long collectedMad, long tillMad, long payments) {}

    record RefundsRequiredDto(long count, long amountMad) {}

    record DueDto(long count, long amountMad, long overdue) {}

    record StaffRef(UUID id, String name) {}

    record InvoiceRef(UUID id, String number) {}

    /** 04 settlements[] row. */
    record RowDto(UUID id, LocalDate day, String status, boolean overdue, long collectedMad, long tillMad,
                  int feeRateBp, long feeHtMad, long feeVatMad, long carriedInMad, long payoutMad, LocalDate dueOn,
                  Instant paidAt, StaffRef paidBy, String payoutReference, InvoiceRef invoice) {
        static RowDto of(Row r) {
            Settlement s = r.settlement();
            return new RowDto(s.id(), s.day(), s.status(), r.overdue(), s.collectedMad(), s.tillMad(), s.feeRateBp(),
                    s.feeHtMad(), s.feeVatMad(), s.carriedInMad(), s.payoutMad(), s.dueOn(), s.paidAt(),
                    s.paidById() == null ? null : new StaffRef(s.paidById(), s.paidByName()), s.payoutReference(),
                    s.invoiceId() == null ? null : new InvoiceRef(s.invoiceId(), s.invoiceNumber()));
        }
    }

    record ListDto(LocalDate from, LocalDate to, String timezone, SettingsDto settings, OpenDto open,
                   RefundsRequiredDto refundsRequired, DueDto due, int vatRateBp, List<RowDto> settlements) {}

    record LineDto(Integer eventId, UUID productId, String title, String method, String collector,
                   String collectorName, int payments, long amountMad) {
        static LineDto of(Line l) {
            Settlement.Line x = l.line();
            return new LineDto(x.eventId(), x.productId(), x.title(), x.method(), x.collector(), l.collectorName(),
                    x.payments(), x.amountMad());
        }
    }

    /** The row + its lines. */
    record DetailDto(@JsonUnwrapped RowDto row, List<LineDto> lines) {}

    record PaidBody(String reference) {}

    private final SettlementService settlements;

    OrgFinanceSettlementController(SettlementService settlements) {
        this.settlements = settlements;
    }

    @GetMapping("/org/finance/settlements")
    ListDto list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @RequestParam(required = false) String from,
                 @RequestParam(required = false) String to) {
        org.only(StaffRole.ADMIN, StaffRole.FINANCE);
        Listing l = settlements.list(org.organizerId(), from, to);
        return new ListDto(l.from(), l.to(), l.timezone(), SettingsDto.of(l.settings()),
                l.open() == null ? null : new OpenDto(l.open().since(), l.open().collectedMad(), l.open().tillMad(),
                        l.open().payments()),
                new RefundsRequiredDto(l.refundsRequired().count(), l.refundsRequired().amountMad()),
                new DueDto(l.due().count(), l.due().amountMad(), l.due().overdue()), l.vatRateBp(),
                l.settlements().stream().map(RowDto::of).toList());
    }

    @GetMapping("/org/finance/settlements/{id}")
    DetailDto get(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        org.only(StaffRole.ADMIN, StaffRole.FINANCE);
        SettlementService.Detail d = settlements.get(org.organizerId(), id);
        return new DetailDto(RowDto.of(d.row()), d.lines().stream().map(LineDto::of).toList());
    }

    @GetMapping("/org/finance/settlements.csv")
    ResponseEntity<byte[]> csv(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                               @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        org.only(StaffRole.ADMIN, StaffRole.FINANCE);
        Export e = settlements.export(org.organizerId(), from, to);
        return ResponseEntity.ok().contentType(Csv.MEDIA_TYPE).header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"releves-" + e.from() + "-" + e.to() + ".csv\"").body(Csv.withBom(csv(e)));
    }

    /** "Versement reçu" (04 POST /org/finance/settlements/{id}/paid): ADMIN, FINANCE. */
    @PostMapping("/org/finance/settlements/{id}/paid")
    RowDto paid(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                @RequestBody PaidBody body) {
        org.only(StaffRole.ADMIN, StaffRole.FINANCE);
        return RowDto.of(settlements.paid(org.organizerId(), org.userId(), id, body.reference()));
    }

    /** The invoice PDF, drawn from the stored row (04 GET /org/finance/invoices/{id}.pdf): ADMIN, FINANCE. */
    @GetMapping("/org/finance/invoices/{id}.pdf")
    ResponseEntity<byte[]> invoice(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        org.only(StaffRole.ADMIN, StaffRole.FINANCE);
        Invoice i = settlements.invoice(org.organizerId(), id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"facture-" + i.number() + ".pdf\"")
                .body(InvoicePdf.render(i));
    }

    /** Per statement (newest day first): its releve row, then its ligne rows; CRLF. */
    static String csv(Export e) {
        StringBuilder out = new StringBuilder(CSV_HEADER).append("\r\n");
        for (Row r : e.settlements()) {
            Settlement s = r.settlement();
            row(out, "releve", s.day().toString(), "", "", "", "", "", CashReport.dh(s.collectedMad()),
                    CashReport.dh(s.tillMad()), CashReport.dh(s.feeHtMad()), CashReport.dh(s.feeVatMad()),
                    CashReport.dh(s.carriedInMad()), CashReport.dh(s.payoutMad()), s.dueOn().toString(), s.status(),
                    s.invoiceNumber() == null ? "" : s.invoiceNumber(),
                    s.payoutReference() == null ? "" : Csv.text(s.payoutReference()));
            for (Line l : e.lines().getOrDefault(s.id(), List.of())) {
                Settlement.Line x = l.line();
                row(out, "ligne", s.day().toString(), Csv.text(x.title()), x.method(), Csv.text(l.collectorName()),
                        Integer.toString(x.payments()), CashReport.dh(x.amountMad()), "", "", "", "", "", "", "", "",
                        "", "");
            }
        }
        return out.toString();
    }

    private static void row(StringBuilder out, String... cells) {
        out.append(String.join(";", cells)).append("\r\n");
    }
}
