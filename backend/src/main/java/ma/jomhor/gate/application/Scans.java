package ma.jomhor.gate.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.gate.domain.Scan;

/** scans table: insert and read only, never UPDATE or DELETE (append-only). Every method takes the organizer. */
public interface Scans {

    /** One statement, {@code insert ... on conflict (scan_id) do nothing}; returns the rows actually inserted. */
    int insert(UUID org, int eventId, UUID deviceId, List<Scan> rows);

    /** Whether the event has an admitted (OK/OVERRIDE) scan of the ticket, so a canonical one. */
    boolean admitted(UUID org, int eventId, UUID ticketId);

    /** Admitted (OK/OVERRIDE) scans of the event by devices other than {@code deviceId} with seq > cursor, by seq. */
    List<Scan.Used> usedSince(UUID org, int eventId, UUID deviceId, long cursor, int limit);

    // ---------- gate reports (docs/04-api.md §Gate reports), read in the caller's snapshot ----------

    /**
     * Admitted (OK/OVERRIDE) scans of one gate on tickets of one zone, VALID or not: how many, how many are canonical
     * (per ticket and direction, the earliest device_time, tie lowest scan_id), and how many have a device_time in
     * the last 5 min ({@code last5}) or the 5 min before ({@code prev5}) of now().
     */
    record Admitted(UUID gateId, int zoneId, boolean valid, long admitted, long canonical, long last5, long prev5) {}

    List<Admitted> admitted(UUID org, int eventId);

    /** Every other row (refused results and overrides), counted per gate, device, ticket zone (null without a ticket), result and reason. */
    record Other(UUID gateId, UUID deviceId, Integer zoneId, Scan.Result result, Scan.OverrideReason reason, long count) {}

    List<Other> others(UUID org, int eventId);

    /** The database clock, now() of the caller's transaction: the instant both lists above are measured from. */
    Instant now();
}
