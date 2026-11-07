package ma.jomhor.gate.adapters.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.HolderNames;
import ma.jomhor.gate.application.AssignmentService;
import ma.jomhor.gate.application.AssignmentService.GateEvent;
import ma.jomhor.gate.application.GateDevice;
import ma.jomhor.gate.domain.Device;
import ma.jomhor.tenancy.domain.StaffMember;
import ma.jomhor.tenancy.domain.StaffRole;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /gate/events (docs/04-api.md §Gate): the device's events and its organizer's scanner staff with their PIN
 * verifiers (§Gate PINs). Staff appear by {@code holder_label} only, never by full name.
 */
@RestController
class GateEventController {

    record DeviceRef(UUID id, String label, Device.Status status) {}

    record VenueRef(String name) {}

    record GateRef(UUID id, String code, String name) {}

    record EventDto(int id, String title, Event.Type eventType, Event.Status status, Instant startsAt, String timezone,
                    VenueRef venue, GateRef gate, Event.ReentryPolicy reentryPolicy, Instant manifestAvailableAt,
                    Integer manifestVersion) {
        static EventDto of(GateEvent g) {
            Event e = g.event();
            return new EventDto(e.id(), e.title(), e.eventType(), e.status(), e.startsAt(), e.timezone(),
                    new VenueRef(g.venueName()), new GateRef(g.gate().id(), g.gate().code(), g.gate().name()),
                    e.reentryPolicy(), g.manifestAvailableAt(), g.manifestVersion());
        }
    }

    record StaffDto(UUID staffId, String label, StaffRole role, String pinVerifier) {

        /**
         * holder_label of each name; when several share a label, the later ones (the list is in name order) get
         * " \u00b7 2", " \u00b7 3", ... and the first keeps the plain label (04 GET /gate/events).
         */
        static List<StaffDto> of(List<StaffMember> staff) {
            Map<String, Integer> seen = new HashMap<>();
            return staff.stream().map(s -> {
                String label = HolderNames.label(s.name());
                int n = seen.merge(label, 1, Integer::sum);
                return new StaffDto(s.id(), n == 1 ? label : label + " \u00b7 " + n, s.role(), s.pinVerifier());
            }).toList();
        }
    }

    record GateEventsDto(Instant serverTime, DeviceRef device, List<EventDto> events, List<StaffDto> staff) {}

    private final AssignmentService assignments;

    GateEventController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/gate/events")
    ResponseEntity<GateEventsDto> events(@RequestAttribute(GateDevice.ATTRIBUTE) GateDevice device) {
        var g = assignments.gateEvents(device);
        Device d = g.device();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new GateEventsDto(Instant.now().truncatedTo(ChronoUnit.MILLIS),
                        new DeviceRef(d.id(), d.label(), d.status()),
                        g.events().stream().map(EventDto::of).toList(),
                        StaffDto.of(g.staff())));
    }
}
