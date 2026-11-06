package ma.jomhor.catalog.adapters.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.catalog.application.KeyEncryptor;
import ma.jomhor.common.Secrets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM under the master key from env EVENT_KEY_MASTER (base64, 32 bytes; startup fails otherwise).
 * Layout: nonce(12, random) | ciphertext(32) | tag(16) = 60 bytes. AAD = organizer_id(16, big-endian msb|lsb)
 * | event_id(4, BE). Neither key is ever logged; exceptions never carry key material.
 */
@Component
class AesGcmKeyEncryptor implements KeyEncryptor {

    static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey master;
    private final SecureRandom random = new SecureRandom();

    AesGcmKeyEncryptor(@Value("${jomhor.event-key-master}") String masterBase64) {
        byte[] bytes = Secrets.base64Of32Bytes("EVENT_KEY_MASTER", masterBase64);
        this.master = new SecretKeySpec(bytes, "AES");
        Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public byte[] encrypt(byte[] eventKey, UUID organizerId, int eventId) {
        if (eventKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("event key must be 32 bytes");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] sealed = cipher(Cipher.ENCRYPT_MODE, nonce, aad(organizerId, eventId), eventKey, 0, eventKey.length);
        return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    }

    @Override
    public byte[] decrypt(byte[] encrypted, UUID organizerId, int eventId) {
        if (encrypted.length != NONCE_BYTES + KEY_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("event key ciphertext has the wrong length");
        }
        byte[] nonce = Arrays.copyOf(encrypted, NONCE_BYTES);
        return cipher(Cipher.DECRYPT_MODE, nonce, aad(organizerId, eventId), encrypted, NONCE_BYTES, encrypted.length - NONCE_BYTES);
    }

    private static byte[] aad(UUID organizerId, int eventId) {
        return ByteBuffer.allocate(20)
                .putLong(organizerId.getMostSignificantBits()).putLong(organizerId.getLeastSignificantBits())
                .putInt(eventId).array();
    }

    private byte[] cipher(int mode, byte[] nonce, byte[] aad, byte[] in, int offset, int length) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, master, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad);
            return c.doFinal(in, offset, length);
        } catch (GeneralSecurityException e) {
            // No cause: on decrypt a wrong tag means tampering or another organizer's or event's row, nothing to show.
            throw new IllegalStateException("event key " + (mode == Cipher.ENCRYPT_MODE ? "encryption" : "decryption")
                    + " failed");
        }
    }
}
