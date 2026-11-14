package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.payments.PaymentTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Lot 5 step 1 exit proof, backend side (plan lot-5 §1 Demo-complete (1)-(6), §5 BE-46): a room for verified accounts
 * (≥ 7 days), a pre-queue of three fans and a refused 2-day account, the draw, admission in rank order at 6/min, the
 * first fan buys with the token and pays (simulated card), an order without a token is refused before any SQL, pause
 * and resume, an expired turn goes to the back, the category sells out and admission stops, the end lifts the token
 * rule, and {@code loadtest/check.sql} finds no breach on this event. The tick runs at fixed times (QueueFixture); the
 * fake card callback is the PaymentTest one. The 2-instance and kill parts of the demo are BE-43's and BE-45's.
 */
class Lot5OnSaleFlowTest extends QueueFixture {

    /** The tick clock: 10 s a step, so the room's 6/min admits one fan per step. */
    private int seconds;

    /** Each fan's latest queue token (a poll refreshes it). */
    private final Map<UUID, String> queueTokens = new HashMap<>();

    private void step() {
        seconds += 10;
        tick("2099-04-01T10:%02d:%02dZ".formatted(seconds / 60, seconds % 60));
    }

    @Test
    void preQueueDrawAdmissionBuyPauseExpiryRejoinSoldOutEndAndNoBreach() throws Exception {
        // ---------- (1) set-up: 4 places, pre-queue 30 min, 6 fans/min, "verified ≥ 7 d" ----------
        Room r = room("""
                {"pre_queue_min": 30, "admission_rate_per_min": 6, "eligibility": {"min_account_age_days": 7}}""", 4);

        // ---------- (2) pre-queue: 3 verified fans join, a 2-day account is refused ----------
        tick(OPENS);
        assertThat(roomRow(r.id())).containsEntry("status", "PRE_QUEUE");
        List<Fan> fans = List.of(fan(), fan(), fan());
        for (int i = 0; i < fans.size(); i++) {
            Fan f = fans.get(i);
            jdbc.sql("update users set verified_at = now() - interval '30 days' where id = ?").param(f.id()).update();
            String json = body(join(f, r.event(), "device-" + UUID.randomUUID(), "10.50.0." + (i + 1))
                    .andExpect(status().isCreated()));
            assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("PRE_QUEUE");
            assertThat((Object) JsonPath.read(json, "$.position")).isNull();
            queueTokens.put(f.id(), JsonPath.read(json, "$.queue_token"));
        }
        Fan young = fan();
        jdbc.sql("update users set verified_at = '2099-03-30T10:00:00Z' where id = ?").param(young.id()).update();
        String refused = body(join(young, r.event(), "device-" + UUID.randomUUID(), "10.50.0.9")
                .andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(refused, "$.code")).isEqualTo("NOT_ELIGIBLE");
        assertThat((String) JsonPath.read(refused, "$.details.reason")).isEqualTo("ACCOUNT_TOO_NEW");
        assertThat((String) JsonPath.read(refused, "$.details.verified_before")).isEqualTo("2099-03-25T10:00:00Z");

        tick("2099-04-01T09:31:00Z");
        String waiting = poll(fans.getFirst());
        assertThat((String) JsonPath.read(waiting, "$.status")).isEqualTo("PRE_QUEUE");
        assertThat((Integer) JsonPath.read(waiting, "$.in_room")).isEqualTo(3);

        // ---------- (3) draw at the sale start: every pre-queue entry ranked 1..3 at random ----------
        tick(SALE);
        assertThat(roomRow(r.id())).containsEntry("status", "OPEN").containsEntry("ranked_count", 3)
                .containsEntry("admitted_through", 0);
        List<Fan> byRank = fans.stream().sorted(Comparator.comparing(f -> rank(r, f))).toList();
        assertThat(byRank.stream().map(f -> rank(r, f))).containsExactly(1, 2, 3);
        Fan first = byRank.get(0);
        Fan second = byRank.get(1);
        Fan third = byRank.get(2);
        for (int rank = 1; rank <= 3; rank++) {
            String json = poll(byRank.get(rank - 1));
            assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("WAITING");
            assertThat((Integer) JsonPath.read(json, "$.position")).isEqualTo(rank);
        }

        // ---------- (4) admission in rank order, one per 10 s ----------
        step();
        assertThat(roomRow(r.id())).containsEntry("admitted_through", 1);
        String admitted = poll(first);
        assertThat((String) JsonPath.read(admitted, "$.status")).isEqualTo("ADMITTED");
        String firstToken = JsonPath.read(admitted, "$.admission.token");
        assertPosition(second, "WAITING", 1);
        assertPosition(third, "WAITING", 2);

        // The tick clock is in 2099; orders check the sale window on the real clock. A direct update, because the
        // API forbids it (PATCH sale_starts_at once the room has started → 409 ROOM_STARTED): the room keeps its 2099
        // draw, and verified_before moves with the sale start (now − 1 min − 7 d), so the rejoin below checks
        // eligibility on the moved date. That is why the eligible fans were verified 30 days back on the real clock.
        jdbc.sql("update events set sale_starts_at = now() - interval '1 minute' where id = ?").param(r.event())
                .update();
        String order = body(order(first, r, firstToken, 2).andExpect(status().isCreated()));
        String orderId = JsonPath.read(order, "$.id");
        String payment = JsonPath.read(body(mvc.perform(post("/fan/orders/" + orderId + "/pay")
                .header("Authorization", "Bearer " + first.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\": \"CARD\"}")).andExpect(status().isOk())), "$.payment_id");
        callback(PaymentTest.approve(payment, 10_000)).andExpect(status().isSeeOther());
        assertThat(jdbc.sql("select status::text from orders where id = ?::uuid").param(orderId).query(String.class)
                .single()).isEqualTo("PAID");
        assertThat(jdbc.sql("select count(*) from tickets where order_id = ?::uuid and status = 'VALID'")
                .param(orderId).query(Long.class).single()).isEqualTo(2);

        // ---------- (5) guard: no token → 409, decided before any SQL beyond the session check ----------
        memo.get(r.event()); // warm this instance's memo right before counting
        long before = statements.count();
        String guarded = body(order(second, r, null, 1).andExpect(status().isConflict()));
        assertThat(statements.count() - before).isEqualTo(1);
        assertThat((String) JsonPath.read(guarded, "$.code")).isEqualTo("ADMISSION_REQUIRED");
        assertThat((String) JsonPath.read(guarded, "$.details.reason")).isEqualTo("MISSING");

        // ---------- (6) controls: pause freezes positions, resume continues ----------
        call(r.admin(), post("/org/events/" + r.event() + "/room/pause")).andExpect(status().isOk());
        step();
        step();
        assertThat(roomRow(r.id())).containsEntry("status", "PAUSED").containsEntry("admitted_through", 1);
        String paused = poll(second);
        assertThat((String) JsonPath.read(paused, "$.status")).isEqualTo("PAUSED");
        assertThat((Integer) JsonPath.read(paused, "$.position")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(paused, "$.rate_per_min")).isZero();

        call(r.admin(), post("/org/events/" + r.event() + "/room/resume")).andExpect(status().isOk());
        step();
        assertThat(roomRow(r.id())).containsEntry("status", "OPEN").containsEntry("admitted_through", 2);
        assertThat((String) JsonPath.read(poll(second), "$.status")).isEqualTo("ADMITTED");
        assertPosition(third, "WAITING", 1);

        // The second fan's turn passes unused (10 min): EXPIRED, then a rejoin puts them at the back.
        jdbc.sql("update queue_entries set admitted_at = admitted_at - interval '11 minutes' "
                + "where room_id = ? and user_id = ?").params(r.id(), second.id()).update();
        String expired = poll(second);
        assertThat((String) JsonPath.read(expired, "$.status")).isEqualTo("EXPIRED");
        assertThat((Object) JsonPath.read(expired, "$.admission")).isNull();
        String rejoined = body(join(second, r.event(), "device-" + UUID.randomUUID(), "10.50.0.2")
                .andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(rejoined, "$.status")).isEqualTo("WAITING");
        queueTokens.put(second.id(), JsonPath.read(rejoined, "$.queue_token"));
        step();
        assertThat(rank(r, second)).isEqualTo(4);
        assertThat(roomRow(r.id())).containsEntry("admitted_through", 3);
        String thirdAdmitted = poll(third);
        assertThat((String) JsonPath.read(thirdAdmitted, "$.status")).isEqualTo("ADMITTED");
        assertPosition(second, "WAITING", 1);

        // ---------- sold out: the last 2 places are held, admission stops ----------
        order(third, r, JsonPath.read(thirdAdmitted, "$.admission.token"), 2).andExpect(status().isCreated());
        step();
        step();
        assertThat(roomRow(r.id())).containsEntry("sold_out", true).containsEntry("admitted_through", 3);
        String soldOut = poll(second);
        assertThat((Boolean) JsonPath.read(soldOut, "$.sold_out")).isTrue();
        assertThat((String) JsonPath.read(soldOut, "$.status")).isEqualTo("WAITING");

        // ---------- end: polls stop, orders need no token (OPS releases one more place first) ----------
        call(r.admin(), post("/org/events/" + r.event() + "/room/end")).andExpect(status().isOk());
        memo.invalidate();
        assertThat((String) JsonPath.read(poll(second), "$.status")).isEqualTo("ENDED");
        call(caller(r.admin().org(), "OPS"), patch("/org/events/" + r.event() + "/prices/" + r.price()),
                        "{\"quota\": 5}")
                .andExpect(status().isOk());
        order(second, r, null, 1).andExpect(status().isCreated());

        // ---------- check.sql: no oversell, double charge, limit breach or out-of-order admission ----------
        assertThat(breaches(r)).isEmpty();
        // It does see this event (BE-44's CheckSqlTest crafts every breach): the paid order flipped to EXPIRED keeps
        // its 2 VALID tickets and its SUCCEEDED card payment, and its places drop out of sold's count.
        jdbc.sql("update orders set status = 'EXPIRED' where id = ?::uuid").param(orderId).update();
        assertThat(breaches(r)).extracting(b -> b.get("breach"))
                .containsExactlyInAnyOrder("SOLD_MISMATCH", "TICKET_NOT_PAID", "CHARGED_NO_ORDER");
        jdbc.sql("update orders set status = 'PAID' where id = ?::uuid").param(orderId).update();
        assertThat(breaches(r)).isEmpty();
    }

