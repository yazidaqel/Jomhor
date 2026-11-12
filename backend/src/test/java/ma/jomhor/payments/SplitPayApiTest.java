package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract (BE-29): docs/04-api.md §Split pay, POST /fan/orders {@code pays} + {@code group}, GET /fan/orders/{id}
 * {@code group}, GET /fan/orders {@code group_role} and order, /pay {@code channel} + /return (P77), and the split
 * holds (expiry, cash mirror P78, P79, settle extension, P83).
 */
class SplitPayApiTest extends PaymentTest {

    private static final String BUYER_NAME = "Yazid Aqel";
    private static final String KARIM = "Karim Alaoui";
    private static final String NADIA = "Nadia Berrada";

    @Autowired
    OrderService orderService;

    @Autowired
    PlatformTransactionManager transactions;

    /** The buyer and two friends, each with a phone; the friends' tokens are made on demand (they log in later). */
    private record Fans(String buyerPhone, String buyer, String f1, String f2) {}

    private Fans fans() throws Exception {
        String phone = randomPhone();
        return new Fans(phone, fanToken(phone), randomPhone(), randomPhone());
    }

    private static String h(String name) {
        return "{\"name\": \"%s\"}".formatted(name);
    }

    private static String h(String name, String phone, boolean pays) {
        return "{\"name\": \"%s\", \"phone\": \"%s\", \"pays\": %s}".formatted(name, phone, pays);
    }

    private ResultActions place(String token, int eventId, String items) throws Exception {
        return fan(token, post("/fan/orders"), "{\"event_id\": %d, \"channel\": \"APP\", \"items\": [%s]}"
                .formatted(eventId, items));
    }

    private static String item(String priceId, String... holders) {
        return "{\"price_category_id\": \"%s\", \"qty\": %d, \"holders\": [%s]}"
                .formatted(priceId, holders.length, String.join(", ", holders));
    }

