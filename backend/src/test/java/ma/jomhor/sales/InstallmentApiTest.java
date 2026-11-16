package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-57): docs/04-api.md §Passes Installments. {@code plans} on GET /fan/products, POST /fan/product-orders
 * {@code installments} (422 NOT_OFFERED), the schedule made at purchase, POST /fan/passes/{id}/installments
 * (PASS_STATUS, 201/200), installment orders paid by card or cash code (24 h hold) and their late success, the GET
 * /fan/passes schedule fields, the org product's {@code max_installments} and {@code installments} figures. Dates are
 * relative to the database clock: the series ends long after the 4th due date, or before it.
 */
class InstallmentApiTest extends PaymentTest {

    private static final long PRICE = 100_001; // 4 payments: 25,001 then 3 × 25,000
    private static final String CLOSE = "2099-06-30T22:00:00Z";

    @Autowired
    OrderService orderService;

    private record Club(Caller admin, int zone, String pass) {}

    private record Fan(String token, UUID id) {}

    /** A series ending {@code days} after today (Africa/Casablanca). */
    private String series(Caller a, int days) throws Exception {
        LocalDate end = LocalDate.now(ZoneId.of("Africa/Casablanca")).plusDays(days);
        return JsonPath.read(body(call(a, post("/org/series"), """
                {"name": "Saison", "starts_on": "2026-08-01", "ends_on": "%s"}""".formatted(end))
                .andExpect(status().isCreated())), "$.id");
    }

    private String passProduct(Caller a, String series, int zone, int maxInstallments) throws Exception {
        return JsonPath.read(body(call(a, post("/org/products"), """
                {"kind": "PASS", "name": "Abonnement", "price_mad": %d, "quota": 10, "series_id": "%s", "zone_id": %d,
                 "sales_close_at": "%s", "on_sale": true, "max_installments": %d}"""
                .formatted(PRICE, series, zone, CLOSE, maxInstallments)).andExpect(status().isCreated())), "$.id");
    }

    /** A club with a cash point and a pass in up to 4 payments on a series ending in 200 days. */
    private Club club() throws Exception {
        Caller a = admin();
        jdbc.sql("update organizers set settings = settings || '{\"cash_points\": [{\"name\": \"Guichet\"}]}'::jsonb "
                + "where id = ?").param(a.org()).update();
        int zone = zone(a, venue(a, "Stade"), "EST");
        return new Club(a, zone, passProduct(a, series(a, 200), zone, 4));
    }

