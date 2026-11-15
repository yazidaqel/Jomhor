package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import ma.jomhor.catalog.OrgApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-40, BE-48): docs/04-api.md §Organizer GET /org/events/{id}/rooms, PUT /rooms/{phase} and
 * pause/resume/end per phase, §Waiting room Phases; plan lot-5 Q141, Q142, Q144, Q154, Q167-Q169. Statuses past
 * SCHEDULED and the tick's counts are set as rows here.
 */
class WaitingRoomOrgApiTest extends OrgApiTest {

    static final String SALE = "2099-04-01T10:00:00Z";
    private static final String[] ROOM = {"event_id", "phase", "label", "starts_at", "status", "pre_queue_min",
            "opens_at", "sale_starts_at", "admission_rate_per_min", "eligibility", "drawn_at", "figures"};
    private static final String[] FIGURES = {"joined", "waiting", "admitted", "admitted_last_min", "active_holds",
            "max_active_holds", "paid", "quota", "remaining", "sold_out", "estimated_drain_s"};
    private static final String[] RULE = {"min_account_age_days", "member_joined_before", "min_events_attended"};

    record Setup(Caller admin, int event, String price) {}

    @Autowired
    DataSource dataSource;

    /** An ON_SALE event (starts 2099-05-01T19:00Z, sale opens {@link #SALE}) with one category of 100. */
    private Setup onSale() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int zone = zone(a, venue, "NORD");
        int event = event(a, venue);
        call(a, patch("/org/events/" + event), "{\"sale_starts_at\": \"%s\"}".formatted(SALE))
                .andExpect(status().isOk());
        String price = price(a, event, zone, "Cat 1", 100);
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        return new Setup(a, event, price);
    }

    private ResultActions putRoom(Caller c, int event, String json) throws Exception {
        return call(c, put("/org/events/" + event + "/rooms/1"), json);
    }

    private ResultActions action(Caller c, int event, String action) throws Exception {
        return call(c, post("/org/events/" + event + "/rooms/1/" + action));
    }

    private void roomStatus(int event, String status) {
        jdbc.sql("update waiting_rooms set status = ?::room_status where event_id = ? and phase = 1")
                .params(status, event).update();
    }

    private List<Map<String, Object>> audits(int event, String action) {
        return jdbc.sql("select actor_type::text as actor_type, payload::text as payload from audit_log "
                        + "where action = ? and target = ? order by id")
                .params(action, String.valueOf(event)).query().listOfRows();
    }

    private static void roomStatus(ResultActions r, String status) throws Exception {
        r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_STATUS"))
                .andExpect(jsonPath("$.details.status").value(status));
    }

    // ---------- create, read ----------

    @Test
    void nullBeforeThenPutCreatesWithDefaultsAndTheGetShape() throws Exception {
        Setup s = onSale();
        assertThat(body(call(s.admin(), get("/org/events/" + s.event() + "/rooms")).andExpect(status().isOk())))
                .isEqualTo("{\"rooms\":[]}");

        String created = body(putRoom(s.admin(), s.event(), "{}").andExpect(status().isCreated()));
        assertKeys(created, "$", ROOM);
        assertKeys(created, "$.eligibility", RULE);
        assertKeys(created, "$.figures", FIGURES);
        assertThat((Integer) JsonPath.read(created, "$.event_id")).isEqualTo(s.event());
        assertThat((Integer) JsonPath.read(created, "$.phase")).isEqualTo(1);
        assertThat((Object) JsonPath.read(created, "$.label")).isNull();
        assertThat((Object) JsonPath.read(created, "$.starts_at")).isNull();
        assertThat((String) JsonPath.read(created, "$.status")).isEqualTo("SCHEDULED");
        assertThat((Integer) JsonPath.read(created, "$.pre_queue_min")).isEqualTo(30);
        assertThat((Integer) JsonPath.read(created, "$.admission_rate_per_min")).isEqualTo(500);
        assertThat((String) JsonPath.read(created, "$.sale_starts_at")).isEqualTo(SALE);
        assertThat((String) JsonPath.read(created, "$.opens_at")).isEqualTo("2099-04-01T09:30:00Z");
        assertThat((Object) JsonPath.read(created, "$.drawn_at")).isNull();
        assertThat(((Map<String, Object>) JsonPath.read(created, "$.eligibility")).values()).containsOnlyNulls();
        Map<String, Object> figures = JsonPath.read(created, "$.figures");
        assertThat(figures).containsEntry("joined", 0).containsEntry("waiting", 0).containsEntry("admitted", 0)
                .containsEntry("admitted_last_min", 0).containsEntry("active_holds", 0)
                .containsEntry("max_active_holds", 2000).containsEntry("paid", 0).containsEntry("quota", 100)
                .containsEntry("remaining", 100).containsEntry("sold_out", false)
                .containsEntry("estimated_drain_s", null);
        assertThat(body(call(s.admin(), get("/org/events/" + s.event() + "/rooms")).andExpect(status().isOk())))
                .isEqualTo("{\"rooms\":[" + created + "]}");

        List<Map<String, Object>> audit = audits(s.event(), "room.create");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor_type")).isEqualTo("STAFF");
        Map<String, Object> payload = JsonPath.read((String) audit.getFirst().get("payload"), "$");
        assertThat(payload.keySet()).containsExactlyInAnyOrder("phase", "label", "starts_at", "pre_queue_min",
                "admission_rate_per_min", "min_account_age_days", "member_joined_before", "min_events_attended");
        assertThat(payload).containsEntry("pre_queue_min", 30).containsEntry("admission_rate_per_min", 500);
    }

    @Test
    void putReplacesWhileScheduledWithTheRule() throws Exception {
        Setup s = onSale();
        putRoom(s.admin(), s.event(), "{}").andExpect(status().isCreated());
        String json = body(putRoom(s.admin(), s.event(), """
                {"pre_queue_min": 0, "admission_rate_per_min": 800,
                 "eligibility": {"min_account_age_days": 7, "member_joined_before": "2099-01-01T00:00:00Z",
                                 "min_events_attended": 3}}""").andExpect(status().isOk()));
        assertThat((Integer) JsonPath.read(json, "$.pre_queue_min")).isZero();
        assertThat((String) JsonPath.read(json, "$.opens_at")).isEqualTo(SALE);
        assertThat((Integer) JsonPath.read(json, "$.admission_rate_per_min")).isEqualTo(800);
        assertThat((Integer) JsonPath.read(json, "$.eligibility.min_account_age_days")).isEqualTo(7);
        assertThat((String) JsonPath.read(json, "$.eligibility.member_joined_before"))
                .isEqualTo("2099-01-01T00:00:00Z");
        assertThat((Integer) JsonPath.read(json, "$.eligibility.min_events_attended")).isEqualTo(3);
        Map<String, Object> payload = JsonPath.read((String) audits(s.event(), "room.update").getFirst().get("payload"),
                "$");
        assertThat(payload).hasSize(8).containsEntry("phase", 1).containsEntry("label", null)
                .containsEntry("starts_at", null);
        assertThat(payload).contains(Map.entry("pre_queue_min", 0), Map.entry("admission_rate_per_min", 800),
                Map.entry("min_account_age_days", 7),
                Map.entry("member_joined_before", "2099-01-01T00:00:00Z"), Map.entry("min_events_attended", 3));
    }

    @Test
    void saleStartMustBeSetAndInTheFutureAtCreation() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int event = event(a, venue);
        putRoom(a, event, "{}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reason").value("SALE_START_REQUIRED"))
                .andExpect(jsonPath("$.details.fields.sale_starts_at").exists());

        jdbc.sql("update events set sale_starts_at = now() - interval '1 minute' where id = ?").param(event).update();
        putRoom(a, event, "{}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("SALE_START_REQUIRED"));
        assertThat(jdbc.sql("select count(*) from waiting_rooms where event_id = ?").param(event).query(Long.class)
                .single()).isZero();

        jdbc.sql("update events set sale_starts_at = now() + interval '1 day' where id = ?").param(event).update();
        putRoom(a, event, "{}").andExpect(status().isCreated()); // DRAFT is allowed
    }

    @Test
    void rangesPer03AreEachA422() throws Exception {
        Setup s = onSale();
        for (String json : List.of("{\"pre_queue_min\": -1}", "{\"pre_queue_min\": 1441}",
                "{\"admission_rate_per_min\": 0}", "{\"admission_rate_per_min\": 6001}",
                "{\"eligibility\": {\"min_account_age_days\": 0}}", "{\"eligibility\": {\"min_account_age_days\": 366}}",
                "{\"eligibility\": {\"min_events_attended\": 0}}", "{\"eligibility\": {\"min_events_attended\": 51}}",
                "{\"eligibility\": {\"member_joined_before\": \"2099-04-01T10:00:01Z\"}}",
                "{\"admission_rate_per_min\": 50.5}")) {
            putRoom(s.admin(), s.event(), json).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        String json = body(putRoom(s.admin(), s.event(), """
                {"pre_queue_min": 1441, "admission_rate_per_min": 0,
                 "eligibility": {"min_account_age_days": 366, "member_joined_before": "2099-05-01T00:00:00Z",
                                 "min_events_attended": 51}}""").andExpect(status().isUnprocessableContent()));
        assertKeys(json, "$.details.fields", "pre_queue_min", "admission_rate_per_min",
                "eligibility.min_account_age_days", "eligibility.member_joined_before", "eligibility.min_events_attended");
        // The edges are accepted.
        putRoom(s.admin(), s.event(), """
                {"pre_queue_min": 1440, "admission_rate_per_min": 6000,
                 "eligibility": {"min_account_age_days": 365, "member_joined_before": "2099-04-01T10:00:00Z",
                                 "min_events_attended": 50}}""").andExpect(status().isCreated());
        putRoom(s.admin(), s.event(), "{\"pre_queue_min\": 0, \"admission_rate_per_min\": 1, "
                + "\"eligibility\": {\"min_account_age_days\": 1, \"min_events_attended\": 1}}")
                .andExpect(status().isOk());
    }

    @Test
    void anEventNotDraftOrOnSaleIsNotEditable() throws Exception {
        Setup s = onSale();
        setStatus(s.event(), "CLOSED");
        putRoom(s.admin(), s.event(), "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
    }

    // ---------- after SCHEDULED ----------

    @Test
    void afterPreQueueOnlyTheRateMayChange() throws Exception {
        Setup s = onSale();
        putRoom(s.admin(), s.event(), "{\"eligibility\": {\"min_account_age_days\": 7}}")
                .andExpect(status().isCreated());
        roomStatus(s.event(), "PRE_QUEUE");

        roomStatus(putRoom(s.admin(), s.event(), "{\"pre_queue_min\": 45, \"eligibility\": {\"min_account_age_days\": 7}}"),
                "PRE_QUEUE");
        roomStatus(putRoom(s.admin(), s.event(), "{\"eligibility\": {\"min_account_age_days\": 8}}"), "PRE_QUEUE");
        roomStatus(putRoom(s.admin(), s.event(), "{}"), "PRE_QUEUE"); // the rule dropped is a change too

        String json = body(putRoom(s.admin(), s.event(), """
                {"pre_queue_min": 30, "admission_rate_per_min": 800, "eligibility": {"min_account_age_days": 7}}""")
                .andExpect(status().isOk()));
        assertThat((Integer) JsonPath.read(json, "$.admission_rate_per_min")).isEqualTo(800);
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("PRE_QUEUE");
        assertThat(audits(s.event(), "room.update")).hasSize(1);
    }

    /** Opened by time but not yet ticked: the rules are already frozen (WaitingRoom.statusAt). */
    @Test
    void aRoomOpenByTimeWithALateTickStillRefusesRuleChanges() throws Exception {
        Setup s = onSale();
        putRoom(s.admin(), s.event(), "{}").andExpect(status().isCreated());
        jdbc.sql("update events set sale_starts_at = now() + interval '10 minutes' where id = ?").param(s.event())
                .update(); // pre-queue (30 min) opened 20 min ago; the room row still says SCHEDULED
        assertThat(jdbc.sql("select status::text from waiting_rooms where event_id = ?").param(s.event())
                .query(String.class).single()).isEqualTo("SCHEDULED");

        roomStatus(putRoom(s.admin(), s.event(), "{\"pre_queue_min\": 45}"), "PRE_QUEUE");
        roomStatus(putRoom(s.admin(), s.event(), "{\"eligibility\": {\"min_account_age_days\": 7}}"), "PRE_QUEUE");
        putRoom(s.admin(), s.event(), "{\"admission_rate_per_min\": 800}").andExpect(status().isOk());
    }

    @Test
    void pauseResumeEndTransitionsAndAudits() throws Exception {
        Setup s = onSale();
        Caller a = s.admin();
        int e = s.event();
        action(a, e, "pause").andExpect(status().isNotFound()); // no room yet
        putRoom(a, e, "{}").andExpect(status().isCreated());

        roomStatus(action(a, e, "pause"), "SCHEDULED");
        roomStatus(action(a, e, "resume"), "SCHEDULED");
        roomStatus(setAndCall(e, "PRE_QUEUE", a, "pause"), "PRE_QUEUE");

        roomStatus(e, "OPEN");
        roomStatus(action(a, e, "resume"), "OPEN");
        String paused = body(action(a, e, "pause").andExpect(status().isOk()));
        assertKeys(paused, "$", ROOM);
        assertThat((String) JsonPath.read(paused, "$.status")).isEqualTo("PAUSED");
        roomStatus(action(a, e, "pause"), "PAUSED");
        action(a, e, "resume").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        action(a, e, "end").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));

        roomStatus(action(a, e, "end"), "ENDED");
        roomStatus(action(a, e, "resume"), "ENDED");
        roomStatus(action(a, e, "pause"), "ENDED");
        roomStatus(putRoom(a, e, "{}"), "ENDED");
        call(a, get("/org/events/" + e + "/rooms")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].status").value("ENDED"));

        for (String action : List.of("room.pause", "room.resume", "room.end")) {
            List<Map<String, Object>> rows = audits(e, action);
            assertThat(rows).as(action).hasSize(1);
            assertThat((String) rows.getFirst().get("payload")).as(action).isEqualTo("{\"phase\": 1}");
        }
    }

    private ResultActions setAndCall(int event, String status, Caller c, String action) throws Exception {
        roomStatus(event, status);
        return action(c, event, action);
    }

    @Test
    void endWorksFromScheduled() throws Exception {
        Setup s = onSale();
        putRoom(s.admin(), s.event(), "{}").andExpect(status().isCreated());
        action(s.admin(), s.event(), "end").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
    }

    // ---------- phases (BE-48) ----------

    private ResultActions putPhase(Caller c, int event, int phase, String json) throws Exception {
        return call(c, put("/org/events/" + event + "/rooms/" + phase), json);
    }

    private static void phaseOrder(ResultActions r, String field) throws Exception {
        String json = body(r.andExpect(status().isUnprocessableContent()));
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat((String) JsonPath.read(json, "$.details.reason")).isEqualTo("PHASE_ORDER");
        assertKeys(json, "$.details.fields", field);
    }

    @Test
    void phasesAreCreatedInOrderEachWithItsOwnStartAndLabel() throws Exception {
        Setup s = onSale();
        Caller a = s.admin();
        int e = s.event();
        // Phase 1 follows the event's sale start: no starts_at.
        putPhase(a, e, 1, "{\"starts_at\": \"2099-04-02T10:00:00Z\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.starts_at").exists());
        // Phase 3 without phase 2: PHASE_ORDER, no field of the body.
        String json = body(putPhase(a, e, 3, "{\"starts_at\": \"2099-04-03T10:00:00Z\"}")
                .andExpect(status().isUnprocessableContent()));
        assertThat((String) JsonPath.read(json, "$.details.reason")).isEqualTo("PHASE_ORDER");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.details.fields")).doesNotContainKeys("starts_at",
                "pre_queue_min");
        putPhase(a, e, 2, "{\"starts_at\": \"2099-04-02T10:00:00Z\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("PHASE_ORDER")); // no phase 1 yet

        String one = body(putPhase(a, e, 1, "{\"label\": \"  Pr\u00e9vente membres  \"}")
                .andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(one, "$.label")).isEqualTo("Pr\u00e9vente membres"); // trimmed
        assertThat((Object) JsonPath.read(one, "$.starts_at")).isNull();

        // Phase 2: starts_at required, in the future, before the event's starts_at - 60 min; label 1-40.
        putPhase(a, e, 2, "{}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.starts_at").exists());
        putPhase(a, e, 2, "{\"starts_at\": \"2020-01-01T10:00:00Z\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.starts_at").exists());
        putPhase(a, e, 2, "{\"starts_at\": \"2099-05-01T18:00:01Z\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.starts_at").exists());
        for (String label : List.of("x".repeat(41), "\uD83C\uDFDF".repeat(41), "   ")) {
            putPhase(a, e, 2, "{\"label\": \"%s\", \"starts_at\": \"2099-04-02T10:00:00Z\"}".formatted(label))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.label").exists());
        }
        // The order: opens_at(2) = starts_at - pre_queue_min must be after phase 1's sale start (SALE).
        phaseOrder(putPhase(a, e, 2, "{\"starts_at\": \"%s\"}".formatted(SALE)), "starts_at");
        phaseOrder(putPhase(a, e, 2, "{\"pre_queue_min\": 30, \"starts_at\": \"2099-04-01T10:30:00Z\"}"),
                "pre_queue_min");
        assertThat(jdbc.sql("select count(*) from waiting_rooms where event_id = ?").param(e).query(Long.class)
                .single()).isEqualTo(1);

        String two = body(putPhase(a, e, 2, """
                {"label": "Vente g\u00e9n\u00e9rale", "pre_queue_min": 29, "starts_at": "2099-04-01T10:30:00Z"}""")
                .andExpect(status().isCreated()));
        assertKeys(two, "$", ROOM);
        assertThat(JsonPath.<Map<String, Object>>read(two, "$")).containsEntry("phase", 2)
                .containsEntry("label", "Vente g\u00e9n\u00e9rale").containsEntry("starts_at", "2099-04-01T10:30:00Z")
                .containsEntry("sale_starts_at", "2099-04-01T10:30:00Z").containsEntry("opens_at", "2099-04-01T10:01:00Z")
                .containsEntry("status", "SCHEDULED");
        // Phase 2 can't move before phase 3's opening either.
        putPhase(a, e, 3, "{\"pre_queue_min\": 0, \"starts_at\": \"2099-04-03T10:00:00Z\"}")
                .andExpect(status().isCreated());
        phaseOrder(putPhase(a, e, 2, "{\"starts_at\": \"2099-04-03T10:00:00Z\"}"), "starts_at");
        putPhase(a, e, 2, "{\"label\": null, \"starts_at\": \"2099-04-02T10:00:00Z\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.label").isEmpty());

        String list = body(call(a, get("/org/events/" + e + "/rooms")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(list, "$.rooms[*].phase")).containsExactly(1, 2, 3);
        assertKeys(list, "$.rooms[1].figures", FIGURES);
        Map<String, Object> audit = JsonPath.read((String) audits(e, "room.update").getLast().get("payload"), "$");
        assertThat(audit).containsEntry("phase", 2).containsEntry("label", null)
                .containsEntry("starts_at", "2099-04-02T10:00:00Z");

        putPhase(a, e, 6, "{\"starts_at\": \"2099-04-04T10:00:00Z\"}").andExpect(status().isNotFound());
        putPhase(a, e, 0, "{}").andExpect(status().isNotFound());
        call(a, post("/org/events/" + e + "/rooms/4/end")).andExpect(status().isNotFound()); // no such phase
        call(a, put("/org/events/" + e + "/room"), "{}").andExpect(status().isNotFound()); // step 1's path is gone
        call(a, get("/org/events/" + e + "/room")).andExpect(status().isNotFound());
    }

    /** 03 char_length counts code points: 40 stadium emoji (80 UTF-16 units) are a valid label. */
    @Test
    void theLabelLimitCountsCodePoints() throws Exception {
        Setup s = onSale();
        String stadiums = "\uD83C\uDFDF".repeat(40);
        putRoom(s.admin(), s.event(), "{\"label\": \"%s\"}".formatted(stadiums)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value(stadiums));
    }

    /**
     * B2: the order is checked against the nearest phases that aren't ENDED. Phase 2 ended, phase 3 must open after
     * phase 1's sale start, and the event PATCH must keep phase 1's start before phase 3's opening.
     */
    @Test
    void thePhaseOrderSkipsEndedPhases() throws Exception {
        Setup s = onSale();
        Caller a = s.admin();
        int e = s.event();
        putRoom(a, e, "{}").andExpect(status().isCreated());
        putPhase(a, e, 2, "{\"starts_at\": \"2099-04-02T10:00:00Z\"}").andExpect(status().isCreated());
        call(a, post("/org/events/" + e + "/rooms/2/end")).andExpect(status().isOk());

        phaseOrder(putPhase(a, e, 3, "{\"pre_queue_min\": 30, \"starts_at\": \"2099-04-01T10:10:00Z\"}"),
                "pre_queue_min");
        phaseOrder(putPhase(a, e, 3, "{\"pre_queue_min\": 0, \"starts_at\": \"2099-04-01T09:50:00Z\"}"),
                "starts_at");
        putPhase(a, e, 3, "{\"pre_queue_min\": 5, \"starts_at\": \"2099-04-01T10:10:00Z\"}")
                .andExpect(status().isCreated()); // opens 10:05, after phase 1's 10:00

        String json = body(call(a, patch("/org/events/" + e), "{\"sale_starts_at\": \"2099-04-01T10:05:00Z\"}")
                .andExpect(status().isUnprocessableContent()));
        assertThat((String) JsonPath.read(json, "$.details.reason")).isEqualTo("PHASE_ORDER");
        assertKeys(json, "$.details.fields", "sale_starts_at");
        call(a, patch("/org/events/" + e), "{\"sale_starts_at\": \"2099-04-01T10:04:00Z\"}")
                .andExpect(status().isOk());
    }

    /**
     * B1: a PUT on an existing phase locks the event row, then the phase's row ({@code for no key update}), before it
     * reads or writes anything of the room. With the row held for share elsewhere, the PUT waits at that lock
     * statement (not at its update), and goes through once the row is released.
     */
    @Test
    void aPutLocksThePhaseRowBeforeReadingIt() throws Exception {
        Setup s = onSale();
        putRoom(s.admin(), s.event(), "{}").andExpect(status().isCreated());
        UUID room = jdbc.sql("select id from waiting_rooms where event_id = ? and phase = 1").param(s.event())
                .query(UUID.class).single();
        try (var pool = Executors.newSingleThreadExecutor(); Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false); // closed first, so pool.close() never waits on a held row
            try (var st = holder.prepareStatement("select 1 from waiting_rooms where id = ? for share")) {
                st.setObject(1, room);
                st.executeQuery().close();
            }
            Future<Integer> put = pool.submit(() -> putRoom(s.admin(), s.event(), "{\"admission_rate_per_min\": 700}")
                    .andReturn().getResponse().getStatus());
            String waiting = null;
            for (int i = 0; i < 200 && waiting == null; i++) {
                waiting = jdbc.sql("""
                                select query from pg_stat_activity
                                where datname = current_database() and wait_event_type = 'Lock'
                                  and query like '%waiting_rooms%'""")
                        .query(String.class).optional().orElse(null);
                if (waiting == null) {
                    Thread.sleep(50);
                }
            }
            assertThat(waiting).startsWith("select status::text from waiting_rooms").contains("for no key update");
            assertThat(put.isDone()).isFalse();
            holder.commit();
            assertThat(put.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
    }

    @Test
    void controlsArePerPhaseAndAStartedPhaseFreezesItsLabelAndStart() throws Exception {
        Setup s = onSale();
        Caller a = s.admin();
        int e = s.event();
        putRoom(a, e, "{}").andExpect(status().isCreated());
        String body2 = "{\"label\": \"G\", \"starts_at\": \"2099-04-02T10:00:00Z\"}";
        putPhase(a, e, 2, body2).andExpect(status().isCreated());

        roomStatus(e, "OPEN");
        call(a, post("/org/events/" + e + "/rooms/2/pause")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.status").value("SCHEDULED"));
        String paused = body(action(a, e, "pause").andExpect(status().isOk()));
        assertThat(JsonPath.<Map<String, Object>>read(paused, "$")).containsEntry("phase", 1)
                .containsEntry("status", "PAUSED");
        call(a, post("/org/events/" + e + "/rooms/2/end")).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value(2)).andExpect(jsonPath("$.status").value("ENDED"));
        assertThat(audits(e, "room.end").getFirst().get("payload")).isEqualTo("{\"phase\": 2}");

        jdbc.sql("update waiting_rooms set status = 'OPEN' where event_id = ? and phase = 2").param(e).update();
        roomStatus(putPhase(a, e, 2, "{\"label\": \"H\", \"starts_at\": \"2099-04-02T10:00:00Z\"}"), "OPEN");
        roomStatus(putPhase(a, e, 2, "{\"label\": \"G\", \"starts_at\": \"2099-04-02T11:00:00Z\"}"), "OPEN");
        putPhase(a, e, 2, body2.replace("{", "{\"admission_rate_per_min\": 900, ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.admission_rate_per_min").value(900));
    }

    // ---------- figures ----------

    @Test
    void figuresFromTheTickCountsHoldsSalesAndStock() throws Exception {
        Setup s = onSale();
        putRoom(s.admin(), s.event(), "{}").andExpect(status().isCreated());
        jdbc.sql("""
                update waiting_rooms set status = 'OPEN', joined_count = 50, ranked_count = 40, admitted_through = 25,
                                         measured_rate_per_min = 10, drawn_at = '2099-04-01T10:00:00Z'
                where event_id = ?""").param(s.event()).update();
        // 10 entries the tick has not ranked yet.
        for (int i = 0; i < 10; i++) {
            UUID u = jdbc.sql("insert into users (phone_e164) values (?) returning id").param(randomPhone())
                    .query(UUID.class).single();
            jdbc.sql("""
                    insert into queue_entries (organizer_id, room_id, user_id, device_hash, ip_hash, draw_key)
                    select organizer_id, id, ?, ?, ?, 0 from waiting_rooms where event_id = ?""")
                    .params(u, new byte[32], new byte[32], s.event()).update();
        }
        setSold(s.price(), 10);
        UUID fan = jdbc.sql("insert into users (phone_e164) values (?) returning id").param(randomPhone())
                .query(UUID.class).single();
        jdbc.sql("insert into orders (organizer_id, event_id, user_id, channel, status, total_mad, reserved_until) "
                        + "values (?, ?, ?, 'APP', 'RESERVED', 5000, now() + interval '8 minutes')")
                .params(s.admin().org(), s.event(), fan).update();

        String json = body(call(s.admin(), get("/org/events/" + s.event() + "/rooms")).andExpect(status().isOk()));
        Map<String, Object> f = JsonPath.read(json, "$.rooms[0].figures");
        // waiting = (40 - 25) ranked + 10 unranked entries; drain = 25 × 60 / 10
        assertThat(f).containsEntry("joined", 50).containsEntry("waiting", 25).containsEntry("admitted", 25)
                .containsEntry("admitted_last_min", 10).containsEntry("active_holds", 1).containsEntry("quota", 100)
                .containsEntry("remaining", 90).containsEntry("sold_out", false).containsEntry("estimated_drain_s", 150);
        assertThat((String) JsonPath.read(json, "$.rooms[0].drawn_at")).isEqualTo("2099-04-01T10:00:00Z");

        setSold(s.price(), 100);
        call(s.admin(), get("/org/events/" + s.event() + "/rooms"))
                .andExpect(jsonPath("$.rooms[0].figures.remaining").value(0))
                .andExpect(jsonPath("$.rooms[0].figures.sold_out").value(true));
    }

    // ---------- roles and tenants ----------

    @Test
    void financeReadsCashierAndGateAre403() throws Exception {
        Setup s = onSale();
        putRoom(s.admin(), s.event(), "{}").andExpect(status().isCreated());
        Caller finance = caller(s.admin().org(), "FINANCE");
        call(finance, get("/org/events/" + s.event() + "/rooms")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].status").value("SCHEDULED"));
        putRoom(finance, s.event(), "{}").andExpect(status().isForbidden());
        action(finance, s.event(), "end").andExpect(status().isForbidden());
        for (String role : List.of("CASHIER", "GATE", "SUPERVISOR")) {
            Caller c = caller(s.admin().org(), role);
            call(c, get("/org/events/" + s.event() + "/rooms")).andExpect(status().isForbidden());
            putRoom(c, s.event(), "{}").andExpect(status().isForbidden());
        }
        Caller ops = caller(s.admin().org(), "OPS");
        putRoom(ops, s.event(), "{\"admission_rate_per_min\": 700}").andExpect(status().isOk());
        action(ops, s.event(), "end").andExpect(status().isOk());
    }

    @Test
    void anotherOrganizersEventIs404() throws Exception {
        Setup s = onSale();
        putRoom(s.admin(), s.event(), "{}").andExpect(status().isCreated());
        Caller b = admin();
        call(b, get("/org/events/" + s.event() + "/rooms")).andExpect(status().isNotFound());
        putRoom(b, s.event(), "{}").andExpect(status().isNotFound());
        for (String action : List.of("pause", "resume", "end")) {
            action(b, s.event(), action).andExpect(status().isNotFound());
        }
        call(b, get("/org/events/999999999/rooms")).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select status::text from waiting_rooms where event_id = ?").param(s.event())
                .query(String.class).single()).isEqualTo("SCHEDULED");
    }
}
