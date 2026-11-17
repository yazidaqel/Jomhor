package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BE-65: docs/04-api.md §Promo codes on POST /fan/orders (promo_code, PROMO_REFUSED, discount and total), GET
 * /fan/orders/{id} promo_code + discount_mad, the refund amount (§Promo codes Refunds), /sales discount_mad, and no code
 * at a till or on a product order.
 */
class PromoOrderApiTest extends QueueFixture {

    @Autowired
    OrderService orderService;

    /** An event on sale now: "Plein tarif" 150 DH in EST, "Virage" 30 DH in VIRAGE, 50 places each, limit 10. */
    private record Setup(Caller admin, int event, String plein, String virage) {}

    private Setup setup() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int est = zone(a, venue, "EST");
        int virage = zone(a, venue, "VIRAGE");
        int event = event(a, venue);
        String plein = category(a, event, est, "Plein tarif", 15_000);
        String v = category(a, event, virage, "Virage", 3_000);
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        jdbc.sql("update events set per_account_limit = 10 where id = ?").param(event).update();
        return new Setup(a, event, plein, v);
    }

    private String category(Caller a, int event, int zone, String name, long price) throws Exception {
        return JsonPath.read(body(call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "%s", "price_mad": %d, "quota": 50}""".formatted(zone, name, price))
                .andExpect(status().isCreated())), "$.id");
    }

    private String code(Setup s, String json) throws Exception {
        return JsonPath.read(body(call(s.admin(), post("/org/events/" + s.event() + "/promo-codes"), json)
                .andExpect(status().isCreated())), "$.id");
    }

    private static String items(String category, int qty) {
        return "[{\"price_category_id\": \"%s\", \"qty\": %d, \"holders\": %s}]".formatted(category, qty, holders(qty));
    }

    /** POST /fan/orders; {@code promo} is put in the body as JSON (a quoted string, or null). */
    private ResultActions order(Fan f, int event, String items, String promo, String admission) throws Exception {
        var req = post("/fan/orders").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"event_id": %d, "channel": "APP", "items": %s, "promo_code": %s}"""
                        .formatted(event, items, promo));
        return mvc.perform(admission == null ? req : req.header("X-Admission-Token", admission));
    }

    private ResultActions order(Fan f, Setup s, String items, String promo) throws Exception {
        return order(f, s.event(), items, promo, null);
    }

    private String placed(Fan f, Setup s, String items, String promo) throws Exception {
        return JsonPath.read(body(order(f, s, items, promo).andExpect(status().isCreated())), "$.id");
    }

    private static String refused(ResultActions r, String reason) throws Exception {
        return body(r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROMO_REFUSED"))
                .andExpect(jsonPath("$.details.field").value("promo_code"))
                .andExpect(jsonPath("$.details.reason").value(reason)));
    }

    private String pay(Fan f, String orderId, String method) throws Exception {
        return body(mvc.perform(post("/fan/orders/" + orderId + "/pay").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\": \"%s\"}".formatted(method)))
                .andExpect(status().isOk()));
    }

    private void paid(Fan f, String orderId, long amount) throws Exception {
        String payment = JsonPath.read(pay(f, orderId, "CARD"), "$.payment_id");
        callback(PaymentTest.approve(payment, amount)).andExpect(status().isSeeOther());
        assertThat(column(orderId, "status::text")).isEqualTo("PAID");
    }

    private void expire(String orderId) {
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId)
                .update();
        orderService.expireDue();
        assertThat(column(orderId, "status::text")).isEqualTo("EXPIRED");
    }

    private String column(String orderId, String expression) {
        return jdbc.sql("select " + expression + " from orders where id = ?::uuid").param(orderId)
                .query(String.class).single();
    }

    private long orders(Fan f) {
        return jdbc.sql("select count(*) from orders where user_id = ?").param(f.id()).query(Long.class).single();
    }

    private List<String> tickets(String orderId) {
        return jdbc.sql("select id::text from tickets where order_id = ?::uuid order by id").param(orderId)
                .query(String.class).list();
    }

    private long refundOf(Caller admin, String ticket) throws Exception {
        call(admin, post("/org/tickets/" + ticket + "/refund"), "{\"reason\": \"Doublon\"}").andExpect(status().isOk());
        return jdbc.sql("select amount_mad from refunds where ticket_id = ?::uuid").param(ticket).query(Long.class)
                .single();
    }

    /** (a), (g), (h): 20 % on Plein tarif × 2 at 150 DH; its proportional refunds; /sales discount_mad. */
    @Test
    void aPercentCodeDiscountsTheOrderItsPaymentsAndItsRefunds() throws Exception {
        Setup s = setup();
        code(s, """
                {"code": "ABONNES20", "kind": "PERCENT", "percent": 20, "price_category_id": "%s", "max_uses": 100}"""
                .formatted(s.plein()));
        Fan yazid = fan();
        String json = body(order(yazid, s, items(s.plein(), 2), "\" abonnes20 \"").andExpect(status().isCreated()));
        assertKeys(json, "$", "id", "status", "reserved_until", "total_mad", "items", "group", "promo_code",
                "discount_mad");
        assertThat(JsonPath.<String>read(json, "$.promo_code")).isEqualTo("ABONNES20");
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(6_000);
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(24_000);
        assertThat(JsonPath.<Integer>read(json, "$.items[0].unit_price_mad")).isEqualTo(15_000);
        String order = JsonPath.read(json, "$.id");

        json = body(mvc.perform(get("/fan/orders/" + order).header("Authorization", "Bearer " + yazid.token()))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.promo_code")).isEqualTo("ABONNES20");
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(6_000);
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(24_000);

        paid(yazid, order, 24_000); // the card attempt charged 24,000: approved at that amount, no AMOUNT_MISMATCH
        assertThat(jdbc.sql("select amount_mad from payments where order_id = ?::uuid").param(order)
                .query(Long.class).single()).isEqualTo(24_000);

        // A cash code is for the discounted total too.
        jdbc.sql("update organizers set settings = settings || jsonb_build_object('cash_points', ?::jsonb) where id = ?")
                .params("[{\"name\": \"Guichet Stade\"}]", s.admin().org()).update();
        Fan karim = fan();
        String cash = placed(karim, s, items(s.plein(), 2), "\"ABONNES20\"");
        assertThat(JsonPath.<Integer>read(pay(karim, cash, "CASH_AGENCY"), "$.amount_mad")).isEqualTo(24_000);

        // (h) /sales: discount_mad = Σ of the PAID orders only; revenue_mad stays at list prices.
        json = body(call(s.admin(), get("/org/events/" + s.event() + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(6_000);
        assertThat(JsonPath.<Integer>read(json, "$.revenue_mad")).isEqualTo(30_000);

        // (g) Refunds: ⌊15,000 × 24,000 ÷ 30,000⌋ each, Σ = what was paid; an order without a code refunds its price.
        List<String> paidTickets = tickets(order);
        assertThat(refundOf(s.admin(), paidTickets.get(0))).isEqualTo(12_000);
        assertThat(refundOf(s.admin(), paidTickets.get(1))).isEqualTo(12_000);
        Fan plain = fan();
        String full = placed(plain, s, items(s.virage(), 1), null);
        paid(plain, full, 3_000);
        assertThat(refundOf(s.admin(), tickets(full).getFirst())).isEqualTo(3_000);
        json = body(call(s.admin(), get("/org/events/" + s.event() + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.refunded_mad")).isEqualTo(27_000);
        assertThat(breaches(s.event())).isEmpty();
    }

    /** (b) AMOUNT 50 DH on one place at 30 DH: 1 DH stays to pay. */
    @Test
    void anAmountCodeLeavesOneDirhamToPay() throws Exception {
        Setup s = setup();
        code(s, "{\"code\": \"DERBY50\", \"kind\": \"AMOUNT\", \"amount_mad\": 5000}");
        String json = body(order(fan(), s, items(s.virage(), 1), "\"derby50\"").andExpect(status().isCreated()));
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(2_900);
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(100);
        // Event-wide: on 300 DH, 50 DH off once per order.
        json = body(order(fan(), s, items(s.plein(), 2), "\"DERBY50\"").andExpect(status().isCreated()));
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(5_000);
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(25_000);
    }

    /** (c) The code's shape is checked with the body: lowercase accepted, blank = none, a bad one 422, no SQL first. */
    @Test
    void theCodesShapeIsCheckedWithTheBodyBeforeAdmissionAndAnySql() throws Exception {
        Setup s = setup();
        Fan f = fan();
        String json = body(order(f, s, items(s.virage(), 1), "\"   \"").andExpect(status().isCreated()));
        assertThat(JsonPath.<Object>read(json, "$.promo_code")).isNull();
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isZero();
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(3_000);
        for (String bad : List.of("\"AB-12\"", "\"abc\"", "\"ABCDEFGHIJKLMNOPQRSTU\"", "\"CAFÉ1\"")) {
            order(f, s, items(s.virage(), 1), bad).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.fields.promo_code").isString());
        }
        assertThat(orders(f)).isOne();

        // A live room and no admission token: ADMISSION_REQUIRED, with no SQL but the fan session lookup; a bad
        // shape is a 422 before that.
        Room r = room("{\"pre_queue_min\": 0, \"admission_rate_per_min\": 6000}");
        jdbc.sql("update events set sale_starts_at = now() - interval '1 minute' where id = ?").param(r.event())
                .update();
        tick(Instant.parse(SALE).plusSeconds(10).toString());
        assertThat(roomRow(r.id())).containsEntry("status", "OPEN");
        memo.rooms(r.event());
        long before = statements.count();
        String one = "[{\"price_category_id\": \"%s\", \"qty\": 1, \"holders\": %s}]".formatted(r.price(), holders(1));
        order(f, r.event(), one, "\"abonnes20\"", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMISSION_REQUIRED"));
        assertThat(statements.count() - before).isEqualTo(1);
        before = statements.count();
        order(f, r.event(), one, "\"AB-12\"", null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.promo_code").isString());
        assertThat(statements.count() - before).isEqualTo(1);
    }

    /**
     * (d) An inactive code, another event's and a typo read the same; a code with no buyer place in scope is
     * NOT_APPLICABLE; neither leaves an order. A split: the discount is on the buyer's places, the member pays full.
     */
    @Test
    void refusalsReadTheSameAndASplitDiscountsTheBuyersPlacesOnly() throws Exception {
        Setup s = setup();
        Setup other = setup();
        String inactive = code(s, "{\"code\": \"SLEEP1\", \"kind\": \"PERCENT\", \"percent\": 10}");
        call(s.admin(), patch("/org/events/" + s.event() + "/promo-codes/" + inactive), "{\"active\": false}")
                .andExpect(status().isOk());
        code(other, "{\"code\": \"ELSEWHERE\", \"kind\": \"PERCENT\", \"percent\": 10}");
        code(s, """
                {"code": "ABONNES20", "kind": "PERCENT", "percent": 20, "price_category_id": "%s"}"""
                .formatted(s.plein()));
        Fan f = fan();
        String typo = refused(order(f, s, items(s.plein(), 1), "\"ABONNES2O\""), "UNKNOWN");
        assertThat(refused(order(f, s, items(s.plein(), 1), "\"SLEEP1\""), "UNKNOWN")).isEqualTo(typo);
        assertThat(refused(order(f, s, items(s.plein(), 1), "\"ELSEWHERE\""), "UNKNOWN")).isEqualTo(typo);
        assertKeys(typo, "$.details", "field", "reason");
        refused(order(f, s, items(s.virage(), 2), "\"ABONNES20\""), "NOT_APPLICABLE");
        assertThat(orders(f)).isZero();
        assertThat(jdbc.sql("select sold from price_categories where id in (?::uuid, ?::uuid) order by id")
                .params(s.plein(), s.virage()).query(Integer.class).list()).containsOnly(0);

        // Split: the buyer keeps one place, a friend pays for the other.
        String split = "[{\"price_category_id\": \"%s\", \"qty\": 2, \"holders\": [{\"name\": \"Yazid Aqel\"}, "
                .formatted(s.plein()) + "{\"name\": \"Nadia Berrada\", \"phone\": \"%s\", \"pays\": true}]}]"
                .formatted(randomPhone());
        String json = body(order(f, s, split, "\"ABONNES20\"").andExpect(status().isCreated()));
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(3_000);
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(12_000);
        String member = JsonPath.read(json, "$.group.members[0].order_id");
        assertThat(JsonPath.<Integer>read(json, "$.group.members[0].total_mad")).isEqualTo(15_000);
        assertThat(column(member, "coalesce(promo_code_id::text, 'none') || ':' || discount_mad")).isEqualTo("none:0");
    }

    /** (e) max_uses and per_account_max: exact, in parallel too; an expired order frees its use. */
    @Test
    void capsAreExactAndAnExpiredOrderFreesItsUse() throws Exception {
        Setup s = setup();
        code(s, "{\"code\": \"DERBY50\", \"kind\": \"AMOUNT\", \"amount_mad\": 5000, \"max_uses\": 2}");
        String first = placed(fan(), s, items(s.plein(), 1), "\"DERBY50\"");
        placed(fan(), s, items(s.plein(), 1), "\"DERBY50\"");
        Fan third = fan();
        refused(order(third, s, items(s.plein(), 1), "\"DERBY50\""), "USED_UP");
        assertThat(orders(third)).isZero();
        expire(first);
        placed(third, s, items(s.plein(), 1), "\"DERBY50\"");

        // max_uses 1, two fans at once: exactly one 201.
        code(s, "{\"code\": \"SOLO1\", \"kind\": \"PERCENT\", \"percent\": 10, \"max_uses\": 1, "
                + "\"per_account_max\": 5}");
        Fan a = fan();
        Fan b = fan();
        List<Integer> statuses = both(() -> order(a, s, items(s.virage(), 1), "\"SOLO1\"").andReturn().getResponse()
                .getStatus(), () -> order(b, s, items(s.virage(), 1), "\"SOLO1\"").andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        // per_account_max 1 (the default): a second order of the account is refused until the first expires.
        code(s, "{\"code\": \"ONCE1\", \"kind\": \"PERCENT\", \"percent\": 10}");
        Fan once = fan();
        String mine = placed(once, s, items(s.virage(), 1), "\"ONCE1\"");
        refused(order(once, s, items(s.virage(), 1), "\"ONCE1\""), "ACCOUNT_LIMIT");
        expire(mine);
        placed(once, s, items(s.virage(), 1), "\"ONCE1\"");

        // Two orders of one account at once: one 201.
        code(s, "{\"code\": \"TWICE1\", \"kind\": \"PERCENT\", \"percent\": 10}");
        Fan twice = fan();
        statuses = both(() -> order(twice, s, items(s.virage(), 1), "\"TWICE1\"").andReturn().getResponse()
                .getStatus(), () -> order(twice, s, items(s.virage(), 1), "\"TWICE1\"").andReturn().getResponse()
                .getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(breaches(s.event())).isEmpty();
    }

    /** (f) A late success after expiry takes the order back, discount and all, though the code's cap was re-taken. */
    @Test
    void aLateSuccessKeepsItsDiscountPastTheCap() throws Exception {
        Setup s = setup();
        code(s, "{\"code\": \"SOLO1\", \"kind\": \"PERCENT\", \"percent\": 20, \"max_uses\": 1}");
        Fan late = fan();
        String order = placed(late, s, items(s.plein(), 1), "\"SOLO1\"");
        String payment = JsonPath.read(pay(late, order, "CARD"), "$.payment_id");
        expire(order);
        placed(fan(), s, items(s.plein(), 1), "\"SOLO1\""); // the use it freed
        callback(PaymentTest.approve(payment, 12_000)).andExpect(status().isSeeOther());
        assertThat(column(order, "status::text || ':' || total_mad || ':' || discount_mad"))
                .isEqualTo("PAID:12000:3000");
        assertThat(jdbc.sql("select count(*) from orders where promo_code_id is not null and event_id = ? "
                + "and status in ('RESERVED', 'PAID')").param(s.event()).query(Long.class).single()).isEqualTo(2);
    }

    /** (i) A till sale and a product order ignore a promo_code: full price, no code. */
    @Test
    void aTillSaleAndAProductOrderNeverCarryACode() throws Exception {
        Setup s = setup();
        code(s, "{\"code\": \"ABONNES20\", \"kind\": \"PERCENT\", \"percent\": 20}");
        Caller cashier = caller(s.admin().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        String sale = JsonPath.read(body(call(cashier, post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d, "promo_code": "ABONNES20",
                 "items": [{"price_category_id": "%s", "qty": 1, "holders": [{"name": "Yazid Aqel"}]}]}"""
                .formatted(UUID.randomUUID(), s.event(), s.plein())).andExpect(status().isCreated())), "$.id");
        assertThat(column(sale, "total_mad || ':' || discount_mad || ':' || coalesce(promo_code_id::text, 'none')"))
                .isEqualTo("15000:0:none");

        String product = JsonPath.read(body(call(s.admin(), post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "Cotisation", "price_mad": 15000, "valid_until": "2099-07-01T00:00:00Z",
                 "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}""").andExpect(status().isCreated())), "$.id");
        Fan f = fan();
        String json = body(mvc.perform(post("/fan/product-orders").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"product_id": "%s", "channel": "APP", "holder_name": "Nadia Berrada", "promo_code": "ABONNES20"}"""
                        .formatted(product))).andExpect(status().isCreated()));
        String order = JsonPath.read(json, "$.id");
        assertThat(column(order, "total_mad || ':' || discount_mad || ':' || coalesce(promo_code_id::text, 'none')"))
                .isEqualTo("15000:0:none");
        json = body(mvc.perform(get("/fan/orders/" + order).header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsEntry("promo_code", null)
                .containsEntry("discount_mad", 0);
    }
}
