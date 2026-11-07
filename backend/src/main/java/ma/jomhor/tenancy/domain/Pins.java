package ma.jomhor.tenancy.domain;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * docs/04-api.md §Gate PINs: a 4-digit PIN drawn by the server and its verifier
 * {@code pbkdf2-sha256$10000$<salt>$<hash>} (PBKDF2-HMAC-SHA256 of the UTF-8 PIN, 16-byte salt, 32 bytes, base64url
 * without padding). Scanners check PINs offline against the verifier: it is a UI lock, not a secret (plan lot-2 Q31).
 * A PIN is never logged or stored in clear.
 */
public final class Pins {

    public static final int ITERATIONS = 10_000;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private Pins() {}

    /** 0000–9999, uniform. */
    public static String newPin() {
        return "%04d".formatted(RANDOM.nextInt(10_000));
    }

    /** With a fresh random salt. */
    public static String verifier(String pin) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return verifier(pin, salt);
    }

    /** With a given salt: deterministic, for docs/gate-vectors.json. */
    public static String verifier(String pin, byte[] salt) {
        return PREFIX + "$" + ITERATIONS + "$" + B64.encodeToString(salt) + "$"
                + B64.encodeToString(pbkdf2(pin, salt, ITERATIONS));
    }

    /** Constant-time compare of the recomputed hash; false for any malformed verifier. */
    public static boolean matches(String pin, String verifier) {
        String[] p = verifier == null ? new String[0] : verifier.split("\\$", -1);
        if (pin == null || p.length != 4 || !p[0].equals(PREFIX) || !p[1].equals(String.valueOf(ITERATIONS))) {
            return false;
        }
        try {
            byte[] salt = Base64.getUrlDecoder().decode(p[2]);
            byte[] hash = Base64.getUrlDecoder().decode(p[3]);
            return MessageDigest.isEqual(pbkdf2(pin, salt, ITERATIONS), hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String pin, byte[] salt, int iterations) {
        // SunJCE's PBKDF2 encodes the password chars as UTF-8, as 04 asks (GateVectorsTest checks it against Python).
        var spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
