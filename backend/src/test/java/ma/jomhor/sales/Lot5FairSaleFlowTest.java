package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.waitlist.application.WaitlistService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Lot 5 step 2 exit proof, backend side (plan lot-5 §10.1 Exit (1)-(10), §10.5 BE-50): "Plein tarif" (ALL) next to
 * "Tarif membres" (MEMBERS); phase 1 "Prévente membres" (members who joined before a date), phase 2 "Vente générale".
 * In the presale a non-member is NOT_ELIGIBLE and sees the next phase, a member is admitted and buys at the member
 * price. Phase 2's opening ends phase 1 and kills the presale token. In phase 2 a non-member's member-price place is
 * MEMBERS_ONLY and an order naming a phone at its limit is HOLDER; the event sells out and the room ends; two fans join
 * the waitlist; an ADMIN refund frees one place and the buyer's limit (Q52); the job notifies the oldest fan only, who
 * buys it; a further release inside the cooldown notifies nobody; {@code loadtest/check.sql} finds no breach. The gap
 * between two phases (Q169) runs on a twin event, since ending this presale by hand would skip its auto-end. Tick and
 * waitlist job at fixed times (QueueFixture); card payments through the fake gateway (PaymentTest).
 */
class Lot5FairSaleFlowTest extends QueueFixture {

    private static final String PRESALE_CUTOFF = "2099-03-01T00:00:00Z";
    private static final String GENERAL_OPENS = "2099-04-02T09:30:00Z";
    private static final String GENERAL_SALE = "2099-04-02T10:00:00Z";
    private static final String NAME = "Yazid Aqel";

    @Autowired
    WaitlistService waitlist;

    @Autowired
    FanMessageService messages;

    /** Each fan's latest queue token (a poll refreshes it). */
    private final Map<UUID, String> queueTokens = new HashMap<>();

