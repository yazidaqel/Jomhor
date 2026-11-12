package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.jomhor.payments.application.Payments;
import ma.jomhor.payments.domain.CashCode;
import ma.jomhor.payments.domain.Payment;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract (BE-22): POST /fan/orders/{id}/pay CASH_AGENCY and WALLET, CARD on an AWAITING_CASH order, GET
 * /fan/orders/{id} payment_method + cash, GET /fan/orders, GET /fan/events/{id} payment_methods (docs/04-api.md §Fan,
 * §Cash codes), the AWAITING_CASH expiry and plan P38 (only a redirected attempt holds its order).
 */
class CashOrderApiTest extends PaymentTest {

    private static final String CODE = "[0-9A-HJKMNP-TV-Z]{6}";
    private static final String POINTS = """
            [{"name": "Guichet Stade", "address": "Porte 1", "hours": "Lun-Sam 10:00-19:00"},
             "not an object", {"address": "no name"}, {"name": "Boutique du club", "address": "Centre", "hours": "9-18"},
             {"name": "Kiosque"}]
            """;

    @Autowired
    OrderService orderService;

    @Autowired
    Payments payments;

    @Autowired
    PlatformTransactionManager transactions;

    /** An event on sale (one category of {@code quota}, limit 4) whose organizer has two cash points. */
    private Sale cashSale(int quota) throws Exception {
        Sale s = onSale(quota);
        jdbc.sql("update organizers set settings = settings || jsonb_build_object('cash_points', ?::jsonb) where id = ?")
                .params(POINTS, s.org().org()).update();
        return s;
    }

    private ResultActions payWith(String token, String orderId, String method) throws Exception {
        return fan(token, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"%s\"}".formatted(method));
    }

    private String payCash(String token, String orderId) throws Exception {
        return body(payWith(token, orderId, "CASH_AGENCY").andExpect(status().isOk()));
    }

    private static void error(ResultActions r, String code, String reason) throws Exception {
        r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.details.reason").value(reason));
    }

    private void salesCloseIn(int eventId, String interval) {
        jdbc.sql("update events set refund_cutoff_at = now() + cast(? as interval) where id = ?").params(interval, eventId)
                .update();
    }

    private Instant dbInstant(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(OffsetDateTime.class).single().toInstant();
    }

    private int payments(String orderId) {
        return jdbc.sql("select count(*) from payments where order_id = ?::uuid").param(orderId).query(Integer.class).single();
    }

    private String orderJson(String token, String orderId) throws Exception {
        return body(fan(token, get("/fan/orders/" + orderId)).andExpect(status().isOk()));
    }

    // ---------- /pay CASH_AGENCY ----------