    private Fan newFan() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        return new Fan(token, jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class)
                .single());
    }

    private ResultActions buy(Fan f, String product, Integer installments) throws Exception {
        return fan(f.token(), post("/fan/product-orders"), """
                {"product_id": "%s", "channel": "WEB", "holder_name": "Yazid Aqel"%s}""".formatted(product,
                installments == null ? "" : ", \"installments\": " + installments));
    }

    private String bought(Fan f, String product, int installments) throws Exception {
        return JsonPath.read(body(buy(f, product, installments).andExpect(status().isCreated())), "$.id");
    }

    /** Buys the pass in 4 and pays the first by card → the pass id. */
    private String activePass(Fan f, Club c) throws Exception {
        String order = bought(f, c.pass(), 4);
        callback(approve(payment(f.token(), order), 25_001)).andExpect(status().isSeeOther());
        return passOf(order);
    }

    private ResultActions next(Fan f, String pass) throws Exception {
        return fan(f.token(), post("/fan/passes/" + pass + "/installments"));
    }

    private String passOf(String orderId) {
        return jdbc.sql("select pass_id::text from orders where id = ?::uuid").param(orderId).query(String.class)
                .single();
    }

    /** seq → the order that paid it ("" while unpaid), by seq. */
    private List<String> paidBy(String pass) {
        return jdbc.sql("select coalesce(order_id::text, '') from pass_installments where pass_id = ?::uuid order by seq")
                .param(pass).query(String.class).list();
    }

    private String passStatus(String pass) {
        return jdbc.sql("select status::text from passes where id = ?::uuid").param(pass).query(String.class).single();
    }

    private String refundReason(String paymentId) {
        return jdbc.sql("select payload ->> 'reason' from audit_log where action = 'payment.refund_required' "
                + "and target = ?").param(paymentId).query(String.class).single();
    }

    private void due(String orderId) {
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId)
                .update();
    }

    private void payByCard(Fan f, String order, long amount) throws Exception {
        callback(approve(payment(f.token(), order), amount)).andExpect(status().isSeeOther());
        assertThat(orderStatus(order)).isEqualTo("PAID");
    }

    // ---------- plans ----------

    @Test
    void plansOfferTheMaxWhenTheLastDueDateFitsTheSeason() throws Exception {
        Club c = club();
        Fan f = newFan();
        String json = body(mvc.perform(get("/fan/products/" + c.pass())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Map<String, Object>>>read(json, "$.plans")).containsExactly(
                planOf(1, List.of(100_001), null),
                planOf(4, List.of(25_001, 25_000, 25_000, 25_000), 30));
        assertKeys(json, "$.plans[1]", "installments", "amounts_mad", "interval_days");

        // The series ends in 60 days: the 4th due date (+90 d) is past it, so in full only, and 4 isn't offered.
        String soon = passProduct(c.admin(), series(c.admin(), 60), c.zone(), 4);
        assertThat(JsonPath.<List<Map<String, Object>>>read(
                body(mvc.perform(get("/fan/products/" + soon))), "$.plans"))
                .containsExactly(planOf(1, List.of(100_001), null));
        for (var bad : List.of(Map.of(soon, 4), Map.of(c.pass(), 3), Map.of(c.pass(), 11), Map.of(c.pass(), 0))) {
            var e = bad.entrySet().iterator().next();
            buy(f, e.getKey(), e.getValue()).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.reason").value("NOT_OFFERED"))
                    .andExpect(jsonPath("$.details.fields.installments").exists());
        }
        assertThat(jdbc.sql("select count(*) from passes where user_id = ?").param(f.id()).query(Integer.class)
                .single()).isZero();

        // A closed product says so before the plan: 409, not NOT_OFFERED.
        jdbc.sql("update products set sales_close_at = now() - interval '1 minute' where id = ?::uuid").param(soon)
                .update();
        buy(f, soon, 4).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_ON_SALE"));

        // max_installments 1, the default: in full only.
        String inFull = passProduct(c.admin(), series(c.admin(), 200), c.zone(), 1);
        assertThat(JsonPath.<List<Object>>read(body(mvc.perform(get("/fan/products/" + inFull))), "$.plans"))
                .hasSize(1);
    }

    private static Map<String, Object> planOf(int n, List<Integer> amounts, Integer intervalDays) {
        var m = new java.util.HashMap<String, Object>();
        m.put("installments", n);
        m.put("amounts_mad", amounts);
        m.put("interval_days", intervalDays);
        return m;
    }

    // ---------- purchase ----------

    @Test
    void aPurchaseInFourMakesTheScheduleAndPaysTheFirst() throws Exception {
        Club c = club();
        Fan f = newFan();
        String json = body(buy(f, c.pass(), 4).andExpect(status().isCreated()));
        String order = JsonPath.read(json, "$.id");
        String pass = JsonPath.read(json, "$.pass_id");
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(25_001);
        assertThat(JsonPath.<Integer>read(json, "$.items[0].unit_price_mad")).isEqualTo(25_001);
        assertThat(jdbc.sql("""
                        select i.seq || ':' || i.amount_mad || ':' || extract(epoch from i.due_at - p.created_at)::bigint / 3600
                        from pass_installments i join passes p on p.id = i.pass_id
                        where i.pass_id = ?::uuid order by i.seq""").param(pass).query(String.class).list())
                .containsExactly("1:25001:0", "2:25000:720", "3:25000:1440", "4:25000:2160"); // hours

        payByCard(f, order, 25_001);
        assertThat(passStatus(pass)).isEqualTo("ACTIVE");
        assertThat(paidBy(pass)).containsExactly(order, "", "", "");

        // In full: one installment of the price.
        String full = bought(newFan(), c.pass(), 1);
        assertThat(total(full)).isEqualTo(PRICE);
        assertThat(jdbc.sql("select amount_mad from pass_installments where pass_id = ?::uuid").param(passOf(full))
                .query(Long.class).list()).containsExactly(PRICE);
    }

    // ---------- POST /fan/passes/{id}/installments ----------

    @Test
    void installmentOrdersPayTheScheduleInOrderThenPaidUp() throws Exception {
        Club c = club();
        Fan f = newFan();
        String pass = activePass(f, c);

        // Seq 2: a new order, then the same live one again.
        String json = body(next(f, pass).andExpect(status().isCreated()));
        assertKeys(json, "$", "id", "status", "reserved_until", "total_mad", "product", "pass_id", "items", "group");
        String second = JsonPath.read(json, "$.id");
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("RESERVED");
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(25_000);
        assertThat(JsonPath.<String>read(json, "$.pass_id")).isEqualTo(pass);
        assertThat(JsonPath.<Integer>read(json, "$.items[0].unit_price_mad")).isEqualTo(25_000);
        assertThat(JsonPath.<String>read(json, "$.items[0].holders[0].label")).isEqualTo("Yazid A.");
        assertThat(jdbc.sql("select channel::text from orders where id = ?::uuid").param(second).query(String.class)
                .single()).isEqualTo("WEB");
        assertThat(JsonPath.<String>read(body(next(f, pass).andExpect(status().isOk())), "$.id")).isEqualTo(second);

        // A cash code: held 24 h (no sales close), still the live order.
        String code = JsonPath.read(body(fan(f.token(), post("/fan/orders/" + second + "/pay"),
                "{\"method\": \"CASH_AGENCY\"}").andExpect(status().isOk())), "$.pay_code");
        assertThat(jdbc.sql("select abs(extract(epoch from reserved_until - now()) - 86400) < 60 from orders "
                + "where id = ?::uuid").param(second).query(Boolean.class).single()).isTrue();
        String owned = body(fan(f.token(), get("/fan/passes")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(owned, "$.passes[0].pay_order_id")).isEqualTo(second);
        String again = body(next(f, pass).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(again, "$.id")).isEqualTo(second);
        assertThat(JsonPath.<String>read(again, "$.status")).isEqualTo("AWAITING_CASH");

        // Collected at a till → seq 2 paid.
        Caller cashier = caller(c.admin().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        call(cashier, post("/org/pos/codes/" + code + "/collect"), "{\"amount_mad\": 25000}")
                .andExpect(status().isOk());
        assertThat(orderStatus(second)).isEqualTo("PAID");
        assertThat(paidBy(pass).get(1)).isEqualTo(second);

        // Seq 3 paid ahead of its date, by card; then seq 4; then paid up.
        String third = JsonPath.read(body(next(f, pass).andExpect(status().isCreated())), "$.id");
        payByCard(f, third, 25_000);
        String fourth = JsonPath.read(body(next(f, pass).andExpect(status().isCreated())), "$.id");
        payByCard(f, fourth, 25_000);
        assertThat(paidBy(pass).subList(2, 4)).containsExactly(third, fourth);
        next(f, pass).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PASS_STATUS"))
                .andExpect(jsonPath("$.details.reason").value("PAID_UP"));
        assertThat(passStatus(pass)).isEqualTo("ACTIVE");

        // The org figures: everything collected, nothing due.
        String figures = body(call(c.admin(), get("/org/products/" + c.pass())).andExpect(status().isOk()));
        assertKeys(figures, "$.figures.installments", "overdue", "due_mad", "collected_mad");
        assertThat(JsonPath.<Map<String, Object>>read(figures, "$.figures.installments"))
                .isEqualTo(Map.of("overdue", 0, "due_mad", 0, "collected_mad", 100_001));
        assertThat(JsonPath.<Integer>read(figures, "$.figures.revenue_mad")).isEqualTo(100_001);
    }

    /**
     * GET /fan/orders/{id} {@code installment}: {1, n} on the purchase in installments, {seq, n} on a later installment
     * order, null on a purchase in full. Once the pass product is off sale and past its sales close, a later installment
     * order still offers cash (cash_until null) and takes a 24 h code; the purchase order keeps the product's close.
     */
    @Test
    void anInstallmentOrderSaysWhichAndKeepsCashAfterTheSalesClose() throws Exception {
        Club c = club();
        Fan f = newFan();
        String purchase = bought(f, c.pass(), 4);
        callback(approve(payment(f.token(), purchase), 25_001)).andExpect(status().isSeeOther());
        String bought = body(fan(f.token(), get("/fan/orders/" + purchase)).andExpect(status().isOk()));
        assertThat(JsonPath.<Map<String, Object>>read(bought, "$.installment")).isEqualTo(Map.of("seq", 1, "of", 4));
        assertThat(JsonPath.<Object>read(bought, "$.product.cash_until")).isNotNull();
        Fan g = newFan();
        String full = bought(g, c.pass(), 1);
        assertThat(JsonPath.<Object>read(body(fan(g.token(), get("/fan/orders/" + full)).andExpect(status().isOk())),
                "$.installment")).isNull();

        jdbc.sql("update products set on_sale = false, sales_close_at = now() - interval '1 minute' where id = ?::uuid")
                .param(c.pass()).update();
        String order = JsonPath.read(body(next(f, passOf(purchase)).andExpect(status().isCreated())), "$.id");
        String json = body(fan(f.token(), get("/fan/orders/" + order)).andExpect(status().isOk()));
        assertKeys(json, "$.installment", "seq", "of");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.installment")).isEqualTo(Map.of("seq", 2, "of", 4));
        assertThat(JsonPath.<List<String>>read(json, "$.product.payment_methods")).contains("CASH_AGENCY");
        assertThat(JsonPath.<Object>read(json, "$.product.cash_until")).isNull();
        fan(f.token(), post("/fan/orders/" + order + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select abs(extract(epoch from reserved_until - now()) - 86400) < 60 from orders "
                + "where id = ?::uuid").param(order).query(Boolean.class).single()).isTrue();

        // The purchase (seq 1) keeps the product's sales close: past it, no cash, cash_until still set.
        String purchased = body(fan(f.token(), get("/fan/orders/" + purchase)).andExpect(status().isOk()));
        assertThat(JsonPath.<Map<String, Object>>read(purchased, "$.installment")).isEqualTo(Map.of("seq", 1, "of", 4));
        assertThat(JsonPath.<List<String>>read(purchased, "$.product.payment_methods")).doesNotContain("CASH_AGENCY");
        assertThat(JsonPath.<Object>read(purchased, "$.product.cash_until")).isNotNull();
    }

    /** Two calls at once (real threads, one start signal): one live order, 201 then 200 with its id. */
    @Test
    void twoParallelCallsMakeOneLiveOrder() throws Exception {
        Club c = club();
        Fan f = newFan();
        String pass = activePass(f, c);
        var start = new CountDownLatch(1);
        Callable<String> call = () -> {
            start.await();
            var r = next(f, pass).andReturn().getResponse();
            return r.getStatus() + " " + JsonPath.read(r.getContentAsString(), "$.id");
        };
        List<String> answers;
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> runs = List.of(pool.submit(call), pool.submit(call));
            start.countDown();
            answers = List.of(runs.get(0).get(), runs.get(1).get());
        }
        String id = answers.getFirst().split(" ")[1];
        assertThat(answers).containsExactlyInAnyOrder("201 " + id, "200 " + id);
        assertThat(jdbc.sql("select count(*) from orders where pass_id = ?::uuid and event_id is null "
                + "and status = 'RESERVED'").param(pass).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void onlyTheHolderOfAPayingPassMakesAnInstallmentOrder() throws Exception {
        Club c = club();
        Fan f = newFan();
        String pending = passOf(bought(f, c.pass(), 4));
        // Still being bought: seq 1 is due at purchase, but not overdue.
        String json = body(fan(f.token(), get("/fan/passes")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.passes[0].status")).isEqualTo("PENDING");
        assertThat(JsonPath.<Integer>read(json, "$.passes[0].next_due.seq")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(json, "$.passes[0].next_due.overdue")).isFalse();
        next(f, pending).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PASS_STATUS"))
                .andExpect(jsonPath("$.details.status").value("PENDING"));
        Fan g = newFan();
        String active = activePass(g, c);
        next(f, active).andExpect(status().isNotFound());
        next(f, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        mvc.perform(post("/fan/passes/" + active + "/installments")).andExpect(status().isUnauthorized());

        // SUSPENDED still pays (BE-58 suspends; set here by hand).
        jdbc.sql("update passes set status = 'SUSPENDED', suspended_at = now() where id = ?::uuid").param(active)
                .update();
        next(g, active).andExpect(status().isCreated());
    }

    @Test
    void theFansPassShowsItsSchedule() throws Exception {
        Club c = club();
        Fan f = newFan();
        String pass = activePass(f, c);
        jdbc.sql("update pass_installments set due_at = now() - interval '1 day' where pass_id = ?::uuid and seq = 2")
                .param(pass).update();
        String json = body(fan(f.token(), get("/fan/passes")).andExpect(status().isOk()));
        assertKeys(json, "$.passes[0]", "id", "status", "product", "organizer", "series", "zone", "timezone",
                "holder_label", "created_at", "order_id", "events", "suspended_at", "installments", "next_due",
                "pay_order_id");
        assertThat(JsonPath.<Object>read(json, "$.passes[0].suspended_at")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.passes[0].pay_order_id")).isNull();
        assertThat(JsonPath.<List<Integer>>read(json, "$.passes[0].installments[*].seq")).containsExactly(1, 2, 3, 4);
        assertKeys(json, "$.passes[0].installments[0]", "seq", "amount_mad", "due_at", "paid_at");
        assertThat(JsonPath.<Object>read(json, "$.passes[0].installments[0].paid_at")).isNotNull();
        assertThat(JsonPath.<Object>read(json, "$.passes[0].installments[1].paid_at")).isNull();
        assertKeys(json, "$.passes[0].next_due", "seq", "amount_mad", "due_at", "overdue");
        assertThat(JsonPath.<Integer>read(json, "$.passes[0].next_due.seq")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$.passes[0].next_due.amount_mad")).isEqualTo(25_000);
        assertThat(JsonPath.<Boolean>read(json, "$.passes[0].next_due.overdue")).isTrue();

        // The org figures: 1 overdue, 3 due, the first collected. A PENDING pass (bought, not paid) counts nowhere.
        bought(newFan(), c.pass(), 4);
        assertThat(JsonPath.<Map<String, Object>>read(body(call(c.admin(), get("/org/products/" + c.pass()))),
                "$.figures.installments")).isEqualTo(Map.of("overdue", 1, "due_mad", 75_000, "collected_mad", 25_001));
    }

    // ---------- settle ----------

    @Test
    void aLateSuccessPaysTheNextInstallmentWhenTheAmountMatchesElseARefund() throws Exception {
        Club c = club();
        Fan f = newFan();
        String pass = activePass(f, c);

        // A: seq 2, its card attempt left pending, then expired; B pays seq 2; A's late success pays seq 3.
        String a = JsonPath.read(body(next(f, pass).andExpect(status().isCreated())), "$.id");
        String aPayment = payment(f.token(), a);
        due(a);
        orderService.expireDue();
        assertThat(orderStatus(a)).isEqualTo("EXPIRED");
        String b = JsonPath.read(body(next(f, pass).andExpect(status().isCreated())), "$.id");
        assertThat(b).isNotEqualTo(a);
        payByCard(f, b, 25_000);
        callback(approve(aPayment, 25_000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(a)).isEqualTo("PAID");
        assertThat(paidBy(pass).subList(1, 3)).containsExactly(b, a);

        // C: seq 4, expired; D pays it; C's late success finds nothing left: a refund, C stays EXPIRED.
        String d0 = JsonPath.read(body(next(f, pass).andExpect(status().isCreated())), "$.id");
        String cPayment = payment(f.token(), d0);
        due(d0);
        orderService.expireDue();
        String d = JsonPath.read(body(next(f, pass).andExpect(status().isCreated())), "$.id");
        payByCard(f, d, 25_000);
        callback(approve(cPayment, 25_000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(d0)).isEqualTo("EXPIRED");
        assertThat(refundReason(cPayment)).isEqualTo("NOT_PAYABLE");
        assertThat(paidBy(pass).get(3)).isEqualTo(d);
    }

    /** A purchase whose pass has no seq 1 (a broken schedule) is never PAID: no ACTIVE pass without its schedule. */
    @Test
    void aPurchaseWithNoFirstInstallmentIsNotPaid() throws Exception {
        Club c = club();
        Fan f = newFan();
        String order = bought(f, c.pass(), 4);
        String payment = payment(f.token(), order);
        jdbc.sql("delete from pass_installments where pass_id = ?::uuid").param(passOf(order)).update();
        callback(approve(payment, 25_001)).andExpect(status().isSeeOther());
        assertThat(orderStatus(order)).isNotEqualTo("PAID");
        assertThat(passStatus(passOf(order))).isEqualTo("PENDING");
        assertThat(refundReason(payment)).isEqualTo("NOT_PAYABLE");
    }

    @Test
    void aHeldOrderForAnotherAmountIsCancelledAndRefunded() throws Exception {
        Club c = club();
        Fan f = newFan();
        String pass = activePass(f, c);
        String order = JsonPath.read(body(next(f, pass).andExpect(status().isCreated())), "$.id");
        String payment = payment(f.token(), order);
        jdbc.sql("update pass_installments set amount_mad = 30000 where pass_id = ?::uuid and seq = 2").param(pass)
                .update();
        callback(approve(payment, 25_000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertThat(refundReason(payment)).isEqualTo("NOT_PAYABLE");
        assertThat(paidBy(pass).get(1)).isEmpty();
    }

    @Test
    void payingTheOverdueInstallmentReactivatesOnlyWhenNoneIsLeftPastItsGrace() throws Exception {
        Club c = club();
        Fan f = newFan();
        String pass = activePass(f, c);
        jdbc.sql("update passes set status = 'SUSPENDED', suspended_at = now() where id = ?::uuid").param(pass)
                .update();
        // Seq 2 and 3 both past their 7-day grace: paying seq 2 leaves seq 3, still SUSPENDED.
        jdbc.sql("update pass_installments set due_at = now() - interval '8 days' where pass_id = ?::uuid "
                + "and seq in (2, 3)").param(pass).update();
        payByCard(f, JsonPath.read(body(next(f, pass).andExpect(status().isCreated())), "$.id"), 25_000);
        assertThat(passStatus(pass)).isEqualTo("SUSPENDED");
        String third = JsonPath.read(body(next(f, pass).andExpect(status().isCreated())), "$.id");
        payByCard(f, third, 25_000);
        assertThat(passStatus(pass)).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("select suspended_at is null from passes where id = ?::uuid").param(pass)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select payload::text from audit_log where action = 'pass.reactivate' and target = ?")
                .param(pass).query(String.class).list()).containsExactly("{\"seq\": 3}");
    }

    // ---------- org product ----------

    @Test
    void maxInstallmentsInTheOrgCrudAndAPriceChangeLeavesTheScheduleAlone() throws Exception {
        Club c = club();
        String json = body(call(c.admin(), get("/org/products?kind=PASS")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$[0].max_installments")).isEqualTo(4);

        for (int bad : List.of(0, 11)) {
            call(c.admin(), patch("/org/products/" + c.pass()), "{\"max_installments\": %d}".formatted(bad))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.fields.max_installments").exists());
        }
        call(c.admin(), post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "Cotisation", "price_mad": 15000, "valid_until": "2099-07-01T00:00:00Z",
                 "sales_close_at": "%s", "max_installments": 3}""".formatted(CLOSE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.max_installments").exists());
        assertThat(JsonPath.<Integer>read(body(call(c.admin(), post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "Cotisation", "price_mad": 15000, "valid_until": "2099-07-01T00:00:00Z",
                 "sales_close_at": "%s"}""".formatted(CLOSE)).andExpect(status().isCreated())), "$.max_installments"))
                .isEqualTo(1);

        Fan f = newFan();
        String pass = activePass(f, c);
        String patched = body(call(c.admin(), patch("/org/products/" + c.pass()),
                "{\"price_mad\": 200000, \"max_installments\": 2}").andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(patched, "$.max_installments")).isEqualTo(2);
        assertThat(jdbc.sql("select payload ->> 'max_installments' from audit_log where action = 'product.update' "
                + "and target = ?").param(c.pass()).query(String.class).single()).isEqualTo("2");
        assertThat(jdbc.sql("select sum(amount_mad) from pass_installments where pass_id = ?::uuid").param(pass)
                .query(Long.class).single()).isEqualTo(PRICE);
        assertThat(JsonPath.<Integer>read(body(next(f, pass).andExpect(status().isCreated())), "$.total_mad"))
                .isEqualTo(25_000);
        assertThat(JsonPath.<List<Map<String, Object>>>read(body(mvc.perform(get("/fan/products/" + c.pass()))),
                "$.plans")).containsExactly(planOf(1, List.of(200_000), null),
                planOf(2, List.of(100_000, 100_000), 30));
    }
}
