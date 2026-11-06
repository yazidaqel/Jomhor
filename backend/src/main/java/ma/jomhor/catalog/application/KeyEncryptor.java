package ma.jomhor.catalog.application;

import java.util.UUID;

/**
 * Catalog-internal: encrypts event keys (K_e) at rest. Public only because its adapter lives in
 * catalog.adapters.crypto; no other module may use it (enforced by EventKeyTest). Other modules reach K_e
 * through {@link EventService#withEventKey}, which zeroes it afterwards.
 *
 * <p>A ciphertext is bound to its organizer and event (AAD): copied onto another event or organizer, it
 * doesn't decrypt. Lot 0 adapter: AES-256-GCM under EVENT_KEY_MASTER; a KMS adapter replaces it later.
 */
public interface KeyEncryptor {

    /** {@code eventKey} is 32 bytes; the result is what goes in events.event_key_enc. */
    byte[] encrypt(byte[] eventKey, UUID organizerId, int eventId);

    /** Throws if the ciphertext was tampered with or made for another organizer or event. */
    byte[] decrypt(byte[] encrypted, UUID organizerId, int eventId);
}
