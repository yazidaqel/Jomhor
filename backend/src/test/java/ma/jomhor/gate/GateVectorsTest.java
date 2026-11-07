package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import ma.jomhor.gate.adapters.crypto.TestManifestSigners;
import ma.jomhor.gate.application.ManifestSigner;
import ma.jomhor.gate.domain.DeviceSignature;
import ma.jomhor.tenancy.domain.Pins;
import org.junit.jupiter.api.Test;

/**
 * docs/04-api.md §Gate vectors, the contract with {@code apps/scanner}: {@code signing} (the string a device signs and
 * its body hash), {@code fingerprints}, {@code manifest} (ManifestPayloadTest's payloads signed with the fixed test
 * key; Ed25519 is deterministic) and {@code pins} (verifiers with fixed salts, so PBKDF2 gives fixed bytes). Same rules as TokenVectorsTest: writes {@code docs/gate-vectors.json} when absent or with {@code -Djomhor.writeVectors=true},
 * otherwise fails when the committed file differs; on CI a missing file fails.
 *
 * <p>ECDSA signatures are randomized, so the file holds no device signature: {@link #fixedSignatureVerifies()} checks
 * one made by OpenSSL with key A over signing vector 2 instead.
 */
class GateVectorsTest {

    record Signing(String method, String path, long time, String body) {}

    /** {@code pin} typed on the device, checked against the verifier of {@code setPin} with a fixed salt. */
    record Pin(String pin, String setPin, byte[] salt) {
        String verifier() {
            return Pins.verifier(setPin, salt);
        }

        boolean ok() {
            return Pins.matches(pin, verifier());
        }
    }

    /** Two P-256 SubjectPublicKeyInfo (DER, base64url) generated once with OpenSSL. */
    static final String KEY_A = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEywbQayvKJf7EE3eixozLL2IL9ybXy-BliZa3mnRDoHYjTh3g40a5V"
            + "_gOY8EltE25ZgPRkdas0YKviSgrDjPncg";
    static final String KEY_B = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmwzt5JOrpjgxUkhG5_CThvrvGwPznd-ZebY8UpTKGbxwKXMlFTpIMUd"
            + "G9FxuGDX9aMceJxWYs5dT3tfLRL6CPg";

    static final String ENROL_BODY = "{\"public_key\":\"" + KEY_A + "\",\"label\":\"Tablette B\u00e2b 3\","
            + "\"enrol_code\":\"eyJhbGciOiJIUzI1NiJ9.e30.c2lnbmF0dXJl\"}";
    static final String SCANS_BODY = "{\n  \"cursor\": 0,\n  \"scans\": [\n"
            + "    {\"scan_id\": \"0192f0c1-7a3e-7c4d-9b1a-2f3e4d5c6b7a\", \"result\": \"OK\"}\n  ]\n}\n";

    /** 1793995200000 = 2026-11-06T20:00:00Z. */
    static final List<Signing> SIGNING = List.of(
            new Signing("GET", "/v1/gate/events", 1793995200000L, ""),
            new Signing("GET", "/v1/gate/events/12/manifest?since=3", 1793995200123L, ""),
            new Signing("POST", "/v1/gate/devices/enrol", 1793995200000L, ENROL_BODY),
            new Signing("POST", "/v1/gate/events/12/scans", 1793995260999L, SCANS_BODY),
            new Signing("POST", "/v1/gate/events/4294967295/closed-ack", 9999999999999L, ""));

    static final List<String> FINGERPRINT_KEYS = List.of(KEY_A, KEY_B);

    /**
     * The Ed25519 test key of src/test/resources/application.properties (PKCS#8, OpenSSL) and its raw public key. The
     * signed bytes of a {@code manifest} vector are the UTF-8 bytes of its {@code payload}, nothing else.
     */
    static final String MANIFEST_KEY = "MC4CAQAwBQYDK2VwBCIEIL+e1GK5oIgWytc6DivLU7RK4h6MpYvqcLDohcW8gl2Y";
    static final String MANIFEST_PUBLIC_KEY = "9657JB-9nYlMlNMM2AYY8UzKMLcbfTkI3UKd6SDuEDc";
    static final ManifestSigner MANIFEST_SIGNER = TestManifestSigners.ed25519(MANIFEST_KEY);

