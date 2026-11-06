package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract: POST /fan/orders/{id}/pay, GET /payments/card/{id}/redirect, GET|POST /payments/card/{id}/return,
 * POST /payments/{provider}/callback, GET /payments/fake/{id} (docs/04-api.md §Fan, §Payments), plus
 * idempotency, late success (plan Q8) and the pay/expiry lock.
 */
class PaymentApiTest extends PaymentTest {

    @Autowired
    OrderService orderService;

    @Autowired
    PlatformTransactionManager transactions;

    private static void error(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    /** Makes the payment older than the 20 min grace and the order due, then runs the expiry. */
    private void expireWithStalePayment(String orderId, String paymentId) {
        jdbc.sql("update payments set created_at = now() - interval '21 minutes' where id = ?::uuid").param(paymentId).update();
        jdbc.sql("update orders set reserved_until = now() - interval '13 minutes' where id = ?::uuid").param(orderId).update();
        orderService.expireDue();
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
    }

    // ---------- pay ----------

    @Test
    void payCreatesAPendingAttemptEachTime() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 2);
        String first = payment(fan, orderId);
        String second = payment(fan, orderId);
        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.sql("""
                select string_agg(status::text || ' ' || method::text || ' ' || provider || ' ' || amount_mad || ' '
                                  || (organizer_id = ?), ',')
                from payments where order_id = ?::uuid
                """).params(s.org().org(), orderId).query(String.class).single())
                .isEqualTo("PENDING CARD FAKE 10000 true,PENDING CARD FAKE 10000 true");
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
    }

    @Test
    void payIsForTheBuyerOnAPayableOrderByCard() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        error(pay(fanToken(randomPhone()), orderId), 404, "NOT_FOUND");
        error(pay(fan, UUID.randomUUID().toString()), 404, "NOT_FOUND");
        error(mvc.perform(post("/fan/orders/" + orderId + "/pay").header("Authorization", "Bearer " + s.org().token())
                .contentType("application/json").content("{\"method\": \"CARD\"}")), 401, "UNAUTHORIZED");
        for (String method : List.of("CASH_AGENCY", "WALLET", "BITCOIN")) {
            fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"%s\"}".formatted(method))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.fields.method").isString());
        }
        assertThat(jdbc.sql("select count(*) from payments where order_id = ?::uuid").param(orderId).query(Integer.class).single())
                .isZero();
    }

    @Test
    void payOnAnExpiredOrPaidOrderIs409() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());

        String pastHold = order(fan, s, 1);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(pastHold).update();
        error(pay(fan, pastHold), 409, "ORDER_EXPIRED"); // not swept yet: still past its hold
        orderService.expireDue();
        error(pay(fan, pastHold), 409, "ORDER_EXPIRED");

        String paid = order(fan, s, 1);
        String p = payment(fan, paid);
        callback(approve(p, total(paid))).andExpect(status().isSeeOther());
        error(pay(fan, paid), 409, "ORDER_NOT_PAYABLE");
        String cancelled = order(fan, s, 1);
        jdbc.sql("update orders set status = 'CANCELLED' where id = ?::uuid").param(cancelled).update();
        error(pay(fan, cancelled), 409, "ORDER_NOT_PAYABLE");
    }

    /**
     * The expiry holds the order row (as its claim does) and flips it to EXPIRED while a pay is in flight: the
     * pay must wait for it and then refuse, never insert a payment the expiry didn't see.
     */
    @Test
    void aPayWaitsForTheExpiryHoldingItsOrder() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<MockHttpServletResponse> paid = new TransactionTemplate(transactions).execute(t -> {
                jdbc.sql("select 1 from orders where id = ?::uuid for no key update").param(orderId).query(Integer.class).single();
                Future<MockHttpServletResponse> f = pool.submit(() -> pay(fan, orderId).andReturn().getResponse());
                try {
                    f.get(1, TimeUnit.SECONDS);
                    throw new AssertionError("pay did not wait for the order lock");
                } catch (TimeoutException expected) {
                    jdbc.sql("update orders set status = 'EXPIRED' where id = ?::uuid").param(orderId).update();
                    return f;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            MockHttpServletResponse r = paid.get(10, TimeUnit.SECONDS);
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(r.getContentAsString(), "$.code")).isEqualTo("ORDER_EXPIRED");
        }
        assertThat(jdbc.sql("select count(*) from payments where order_id = ?::uuid").param(orderId).query(Integer.class).single())
                .isZero();
    }

    /** The other order of events: a pay that committed first keeps its order out of the expiry. */
    @Test
    void anOrderBeingPaidIsNotExpired() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId).update();
        orderService.expireDue();
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        callback(approve(p, total(orderId))).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(sold(s.priceId())).isEqualTo(1);
    }

    // ---------- redirect, return, gateway page ----------

    @Test
    void redirectIs302WhilePendingThen410() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        mvc.perform(get("/payments/card/" + p + "/redirect")).andExpect(status().isFound())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Location")).isEqualTo(BASE + "/payments/fake/" + p))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain(FAKE_SECRET));
        callback(signed(p, "DECLINED", total(orderId), "FAKE-" + UUID.randomUUID())).andExpect(status().isSeeOther());
        mvc.perform(get("/payments/card/" + p + "/redirect")).andExpect(status().isGone());
        error(mvc.perform(get("/payments/card/" + UUID.randomUUID() + "/redirect")), 404, "NOT_FOUND");
    }

    @Test
    void theGatewayPageNeverShowsTheSecret() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String p = payment(fan, order(fan, s, 3));
        String page = mvc.perform(get("/payments/fake/" + p)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("150,00 DH", "Approuver", "Refuser", "Réponse tardive").doesNotContain(FAKE_SECRET);
    }

    @Test
    void returnNeverChangesState() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        for (var req : List.of(get("/payments/card/" + p + "/return"), post("/payments/card/" + p + "/return")
                .param("outcome", "APPROVED").param("payment_id", p))) {
            mvc.perform(req).andExpect(status().isOk());
        }
        mvc.perform(get("/payments/card/" + UUID.randomUUID() + "/return")).andExpect(status().isOk());
        assertThat(paymentStatus(p)).isEqualTo("PENDING");
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        assertThat(tickets(orderId)).isZero();
    }

    // ---------- callback ----------

    @Test
    void theSameCallbackTwiceHasOneEffect() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 2);
        String p = payment(fan, orderId);
        Map<String, String> approval = approve(p, total(orderId));
        callback(approval).andExpect(status().isSeeOther());
        callback(approval).andExpect(status().isSeeOther());
        callback(signed(p, "DECLINED", total(orderId), "FAKE-" + UUID.randomUUID())).andExpect(status().isSeeOther());
        assertThat(paymentStatus(p)).isEqualTo("SUCCEEDED");
        assertThat(tickets(orderId)).isEqualTo(2);
        assertThat(sold(s.priceId())).isEqualTo(2);
        fan(fan, get("/fan/orders/" + orderId)).andExpect(jsonPath("$.ticket_ids.length()").value(2));
    }

    @Test
    void concurrentCallbacksOfOnePaymentHaveOneEffect() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        Map<String, String> approval = approve(p, total(orderId));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return callback(approval).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var r : results) {
                assertThat(r.get()).isEqualTo(303);
            }
        }
        assertThat(tickets(orderId)).isEqualTo(1);
        assertThat(sold(s.priceId())).isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
    }

    @Test
    void aDeclineLeavesTheOrderPayable() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String first = payment(fan, orderId);
        callback(signed(first, "DECLINED", total(orderId), "FAKE-" + UUID.randomUUID())).andExpect(status().isSeeOther());
        assertThat(paymentStatus(first)).isEqualTo("FAILED");
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        assertThat(tickets(orderId)).isZero();
        assertThat(sold(s.priceId())).isEqualTo(1); // still held

        String second = payment(fan, orderId);
        callback(approve(second, total(orderId))).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(tickets(orderId)).isEqualTo(1);
    }

    @Test
    void anAmountMismatchFailsThePayment() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        callback(approve(p, total(orderId) - 1)).andExpect(status().isSeeOther());
        assertThat(paymentStatus(p)).isEqualTo("FAILED");
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        assertThat(tickets(orderId)).isZero();
        // The card was charged anyway: flagged for a manual refund of what was charged.
        assertRefund(p, orderId, "AMOUNT_MISMATCH");
        assertThat(jdbc.sql("select (payload ->> 'amount_mad')::bigint from audit_log where target = ?").param(p)
                .query(Long.class).single()).isEqualTo(total(orderId) - 1);
        // Still payable.
        callback(approve(payment(fan, orderId), total(orderId))).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
    }

    @Test
    void aBadSignatureChangesNothing() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        Map<String, String> forged = new HashMap<>(approve(p, total(orderId)));
        forged.put("signature", forged.get("signature").replaceFirst(".$", forged.get("signature").endsWith("0") ? "1" : "0"));
        Map<String, String> tampered = new HashMap<>(approve(p, total(orderId)));
        tampered.put("amount_mad", "1");
        Map<String, String> unsigned = new HashMap<>(approve(p, total(orderId)));
        unsigned.remove("signature");
        for (var fields : List.of(forged, tampered, unsigned, Map.<String, String>of())) {
            callback(fields).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Invalid callback"))
                    .andExpect(jsonPath("$.details").isEmpty());
        }
        assertThat(paymentStatus(p)).isEqualTo("PENDING");
        assertThat(jdbc.sql("select callback is null and provider_ref is null from payments where id = ?::uuid").param(p)
                .query(Boolean.class).single()).isTrue();
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        assertThat(tickets(orderId)).isZero();
    }

    @Test
    void unknownPaymentOrProviderIs404() throws Exception {
        error(callback(approve(UUID.randomUUID().toString(), 5000)), 404, "NOT_FOUND");
        error(mvc.perform(post("/payments/payzone/callback").param("x", "1")), 404, "NOT_FOUND");
    }

    // ---------- late success (plan Q8) ----------

    @Test
    void aLateSuccessReReservesThePlaces() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 2);
        String p = payment(fan, orderId);
        expireWithStalePayment(orderId, p);
        assertThat(sold(s.priceId())).isZero();

        callback(approve(p, total(orderId))).andExpect(status().isSeeOther());
        assertThat(paymentStatus(p)).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(sold(s.priceId())).isEqualTo(2);
        assertThat(tickets(orderId)).isEqualTo(2);
        assertThat(refundsRequired(p)).isZero();
    }

    @Test
    void aLateSuccessWithNoPlaceLeftIsARefund() throws Exception {
        Sale s = onSale(2);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 2);
        String p = payment(fan, orderId);
        expireWithStalePayment(orderId, p);
        order(fanToken(randomPhone()), s, 2); // someone else takes the places back

        callback(approve(p, total(orderId))).andExpect(status().isSeeOther());
        assertThat(paymentStatus(p)).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(sold(s.priceId())).isEqualTo(2);
        assertThat(tickets(orderId)).isZero();
        assertRefund(p, orderId, "SOLD_OUT");
    }

    @Test
    void aLateSuccessOverTheAccountLimitIsARefund() throws Exception {
        Sale s = onSale(10); // per_account_limit 4
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 3);
        String p = payment(fan, orderId);
        expireWithStalePayment(orderId, p);
        order(fan, s, 2); // the buyer holds 2 again: 2 + 3 > 4

        callback(approve(p, total(orderId))).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(sold(s.priceId())).isEqualTo(2);
        assertThat(tickets(orderId)).isZero();
        assertRefund(p, orderId, "PER_ACCOUNT_LIMIT");
    }

    @Test
    void aSecondSuccessOnAPaidOrderIsARefund() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String first = payment(fan, orderId);
        String second = payment(fan, orderId);
        callback(approve(first, total(orderId))).andExpect(status().isSeeOther());
        callback(approve(second, total(orderId))).andExpect(status().isSeeOther());
        assertThat(paymentStatus(second)).isEqualTo("SUCCEEDED");
        assertThat(tickets(orderId)).isEqualTo(1);
        assertRefund(second, orderId, "NOT_PAYABLE");
    }

    @Test
    void theLateButtonApprovesAfterTheDelay() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        mvc.perform(post("/payments/fake/" + p + "/late")).andExpect(status().isSeeOther())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Location"))
                        .isEqualTo(BASE + "/payments/card/" + p + "/return"));
        assertThat(paymentStatus(p)).isEqualTo("PENDING"); // payments.fake.late-delay=1s in tests
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!orderStatus(orderId).equals("PAID") && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(paymentStatus(p)).isEqualTo("SUCCEEDED");
        assertThat(tickets(orderId)).isEqualTo(1);

        // Done: the late button is gone too, and queues nothing more.
        mvc.perform(post("/payments/fake/" + p + "/late")).andExpect(status().isGone());
    }

    @Test
    void theLateButtonIs410OnceThePaymentIsDone() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String p = payment(fan, orderId);
        callback(signed(p, "DECLINED", total(orderId), "FAKE-" + UUID.randomUUID())).andExpect(status().isSeeOther());
        mvc.perform(post("/payments/fake/" + p + "/late")).andExpect(status().isGone());
        Thread.sleep(1500); // past payments.fake.late-delay: had a task been queued, it would have run
        assertThat(paymentStatus(p)).isEqualTo("FAILED");
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
    }

    /** Two categories, the second sold out meanwhile: the first is given back, nothing stays taken. */
    @Test
    void aLateSuccessHalfSoldOutGivesTheFirstCategoryBack() throws Exception {
        Sale s = onSale(10);
        // Inventory takes categories in UUID order: make the scarce one come second, so the first is really taken
        // and has to be given back.
        String scarce = price(s.org(), s.eventId(), s.zoneId(), "Abonné", 1);
        while (UUID.fromString(scarce).compareTo(UUID.fromString(s.priceId())) < 0) {
            call(s.org(), delete("/org/events/" + s.eventId() + "/prices/" + scarce)).andExpect(status().isNoContent());
            scarce = price(s.org(), s.eventId(), s.zoneId(), "Abonné", 1);
        }
        String fan = fanToken(randomPhone());
        String orderId = JsonPath.read(body(fan(fan, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 2},
                                                             {"price_category_id": "%s", "qty": 1}]}
                """.formatted(s.eventId(), s.priceId(), scarce)).andExpect(status().isCreated())), "$.id");
        String p = payment(fan, orderId);
        expireWithStalePayment(orderId, p);
        fan(fanToken(randomPhone()), post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 1}]}
                """.formatted(s.eventId(), scarce)).andExpect(status().isCreated());
        assertThat(sold(s.priceId())).isZero();
        assertThat(sold(scarce)).isEqualTo(1);

        callback(approve(p, total(orderId))).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(sold(s.priceId())).isZero(); // taken first, then given back
        assertThat(sold(scarce)).isEqualTo(1);
        assertThat(tickets(orderId)).isZero();
        assertRefund(p, orderId, "SOLD_OUT");
    }

    private void assertRefund(String paymentId, String orderId, String reason) {
        String payload = jdbc.sql("""
                select payload::text from audit_log
                where action = 'payment.refund_required' and target = ? and actor_type = 'SYSTEM'
                """).param(paymentId).query(String.class).single();
        assertThat(JsonPath.<String>read(payload, "$.reason")).isEqualTo(reason);
        assertThat(JsonPath.<String>read(payload, "$.order_id")).isEqualTo(orderId);
        assertThat(payload).doesNotContain(FAKE_SECRET).doesNotContain("signature");
    }
}
