package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import ma.jomhor.gate.domain.ManifestPayload;
import ma.jomhor.gate.domain.ManifestPayload.Gate;
import ma.jomhor.gate.domain.ManifestPayload.Row;
import ma.jomhor.gate.domain.ManifestPayload.Zone;
import org.junit.jupiter.api.Test;

/**
 * docs/04-api.md §Manifest payload, byte for byte. The same fixture feeds the {@code manifest} gate vectors
 * (GateVectorsTest): v1 full and the v2 delta since 1.
 */
class ManifestPayloadTest {

    static final List<Zone> ZONES = List.of(
            new Zone(3, "VIR", "\u0627\u0644\u0634\u0645\u0627\u0644"),
            new Zone(1, "TRIB", "Tribune Pr\u00e9sidentielle"),
            new Zone(2, "PEL", "Pelouse\tA"));
    static final List<Gate> GATES = List.of(
            new Gate(UUID.fromString("a1a1a1a1-0000-4000-8000-000000000002"), "G2", "Porte 2\r\nB", List.of(3, 1)),
            new Gate(UUID.fromString("a1a1a1a1-0000-4000-8000-000000000009"), "G9", "Porte 9", List.of()),
            new Gate(UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001"), "G1", "Porte 1", List.of(2)));

    static final UUID T1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
    /** Above T1 as a string, below it as Java's signed UUID order: the payload uses the string (PostgreSQL) order. */
    static final UUID T2 = UUID.fromString("80000000-0000-4000-8000-000000000002");
    static final UUID T3 = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    static final UUID T4 = UUID.fromString("c0000000-0000-4000-8000-000000000004");
    static final UUID T5 = UUID.fromString("20000000-0000-4000-8000-000000000005");

    static final List<Row> V1_ROWS = List.of(
            new Row(T4, 1, "BLOCKED", "M. E."),
            new Row(T2, 2, "VALID", null),
            new Row(T1, 1, "VALID", "Y. A."),
            new Row(T3, 3, "REFUNDED", "S. B."));
    /** T2 cancelled, T5 sold. */
    static final List<Row> V2_ROWS = List.of(
            new Row(T4, 1, "BLOCKED", "M. E."),
            new Row(T2, 2, "CANCELLED", null),
            new Row(T1, 1, "VALID", "Y. A."),
            new Row(T3, 3, "REFUNDED", "S. B."),
            new Row(T5, 3, "VALID", "K. O."));

    static final Instant V1_AT = Instant.parse("2026-11-06T17:00:00Z");
    static final Instant V2_AT = Instant.parse("2026-11-06T17:05:00.250Z");

    static final String ZG = """
            Z\t1\tTRIB\tTribune Pr\u00e9sidentielle
            Z\t2\tPEL\tPelouse A
            Z\t3\tVIR\t\u0627\u0644\u0634\u0645\u0627\u0644
            G\ta1a1a1a1-0000-4000-8000-000000000001\tG1\tPorte 1\t2
            G\ta1a1a1a1-0000-4000-8000-000000000002\tG2\tPorte 2  B\t1,3
            G\ta1a1a1a1-0000-4000-8000-000000000009\tG9\tPorte 9\t
            """;

    static final String V1 = """
            JMH-MANIFEST\t1
            event\t12
            version\t1
            since\t0
            built_at\t2026-11-06T17:00:00Z
            reentry\tNONE
            rows\t4
            """ + ZG + """
            T\t10000000-0000-4000-8000-000000000001\t1\tVALID\tY. A.
            T\t3f2504e0-4f89-41d3-9a0c-0305e82c3301\t3\tREFUNDED\tS. B.
            T\t80000000-0000-4000-8000-000000000002\t2\tVALID\t
            T\tc0000000-0000-4000-8000-000000000004\t1\tBLOCKED\tM. E.
            """;

    static final String V2_DELTA = """
            JMH-MANIFEST\t1
            event\t12
            version\t2
            since\t1
            built_at\t2026-11-06T17:05:00.250Z
            reentry\tNONE
            rows\t5
            """ + ZG + """
            T\t20000000-0000-4000-8000-000000000005\t3\tVALID\tK. O.
            T\t80000000-0000-4000-8000-000000000002\t2\tCANCELLED\t
            """;

    static String v1() {
        return ManifestPayload.full(12, 1, V1_AT, "NONE", ZONES, GATES, V1_ROWS);
    }

    static String v2() {
        return ManifestPayload.full(12, 2, V2_AT, "NONE", ZONES, GATES, V2_ROWS);
    }

    static String v2Delta() {
        return ManifestPayload.delta(v2(), v1(), 1);
    }

    @Test
    void fullPayloadIsTheFixtureByteForByte() {
        assertThat(v1()).isEqualTo(V1);
        assertThat(V1).doesNotContain("\r").endsWith("\n");
    }

    @Test
    void inputOrderDoesNotChangeTheBytes() {
        var rows = new ArrayList<>(V2_ROWS);
        var zones = new ArrayList<>(ZONES);
        var gates = new ArrayList<>(GATES);
        Random random = new Random(7);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(rows, random);
            Collections.shuffle(zones, random);
            Collections.shuffle(gates, random);
            assertThat(ManifestPayload.full(12, 2, V2_AT, "NONE", zones, gates, rows)).isEqualTo(v2());
        }
    }

    @Test
    void deltaHasTheNewAndChangedRowsOnly() {
        assertThat(v2Delta()).isEqualTo(V2_DELTA);
        // since = latest: Z and G lines, no T
        String none = ManifestPayload.delta(v2(), v2(), 2);
        assertThat(none).doesNotContain("\nT\t").contains("\nsince\t2\n").contains("\nrows\t5\n").endsWith(ZG);
    }

    @Test
    void sameContentIgnoresVersionSinceAndBuiltAtOnly() {
        String rebuilt = ManifestPayload.full(12, 7, V2_AT.plusSeconds(600), "NONE", ZONES, GATES, V1_ROWS);
        assertThat(ManifestPayload.sameContent(v1(), rebuilt)).isTrue();
        assertThat(ManifestPayload.sameContent(v1(), v2())).isFalse();
        assertThat(ManifestPayload.sameContent(v1(),
                ManifestPayload.full(12, 1, V1_AT, "ONE_EXIT", ZONES, GATES, V1_ROWS))).isFalse();
        var renamed = List.of(ZONES.get(0), ZONES.get(1), new Zone(2, "PEL", "Pelouse B"));
        assertThat(ManifestPayload.sameContent(v1(),
                ManifestPayload.full(12, 1, V1_AT, "NONE", renamed, GATES, V1_ROWS))).isFalse();
    }

    @Test
    void builtAtKeepsMillisecondsAtMost() {
        assertThat(ManifestPayload.builtAt(Instant.parse("2026-11-06T17:05:00.250999Z")))
                .isEqualTo(Instant.parse("2026-11-06T17:05:00.250Z"));
    }
}
