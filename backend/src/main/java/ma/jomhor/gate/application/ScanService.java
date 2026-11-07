package ma.jomhor.gate.application;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.VenueService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.gate.domain.Scan;
import ma.jomhor.tenancy.application.StaffService;
import ma.jomhor.tenancy.domain.StaffMember;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tickets.application.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Scan sync (docs/04-api.md §Gate POST /gate/events/{id}/scans + sync rules, POST /gate/events/{id}/closed-ack).
 * A batch is one transaction under the gate event lock (exclusive), so seq values commit in order and a used_since
 * cursor never skips a row. That also needs scans.seq's identity sequence at CACHE 1 (the default, as V11 creates
 * it): with a cache, each session would draw from its own block and seq would stop following commit order. Each row
 * is checked on its own: a failing row is acked, listed in errors and audited, never stored, and never fails the
 * batch.
 *
 * <p>{@code acked} and {@code errors[].scan_id} echo each row's {@code scan_id} string as sent, even when it is not
 * a UUIDv7 (it is then refused on scan_id). A row whose scan_id is missing or not a JSON string cannot be acked: it
 * is left out of {@code acked} and its error has {@code scan_id: null}.
 */
@Service
public class ScanService {

    static final int MAX_ROWS = 500;
    static final int USED_SINCE_MAX = 5000;

    public record RowError(String scanId, String field) {}

    public record Synced(List<String> acked, List<RowError> errors, List<Scan.Used> usedSince, long cursor,
                         boolean more, Integer manifestVersion, Event.Status eventStatus) {}

    /** What a batch is checked against: read once per batch. */
    private record Known(Set<UUID> tickets, Set<UUID> gates, Map<UUID, StaffRole> staff) {}

    /** A row either checked ({@code scan}) or refused on {@code field}. */
    private record Checked(Scan scan, String field) {
        static Checked refused(String field) {
            return new Checked(null, field);
        }
    }

    private final Scans scans;
    private final Assignments assignments;
    private final Manifests manifests;
    private final EventService events;
    private final VenueService venues;
    private final TicketService tickets;
    private final StaffService staff;
    private final AuditLog audit;

    public ScanService(Scans scans, Assignments assignments, Manifests manifests, EventService events,
                       VenueService venues, TicketService tickets, StaffService staff, AuditLog audit) {
        this.scans = scans;
        this.assignments = assignments;
        this.manifests = manifests;
        this.events = events;
        this.venues = venues;
        this.tickets = tickets;
        this.staff = staff;
        this.audit = audit;
    }

    /**
     * Order: 422 on the batch (cursor, scans, more than 500 rows) → 404 event of another organizer → event lock →
     * 404 device not assigned → rows. Any event status, CLOSED included.
     */
    @Transactional
    public Synced sync(GateDevice device, int eventId, Long cursor, List<JsonNode> rows) {
        if (cursor != null && cursor < 0) {
            throw invalid("cursor", "must be >= 0");
        }
        if (rows == null) {
            throw invalid("scans", "required");
        }
        if (rows.size() > MAX_ROWS) {
            throw invalid("scans", "at most " + MAX_ROWS + " rows");
        }
        UUID org = device.organizerId();
        // Before the lock (404 for another organizer's event, and the row checks): tickets and staff rows are never
        // deleted and a venue is fixed per event; a gate with scans or assignments can't be deleted (GATE_IN_USE).
        Event before = events.get(org, eventId);
        List<Checked> checked = check(rows, known(org, before, rows));

        assignments.lockEvent(org, eventId);
        if (!manifests.assigned(org, device.id(), eventId)) {
            throw ApiException.notFound("Event not found");
        }
        Event e = events.get(org, eventId); // under the lock: a close has committed or waits

        List<String> acked = new ArrayList<>(rows.size());
        List<RowError> errors = new ArrayList<>();
        List<AuditLog.Entry> refused = new ArrayList<>();
        List<Scan> valid = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            String raw = text(rows.get(i), "scan_id");
            if (raw != null) {
                acked.add(raw);
            }
            Checked c = checked.get(i);
            if (c.scan() != null) {
                valid.add(c.scan());
            } else {
                errors.add(new RowError(raw, c.field()));
                UUID id = uuid(raw);
                refused.add(new AuditLog.Entry(id == null ? null : id.toString(),
                        "{\"event_id\": %d, \"field\": \"%s\"}".formatted(eventId, c.field())));
            }
        }
        scans.insert(org, eventId, device.id(), valid);
        audit.device(org, device.id(), "scan.refused", refused);

