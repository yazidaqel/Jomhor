package ma.jomhor.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.notifications.domain.MessageCopy;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-30): docs/04-api.md §Notifications and PUT /fan/me/push-token. The fan_messages outbox is queued in
 * the transfer, settle and cash-code transactions, sent by FanMessageService.deliverDue with the ticket_deliveries
 * retry policy, rendered at send time in the recipient's locale, and never carries a phone, a full name or a K_t.
 */
@TestPropertySource(properties = {"payments.cash.networks=fake", "payments.wallet.provider=fake"})
class FanMessagesTest extends PaymentTest {

    private static final String BUYER_NAME = "Yazid Aqel"; // OrgApiTest.holders
    private static final String KARIM = "Karim Alaoui";
    private static final String NADIA = "Nadia Berrada";
    private static final String SAMIR = "Samir Benali";
    private static final String TZ = "Africa/Casablanca"; // the venues' default zone (V5)

    @Autowired
    FanMessageService messages;

    @Autowired
    OrderService orderService;

    /** Rows other test classes left due are sent first (to the mocks), so each test sees only its own. */
    @BeforeEach
    void drain() {
        messages.deliverDue(Integer.MAX_VALUE);
        clearInvocations(pushSender, ticketMessenger);
    }

    // ---------- fixtures ----------

    private record Fan(String phone, String token) {
        UUID sid() {
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
            return UUID.fromString(JsonPath.read(payload, "$.sid"));
        }
    }

    private Fan login(String phone) throws Exception {
        return new Fan(phone, fanToken(phone));
    }

    private Fan fan() throws Exception {
        return login(randomPhone());
    }

    private UUID user(String phone) {
        return jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
    }

    private ResultActions putToken(Fan f, String body) throws Exception {
        return fan(f.token(), put("/fan/me/push-token"), body);
    }

    /** Registers a fresh token for the fan's session; returns it. */
    private String device(Fan f) throws Exception {
        String token = "fcm-" + UUID.randomUUID();
        putToken(f, "{\"token\": \"%s\", \"platform\": \"android\"}".formatted(token)).andExpect(status().isNoContent());
        return token;
    }

    private List<Map<String, Object>> tokenRows(String token) {
        return jdbc.sql("select user_id, session_id, platform from push_tokens where token = ?").param(token).query()
                .listOfRows();
    }

    private List<Map<String, Object>> rows(String where, Object... params) {
        return jdbc.sql("""
                        select id, organizer_id, user_id, channel::text as channel, kind::text as kind, order_id,
                               ticket_id, status::text as status, attempts, last_error,
                               extract(epoch from next_attempt_at - now())::bigint as due_in_s
                        from fan_messages where %s order by created_at, channel, id""".formatted(where))
                .params(params).query().listOfRows();
    }

