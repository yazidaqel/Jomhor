package ma.jomhor.gate.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A device assigned to an event at a starting gate (device_assignments), with the device fields B2 shows.
 * {@code manifestVersion} is the last version this device downloaded.
 */
public record Assignment(UUID deviceId, String label, Device.Status status, UUID gateId, Integer manifestVersion,
                         Instant lastSeenAt, Instant closedAckAt) {

    /** One assignment seen from the device (GET /gate/events): {@code latestManifest} = latest version built. */
    public record OfDevice(int eventId, UUID gateId, Instant closedAckAt, Integer latestManifest) {}
}