    /** 1 own place + one {@code pays} place per friend phone; returns the POST body. */
    private String split(Fans f, Sale s) throws Exception {
        return body(place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME), h(KARIM, f.f1(), true),
                h(NADIA, f.f2(), true))).andExpect(status().isCreated()));
    }

    private String memberOf(String buyerOrderId, String phone) {
        return jdbc.sql("""
                select o.id::text from orders o join users u on u.id = o.user_id
                where o.group_order_id = ?::uuid and u.phone_e164 = ?""").params(buyerOrderId, phone)
                .query(String.class).single();
    }

    private void due(String... orderIds) {
        for (String id : orderIds) {
            jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(id)
                    .update();
        }
    }

    private boolean sameHold(String a, String b) {
        return jdbc.sql("select (select reserved_until from orders where id = ?::uuid) = "
                + "(select reserved_until from orders where id = ?::uuid)").params(a, b).query(Boolean.class).single();
    }

    /** The member's reserved_until = the buyer's paid_at + 2 h (one transaction: the same now()). */
    private boolean heldTwoHoursAfterPaid(String member, String buyer) {
        return jdbc.sql("select (select reserved_until from orders where id = ?::uuid) = "
                + "(select paid_at + interval '2 hours' from orders where id = ?::uuid)").params(member, buyer)
                .query(Boolean.class).single();
    }

    private void cardPaid(String token, String orderId, long amountMad) throws Exception {
        callback(approve(payment(token, orderId), amountMad)).andExpect(status().isSeeOther());
    }

    private void setCashPoints(Sale s) {
        jdbc.sql("update organizers set settings = settings || jsonb_build_object('cash_points', ?::jsonb) where id = ?")
                .params("[{\"name\": \"Guichet Stade\"}]", s.org().org()).update();
    }

    private String payCash(String token, String orderId) throws Exception {
        return JsonPath.read(body(fan(token, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk())), "$.pay_code");
    }

    private static void noPii(String json, String... secrets) {
        for (String secret : secrets) {
            assertThat(json).doesNotContain(secret);
        }
        assertThat(json).doesNotContain("Alaoui").doesNotContain("Berrada").doesNotContain("Aqel");
    }

    private static void invalid(ResultActions r, String field, String reason) throws Exception {
        r.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reason").value(reason))
                .andExpect(jsonPath("$.details.fields['" + field + "']").exists());
    }

    // ---------- POST /fan/orders ----------

    @Test
    void oneOwnAndTwoPaysMakesTwoMemberOrders() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        String json = split(f, s);
        assertKeys(json, "$", "id", "status", "reserved_until", "total_mad", "items", "group");
        String buyer = JsonPath.read(json, "$.id");
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(5000);
        assertThat(JsonPath.<Integer>read(json, "$.items[0].qty")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(json, "$.items[0].holders[*].label")).containsExactly("Yazid A.");
        assertKeys(json, "$.group", "role", "members");
        assertThat(JsonPath.<String>read(json, "$.group.role")).isEqualTo("INITIATOR");
        assertKeys(json, "$.group.members[0]", "order_id", "holder_label", "places", "total_mad", "status",
                "reserved_until", "pay_url");
        noPii(json, f.f1(), f.f2(), f.buyerPhone());

        String m1 = memberOf(buyer, f.f1());
        String m2 = memberOf(buyer, f.f2());
        List<String> ids = new ArrayList<>(List.of(m1, m2));
        ids.sort(null);
        assertThat(JsonPath.<List<String>>read(json, "$.group.members[*].order_id")).containsExactlyElementsOf(ids);
        assertThat(JsonPath.<List<String>>read(json, "$.group.members[*].pay_url"))
                .containsExactlyElementsOf(ids.stream().map(id -> "http://shop.test/orders/" + id).toList());
        assertThat(JsonPath.<List<String>>read(json, "$.group.members[*].holder_label"))
                .containsExactlyInAnyOrder("Karim A.", "Nadia B.");
        assertThat(JsonPath.<List<Integer>>read(json, "$.group.members[*].places")).containsExactly(1, 1);
        assertThat(JsonPath.<List<Integer>>read(json, "$.group.members[*].total_mad")).containsExactly(5000, 5000);
        assertThat(JsonPath.<List<String>>read(json, "$.group.members[*].status")).containsExactly("RESERVED", "RESERVED");
        for (String m : ids) {
            assertThat(jdbc.sql("""
                    select m.status || ' ' || m.channel || ' ' || (m.event_id = b.event_id) || ' '
                           || (m.organizer_id = b.organizer_id) || ' ' || (m.reserved_until = b.reserved_until)
                    from orders m join orders b on b.id = m.group_order_id where m.id = ?::uuid""").param(m)
                    .query(String.class).single()).isEqualTo("RESERVED APP true true true");
        }
        assertThat(sold(s.priceId())).isEqualTo(3);
        assertThat(total(buyer) + total(m1) + total(m2)).isEqualTo(3 * 5000L);

        // GET: the buyer sees INITIATOR, the member MEMBER with the buyer's label only.
        String got = body(fan(f.buyer(), get("/fan/orders/" + buyer)).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(got, "$.group.role")).isEqualTo("INITIATOR");
        assertThat(JsonPath.<List<String>>read(got, "$.group.members[*].order_id")).containsExactlyElementsOf(ids);
        noPii(got, f.f1(), f.f2(), f.buyerPhone());
        fan(f.buyer(), get("/fan/orders/" + m1)).andExpect(status().isNotFound()); // Q115: not the owner

        String friend = fanToken(f.f1());
        String mine = body(fan(friend, get("/fan/orders/" + m1)).andExpect(status().isOk()));
        assertKeys(mine, "$.group", "role", "initiator_label", "initiator_status");
        assertThat(JsonPath.<String>read(mine, "$.group.role")).isEqualTo("MEMBER");
        assertThat(JsonPath.<String>read(mine, "$.group.initiator_label")).isEqualTo("Yazid A.");
        assertThat(JsonPath.<String>read(mine, "$.group.initiator_status")).isEqualTo("RESERVED");
        assertThat(JsonPath.<Integer>read(mine, "$.total_mad")).isEqualTo(5000);
        assertThat(JsonPath.<List<String>>read(mine, "$.items[0].holders[*].label")).containsExactly("Karim A.");
        noPii(mine, f.f1(), f.f2(), f.buyerPhone());

        // GET /fan/orders: group_role; the friend's own newer order comes after their member order still held.
        String own = order(friend, s, 1);
        String list = body(fan(friend, get("/fan/orders")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(list, "$.orders[*].id")).containsExactly(m1, own);
        assertThat(JsonPath.<List<Object>>read(list, "$.orders[*].group_role")).containsExactly("MEMBER", null);
        noPii(list, f.f1(), f.f2(), f.buyerPhone());
        String buyerList = body(fan(f.buyer(), get("/fan/orders")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(buyerList, "$.orders[*].group_role")).containsExactly("INITIATOR");
        noPii(buyerList, f.f1(), f.f2(), f.buyerPhone());

        // A plain order: group null.
        String plain = body(fan(friend, get("/fan/orders/" + own)).andExpect(status().isOk()));
        assertThat(JsonPath.<Object>read(plain, "$.group")).isNull();
    }

    /** Q106 + one member order per phone: two pays places in two categories, and the same phone without pays. */
    @Test
    void onePhonePayingTwoPlacesGetsOneOrderWithTwoItems() throws Exception {
        Sale s = onSale(10);
        String other = price(s.org(), s.eventId(), s.zoneId(), "Tarif B", 10);
        Fans f = fans();
        String json = body(place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME), h(KARIM, f.f1(), true),
                h(KARIM, f.f1(), false)) + ", " + item(other, h(KARIM, f.f1(), true))).andExpect(status().isCreated()));
        String buyer = JsonPath.read(json, "$.id");
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(10000); // own + the friend's non-pays place
        assertThat(JsonPath.<List<Object>>read(json, "$.group.members")).hasSize(1);
        assertThat(JsonPath.<Integer>read(json, "$.group.members[0].places")).isEqualTo(2);
        String m = memberOf(buyer, f.f1());
        assertThat(total(m)).isEqualTo(10000);
        assertThat(jdbc.sql("select count(*) from order_items where order_id = ?::uuid").param(m).query(Integer.class)
                .single()).isEqualTo(2);
        assertThat(sold(s.priceId()) + sold(other)).isEqualTo(4);
    }

    @Test
    void paysNeedsAnotherPhoneAndTheBuyerKeepsAPlace() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        invalid(place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME), "{\"name\": \"Karim Alaoui\", \"pays\": true}")),
                "items[0].holders[1].pays", "PHONE_REQUIRED");
        invalid(place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME), h(KARIM, f.buyerPhone(), true))),
                "items[0].holders[1].pays", "PHONE_REQUIRED");
        invalid(place(f.buyer(), s.eventId(), item(s.priceId(), h(KARIM, f.f1(), true), h(NADIA, f.f2(), true))),
                "items", "NO_BUYER_PLACE");
        // Per holder: name before pays.
        place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME), "{\"name\": \"K\", \"pays\": true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields['items[0].holders[1].name']").exists());
        assertThat(sold(s.priceId())).isZero();
    }

    @Test
    void splitIsRefusedWhenSalesCloseWithinTwoHours() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        jdbc.sql("update events set refund_cutoff_at = now() + interval '1 hour 59 minutes' where id = ?")
                .param(s.eventId()).update();
        invalid(place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME), h(KARIM, f.f1(), true))),
                "items[0].holders[1].pays", "TOO_LATE");
        order(f.buyer(), s, 1); // no split: fine
        jdbc.sql("update events set refund_cutoff_at = now() + interval '2 hours 1 minute' where id = ?")
                .param(s.eventId()).update();
        place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME), h(KARIM, f.f1(), true)))
                .andExpect(status().isCreated());
    }

    @Test
    void aMemberOverTheirLimitIsRefusedWithTheField() throws Exception {
        Sale s = onSale(20);
        Fans f = fans();
        String friend = fanToken(f.f1());
        order(friend, s, 4); // the friend is at the limit (4)
        place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME), h(NADIA, f.f2(), true), h(KARIM, f.f1(), true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT"))
                .andExpect(jsonPath("$.details.field").value("items[0].holders[2]"))
                .andExpect(jsonPath("$.details.held").value(4))
                .andExpect(jsonPath("$.details.limit").value(4));
        assertThat(sold(s.priceId())).isEqualTo(4);
    }

    /** P83: unpaid member places count toward the initiator until the members pay. */
    @Test
    void unpaidMemberPlacesCountTowardTheInitiator() throws Exception {
        Sale s = onSale(20);
        setCashPoints(s);
        Fans f = fans();
        String f3 = randomPhone();
        String buyer = JsonPath.read(body(place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME),
                h(KARIM, f.f1(), true), h(NADIA, f.f2(), true), h("Omar Idrissi", f3, true)))
                .andExpect(status().isCreated())), "$.id");
        place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT")).andExpect(jsonPath("$.details.held").value(4));
        String m1 = memberOf(buyer, f.f1());
        String friend = fanToken(f.f1());
        payCash(friend, m1); // AWAITING_CASH: still unpaid, still counted
        assertThat(orderStatus(m1)).isEqualTo("AWAITING_CASH");
        place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT")).andExpect(jsonPath("$.details.held").value(4));
        cardPaid(friend, m1, 5000);
        for (String phone : List.of(f.f2(), f3)) {
            cardPaid(fanToken(phone), memberOf(buyer, phone), 5000);
        }
        place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME))).andExpect(status().isCreated());
    }

    // ---------- holds and expiry ----------

    @Test
    void membersWaitForTheBuyerThenExpireWithItExactlyOnce() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        String buyer = JsonPath.read(split(f, s), "$.id");
        String m1 = memberOf(buyer, f.f1());
        String m2 = memberOf(buyer, f.f2());
        due(m1);
        orderService.expireDue();
        assertThat(orderStatus(m1)).isEqualTo("RESERVED"); // the buyer's order still holds
        due(buyer, m2);

        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> passes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                passes.add(pool.submit(() -> {
                    start.await();
                    return orderService.expireDue();
                }));
            }
            start.countDown();
            for (var p : passes) {
                p.get();
            }
        }
        for (String id : List.of(buyer, m1, m2)) {
            assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        }
        assertThat(sold(s.priceId())).isZero();
        orderService.expireDue();
        assertThat(sold(s.priceId())).isZero();
    }

    @Test
    void buyerCardSuccessExtendsMembersOnceCappedAtTheCutoff() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        String buyer = JsonPath.read(split(f, s), "$.id");
        String m1 = memberOf(buyer, f.f1());
        String m2 = memberOf(buyer, f.f2());
        Map<String, String> approval = approve(payment(f.buyer(), buyer), 5000);
        callback(approval).andExpect(status().isSeeOther());
        assertThat(orderStatus(buyer)).isEqualTo("PAID");
        assertThat(heldTwoHoursAfterPaid(m1, buyer)).isTrue();
        assertThat(heldTwoHoursAfterPaid(m2, buyer)).isTrue();
        assertThat(tickets(buyer)).isEqualTo(1); // the buyer never pays for a member's place

        jdbc.sql("update orders set reserved_until = reserved_until + interval '5 minutes' where id = ?::uuid")
                .param(m1).update();
        callback(approval).andExpect(status().isSeeOther()); // a replayed callback moves nothing
        assertThat(heldTwoHoursAfterPaid(m1, buyer)).isFalse();
        assertThat(heldTwoHoursAfterPaid(m2, buyer)).isTrue();

        // Sales close in 1 h 30: the members' deadline is the cutoff.
        Fans g = fans();
        String buyer2 = JsonPath.read(split(g, s), "$.id");
        jdbc.sql("update events set refund_cutoff_at = now() + interval '90 minutes' where id = ?").param(s.eventId())
                .update();
        cardPaid(g.buyer(), buyer2, 5000);
        assertThat(jdbc.sql("""
                select bool_and(o.reserved_until = e.refund_cutoff_at) from orders o join events e on e.id = o.event_id
                where o.group_order_id = ?::uuid""").param(buyer2).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aMemberPaysBeforeTheBuyerAndGetsTheTicketAlone() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        String buyer = JsonPath.read(split(f, s), "$.id");
        String m1 = memberOf(buyer, f.f1());
        String friend = fanToken(f.f1());
        cardPaid(friend, m1, 5000);
        assertThat(orderStatus(m1)).isEqualTo("PAID");
        assertThat(orderStatus(buyer)).isEqualTo("RESERVED");
        assertThat(jdbc.sql("select t.holder_user_id = o.user_id from tickets t join orders o on o.id = t.order_id "
                + "where t.order_id = ?::uuid").param(m1).query(Boolean.class).single()).isTrue();
        String friendTicket = jdbc.sql("select id::text from tickets where order_id = ?::uuid").param(m1)
                .query(String.class).single();
        assertThat(body(fan(friend, get("/fan/tickets")).andExpect(status().isOk()))).contains(friendTicket);
        String mine = body(fan(friend, get("/fan/orders/" + m1)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(mine, "$.ticket_ids")).containsExactly(friendTicket);

        cardPaid(f.buyer(), buyer, 5000);
        String got = body(fan(f.buyer(), get("/fan/orders/" + buyer)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(got, "$.ticket_ids")).hasSize(1).doesNotContain(friendTicket);
        assertThat(JsonPath.<List<String>>read(got, "$.group.members[*].status")).contains("PAID", "RESERVED");
        String buyerTickets = body(fan(f.buyer(), get("/fan/tickets")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(buyerTickets, "$.tickets")).hasSize(1);
        assertThat(buyerTickets).doesNotContain(friendTicket);
    }

    @Test
    void aMemberPastItsOwnDeadlineGivesOnePlaceBack() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        String buyer = JsonPath.read(split(f, s), "$.id");
        cardPaid(f.buyer(), buyer, 5000);
        String m1 = memberOf(buyer, f.f1());
        due(m1);
        orderService.expireDue();
        assertThat(orderStatus(m1)).isEqualTo("EXPIRED");
        assertThat(orderStatus(memberOf(buyer, f.f2()))).isEqualTo("RESERVED");
        assertThat(sold(s.priceId())).isEqualTo(2);
    }

    /** P78: the buyer's cash code holds the members too; a member pays by card meanwhile. */
    @Test
    void buyerCashCodeMirrorsTheMembersHoldThenTheGuichetExtendsThem() throws Exception {
        Sale s = onSale(10);
        setCashPoints(s);
        Fans f = fans();
        String buyer = JsonPath.read(split(f, s), "$.id");
        String m1 = memberOf(buyer, f.f1());
        String m2 = memberOf(buyer, f.f2());
        String code = payCash(f.buyer(), buyer);
        assertThat(sameHold(m1, buyer)).isTrue();
        assertThat(sameHold(m2, buyer)).isTrue();
        // Minute 9: placed 9 min ago, so the members' first 8-minute hold has run out; the mirror keeps them.
        jdbc.sql("update orders set created_at = created_at - interval '9 minutes' where id = ?::uuid or "
                + "group_order_id = ?::uuid").params(buyer, buyer).update();
        assertThat(jdbc.sql("select bool_and(reserved_until > created_at + interval '9 minutes' and reserved_until > now() "
                + "+ interval '23 hours') from orders where group_order_id = ?::uuid").param(buyer)
                .query(Boolean.class).single()).isTrue();
        orderService.expireDue();
        assertThat(orderStatus(m1)).isEqualTo("RESERVED");
        assertThat(orderStatus(m2)).isEqualTo("RESERVED");
        cardPaid(fanToken(f.f1()), m1, 5000); // past the first 8 min: still payable
        assertThat(orderStatus(m1)).isEqualTo("PAID");

        Caller cashier = caller(s.org().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        call(cashier, post("/org/pos/codes/" + code + "/collect"), "{\"amount_mad\": 5000}").andExpect(status().isOk());
        assertThat(orderStatus(buyer)).isEqualTo("PAID");
        assertThat(heldTwoHoursAfterPaid(m2, buyer)).isTrue();
    }

    /** P79: a member with its own cash code or an open card page keeps its own hold when the buyer expires. */
    @Test
    void membersPayingOnTheirOwnSurviveTheBuyersExpiry() throws Exception {
        Sale s = onSale(10);
        setCashPoints(s);
        Fans f = fans();
        String f3 = randomPhone();
        String buyer = JsonPath.read(body(place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME),
                h(KARIM, f.f1(), true), h(NADIA, f.f2(), true), h("Omar Idrissi", f3, true)))
                .andExpect(status().isCreated())), "$.id");
        String m1 = memberOf(buyer, f.f1());
        String m2 = memberOf(buyer, f.f2());
        String m3 = memberOf(buyer, f3);
        payCash(fanToken(f.f1()), m1);
        String p2 = payment(fanToken(f.f2()), m2);
        mvc.perform(get("/payments/card/" + p2 + "/redirect")).andExpect(status().isFound());
        due(buyer, m2, m3);
        orderService.expireDue();
        assertThat(orderStatus(buyer)).isEqualTo("EXPIRED");
        assertThat(orderStatus(m1)).isEqualTo("AWAITING_CASH");
        assertThat(orderStatus(m2)).isEqualTo("RESERVED");
        assertThat(orderStatus(m3)).isEqualTo("EXPIRED");
        assertThat(sold(s.priceId())).isEqualTo(2);
        callback(approve(p2, 5000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(m2)).isEqualTo("PAID");
    }

    @Test
    void aRedirectedBuyerPayingLateKeepsAndExtendsTheMembers() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        String buyer = JsonPath.read(split(f, s), "$.id");
        String p = payment(f.buyer(), buyer);
        mvc.perform(get("/payments/card/" + p + "/redirect")).andExpect(status().isFound());
        jdbc.sql("update orders set reserved_until = now() - interval '2 minutes' where id = ?::uuid or "
                + "group_order_id = ?::uuid").params(buyer, buyer).update(); // minute 10
        orderService.expireDue();
        assertThat(jdbc.sql("select count(*) from orders where (id = ?::uuid or group_order_id = ?::uuid) "
                + "and status = 'RESERVED'").params(buyer, buyer).query(Integer.class).single()).isEqualTo(3);
        callback(approve(p, 5000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(buyer)).isEqualTo("PAID");
        assertThat(heldTwoHoursAfterPaid(memberOf(buyer, f.f1()), buyer)).isTrue();
        assertThat(heldTwoHoursAfterPaid(memberOf(buyer, f.f2()), buyer)).isTrue();
    }

    @Test
    void aLateBuyerSuccessDoesNotReviveExpiredMembers() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        String buyer = JsonPath.read(split(f, s), "$.id");
        String p = payment(f.buyer(), buyer);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid or "
                + "group_order_id = ?::uuid").params(buyer, buyer).update();
        orderService.expireDue();
        assertThat(sold(s.priceId())).isZero();
        callback(approve(p, 5000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(buyer)).isEqualTo("PAID");
        assertThat(orderStatus(memberOf(buyer, f.f1()))).isEqualTo("EXPIRED");
        assertThat(orderStatus(memberOf(buyer, f.f2()))).isEqualTo("EXPIRED");
        assertThat(sold(s.priceId())).isEqualTo(1);
    }

    /** 04 §Split pay Limits: a member's own orders only; the P83 term is the initiator's rule. */
    @Test
    void aMembersOwnSplitDoesNotCountTowardTheirMemberLimit() throws Exception {
        Sale s = onSale(20);
        Fans f = fans();
        String friend = fanToken(f.f1()); // the friend started a split of 1 own + 3 unpaid members
        place(friend, s.eventId(), item(s.priceId(), h(KARIM), h(NADIA, randomPhone(), true),
                h("Omar Idrissi", randomPhone(), true), h("Sara Bennani", randomPhone(), true)))
                .andExpect(status().isCreated());
        String json = body(place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME), h(KARIM, f.f1(), true)))
                .andExpect(status().isCreated()));
        assertThat(JsonPath.<List<Object>>read(json, "$.group.members")).hasSize(1);
    }

    /**
     * Reviewer BE-29 #1: a late buyer success waits for its member rows before any price category, so an expiry
     * pass holding a member (out of card grace) while it releases that member's category can't deadlock with it.
     */
    @Test
    void aLateBuyerSuccessWaitsForAMemberTheExpiryHoldsWithoutDeadlock() throws Exception {
        Sale s = onSale(10);
        Fans f = fans();
        String buyer = JsonPath.read(body(place(f.buyer(), s.eventId(), item(s.priceId(), h(BUYER_NAME),
                h(KARIM, f.f1(), true))).andExpect(status().isCreated())), "$.id");
        String m1 = memberOf(buyer, f.f1());
        String p = payment(f.buyer(), buyer);
        String p1 = payment(fanToken(f.f1()), m1);
        mvc.perform(get("/payments/card/" + p1 + "/redirect")).andExpect(status().isFound());
        due(buyer, m1);
        orderService.expireDue();
        assertThat(orderStatus(buyer)).isEqualTo("EXPIRED");
        assertThat(orderStatus(m1)).isEqualTo("RESERVED"); // card grace
        assertThat(sold(s.priceId())).isEqualTo(1);

        // An expiry pass on the member, by hand: its row first, then (once the callback waits) its category.
        var memberLocked = new CountDownLatch(1);
        var settleWaits = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> expiry = pool.submit(() -> tx.executeWithoutResult(st -> {
                jdbc.sql("select id from orders where id = ?::uuid for no key update").param(m1).query(String.class)
                        .single();
                memberLocked.countDown();
                try {
                    if (!settleWaits.await(20, TimeUnit.SECONDS)) {
                        throw new AssertionError("the callback never waited");
                    }
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                jdbc.sql("update price_categories set sold = sold - 1 where id = ?::uuid").param(s.priceId()).update();
                jdbc.sql("update orders set status = 'EXPIRED' where id = ?::uuid").param(m1).update();
            }));
            assertThat(memberLocked.await(20, TimeUnit.SECONDS)).isTrue();
            Future<?> late = pool.submit(() -> {
                callback(approve(p, 5000)).andExpect(status().isSeeOther());
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (jdbc.sql("select count(*) from pg_stat_activity where datname = current_database() "
                    + "and wait_event_type = 'Lock'").query(Integer.class).single() == 0) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.onSpinWait();
            }
            settleWaits.countDown();
            expiry.get(30, TimeUnit.SECONDS);
            late.get(30, TimeUnit.SECONDS);
        }
        assertThat(orderStatus(buyer)).isEqualTo("PAID");
        assertThat(orderStatus(m1)).isEqualTo("EXPIRED"); // not revived
        assertThat(sold(s.priceId())).isEqualTo(1);
        assertThat(refundsRequired(p)).isZero();
    }

    // ---------- /pay channel (P77) ----------

    @Test
    void theAttemptsChannelPicksTheReturnUrl() throws Exception {
        Sale s = onSale(10);
        String token = fanToken(randomPhone());
        String orderId = order(token, s, 1); // channel APP
        String web = JsonPath.read(body(fan(token, post("/fan/orders/" + orderId + "/pay"),
                "{\"method\": \"CARD\", \"channel\": \"WEB\"}").andExpect(status().isOk())), "$.payment_id");
        mvc.perform(get("/payments/card/" + web + "/return")).andExpect(status().isFound())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Location"))
                        .isEqualTo("http://shop.test/orders/" + orderId));
        assertThat(jdbc.sql("select return_channel::text from payments where id = ?::uuid").param(web)
                .query(String.class).single()).isEqualTo("WEB");
        String app = payment(token, orderId);
        mvc.perform(get("/payments/card/" + app + "/return")).andExpect(status().isFound())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Location"))
                        .isEqualTo("jomhor-fan://orders/" + orderId));
        fan(token, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CARD\", \"channel\": \"POS\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.channel").exists());
    }

    // ---------- tenancy ----------

    @Test
    void groupsAcrossClubsReadOnlyTheirOrganizersOrders() throws Exception {
        Sale a = onSale(10);
        Sale b = onSale(10);
        Fans f = fans(); // club A: f.buyer splits with f1
        String x = f.f1();
        String buyerA = JsonPath.read(body(place(f.buyer(), a.eventId(), item(a.priceId(), h(BUYER_NAME),
                h(KARIM, x, true))).andExpect(status().isCreated())), "$.id");
        String memberA = memberOf(buyerA, x);
        String xToken = fanToken(x); // club B: x splits with y
        String y = randomPhone();
        String buyerB = JsonPath.read(body(place(xToken, b.eventId(), item(b.priceId(), h(KARIM),
                h(NADIA, y, true))).andExpect(status().isCreated())), "$.id");
        // A forged cross-club link: never read through club B's group.
        String stray = order(fanToken(randomPhone()), a, 1);
        jdbc.sql("update orders set group_order_id = ?::uuid where id = ?::uuid").params(buyerB, stray).update();

        String list = body(fan(xToken, get("/fan/orders")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(list, "$.orders[*].id")).containsExactly(memberA, buyerB);
        assertThat(JsonPath.<List<String>>read(list, "$.orders[*].group_role")).containsExactly("MEMBER", "INITIATOR");
        String gotB = body(fan(xToken, get("/fan/orders/" + buyerB)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(gotB, "$.group.members[*].order_id"))
                .containsExactly(memberOf(buyerB, y));
        String gotA = body(fan(xToken, get("/fan/orders/" + memberA)).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(gotA, "$.group.initiator_label")).isEqualTo("Yazid A.");
    }
}
