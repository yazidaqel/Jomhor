package ma.jomhor.gate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Scan sync helpers: an event site with gates and staff, JSON scan rows as a device sends them, signed batches. */
abstract class ScanApiTest extends GateApiTest {

    @Autowired
    JsonMapper json;

    /** A UUIDv7 as a device makes it: unix ms in the top 48 bits, version 7, variant 2. */
    static UUID v7() {
        var r = ThreadLocalRandom.current();
        long msb = (System.currentTimeMillis() << 16) | 0x7000L | (r.nextLong() & 0x0FFFL);
        long lsb = (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    /** The event, its gates and a GATE and a SUPERVISOR staff of the organizer. */
    record Site(Fixture f, UUID g1, UUID g2, UUID gateStaff, UUID supervisor) {}

    Site site(Instant startsAt) throws Exception {
        Fixture f = fixture(startsAt);
        return new Site(f, gateId(f, "G1"), gateId(f, "G2"), staffRow(f, "GATE"), staffRow(f, "SUPERVISOR"));
    }

    UUID gateId(Fixture f, String code) {
        return jdbc.sql("select g.id from gates g join events e on e.venue_id = g.venue_id where e.id = ? and g.code = ?")
                .params(f.event(), code).query(UUID.class).single();
    }

    UUID staffRow(Fixture f, String role) {
        return jdbc.sql("insert into staff (organizer_id, name, role) values (?, 'Gate Person', ?::staff_role) returning id")
                .params(f.c().org(), role).query(UUID.class).single();
    }

    /** A JSON scan row; null values are left out. */
    Map<String, Object> row(UUID scanId, UUID ticketId, UUID gateId, UUID staffId, String result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scan_id", scanId.toString());
        if (ticketId != null) {
            m.put("ticket_id", ticketId.toString());
        }
        m.put("gate_id", gateId.toString());
        m.put("staff_id", staffId.toString());
        m.put("direction", "IN");
        m.put("result", result);
        m.put("token_version", 1);
        m.put("device_time", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        return m;
    }

    /** {@code row} with this device_time (ms). */
    static Map<String, Object> at(Map<String, Object> row, Instant deviceTime) {
        row.put("device_time", deviceTime.truncatedTo(ChronoUnit.MILLIS).toString());
        return row;
    }

    Map<String, Object> ok(Site s, UUID ticketId) {
        return row(v7(), ticketId, s.g1(), s.gateStaff(), "OK");
    }

    String batch(long cursor, List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cursor", cursor);
        m.put("scans", rows);
        return json.writeValueAsString(m);
    }

    ResultActions postScans(Dev d, int eventId, String body) throws Exception {
        return send(d, Req.now("POST", "/v1/gate/events/" + eventId + "/scans", body));
    }

    /** A 200 sync response. */
    String sync(Dev d, int eventId, long cursor, List<Map<String, Object>> rows) throws Exception {
        return body(postScans(d, eventId, batch(cursor, rows)).andExpect(status().isOk()));
    }

    void assignAll(Fixture f, UUID gateId, Dev... devices) throws Exception {
        String rows = String.join(", ", Stream.of(devices)
                .map(d -> "{\"device_id\": \"%s\", \"gate_id\": \"%s\"}".formatted(d.id(), gateId)).toList());
        call(f.c(), put("/org/events/" + f.event() + "/devices"), "{\"assignments\": [" + rows + "]}")
                .andExpect(status().isOk());
    }

    int stored(UUID... scanIds) {
        return jdbc.sql("select count(*) from scans where scan_id = any(?)").param(scanIds).query(Integer.class).single();
    }

    @SafeVarargs
    static List<String> ids(Map<String, Object>... rows) {
        return Stream.of(rows).map(r -> (String) r.get("scan_id")).toList();
    }

    List<UUID> ticketIds(Fixture f) {
        return jdbc.sql("select id from tickets where event_id = ? order by id").param(f.event()).query(UUID.class).list();
    }
}