    @Test
    void cashMovesTheOrderToAwaitingCashFor24Hours() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 2);
        String json = payCash(fan, orderId);
        assertKeys(json, "$", "payment_id", "method", "pay_code", "reserved_until", "amount_mad");
        assertThat(JsonPath.<String>read(json, "$.method")).isEqualTo("CASH_AGENCY");
        String code = JsonPath.read(json, "$.pay_code");
        assertThat(code).matches(CODE).doesNotStartWith("JMH"); // the 6 stored symbols on the wire
        assertThat(JsonPath.<Integer>read(json, "$.amount_mad")).isEqualTo(10000);
        assertThat(orderStatus(orderId)).isEqualTo("AWAITING_CASH");
        Instant until = Instant.parse(JsonPath.read(json, "$.reserved_until"));
        assertThat(until).isEqualTo(dbInstant("select reserved_until from orders where id = ?::uuid", orderId));
        assertThat(jdbc.sql("""
                select abs(extract(epoch from reserved_until - (now() + interval '24 hours'))) < 10 from orders
                where id = ?::uuid""").param(orderId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                select method || ' ' || provider || ' ' || status || ' ' || pay_code || ' ' || amount_mad
                       || ' ' || (organizer_id = ?) || ' ' || (redirected_at is null)
                from payments where order_id = ?::uuid""").params(s.org().org(), orderId).query(String.class).single())
                .isEqualTo("CASH_AGENCY CASH PENDING " + code + " 10000 true true");
        assertThat(sold(s.priceId())).isEqualTo(2); // still held

        String got = orderJson(fan, orderId);
        assertThat(JsonPath.<String>read(got, "$.status")).isEqualTo("AWAITING_CASH");
        assertThat(JsonPath.<String>read(got, "$.payment_method")).isEqualTo("CASH_AGENCY");
        assertThat(JsonPath.<String>read(got, "$.payment_status")).isEqualTo("PENDING");
        assertKeys(got, "$.cash", "pay_code", "amount_mad", "points", "networks");
        assertThat(JsonPath.<String>read(got, "$.cash.pay_code")).isEqualTo(code);
        assertThat(JsonPath.<Integer>read(got, "$.cash.amount_mad")).isEqualTo(10000);
        assertThat(JsonPath.<List<?>>read(got, "$.cash.networks")).isEmpty();
    }

    /** Q54: cash.points = organizers.settings.cash_points in order; malformed entries are left out. */
    @Test
    void cashPointsComeFromTheOrganizerSettings() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        payCash(fan, orderId);
        String got = orderJson(fan, orderId);
        assertThat(JsonPath.<List<String>>read(got, "$.cash.points[*].name"))
                .containsExactly("Guichet Stade", "Boutique du club", "Kiosque");
        assertKeys(got, "$.cash.points[0]", "name", "address", "hours");
        assertKeys(got, "$.cash.points[2]", "name"); // {name, address?, hours?}: absent fields stay absent
        assertThat(JsonPath.<String>read(got, "$.cash.points[0].address")).isEqualTo("Porte 1");
        assertThat(JsonPath.<String>read(got, "$.cash.points[1].hours")).isEqualTo("9-18");
    }

    @Test
    void theHoldIsCappedAtSalesClose() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        salesCloseIn(s.eventId(), "5 hours");
        String json = payCash(fan, orderId);
        Instant cutoff = dbInstant("select refund_cutoff_at from events where id = ?", s.eventId());
        assertThat(Instant.parse(JsonPath.read(json, "$.reserved_until"))).isEqualTo(cutoff);
        assertThat(dbInstant("select reserved_until from orders where id = ?::uuid", orderId)).isEqualTo(cutoff);
    }

    @Test
    void payingCashTwiceGivesTheSameCode() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String first = payCash(fan, orderId);
        String second = payCash(fan, orderId);
        assertThat(second).isEqualTo(first);
        assertThat(payments(orderId)).isEqualTo(1);
    }

    @Test
    void lessThanTheMinWindowIsTooLate() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        salesCloseIn(s.eventId(), "1 hour 59 minutes");
        ResultActions r = payWith(fan, orderId, "CASH_AGENCY");
        error(r, "METHOD_NOT_AVAILABLE", "TOO_LATE");
        Instant cutoff = dbInstant("select refund_cutoff_at from events where id = ?", s.eventId());
        assertThat(Instant.parse(JsonPath.read(body(r), "$.details.sales_close_at"))).isEqualTo(cutoff);
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        assertThat(payments(orderId)).isZero();
        assertThat(paymentMethods(s.eventId())).isEqualTo(List.of("CARD"));
        // cash_until is shown once cash is set up, past or not: clients say "Trop tard pour le cash" after it.
        assertThat(cashUntil(s.eventId())).isEqualTo(cutoff.minus(Duration.ofHours(2)));
        payment(fan, orderId); // card is still open
    }

    /** Q46: quota 10, 20 % → 2 live cash places; a third is refused, card stays open. */
    @Test
    void cashHoldsAreCappedPerEvent() throws Exception {
        Sale s = cashSale(10);
        for (int i = 0; i < 2; i++) {
            String fan = fanToken(randomPhone());
            payCash(fan, order(fan, s, 1));
        }
        String third = fanToken(randomPhone());
        String orderId = order(third, s, 1);
        error(payWith(third, orderId, "CASH_AGENCY"), "METHOD_NOT_AVAILABLE", "CASH_CAP");
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        assertThat(payments(orderId)).isZero();
        payment(third, orderId);
    }

    @Test
    void noCashPointAndNoNetworkIsNotConfigured() throws Exception {
        Sale s = onSale(10); // no cash_points
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        error(payWith(fan, orderId, "CASH_AGENCY"), "METHOD_NOT_AVAILABLE", "NOT_CONFIGURED");
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        assertThat(paymentMethods(s.eventId())).isEqualTo(List.of("CARD"));
        assertThat(cashUntil(s.eventId())).isNull(); // no cash set up: clients hide the row

        Sale cash = cashSale(10);
        assertThat(paymentMethods(cash.eventId())).isEqualTo(List.of("CARD", "CASH_AGENCY"));
        assertThat(cashUntil(cash.eventId())).isEqualTo(
                dbInstant("select refund_cutoff_at from events where id = ?", cash.eventId()).minus(Duration.ofHours(2)));
    }

    /** payments.wallet.provider unset (prod until Q47): 409 NOT_CONFIGURED, after the order checks, nothing written. */
    @Test
    void walletIsNotConfiguredYet() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        error(payWith(fan, orderId, "WALLET"), "METHOD_NOT_AVAILABLE", "NOT_CONFIGURED");
        payWith(fanToken(randomPhone()), orderId, "WALLET").andExpect(status().isNotFound());
        assertThat(payments(orderId)).isZero();
    }

    @Test
    void theLimitCountsAwaitingCash() throws Exception {
        Sale s = cashSale(100);
        String fan = fanToken(randomPhone());
        payCash(fan, order(fan, s, 3));
        fan(fan, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 2, "holders": %s}]}
                """.formatted(s.eventId(), s.priceId(), holders(2)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT"))
                .andExpect(jsonPath("$.details.held").value(3));
    }

    @Test
    void cardOnAnAwaitingCashOrderPaysIt() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 2);
        String code = JsonPath.read(payCash(fan, orderId), "$.pay_code");
        String json = body(pay(fan, orderId).andExpect(status().isOk()));
        assertKeys(json, "$", "payment_id", "method", "redirect_url");
        String p = JsonPath.read(json, "$.payment_id");
        assertThat(JsonPath.<String>read(json, "$.method")).isEqualTo("CARD");
        assertThat(JsonPath.<String>read(json, "$.redirect_url")).isEqualTo(BASE + "/payments/card/" + p + "/redirect");
        // The latest attempt is a CARD still PENDING: the code stays shown and payable meanwhile.
        String waiting = orderJson(fan, orderId);
        assertThat(JsonPath.<String>read(waiting, "$.payment_method")).isEqualTo("CARD");
        assertThat(JsonPath.<String>read(waiting, "$.cash.pay_code")).isEqualTo(code);

        callback(approve(p, total(orderId))).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(tickets(orderId)).isEqualTo(2);
        assertThat(sold(s.priceId())).isEqualTo(2);
        String paid = orderJson(fan, orderId);
        assertThat(JsonPath.<String>read(paid, "$.payment_method")).isEqualTo("CARD");
        assertThat(JsonPath.<String>read(paid, "$.payment_status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Object>read(paid, "$.cash")).isNull();
        assertThat(jdbc.sql("select payment_method::text from orders where id = ?::uuid").param(orderId)
                .query(String.class).single()).isEqualTo("CARD");
        // The code payment is left PENDING: a later confirmation of it is a refund (BE-24 callback, NOT_PAYABLE).
        assertThat(jdbc.sql("select status::text from payments where pay_code = ?").param(code).query(String.class).single())
                .isEqualTo("PENDING");
        payWith(fan, orderId, "CASH_AGENCY").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
    }

    /** P37: the expiry moves AWAITING_CASH past its deadline to EXPIRED and gives the places back once. */
    @Test
    void anAwaitingCashOrderExpiresAtItsDeadlineOnce() throws Exception {
        Sale s = cashSale(30); // cap: 6 live cash places
        List<String> due = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String fan = fanToken(randomPhone());
            String orderId = order(fan, s, 1);
            payCash(fan, orderId);
            due.add(orderId);
        }
        String keptFan = fanToken(randomPhone());
        String kept = order(keptFan, s, 1);
        payCash(keptFan, kept);
        assertThat(sold(s.priceId())).isEqualTo(5);
        orderService.expireDue();
        assertThat(orderStatus(due.getFirst())).isEqualTo("AWAITING_CASH"); // not due yet
        due.forEach(id -> jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid")
                .param(id).update());

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
            for (var f : passes) {
                f.get();
            }
        }
        due.forEach(id -> assertThat(orderStatus(id)).isEqualTo("EXPIRED"));
        assertThat(orderStatus(kept)).isEqualTo("AWAITING_CASH");
        assertThat(sold(s.priceId())).isEqualTo(1);
        // The code payment stays PENDING (late success, §Cash codes Expiry); the order no longer shows it.
        assertThat(jdbc.sql("select status::text from payments where order_id = ?::uuid").param(due.getFirst())
                .query(String.class).single()).isEqualTo("PENDING");
        orderService.expireDue();
        assertThat(sold(s.priceId())).isEqualTo(1);
    }

    @Test
    void anExpiredCashOrderCanNotBePaid() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        payCash(fan, orderId);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId).update();
        fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));
        pay(fan, orderId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));
        orderService.expireDue();
        String got = orderJson(fan, orderId);
        assertThat(JsonPath.<String>read(got, "$.status")).isEqualTo("EXPIRED");
        assertThat(JsonPath.<Object>read(got, "$.cash")).isNull();
    }

    /** P35: the 20 min FAILED rule is for CARD/WALLET only; a cash code hours old is still PENDING. */
    @Test
    void aThreeHourOldCodeIsStillPending() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        payCash(fan, orderId);
        jdbc.sql("update payments set created_at = now() - interval '3 hours' where order_id = ?::uuid").param(orderId).update();
        assertThat(JsonPath.<String>read(orderJson(fan, orderId), "$.payment_status")).isEqualTo("PENDING");
    }

    /** §Cash codes Stored: a taken code is drawn again; the third draw is stored, the transaction still usable. */
    @Test
    void aCollidingCodeIsDrawnAgain() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String taken = JsonPath.read(payCash(fan, order(fan, s, 1)), "$.pay_code");
        String other = order(fanToken(randomPhone()), s, 1);
        UUID org = s.org().org();
        String fresh;
        do {
            fresh = CashCode.draw();
        } while (fresh.equals(taken));
        Iterator<String> draws = List.of(taken, taken, fresh).iterator();
        List<String> tried = new ArrayList<>();
        Payment stored = new TransactionTemplate(transactions).execute(t -> CashCode.issue(() -> {
            String c = draws.next();
            tried.add(c);
            return c;
        }, code -> payments.insertCashPending(org, UUID.fromString(other), code, 5000)));
        assertThat(tried).containsExactly(taken, taken, fresh);
        assertThat(stored.payCode()).isEqualTo(fresh);
        assertThat(jdbc.sql("select count(*) from payments where pay_code in (?, ?)").params(taken, fresh)
                .query(Integer.class).single()).isEqualTo(2);
    }

    /** §Cash codes Payable: a code no longer PENDING is reissued on the same hold, with min-window of it left. */
    @Test
    void aFailedCodeIsReissuedOnTheSameHoldWhileEnoughIsLeft() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String first = payCash(fan, orderId);
        String firstCode = JsonPath.read(first, "$.pay_code");
        jdbc.sql("update payments set status = 'FAILED', completed_at = now() where order_id = ?::uuid").param(orderId).update();

        jdbc.sql("update orders set reserved_until = now() + interval '1 hour 59 minutes' where id = ?::uuid").param(orderId)
                .update();
        error(payWith(fan, orderId, "CASH_AGENCY"), "METHOD_NOT_AVAILABLE", "TOO_LATE");
        assertThat(payments(orderId)).isEqualTo(1);

        jdbc.sql("update orders set reserved_until = now() + interval '3 hours' where id = ?::uuid").param(orderId).update();
        Instant held = dbInstant("select reserved_until from orders where id = ?::uuid", orderId);
        String again = payCash(fan, orderId);
        assertThat(JsonPath.<String>read(again, "$.pay_code")).matches(CODE).isNotEqualTo(firstCode);
        assertThat(Instant.parse(JsonPath.read(again, "$.reserved_until"))).isEqualTo(held);
        assertThat(dbInstant("select reserved_until from orders where id = ?::uuid", orderId)).isEqualTo(held);
        assertThat(payments(orderId)).isEqualTo(2);
        assertThat(JsonPath.<String>read(orderJson(fan, orderId), "$.cash.pay_code"))
                .isEqualTo(JsonPath.<String>read(again, "$.pay_code"));
    }

    // ---------- GET /fan/orders ----------

    @Test
    void theFanOrderListPutsAwaitingCashFirst() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String cash = order(fan, s, 1);
        String code = JsonPath.read(payCash(fan, cash), "$.pay_code");
        String card = order(fan, s, 1);
        String p = payment(fan, card);
        String plain = order(fan, s, 1);
        jdbc.sql("update orders set created_at = now() - interval '1 hour' where id = ?::uuid").param(cash).update();
        String strangers = order(fanToken(randomPhone()), s, 1);

        String json = body(fan(fan, get("/fan/orders")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertKeys(json, "$", "orders");
        assertThat(JsonPath.<List<String>>read(json, "$.orders[*].id")).containsExactly(cash, plain, card)
                .doesNotContain(strangers);
        assertKeys(json, "$.orders[0]", "id", "event", "channel", "status", "total_mad", "reserved_until",
                "payment_method", "pay_code", "created_at", "group_role");
        assertKeys(json, "$.orders[0].event", "id", "title", "starts_at", "timezone");
        assertThat(JsonPath.<Integer>read(json, "$.orders[0].event.id")).isEqualTo(s.eventId());
        assertThat(JsonPath.<String>read(json, "$.orders[0].event.title")).isEqualTo("Match");
        assertThat(JsonPath.<String>read(json, "$.orders[0].event.timezone")).isEqualTo("Africa/Casablanca");
        assertThat(JsonPath.<String>read(json, "$.orders[0].status")).isEqualTo("AWAITING_CASH");
        assertThat(JsonPath.<String>read(json, "$.orders[0].payment_method")).isEqualTo("CASH_AGENCY");
        assertThat(JsonPath.<String>read(json, "$.orders[0].pay_code")).isEqualTo(code); // the PENDING code, 6 symbols
        assertThat(JsonPath.<Object>read(json, "$.orders[1].pay_code")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.orders[2].pay_code")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.orders[1].payment_method")).isNull();
        assertThat(JsonPath.<String>read(json, "$.orders[2].payment_method")).isEqualTo("CARD");
        assertThat(JsonPath.<Integer>read(json, "$.orders[2].total_mad")).isEqualTo(5000);
        assertThat(paymentStatus(p)).isEqualTo("PENDING");

        String empty = body(fan(fanToken(randomPhone()), get("/fan/orders")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<?>>read(empty, "$.orders")).isEmpty();
        mvc.perform(get("/fan/orders")).andExpect(status().isUnauthorized());
    }

    // ---------- P38: only an attempt whose hosted page was opened holds the order ----------

    @Test
    void aNeverRedirectedCardAttemptDoesNotHoldTheOrder() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        payment(fan, orderId);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId).update();
        orderService.expireDue();
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(sold(s.priceId())).isZero();
    }

    @Test
    void aRedirectedCardAttemptHoldsTheOrderFor20Minutes() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        assertThat(redirectedAt(p)).isNull();
        mvc.perform(get("/payments/card/" + p + "/redirect")).andExpect(status().isFound());
        Instant first = redirectedAt(p);
        assertThat(first).isNotNull();
        mvc.perform(get("/payments/card/" + p + "/redirect")).andExpect(status().isFound());
        assertThat(redirectedAt(p)).isEqualTo(first); // set once

        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId).update();
        orderService.expireDue();
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        jdbc.sql("update payments set redirected_at = now() - interval '20 minutes' where id = ?::uuid").param(p).update();
        orderService.expireDue();
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
    }

    @Test
    void aCashCodeHasNoHostedPage() throws Exception {
        Sale s = cashSale(10);
        String fan = fanToken(randomPhone());
        String p = JsonPath.read(payCash(fan, order(fan, s, 1)), "$.payment_id");
        for (var req : List.of(get("/payments/card/" + p + "/redirect"), get("/payments/fake/" + p),
                post("/payments/fake/" + p + "/late"))) {
            mvc.perform(req).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(paymentStatus(p)).isEqualTo("PENDING");
        assertThat(redirectedAt(p)).isNull();
    }

    private List<String> paymentMethods(int eventId) throws Exception {
        return JsonPath.read(body(mvc.perform(get("/fan/events/" + eventId)).andExpect(status().isOk())), "$.payment_methods");
    }

    /** The hosted page needs a running hold: past it, 410 and redirected_at stays unset. */
    @Test
    void aRedirectAfterTheHoldIsGone() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId).update();
        mvc.perform(get("/payments/card/" + p + "/redirect")).andExpect(status().isGone());
        assertThat(redirectedAt(p)).isNull();
        orderService.expireDue();
        mvc.perform(get("/payments/card/" + p + "/redirect")).andExpect(status().isGone());
        assertThat(redirectedAt(p)).isNull();
    }

    /** payment_status's 20 min counts from redirected_at when set, like the expiry grace. */
    @Test
    void aStaleAttemptIsCountedFromItsRedirect() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        mvc.perform(get("/payments/card/" + p + "/redirect")).andExpect(status().isFound());
        jdbc.sql("update payments set created_at = now() - interval '30 minutes', redirected_at = now() - interval '5 minutes'"
                + " where id = ?::uuid").param(p).update();
        assertThat(JsonPath.<String>read(orderJson(fan, orderId), "$.payment_status")).isEqualTo("PENDING");
        jdbc.sql("update payments set redirected_at = now() - interval '20 minutes' where id = ?::uuid").param(p).update();
        assertThat(JsonPath.<String>read(orderJson(fan, orderId), "$.payment_status")).isEqualTo("FAILED");
    }

    private Instant cashUntil(int eventId) throws Exception {
        String t = JsonPath.read(body(mvc.perform(get("/fan/events/" + eventId)).andExpect(status().isOk())), "$.cash_until");
        return t == null ? null : Instant.parse(t);
    }

    private Instant redirectedAt(String paymentId) {
        OffsetDateTime t = jdbc.sql("select redirected_at from payments where id = ?::uuid").param(paymentId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).list().getFirst();
        return t == null ? null : t.toInstant();
    }
}
