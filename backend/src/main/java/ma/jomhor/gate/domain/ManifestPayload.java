package ma.jomhor.gate.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * docs/04-api.md §Manifest payload: UTF-8 text, LF after every line, TAB between fields. The text is what gets signed
 * and sent, byte for byte. Sorting is done here, so the same rows give the same bytes whatever order they came in.
 */
public final class ManifestPayload {

    public static final String MAGIC = "JMH-MANIFEST\t1";

    public record Zone(int id, String code, String name) {}

    public record Gate(UUID id, String code, String name, List<Integer> zoneIds) {}

    /** {@code status}: VALID, CANCELLED, REFUNDED or BLOCKED. {@code initials} may be null. */
    public record Row(UUID ticketId, int zoneId, String status, String initials) {}

    private ManifestPayload() {}

    /**
     * A full manifest ({@code since 0}): zones by id, gates by code (zone ids ascending), one T per ticket by
     * ticket_id (canonical string order, the same as PostgreSQL's uuid order).
     */
    public static String full(int eventId, int version, Instant builtAt, String reentry, List<Zone> zones,
                              List<Gate> gates, List<Row> rows) {
        StringBuilder s = new StringBuilder(64 + rows.size() * 64);
        header(s, eventId, version, 0, builtAt, reentry, rows.size());
        zones.stream().sorted(Comparator.comparingInt(Zone::id))
                .forEach(z -> line(s, "Z", Integer.toString(z.id()), clean(z.code()), clean(z.name())));
        gates.stream().sorted(Comparator.comparing(Gate::code).thenComparing(g -> g.id().toString()))
                .forEach(g -> line(s, "G", g.id().toString(), clean(g.code()), clean(g.name()),
                        g.zoneIds().stream().sorted().map(String::valueOf).collect(Collectors.joining(","))));
        rows.stream().sorted(Comparator.comparing(r -> r.ticketId().toString()))
                .forEach(r -> line(s, "T", r.ticketId().toString(), Integer.toString(r.zoneId()), r.status(),
                        clean(r.initials())));
        return s.toString();
    }

    /**
     * The delta from {@code base} (the full payload of version {@code since}) to {@code latest} (a later full
     * payload): latest's header with {@code since}, every Z and G line, and only the T lines that are new or changed
     * (zone and initials never change, so a changed line is a changed status).
     */
    public static String delta(String latest, String base, int since) {
        Set<String> baseRows = new HashSet<>();
        for (String l : lines(base)) {
            if (l.startsWith("T\t")) {
                baseRows.add(l);
            }
        }
        StringBuilder s = new StringBuilder();
        List<String> lines = lines(latest);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (i == SINCE_LINE) {
                l = "since\t" + since;
            }
            if (!l.startsWith("T\t") || !baseRows.contains(l)) {
                s.append(l).append('\n');
            }
        }
        return s.toString();
    }

    /** Same content once version, since and built_at are set aside: no new version is needed. */
    public static boolean sameContent(String a, String b) {
        return content(a).equals(content(b));
    }

    /** RFC 3339 UTC, milliseconds at most (what a version stores and prints). */
    public static Instant builtAt(Instant now) {
        return now.truncatedTo(ChronoUnit.MILLIS);
    }

    public static byte[] gzip(byte[] bytes) {
        var out = new ByteArrayOutputStream(bytes.length / 3 + 64);
        try (var z = new GZIPOutputStream(out)) {
            z.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** The UTF-8 text of a stored (gzip) body. */
    public static String gunzip(byte[] body) {
        try (var z = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(z.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Text fields: TAB, CR and LF become a space; null is empty. */
    static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    // Header lines, in order: magic, event, version, since, built_at, reentry, rows.
    private static final int VERSION_LINE = 2;
    private static final int SINCE_LINE = 3;
    private static final int BUILT_AT_LINE = 4;

    private static void header(StringBuilder s, int eventId, int version, int since, Instant builtAt, String reentry,
                               int rows) {
        s.append(MAGIC).append('\n');
        line(s, "event", Integer.toString(eventId));
        line(s, "version", Integer.toString(version));
        line(s, "since", Integer.toString(since));
        line(s, "built_at", builtAt(builtAt).toString());
        line(s, "reentry", reentry);
        line(s, "rows", Integer.toString(rows));
    }

    private static void line(StringBuilder s, String first, String... fields) {
        s.append(first);
        for (String f : fields) {
            s.append('\t').append(f);
        }
        s.append('\n');
    }

    private static List<String> lines(String payload) {
        return List.of(payload.split("\n"));
    }

    private static List<String> content(String payload) {
        List<String> l = new ArrayList<>(lines(payload));
        l.remove(BUILT_AT_LINE);
        l.remove(SINCE_LINE);
        l.remove(VERSION_LINE);
        return l;
    }
}
