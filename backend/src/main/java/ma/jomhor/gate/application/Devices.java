package ma.jomhor.gate.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.gate.domain.Device;

/** devices table. Every method takes the organizer except the two lookups that establish it (signature check, enrol). */
public interface Devices {

    /** Request authentication: the device id header names the device, and the device names its organizer. */
    Optional<Device> findForAuth(UUID id);

    /** Enrolment: the key is unique across organizers. */
    Optional<Device> findByPublicKey(byte[] publicKey);

    /** PENDING; empty when the key already exists (insert … on conflict do nothing). */
    Optional<Device> insert(UUID org, String label, byte[] publicKey);

    void touch(UUID id);

    /** PENDING first, then by label. */
    List<Device> list(UUID org);

    Optional<Device> find(UUID org, UUID id);

    /** PENDING → ACTIVE; false if the device is missing or not PENDING. */
    boolean approve(UUID org, UUID id);

    /** Any status but REVOKED → REVOKED; false if the device is missing or already revoked. */
    boolean revoke(UUID org, UUID id);
}
