package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-41): docs/04-api.md §Fan POST/GET /fan/queue, §Waiting room (Queue token, Admission, Eligibility,
 * Device, Limits); errors QUEUE_NOT_OPEN, NOT_ELIGIBLE, QUEUE_DEVICE_LIMIT, QUEUE_RATE_LIMITED; plan lot-5 Q140,
 * Q144, Q146, Q147. The test profile sets rooms.ip-limit-5m = 3.
 */
class WaitingRoomFanApiTest extends QueueFixture {

    private static final String[] SHAPE = {"event_id", "status", "sale_starts_at", "position", "in_room",
            "rate_per_min", "estimated_wait_s", "sold_out", "admission", "queue_token", "poll_after_s", "server_time"};

    private static void notOpen(ResultActions r, String reason) throws Exception {
        r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUEUE_NOT_OPEN"))
                .andExpect(jsonPath("$.details.reason").value(reason));
    }

    private static void unauthorized(ResultActions r) throws Exception {
        r.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---------- join ----------

    @Test
    void joinRefusedOutsideTheRoomAndForABadBody() throws Exception {
        Room r = room("{}");
        Fan f = fan();
        String json = body(join(f, r.event()));
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("QUEUE_NOT_OPEN");
        assertThat((Map<String, Object>) JsonPath.read(json, "$.details"))
                .containsExactly(Map.entry("reason", "NOT_STARTED"), Map.entry("opens_at", OPENS));

        join(f, 999_999_999).andExpect(status().isNotFound());
        join(f, r.event(), "too-short", "10.0.0.1").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.device_id").exists());
        join(f, r.event(), "bad chars!bad chars!", "10.0.0.1").andExpect(status().isUnprocessableContent());
        mvc.perform(post("/fan/queue").header("Authorization", "Bearer " + f.token())
                        .contentType("application/json").content("{\"device_id\": \"device-0123456789ab\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.event_id").exists());
        mvc.perform(post("/fan/queue").contentType("application/json")
                .content("{\"event_id\": %d, \"device_id\": \"device-0123456789ab\"}".formatted(r.event())))
                .andExpect(status().isUnauthorized());

        Room none = room("{}");
        jdbc.sql("delete from waiting_rooms where id = ?").param(none.id()).update();
        notOpen(join(f, none.event()), "NO_ROOM");

        tick(OPENS);
        setStatus(r.event(), "GATES_OPEN");
        notOpen(join(f, r.event()), "CLOSED");
        setStatus(r.event(), "ON_SALE");
        jdbc.sql("update waiting_rooms set status = 'ENDED' where id = ?").param(r.id()).update();
        notOpen(join(f, r.event()), "ENDED");
    }

    @Test
    void joinInPreQueueThenAgainIsTheSameEntryWithTheGetShape() throws Exception {
        Room r = room("{}");
        tick(OPENS);
        Fan f = fan();
        ResultActions first = join(f, r.event()).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"));
        String json = body(first);
        assertKeys(json, "$", SHAPE);
        assertThat((Integer) JsonPath.read(json, "$.event_id")).isEqualTo(r.event());
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("PRE_QUEUE");
        assertThat((String) JsonPath.read(json, "$.sale_starts_at")).isEqualTo(SALE);
        assertThat((Object) JsonPath.read(json, "$.position")).isNull();
        assertThat((Object) JsonPath.read(json, "$.estimated_wait_s")).isNull();
        assertThat((Object) JsonPath.read(json, "$.admission")).isNull();
        assertThat((Integer) JsonPath.read(json, "$.rate_per_min")).isZero();
        assertThat((Boolean) JsonPath.read(json, "$.sold_out")).isFalse();
        assertThat((Integer) JsonPath.read(json, "$.poll_after_s")).isBetween(1, 30);
        assertThat(Instant.parse(JsonPath.read(json, "$.server_time"))).isCloseTo(Instant.now(),
                org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
        String token = JsonPath.read(json, "$.queue_token");
        UUID entry = jdbc.sql("select id from queue_entries where room_id = ? and user_id = ?")
                .params(r.id(), f.id()).query(UUID.class).single();
        assertThat(claims(token)).containsEntry("sub", entry.toString());

        tick("2099-04-01T09:31:00Z");
        String again = body(join(f, r.event()).andExpect(status().isOk()));
        assertThat(claims(JsonPath.read(again, "$.queue_token"))).containsEntry("sub", entry.toString());
        assertThat((Integer) JsonPath.read(again, "$.in_room")).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from queue_entries where room_id = ?").param(r.id()).query(Long.class)
                .single()).isOne();

        String polled = body(poll(token).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertKeys(polled, "$", SHAPE);
        assertThat((String) JsonPath.read(polled, "$.status")).isEqualTo("PRE_QUEUE");
        assertThat((Integer) JsonPath.read(polled, "$.in_room")).isEqualTo(1);
    }

    // ---------- tokens ----------

    @Test
    void tokensCarryIdsOnlyAndAreNeverTakenForAnotherKind() throws Exception {
        Room r = room("{\"admission_rate_per_min\": 6000}");
        tick(OPENS);
        Fan f = fan();
        String phone = jdbc.sql("select phone_e164 from users where id = ?").param(f.id()).query(String.class).single();
        String device = "device-" + UUID.randomUUID();
        String unranked = JsonPath.read(body(join(f, r.event(), device, "10.0.9.1").andExpect(status().isCreated())),
                "$.queue_token");
        assertThat(claims(unranked).keySet()).containsExactlyInAnyOrder("iss", "aud", "sub", "evt", "exp");
        assertThat(claims(unranked)).containsEntry("iss", "jomhor").containsEntry("aud", "queue")
                .containsEntry("evt", r.event())
                .containsEntry("exp", Instant.parse(CUTOFF).getEpochSecond());
        assertThat(jwtHeader(unranked)).isEqualTo(Map.of("alg", "HS256"));

        tick(SALE);
        String ranked = JsonPath.read(body(poll(unranked).andExpect(status().isOk())), "$.queue_token");
        assertThat(claims(ranked).keySet()).containsExactlyInAnyOrder("iss", "aud", "sub", "evt", "exp", "rnk");
        assertThat(claims(ranked)).containsEntry("rnk", 1);

        tick("2099-04-01T10:00:10Z");
        String admitted = body(poll(ranked).andExpect(status().isOk()));
        String admission = JsonPath.read(admitted, "$.admission.token");
        assertThat(claims(admission).keySet()).containsExactlyInAnyOrder("aud", "sub", "evt", "ent", "exp");
        assertThat(claims(admission)).containsEntry("aud", "admission").containsEntry("sub", f.id().toString())
                .containsEntry("evt", r.event()).containsEntry("ent", claims(ranked).get("sub"));
        for (String t : List.of(unranked, ranked, admission)) {
            String decoded = claims(t).toString();
            assertThat(decoded).doesNotContain(phone).doesNotContain(phone.substring(1)).doesNotContain(device);
        }

        // A fan JWT, an admission token or a tampered rank is not a queue token; nothing is accepted without one.
        unauthorized(poll(f.token()));
        unauthorized(poll(admission));
        unauthorized(poll(withClaim(ranked, "\"rnk\":1", "\"rnk\":0")));
        unauthorized(poll("not.a.jwt"));
        unauthorized(mvc.perform(get("/fan/queue")));
        unauthorized(mvc.perform(get("/fan/queue").header("Authorization", "Bearer " + f.token())));
        // A queue or admission token is not a fan JWT.
        unauthorized(mvc.perform(get("/fan/me").header("Authorization", "Bearer " + ranked)));
        unauthorized(mvc.perform(get("/fan/me").header("Authorization", "Bearer " + admission)));
    }

    // ---------- device and IP limits ----------

    @Test
    void fourthAccountOnOneDeviceIsRefused() throws Exception {
        Room r = room("{}");
        tick(OPENS);
        String device = "device-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            join(fan(), r.event(), device, "10.1.0." + i).andExpect(status().isCreated());
        }
        join(fan(), r.event(), device, "10.1.0.9").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUEUE_DEVICE_LIMIT"))
                .andExpect(jsonPath("$.details.limit").value(3));
        join(fan(), r.event(), "device-" + UUID.randomUUID(), "10.1.0.9").andExpect(status().isCreated());
    }

    @Test
    void perIpLimitCountsNewEntriesOnlyInFiveMinutes() throws Exception {
        Room r = room("{}");
        tick(OPENS);
        String ip = "10.2." + ThreadLocalRandom.current().nextInt(256) + ".7";
        Fan first = fan();
        join(first, r.event(), "device-" + UUID.randomUUID(), ip).andExpect(status().isCreated());
        join(fan(), r.event(), "device-" + UUID.randomUUID(), ip).andExpect(status().isCreated());
        join(fan(), r.event(), "device-" + UUID.randomUUID(), ip).andExpect(status().isCreated());

        Fan fourth = fan();
        String device = "device-" + UUID.randomUUID();
        join(fourth, r.event(), device, ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUEUE_RATE_LIMITED"))
                .andExpect(jsonPath("$.details.reason").value("IP"))
                .andExpect(jsonPath("$.details.retry_after_s").value(60));
        // X-Forwarded-For from a peer that isn't a trusted proxy is ignored.
        mvc.perform(post("/fan/queue").header("Authorization", "Bearer " + fourth.token())
                        .header("X-Forwarded-For", "10.250.0.1").contentType("application/json")
                        .content("{\"event_id\": %d, \"device_id\": \"%s\"}".formatted(r.event(), device))
                        .with(q -> {
                            q.setRemoteAddr(ip);
                            return q;
                        }))
                .andExpect(status().isTooManyRequests());
        join(first, r.event(), "device-" + UUID.randomUUID(), ip).andExpect(status().isOk()); // a re-join: not counted
        join(fourth, r.event(), device, "10.3.0.1").andExpect(status().isCreated());

        // Five minutes later (the entries' joined_at moved back, as a fixed clock would).
        jdbc.sql("update queue_entries set joined_at = joined_at - interval '5 minutes' where room_id = ?")
                .param(r.id()).update();
        join(fan(), r.event(), "device-" + UUID.randomUUID(), ip).andExpect(status().isCreated());

        List<Map<String, Object>> rows = jdbc.sql("select ip_hash, length(ip_hash) as n, q::text as row "
                + "from queue_entries q where room_id = ?").param(r.id()).query().listOfRows();
        assertThat(rows).hasSize(5).allSatisfy(row -> {
            assertThat(row.get("n")).isEqualTo(32);
            assertThat((String) row.get("row")).doesNotContain(ip).doesNotContain("10.3.0.1");
        });
        assertThat(rows.stream().map(row -> Base64.getEncoder().encodeToString((byte[]) row.get("ip_hash"))).distinct())
                .hasSize(2);
    }

    // ---------- eligibility ----------

    @Test
    void eachEligibilityReasonWithItsDetails() throws Exception {
        Room age = room("{\"eligibility\": {\"min_account_age_days\": 7}}");
        Room member = room("{\"eligibility\": {\"member_joined_before\": \"2099-01-01T00:00:00Z\"}}");
        Room events = room("{\"eligibility\": {\"min_events_attended\": 3}}");
        tick(OPENS);
        Fan f = fan();

        jdbc.sql("update users set verified_at = '2099-03-26T10:00:00Z' where id = ?").param(f.id()).update();
        String json = body(join(f, age.event()).andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("NOT_ELIGIBLE");
        assertThat((Map<String, Object>) JsonPath.read(json, "$.details")).containsExactly(
                Map.entry("reason", "ACCOUNT_TOO_NEW"), Map.entry("verified_before", "2099-03-25T10:00:00Z"));
        jdbc.sql("update users set verified_at = '2099-03-25T10:00:00Z' where id = ?").param(f.id()).update();
        join(f, age.event()).andExpect(status().isCreated());

        jdbc.sql("insert into memberships (organizer_id, user_id, number, joined_at, left_at) "
                + "values (?, ?, 1, '2098-06-01T00:00:00Z', '2098-09-01T00:00:00Z')").params(member.admin().org(), f.id())
                .update();
        json = body(join(f, member.event()).andExpect(status().isConflict()));
        assertThat((Map<String, Object>) JsonPath.read(json, "$.details")).containsExactly(
                Map.entry("reason", "NOT_MEMBER"), Map.entry("member_joined_before", "2099-01-01T00:00:00Z"));
        jdbc.sql("update memberships set left_at = null where user_id = ?").param(f.id()).update();
        join(f, member.event()).andExpect(status().isCreated());

        attend(events, f.id(), 2);
        json = body(join(f, events.event()).andExpect(status().isConflict()));
        assertThat((Map<String, Object>) JsonPath.read(json, "$.details")).containsExactly(
                Map.entry("reason", "NOT_ENOUGH_EVENTS"), Map.entry("min_events_attended", 3),
                Map.entry("attended", 2));
        attend(events, f.id(), 1);
        join(f, events.event()).andExpect(status().isCreated());
    }

    /** {@code n} CLOSED events of the room's organizer, each with a ticket of {@code user} scanned IN. */
    private void attend(Room r, UUID user, int n) {
        UUID org = r.admin().org();
        UUID venue = jdbc.sql("select venue_id from events where id = ?").param(r.event()).query(UUID.class).single();
        int zone = jdbc.sql("select zone_id from price_categories where id = ?::uuid").param(r.price())
                .query(Integer.class).single();
        UUID gate = jdbc.sql("insert into gates (organizer_id, venue_id, code, name) values (?, ?, ?, 'Porte') "
                + "returning id").params(org, venue, "G" + ThreadLocalRandom.current().nextInt(1_000_000))
                .query(UUID.class).single();
        byte[] key = new byte[32];
        ThreadLocalRandom.current().nextBytes(key);
        UUID device = jdbc.sql("insert into devices (organizer_id, label, public_key, status) "
                + "values (?, 'PDA', ?, 'ACTIVE') returning id").params(org, key).query(UUID.class).single();
        UUID staff = jdbc.sql("insert into staff (organizer_id, name, role) values (?, 'Agent', 'GATE') returning id")
                .param(org).query(UUID.class).single();
        for (int i = 0; i < n; i++) {
            int e = jdbc.sql("insert into events (organizer_id, venue_id, event_type, title, starts_at, status, "
                            + "event_key_enc) values (?, ?, 'FOOTBALL_MATCH', 'Past', ?, 'CLOSED', ?) returning id")
                    .params(org, venue, Instant.now().minusSeconds(86_400L * (7 + i)).atOffset(ZoneOffset.UTC),
                            new byte[32])
                    .query(Integer.class).single();
            UUID price = jdbc.sql("insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, "
                    + "quota) values (?, ?, ?, 'Cat', 5000, 100) returning id").params(org, e, zone)
                    .query(UUID.class).single();
            UUID order = jdbc.sql("insert into orders (organizer_id, event_id, user_id, channel, status, total_mad, "
                    + "reserved_until) values (?, ?, ?, 'APP', 'PAID', 5000, now()) returning id")
                    .params(org, e, user).query(UUID.class).single();
            UUID ticket = jdbc.sql("insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, "
                            + "holder_user_id, holder_name, holder_initials) "
                            + "values (?, ?, ?, ?, ?, ?, 'Fan Un', 'F. U.') returning id")
                    .params(org, order, e, zone, price, user).query(UUID.class).single();
            jdbc.sql("insert into scans (scan_id, organizer_id, event_id, device_id, ticket_id, gate_id, staff_id, "
                            + "direction, result, token_version, device_time) "
                            + "values (uuidv7(), ?, ?, ?, ?, ?, ?, 'IN', 'OK', 1, now())")
                    .params(org, e, device, ticket, gate, staff).update();
        }
    }

    // ---------- helpers ----------

    private static Map<String, Object> jwtHeader(String jwt) {
        return JsonPath.read(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8),
                "$");
    }

    /** The token with its payload edited and the original signature kept. */
    private static String withClaim(String jwt, String from, String to) {
        String[] parts = jwt.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains(from);
        String edited = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.replace(from, to).getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + edited + "." + parts[2];
    }
}
