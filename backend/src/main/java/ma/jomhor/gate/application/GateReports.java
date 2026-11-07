package ma.jomhor.gate.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.common.Csv;
import ma.jomhor.catalog.application.VenueService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.gate.application.Scans.Admitted;
import ma.jomhor.gate.application.Scans.Other;
import ma.jomhor.gate.domain.Assignment;
import ma.jomhor.gate.domain.Device;
import ma.jomhor.gate.domain.Scan;
import ma.jomhor.tickets.application.TicketService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /org/events/{id}/live, /reconciliation and /reconciliation.csv (docs/04-api.md §Gate reports; plan lot-2 Q32,
 * Q34, Q37). Computed on every read, never stored: the canonical scan and DUPLICATE_LATE are derived (scans are
 * append-only), so a late sync corrects the figures. Each read is one REPEATABLE READ snapshot with one now(), so the
 * counts, the rate windows and the online/stale thresholds agree. The records below are the response shapes.
 */
@Service
public class GateReports {

    static final int OVERRIDE_CAP_PER_DEVICE = 20;
    static final Duration ONLINE_WITHIN = Duration.ofMinutes(2);
    static final Duration STALE_AFTER = Duration.ofMinutes(5);
    static final String CSV_HEADER = "kind;code;nom;vendus;entres;absents;refuses;doublons_tardifs;derogations";

    // ---------- /live ----------

    public record Live(int eventId, Event.Status status, Instant startsAt, Instant serverTime, long sold, long entered,
                       long ratePerMin, long ratePerMinPrev, Refused refused, long duplicatesLate, Overrides overrides,
                       List<LiveGate> gates, List<StaleDevice> devicesStale) {}

    public record Refused(long total, Map<Scan.Result, Long> byResult) {}

    public record Overrides(long total, int capPerDevice, List<DeviceOverrides> byDevice) {}

    public record DeviceOverrides(UUID deviceId, String label, long count) {}

    public record LiveGate(UUID gateId, String code, List<String> zoneCodes, long devicesTotal, long devicesOnline,
                           long entered, long refused, long overrides, long ratePerMin, Instant lastSyncAt) {}

    public record StaleDevice(UUID deviceId, String label, UUID gateId, String gateCode, Instant lastSeenAt) {}

    // ---------- /reconciliation ----------

    public record Reconciliation(int eventId, Event.Status status, Instant generatedAt,
                                 @JsonProperty("final") boolean isFinal, DeviceSync devices, Totals totals,
                                 List<ZoneRow> zones, List<GateRow> gates) {}

    public record DeviceSync(long assigned, long acked, long revoked, List<Unsynced> unsynced) {}

    public record Unsynced(UUID deviceId, String label, String gateCode, Device.Status status, Instant lastSeenAt) {}

    public record Totals(long sold, long entered, long noShow, Map<Scan.Result, Long> refusedByResult,
                         long duplicatesLate, Map<Scan.OverrideReason, Long> overridesByReason) {}

    public record ZoneRow(int zoneId, String code, String name, long sold, long entered, long noShow, long overrides) {}

    public record GateRow(UUID gateId, String code, String name, List<String> zoneCodes, long entered,
                          Map<Scan.Result, Long> refusedByResult, long duplicatesLate, long overrides) {}

