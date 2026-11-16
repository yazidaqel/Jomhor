package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.notifications.domain.MessageCopy;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-52): docs/04-api.md POST /fan/product-orders (MEMBERSHIP), a product order through /pay (card, cash
 * code, till), expiry and late success, GET /fan/orders/{id} and GET /fan/orders with {@code product}, the till's code
 * lookup, and PAY_CODE's deadline in the product's zone (§Products, §Point of sale, §Notifications).
 */
class ProductOrderApiTest extends PaymentTest {

    private static final String VALID_UNTIL = "2099-07-01T00:00:00Z";
    private static final long PRICE = 20_000;
    private static final String POINTS = "[{\"name\": \"Guichet Stade\"}]";

    @Autowired
    OrderService orderService;

    @Autowired
    FanMessageService messages;

    private record Club(Caller admin, String product) {}

    private record Fan(String phone, String token, UUID id) {}

    /** An organizer with cash points and a MEMBERSHIP product on sale (closing 2099-06-30T22:00Z). */
    private Club club() throws Exception {
        Caller a = admin();
        jdbc.sql("update organizers set settings = settings || jsonb_build_object('cash_points', ?::jsonb) where id = ?")
                .params(POINTS, a.org()).update();
        String product = JsonPath.read(body(call(a, post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "Cotisation 2026-27", "price_mad": %d, "valid_until": "%s",
                 "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}""".formatted(PRICE, VALID_UNTIL))
                .andExpect(status().isCreated())), "$.id");
        return new Club(a, product);
    }

