package ma.jomhor.gate.application;

import java.util.List;
import java.util.UUID;
import ma.jomhor.gate.domain.Scan;

/** scans table: insert and read only, never UPDATE or DELETE (append-only). Every method takes the organizer. */
public interface Scans {

    /** One statement, {@code insert ... on conflict (scan_id) do nothing}; returns the rows actually inserted. */
    int insert(UUID org, int eventId, UUID deviceId, List<Scan> rows);

    /** Admitted (OK/OVERRIDE) scans of the event by devices other than {@code deviceId} with seq > cursor, by seq. */
    List<Scan.Used> usedSince(UUID org, int eventId, UUID deviceId, long cursor, int limit);
}
