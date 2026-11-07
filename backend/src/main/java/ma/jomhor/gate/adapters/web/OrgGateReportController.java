package ma.jomhor.gate.adapters.web;

import ma.jomhor.common.Csv;
import ma.jomhor.gate.application.GateReports;
import ma.jomhor.gate.application.GateReports.Live;
import ma.jomhor.gate.application.GateReports.Reconciliation;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Gate reports: GET /org/events/{id}/live, /reconciliation, /reconciliation.csv. ADMIN, OPS and
 * FINANCE (GETs); GATE and SUPERVISOR get 403 from the org interceptor.
 *
 * <p>Deliberately, the JSON bodies are GateReports' application records serialized as they are, with no mirror DTO
 * layer: those records exist only as these read models (no entity, no domain state), and GateReportsTest locks every
 * key set against 04, so a field added to a record fails a test before it reaches a client.
 */
@RestController
class OrgGateReportController {


    private final GateReports reports;

    OrgGateReportController(GateReports reports) {
        this.reports = reports;
    }

    @GetMapping("/org/events/{id}/live")
    Live live(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return reports.live(org.organizerId(), id);
    }

    @GetMapping("/org/events/{id}/reconciliation")
    Reconciliation reconciliation(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return reports.reconciliation(org.organizerId(), id);
    }

    @GetMapping("/org/events/{id}/reconciliation.csv")
    ResponseEntity<byte[]> csv(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        byte[] body = Csv.withBom(GateReports.csv(reports.reconciliation(org.organizerId(), id)));
        return ResponseEntity.ok().contentType(Csv.MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reconciliation-" + id + ".csv\"")
                .body(body);
    }
}
