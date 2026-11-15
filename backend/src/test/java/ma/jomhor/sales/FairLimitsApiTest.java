package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-47): docs/04-api.md POST /fan/orders holder limit and members-only places, the refunded-places rule
 * (plan lot-5 Q175, lot-3 Q52), POST /fan/tickets/{id}/transfer holder limit, price {@code audience} on
 * /org/events/{id}/prices* and GET /fan/events*, {@code from_price_mad} over ALL only. Every event has
 * per_account_limit 4.
 */
class FairLimitsApiTest extends PaymentTest {

    private static final String NAME = "Yazid Aqel";

    @Autowired
    OrderService orderService;

    private record Fan(String phone, String token, UUID id) {}

    /** A logged-in fan. */
    private Fan fan() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        return new Fan(phone, token, userId(phone));
    }

    private UUID userId(String phone) {
        return jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
    }

    private static String h(String phone) {
        return phone == null ? "{\"name\": \"%s\"}".formatted(NAME)
                : "{\"name\": \"%s\", \"phone\": \"%s\"}".formatted(NAME, phone);
    }

    private static String pays(String phone) {
        return "{\"name\": \"%s\", \"phone\": \"%s\", \"pays\": true}".formatted(NAME, phone);
    }

    private static String item(String priceId, String... holders) {
        return "{\"price_category_id\": \"%s\", \"qty\": %d, \"holders\": [%s]}"
                .formatted(priceId, holders.length, String.join(", ", holders));
    }

    private ResultActions place(Fan f, int eventId, String... items) throws Exception {
        return fan(f.token(), post("/fan/orders"), "{\"event_id\": %d, \"channel\": \"APP\", \"items\": [%s]}"
                .formatted(eventId, String.join(", ", items)));
    }

    private String placed(Fan f, int eventId, String... items) throws Exception {
        return JsonPath.read(body(place(f, eventId, items).andExpect(status().isCreated())), "$.id");
    }

    /** Placed and paid by card: the order id. */
    private String paid(Fan f, int eventId, String... items) throws Exception {
        String orderId = placed(f, eventId, items);
        cardPaid(f, orderId);
        return orderId;
    }

    private void cardPaid(Fan f, String orderId) throws Exception {
        callback(approve(payment(f.token(), orderId), total(orderId))).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
    }

    private List<UUID> ticketIds(String orderId) {
        return jdbc.sql("select id from tickets where order_id = ?::uuid order by id").param(orderId)
                .query(UUID.class).list();
    }

    private void refund(Sale s, UUID ticket) throws Exception {
        call(s.org(), post("/org/tickets/" + ticket + "/refund"), "{\"reason\": \"Geste commercial\"}")
                .andExpect(status().isOk());
    }

    private void expire(String orderId) {
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId)
                .update();
        orderService.expireDue();
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
    }

    private ResultActions transfer(Fan from, UUID ticket, String toPhone) throws Exception {
        return fan(from.token(), post("/fan/tickets/" + ticket + "/transfer"),
                "{\"to_phone\": \"%s\", \"name\": \"Karim Benali\"}".formatted(toPhone));
    }

    /** 409 PER_ACCOUNT_LIMIT HOLDER on {@code field}: details are reason, field and limit, never held. */
    private static void holderLimit(ResultActions r, String field) throws Exception {
        String json = body(r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT")));
        assertKeys(json, "$.details", "reason", "field", "limit");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.details"))
                .containsEntry("reason", "HOLDER").containsEntry("field", field).containsEntry("limit", 4);
    }

    private String openSession(Caller c) throws Exception {
        return JsonPath.read(body(call(c, post("/org/pos/sessions"), "{\"label\": \"Guichet Nord\"}")
                .andExpect(status().isCreated())), "$.id");
    }

    private ResultActions sell(Caller cashier, int eventId, String... items) throws Exception {
        return call(cashier, post("/org/pos/orders"), "{\"sale_id\": \"%s\", \"event_id\": %d, \"items\": [%s]}"
                .formatted(UUID.randomUUID(), eventId, String.join(", ", items)));
    }

    // ---------- (a) refunded places, plan lot-3 Q52 ----------

    @Test
    void aRefundedTicketNoLongerCountsInTheLimit() throws Exception {
        Sale s = onSale(50);
        Fan buyer = fan();
        String full = paid(buyer, s.eventId(), item(s.priceId(), h(null), h(null), h(null), h(null)));
        place(buyer, s.eventId(), item(s.priceId(), h(null))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT"));
        refund(s, ticketIds(full).getFirst());
        place(buyer, s.eventId(), item(s.priceId(), h(null), h(null))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT")).andExpect(jsonPath("$.details.held").value(3))
                .andExpect(jsonPath("$.details.limit").value(4));
        place(buyer, s.eventId(), item(s.priceId(), h(null))).andExpect(status().isCreated());

        // A split member's refunded ticket frees their own limit.
        Fan member = fan();
        String own = paid(member, s.eventId(), item(s.priceId(), h(null), h(null), h(null), h(null)));
        Fan initiator = fan();
        place(initiator, s.eventId(), item(s.priceId(), h(null), pays(member.phone())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.field").value("items[0].holders[1]"))
                .andExpect(jsonPath("$.details.held").value(4));
        refund(s, ticketIds(own).getFirst());
        place(initiator, s.eventId(), item(s.priceId(), h(null), pays(member.phone()))).andExpect(status().isCreated());
    }

    @Test
    void aLateSuccessCountsRefundedPlacesTheSameWay() throws Exception {
        Sale s = onSale(50);
        Fan buyer = fan();
        String first = paid(buyer, s.eventId(), item(s.priceId(), h(null), h(null), h(null)));
        String late = placed(buyer, s.eventId(), item(s.priceId(), h(null)));
        String payment = payment(buyer.token(), late);
        jdbc.sql("update payments set created_at = now() - interval '21 minutes' where id = ?::uuid").param(payment)
                .update();
        expire(late);
        placed(buyer, s.eventId(), item(s.priceId(), h(null))); // 3 PAID + 1 RESERVED: at the limit
        refund(s, ticketIds(first).getFirst());

        callback(approve(payment, total(late))).andExpect(status().isSeeOther());
        assertThat(orderStatus(late)).isEqualTo("PAID"); // 2 + 1 held + 1 revived = 4
    }

    // ---------- (b) holder limit on orders ----------

    @Test
    void aHolderCantHoldMoreThanTheLimitWhoeverBuys() throws Exception {
        Sale s = onSale(50);
        String holder = randomPhone();
        String bought = paid(fan(), s.eventId(), item(s.priceId(), h(holder), h(holder), h(holder)));
        String reserved = placed(fan(), s.eventId(), item(s.priceId(), h(holder))); // 3 VALID + 1 RESERVED

        Fan c = fan();
        holderLimit(place(c, s.eventId(), item(s.priceId(), h(null), h(holder))), "items[0].holders[1]");
        expire(reserved);
        placed(c, s.eventId(), item(s.priceId(), h(null), h(holder))); // 3 + 1 again

        // CANCELLED (transferred away) and REFUNDED tickets no longer count: 1 VALID + 1 RESERVED, room for 2.
        Fan named = new Fan(holder, fanToken(holder), userId(holder));
        List<UUID> held = jdbc.sql("select id from tickets where order_id = ?::uuid and holder_user_id = ? order by id")
                .params(bought, named.id()).query(UUID.class).list();
        transfer(named, held.get(0), randomPhone()).andExpect(status().isCreated());
        refund(s, held.get(1));
        placed(fan(), s.eventId(), item(s.priceId(), h(holder), h(holder)));

        // The holder's own order counts what they hold: 4 already.
        holderLimit(place(named, s.eventId(), item(s.priceId(), h(null))), "items[0].holders[0]");

        // POS checks no holder limit.
        Caller cashier = caller(s.org().org(), "CASHIER");
        openSession(cashier);
        sell(cashier, s.eventId(), item(s.priceId(), h(holder))).andExpect(status().isCreated());
    }

    /** Two buyers place {@code item} at once (real threads, one start signal): "status RESERVED|reason" each. */
    private List<String> race(Sale s, String item) throws Exception {
        List<Fan> buyers = List.of(fan(), fan());
        var start = new CountDownLatch(1);
        List<String> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> runs = new ArrayList<>();
            for (Fan b : buyers) {
                runs.add(pool.submit(() -> {
                    start.await();
                    var response = place(b, s.eventId(), item).andReturn().getResponse();
                    String json = response.getContentAsString();
                    return response.getStatus() + " "
                            + JsonPath.read(json, response.getStatus() == 201 ? "$.status" : "$.details.reason");
                }));
            }
            start.countDown();
            for (var r : runs) {
                results.add(r.get());
            }
        }
        return results;
    }

    @Test
    void twoParallelOrdersNamingOneNewPhoneMakeOne() throws Exception {
        Sale s = onSale(50);
        String holder = randomPhone();
        assertThat(race(s, item(s.priceId(), h(holder), h(holder), h(holder))))
                .containsExactlyInAnyOrder("201 RESERVED", "409 HOLDER");
        assertThat(sold(s.priceId())).isEqualTo(3);
    }

    /**
     * The holder's account exists already, so nothing but its per-account lock orders the two checks (the new-phone
     * race above also queues on the users unique index): 1 VALID + 2 + 2 > 4, exactly one passes.
     */
    @Test
    void twoParallelOrdersNamingOneExistingHolderMakeOne() throws Exception {
        Sale s = onSale(50);
        String holder = randomPhone();
        paid(fan(), s.eventId(), item(s.priceId(), h(holder)));
        assertThat(race(s, item(s.priceId(), h(holder), h(holder))))
                .containsExactlyInAnyOrder("201 RESERVED", "409 HOLDER");
        assertThat(sold(s.priceId())).isEqualTo(3);
    }

    // ---------- (c) holder limit on transfers ----------

    @Test
    void aTransferToAHolderAtTheLimitIsRefused() throws Exception {
        Sale s = onSale(50);
        String full = randomPhone();
        paid(fan(), s.eventId(), item(s.priceId(), h(full), h(full), h(full), h(full)));
        String three = randomPhone();
        paid(fan(), s.eventId(), item(s.priceId(), h(three), h(three), h(three)));
        Fan giver = fan();
        UUID ticket = ticketIds(paid(giver, s.eventId(), item(s.priceId(), h(null), h(null)))).getFirst();

        holderLimit(transfer(giver, ticket, full), "to_phone");
        assertThat(jdbc.sql("select status::text from tickets where id = ?").param(ticket).query(String.class).single())
                .isEqualTo("VALID");
        transfer(giver, ticket, three).andExpect(status().isCreated());

        // Re-issue: the count is unchanged, never refused for the limit.
        Fan holder = new Fan(full, fanToken(full), userId(full));
        UUID own = jdbc.sql("select id from tickets where holder_user_id = ? and event_id = ? limit 1")
                .params(holder.id(), s.eventId()).query(UUID.class).single();
        fan(holder.token(), post("/fan/tickets/" + own + "/reissue")).andExpect(status().isCreated());
    }

    // ---------- (d) members-only places ----------

    private record Members(Sale s, String members, Instant saleStartsAt) {}

    /** An event on sale since a day ago with an ALL category (s.priceId()) and a MEMBERS one. */
    private Members members() throws Exception {
        Sale s = onSale(50);
        String members = JsonPath.read(body(call(s.org(), post("/org/events/" + s.eventId() + "/prices"), """
                {"zone_id": %d, "name": "Membres", "price_mad": 3000, "quota": 50, "audience": "MEMBERS"}"""
                .formatted(s.zoneId())).andExpect(status().isCreated())), "$.id");
        Instant start = jdbc.sql("update events set sale_starts_at = date_trunc('second', now()) - interval '1 day' "
                        + "where id = ? returning sale_starts_at").param(s.eventId())
                .query(OffsetDateTime.class).single().toInstant();
        return new Members(s, members, start);
    }

    private void join(Sale s, UUID user, String joinedAt, String leftAt) {
        jdbc.sql("""
                        insert into memberships (organizer_id, user_id, number, joined_at, left_at)
                        values (?, ?, (select coalesce(max(number), 0) + 1 from memberships where organizer_id = ?),
                                now() + cast(? as interval), now() + cast(? as interval))""")
                .params(s.org().org(), user, s.org().org(), joinedAt, leftAt).update();
    }

    private static void membersOnly(ResultActions r, String field, Instant joinedBefore) throws Exception {
        String json = body(r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBERS_ONLY")));
        assertKeys(json, "$.details", "field", "member_joined_before");
        assertThat(JsonPath.<String>read(json, "$.details.field")).isEqualTo(field);
        String before = JsonPath.read(json, "$.details.member_joined_before");
        assertThat(before == null ? null : Instant.parse(before)).isEqualTo(joinedBefore);
    }

    @Test
    void aMembersPlaceNeedsAMemberWhoJoinedBeforeTheSale() throws Exception {
        Members m = members();
        Sale s = m.s();
        Fan outsider = fan();
        Fan late = fan();
        join(s, late.id(), "-1 hour", null);
        Fan left = fan();
        join(s, left.id(), "-2 days", "-1 minute");
        Fan member = fan();
        join(s, member.id(), "-2 days", null);

        for (Fan f : List.of(outsider, late, left)) {
            membersOnly(place(f, s.eventId(), item(m.members(), h(null))), "items[0].holders[0]", m.saleStartsAt());
        }
        placed(member, s.eventId(), item(m.members(), h(null)));

        // Each members place through its holder; an ALL place for the outsider in the same order is fine.
        membersOnly(place(member, s.eventId(), item(m.members(), h(null), h(outsider.phone()))),
                "items[0].holders[1]", m.saleStartsAt());
        placed(member, s.eventId(), item(s.priceId(), h(outsider.phone())), item(m.members(), h(null)));

        // No sale_starts_at: any live member.
        jdbc.sql("update events set sale_starts_at = null where id = ?").param(s.eventId()).update();
        placed(late, s.eventId(), item(m.members(), h(null)));
        membersOnly(place(left, s.eventId(), item(m.members(), h(null))), "items[0].holders[0]", null);

        // POS: the cashier checks the card.
        Caller cashier = caller(s.org().org(), "CASHIER");
        openSession(cashier);
        sell(cashier, s.eventId(), item(m.members(), h(null))).andExpect(status().isCreated());
    }

    // ---------- (e) prices ----------

    @Test
    void audienceOnOrgPricesAndFanEvents() throws Exception {
        Sale s = onSale(10); // 50 DH, ALL
        String prices = "/org/events/" + s.eventId() + "/prices";
        assertThat(JsonPath.<String>read(body(call(s.org(), get(prices)).andExpect(status().isOk())), "$[0].audience"))
                .isEqualTo("ALL");
        String cheap = JsonPath.read(body(call(s.org(), post(prices), """
                {"zone_id": %d, "name": "Membres", "price_mad": 3000, "quota": 10}""".formatted(s.zoneId()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.audience").value("ALL"))), "$.id");
        call(s.org(), patch(prices + "/" + cheap), "{\"audience\": \"MEMBERS\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("MEMBERS")).andExpect(jsonPath("$.price_mad").value(3000));
        assertThat(JsonPath.<List<String>>read(body(call(s.org(), get(prices)).andExpect(status().isOk())),
                "$[?(@.id == '" + cheap + "')].audience")).containsExactly("MEMBERS");
        call(s.org(), patch(prices + "/" + cheap), "{\"audience\": \"VIP\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.audience").exists());
        call(s.org(), post(prices), """
                {"zone_id": %d, "name": "Autre", "price_mad": 1000, "quota": 1, "audience": "everyone"}"""
                .formatted(s.zoneId())).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.audience").exists());

        // from_price_mad: the lowest ALL price left; the cheaper MEMBERS one never shows as "dès".
        mvc.perform(get("/fan/events/" + s.eventId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.zones[0].prices[?(@.price_category_id == '" + cheap + "')].audience")
                        .value("MEMBERS"))
                .andExpect(jsonPath("$.zones[0].prices[?(@.price_category_id == '" + s.priceId() + "')].audience")
                        .value("ALL"))
                .andExpect(jsonPath("$.from_price_mad").value(5000)).andExpect(jsonPath("$.sold_out").value(false));
        mvc.perform(get("/fan/events").param("organizer", s.org().org().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].from_price_mad").value(5000));
        setSold(s.priceId(), 10);
        mvc.perform(get("/fan/events/" + s.eventId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.from_price_mad").isEmpty()).andExpect(jsonPath("$.sold_out").value(false));
        mvc.perform(get("/fan/events").param("organizer", s.org().org().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].from_price_mad").isEmpty())
                .andExpect(jsonPath("$[0].sold_out").value(false));
    }
}
