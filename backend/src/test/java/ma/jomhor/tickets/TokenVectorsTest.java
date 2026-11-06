package ma.jomhor.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import ma.jomhor.tickets.domain.TicketToken;
import org.junit.jupiter.api.Test;

/**
 * docs/04-api.md §Test vectors: 10 static + 10 rotating vectors from fixed inputs, the contract with
 * {@code packages/core}. Writes {@code docs/token-vectors.json} when it is absent or with
 * {@code -Djomhor.writeVectors=true}; otherwise fails when the committed file differs. On CI (env {@code CI} set) a
 * missing file fails instead of being written.
 */
class TokenVectorsTest {

    record Vector(byte[] kE, long eventId, UUID ticketId, int zoneId, Long slot) {}

    private static final HexFormat HEX = HexFormat.of();
    static final byte[] KE_A = new byte[32];
    static final byte[] KE_B = new byte[32];
    static final byte[] KE_C = sha256("jomhor-interim");
    static final byte[] KE_D = sha256("jomhor-vectors");
    static final byte[] KE_ZERO = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KE_A[i] = (byte) i;
            KE_B[i] = (byte) 0xFF;
        }
    }

    static final UUID ZEROS = UUID.fromString("00000000-0000-0000-0000-000000000000");
    static final UUID ONES = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    static final UUID V7 = UUID.fromString("0192f0c1-7a3e-7c4d-9b1a-2f3e4d5c6b7a");
    static final UUID SIGN = UUID.fromString("80000000-0000-0000-8000-000000000000");
    static final UUID MAX_SIGNED = UUID.fromString("7fffffff-ffff-7fff-7fff-ffffffffffff");
    static final UUID V7_B = UUID.fromString("0193a5b2-1c00-7000-8000-00000000abcd");
    static final UUID V4 = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");

    /** The first 3 of each half are the 6 interim cases (hard-coded in INTERIM), in that order. */
    static final List<Vector> VECTORS = List.of(
            new Vector(KE_A, 1, ZEROS, 0, null),
            new Vector(KE_B, 0xFFFFFFFFL, ONES, 0xFFFF, null),
            new Vector(KE_C, 4242, V7, 12, null),
            new Vector(KE_ZERO, 0, ZEROS, 0, null),
            new Vector(KE_A, 0x80000000L, SIGN, 0x8000, null),
            new Vector(KE_B, 0x7FFFFFFFL, MAX_SIGNED, 0x7FFF, null),
            new Vector(KE_C, 4242, V7, 0xFFFF, null),
            new Vector(KE_C, 4243, V7, 12, null),
            new Vector(KE_D, 4242, V7_B, 12, null),
            new Vector(KE_D, 123456789, V4, 300, null),
            new Vector(KE_A, 1, ZEROS, 0, 0L),
            new Vector(KE_B, 0xFFFFFFFFL, ONES, 0xFFFF, 0xFFFFFFFFL),
            new Vector(KE_C, 4242, V7, 12, 59_000_000L),
            new Vector(KE_ZERO, 0, ZEROS, 0, 0L),
            new Vector(KE_A, 0x80000000L, SIGN, 0x8000, 0x80000000L),
            new Vector(KE_B, 0x7FFFFFFFL, MAX_SIGNED, 0x7FFF, 0x7FFFFFFFL),
            new Vector(KE_C, 4242, V7, 12, 58_999_999L),
            new Vector(KE_C, 4242, V7, 12, 59_000_001L),
            new Vector(KE_D, 123456789, V4, 300, 59_799_840L), // 2026-11-06T20:00:00Z
            new Vector(KE_D, 4242, V7_B, 12, 0xFFFFFFFEL));

    /** Expected values from the independent stdlib-Python reference, not from this codec. */
    static final List<String> INTERIM = List.of(
            "AQAAAAEAAAAAAAAAAAAAAAAAAAAAAADY-OQAv69oEqMuKmsGFGbt",
            "Af____________________________-pSYKrqUqqRa-brQSqhrNn",
            "AQAAEJIBkvDBej58TZsaLz5NXGt6AAzL3C2r68YM_6EOzHyVF8yf",
            "AgAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwbWQAOILF_FGKbsjCpWAyQ",
            "Av__________________________________FrgpyDzZB-IGDRviZ-0Lwg",
            "AgAAEJIBkvDBej58TZsaLz5NXGt6AAwDhETAN2Y8hye_mRwmw86JAJgtcA");

    static String token(Vector v) {
        return v.slot() == null
                ? TicketToken.encodeStatic(v.kE(), v.eventId(), v.ticketId(), v.zoneId())
                : TicketToken.encodeRotating(TicketToken.deriveTicketKey(v.kE(), v.ticketId()), v.eventId(),
                        v.ticketId(), v.zoneId(), v.slot());
    }

    @Test
    void interimCasesMatchThePythonReference() {
        assertThat(HEX.formatHex(KE_C)).isEqualTo("07fd348a359bcfdb2e69357771fc9a7c78df62ff8b0834e3fcc478688f46c27b");
        assertThat(HEX.formatHex(TicketToken.deriveTicketKey(KE_C, V7)))
                .isEqualTo("ec4cb72d6b3af05e58da412ac6a988cad6433b16870da8f1cf1256fffae15a2a");
        List<String> got = new ArrayList<>();
        for (int i : new int[] {0, 1, 2, 10, 11, 12}) {
            got.add(token(VECTORS.get(i)));
        }
        assertThat(got).containsExactlyElementsOf(INTERIM);
    }

    @Test
    void everyVectorDecodesAndVerifies() {
        assertThat(VECTORS.stream().filter(v -> v.slot() == null)).hasSize(10);
        assertThat(VECTORS.stream().map(TokenVectorsTest::token).distinct()).hasSize(20);
        for (Vector v : VECTORS) {
            String t = token(v);
            assertThat(t).hasSize(v.slot() == null ? 52 : 58);
            TicketToken.Parsed p = TicketToken.decode(t);
            assertThat(p.version()).isEqualTo(v.slot() == null ? 1 : 2);
            assertThat(p.eventId()).isEqualTo(v.eventId());
            assertThat(p.ticketId()).isEqualTo(v.ticketId());
            assertThat(p.zoneId()).isEqualTo(v.zoneId());
            assertThat(p.slot().isPresent() ? p.slot().getAsLong() : null).isEqualTo(v.slot());
            long now = v.slot() == null ? 0 : v.slot();
            assertThat(TicketToken.verify(p, v.eventId(), v.kE(), now)).isEmpty();
        }
    }

    @Test
    void committedFileMatches() throws Exception {
        Path docs = Stream.of(Path.of("docs"), Path.of("..", "docs"))
                .filter(d -> Files.exists(d.resolve("04-api.md"))).findFirst()
                .orElseThrow(() -> new AssertionError("docs/ not found from " + Path.of("").toAbsolutePath()));
        Path file = docs.resolve("token-vectors.json");
        String json = json();
        boolean absent = !Files.exists(file);
        if (absent && System.getenv("CI") != null) {
            throw new AssertionError("docs/token-vectors.json is missing; CI never generates it: run TokenVectorsTest "
                    + "locally and commit the file");
        }
        if (absent || Boolean.getBoolean("jomhor.writeVectors")) {
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("docs/token-vectors.json differs from TicketToken; if the layout changed on purpose, "
                        + "rerun with -Djomhor.writeVectors=true and tell packages/core")
                .isEqualTo(json);
    }

    /** Pretty, fixed key order, LF, trailing newline. */
    static String json() {
        StringBuilder s = new StringBuilder("[\n");
        for (int i = 0; i < VECTORS.size(); i++) {
            Vector v = VECTORS.get(i);
            s.append("  {\n")
                    .append("    \"k_e\": \"").append(HEX.formatHex(v.kE())).append("\",\n")
                    .append("    \"event_id\": ").append(v.eventId()).append(",\n")
                    .append("    \"ticket_id\": \"").append(v.ticketId()).append("\",\n")
                    .append("    \"zone_id\": ").append(v.zoneId()).append(",\n");
            if (v.slot() != null) {
                s.append("    \"slot\": ").append(v.slot()).append(",\n");
            }
            s.append("    \"expected_token\": \"").append(token(v)).append("\"\n")
                    .append(i == VECTORS.size() - 1 ? "  }\n" : "  },\n");
        }
        return s.append("]\n").toString();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
