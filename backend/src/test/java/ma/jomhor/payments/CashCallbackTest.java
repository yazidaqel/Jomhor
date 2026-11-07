package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract (BE-24): POST /payments/cash/{network}/callback and the simulated terminal GET, POST /payments/cash/fake
 * (docs/04-api.md §Payments, §Cash codes), and WALLET through the simulated hosted page (plan Q47). This context
 * enables the fake network and the fake wallet; CashOrderApiTest covers both unset.
 */
@TestPropertySource(properties = {"payments.cash.networks=fake", "payments.wallet.provider=fake"})
class CashCallbackTest extends PaymentTest {

    private static final String NAME = "Yazid Aqel"; // the fixture holder (OrgApiTest.holders)

    @Autowired
    OrderService orderService;

    @Autowired
    PlatformTransactionManager transactions;

    /** A fan order on {@code s} with a cash code: the fan's phone, the order, the code (6 symbols). */
    private record Code(String phone, String orderId, String code, String paymentId) {}

    private Code cashCode(Sale s, int qty) throws Exception {
        String phone = randomPhone();
        String fan = fanToken(phone);
        String orderId = order(fan, s, qty);
        String json = body(fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk()));
        return new Code(phone, orderId, JsonPath.read(json, "$.pay_code"), JsonPath.read(json, "$.payment_id"));
    }

    static Map<String, String> signed(String payCode, long amountMad, String ref) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(FAKE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String amount = Long.toString(amountMad);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pay_code", payCode);
        m.put("amount_mad", amount);
        m.put("network_ref", ref);
        m.put("signature", HexFormat.of().formatHex(mac.doFinal(String.join("|", payCode, amount, ref)
                .getBytes(StandardCharsets.UTF_8))));
        return m;
    }

    private ResultActions cashCallback(Map<String, String> fields) throws Exception {
        var req = post("/payments/cash/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        fields.forEach(req::param);
        return mvc.perform(req);
    }

    private static String ref() {
        return "NET-" + UUID.randomUUID();
    }

    private String payment(Code c) {
        return jdbc.sql("select status || ' ' || provider || ' ' || coalesce(provider_ref, '-') from payments where id = ?::uuid")
                .param(c.paymentId()).query(String.class).single();
    }

    private int audits(String action, String target) {
        return jdbc.sql("select count(*) from audit_log where action = ? and target = ?").params(action, target)
                .query(Integer.class).single();
    }

    private String refundReason(String paymentId) {
        return jdbc.sql("select payload->>'reason' from audit_log where action = 'payment.refund_required' and target = ?")
                .param(paymentId).query(String.class).single();
    }

    // ---------- signature ----------

    @Test
    void aBadSignatureIs401AndWritesNothing() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 2);
        long total = total(c.orderId());
        Map<String, String> good = signed(c.code(), total, ref());
        Map<String, String> tampered = new LinkedHashMap<>(good);
        tampered.put("amount_mad", "1");
        Map<String, String> missing = new LinkedHashMap<>(good);
        missing.remove("signature");
        Map<String, String> wrongKey = new LinkedHashMap<>(good);
        wrongKey.put("signature", "00".repeat(32));
        Map<String, String> piped = signed(c.code(), total, "NET|1"); // signed, but '|' inside a field
        for (var fields : List.of(tampered, missing, wrongKey, piped)) {
            cashCallback(fields).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Invalid callback"));
        }
        assertThat(payment(c)).isEqualTo("PENDING CASH -");
        assertThat(orderStatus(c.orderId())).isEqualTo("AWAITING_CASH");
        assertThat(tickets(c.orderId())).isZero();
        assertThat(jdbc.sql("select count(*) from audit_log where target = ?").param(c.paymentId()).query(Integer.class)
                .single()).isZero();
        // An unknown network has no callback, signed or not.
        var other = post("/payments/cash/wafacash/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        good.forEach(other::param);
        mvc.perform(other).andExpect(status().isNotFound());
    }

    // ---------- terminal + good callback ----------

    /** The terminal shows the event title and amount only; Encaisser posts the signed callback → PAID + tickets. */
    @Test
    void theTerminalCollectsACode() throws Exception {
        Sale s = onSale(10);
        jdbc.sql("update events set title = 'Atlas & Raja' where id = ?").param(s.eventId()).update();
        Code c = cashCode(s, 2);
        mvc.perform(get("/payments/cash/fake")).andExpect(status().isOk());

        String typed = "jmh " + c.code().substring(0, 4).toLowerCase() + "-" + c.code().substring(4); // §Cash codes Input
        String page = body(mvc.perform(post("/payments/cash/fake").param("code", typed)).andExpect(status().isOk()));
        assertThat(page).contains("Atlas &amp; Raja", "100,00 DH", "Encaisser")
                .doesNotContain(NAME, "Yazid", "Aqel", c.phone(), c.phone().substring(1), "Yazid A.", FAKE_SECRET);

        var form = Pattern.compile("<form method=\"post\" action=\"fake/callback\">(.*?)</form>", Pattern.DOTALL)
                .matcher(page);
        assertThat(form.find()).isTrue();
        Map<String, String> fields = new LinkedHashMap<>();
        var input = Pattern.compile("name=\"([^\"]+)\" value=\"([^\"]*)\"").matcher(form.group(1));
        while (input.find()) {
            fields.put(input.group(1), input.group(2));
        }
        assertThat(fields).containsKeys("pay_code", "amount_mad", "network_ref", "signature")
                .containsEntry("pay_code", c.code()).containsEntry("amount_mad", "10000");

        assertThat(body(cashCallback(fields).andExpect(status().isOk()))).contains("Encaissement enregistré");
        assertThat(payment(c)).isEqualTo("SUCCEEDED FAKE_CASH " + fields.get("network_ref"));
        assertThat(orderStatus(c.orderId())).isEqualTo("PAID");
        assertThat(jdbc.sql("select payment_method || ' ' || payment_ref from orders where id = ?::uuid")
                .param(c.orderId()).query(String.class).single()).isEqualTo("CASH_AGENCY " + c.code());
        assertThat(tickets(c.orderId())).isEqualTo(2);
        assertThat(sold(s.priceId())).isEqualTo(2);
        assertThat(jdbc.sql("""
                select count(*) from ticket_deliveries d join tickets t on t.id = d.ticket_id where t.order_id = ?::uuid""")
                .param(c.orderId()).query(Integer.class).single()).isEqualTo(2);

        // Paid: the terminal no longer offers it.
        assertThat(body(mvc.perform(post("/payments/cash/fake").param("code", c.code()))))
                .contains("Code inconnu ou non payable").doesNotContain("Encaisser<");
    }

    @Test
    void theSameCallbackTwiceHasOneEffect() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 1);
        Map<String, String> fields = signed(c.code(), total(c.orderId()), ref());
        cashCallback(fields).andExpect(status().isOk());
        assertThat(body(cashCallback(fields).andExpect(status().isOk()))).contains("déjà enregistré");
        assertThat(tickets(c.orderId())).isEqualTo(1);
        assertThat(sold(s.priceId())).isEqualTo(1);
        assertThat(audits("payment.refund_required", c.paymentId())).isZero();
    }

    @Test
    void anUnknownCodeWritesNothing() throws Exception {
        assertThat(body(cashCallback(signed("ZZZZZZ", 5000, ref())).andExpect(status().isNotFound())))
                .contains("Code inconnu");
        cashCallback(signed("not a code", 5000, ref())).andExpect(status().isNotFound());
        assertThat(body(mvc.perform(post("/payments/cash/fake").param("code", "ZZZZZZ")).andExpect(status().isOk())))
                .contains("Code inconnu ou non payable");
    }

    // ---------- amount, late success, paid twice, reused ref ----------

    /** 04 §Payments: FAILED + refund_required AMOUNT_MISMATCH; the order stays payable (a new code on /pay). */
    @Test
    void anAmountMismatchFailsTheCodeAndFlagsARefund() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 1);
        String ref = ref();
        Map<String, String> fields = signed(c.code(), 4000, ref);
        cashCallback(fields).andExpect(status().isConflict());
        assertThat(payment(c)).isEqualTo("FAILED FAKE_CASH " + ref);
        assertThat(refundReason(c.paymentId())).isEqualTo("AMOUNT_MISMATCH");
        assertThat(jdbc.sql("select (payload->>'amount_mad')::bigint from audit_log where target = ?").param(c.paymentId())
                .query(Long.class).single()).isEqualTo(4000L);
        assertThat(orderStatus(c.orderId())).isEqualTo("AWAITING_CASH");
        assertThat(tickets(c.orderId())).isZero();
        cashCallback(fields).andExpect(status().isConflict()); // the same callback again: same answer, no second flag
        assertThat(audits("payment.refund_required", c.paymentId())).isEqualTo(1);

        // The failed code is no longer payable: a confirmation under a new ref is cash taken twice.
        String other = ref();
        cashCallback(signed(c.code(), 5000, other)).andExpect(status().isOk());
        assertThat(payment(c)).isEqualTo("FAILED FAKE_CASH " + ref);
        assertThat(jdbc.sql("""
                select payload->>'reason' from audit_log where action = 'payment.refund_required' and target = ?
                  and payload->>'provider_ref' = ?""").params(c.paymentId(), other).query(String.class).single())
                .isEqualTo("NOT_PAYABLE");
        cashCallback(signed(c.code(), 5000, other)).andExpect(status().isOk()); // replay: acknowledged, no new flag
        assertThat(audits("payment.refund_required", c.paymentId())).isEqualTo(2);
        assertThat(tickets(c.orderId())).isZero();
    }

    /** The fan paid by card while the code was out: the network's later confirmation is a refund; PAID once. */
    @Test
    void aCardSuccessThenANetworkConfirmationFlagsARefund() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String json = body(fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}").andExpect(status().isOk()));
        Code c = new Code(null, orderId, JsonPath.read(json, "$.pay_code"), JsonPath.read(json, "$.payment_id"));
        String card = payment(fan, orderId);
        callback(approve(card, 5000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(payment(c)).isEqualTo("PENDING CASH -"); // the code stays as it was

        cashCallback(signed(c.code(), 5000, ref())).andExpect(status().isOk());
        assertThat(payment(c)).startsWith("SUCCEEDED FAKE_CASH ");
        assertThat(refundReason(c.paymentId())).isEqualTo("NOT_PAYABLE");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(jdbc.sql("select payment_method::text from orders where id = ?::uuid").param(orderId)
                .query(String.class).single()).isEqualTo("CARD");
        assertThat(tickets(orderId)).isEqualTo(1);
        assertThat(sold(s.priceId())).isEqualTo(1);
    }

    @Test
    void afterExpiryWithQuotaLeftTheOrderIsPaid() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 1);
        expire(c);
        assertThat(sold(s.priceId())).isZero();
        cashCallback(signed(c.code(), total(c.orderId()), ref())).andExpect(status().isOk());
        assertThat(orderStatus(c.orderId())).isEqualTo("PAID");
        assertThat(tickets(c.orderId())).isEqualTo(1);
        assertThat(sold(s.priceId())).isEqualTo(1);
        assertThat(audits("payment.refund_required", c.paymentId())).isZero();
    }

    @Test
    void afterExpiryWithoutQuotaARefundIsFlagged() throws Exception {
        Sale s = onSale(5); // 20 % cash cap: one cash place
        Code c = cashCode(s, 1);
        expire(c);
        order(fanToken(randomPhone()), s, 4); // the places go to others
        order(fanToken(randomPhone()), s, 1);
        cashCallback(signed(c.code(), total(c.orderId()), ref())).andExpect(status().isOk());
        assertThat(payment(c)).startsWith("SUCCEEDED FAKE_CASH ");
        assertThat(orderStatus(c.orderId())).isEqualTo("EXPIRED");
        assertThat(tickets(c.orderId())).isZero();
        assertThat(refundReason(c.paymentId())).isEqualTo("SOLD_OUT");
    }

    /** Collected at a club till first, then confirmed by the network: the network's cash goes back (once). */
    @Test
    void afterAGuichetCollectARefundIsFlaggedOnce() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 1);
        Caller cashier = caller(s.org().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        call(cashier, post("/org/pos/codes/" + c.code() + "/collect"), "{\"amount_mad\": 5000}").andExpect(status().isOk());

        Map<String, String> fields = signed(c.code(), 5000, ref());
        cashCallback(fields).andExpect(status().isOk());
        cashCallback(fields).andExpect(status().isOk());
        assertThat(payment(c)).isEqualTo("SUCCEEDED POS -");
        assertThat(refundReason(c.paymentId())).isEqualTo("NOT_PAYABLE");
        assertThat(jdbc.sql("select payload->>'provider' from audit_log where target = ?").param(c.paymentId())
                .query(String.class).single()).isEqualTo("FAKE_CASH");
        assertThat(tickets(c.orderId())).isEqualTo(1);
        // Another ref for the same code: another cash taken, another refund.
        cashCallback(signed(c.code(), 5000, ref())).andExpect(status().isOk());
        assertThat(audits("payment.refund_required", c.paymentId())).isEqualTo(2);
    }

    /** A ref first spent on a refund (code A collected at a till) can't then pay code B, and the other way round. */
    @Test
    void aRefSpentOnARefundIsUsed() throws Exception {
        Sale s = onSale(20);
        Caller cashier = caller(s.org().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());

        Code a = cashCode(s, 1);
        Code b = cashCode(s, 1);
        call(cashier, post("/org/pos/codes/" + a.code() + "/collect"), "{\"amount_mad\": 5000}").andExpect(status().isOk());
        String r = ref();
        cashCallback(signed(a.code(), 5000, r)).andExpect(status().isOk()); // A: NOT_PAYABLE refund under r
        cashCallback(signed(b.code(), 5000, r)).andExpect(status().isConflict());
        assertThat(payment(b)).isEqualTo("PENDING CASH -");
        assertThat(audits("payment.duplicate_ref", b.paymentId())).isEqualTo(1);
        assertThat(audits("payment.refund_required", b.paymentId())).isZero();
        cashCallback(signed(a.code(), 5000, r)).andExpect(status().isOk()); // A's replay: acknowledged, no new flag
        assertThat(audits("payment.refund_required", a.paymentId())).isEqualTo(1);

        // The other order: r2 pays pending code D, then r2 on collected code C is refused (no refund flagged).
        Code c = cashCode(s, 1);
        Code d = cashCode(s, 1);
        call(cashier, post("/org/pos/codes/" + c.code() + "/collect"), "{\"amount_mad\": 5000}").andExpect(status().isOk());
        String r2 = ref();
        cashCallback(signed(d.code(), 5000, r2)).andExpect(status().isOk());
        assertThat(orderStatus(d.orderId())).isEqualTo("PAID");
        cashCallback(signed(c.code(), 5000, r2)).andExpect(status().isConflict());
        assertThat(audits("payment.duplicate_ref", c.paymentId())).isEqualTo(1);
        assertThat(audits("payment.refund_required", c.paymentId())).isZero();
    }

    /**
     * The duplicate check runs before the write: a ref committed by another transaction in between hits the unique
     * index, and the callback still answers REFUSED + payment.duplicate_ref (never a 500).
     */
    @Test
    void aRefTakenByARacingTransactionIsRefused() throws Exception {
        Sale s = onSale(10);
        Code a = cashCode(s, 1);
        Code b = cashCode(s, 1);
        String r = ref();
        var tx = new TransactionTemplate(transactions);
        var written = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> racer = pool.submit(() -> tx.executeWithoutResult(t -> {
                jdbc.sql("update payments set provider = 'FAKE_CASH', provider_ref = ? where id = ?::uuid")
                        .params(r, b.paymentId()).update(); // B takes r, uncommitted
                written.countDown();
                try {
                    commit.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
            written.await();
            Future<Integer> callback = pool.submit(() -> cashCallback(signed(a.code(), 5000, r)).andReturn()
                    .getResponse().getStatus());
            // Wait until A's write waits on B's uncommitted index entry, then let B commit.
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (jdbc.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock'")
                    .query(Integer.class).single() == 0) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.onSpinWait();
            }
            commit.countDown();
            racer.get();
            assertThat(callback.get()).isEqualTo(409);
        }
        assertThat(payment(a)).isEqualTo("PENDING CASH -");
        assertThat(orderStatus(a.orderId())).isEqualTo("AWAITING_CASH");
        assertThat(audits("payment.duplicate_ref", a.paymentId())).isEqualTo(1);
        assertThat(tickets(a.orderId())).isZero();
    }

    /** The till and the network race on one code: one wins, the other is refused or flagged; tickets once. */
    @Test
    void aGuichetCollectAndANetworkCallbackRace() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 1);
        Caller cashier = caller(s.org().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        Map<String, String> fields = signed(c.code(), 5000, ref());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> till = pool.submit(() -> {
                start.await();
                return call(cashier, post("/org/pos/codes/" + c.code() + "/collect"), "{\"amount_mad\": 5000}")
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> network = pool.submit(() -> {
                start.await();
                return cashCallback(fields).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(network.get()).isEqualTo(200);
            int t = till.get();
            assertThat(t).isIn(200, 409);
            if (t == 200) {
                assertThat(payment(c)).isEqualTo("SUCCEEDED POS -");
                assertThat(refundReason(c.paymentId())).isEqualTo("NOT_PAYABLE");
            } else {
                assertThat(payment(c)).startsWith("SUCCEEDED FAKE_CASH ");
                assertThat(audits("payment.refund_required", c.paymentId())).isZero();
            }
        }
        assertThat(orderStatus(c.orderId())).isEqualTo("PAID");
        assertThat(tickets(c.orderId())).isEqualTo(1);
    }

    @Test
    void aNetworkRefReusedOnAnotherCodeIsRefused() throws Exception {
        Sale s = onSale(10);
        Code first = cashCode(s, 1);
        Code second = cashCode(s, 1);
        String ref = ref();
        cashCallback(signed(first.code(), 5000, ref)).andExpect(status().isOk());
        assertThat(body(cashCallback(signed(second.code(), 5000, ref)).andExpect(status().isConflict())))
                .contains("Encaissement refusé");
        assertThat(payment(second)).isEqualTo("PENDING CASH -");
        assertThat(orderStatus(second.orderId())).isEqualTo("AWAITING_CASH");
        assertThat(audits("payment.duplicate_ref", second.paymentId())).isEqualTo(1);
        assertThat(audits("payment.refund_required", second.paymentId())).isZero();
        assertThat(payment(first)).isEqualTo("SUCCEEDED FAKE_CASH " + ref);
    }

    // ---------- what fans see ----------

    @Test
    void theOrderListsTheNetworkAndTheEventOffersWalletAndCash() throws Exception {
        Sale s = onSale(10); // no cash point: the network alone makes cash available
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}").andExpect(status().isOk());
        String json = body(fan(fan, get("/fan/orders/" + orderId)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.cash.networks")).containsExactly("Réseau cash simulé");
        assertThat(JsonPath.<List<String>>read(body(mvc.perform(get("/fan/events/" + s.eventId()))), "$.payment_methods"))
                .containsExactly("CARD", "WALLET", "CASH_AGENCY");
    }

    // ---------- wallet ----------

    @Test
    void aWalletPaymentGoesThroughTheSimulatedHostedPage() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String json = body(fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"WALLET\"}")
                .andExpect(status().isOk()));
        assertKeys(json, "$", "payment_id", "method", "redirect_url");
        String p = JsonPath.read(json, "$.payment_id");
        assertThat(JsonPath.<String>read(json, "$.method")).isEqualTo("WALLET");
        assertThat(JsonPath.<String>read(json, "$.redirect_url")).isEqualTo(BASE + "/payments/card/" + p + "/redirect");

        String location = browse(JsonPath.read(json, "$.redirect_url")).andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        String page = body(browse(location).andExpect(status().isOk()));
        assertThat(page).contains("Wallet simulé", "50,00 DH").doesNotContain("Paiement simulé");

        callback(formFields(page, "Approuver")).andExpect(status().isSeeOther());
        assertThat(paymentStatus(p)).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(tickets(orderId)).isEqualTo(1);
        String got = body(fan(fan, get("/fan/orders/" + orderId)).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(got, "$.payment_method")).isEqualTo("WALLET");
        assertThat(jdbc.sql("select payment_method::text from orders where id = ?::uuid").param(orderId)
                .query(String.class).single()).isEqualTo("WALLET");
    }

    private void expire(Code c) {
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(c.orderId())
                .update();
        orderService.expireDue();
        assertThat(orderStatus(c.orderId())).isEqualTo("EXPIRED");
    }
}