    /** Everything a report reads, in one snapshot. */
    private record Snapshot(Event event, Instant now, Map<UUID, Venue.Gate> gates, Map<Integer, String> zoneCodes,
                            List<Assignment> assignments, List<Admitted> admitted, List<Other> others,
                            Map<Integer, Long> soldByZone) {

        /** 04: gates with an assigned device or a scan, by code. */
        List<Venue.Gate> shownGates() {
            Set<UUID> ids = new HashSet<>();
            assignments.forEach(a -> ids.add(a.gateId()));
            admitted.forEach(a -> ids.add(a.gateId()));
            others.forEach(o -> ids.add(o.gateId()));
            return ids.stream().map(gates::get).filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Venue.Gate::code)).toList();
        }

        /** The gate's zone codes, by zone id (as its zone_ids). */
        List<String> zoneCodes(Venue.Gate g) {
            return g.zoneIds().stream().map(zoneCodes::get).toList();
        }

        String gateCode(UUID gateId) {
            Venue.Gate g = gates.get(gateId);
            return g == null ? null : g.code();
        }

        /** By gate code, then label. */
        Stream<Assignment> devices(Predicate<Assignment> keep) {
            return assignments.stream().filter(keep)
                    .sorted(Comparator.comparing((Assignment a) -> Objects.toString(gateCode(a.gateId()), ""))
                            .thenComparing(Assignment::label));
        }
    }

    private final Scans scans;
    private final Assignments assignments;
    private final EventService events;
    private final VenueService venues;
    private final TicketService tickets;

    GateReports(Scans scans, Assignments assignments, EventService events, VenueService venues, TicketService tickets) {
        this.scans = scans;
        this.assignments = assignments;
        this.events = events;
        this.venues = venues;
        this.tickets = tickets;
    }

    /** Unknown event or another organizer's → 404 (EventService.get). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Live live(UUID org, int eventId) {
        Snapshot s = snapshot(org, eventId);
        Instant online = s.now().minus(ONLINE_WITHIN);
        Instant stale = s.now().minus(STALE_AFTER);
        Map<UUID, String> labels = s.assignments().stream()
                .collect(Collectors.toMap(Assignment::deviceId, Assignment::label));
        List<Other> overrides = s.others().stream().filter(GateReports::isOverride).toList();
        List<DeviceOverrides> byDevice = overrides.stream()
                .collect(Collectors.groupingBy(Other::deviceId, Collectors.summingLong(Other::count)))
                .entrySet().stream()
                .map(e -> new DeviceOverrides(e.getKey(), labels.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparingLong(DeviceOverrides::count).reversed()
                        .thenComparing(d -> Objects.toString(d.label(), "")))
                .toList();
        List<LiveGate> gates = s.shownGates().stream().map(g -> {
            List<Assignment> devices = s.assignments().stream().filter(a -> a.gateId().equals(g.id())).toList();
            // 04: devices_total / devices_online count ACTIVE devices; last_sync_at reads every assigned one
            List<Assignment> active = devices.stream().filter(GateReports::isActive).toList();
            List<Admitted> admitted = atGate(s.admitted(), Admitted::gateId, g.id());
            List<Other> others = atGate(s.others(), Other::gateId, g.id());
            return new LiveGate(g.id(), g.code(), s.zoneCodes(g), active.size(),
                    active.stream().filter(a -> a.lastSeenAt() != null && !a.lastSeenAt().isBefore(online)).count(),
                    sum(admitted, Admitted::canonical), sum(others, o -> isOverride(o) ? 0 : o.count()),
                    sum(others, o -> isOverride(o) ? o.count() : 0), sum(admitted, Admitted::last5) / 5,
                    devices.stream().map(Assignment::lastSeenAt).filter(Objects::nonNull).max(Comparator.naturalOrder())
                            .orElse(null));
        }).toList();
        // 04: assigned, ACTIVE, not yet acked the close, and silent for more than 5 min or never seen
        List<StaleDevice> devicesStale = s.devices(a -> isActive(a) && a.closedAckAt() == null
                        && (a.lastSeenAt() == null || a.lastSeenAt().isBefore(stale)))
                .map(a -> new StaleDevice(a.deviceId(), a.label(), a.gateId(), s.gateCode(a.gateId()), a.lastSeenAt()))
                .toList();
        Map<Scan.Result, Long> refused = refusedByResult(s.others());
        Event e = s.event();
        return new Live(e.id(), e.status(), e.startsAt(), s.now(), total(s.soldByZone()),
                sum(s.admitted(), Admitted::canonical), sum(s.admitted(), Admitted::last5) / 5,
                sum(s.admitted(), Admitted::prev5) / 5, new Refused(total(refused), refused),
                sum(s.admitted(), a -> a.admitted() - a.canonical()),
                new Overrides(sum(overrides, Other::count), OVERRIDE_CAP_PER_DEVICE, byDevice), gates, devicesStale);
    }

    /**
     * Here entered counts VALID tickets only, so entered + no_show = sold per zone and in total; an override of a
     * non-VALID ticket counts in overrides only. final = CLOSED and every assigned ACTIVE device acked the close: a revoked
     * device can never ack (its unsynced scans are lost, plan Q41), so it stays in unsynced without blocking final.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Reconciliation reconciliation(UUID org, int eventId) {
        Snapshot s = snapshot(org, eventId);
        List<Admitted> valid = s.admitted().stream().filter(Admitted::valid).toList();
        List<ZoneRow> zones = events.pricedZones(org, eventId).stream().map(z -> {
            long sold = s.soldByZone().getOrDefault(z.zoneId(), 0L);
            long entered = sum(valid, a -> a.zoneId() == z.zoneId() ? a.canonical() : 0);
            return new ZoneRow(z.zoneId(), z.code(), z.name(), sold, entered, sold - entered,
                    sum(s.others(), o -> isOverride(o) && Objects.equals(o.zoneId(), z.zoneId()) ? o.count() : 0));
        }).toList();
        List<GateRow> gates = s.shownGates().stream().map(g -> {
            List<Admitted> admitted = atGate(s.admitted(), Admitted::gateId, g.id());
            List<Other> others = atGate(s.others(), Other::gateId, g.id());
            return new GateRow(g.id(), g.code(), g.name(), s.zoneCodes(g),
                    sum(admitted, a -> a.valid() ? a.canonical() : 0), refusedByResult(others),
                    sum(admitted, a -> a.admitted() - a.canonical()), sum(others, o -> isOverride(o) ? o.count() : 0));
        }).toList();
        Map<Scan.OverrideReason, Long> byReason = new EnumMap<>(Scan.OverrideReason.class);
        s.others().stream().filter(GateReports::isOverride)
                .forEach(o -> byReason.merge(o.reason(), o.count(), Long::sum));
        long sold = total(s.soldByZone());
        long entered = sum(valid, Admitted::canonical);
        Totals totals = new Totals(sold, entered, sold - entered, refusedByResult(s.others()),
                sum(s.admitted(), a -> a.admitted() - a.canonical()), byReason);
        List<Unsynced> unsynced = s.devices(a -> a.closedAckAt() == null)
                .map(a -> new Unsynced(a.deviceId(), a.label(), s.gateCode(a.gateId()), a.status(), a.lastSeenAt()))
                .toList();
        long assigned = s.assignments().size();
        long acked = assigned - unsynced.size();
        long revoked = s.assignments().stream().filter(a -> !isActive(a)).count();
        Event e = s.event();
        return new Reconciliation(e.id(), e.status(), s.now(),
                e.status() == Event.Status.CLOSED && unsynced.stream().noneMatch(u -> u.status() == Device.Status.ACTIVE),
                new DeviceSync(assigned, acked, revoked, unsynced), totals, zones, gates);
    }

    /**
     * 04 CSV: {@code ;}-separated, CRLF, FR headers; one row per zone, then per gate, then the total. Gate rows leave
     * vendus/absents empty; zone rows leave refuses/doublons_tardifs empty (the zone rows of the JSON have neither).
     * The caller adds the UTF-8 BOM.
     */
    public static String csv(Reconciliation r) {
        StringBuilder out = new StringBuilder(CSV_HEADER).append("\r\n");
        r.zones().forEach(z -> row(out, "zone", z.code(), z.name(), z.sold(), z.entered(), z.noShow(), null, null,
                z.overrides()));
        r.gates().forEach(g -> row(out, "gate", g.code(), g.name(), null, g.entered(), null,
                total(g.refusedByResult()), g.duplicatesLate(), g.overrides()));
        Totals t = r.totals();
        row(out, "total", "", "", t.sold(), t.entered(), t.noShow(), total(t.refusedByResult()), t.duplicatesLate(),
                total(t.overridesByReason()));
        return out.toString();
    }

    // ---------- helpers ----------

    private Snapshot snapshot(UUID org, int eventId) {
        Event e = events.get(org, eventId);
        Venue.Detail venue = venues.get(org, e.venueId());
        Map<UUID, Venue.Gate> gates = venue.gates().stream().collect(Collectors.toMap(Venue.Gate::id, g -> g));
        Map<Integer, String> zoneCodes = new HashMap<>();
        venue.zones().forEach(z -> zoneCodes.put(z.id(), z.code()));
        return new Snapshot(e, scans.now().truncatedTo(ChronoUnit.MILLIS), gates, zoneCodes,
                assignments.list(org, eventId), scans.admitted(org, eventId), scans.others(org, eventId),
                tickets.soldByZone(org, eventId));
    }

    private static boolean isActive(Assignment a) {
        return a.status() == Device.Status.ACTIVE;
    }

    private static boolean isOverride(Other o) {
        return o.result() == Scan.Result.OVERRIDE;
    }

    /** Refused codes (never OK/OVERRIDE) with a count > 0, in the enum's order. */
    private static Map<Scan.Result, Long> refusedByResult(List<Other> others) {
        Map<Scan.Result, Long> m = new EnumMap<>(Scan.Result.class);
        others.stream().filter(o -> !isOverride(o)).forEach(o -> m.merge(o.result(), o.count(), Long::sum));
        return m;
    }

    private static <T> List<T> atGate(List<T> rows, Function<T, UUID> gate, UUID gateId) {
        return rows.stream().filter(r -> gate.apply(r).equals(gateId)).toList();
    }

    private static <T> long sum(List<T> rows, ToLongFunction<T> f) {
        return rows.stream().mapToLong(f).sum();
    }

    private static long total(Map<?, Long> counts) {
        return counts.values().stream().mapToLong(n -> n).sum();
    }

    private static void row(StringBuilder out, String kind, String code, String name, Object... counts) {
        out.append(kind).append(';').append(Csv.text(code)).append(';').append(Csv.text(name));
        for (Object n : counts) {
            out.append(';').append(n == null ? "" : n);
        }
        out.append("\r\n");
    }

}
