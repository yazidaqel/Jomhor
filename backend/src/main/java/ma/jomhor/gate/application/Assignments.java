package ma.jomhor.gate.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.gate.domain.Assignment;

/** device_assignments (with the devices, scans and manifests reads they need). Every method takes the organizer. */
public interface Assignments {

    /** The gate event lock (common EventLock), exclusive: assignment PUTs, scan ingestion and close. */
    void lockEvent(UUID org, int eventId);

    /** The same lock in shared mode (manifest downloads): they run together, never beside a PUT, ingestion or close. */
    void lockEventShared(UUID org, int eventId);

    List<Assignment> list(UUID org, int eventId);

    /** The ids among {@code deviceIds} of this organizer's ACTIVE devices. */
    Set<UUID> activeDevices(UUID org, Collection<UUID> deviceIds);

    /** The ids among {@code deviceIds} with at least one scan on the event. */
    Set<UUID> withScans(UUID org, int eventId, Collection<UUID> deviceIds);

    /**
     * device → gate becomes the event's whole set: other rows deleted, new ones inserted, kept devices moved to their
     * gate with their manifest_version and closed_ack_at unchanged.
     */
    void replace(UUID org, int eventId, Map<UUID, UUID> gateByDevice);

    List<Assignment.OfDevice> ofDevice(UUID org, UUID deviceId);

    /** closed_ack_at = now() unless already set (04 POST /gate/events/{id}/closed-ack is idempotent). */
    void ackClosed(UUID org, UUID deviceId, int eventId);
}
