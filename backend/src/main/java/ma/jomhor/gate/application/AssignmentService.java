package ma.jomhor.gate.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.VenueService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.gate.domain.Assignment;
import ma.jomhor.gate.domain.Device;
import ma.jomhor.tenancy.application.StaffService;
import ma.jomhor.tenancy.domain.StaffMember;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event device assignments (docs/04-api.md §Organizer GET/PUT /org/events/{id}/devices) and what a scanner learns
 * from them (§Gate GET /gate/events). {@code org} is OrgContext.organizerId() or the device's organizer; the event,
 * its venue and gates come from catalog's services, so another organizer's event is 404 there.
 */
@Service
public class AssignmentService {

    /** One PUT row; nulls are refused with the row's index. */
    public record Row(UUID deviceId, UUID gateId) {}

    /** A GET row: the assignment and its gate code. */
    public record Listed(Assignment assignment, String gateCode) {}

    /** One event of GET /gate/events. */
    public record GateEvent(Event event, String venueName, Venue.Gate gate, Instant manifestAvailableAt,
                            Integer manifestVersion) {}

    public record GateEvents(Device device, List<GateEvent> events, List<StaffMember> staff) {}

    private final Assignments assignments;
    private final Devices devices;
    private final EventService events;
    private final VenueService venues;
    private final StaffService staff;
    private final Duration manifestLead;

    public AssignmentService(Assignments assignments, Devices devices, EventService events, VenueService venues,
                             StaffService staff, @Value("${jomhor.gate.manifest-lead}") Duration manifestLead) {
        this.assignments = assignments;
        this.devices = devices;
        this.events = events;
        this.venues = venues;
        this.staff = staff;
        this.manifestLead = manifestLead;
    }

    /** By gate code, then label. */
    public List<Listed> list(UUID org, int eventId) {
        Map<UUID, Venue.Gate> gates = gates(org, events.get(org, eventId));
        return listed(org, eventId, gates);
    }

    /**
     * Replaces the event's set, under the event lock. Order: 404 event → 409 CLOSED → 422 per row (device ACTIVE and
     * this organizer's, or already assigned here and kept on the same gate; listed once; gate of the event's venue)
     * → 409 DEVICE_IN_USE for a removed device with scans on the event or a manifest downloaded.
     */
    @Transactional
    public List<Listed> replace(UUID org, int eventId, List<Row> rows) {
        events.get(org, eventId); // 404 before taking a lock
        assignments.lockEvent(org, eventId);
        Event e = events.get(org, eventId); // read under the lock: a close (same lock) has either committed or waits
        if (e.status() == Event.Status.CLOSED) {
            throw new ApiException("EVENT_NOT_EDITABLE", HttpStatus.CONFLICT, "Event can't be changed in its current status");
        }
        if (rows == null) {
            throw invalid(Map.of("assignments", "required"));
        }
        Map<UUID, Venue.Gate> gates = gates(org, e);
        Set<UUID> active = assignments.activeDevices(org,
                rows.stream().filter(r -> r != null && r.deviceId() != null).map(Row::deviceId).toList());
        // A device already on this event that stays on the same gate is kept even if no longer ACTIVE (04: a device
        // revoked mid-event stays listed); any other row needs an ACTIVE device.
        Map<UUID, Assignment> current = assignments.list(org, eventId).stream()
                .collect(Collectors.toMap(Assignment::deviceId, Function.identity()));
        Map<String, Object> errors = new LinkedHashMap<>();
        Map<UUID, UUID> gateByDevice = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String at = "assignments[" + i + "].";
            if (r == null || r.deviceId() == null) {
                errors.put(at + "device_id", "required");
            } else if (!active.contains(r.deviceId()) && !keptOnSameGate(current, r)) {
                errors.put(at + "device_id", "not an ACTIVE device of this organizer");
            } else if (gateByDevice.containsKey(r.deviceId())) {
                errors.put(at + "device_id", "listed more than once");
            }
            if (r != null && (r.gateId() == null || !gates.containsKey(r.gateId()))) {
                errors.put(at + "gate_id", r.gateId() == null ? "required" : "not a gate of the event's venue");
            }
            if (r != null && r.deviceId() != null) {
                gateByDevice.putIfAbsent(r.deviceId(), r.gateId());
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        // 04: a removed device is in use if it has scans here, or has downloaded the manifest (it may hold scans not
        // yet synced)
        Set<UUID> removed = new HashSet<>(current.keySet());
        removed.removeAll(gateByDevice.keySet());
        Set<UUID> inUse = new TreeSet<>(removed.isEmpty() ? Set.of() : assignments.withScans(org, eventId, removed));
        removed.stream().filter(id -> current.get(id).manifestVersion() != null).forEach(inUse::add);
        if (!inUse.isEmpty()) {
            throw new ApiException("DEVICE_IN_USE", HttpStatus.CONFLICT,
                    "A removed device has scans on this event or has downloaded its manifest",
                    Map.of("device_ids", List.copyOf(inUse)));
        }
        assignments.replace(org, eventId, gateByDevice);
        return listed(org, eventId, gates);
    }

    /**
     * GET /gate/events for an ACTIVE device: its assignments on ON_SALE/GATES_OPEN events, or CLOSED and not yet
     * acked, by starts_at; and its organizer's active GATE/SUPERVISOR staff with a PIN.
     */
    public GateEvents gateEvents(GateDevice d) {
        UUID org = d.organizerId();
        Device device = devices.find(org, d.id()).orElseThrow();
        List<GateEvent> list = assignments.ofDevice(org, d.id()).stream()
                .map(a -> gateEvent(org, a))
                .filter(g -> g != null)
                .sorted(Comparator.comparing((GateEvent g) -> g.event().startsAt()).thenComparing(g -> g.event().id()))
                .toList();
        return new GateEvents(device, list, staff.scannerStaff(org));
    }

    private GateEvent gateEvent(UUID org, Assignment.OfDevice a) {
        Event e = events.get(org, a.eventId());
        boolean shown = switch (e.status()) {
            case ON_SALE, GATES_OPEN -> true;
            case CLOSED -> a.closedAckAt() == null;
            case DRAFT -> false;
        };
        if (!shown) {
            return null;
        }
        Venue.Detail venue = venues.get(org, e.venueId());
        Venue.Gate gate = venue.gates().stream().filter(g -> g.id().equals(a.gateId())).findFirst().orElseThrow();
        return new GateEvent(e, venue.name(), gate, e.startsAt().minus(manifestLead), a.latestManifest());
    }

    private static boolean keptOnSameGate(Map<UUID, Assignment> current, Row r) {
        Assignment a = current.get(r.deviceId());
        return a != null && a.gateId().equals(r.gateId());
    }

    private List<Listed> listed(UUID org, int eventId, Map<UUID, Venue.Gate> gates) {
        return assignments.list(org, eventId).stream()
                .map(a -> new Listed(a, gates.get(a.gateId()).code()))
                .sorted(Comparator.comparing(Listed::gateCode).thenComparing(l -> l.assignment().label())
                        .thenComparing(l -> l.assignment().deviceId()))
                .toList();
    }

    private Map<UUID, Venue.Gate> gates(UUID org, Event e) {
        return venues.get(org, e.venueId()).gates().stream().collect(Collectors.toMap(Venue.Gate::id, Function.identity()));
    }

    private static ApiException invalid(Map<String, Object> fields) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", fields));
    }
}
