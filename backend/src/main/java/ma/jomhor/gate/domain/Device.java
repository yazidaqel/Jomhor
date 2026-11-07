package ma.jomhor.gate.domain;

import java.time.Instant;
import java.util.UUID;

/** A scanner (docs/02 §Devices): enrolled PENDING, approved ACTIVE by an admin, REVOKED for good. */
public record Device(UUID id, UUID organizerId, String label, Status status, byte[] publicKey, Instant enrolledAt,
                     Instant approvedAt, Instant lastSeenAt) {

    public enum Status { PENDING, ACTIVE, REVOKED }

    public String fingerprint() {
        return DeviceSignature.fingerprint(publicKey);
    }
}