    /** v1 full and the v2 delta since 1 (a cancel and a sale), with non-ASCII zone names and a cleaned TAB/CR/LF. */
    static final List<String> MANIFESTS = List.of(ManifestPayloadTest.V1, ManifestPayloadTest.V2_DELTA);

    static final byte[] SALT_1 = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    static final byte[] SALT_2 = "jomhor-pin-salt!".getBytes(StandardCharsets.US_ASCII);
    static final byte[] SALT_3 = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};

    /** Right PIN, PIN + 1, leading zeros, a 3-digit prefix and the edges 0000 / 9999. */
    static final List<Pin> PINS = List.of(
            new Pin("4821", "4821", SALT_1),
            new Pin("4822", "4821", SALT_1),
            new Pin("0000", "0000", SALT_2),
            new Pin("0001", "0000", SALT_2),
            new Pin("000", "0000", SALT_2),
            new Pin("9999", "9999", SALT_3),
            new Pin("0042", "0042", SALT_3),
            new Pin("4200", "0042", SALT_3));

    /** OpenSSL ({@code dgst -sha256 -sign}, key A) over the canonical string of SIGNING[2]. */
    static final String OPENSSL_SIGNATURE = "MEUCIQC-YJMS5mKKOVt7qmChmEPp2jt_SZuuHsJCTRtOwv6DcwIgOertJIdcCt9X5IKHaXTQ"
            + "LBihJdn2l4po2u2uu-E5Ipc";

    @Test
    void valuesMatchThePythonReference() {
        assertThat(DeviceSignature.bodySha256(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(DeviceSignature.bodySha256(ENROL_BODY.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("2de56bbc981e41141777e54c374769382da26f07ec5d9ea25a0f9cad411e5791");
        assertThat(DeviceSignature.bodySha256(SCANS_BODY.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("a606465d0455b90547c2d2d8bcd618e8f0431375985e16b8eceeec95d9814cb1");
        assertThat(DeviceSignature.fingerprint(der(KEY_A))).isEqualTo("602F 0D53");
        assertThat(DeviceSignature.fingerprint(der(KEY_B))).isEqualTo("E5DE C776");
        // hashlib.pbkdf2_hmac('sha256', pin, salt, 10000, 32), base64url without padding
        assertThat(Pins.verifier("4821", SALT_1))
                .isEqualTo("pbkdf2-sha256$10000$AAECAwQFBgcICQoLDA0ODw$ai3UbCeDBT5nKdEHXGutZKoXwZELT3vm4xIneno6vN8");
        assertThat(Pins.verifier("0000", SALT_2))
                .isEqualTo("pbkdf2-sha256$10000$am9taG9yLXBpbi1zYWx0IQ$Ovfiz4Ysoon92BXSLNgGbKdO0IZMkYM6cTMO1jhRAO8");
        assertThat(Pins.verifier("9999", SALT_3))
                .isEqualTo("pbkdf2-sha256$10000$_____________________w$_ShV_xRB2zwz5Xp0_RpxXoPdojduU2gM1wj66ir_DoM");
        assertThat(canonical(SIGNING.get(1))).isEqualTo("JMH-GATE-1\nGET\n/v1/gate/events/12/manifest?since=3\n"
                + "1793995200123\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void fixedSignatureVerifies() {
        PublicKey key = DeviceSignature.p256(der(KEY_A)).orElseThrow();
        byte[] sig = Base64.getUrlDecoder().decode(OPENSSL_SIGNATURE);
        String canonical = canonical(SIGNING.get(2));
        assertThat(DeviceSignature.verify(key, canonical, sig)).isTrue();
        assertThat(DeviceSignature.verify(key, canonical.replace("enrol", "enroL"), sig)).isFalse();
        assertThat(DeviceSignature.verify(DeviceSignature.p256(der(KEY_B)).orElseThrow(), canonical, sig)).isFalse();
        byte[] flipped = sig.clone();
        flipped[10] ^= 1;
        assertThat(DeviceSignature.verify(key, canonical, flipped)).isFalse();
        assertThat(DeviceSignature.verify(key, canonical, new byte[] {1, 2, 3})).isFalse();
    }

    @Test
    void clockWindowIsFiveMinutesInclusive() {
        long now = 1793995200000L;
        assertThat(DeviceSignature.withinWindow(now + 300_000, now)).isTrue();
        assertThat(DeviceSignature.withinWindow(now - 300_000, now)).isTrue();
        assertThat(DeviceSignature.withinWindow(now + 300_001, now)).isFalse();
        assertThat(DeviceSignature.withinWindow(now - 300_001, now)).isFalse();
    }

    @Test
    void onlyCanonicalP256KeysAreAccepted() throws Exception {
        assertThat(DeviceSignature.p256(der(KEY_A))).isPresent();
        var p384 = KeyPairGenerator.getInstance("EC");
        p384.initialize(new ECGenParameterSpec("secp384r1"));
        assertThat(DeviceSignature.p256(p384.generateKeyPair().getPublic().getEncoded())).isEmpty();
        assertThat(DeviceSignature.p256(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic()
                .getEncoded())).isEmpty();
        byte[] trailing = java.util.Arrays.copyOf(der(KEY_A), der(KEY_A).length + 1);
        assertThat(DeviceSignature.p256(trailing)).isEmpty();
        assertThat(DeviceSignature.p256(new byte[] {0x30, 0x00})).isEmpty();
    }

    @Test
    void pinVerifiersAcceptTheirPinOnly() {
        assertThat(PINS.stream().map(Pin::ok).toList()).containsExactly(true, false, true, false, false, true, true, false);
        String v = Pins.verifier("4821");
        assertThat(v).isNotEqualTo(Pins.verifier("4821")); // random salt
        assertThat(Pins.matches("4821", v)).isTrue();
        for (String bad : List.of("", "pbkdf2-sha256$10000$AAECAwQFBgcICQoLDA0ODw", v.replace("$10000$", "$1000$"),
                v.replace("pbkdf2-sha256", "pbkdf2-sha1"), v + "$", v.substring(0, v.length() - 2) + "!!")) {
            assertThat(Pins.matches("4821", bad)).as(bad).isFalse();
        }
        assertThat(Pins.matches("4821", null)).isFalse();
        assertThat(Pins.newPin()).matches("[0-9]{4}");
    }

    @Test
    void manifestSignaturesVerifyWithThePublicKeyOnly() throws Exception {
        assertThat(ManifestPayloadTest.v1()).isEqualTo(MANIFESTS.get(0));
        assertThat(ManifestPayloadTest.v2Delta()).isEqualTo(MANIFESTS.get(1));
        assertThat(b64url(MANIFEST_SIGNER.publicKey())).isEqualTo(MANIFEST_PUBLIC_KEY);
        var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(HexFormat.of()
                .parseHex("302a300506032b6570032100" + HexFormat.of().formatHex(der(MANIFEST_PUBLIC_KEY)))));
        for (String payload : MANIFESTS) {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            byte[] sig = Base64.getUrlDecoder().decode(manifestSignature(payload));
            var v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(bytes);
            assertThat(v.verify(sig)).isTrue();
            bytes[bytes.length - 2] ^= 1; // one flipped bit in the last T line
            v.initVerify(key);
            v.update(bytes);
            assertThat(v.verify(sig)).isFalse();
        }
    }

    @Test
    void committedFileMatches() throws Exception {
        Path docs = Stream.of(Path.of("docs"), Path.of("..", "docs"))
                .filter(d -> Files.exists(d.resolve("04-api.md"))).findFirst()
                .orElseThrow(() -> new AssertionError("docs/ not found from " + Path.of("").toAbsolutePath()));
        Path file = docs.resolve("gate-vectors.json");
        String json = json();
        boolean absent = !Files.exists(file);
        if (absent && System.getenv("CI") != null) {
            throw new AssertionError("docs/gate-vectors.json is missing; CI never generates it: run GateVectorsTest "
                    + "locally and commit the file");
        }
        if (absent || Boolean.getBoolean("jomhor.writeVectors")) {
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("docs/gate-vectors.json differs from DeviceSignature; if the layout changed on purpose, "
                        + "rerun with -Djomhor.writeVectors=true and tell apps/scanner")
                .isEqualTo(json);
    }

    /** Pretty, fixed key order, LF, trailing newline, ASCII only (non-ASCII as \\u escapes). */
    static String json() {
        StringBuilder s = new StringBuilder("{\n  \"signing\": [\n");
        for (int i = 0; i < SIGNING.size(); i++) {
            Signing v = SIGNING.get(i);
            s.append("    {\n")
                    .append("      \"method\": ").append(str(v.method())).append(",\n")
                    .append("      \"path\": ").append(str(v.path())).append(",\n")
                    .append("      \"time\": ").append(v.time()).append(",\n")
                    .append("      \"body\": ").append(str(v.body())).append(",\n")
                    .append("      \"expected_string\": ").append(str(canonical(v))).append(",\n")
                    .append("      \"expected_body_sha256\": ")
                    .append(str(DeviceSignature.bodySha256(v.body().getBytes(StandardCharsets.UTF_8)))).append("\n")
                    .append(i == SIGNING.size() - 1 ? "    }\n" : "    },\n");
        }
        s.append("  ],\n  \"fingerprints\": [\n");
        for (int i = 0; i < FINGERPRINT_KEYS.size(); i++) {
            String key = FINGERPRINT_KEYS.get(i);
            s.append("    {\n")
                    .append("      \"public_key\": ").append(str(key)).append(",\n")
                    .append("      \"fingerprint\": ").append(str(DeviceSignature.fingerprint(der(key)))).append("\n")
                    .append(i == FINGERPRINT_KEYS.size() - 1 ? "    }\n" : "    },\n");
        }
        s.append("  ],\n  \"manifest\": [\n");
        for (int i = 0; i < MANIFESTS.size(); i++) {
            String payload = MANIFESTS.get(i);
            s.append("    {\n")
                    .append("      \"public_key\": ").append(str(MANIFEST_PUBLIC_KEY)).append(",\n")
                    .append("      \"payload\": ").append(str(payload)).append(",\n")
                    .append("      \"signature\": ").append(str(manifestSignature(payload))).append("\n")
                    .append(i == MANIFESTS.size() - 1 ? "    }\n" : "    },\n");
        }
        s.append("  ],\n  \"pins\": [\n");
        for (int i = 0; i < PINS.size(); i++) {
            Pin p = PINS.get(i);
            s.append("    {\n")
                    .append("      \"pin\": ").append(str(p.pin())).append(",\n")
                    .append("      \"verifier\": ").append(str(p.verifier())).append(",\n")
                    .append("      \"ok\": ").append(p.ok()).append("\n")
                    .append(i == PINS.size() - 1 ? "    }\n" : "    },\n");
        }
        return s.append("  ]\n}\n").toString();
    }

    static String canonical(Signing v) {
        return DeviceSignature.canonical(v.method(), v.path(), v.time(), v.body().getBytes(StandardCharsets.UTF_8));
    }

    static String manifestSignature(String payload) {
        return b64url(MANIFEST_SIGNER.sign(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] der(String base64url) {
        return Base64.getUrlDecoder().decode(base64url);
    }

    /** A JSON string literal: quote, backslash and control characters escaped, anything outside ASCII as \\uXXXX. */
    private static String str(String value) {
        StringBuilder s = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        s.append("\\u%04x".formatted((int) c));
                    } else {
                        s.append(c);
                    }
                }
            }
        }
        return s.append('"').toString();
    }
}