    @Test
    void presaleThenGeneralSaleMembersPricesHolderCapSoldOutWaitlistRefundAndNoBreach() throws Exception {
        // ---------- (1) B2: "Plein tarif" (ALL, 6 places at 50 DH) and "Tarif membres" (MEMBERS, 2 at 30 DH) ---
        Caller admin = admin();
        Caller ops = caller(admin.org(), "OPS");
        String venue = venue(admin, "Stade");
        int zone = zone(admin, venue, "NORD");
        int event = event(admin, venue);
        call(ops, patch("/org/events/" + event), "{\"sale_starts_at\": \"%s\"}".formatted(SALE))
                .andExpect(status().isOk());
        String full = price(ops, event, zone, "{\"name\": \"Plein tarif\", \"price_mad\": 5000, \"quota\": 6}");
        String members = price(ops, event, zone,
                "{\"name\": \"Tarif membres\", \"price_mad\": 3000, \"quota\": 2, \"audience\": \"MEMBERS\"}");
        call(admin, post("/org/events/" + event + "/publish")).andExpect(status().isOk());

        // ---------- (2) B13: phase 1 "Prévente membres" (joined before a date), phase 2 "Vente générale" ----------
        call(ops, put("/org/events/" + event + "/rooms/1"), """
                {"label": "Prévente membres", "pre_queue_min": 30, "admission_rate_per_min": 60,
                 "eligibility": {"member_joined_before": "%s"}}""".formatted(PRESALE_CUTOFF))
                .andExpect(status().isCreated());
        call(ops, put("/org/events/" + event + "/rooms/2"), """
                {"label": "Vente générale", "pre_queue_min": 30, "admission_rate_per_min": 60, "starts_at": "%s"}"""
                .formatted(GENERAL_SALE)).andExpect(status().isCreated());
        Room r = new Room(admin, event, full, roomId(event, 1));
        UUID general = roomId(event, 2);

        // ---------- (3) screen 2: the presale, then "Ensuite : Vente générale"; "dès" is an ALL price ----------
        tick(OPENS);
        String view = fanEvent(event);
        assertThat(JsonPath.<Map<String, Object>>read(view, "$.room")).containsEntry("phase", 1)
                .containsEntry("label", "Prévente membres").containsEntry("status", "PRE_QUEUE")
                .containsEntry("sale_starts_at", SALE);
        assertThat(JsonPath.<Map<String, Object>>read(view, "$.room.next")).containsOnly(Map.entry("phase", 2),
                Map.entry("label", "Vente générale"), Map.entry("opens_at", GENERAL_OPENS),
                Map.entry("sale_starts_at", GENERAL_SALE));
        assertThat((Integer) JsonPath.read(view, "$.from_price_mad")).isEqualTo(5000);
        assertThat(JsonPath.<List<String>>read(view, "$.zones[0].prices[?(@.price_category_id == '" + members
                + "')].audience")).containsExactly("MEMBERS");

        // ---------- (4) a non-member is refused; a member joins, is drawn, buys at the member price, pays ----------
        Fan karim = fan();
        String refused = body(join(karim, event, "device-karim-000001", "10.60.0.1").andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(refused, "$.code")).isEqualTo("NOT_ELIGIBLE");
        assertThat(JsonPath.<Map<String, Object>>read(refused, "$.details")).containsOnly(
                Map.entry("reason", "NOT_MEMBER"), Map.entry("member_joined_before", PRESALE_CUTOFF));

        Fan yazid = fan();
        mvc.perform(put("/fan/me/membership").header("Authorization", "Bearer " + yazid.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"organizer_id": "%s", "marketing_push": false, "marketing_whatsapp": false}"""
                                .formatted(admin.org())))
                .andExpect(status().isCreated());
        // The member price needs joined_at < the event's sale_starts_at, which moves to the real clock below.
        jdbc.sql("update memberships set joined_at = now() - interval '30 days' where user_id = ?").param(yazid.id())
                .update();
        String joined = body(join(yazid, event, "device-yazid-000001", "10.60.0.2").andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(joined, "$.status")).isEqualTo("PRE_QUEUE");
        queueTokens.put(yazid.id(), JsonPath.read(joined, "$.queue_token"));
        assertThat(claims(queueTokens.get(yazid.id()))).containsEntry("rm", r.id().toString());

        tick(SALE); // the draw
        tick("2099-04-01T10:00:10Z"); // 60/min: the one ranked fan is admitted
        String admitted = poll(yazid);
        assertThat((String) JsonPath.read(admitted, "$.status")).isEqualTo("ADMITTED");
        String presaleToken = JsonPath.read(admitted, "$.admission.token");
        String presaleQueue = queueTokens.get(yazid.id());

        // As in Lot5OnSaleFlowTest: the tick clock is in 2099, orders check the sale window on the real clock. A direct
        // update, since the API refuses it once phase 1 has started (ROOM_STARTED). Also the members-price cutoff.
        Instant saleStartsAt = jdbc.sql("""
                        update events set sale_starts_at = date_trunc('second', now()) - interval '1 minute'
                        where id = ? returning sale_starts_at""").param(event)
                .query(OffsetDateTime.class).single().toInstant();
        String order = body(place(yazid, event, presaleToken, item(members, h(null), h(null)))
                .andExpect(status().isCreated()));
        assertThat((Integer) JsonPath.read(order, "$.total_mad")).isEqualTo(6000);
        String presaleOrder = pay(yazid, order);
        assertThat(jdbc.sql("select count(*) from tickets where order_id = ?::uuid and status = 'VALID'")
                .param(presaleOrder).query(Long.class).single()).isEqualTo(2);

        // ---------- Q169 on a twin event: the presale ended by hand, phase 2 not open: orders wait, no SQL ----------
        Room twin = room("{}");
        phase(twin, 2, "{\"starts_at\": \"%s\"}".formatted(GENERAL_SALE));
        call(twin.admin(), post("/org/events/" + twin.event() + "/rooms/1/end")).andExpect(status().isOk());
        memo.invalidate();
        memo.rooms(twin.event()); // warm this instance's memo right before counting
        long before = statements.count();
        String gap = body(order(yazid, twin, presaleToken, 1).andExpect(status().isConflict()));
        assertThat(statements.count() - before).isEqualTo(1); // the fan session lookup only
        assertThat((String) JsonPath.read(gap, "$.code")).isEqualTo("EVENT_NOT_ON_SALE");
        assertThat(JsonPath.<Map<String, Object>>read(gap, "$.details")).containsOnly(
                Map.entry("reason", "NOT_STARTED"), Map.entry("sale_starts_at", GENERAL_SALE));

        // ---------- (5) phase 2's pre-queue opens: phase 1 ends by itself, the presale token dies ----------
        tick(GENERAL_OPENS);
        assertThat(roomRow(r.id())).containsEntry("status", "ENDED");
        assertThat(roomRow(general)).containsEntry("status", "PRE_QUEUE");
        String dead = body(place(yazid, event, presaleToken, item(full, h(null))).andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(dead, "$.code")).isEqualTo("ADMISSION_REQUIRED");
        assertThat((String) JsonPath.read(dead, "$.details.reason")).isEqualTo("INVALID");
        assertThat((String) JsonPath.read(body(poll(presaleQueue).andExpect(status().isOk())), "$.status"))
                .isEqualTo("ENDED");
        view = fanEvent(event);
        assertThat(JsonPath.<Map<String, Object>>read(view, "$.room")).containsEntry("phase", 2)
                .containsEntry("label", "Vente générale").containsEntry("status", "PRE_QUEUE")
                .containsEntry("sale_starts_at", GENERAL_SALE).containsEntry("next", null);

        // Every fan joins phase 2 (no rule), is drawn and admitted.
        Fan sara = fan();
        Fan omar = fan();
        List<Fan> fans = List.of(karim, sara, omar);
        for (int i = 0; i < fans.size(); i++) {
            String json = body(join(fans.get(i), event, "device-general-00000" + i, "10.61.0." + (i + 1))
                    .andExpect(status().isCreated()));
            assertThat(claims(JsonPath.read(json, "$.queue_token"))).containsEntry("rm", general.toString());
            queueTokens.put(fans.get(i).id(), JsonPath.read(json, "$.queue_token"));
        }
        tick(GENERAL_SALE);
        assertThat(jdbc.sql("""
                        select count(*) from audit_log
                        where action = 'room.end' and target = ? and actor_type = 'SYSTEM'
                          and payload = '{"reason": "NEXT_PHASE", "phase": 1}'::jsonb""")
                .param(String.valueOf(event)).query(Long.class).single()).isOne();
        tick("2099-04-02T10:00:10Z");
        assertThat(roomRow(general)).containsEntry("ranked_count", 3).containsEntry("admitted_through", 3);
        Map<UUID, String> tokens = new HashMap<>();
        for (Fan f : fans) {
            String json = poll(f);
            assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("ADMITTED");
            tokens.put(f.id(), JsonPath.read(json, "$.admission.token"));
        }

        // ---------- (6) a non-member picking "Tarif membres": MEMBERS_ONLY on that holder row ----------
        String membersOnly = body(place(karim, event, tokens.get(karim.id()), item(members, h(null)))
                .andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(membersOnly, "$.code")).isEqualTo("MEMBERS_ONLY");
        assertThat(JsonPath.<Map<String, Object>>read(membersOnly, "$.details")).containsOnly(
                Map.entry("field", "items[0].holders[0]"), Map.entry("member_joined_before", saleStartsAt.toString()));

        // ---------- (7) Sara buys 4 for Hamza; a fourth friend's order naming Hamza: HOLDER ----------
        String hamza = randomPhone();
        String saraOrder = pay(sara, body(place(sara, event, tokens.get(sara.id()),
                item(full, h(hamza), h(hamza), h(hamza), h(hamza))).andExpect(status().isCreated())));
        String holder = body(place(omar, event, tokens.get(omar.id()), item(full, h(null), h(hamza)))
                .andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(holder, "$.code")).isEqualTo("PER_ACCOUNT_LIMIT");
        assertThat(JsonPath.<Map<String, Object>>read(holder, "$.details")).containsOnly(
                Map.entry("reason", "HOLDER"), Map.entry("field", "items[0].holders[1]"), Map.entry("limit", 4));

        // ---------- (8) Omar takes the last 2 places: sold out; OPS ends the room; two fans join the waitlist ---
        pay(omar, body(place(omar, event, tokens.get(omar.id()), item(full, h(null), h(null)))
                .andExpect(status().isCreated())));
        tick("2099-04-02T10:00:20Z");
        assertThat(roomRow(general)).containsEntry("sold_out", true);
        String closed = body(waitlistJoin(karim, event).andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(closed, "$.code")).isEqualTo("WAITLIST_CLOSED");
        assertThat((String) JsonPath.read(closed, "$.details.reason")).isEqualTo("ROOM");

        call(ops, post("/org/events/" + event + "/rooms/2/end")).andExpect(status().isOk());
        memo.invalidate();
        view = fanEvent(event);
        assertThat((Object) JsonPath.read(view, "$.room")).isNull();
        assertThat((Boolean) JsonPath.read(view, "$.on_sale")).isTrue();
        assertThat((Boolean) JsonPath.read(view, "$.sold_out")).isTrue();
        assertThat((Object) JsonPath.read(view, "$.from_price_mad")).isNull();

        String pushToken = "fcm-" + UUID.randomUUID();
        mvc.perform(put("/fan/me/push-token").header("Authorization", "Bearer " + karim.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"%s\", \"platform\": \"android\"}".formatted(pushToken)))
                .andExpect(status().isNoContent());
        waitlistJoin(karim, event).andExpect(status().isCreated());
        Fan nadia = fan();
        waitlistJoin(nadia, event).andExpect(status().isCreated());

        // ---------- (9) Sara is at her limit; an ADMIN refunds one of her tickets ----------
        String atLimit = body(place(sara, event, null, item(full, h(null))).andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(atLimit, "$.code")).isEqualTo("PER_ACCOUNT_LIMIT");
        assertThat((Integer) JsonPath.read(atLimit, "$.details.held")).isEqualTo(4);
        UUID refunded = jdbc.sql("select id from tickets where order_id = ?::uuid order by id limit 1")
                .param(saraOrder).query(UUID.class).single();
        call(admin, post("/org/tickets/" + refunded + "/refund"), "{\"reason\": \"Geste commercial\"}")
                .andExpect(status().isOk());

        // One place back: the job notifies the oldest fan only, by push and WhatsApp.
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        waitlist.notifyAt(t);
        assertThat(notified(event)).containsExactly(karim.id());
        assertThat(jdbc.sql("select user_id, channel::text as channel, kind::text as kind from fan_messages "
                        + "where event_id = ? order by channel").param(event).query().listOfRows())
                .containsExactly(Map.of("user_id", karim.id(), "channel", "PUSH", "kind", "WAITLIST_OPEN"),
                        Map.of("user_id", karim.id(), "channel", "WHATSAPP", "kind", "WAITLIST_OPEN"));
        messages.deliverDue(Integer.MAX_VALUE);
        var push = ArgumentCaptor.forClass(PushSender.Push.class);
        verify(pushSender, atLeast(1)).send(push.capture());
        assertThat(push.getAllValues().stream().filter(p -> p.token().equals(pushToken)).toList()).singleElement()
                .satisfies(p -> {
                    assertThat(p.title()).isEqualTo("Des places se sont libérées");
                    assertThat(p.path()).isEqualTo("/events/" + event);
                });

        // Karim buys it (no room left: no token). Sara's refunded ticket no longer counts (Q52): 3 held, so 2 more is
        // over the limit and 1 more passes it, refused only because the place has gone.
        pay(karim, body(place(karim, event, null, item(full, h(null))).andExpect(status().isCreated())));
        String overLimit = body(place(sara, event, null, item(full, h(null), h(null)))
                .andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(overLimit, "$.code")).isEqualTo("PER_ACCOUNT_LIMIT");
        assertThat((Integer) JsonPath.read(overLimit, "$.details.held")).isEqualTo(3);
        String gone = body(place(sara, event, null, item(full, h(null))).andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(gone, "$.code")).isEqualTo("SOLD_OUT");

        // OPS releases one more place inside the 10 min cooldown: no notice; Sara buys her one more place.
        call(ops, patch("/org/events/" + event + "/prices/" + full), "{\"quota\": 7}").andExpect(status().isOk());
        waitlist.notifyAt(t.plus(Duration.ofMinutes(5)));
        assertThat(notified(event)).containsExactly(karim.id());
        pay(sara, body(place(sara, event, null, item(full, h(null))).andExpect(status().isCreated())));
        waitlist.notifyAt(t.plus(Duration.ofMinutes(10))); // after the cooldown, but sold out again: nobody
        assertThat(notified(event)).containsExactly(karim.id());
        assertThat(jdbc.sql("select count(*) from fan_messages where event_id = ?").param(event).query(Long.class)
                .single()).isEqualTo(2);

        // ---------- (10) check.sql: no oversell, double charge, limit breach or out-of-order admission ----------
        assertThat(breaches(event)).isEmpty();
    }

    // ---------- helpers ----------

    private UUID roomId(int event, int phase) {
        return jdbc.sql("select id from waiting_rooms where event_id = ? and phase = ?").params(event, phase)
                .query(UUID.class).single();
    }

    /** POST /org/events/{id}/prices with {@code json} plus the zone → its id. */
    private String price(Caller c, int event, int zone, String json) throws Exception {
        return JsonPath.read(body(call(c, post("/org/events/" + event + "/prices"),
                "{\"zone_id\": %d, %s".formatted(zone, json.substring(1))).andExpect(status().isCreated())), "$.id");
    }

    private String fanEvent(int event) throws Exception {
        return body(mvc.perform(get("/fan/events/" + event)).andExpect(status().isOk()));
    }

    private static String h(String phone) {
        return phone == null ? "{\"name\": \"%s\"}".formatted(NAME)
                : "{\"name\": \"%s\", \"phone\": \"%s\"}".formatted(NAME, phone);
    }

    private static String item(String priceId, String... holders) {
        return "{\"price_category_id\": \"%s\", \"qty\": %d, \"holders\": [%s]}"
                .formatted(priceId, holders.length, String.join(", ", holders));
    }

    /** POST /fan/orders of one item, with the admission token if any. */
    private ResultActions place(Fan f, int event, String admission, String item) throws Exception {
        MockHttpServletRequestBuilder req = post("/fan/orders").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\": %d, \"channel\": \"APP\", \"items\": [%s]}".formatted(event, item));
        return mvc.perform(admission == null ? req : req.header("X-Admission-Token", admission));
    }

    /** Pays the order (the POST /fan/orders response) by simulated card → PAID; its id. */
    private String pay(Fan f, String order) throws Exception {
        String orderId = JsonPath.read(order, "$.id");
        String payment = JsonPath.read(body(mvc.perform(post("/fan/orders/" + orderId + "/pay")
                .header("Authorization", "Bearer " + f.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\": \"CARD\"}")).andExpect(status().isOk())), "$.payment_id");
        callback(PaymentTest.approve(payment, ((Number) JsonPath.read(order, "$.total_mad")).longValue()))
                .andExpect(status().isSeeOther());
        assertThat(jdbc.sql("select status::text from orders where id = ?::uuid").param(orderId).query(String.class)
                .single()).isEqualTo("PAID");
        return orderId;
    }

    private ResultActions waitlistJoin(Fan f, int event) throws Exception {
        return mvc.perform(put("/fan/waitlist/" + event).header("Authorization", "Bearer " + f.token()));
    }

    /** The event's waitlist entries notified, oldest first. */
    private List<UUID> notified(int event) {
        return jdbc.sql("""
                        select user_id from waitlist_entries where event_id = ? and notified_at is not null
                        order by joined_at, id""").param(event).query(UUID.class).list();
    }

    /** Polls with the fan's latest queue token and keeps the refreshed one. */
    private String poll(Fan f) throws Exception {
        String json = body(poll(queueTokens.get(f.id())).andExpect(status().isOk()));
        queueTokens.put(f.id(), JsonPath.read(json, "$.queue_token"));
        return json;
    }
}
