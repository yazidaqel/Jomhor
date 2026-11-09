package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract: docs/04-api.md §Fan GET /fan/gates/{event_id}/load (levels from the §Gate reports /live figures, entries
 * = the caller's canonical admitted scans, 15 s gate memo, no-store); plan lot-4 BE-32.
 */
class GateLoadApiTest extends ScanApiTest {

    // ---------- helpers ----------

    private UUID user(String phone) {
        return jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
    }

    private UUID otherUser() {
        return jdbc.sql("insert into users (phone_e164) values (?) returning id").param(randomPhone())
                .query(UUID.class).single();
    }

    /** A ticket of zone 1 held by {@code holder}. */
    private UUID held(Fixture f, UUID holder) {
        return jdbc.sql("insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, "
                        + "holder_user_id, holder_name, holder_initials) values (?, ?, ?, ?, ?::uuid, ?, 'Fan Un', 'F. U.') "
                        + "returning id")
                .params(f.c().org(), order(f), f.event(), f.zone1(), f.price(), holder).query(UUID.class).single();
    }

    /** One scan row as ingestion would store it (OVERRIDE gets a reason and the site's supervisor). */
    private void scan(Site s, Dev d, UUID ticketId, UUID gateId, String result, Instant at) {
        boolean override = result.equals("OVERRIDE");
        jdbc.sql("insert into scans (scan_id, organizer_id, event_id, device_id, ticket_id, gate_id, staff_id, direction, "
                        + "result, token_version, device_time, override_reason, supervisor_id) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'IN', ?::scan_result, 1, ?, ?::override_reason, ?)")
                .params(v7(), s.f().c().org(), s.f().event(), d.id(), ticketId, gateId, s.gateStaff(), result,
                        at.atOffset(ZoneOffset.UTC), override ? "ID_CHECKED" : null, override ? s.supervisor() : null)
                .update();
    }

    /** {@code count} OK scans of {@code ticketId} at {@code gateId}, device_time 1 min ago (in the 5 min window). */
    private void admitted(Site s, Dev d, UUID ticketId, UUID gateId, int count) {
        jdbc.sql("insert into scans (scan_id, organizer_id, event_id, device_id, ticket_id, gate_id, staff_id, direction, "
                        + "result, token_version, device_time) "
                        + "select gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'IN', 'OK', 1, now() - interval '1 minute' "
                        + "from generate_series(1, ?)")
                .params(s.f().c().org(), s.f().event(), d.id(), ticketId, gateId, s.gateStaff(), count).update();
    }

    private void seen(Dev d, Duration ago) {
        jdbc.sql("update devices set last_seen_at = now() - ? * interval '1 second' where id = ?")
                .params(ago.toSeconds(), d.id()).update();
    }

    /** {@code pairs}: device, gate, device, gate... become the event's whole assignment set. */
    private void assignments(Fixture f, Object... pairs) throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            rows.add("{\"device_id\": \"%s\", \"gate_id\": \"%s\"}".formatted(((Dev) pairs[i]).id(), pairs[i + 1]));
        }
        call(f.c(), put("/org/events/" + f.event() + "/devices"), "{\"assignments\": " + rows + "}")
                .andExpect(status().isOk());
    }

    private ResultActions getLoad(String token, Object eventId) throws Exception {
        return mvc.perform(get("/fan/gates/" + eventId + "/load").header("Authorization", "Bearer " + token));
    }

    private String load(String token, int eventId) throws Exception {
        return body(getLoad(token, eventId).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
    }

    private static Map<String, String> levels(String json) {
        Map<String, String> m = new LinkedHashMap<>();
        List<Map<String, Object>> gates = JsonPath.read(json, "$.gates");
        gates.forEach(g -> m.put((String) g.get("code"), (String) g.get("level")));
        return m;
    }

    // ---------- access ----------

    @Test
    void onlyAHolderOfTheEventGetsIt() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(2)));
        Fixture f = s.f();
        String phone = randomPhone();
        String fan = fanToken(phone);
        String stranger = fanToken(randomPhone());
        UUID ticket = held(f, user(phone));

        String json = load(fan, f.event());
        assertKeys(json, "$", "event_id", "server_time", "gates", "entries");
        assertThat(JsonPath.<Integer>read(json, "$.event_id")).isEqualTo(f.event());
        assertThat(Instant.parse(JsonPath.read(json, "$.server_time"))).isCloseTo(Instant.now(),
                within(5, ChronoUnit.SECONDS));
        assertKeys(json, "$.gates[0]", "code", "level");

        getLoad(stranger, f.event()).andExpect(status().isNotFound());
        getLoad(fan, Integer.MAX_VALUE).andExpect(status().isNotFound());
        getLoad(fan, "abc").andExpect(status().isBadRequest());
        mvc.perform(get("/fan/gates/" + f.event() + "/load")).andExpect(status().isUnauthorized());
        getLoad(f.c().token(), f.event()).andExpect(status().isUnauthorized()); // an org token

        // A holder whose only ticket was transferred away still gets it (any status).
        UUID recipient = otherUser();
        UUID newTicket = held(f, recipient);
        jdbc.sql("update tickets set status = 'CANCELLED' where id = ?").param(ticket).update();
        jdbc.sql("insert into ticket_transfers (organizer_id, ticket_id, new_ticket_id, from_user_id, to_user_id) "
                + "values (?, ?, ?, ?, ?)").params(f.c().org(), ticket, newTicket, user(phone), recipient).update();
        assertThat(JsonPath.<List<Object>>read(load(fan, f.event()), "$.entries")).isEmpty();
    }

    @Test
    void multiClubOnlyTheAskedEventsGatesAndEntries() throws Exception {
        Site a = site(Instant.now().plus(Duration.ofHours(2)));
        Site b = site(Instant.now().plus(Duration.ofHours(2)));
        String phone = randomPhone();
        String fan = fanToken(phone);
        UUID inA = held(a.f(), user(phone));
        UUID inB = held(b.f(), user(phone));
        seen(a.f().device(), Duration.ZERO);
        seen(b.f().device(), Duration.ZERO);
        // B's G1 (same code as A's) is busy and admits the fan's B ticket.
        admitted(b, b.f().device(), inB, b.g1(), 230);
        scan(b, b.f().device(), inB, b.g1(), "OK", Instant.now().minus(Duration.ofMinutes(3)));

        String json = load(fan, a.f().event());
        assertThat(levels(json)).containsExactly(Map.entry("G1", "LOW"));
        assertThat(JsonPath.<List<Object>>read(json, "$.entries")).isEmpty();
        json = load(fan, b.f().event());
        assertThat(levels(json)).containsExactly(Map.entry("G1", "HIGH"));
        assertThat(JsonPath.<List<String>>read(json, "$.entries[*].ticket_id")).containsExactly(inB.toString());
        assertThat(json).doesNotContain(inA.toString());

        String onlyB = randomPhone();
        String onlyBFan = fanToken(onlyB);
        held(b.f(), user(onlyB));
        getLoad(onlyBFan, a.f().event()).andExpect(status().isNotFound());
    }

    // ---------- levels ----------

    @Test
    void levelsFollowTheLiveRateAndTheDevicesOnline() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(2)));
        Fixture f = s.f();
        Caller c = f.c();
        String venueId = jdbc.sql("select venue_id::text from events where id = ?").param(f.event())
                .query(String.class).single();
        UUID[] g = new UUID[9];
        g[1] = s.g1();
        g[2] = s.g2();
        for (int i = 3; i <= 8; i++) {
            g[i] = UUID.fromString(gate(c, venueId, "G" + i, f.zone1()));
        }
        Dev d1a = f.device();
        Dev d1b = active(c);
        Dev d2a = active(c);
        Dev d2b = active(c);
        Dev d3a = active(c);
        Dev d3b = active(c);
        Dev d4 = active(c);
        Dev d5 = active(c);
        Dev d6 = active(c);
        Dev d7 = active(c);
        assignments(f, d1a, g[1], d1b, g[1], d2a, g[2], d2b, g[2], d3a, g[3], d3b, g[3], d4, g[4], d5, g[5], d6, g[6],
                d7, g[7]);
        call(c, post("/org/devices/" + d7.id() + "/revoke")).andExpect(status().isOk());
        for (Dev d : List.of(d1a, d1b, d2a, d2b, d3a, d3b, d4, d5, d7)) {
            seen(d, Duration.ZERO);
        }
        seen(d6, Duration.ofMinutes(3)); // silent > 2 min
        UUID t = ticket(f, f.zone1(), "Fan Deux");
        admitted(s, d1a, t, g[1], 30);   // 2 online, rate 6: 6 / 48 < 0.5
        admitted(s, d2a, t, g[2], 230);  // 2 online, rate 46: 46 / 48 >= 0.85
        admitted(s, d3a, t, g[3], 120);  // 2 online, rate 24: 24 / 48 = 0.5 exactly
        admitted(s, d4, t, g[4], 100);   // 1 online, rate 20: 20 / 24 < 0.85
        admitted(s, d5, t, g[5], 105);   // 1 online, rate 21: 21 / 24 >= 0.85
        admitted(s, d6, t, g[6], 230);   // nobody online
        admitted(s, d7, t, g[7], 230);   // revoked: not online
        admitted(s, d1a, t, g[8], 5);    // scans, but no assignment
        String phone = randomPhone();
        String fan = fanToken(phone);
        held(f, user(phone));

        assertThat(levels(load(fan, f.event()))).containsExactly(
                Map.entry("G1", "LOW"), Map.entry("G2", "HIGH"), Map.entry("G3", "MEDIUM"), Map.entry("G4", "MEDIUM"),
                Map.entry("G5", "HIGH"), Map.entry("G6", "UNKNOWN"), Map.entry("G7", "UNKNOWN"));
    }

    // ---------- entries ----------

    @Test
    void entriesAreTheCallersCanonicalAdmittedScansOnly() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(2)));
        Fixture f = s.f();
        Dev d = f.device();
        String phone = randomPhone();
        String fan = fanToken(phone);
        UUID me = user(phone);
        UUID entered = held(f, me);
        UUID refused = held(f, me);
        UUID overridden = held(f, me);
        UUID notScanned = held(f, me);
        UUID someoneElses = held(f, otherUser());
        Instant t0 = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS);
        scan(s, d, entered, s.g1(), "OK", t0);
        scan(s, d, entered, s.g2(), "OK", t0.plusSeconds(30)); // DUPLICATE_LATE at another gate
        scan(s, d, refused, s.g1(), "WRONG_ZONE", t0);
        scan(s, d, overridden, s.g2(), "OVERRIDE", t0.plusSeconds(60));
        scan(s, d, someoneElses, s.g1(), "OK", t0);

        String json = load(fan, f.event());
        assertKeys(json, "$.entries[0]", "ticket_id", "entered_at", "gate_code");
        List<Map<String, Object>> entries = JsonPath.read(json, "$.entries");
        assertThat(entries).hasSize(2);
        Map<String, Map<String, Object>> byTicket = new HashMap<>();
        entries.forEach(e -> byTicket.put((String) e.get("ticket_id"), e));
        assertThat(byTicket.keySet()).containsExactlyInAnyOrder(entered.toString(), overridden.toString());
        assertThat(Instant.parse((String) byTicket.get(entered.toString()).get("entered_at"))).isEqualTo(t0);
        assertThat(byTicket.get(entered.toString())).containsEntry("gate_code", "G1");
        assertThat(Instant.parse((String) byTicket.get(overridden.toString()).get("entered_at")))
                .isEqualTo(t0.plusSeconds(60));
        assertThat(byTicket.get(overridden.toString())).containsEntry("gate_code", "G2");
        assertThat(json).doesNotContain(someoneElses.toString()).doesNotContain(notScanned.toString())
                .doesNotContain(refused.toString());
    }

    // ---------- 15 s memo ----------

    @Test
    void theGatePartIsSharedWithin15SecondsWhileEntriesAreFresh() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(2)));
        Fixture f = s.f();
        seen(f.device(), Duration.ZERO);
        String phone = randomPhone();
        String fan = fanToken(phone);
        UUID ticket = held(f, user(phone));

        String first = load(fan, f.event());
        assertThat(levels(first)).containsExactly(Map.entry("G1", "LOW"));
        assertThat(JsonPath.<List<Object>>read(first, "$.entries")).isEmpty();

        admitted(s, f.device(), ticket(f, f.zone1(), "Fan Trois"), s.g1(), 230); // HIGH once recomputed
        scan(s, f.device(), ticket, s.g1(), "OK", Instant.now().minusSeconds(5));
        String second = load(fan, f.event());
        assertThat(JsonPath.<List<Object>>read(second, "$.gates")).isEqualTo(JsonPath.read(first, "$.gates"));
        assertThat(JsonPath.<List<String>>read(second, "$.entries[*].ticket_id")).containsExactly(ticket.toString());
    }
}