    private Fan newFan() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        return new Fan(phone, token, jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class)
                .single());
    }

    private ResultActions buy(Fan f, String product, String body) throws Exception {
        return fan(f.token(), post("/fan/product-orders"), body.formatted(product));
    }

    private String bought(Fan f, Club c) throws Exception {
        return JsonPath.read(body(buy(f, c.product(), """
                {"product_id": "%s", "channel": "APP", "holder_name": "Yazid Aqel"}""")
                .andExpect(status().isCreated())), "$.id");
    }

    private void approved(Fan f, String orderId) throws Exception {
        callback(approve(payment(f.token(), orderId), PRICE)).andExpect(status().isSeeOther());
    }

    private String payCash(Fan f, String orderId) throws Exception {
        return body(fan(f.token(), post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk()));
    }

    private Map<String, Object> membership(Club c, Fan f) {
        return jdbc.sql("""
                select number, left_at is null as live, marketing_push, marketing_whatsapp, paid_until
                from memberships where organizer_id = ? and user_id = ?""").params(c.admin().org(), f.id())
                .query().singleRow();
    }

    private List<String> audits(Club c, Fan f) {
        return jdbc.sql("select action from audit_log where organizer_id = ? and actor_id = ? "
                        + "and action like 'membership.%' order by id")
                .params(c.admin().org(), f.id()).query(String.class).list();
    }

    private void due(String orderId) {
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId)
                .update();
    }

    private static Instant instant(Object ts) {
        return ((java.sql.Timestamp) ts).toInstant();
    }

    // ---------- POST /fan/product-orders, card ----------

    @Test
    void aMembershipOrderPaidByCardPaysTheMembership() throws Exception {
        Club c = club();
        Fan first = newFan();
        mvc.perform(put("/fan/me/membership").header("Authorization", "Bearer " + first.token())
                .contentType("application/json")
                .content("{\"organizer_id\": \"%s\", \"marketing_push\": true, \"marketing_whatsapp\": true}"
                        .formatted(c.admin().org()))).andExpect(status().isCreated()); // number 1
        Fan f = newFan();
        String json = body(buy(f, c.product(), """
                {"product_id": "%s", "channel": "WEB", "holder_name": "  Yazid   Aqel ", "installments": 1}""")
                .andExpect(status().isCreated()));
        assertKeys(json, "$", "id", "status", "reserved_until", "total_mad", "product", "pass_id", "items", "group");
        assertKeys(json, "$.product", "id", "kind", "name");
        assertKeys(json, "$.items[0]", "product_id", "price_category_id", "zone_id", "zone_name", "name", "qty",
                "unit_price_mad", "holders");
        String id = JsonPath.read(json, "$.id");
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("RESERVED");
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo((int) PRICE);
        assertThat(JsonPath.<String>read(json, "$.product.kind")).isEqualTo("MEMBERSHIP");
        assertThat(JsonPath.<Object>read(json, "$.pass_id")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.group")).isNull();
        assertThat(JsonPath.<List<Object>>read(json, "$.items")).hasSize(1);
        assertThat(JsonPath.<String>read(json, "$.items[0].product_id")).isEqualTo(c.product());
        assertThat(JsonPath.<Object>read(json, "$.items[0].price_category_id")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.items[0].zone_id")).isNull();
        assertThat(JsonPath.<String>read(json, "$.items[0].name")).isEqualTo("Cotisation 2026-27");
        assertThat(JsonPath.<Integer>read(json, "$.items[0].qty")).isEqualTo(1);
        assertThat(JsonPath.<String>read(json, "$.items[0].holders[0].label")).isEqualTo("Yazid A.");
        Map<String, Object> row = jdbc.sql("""
                select o.event_id, o.channel::text as channel, o.reserved_until > now() + interval '7 minutes' as held,
                       i.product_id, i.price_category_id, i.unit_price_mad
                from orders o join order_items i on i.order_id = o.id where o.id = ?::uuid""").param(id).query()
                .singleRow();
        assertThat(row).containsEntry("event_id", null).containsEntry("channel", "WEB").containsEntry("held", true)
                .containsEntry("product_id", UUID.fromString(c.product())).containsEntry("price_category_id", null)
                .containsEntry("unit_price_mad", PRICE);

        // GET /fan/orders/{id} and GET /fan/orders before payment
        String order = body(fan(f.token(), get("/fan/orders/" + id)).andExpect(status().isOk()));
        assertThat(JsonPath.<Object>read(order, "$.event_id")).isNull();
        assertKeys(order, "$.product", "id", "kind", "name", "organizer", "timezone", "payment_methods", "cash_until");
        assertKeys(order, "$.product.organizer", "slug", "name", "crest_url");
        assertThat(JsonPath.<String>read(order, "$.product.timezone")).isEqualTo("Africa/Casablanca");
        assertThat(JsonPath.<List<String>>read(order, "$.product.payment_methods")).contains("CARD", "CASH_AGENCY");
        assertThat(JsonPath.<Object>read(order, "$.pass_id")).isNull();
        assertThat(JsonPath.<String>read(order, "$.items[0].product_id")).isEqualTo(c.product());
        String list = body(fan(f.token(), get("/fan/orders")).andExpect(status().isOk()));
        assertThat(JsonPath.<Object>read(list, "$.orders[0].event")).isNull();
        assertKeys(list, "$.orders[0].product", "id", "kind", "name", "timezone");
        assertThat(JsonPath.<String>read(list, "$.orders[0].product.id")).isEqualTo(c.product());

        approved(f, id);
        assertThat(orderStatus(id)).isEqualTo("PAID");
        assertThat(jdbc.sql("select count(*) from tickets where order_id = ?::uuid").param(id).query(Integer.class)
                .single()).isZero();
        Map<String, Object> m = membership(c, f);
        assertThat(m).containsEntry("number", 2).containsEntry("live", true).containsEntry("marketing_push", false)
                .containsEntry("marketing_whatsapp", false);
        assertThat(instant(m.get("paid_until"))).isEqualTo(Instant.parse(VALID_UNTIL));
        assertThat(audits(c, f)).containsExactly("membership.join", "membership.paid");
        String paid = jdbc.sql("select payload::text from audit_log where action = 'membership.paid' "
                + "and actor_id = ?").param(f.id()).query(String.class).single();
        assertThat(JsonPath.<String>read(paid, "$.product_id")).isEqualTo(c.product());
        assertThat(Instant.parse(JsonPath.read(paid, "$.paid_until"))).isEqualTo(Instant.parse(VALID_UNTIL));

        // paid to that date: no second order
        buy(f, c.product(), "{\"product_id\": \"%s\", \"channel\": \"APP\", \"holder_name\": \"Yazid Aqel\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBERSHIP_PAID"))
                .andExpect(jsonPath("$.details.paid_until").value(VALID_UNTIL));

        // read from the product even once it is off sale
        call(c.admin(), patch("/org/products/" + c.product()), "{\"on_sale\": false}").andExpect(status().isOk());
        fan(f.token(), get("/fan/orders/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.name").value("Cotisation 2026-27"));
    }

    @Test
    void aLeftMemberRejoinsKeepingTheirNumber() throws Exception {
        Club c = club();
        Fan f = newFan();
        mvc.perform(put("/fan/me/membership").header("Authorization", "Bearer " + f.token())
                .contentType("application/json").content("{\"organizer_id\": \"%s\", \"marketing_push\": true, "
                        .formatted(c.admin().org()) + "\"marketing_whatsapp\": false}"))
                .andExpect(status().isCreated());
        mvc.perform(delete("/fan/me/membership").param("organizer", c.admin().org().toString())
                .header("Authorization", "Bearer " + f.token())).andExpect(status().isNoContent());
        int number = (int) membership(c, f).get("number");

        approved(f, bought(f, c));
        Map<String, Object> m = membership(c, f);
        assertThat(m).containsEntry("number", number).containsEntry("live", true);
        assertThat(instant(m.get("paid_until"))).isEqualTo(Instant.parse(VALID_UNTIL));
        assertThat(audits(c, f)).containsExactly("membership.join", "membership.leave", "membership.join",
                "membership.paid");
    }

    @Test
    void refusalsComeInTheContractOrder() throws Exception {
        Club c = club();
        Fan f = newFan();
        String unknown = UUID.randomUUID().toString();
        // 1. the body first, even on an unknown product
        buy(f, unknown, "{\"product_id\": \"%s\", \"channel\": \"APP\", \"holder_name\": \"X\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.holder_name").exists());
        buy(f, c.product(), "{\"product_id\": \"%s\", \"channel\": \"APP\", \"holder_name\": \"Yazid Aqel\", "
                + "\"installments\": 2}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.installments").exists())
                .andExpect(jsonPath("$.details.reason").value("NOT_OFFERED"));
        buy(f, c.product(), "{\"product_id\": \"%s\", \"channel\": \"POS\", \"holder_name\": \"Yazid Aqel\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.channel").exists());
        // 2. visible as GET /fan/products/{id}
        buy(f, unknown, "{\"product_id\": \"%s\", \"channel\": \"APP\", \"holder_name\": \"Yazid Aqel\"}")
                .andExpect(status().isNotFound());
        // 3. before sales_close_at (database clock)
        jdbc.sql("update products set sales_close_at = now() - interval '1 second' where id = ?::uuid")
                .param(c.product()).update();
        String close = jdbc.sql("select sales_close_at from products where id = ?::uuid").param(c.product())
                .query(OffsetDateTime.class).single().toInstant().toString();
        buy(f, c.product(), "{\"product_id\": \"%s\", \"channel\": \"APP\", \"holder_name\": \"Yazid Aqel\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_ON_SALE"))
                .andExpect(jsonPath("$.details.sales_close_at").value(close));
        // off sale
        jdbc.sql("update products set on_sale = false where id = ?::uuid").param(c.product()).update();
        buy(f, c.product(), "{\"product_id\": \"%s\", \"channel\": \"APP\", \"holder_name\": \"Yazid Aqel\"}")
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("select count(*) from orders where user_id = ?").param(f.id()).query(Integer.class)
                .single()).isZero();
    }

    // ---------- cash code, PAY_CODE, the till ----------

    @Test
    void aCashCodeHoldsUntilTheProductClosesWithNoCap() throws Exception {
        Club c = club();
        messages.deliverDue(Integer.MAX_VALUE); // rows other classes left due
        clearInvocations(ticketMessenger);
        // closes in 30 h: now + 24 h
        jdbc.sql("update products set sales_close_at = now() + interval '30 hours' where id = ?::uuid")
                .param(c.product()).update();
        Fan f = newFan();
        String id = bought(f, c);
        String json = payCash(f, id);
        assertThat(orderStatus(id)).isEqualTo("AWAITING_CASH");
        assertThat(jdbc.sql("select abs(extract(epoch from reserved_until - (now() + interval '24 hours'))) < 10 "
                + "from orders where id = ?::uuid").param(id).query(Boolean.class).single()).isTrue();
        Instant until = Instant.parse(JsonPath.read(json, "$.reserved_until"));

        // PAY_CODE: the deadline in the product's zone (Africa/Casablanca for MEMBERSHIP)
        messages.deliverDue(Integer.MAX_VALUE);
        var captor = ArgumentCaptor.forClass(TicketMessenger.Template.class);
        verify(ticketMessenger, atLeast(0)).send(captor.capture());
        assertThat(captor.getAllValues().stream().filter(t -> t.phoneE164().equals(f.phone())).toList())
                .singleElement().satisfies(t -> {
                    assertThat(t.name()).isEqualTo("pay_code");
                    assertThat(t.params()).containsExactly("JMH " + JsonPath.<String>read(json, "$.pay_code")
                            .substring(0, 4) + " " + JsonPath.<String>read(json, "$.pay_code").substring(4),
                            "200,00 DH", MessageCopy.deadline(until, "Africa/Casablanca", "fr"));
                });

        // closes in 5 h: the close; no CASH_CAP for a product (any number of codes)
        jdbc.sql("update products set sales_close_at = now() + interval '5 hours' where id = ?::uuid")
                .param(c.product()).update();
        String held = null;
        for (int i = 0; i < 3; i++) {
            Fan g = newFan();
            held = bought(g, c);
            payCash(g, held);
        }
        assertThat(jdbc.sql("select reserved_until = p.sales_close_at from orders o, products p "
                + "where o.id = ?::uuid and p.id = ?::uuid").params(held, c.product()).query(Boolean.class).single())
                .isTrue();

        // closes in 1 h 59: under payments.cash.min-window (2 h)
        jdbc.sql("update products set sales_close_at = now() + interval '119 minutes' where id = ?::uuid")
                .param(c.product()).update();
        Fan h = newFan();
        String late = bought(h, c);
        fan(h.token(), post("/fan/orders/" + late + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("METHOD_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.details.reason").value("TOO_LATE"));
        assertThat(orderStatus(late)).isEqualTo("RESERVED");
    }

    @Test
    void aTillLooksUpAndCollectsAProductCodeButPrintsNothing() throws Exception {
        Club c = club();
        Fan f = newFan();
        String id = bought(f, c);
        String code = JsonPath.read(payCash(f, id), "$.pay_code");
        Caller cashier = caller(c.admin().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());

        String json = body(call(cashier, get("/org/pos/codes/" + code)).andExpect(status().isOk()));
        assertKeys(json, "$", "order_id", "status", "payable", "reserved_until", "amount_mad", "places", "event", "holders",
                "product", "reason");
        assertThat(JsonPath.<Object>read(json, "$.event")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.reason")).isNull();
        assertKeys(json, "$.product", "name");
        assertThat(JsonPath.<String>read(json, "$.product.name")).isEqualTo("Cotisation 2026-27");
        assertThat(JsonPath.<Integer>read(json, "$.places")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(json, "$.holders")).containsExactly("Yazid A.");
        assertThat(JsonPath.<Boolean>read(json, "$.payable")).isTrue();

        call(cashier, post("/org/pos/codes/" + code + "/collect"), "{\"amount_mad\": %d}".formatted(PRICE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.ticket_ids").isEmpty());
        assertThat(orderStatus(id)).isEqualTo("PAID");
        assertThat(instant(membership(c, f).get("paid_until"))).isEqualTo(Instant.parse(VALID_UNTIL));
        call(cashier, get("/org/pos/orders/" + id + "/tickets")).andExpect(status().isNotFound());
        call(c.admin(), get("/org/pos/orders/" + id + "/tickets")).andExpect(status().isNotFound());
    }

    // ---------- expiry, late success ----------

    @Test
    void expiryReleasesTheEventPlacesOnceAndNothingForAProduct() throws Exception {
        Club c = club();
        Sale s = onSale(10);
        Fan f = newFan();
        String event = order(f.token(), s, 2);
        String product = bought(f, c);
        Fan g = newFan();
        String cash = bought(g, c);
        payCash(g, cash);
        assertThat(sold(s.priceId())).isEqualTo(2);
        due(event);
        due(product);
        due(cash);

        orderService.expireDue();
        assertThat(List.of(orderStatus(event), orderStatus(product), orderStatus(cash)))
                .containsOnly("EXPIRED");
        assertThat(sold(s.priceId())).isZero(); // the batch committed, the event places came back once
        orderService.expireDue();
        assertThat(sold(s.priceId())).isZero();
    }

    @Test
    void aLateSuccessPaysTheMembership() throws Exception {
        Club c = club();
        Fan f = newFan();
        String late = bought(f, c);
        String payment = payment(f.token(), late);
        due(late);
        orderService.expireDue();
        assertThat(orderStatus(late)).isEqualTo("EXPIRED");

        callback(approve(payment, PRICE)).andExpect(status().isSeeOther());
        assertThat(orderStatus(late)).isEqualTo("PAID");
        assertThat(instant(membership(c, f).get("paid_until"))).isEqualTo(Instant.parse(VALID_UNTIL));
        assertThat(audits(c, f)).containsExactly("membership.join", "membership.paid");
    }

    /** Order A got a cash code and a card attempt, then order B was paid by card. */
    private record Twice(Club club, Fan fan, String a, String code, String cardOfA) {}

    private Twice paidByAnotherOrder() throws Exception {
        Club c = club();
        Fan f = newFan();
        String a = bought(f, c);
        String b = bought(f, c); // both placed before either is paid
        String code = JsonPath.read(payCash(f, a), "$.pay_code");
        String cardOfA = payment(f.token(), a); // a card attempt on an AWAITING_CASH order
        approved(f, b);
        assertThat(orderStatus(b)).isEqualTo("PAID");
        return new Twice(c, f, a, code, cardOfA);
    }

    private String refundReason(String paymentId) {
        return jdbc.sql("select payload ->> 'reason' from audit_log where action = 'payment.refund_required' "
                + "and target = ?").param(paymentId).query(String.class).single();
    }

    /** 04 §Products Paid membership: /pay refused before any charge, the till can't take it, a success is a refund. */
    @Test
    void aMembershipPaidByAnotherOrderIsRefusedThenCancelled() throws Exception {
        Twice t = paidByAnotherOrder();
        for (String method : List.of("CARD", "WALLET", "CASH_AGENCY")) {
            fan(t.fan().token(), post("/fan/orders/" + t.a() + "/pay"), "{\"method\": \"%s\"}".formatted(method))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBERSHIP_PAID"))
                    .andExpect(jsonPath("$.details.paid_until").value(VALID_UNTIL));
        }
        call(t.club().admin(), get("/org/pos/codes/" + t.code())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CASH")).andExpect(jsonPath("$.payable").value(false))
                .andExpect(jsonPath("$.reason").value("MEMBERSHIP_PAID"));

        due(t.a());
        orderService.expireDue();
        assertThat(orderStatus(t.a())).isEqualTo("EXPIRED");
        callback(approve(t.cardOfA(), PRICE)).andExpect(status().isSeeOther()); // a late success
        assertThat(orderStatus(t.a())).isEqualTo("CANCELLED");
        assertThat(refundReason(t.cardOfA())).isEqualTo("NOT_PAYABLE");
        assertThat(audits(t.club(), t.fan())).containsExactly("membership.join", "membership.paid");
        fan(t.fan().token(), post("/fan/orders/" + t.a() + "/pay"), "{\"method\": \"CARD\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
    }

    /**
     * A success on a held order (no expiry) is cancelled the same way. BE-55: GET /org/products/{id} lists the flag;
     * its MEMBERSHIP figures count the paid member and the PAID order's money only.
     */
    @Test
    void aSecondSuccessOnAHeldOrderCancelsIt() throws Exception {
        Twice t = paidByAnotherOrder();
        callback(approve(t.cardOfA(), PRICE)).andExpect(status().isSeeOther());
        assertThat(orderStatus(t.a())).isEqualTo("CANCELLED");
        assertThat(refundReason(t.cardOfA())).isEqualTo("NOT_PAYABLE");

        String json = body(call(t.club().admin(), get("/org/products/" + t.club().product()))
                .andExpect(status().isOk()));
        assertKeys(json, "$.figures", "paid_members", "revenue_mad");
        assertThat(JsonPath.<Integer>read(json, "$.figures.paid_members")).isOne();
        assertThat(JsonPath.<Number>read(json, "$.figures.revenue_mad").longValue()).isEqualTo(PRICE);
        assertKeys(json, "$.refunds_required[0]", "payment_id", "order_id", "amount_mad", "reason", "at");
        assertThat(JsonPath.<List<String>>read(json, "$.refunds_required[*].payment_id"))
                .containsExactly(t.cardOfA());
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.refunds_required[0]"))
                .containsEntry("order_id", t.a()).containsEntry("reason", "NOT_PAYABLE");
        assertThat(JsonPath.<Number>read(json, "$.refunds_required[0].amount_mad").longValue()).isEqualTo(PRICE);
    }

    /** 04 §Point of sale: collect of such a code → 409 CODE_NOT_PAYABLE MEMBERSHIP_PAID, nothing recorded. */
    @Test
    void aTillCantCollectAMembershipPaidByAnotherOrder() throws Exception {
        Twice t = paidByAnotherOrder();
        Caller cashier = caller(t.club().admin().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        call(cashier, post("/org/pos/codes/" + t.code() + "/collect"), "{\"amount_mad\": %d}".formatted(PRICE))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODE_NOT_PAYABLE"))
                .andExpect(jsonPath("$.details.reason").value("MEMBERSHIP_PAID"));
        assertThat(jdbc.sql("select status::text from payments where pay_code = ?").param(t.code())
                .query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("select pos_session_id is null from payments where pay_code = ?").param(t.code())
                .query(Boolean.class).single()).isTrue();
        assertThat(orderStatus(t.a())).isEqualTo("AWAITING_CASH");
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'pos.collect' and target = ?")
                .param(t.a()).query(Integer.class).single()).isZero();
    }
}
