package ma.jomhor.gate.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.gate.application.AssignmentService;
import ma.jomhor.gate.application.AssignmentService.Listed;
import ma.jomhor.gate.application.AssignmentService.Row;
import ma.jomhor.gate.domain.Device;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** docs/04-api.md §Organizer: GET/PUT /org/events/{id}/devices. ADMIN/OPS only. */
@RestController
class OrgAssignmentController {

    record AssignmentsPut(List<Row> assignments) {}

    record AssignmentDto(UUID deviceId, String label, Device.Status status, UUID gateId, String gateCode,
                         Integer manifestVersion, Instant lastSeenAt, Instant closedAckAt) {
        static AssignmentDto of(Listed l) {
            var a = l.assignment();
            return new AssignmentDto(a.deviceId(), a.label(), a.status(), a.gateId(), l.gateCode(), a.manifestVersion(),
                    a.lastSeenAt(), a.closedAckAt());
        }
    }

    private final AssignmentService assignments;

    OrgAssignmentController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/org/events/{id}/devices")
    List<AssignmentDto> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return assignments.list(org.adminOrOps().organizerId(), id).stream().map(AssignmentDto::of).toList();
    }

    @PutMapping("/org/events/{id}/devices")
    List<AssignmentDto> replace(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                                @RequestBody AssignmentsPut body) {
        return assignments.replace(org.adminOrOps().organizerId(), id, body.assignments()).stream()
                .map(AssignmentDto::of).toList();
    }
}
