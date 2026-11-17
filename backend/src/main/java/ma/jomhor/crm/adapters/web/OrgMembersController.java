package ma.jomhor.crm.adapters.web;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import ma.jomhor.common.Csv;
import ma.jomhor.crm.application.CrmExportService;
import ma.jomhor.crm.application.MembershipService;
import ma.jomhor.crm.application.Memberships.ExportRow;
import ma.jomhor.tenancy.domain.OrgContext;
import ma.jomhor.tenancy.domain.StaffRole;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /org/members/summary (ADMIN, OPS, FINANCE: counts only) and GET /org/crm/export.csv (ADMIN only: personal
 * data), docs/04-api.md §Organizer and §Finance CRM export. CASHIER, GATE and SUPERVISOR get 403 from the interceptor.
 */
@RestController
class OrgMembersController {

    static final String CSV_HEADER =
            "numero;nom;telephone;langue;membre_depuis;notifications;whatsapp;cotisation_jusqu_au;abonne;matchs";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(CrmExportService.ZONE);

    private final CrmExportService export;

    OrgMembersController(CrmExportService export) {
        this.export = export;
    }

    @GetMapping("/org/members/summary")
    MembershipService.Summary summary(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        return export.summary(org.organizerId());
    }

    @GetMapping("/org/crm/export.csv")
    ResponseEntity<byte[]> csv(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        org.only(StaffRole.ADMIN);
        CrmExportService.Export e = export.export(org.organizerId(), org.userId());
        return ResponseEntity.ok().contentType(Csv.MEDIA_TYPE).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fans-" + e.day() + ".csv\"")
                .body(Csv.withBom(csv(e.rows())));
    }

    /**
     * By number; CRLF. {@code nom} is typed by the fan: the text guard. {@code telephone} is E.164 (digits after a +,
     * validated on write), so it is written as it is.
     */
    static String csv(List<ExportRow> rows) {
        StringBuilder out = new StringBuilder(CSV_HEADER).append("\r\n");
        rows.forEach(r -> out.append(String.join(";", Integer.toString(r.number()),
                Csv.text(r.name() == null ? "" : r.name()), r.phone() == null ? "" : r.phone(), r.locale(),
                day(r.joinedAt()), yes(r.marketingPush()), yes(r.marketingWhatsapp()), day(r.paidUntil()),
                yes(r.passHolder()), Long.toString(r.eventsAttended()))).append("\r\n"));
        return out.toString();
    }

    private static String day(Instant at) {
        return at == null ? "" : DAY.format(at);
    }

    private static String yes(boolean b) {
        return b ? "oui" : "non";
    }
}
