package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import ma.jomhor.sales.rooms.application.PollLimiter;
import ma.jomhor.sales.rooms.application.RoomTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract (BE-42): docs/04-api.md POST /fan/orders Lot 5 (admission first, split {@code pays} eligibility),
 * ADMISSION_REQUIRED, GET /fan/queue (per-entry poll limit, CORS for SHOP_ORIGIN), POST /org/pos/orders untouched.
 * The poll limit is on here (1 s, its default); the other room tests run with it off.
 */
@TestPropertySource(properties = "rooms.poll-min-interval=1s")
class AdmissionApiTest extends QueueFixture {

    private static final String SHOP = "https://shop.test";

    @Autowired
    RoomTokens tokens;

    @Autowired
    PollLimiter polls;

    /** This test's tick clock: every tick is 10 s after the previous one (rate 6,000/min admits 1,000 a tick). */
    private Instant clock = Instant.parse(SALE);

    private void nextTick() {
        clock = clock.plusSeconds(10);
        tick(clock.toString());
    }

    /** An OPEN room (no pre-queue) on an event whose sale window is open now: sale_starts_at moved to the past. */
    private Room openRoom(String eligibility) throws Exception {
        Room r = room("{\"pre_queue_min\": 0, \"admission_rate_per_min\": 6000, \"eligibility\": %s}"
                .formatted(eligibility));
        jdbc.sql("update events set sale_starts_at = now() - interval '1 minute' where id = ?").param(r.event())
                .update();
        nextTick();
        assertThat(roomRow(r.id())).containsEntry("status", "OPEN");
        return r;
    }

    private String queueToken(Room r, Fan f) throws Exception {
        return JsonPath.read(body(join(f, r.event()).andExpect(status().isCreated())), "$.queue_token");
    }

    /** {@code f} joins, a tick ranks and admits it, and its one poll returns the admission token. */
    private String admitted(Room r, Fan f) throws Exception {
        String queueToken = queueToken(r, f);
        nextTick();
        String json = body(poll(queueToken).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("ADMITTED");
        return JsonPath.read(json, "$.admission.token");
    }

    private UUID entryOf(Room r, Fan f) {
        return jdbc.sql("select id from queue_entries where room_id = ? and user_id = ?").params(r.id(), f.id())
                .query(UUID.class).single();
    }

    private ResultActions order(Fan f, Room r, String admission, int qty, String holders) throws Exception {
        MockHttpServletRequestBuilder req = post("/fan/orders").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"event_id": %d, "channel": "APP",
                         "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}"""
                        .formatted(r.event(), r.price(), qty, holders));
        return mvc.perform(admission == null ? req : req.header("X-Admission-Token", admission));
    }

    private ResultActions order(Fan f, Room r, String admission) throws Exception {
        return order(f, r, admission, 1, holders(1));
    }

