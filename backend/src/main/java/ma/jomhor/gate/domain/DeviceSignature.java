package ma.jomhor.gate.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.AlgorithmParameters;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * docs/04-api.md §Gate auth: the string a scanner signs, the ECDSA P-256 / SHA-256 check and the key fingerprint.
 * Pure functions (JDK crypto only), shared by the request filter and GateVectorsTest.
 */
public final class DeviceSignature {

    public static final String PREFIX = "JMH-GATE-1";
    /** |X-Device-Time − server time| ≤ 5 min. */
    public static final long WINDOW_MS = 5 * 60 * 1000;

    private static final HexFormat HEX = HexFormat.of();
    private static final ECParameterSpec P256 = p256Params();

    private DeviceSignature() {}

    /** "JMH-GATE-1" LF METHOD LF PATH LF time LF lowercase-hex(SHA-256(body)); PATH is raw path + query. */
    public static String canonical(String method, String path, long deviceTimeMs, byte[] body) {
        return PREFIX + "\n" + method + "\n" + path + "\n" + deviceTimeMs + "\n" + bodySha256(body);
    }

    public static String bodySha256(byte[] body) {
        return HEX.formatHex(sha256(body));
    }

    public static boolean withinWindow(long deviceTimeMs, long nowMs) {
        return Math.abs(deviceTimeMs - nowMs) <= WINDOW_MS;
    }

    /**
     * The key of a P-256 SubjectPublicKeyInfo in its canonical DER form, else empty. Re-encoding must give the same
     * bytes, so one key has one {@code devices.public_key} value (enrolment is idempotent on those bytes).
     */
    public static Optional<PublicKey> p256(byte[] spkiDer) {
        try {
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spkiDer));
            if (key instanceof ECPublicKey ec && ec.getParams().getCurve().equals(P256.getCurve())
                    && ec.getParams().getOrder().equals(P256.getOrder())
                    && ec.getParams().getGenerator().equals(P256.getGenerator())
                    && Arrays.equals(key.getEncoded(), spkiDer)) {
                return Optional.of(key);
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            // not a valid EC SubjectPublicKeyInfo
        }
        return Optional.empty();
    }

    /** DER-encoded ECDSA signature over the UTF-8 bytes of {@code canonical}; any malformed input is false. */
    public static boolean verify(PublicKey key, String canonical, byte[] derSignature) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(canonical.getBytes(StandardCharsets.UTF_8));
            return s.verify(derSignature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** First 8 hex chars of SHA-256(SPKI DER), uppercase, grouped 4-4: {@code AB12 CD34}. */
    public static String fingerprint(byte[] spkiDer) {
        String hex = HEX.formatHex(sha256(spkiDer), 0, 4).toUpperCase();
        return hex.substring(0, 4) + " " + hex.substring(4);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ECParameterSpec p256Params() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