    private Map<String, Object> row(String where, Object... params) {
        List<Map<String, Object>> rows = rows(where, params);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static int attempts(Map<String, Object> row) {
        return ((Number) row.get("attempts")).intValue();
    }

    private List<PushSender.Push> pushes() {
        var captor = ArgumentCaptor.forClass(PushSender.Push.class);
        verify(pushSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    private List<PushSender.Push> pushesTo(String token) {
        return pushes().stream().filter(p -> p.token().equals(token)).toList();
    }

    private List<TicketMessenger.Template> templates() {
        var captor = ArgumentCaptor.forClass(TicketMessenger.Template.class);
        verify(ticketMessenger, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    private List<TicketMessenger.Template> templatesTo(String phone) {
        return templates().stream().filter(t -> t.phoneE164().equals(phone)).toList();
    }

    private void cardPaid(Fan f, String orderId) throws Exception {
        callback(approve(payment(f.token(), orderId), total(orderId))).andExpect(status().isSeeOther());
    }

    /** POST /pay CASH_AGENCY → the code (6 symbols). */
    private String payCash(Fan f, String orderId) throws Exception {
        return JsonPath.read(body(fan(f.token(), post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk())), "$.pay_code");
    }

    private ResultActions network(String payCode, long amountMad) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-only-payments-fake-secret-0123456789".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String amount = Long.toString(amountMad);
        String ref = "NET-" + UUID.randomUUID();
        var req = post("/payments/cash/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pay_code", payCode).param("amount_mad", amount).param("network_ref", ref)
                .param("signature", HexFormat.of().formatHex(mac.doFinal(String.join("|", payCode, amount, ref)
                        .getBytes(StandardCharsets.UTF_8))));
        return mvc.perform(req);
    }

    /** An order of the fan paid with a cash code at the simulated network (AWAITING_CASH → PAID). */
    private String cashPaid(Fan f, Sale s) throws Exception {
        String orderId = order(f.token(), s, 1);
        network(payCash(f, orderId), total(orderId)).andExpect(status().isOk());
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        return orderId;
    }

    private void due(Object id) {
        jdbc.sql("update fan_messages set next_attempt_at = now() where id = ?").param(id).update();
    }

    private void settled(Object id) {
        jdbc.sql("update fan_messages set status = 'FAILED' where id = ?").param(id).update();
    }

    // split pay

    private static String h(String name) {
        return "{\"name\": \"%s\"}".formatted(name);
    }

    private static String h(String name, String phone, boolean pays) {
        return "{\"name\": \"%s\", \"phone\": \"%s\", \"pays\": %s}".formatted(name, phone, pays);
    }

    /** The buyer's order of these holders (one item); returns its id. */
    private String place(Fan buyer, Sale s, String... holders) throws Exception {
        return JsonPath.read(body(fan(buyer.token(), post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": %d, "holders": [%s]}]}
                """.formatted(s.eventId(), s.priceId(), holders.length, String.join(", ", holders)))
                .andExpect(status().isCreated())), "$.id");
    }

    private String memberOf(String buyerOrderId, String phone) {
        return jdbc.sql("""
                select o.id::text from orders o join users u on u.id = o.user_id
                where o.group_order_id = ?::uuid and u.phone_e164 = ?""").params(buyerOrderId, phone)
                .query(String.class).single();
    }

    private String deadline(String orderId, String locale) {
        Instant until = jdbc.sql("select reserved_until from orders where id = ?::uuid").param(orderId)
                .query(java.sql.Timestamp.class).single().toInstant();
        return MessageCopy.deadline(until, TZ, locale);
    }

    private UUID newTicket(UUID old) {
        return jdbc.sql("select new_ticket_id from ticket_transfers where ticket_id = ?").param(old).query(UUID.class)
                .single();
    }

    private UUID ticketOf(String orderId) {
        return jdbc.sql("select id from tickets where order_id = ?::uuid order by id limit 1").param(orderId)
                .query(UUID.class).single();
    }

    private ResultActions transfer(Fan from, UUID ticket, String toPhone, String name) throws Exception {
        return fan(from.token(), post("/fan/tickets/" + ticket + "/transfer"),
                "{\"to_phone\": \"%s\", \"name\": \"%s\"}".formatted(toPhone, name));
    }

    // ---------- 1. PUT /fan/me/push-token ----------

    @Test
    void aTokenBelongsToTheCallersSessionAndMovesBetweenUsers() throws Exception {
        Fan a = fan();
        String token = "fcm-" + UUID.randomUUID();
        putToken(a, "{\"token\": \"%s\", \"platform\": \"android\"}".formatted(token)).andExpect(status().isNoContent())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEmpty());
        assertThat(tokenRows(token)).singleElement().satisfies(r -> {
            assertThat(r.get("user_id")).isEqualTo(user(a.phone()));
            assertThat(r.get("session_id")).isEqualTo(a.sid());
            assertThat(r.get("platform")).isEqualTo("android");
        });

        Fan b = fan(); // the same device, another account
        putToken(b, "{\"token\": \"%s\", \"platform\": \"ios\"}".formatted(token)).andExpect(status().isNoContent());
        assertThat(tokenRows(token)).singleElement().satisfies(r -> {
            assertThat(r.get("user_id")).isEqualTo(user(b.phone()));
            assertThat(r.get("session_id")).isEqualTo(b.sid());
            assertThat(r.get("platform")).isEqualTo("ios");
        });

        String refreshed = device(b); // a refreshed token replaces the session's old one
        assertThat(tokenRows(token)).isEmpty();
        assertThat(jdbc.sql("select token from push_tokens where session_id = ?").param(b.sid()).query(String.class)
                .list()).containsExactly(refreshed);
    }

    @Test
    void anInvalidTokenOrPlatformIs422AndNoAuthIs401() throws Exception {
        Fan f = fan();
        for (String body : List.of("{\"token\": \"t\", \"platform\": \"web\"}", "{\"token\": \"t\"}")) {
            putToken(f, body).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.fields.platform").exists());
        }
        for (String token : List.of("", "   ", "x".repeat(4097))) {
            putToken(f, "{\"token\": \"%s\", \"platform\": \"ios\"}".formatted(token))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.fields.token").exists());
        }
        putToken(f, "{\"platform\": \"ios\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.token").exists());
        String longest = "y".repeat(4096);
        putToken(f, "{\"token\": \"%s\", \"platform\": \"ios\"}".formatted(longest)).andExpect(status().isNoContent());
        jdbc.sql("delete from push_tokens where token = ?").param(longest).update();

        mvc.perform(put("/fan/me/push-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"t\", \"platform\": \"ios\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void twoRacingPutsOfOneSessionLeaveOneToken() throws Exception {
        Fan f = fan();
        UUID user = user(f.phone());
        for (int round = 0; round < 5; round++) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Void> put = () -> {
                start.await();
                messages.registerPushToken(user, f.sid(), "fcm-" + UUID.randomUUID(), "android");
                return null;
            };
            try (var pool = Executors.newFixedThreadPool(2)) {
                List<Future<Void>> puts = List.of(pool.submit(put), pool.submit(put));
                start.countDown();
                for (Future<Void> p : puts) {
                    p.get();
                }
            }
            assertThat(jdbc.sql("select count(*) from push_tokens where session_id = ?").param(f.sid())
                    .query(Integer.class).single()).as("round " + round).isEqualTo(1);
        }
    }

    // ---------- 2. logout, all sessions, re-issue, expiry ----------

    @Test
    void logoutDeletesTheTokenAndItIsNeverUsedAgain() throws Exception {
        Sale s = onSale(100);
        Fan f = fan();
        String orderId = order(f.token(), s, 1);
        String code = payCash(f, orderId);
        String token = device(f);
        fan(f.token(), post("/fan/me/logout")).andExpect(status().isNoContent());
        assertThat(tokenRows(token)).isEmpty();

        network(code, total(orderId)).andExpect(status().isOk()); // AWAITING_CASH → PAID: ORDER_PAID
        messages.deliverDue();
        assertThat(row("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId))
                .containsEntry("status", "FAILED").containsEntry("last_error", "NO_DEVICE");
        verify(pushSender, never()).send(any());
    }

    @Test
    void logoutAllDeletesEverySessionsTokens() throws Exception {
        String phone = randomPhone();
        Fan one = login(phone);
        Fan two = login(phone);
        String t1 = device(one);
        String t2 = device(two);
        fan(one.token(), post("/fan/me/logout"), "{\"all\": true}").andExpect(status().isNoContent());
        assertThat(tokenRows(t1)).isEmpty();
        assertThat(tokenRows(t2)).isEmpty();
    }

    @Test
    void aReissueDeletesTheOtherSessionsTokensAndKeepsTheCallers() throws Exception {
        Sale s = onSale(10);
        String phone = randomPhone();
        Fan kept = login(phone);
        Fan lost = login(phone);
        String orderId = order(kept.token(), s, 1);
        cardPaid(kept, orderId);
        String keptToken = device(kept);
        String lostToken = device(lost);
        UUID old = ticketOf(orderId);
        fan(kept.token(), post("/fan/tickets/" + old + "/reissue")).andExpect(status().isCreated());
        assertThat(tokenRows(keptToken)).hasSize(1);
        assertThat(tokenRows(lostToken)).isEmpty();
        assertThat(rows("ticket_id = ?", newTicket(old))).isEmpty(); // no push on a re-issue
    }

    @Test
    void anExpiredSessionsTokenIsSkipped() throws Exception {
        Sale s = onSale(100);
        String phone = randomPhone();
        Fan old = login(phone);
        Fan current = login(phone);
        String stale = device(old);
        String live = device(current);
        jdbc.sql("update fan_sessions set expires_at = now() - interval '1 second' where id = ?").param(old.sid())
                .update();
        String orderId = cashPaid(current, s);
        messages.deliverDue();
        assertThat(row("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId)).containsEntry("status", "SENT");
        assertThat(pushesTo(live)).hasSize(1);
        assertThat(pushesTo(stale)).isEmpty();
    }

    // ---------- 3. TICKET_RECEIVED ----------

    @Test
    void aTransferQueuesOneTicketReceivedPushToTheRecipient() throws Exception {
        Sale s = onSale(10);
        Fan buyer = fan();
        String orderId = order(buyer.token(), s, 1);
        cardPaid(buyer, orderId);
        Fan recipient = fan();
        String token = device(recipient);
        UUID old = ticketOf(orderId);
        transfer(buyer, old, recipient.phone(), SAMIR).andExpect(status().isCreated());
        UUID fresh = newTicket(old);

        Map<String, Object> r = row("kind = 'TICKET_RECEIVED' and user_id = ?", user(recipient.phone()));
        assertThat(r).containsEntry("ticket_id", fresh).containsEntry("channel", "PUSH")
                .containsEntry("organizer_id", s.org().org()).containsEntry("order_id", null);
        assertThat(rows("ticket_id = ?", old)).isEmpty();

        messages.deliverDue();
        assertThat(pushesTo(token)).singleElement().satisfies(p -> {
            assertThat(p.path()).isEqualTo("/tickets/" + fresh);
            assertThat(p.title()).isEqualTo("Tu as reçu un billet");
            assertThat(p.body()).isEqualTo("Match");
        });
        assertThat(row("ticket_id = ?", fresh)).containsEntry("status", "SENT");
        assertThat(attempts(row("ticket_id = ?", fresh))).isEqualTo(1);
    }

    // ---------- 4. SPLIT_INVITE ----------

    @Test
    void theBuyerPaidQueuesOneInvitePerMemberAndChannel() throws Exception {
        Sale s = onSale(10);
        Fan buyer = fan();
        String f1 = randomPhone();
        String f2 = randomPhone();
        String buyerOrder = place(buyer, s, h(BUYER_NAME), h(KARIM, f1, true), h(NADIA, f2, true));
        String m1 = memberOf(buyerOrder, f1);
        String m2 = memberOf(buyerOrder, f2);
        Fan karim = login(f1);
        String token = device(karim);

        var fields = approve(payment(buyer.token(), buyerOrder), total(buyerOrder));
        callback(fields).andExpect(status().isSeeOther());
        callback(fields).andExpect(status().isSeeOther()); // the same signed callback again: nothing more
        List<Map<String, Object>> invites = rows("kind = 'SPLIT_INVITE' and order_id in (?::uuid, ?::uuid)", m1, m2);
        assertThat(invites).hasSize(4).extracting(r -> r.get("order_id") + " " + r.get("channel"))
                .containsExactlyInAnyOrder(m1 + " PUSH", m1 + " WHATSAPP", m2 + " PUSH", m2 + " WHATSAPP");
        assertThat(invites).allSatisfy(r -> assertThat(r.get("organizer_id")).isEqualTo(s.org().org()));
        assertThat(rows("kind = 'SPLIT_INVITE' and order_id = ?::uuid", m1)).allSatisfy(r ->
                assertThat(r.get("user_id")).isEqualTo(user(f1)));

        messages.deliverDue();
        String deadline = deadline(m1, "fr");
        assertThat(templatesTo(f1)).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("split_invite");
            assertThat(t.userId()).isEqualTo(user(f1));
            assertThat(t.locale()).isEqualTo("fr");
            assertThat(t.params()).containsExactly("Yazid A.", "Match", "50,00 DH", deadline,
                    "http://shop.test/orders/" + m1);
        });
        assertThat(templatesTo(f2)).singleElement().extracting(t -> t.params().getLast())
                .isEqualTo("http://shop.test/orders/" + m2);
        assertThat(pushesTo(token)).singleElement().satisfies(p -> {
            assertThat(p.path()).isEqualTo("/orders/" + m1);
            assertThat(p.title()).isEqualTo("Yazid A. t'a gardé une place");
            assertThat(p.body()).isEqualTo("Match · 50,00 DH · avant " + deadline);
        });
        assertThat(row("order_id = ?::uuid and channel = 'PUSH'", m1)).containsEntry("status", "SENT");
        assertThat(row("order_id = ?::uuid and channel = 'PUSH'", m2)).containsEntry("status", "FAILED")
                .containsEntry("last_error", "NO_DEVICE"); // Nadia never installed the app
        assertThat(rows("order_id = ?::uuid and kind = 'ORDER_PAID'", buyerOrder)).isEmpty(); // RESERVED → PAID
    }

    @Test
    void aBuyerWithoutALabelIsAFriend() throws Exception {
        Sale s = onSale(10);
        Fan buyer = fan(); // no display_name, and every place the buyer pays for is held by someone else
        Fan karim = fan();
        String token = device(karim);
        String buyerOrder = place(buyer, s, h(SAMIR, randomPhone(), false), h(KARIM, karim.phone(), true));
        String member = memberOf(buyerOrder, karim.phone());
        cardPaid(buyer, buyerOrder);
        messages.deliverDue();
        assertThat(pushesTo(token)).singleElement().extracting(PushSender.Push::title)
                .isEqualTo("Un ami t'a gardé une place");
        assertThat(templatesTo(karim.phone())).singleElement().satisfies(t -> {
            assertThat(t.params().getFirst()).isEqualTo("Un ami");
            assertThat(t.params().getLast()).isEqualTo("http://shop.test/orders/" + member);
        });
    }

    // ---------- 5. STALE ----------

    @Test
    void aMemberWhoPaidBeforeTheSendGetsNothing() throws Exception {
        Sale s = onSale(10);
        Fan buyer = fan();
        Fan karim = fan();
        String token = device(karim);
        String buyerOrder = place(buyer, s, h(BUYER_NAME), h(KARIM, karim.phone(), true));
        String member = memberOf(buyerOrder, karim.phone());
        cardPaid(buyer, buyerOrder);
        cardPaid(karim, member); // before the job's turn
        messages.deliverDue();
        assertThat(rows("order_id = ?::uuid and kind = 'SPLIT_INVITE'", member)).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("FAILED");
            assertThat(r.get("last_error")).isEqualTo("STALE");
            assertThat(attempts(r)).isEqualTo(1);
        });
        assertThat(pushesTo(token)).isEmpty();
        assertThat(templatesTo(karim.phone())).isEmpty();
    }

    // ---------- 6. PAY_CODE ----------

    @Test
    void theFirstCashCodeQueuesOnePayCode() throws Exception {
        Sale s = onSale(100);
        Fan f = fan();
        String orderId = order(f.token(), s, 1);
        String code = payCash(f, orderId);
        assertThat(payCash(f, orderId)).isEqualTo(code); // the same code again
        Map<String, Object> r = row("order_id = ?::uuid and kind = 'PAY_CODE'", orderId);
        assertThat(r).containsEntry("kind", "PAY_CODE").containsEntry("channel", "WHATSAPP")
                .containsEntry("user_id", user(f.phone()));

        messages.deliverDue();
        assertThat(templatesTo(f.phone())).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("pay_code");
            assertThat(t.params()).containsExactly("JMH " + code.substring(0, 4) + " " + code.substring(4),
                    "50,00 DH", deadline(orderId, "fr"));
            assertThat(t.params().getFirst()).matches("JMH [0-9A-Z]{4} [0-9A-Z]{2}");
        });

        network(code, 4000).andExpect(status().isConflict()); // amount mismatch: the code fails
        String second = payCash(f, orderId);
        assertThat(second).isNotEqualTo(code);
        assertThat(rows("order_id = ?::uuid", orderId)).hasSize(1); // a later code sends nothing (plan Q128)
    }

    // ---------- 7. ORDER_PAID ----------

    @Test
    void awaitingCashPaidByTheNetworkQueuesOrderPaid() throws Exception {
        Sale s = onSale(100);
        Fan f = fan();
        String token = device(f);
        String orderId = cashPaid(f, s);
        assertThat(row("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId)).containsEntry("channel", "PUSH")
                .containsEntry("user_id", user(f.phone()));
        messages.deliverDue();
        assertThat(pushesTo(token)).singleElement().satisfies(p -> {
            assertThat(p.path()).isEqualTo("/orders/" + orderId);
            assertThat(p.title()).isEqualTo("Paiement reçu");
            assertThat(p.body()).isEqualTo("Tes billets sont prêts");
        });
    }

    @Test
    void aGuichetCollectQueuesOrderPaid() throws Exception {
        Sale s = onSale(100);
        Fan f = fan();
        String orderId = order(f.token(), s, 1);
        String code = payCash(f, orderId);
        Caller cashier = caller(s.org().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        call(cashier, post("/org/pos/codes/" + code + "/collect"), "{\"amount_mad\": 5000}").andExpect(status().isOk());
        assertThat(rows("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId)).hasSize(1);
    }

    @Test
    void aCardPaymentOfAReservedOrderQueuesNothing() throws Exception {
        Sale s = onSale(10);
        Fan f = fan();
        String orderId = order(f.token(), s, 1);
        cardPaid(f, orderId);
        assertThat(rows("order_id = ?::uuid", orderId)).isEmpty();
    }

    @Test
    void aLateCashSuccessOfAnExpiredOrderQueuesOrderPaid() throws Exception {
        Sale s = onSale(100);
        Fan f = fan();
        String orderId = order(f.token(), s, 1);
        String code = payCash(f, orderId);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId)
                .update();
        orderService.expireDue();
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        network(code, total(orderId)).andExpect(status().isOk());
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(rows("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId)).hasSize(1);
    }

    // ---------- 8. backoff ----------

    @Test
    void aProviderErrorBacksOffThenFailsAfterFiveTries() throws Exception {
        Sale s = onSale(100);
        Fan f = fan();
        device(f);
        String orderId = cashPaid(f, s);
        Object id = row("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId).get("id");
        doThrow(new PushSender.Failed("X")).when(pushSender).send(any());

        messages.deliverDue();
        Map<String, Object> r = row("id = ?", id);
        assertThat(r).containsEntry("status", "PENDING").containsEntry("last_error", "X");
        assertThat(attempts(r)).isEqualTo(1);
        assertThat(((Number) r.get("due_in_s")).longValue()).isBetween(55L, 60L);
        for (int attempt = 2; attempt <= 5; attempt++) {
            due(id);
            messages.deliverDue();
            assertThat(attempts(row("id = ?", id))).isEqualTo(attempt);
        }
        assertThat(row("id = ?", id)).containsEntry("status", "FAILED").containsEntry("last_error", "X");
        due(id);
        messages.deliverDue(); // FAILED is final
        assertThat(attempts(row("id = ?", id))).isEqualTo(5);
    }

    @Test
    void anyOtherErrorKeepsOnlyItsClass() throws Exception {
        Sale s = onSale(100);
        Fan f = fan();
        device(f);
        String orderId = cashPaid(f, s);
        doThrow(new IllegalStateException("refused for " + f.phone())).when(pushSender).send(any());
        messages.deliverDue();
        Map<String, Object> r = row("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId);
        assertThat(r).containsEntry("status", "PENDING").containsEntry("last_error", "IllegalStateException");
        settled(r.get("id"));
    }

    @Test
    void aRefusalWithoutACodeIsRetriedNotSent() throws Exception {
        Sale s = onSale(100);
        Fan f = fan();
        device(f);
        String orderId = cashPaid(f, s);
        doThrow(new PushSender.Failed(null)).when(pushSender).send(any());
        messages.deliverDue();
        Map<String, Object> r = row("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId);
        assertThat(r).containsEntry("status", "PENDING").containsEntry("last_error", "Failed");
        assertThat(attempts(r)).isEqualTo(1);
        settled(r.get("id"));
    }

    // ---------- 9. tokens ----------

    @Test
    void anUnregisteredOnlyTokenIsDeletedAndTheMessageHasNoDevice() throws Exception {
        Sale s = onSale(100);
        Fan f = fan();
        String token = device(f);
        doThrow(new PushSender.Unregistered()).when(pushSender).send(any());
        String orderId = cashPaid(f, s);
        messages.deliverDue();
        assertThat(tokenRows(token)).isEmpty();
        assertThat(row("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId)).containsEntry("status", "FAILED")
                .containsEntry("last_error", "NO_DEVICE");
    }

    @Test
    void oneUnregisteredAndOneAcceptedIsSentAndKeepsOneToken() throws Exception {
        Sale s = onSale(100);
        String phone = randomPhone();
        Fan one = login(phone);
        Fan two = login(phone);
        String dead = device(one);
        String alive = device(two);
        doAnswer(inv -> {
            if (inv.<PushSender.Push>getArgument(0).token().equals(dead)) {
                throw new PushSender.Unregistered();
            }
            return null;
        }).when(pushSender).send(any());
        String orderId = cashPaid(two, s);
        messages.deliverDue();
        assertThat(row("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId)).containsEntry("status", "SENT");
        assertThat(jdbc.sql("select token from push_tokens where user_id = ?").param(user(phone)).query(String.class)
                .list()).containsExactly(alive);
    }

    @Test
    void noTokenIsNoDevice() throws Exception {
        Sale s = onSale(100);
        String orderId = cashPaid(fan(), s);
        messages.deliverDue();
        assertThat(row("order_id = ?::uuid and kind = 'ORDER_PAID'", orderId)).containsEntry("status", "FAILED")
                .containsEntry("last_error", "NO_DEVICE");
        verify(pushSender, never()).send(any());
    }

    // ---------- 10. no PII ----------

    @Test
    void noPhoneFullNameOrTicketKeyLeavesInAMessage() throws Exception {
        Sale s = onSale(100);
        Fan buyer = fan();
        Fan karim = fan();
        Fan samir = fan();
        String nadia = randomPhone();
        List<String> tokens = List.of(device(buyer), device(karim), device(samir));
        String buyerOrder = place(buyer, s, h(BUYER_NAME), h(KARIM, karim.phone(), true), h(NADIA, nadia, true));
        cardPaid(buyer, buyerOrder); // SPLIT_INVITE ×2 members ×2 channels
        transfer(buyer, ticketOf(buyerOrder), samir.phone(), SAMIR).andExpect(status().isCreated()); // TICKET_RECEIVED
        String cashOrder = order(buyer.token(), s, 1);
        String code = payCash(buyer, cashOrder); // PAY_CODE
        messages.deliverDue(); // the code is still PENDING: PAY_CODE is sent, not STALE
        network(code, total(cashOrder)).andExpect(status().isOk()); // ORDER_PAID
        List<String> keys = new ArrayList<>();
        for (Fan f : List.of(buyer, samir)) {
            keys.addAll(JsonPath.read(body(fan(f.token(), get("/fan/tickets?with_keys=true")).andExpect(status().isOk())),
                    "$.tickets[*].k_t"));
        }
        assertThat(keys).hasSizeGreaterThanOrEqualTo(2);
        messages.deliverDue();

        List<PushSender.Push> pushes = pushes().stream().filter(p -> tokens.contains(p.token())).toList();
        List<TicketMessenger.Template> templates = templates().stream()
                .filter(t -> List.of(buyer.phone(), karim.phone(), nadia).contains(t.phoneE164())).toList();
        assertThat(pushes).hasSize(3); // Karim's invite, Samir's ticket, the buyer's ORDER_PAID (Nadia has no app)
        assertThat(templates).extracting(TicketMessenger.Template::name)
                .containsExactlyInAnyOrder("split_invite", "split_invite", "pay_code");
        StringBuilder all = new StringBuilder();
        pushes.forEach(p -> all.append(p.title()).append('|').append(p.body()).append('|').append(p.path()).append('|')
                .append(p).append('|'));
        templates.forEach(t -> all.append(t.params()).append('|').append(t).append('|'));
        jdbc.sql("select coalesce(last_error, '') from fan_messages where user_id in (?, ?, ?, ?)")
                .params(user(buyer.phone()), user(karim.phone()), user(samir.phone()), user(nadia))
                .query(String.class).list().forEach(e -> all.append(e).append('|'));
        String text = all.toString();
        for (String secret : List.of(buyer.phone(), karim.phone(), samir.phone(), nadia, BUYER_NAME, KARIM, NADIA,
                SAMIR)) {
            assertThat(text).doesNotContain(secret);
        }
        keys.forEach(k -> assertThat(text).doesNotContain(k));
        assertThat(pushes).allSatisfy(p -> assertThat(p.toString()).doesNotContain(p.token()));
    }

    // ---------- 11. Arabic ----------

    @Test
    void anArabicMemberGetsArabicCopyWithWesternDigits() throws Exception {
        Sale s = onSale(10);
        Fan buyer = fan();
        Fan karim = fan();
        fan(karim.token(), patch("/fan/me"), "{\"locale\": \"ar\"}").andExpect(status().isOk());
        String token = device(karim);
        String buyerOrder = place(buyer, s, h(BUYER_NAME), h(KARIM, karim.phone(), true));
        String member = memberOf(buyerOrder, karim.phone());
        cardPaid(buyer, buyerOrder);
        messages.deliverDue();
        String deadline = deadline(member, "ar");
        assertThat(pushesTo(token)).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("\u2068Yazid A.\u2069 خلا ليك بلاصة");
            assertThat(p.body()).isEqualTo("\u2068Match\u2069 · \u206850,00 DH\u2069 · قبل \u2068" + deadline + "\u2069");
        });
        assertThat(deadline).doesNotContainPattern("[\\x{0660}-\\x{0669}]");
        assertThat(templatesTo(karim.phone())).singleElement().satisfies(t -> {
            assertThat(t.locale()).isEqualTo("ar");
            assertThat(t.params()).containsExactly("Yazid A.", "Match", "50,00 DH", deadline,
                    "http://shop.test/orders/" + member); // no isolates in WhatsApp parameters (Q125)
        });
    }

    // ---------- 12. one app, every club ----------

    @Test
    void oneTokenReceivesMessagesOfTwoClubs() throws Exception {
        Sale a = onSale(10);
        Sale b = onSale(100);
        Fan f = fan();
        String token = device(f);
        Fan seller = fan();
        String sold = order(seller.token(), a, 1);
        cardPaid(seller, sold);
        UUID old = ticketOf(sold);
        transfer(seller, old, f.phone(), SAMIR).andExpect(status().isCreated()); // club A
        String paid = cashPaid(f, b); // club B
        messages.deliverDue();
        assertThat(rows("user_id = ? and channel = 'PUSH'", user(f.phone()))).extracting(r -> r.get("organizer_id"))
                .containsExactlyInAnyOrder(a.org().org(), b.org().org());
        assertThat(pushesTo(token)).extracting(PushSender.Push::path)
                .containsExactlyInAnyOrder("/tickets/" + newTicket(old), "/orders/" + paid);
    }

    // ---------- 13, 14. concurrency and the rate cap ----------

    /** {@code n} TICKET_RECEIVED rows of the fan's own valid ticket, due now. */
    private void receivedRows(Sale s, Fan f, int n) throws Exception {
        String orderId = order(f.token(), s, 1);
        cardPaid(f, orderId);
        jdbc.sql("""
                        insert into fan_messages (organizer_id, user_id, channel, kind, ticket_id)
                        select ?, ?, 'PUSH', 'TICKET_RECEIVED', ? from generate_series(1, ?)""")
                .params(s.org().org(), user(f.phone()), ticketOf(orderId), n).update();
    }

    @Test
    void twoParallelRunsSendEachRowOnce() throws Exception {
        Sale s = onSale(10);
        Fan f = fan();
        device(f);
        receivedRows(s, f, 20);
        doAnswer(inv -> {
            Thread.sleep(2);
            return null;
        }).when(pushSender).send(any());
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> run = () -> {
            start.await();
            return messages.deliverDue();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> runs = List.of(pool.submit(run), pool.submit(run));
            start.countDown();
            int claimed = 0;
            for (Future<Integer> r : runs) {
                claimed += r.get();
            }
            assertThat(claimed).isEqualTo(20);
        }
        verify(pushSender, times(20)).send(any());
        assertThat(rows("user_id = ?", user(f.phone()))).hasSize(20)
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("SENT"));
    }

    @Test
    void aRunSendsAtMostItsCapTransactionalKindsFirst() throws Exception {
        Sale s = onSale(10);
        Fan f = fan();
        device(f);
        receivedRows(s, f, 10);
        UUID user = user(f.phone());
        UUID campaign = jdbc.sql("""
                        insert into fan_messages (organizer_id, user_id, channel, kind, created_at, next_attempt_at)
                        values (?, ?, 'PUSH', 'CAMPAIGN', now() - interval '1 hour', now() - interval '1 hour')
                        returning id""").params(s.org().org(), user).query(UUID.class).single();
        try {
            assertThat(messages.deliverDue(5)).isEqualTo(5);
            verify(pushSender, times(5)).send(any());
            assertThat(rows("user_id = ? and kind = 'TICKET_RECEIVED'", user)).extracting(r -> r.get("status"))
                    .containsOnly("SENT", "PENDING").filteredOn("PENDING"::equals).hasSize(5);
            assertThat(messages.deliverDue(5)).isEqualTo(5);
            Map<String, Object> c = row("id = ?", campaign);
            assertThat(c).containsEntry("status", "PENDING");
            assertThat(attempts(c)).isZero(); // the oldest row, yet after every transactional one
        } finally {
            jdbc.sql("delete from fan_messages where id = ?").param(campaign).update();
        }
    }
}