    /** loadtest/check.sql, scoped to the room's event (the file is one statement). */
    private List<Map<String, Object>> breaches(Room r) throws Exception {
        String check = Files.readString(Path.of("loadtest/check.sql")).strip();
        assertThat(check).endsWith(";");
        return jdbc.sql("select * from (" + check.substring(0, check.length() - 1) + "\n) c where event_id = ?")
                .param(r.event()).query().listOfRows();
    }

    // ---------- helpers ----------

    private Integer rank(Room r, Fan f) {
        return (Integer) entry(r.id(), f.id()).get("rank");
    }

    /** Polls with the fan's latest queue token and keeps the refreshed one. */
    private String poll(Fan f) throws Exception {
        String json = body(poll(queueTokens.get(f.id())).andExpect(status().isOk()));
        queueTokens.put(f.id(), JsonPath.read(json, "$.queue_token"));
        return json;
    }

    private void assertPosition(Fan f, String status, int position) throws Exception {
        String json = poll(f);
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo(status);
        assertThat((Integer) JsonPath.read(json, "$.position")).isEqualTo(position);
    }

    private ResultActions order(Fan f, Room r, String admission, int qty) throws Exception {
        MockHttpServletRequestBuilder req = post("/fan/orders").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"event_id": %d, "channel": "APP",
                         "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}"""
                        .formatted(r.event(), r.price(), qty, holders(qty)));
        return mvc.perform(admission == null ? req : req.header("X-Admission-Token", admission));
    }

    /** The simulated gateway's signed callback (PaymentTest#callback). */
    private ResultActions callback(Map<String, String> fields) throws Exception {
        var req = post("/payments/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        fields.forEach(req::param);
        return mvc.perform(req);
    }
}