        long from = cursor == null ? 0 : cursor;
        List<Scan.Used> used = scans.usedSince(org, eventId, device.id(), from, USED_SINCE_MAX + 1);
        boolean more = used.size() > USED_SINCE_MAX;
        if (more) {
            used = used.subList(0, USED_SINCE_MAX);
        }
        long next = used.isEmpty() ? from : used.getLast().seq();
        Integer manifestVersion = manifests.latest(org, eventId).map(Manifests.Latest::version).orElse(null);
        return new Synced(acked, errors, used, next, more, manifestVersion, e.status());
    }

    /** 404 not assigned (or another organizer's event) → 409 EVENT_NOT_CLOSED → closed_ack_at set once. */
    @Transactional
    public void closedAck(GateDevice device, int eventId) {
        UUID org = device.organizerId();
        if (!manifests.assigned(org, device.id(), eventId)) {
            throw ApiException.notFound("Event not found");
        }
        if (events.get(org, eventId).status() != Event.Status.CLOSED) {
            throw new ApiException("EVENT_NOT_CLOSED", HttpStatus.CONFLICT, "Event is not closed");
        }
        assignments.ackClosed(org, device.id(), eventId);
    }

    // ---------- row checks (04 §Gate sync rules, in that order) ----------

    private Known known(UUID org, Event e, List<JsonNode> rows) {
        Set<UUID> ticketIds = rows.stream().map(r -> uuid(text(r, "ticket_id"))).filter(id -> id != null)
                .collect(Collectors.toSet());
        Venue.Detail venue = venues.get(org, e.venueId());
        // Any staff row of the organizer, active or not: a device offline since a deactivation still logs its scans.
        return new Known(tickets.ofEvent(org, e.id(), ticketIds),
                venue.gates().stream().map(Venue.Gate::id).collect(Collectors.toSet()),
                staff.list(org).stream().collect(Collectors.toMap(StaffMember::id, StaffMember::role)));
    }

    private static List<Checked> check(List<JsonNode> rows, Known k) {
        return rows.stream().map(r -> check(r, k)).toList();
    }

    /** The first failing field, or the row. */
    private static Checked check(JsonNode row, Known k) {
        UUID scanId = uuid(text(row, "scan_id"));
        if (scanId == null || scanId.version() != 7 || scanId.variant() != 2) {
            return Checked.refused("scan_id");
        }
        Scan.Result result = parse(Scan.Result.class, text(row, "result"));
        if (result == null) {
            return Checked.refused("result");
        }
        if (!"IN".equals(text(row, "direction"))) {
            return Checked.refused("direction");
        }
        UUID ticketId = uuid(text(row, "ticket_id"));
        if (result.authenticated ? ticketId == null || !k.tickets().contains(ticketId) : !absent(row, "ticket_id")) {
            return Checked.refused("ticket_id");
        }
        UUID gateId = uuid(text(row, "gate_id"));
        if (gateId == null || !k.gates().contains(gateId)) {
            return Checked.refused("gate_id");
        }
        UUID staffId = uuid(text(row, "staff_id"));
        if (staffId == null || !k.staff().containsKey(staffId)) {
            return Checked.refused("staff_id");
        }
        boolean override = result == Scan.Result.OVERRIDE;
        Scan.OverrideReason reason = parse(Scan.OverrideReason.class, text(row, "override_reason"));
        if (override ? reason == null : !absent(row, "override_reason")) {
            return Checked.refused("override_reason");
        }
        UUID supervisorId = uuid(text(row, "supervisor_id"));
        if (override ? k.staff().get(supervisorId) != StaffRole.SUPERVISOR : !absent(row, "supervisor_id")) {
            return Checked.refused("supervisor_id");
        }
        JsonNode tv = row.get("token_version");
        boolean tvAbsent = tv == null || tv.isNull();
        if (!tvAbsent && !(tv.isIntegralNumber() && tv.canConvertToInt() && tv.asInt() >= 0
                && tv.asInt() <= Short.MAX_VALUE)) {
            return Checked.refused("token_version");
        }
        Instant deviceTime = instant(text(row, "device_time"));
        if (deviceTime == null) {
            return Checked.refused("device_time");
        }
        return new Checked(new Scan(scanId, ticketId, gateId, staffId, Scan.Direction.IN, result,
                tvAbsent ? null : tv.asInt(), deviceTime, reason, override ? supervisorId : null), null);
    }

    private static String text(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static boolean absent(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v == null || v.isNull();
    }

    /** Canonical 36-char form only (UUID.fromString also takes "1-2-3-4-5"). */
    private static UUID uuid(String s) {
        if (s == null || s.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String s) {
        return s == null ? null : Arrays.stream(type.getEnumConstants()).filter(c -> c.name().equals(s))
                .findFirst().orElse(null);
    }

    /** RFC 3339 with Z or an offset. */
    private static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }
}
