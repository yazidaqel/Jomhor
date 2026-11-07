package ma.jomhor.gate.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.gate.domain.ManifestPayload;

/** manifests table, plus the device_assignments reads and writes of a manifest download. Every method takes the organizer. */
public interface Manifests {

    /** The newest version of the event: number and build time. */
    record Latest(int version, Instant builtAt) {}

    /** A stored version: its full payload as stored (gzip), signature and row count. */
    record Stored(int version, byte[] body, byte[] signature, int rowCount) {

        /** The full payload text: inflates {@code body}, so call it outside a transaction where it matters. */
        public String payload() {
            return ManifestPayload.gunzip(body);
        }
    }

    /**
     * Manifest build lock of one event until the transaction ends; false (without waiting) when another transaction
     * (another app instance) holds it.
     */
    boolean tryLockBuild(UUID org, int eventId);

    Optional<Latest> latest(UUID org, int eventId);

    Optional<Stored> find(UUID org, int eventId, int version);

    /** False when the version already exists (a parallel build won). */
    boolean insert(UUID org, int eventId, int version, Instant builtAt, String payload, byte[] signature, int rowCount);

    /** The database clock. */
    Instant now();

    /** The device's assignment on the event exists (else the caller answers 404). */
    boolean assigned(UUID org, UUID deviceId, int eventId);

    /** device_assignments.manifest_version = {@code version} (04: set on every full or delta download). */
    void recordDownload(UUID org, UUID deviceId, int eventId, int version);
}