    private static void admissionRequired(ResultActions r, String reason) throws Exception {
        String json = body(r.andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("ADMISSION_REQUIRED");
        assertThat((String) JsonPath.read(json, "$.details.reason")).isEqualTo(reason);
    }

    @Test
    void anOpenRoomNeedsTheCallersAdmissionTokenForThisEventDecidedBeforeAnySql() throws Exception {
        Room r = openRoom("{}");
        Fan f = fan();
        memo.get(r.event()); // warm this instance's memo right before counting
        long before = statements.count();
        admissionRequired(order(f, r, null), "MISSING");
        assertThat(statements.count() - before).isEqualTo(1); // the fan session lookup (SecurityConfig), nothing else

        String token = admitted(r, f);
        UUID entry = entryOf(r, f);
        Instant inTenMin = Instant.now().plusSeconds(600);
        Fan other = fan();
        Room elsewhere = openRoom("{}");
        String othersToken = tokens.admission(other.id(), r.event(), entry, inTenMin);
        admissionRequired(order(f, r, othersToken), "INVALID"); // another user's
        admissionRequired(order(f, r, tokens.admission(f.id(), elsewhere.event(), entry, inTenMin)), "INVALID");
        String[] mine = token.split("\\.");
        admissionRequired(order(f, r, mine[0] + "." + othersToken.split("\\.")[1] + "." + mine[2]), "INVALID");
        admissionRequired(order(f, r, tokens.queue(entry, r.event(), 1, inTenMin)), "INVALID"); // a queue token
        admissionRequired(order(f, r, "not-a-jwt"), "INVALID");
        admissionRequired(order(f, r, tokens.admission(f.id(), r.event(), entry, Instant.now().minusSeconds(1))),
                "EXPIRED");

        // Valid: several orders inside the turn, until the per-account limit.
        jdbc.sql("update events set per_account_limit = 2 where id = ?").param(r.event()).update();
        order(f, r, token).andExpect(status().isCreated());
        order(f, r, token).andExpect(status().isCreated());
        String json = body(order(f, r, token).andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("PER_ACCOUNT_LIMIT");
    }

    @Test
    void pausedStillNeedsATokenEndedNeedsNoneAndTheTillIsNeverAsked() throws Exception {
        Room r = openRoom("{}");
        Fan f = fan();
        String token = admitted(r, f);

        // POS during OPEN: no token, 201.
        call(r.admin(), post("/org/pos/sessions"), "{\"label\": \"Guichet 1\"}").andExpect(status().isCreated());
        call(r.admin(), post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d, "items": [{"price_category_id": "%s", "qty": 1, "holders": %s}]}
                """.formatted(UUID.randomUUID(), r.event(), r.price(), holders(1))).andExpect(status().isCreated());

        call(r.admin(), post("/org/events/" + r.event() + "/room/pause")).andExpect(status().isOk());
        order(f, r, token).andExpect(status().isCreated());
        Fan g = fan();
        admissionRequired(order(g, r, null), "MISSING");

        call(r.admin(), post("/org/events/" + r.event() + "/room/end")).andExpect(status().isOk());
        order(g, r, null).andExpect(status().isCreated());
    }

    @Test
    void aRoomStillScheduledPastItsOpeningFailsClosedAndTakesJoinsBeforeTheTickCatchesUp() throws Exception {
        Room r = room("{\"pre_queue_min\": 0, \"admission_rate_per_min\": 6000}");
        jdbc.sql("update events set sale_starts_at = now() - interval '1 minute' where id = ?").param(r.event())
                .update();
        memo.invalidate();
        assertThat(roomRow(r.id())).containsEntry("status", "SCHEDULED"); // no tick yet
        Fan f = fan();
        admissionRequired(order(f, r, null), "MISSING");

        String json = body(join(f, r.event()).andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("WAITING");
        assertThat((Object) JsonPath.read(json, "$.position")).isNull();
        nextTick(); // SCHEDULED → OPEN (admission counts from here) and ranks the join
        nextTick(); // admits it
        json = body(poll(JsonPath.read(json, "$.queue_token")).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("ADMITTED");
        order(f, r, JsonPath.read(json, "$.admission.token")).andExpect(status().isCreated());
    }

    @Test
    void aSplitPaysMemberMustPassTheRoomsRule() throws Exception {
        Room r = openRoom("{\"min_account_age_days\": 7}");
        Fan f = fan();
        jdbc.sql("update users set verified_at = now() - interval '30 days' where id = ?").param(f.id()).update();
        String token = admitted(r, f);

        String fresh = randomPhone(); // no account yet: created unverified by the order
        String json = body(order(f, r, token, 2, """
                [{"name": "Yazid Aqel"}, {"name": "Sara Bennani", "phone": "%s", "pays": true}]""".formatted(fresh))
                .andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("NOT_ELIGIBLE");
        // The holder path only: no reason, no rule figure (a buyer can't probe another phone's account).
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.details"))
                .containsExactly(Map.entry("field", "items[0].holders[1]"));
        assertThat(jdbc.sql("select count(*) from orders where event_id = ?").param(r.event()).query(Long.class)
                .single()).isZero();

        String verified = randomPhone();
        jdbc.sql("insert into users (phone_e164, verified_at) values (?, now() - interval '30 days')").param(verified)
                .update();
        order(f, r, token, 2, """
                [{"name": "Yazid Aqel"}, {"name": "Sara Bennani", "phone": "%s", "pays": true}]""".formatted(verified))
                .andExpect(status().isCreated());
    }

    @Test
    void aSecondPollOfOneEntryWithinOneSecondIs429WithoutSqlAndABadTokenNeverTouchesTheMap() throws Exception {
        Room r = openRoom("{}");
        String token = queueToken(r, fan());
        String other = queueToken(r, fan());
        poll(token).andExpect(status().isOk());

        long before = statements.count();
        String json = body(poll(token).andExpect(status().isTooManyRequests()));
        assertThat(statements.count()).isEqualTo(before);
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("QUEUE_RATE_LIMITED");
        assertThat((String) JsonPath.read(json, "$.details.reason")).isEqualTo("POLL");
        assertThat((Integer) JsonPath.read(json, "$.details.retry_after_s")).isEqualTo(1);
        poll(other).andExpect(status().isOk()); // another entry is its own

        int tracked = polls.size();
        poll("not-a-jwt").andExpect(status().isUnauthorized());
        String[] t = token.split("\\.");
        poll(t[0] + "." + other.split("\\.")[1] + "." + t[2]).andExpect(status().isUnauthorized());
        assertThat(polls.size()).isEqualTo(tracked);
    }

    // ---------- CORS on GET /fan/queue ----------

    private static boolean hasCorsHeaders(MockHttpServletResponse response) {
        return response.getHeaderNames().stream().anyMatch(h -> h.toLowerCase().startsWith("access-control-"));
    }

    @Test
    void theShopOriginMayPollTheQueueWithoutCredentialsAndNothingElseGetsCors() throws Exception {
        MockHttpServletResponse ok = mvc.perform(options("/fan/queue").header("Origin", SHOP)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-queue-token"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo(SHOP);
        assertThat(ok.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET");
        assertThat(ok.getHeader("Access-Control-Allow-Headers")).isEqualToIgnoringCase("X-Queue-Token");
        assertThat(ok.getHeader("Access-Control-Max-Age")).isEqualTo("7200");
        assertThat(ok.getHeader("Access-Control-Allow-Credentials")).isNull();

        MockHttpServletResponse refused = mvc.perform(options("/fan/queue").header("Origin", "https://evil.test")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-queue-token"))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertThat(hasCorsHeaders(refused)).isFalse();

        // Behind the /v1 context path, as deployed.
        assertThat(mvc.perform(options("/v1/fan/queue").contextPath("/v1").header("Origin", SHOP)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-queue-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Access-Control-Allow-Origin"))
                .isEqualTo(SHOP);

        // The poll itself carries the origin, so the shop can read even a 401.
        assertThat(mvc.perform(get("/fan/queue").header("Origin", SHOP).header("X-Queue-Token", "not-a-jwt"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse()
                .getHeader("Access-Control-Allow-Origin")).isEqualTo(SHOP);

        for (MockHttpServletRequestBuilder other : new MockHttpServletRequestBuilder[] {
                options("/fan/events").header("Access-Control-Request-Method", "GET"),
                options("/fan/orders").header("Access-Control-Request-Method", "POST"),
                get("/fan/events")}) {
            assertThat(hasCorsHeaders(mvc.perform(other.header("Origin", SHOP)).andReturn().getResponse()))
                    .isFalse();
        }

        // The join (POST /fan/queue, the shop's server calls it) is not CORS-checked, whatever its Origin.
        Room r = openRoom("{}");
        Fan f = fan();
        MockHttpServletResponse joined = join(f, r.event(), "device-" + UUID.randomUUID(), "10.9.9.9")
                .andReturn().getResponse();
        assertThat(joined.getStatus()).isEqualTo(201);
        assertThat(hasCorsHeaders(joined)).isFalse();
        MockHttpServletResponse crossOrigin = mvc.perform(post("/fan/queue").header("Origin", "https://evil.test")
                .header("Authorization", "Bearer " + f.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\": %d, \"device_id\": \"device-%s\"}".formatted(r.event(), UUID.randomUUID())))
                .andReturn().getResponse();
        assertThat(crossOrigin.getStatus()).isEqualTo(200); // the same fan's entry, not a 403 "Invalid CORS request"
        assertThat(hasCorsHeaders(crossOrigin)).isFalse();
    }

    @Test
    void anEmptyShopOriginFailsStartupOutsideDevAndTest() {
        var pg = IntegrationTest.POSTGRES;
        assertThatThrownBy(() -> SpringApplication.run(JomhorApplication.class,
                "--spring.profiles.active=staging",
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--" + IntegrationTest.JWT_SECRET,
                "--" + IntegrationTest.OTP_SECRET,
                "--" + IntegrationTest.EVENT_KEY_MASTER,
                "--jomhor.cors.shop-origin=").close())
                .hasStackTraceContaining("SHOP_ORIGIN must be set");
    }
}
