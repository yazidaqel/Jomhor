package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.gate.application.Manifests;
import ma.jomhor.gate.domain.ManifestPayload;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.products.application.PassTicketService;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tickets.domain.Ticket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Lot 5 step 3b exit proof, backend side (plan lot-5 §11.1 Exit 3b (1)-(8), §11.5 BE-60; Q195-Q199 and the founder's
 * 2026-11-16 call: only the holder's own pass tickets are blocked). OPS sets "Paiement en 4 fois" on the pass product.
 * Salma and Karim buy it in 4 installments, the first by card: their passes go ACTIVE and the pass job issues a ticket
 * per match. Seq 2 is reminded 3 days before its due date (push to /passes/{id}, WhatsApp installment_due). Before it
 * falls due, Salma transfers her match 2 ticket to Hamza and OPS blocks Karim's match 1 ticket (B12). Nobody pays seq
 * 2: at due + 7 d both passes go SUSPENDED; their tickets read BLOCKED in the wallet and the next manifest delta, while
 * Hamza's transferred ticket and match 3 (in its final hour at the suspension) stay VALID. A match published meanwhile
 * gets no ticket. After the pass sales have closed, Salma takes a cash code for seq 2 and pays it at the till: ACTIVE,
 * her ticket reads VALID in the next delta, and the missing match's ticket is issued. Karim pays his by card: his
 * staff block stays. Salma pays seq 3 and 4: PAID_UP. The org figures follow; {@code loadtest/check.sql} finds no
 * breach. The pass job on fixed clocks (the schedule is real: seq k due at purchase + (k - 1) × 30 d); card payments
 * through the fake gateway, the cash code at a CASHIER's till.
 */
class Lot5InstallmentsFlowTest extends QueueFixture {

    private static final long PRICE = 150_000;
    private static final long PART = 37_500; // 150 000 / 4
    private static final Duration GRACE = Duration.ofDays(7);
    private static final Duration BEFORE = Duration.ofDays(3);
    private static final String PRODUCT = "Abonnement Tribune Est";

    @Autowired
    PassTicketService passJob;

    @Autowired
    FanMessageService messages;

    @Autowired
    TicketService tickets;

    @Autowired
    ManifestService manifestService;

    @Autowired
    Manifests manifests;

    private record Holder(String phone, String token, UUID id, String pass) {}

    @Test
    void installmentsRemindSuspendBlockReactivateAtTheTillKeepStaffBlocksThenPaidUpAndNoBreach() throws Exception {
        // ---------- (2) set-up: the series, two far matches, "Abonnés" in Tribune Est; "Paiement en 4 fois" ----------
        Caller admin = admin();
        Caller ops = caller(admin.org(), "OPS");
        UUID org = admin.org();
        jdbc.sql("update organizers set settings = settings || '{\"cash_points\": [{\"name\": \"Guichet\"}]}'::jsonb "
                + "where id = ?").param(org).update();
        String stade = venue(admin, "Stade");
        int est = zone(admin, stade, "EST");
        String series = id(call(ops, post("/org/series"),
                "{\"name\": \"Saison 2026-27\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}"));
        int m1 = match(ops, stade, series, Instant.parse("2099-02-01T19:00:00Z"), est);
        int m2 = match(ops, stade, series, Instant.parse("2099-02-15T19:00:00Z"), est);
        String pass = id(call(ops, post("/org/products"), """
                {"kind": "PASS", "name": "%s", "price_mad": %d, "quota": 10, "series_id": "%s", "zone_id": %d,
                 "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}""".formatted(PRODUCT, PRICE, series, est)));
        assertThat(JsonPath.<List<Integer>>read(body(mvc.perform(get("/fan/products/" + pass))),
                "$.plans[*].installments")).containsExactly(1);
        call(ops, patch("/org/products/" + pass), "{\"max_installments\": 4}").andExpect(status().isOk())
                .andExpect(jsonPath("$.max_installments").value(4));
        String plans = body(mvc.perform(get("/fan/products/" + pass)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(plans, "$.plans[*].installments")).containsExactly(1, 4);
        assertThat(JsonPath.<List<Integer>>read(plans, "$.plans[1].amounts_mad"))
                .containsExactly((int) PART, (int) PART, (int) PART, (int) PART);
        assertThat(JsonPath.<Integer>read(plans, "$.plans[1].interval_days")).isEqualTo(30);

        // ---------- (3) Salma (with a push device) and Karim buy in 4; seq 1 by card → ACTIVE ----------
        Holder salma = buyInFour(pass, "Salma Tazi", "APP");
        String device = "fcm-" + UUID.randomUUID();
        as(salma, put("/fan/me/push-token"), "{\"token\": \"%s\", \"platform\": \"android\"}".formatted(device))
                .andExpect(status().isNoContent());
        Holder karim = buyInFour(pass, "Karim Alaoui", "WEB");
        String owned = body(as(salma, get("/fan/passes")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(owned, "$.passes[0].status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<List<Integer>>read(owned, "$.passes[0].installments[*].amount_mad"))
                .containsExactly((int) PART, (int) PART, (int) PART, (int) PART);
        assertThat(JsonPath.<List<Object>>read(owned, "$.passes[0].installments[?(@.paid_at == null)]")).hasSize(3);
        assertThat(JsonPath.<Integer>read(owned, "$.passes[0].next_due.seq")).isEqualTo(2);
        assertThat(due(salma, 2)).isEqualTo(due(salma, 1).plus(Duration.ofDays(30)));
        assertThat(figures(ops, pass)).isEqualTo(Map.of("overdue", 0, "due_mad", 6 * (int) PART,
                "collected_mad", 2 * (int) PART));

        Instant remindAt = later(due(salma, 2), due(karim, 2)).minus(BEFORE);
        Instant suspendAt = later(due(salma, 2), due(karim, 2)).plus(GRACE);
        // Match 3 starts 30 min after the suspension: its refund_cutoff_at (T0 - 60 min) is before it, so it's frozen.
        int m3 = match(ops, stade, series, suspendAt.truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(30)), est);

        // ---------- (4) the pass job 3 days before seq 2: a ticket per match, then the reminder ----------
        passJob.runAt(due(salma, 2).minus(BEFORE).minusSeconds(1)); // 1 s before the earlier of the two
        assertThat(reminders(salma)).isEmpty();
        assertThat(reminders(karim)).isEmpty();
        UUID s1 = ticket(salma, m1);
        UUID s2 = ticket(salma, m2);
        UUID s3 = ticket(salma, m3);
        UUID k1 = ticket(karim, m1);
        UUID k2 = ticket(karim, m2);
        passJob.runAt(remindAt);
        passJob.runAt(remindAt.plusSeconds(60)); // once per installment
        assertThat(reminders(salma)).containsExactly("PUSH", "WHATSAPP");
        assertThat(reminders(karim)).containsExactly("PUSH", "WHATSAPP");
        messages.deliverDue(Integer.MAX_VALUE);
        var push = ArgumentCaptor.forClass(PushSender.Push.class);
        verify(pushSender, atLeast(1)).send(push.capture());
        assertThat(push.getAllValues()).filteredOn(p -> p.token().equals(device)).singleElement()
                .extracting(PushSender.Push::path).isEqualTo("/passes/" + salma.pass());
        var whatsapp = ArgumentCaptor.forClass(TicketMessenger.Template.class);
        verify(ticketMessenger, atLeast(1)).send(whatsapp.capture());
        assertThat(whatsapp.getAllValues()).filteredOn(t -> t.phoneE164().equals(salma.phone())).singleElement()
                .satisfies(t -> {
                    assertThat(t.name()).isEqualTo("installment_due");
                    assertThat(t.params().getFirst()).isEqualTo(PRODUCT);
                });

        // Before seq 2 falls due: Salma transfers her match 2 ticket to Hamza; OPS blocks Karim's match 1 one (B12).
        String hamzaPhone = randomPhone();
        as(salma, post("/fan/tickets/" + s2 + "/transfer"),
                "{\"to_phone\": \"%s\", \"name\": \"Hamza Chraibi\"}".formatted(hamzaPhone))
                .andExpect(status().isCreated());
        Holder hamza = new Holder(hamzaPhone, fanToken(hamzaPhone), null, null);
        UUID h2 = UUID.fromString(JsonPath.read(body(as(hamza, get("/fan/tickets")).andExpect(status().isOk())),
                "$.tickets[0].id"));
        String found = body(call(ops, post("/org/events/" + m1 + "/tickets/search"),
                "{\"q\": \"%s\"}".formatted(karim.phone())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(found, "$[*].id")).containsExactly(k1.toString());
        call(ops, post("/org/tickets/" + k1 + "/block"), "{\"reason\": \"Interdit de stade\"}")
                .andExpect(status().isOk());
        assertThat(manifestService.build(org, m1)).contains(1);
        assertThat(manifestService.build(org, m2)).contains(1);

        // ---------- (5) seq 2 unpaid: still ACTIVE 1 s before the grace ends, SUSPENDED at due + 7 d ----------
        passJob.runAt(due(salma, 2).plus(GRACE).minusSeconds(1));
        assertThat(passStatus(salma)).isEqualTo("ACTIVE");
        passJob.runAt(suspendAt);
        assertThat(passStatus(salma)).isEqualTo("SUSPENDED");
        assertThat(passStatus(karim)).isEqualTo("SUSPENDED");
        assertThat(audits("pass.suspend", salma)).containsExactly("{\"seq\": 2}");
        owned = body(as(salma, get("/fan/passes")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(owned, "$.passes[0].status")).isEqualTo("SUSPENDED");
        assertThat(JsonPath.<String>read(owned, "$.passes[0].suspended_at")).isNotNull();
        assertThat(JsonPath.<Integer>read(owned, "$.passes[0].next_due.seq")).isEqualTo(2);

        // Screen 5: BLOCKED by the pass (no k_t); match 3 (final hour) VALID; Hamza's transferred one VALID.
        assertThat(walletRow(salma, s1)).containsEntry("status", "BLOCKED").containsEntry("blocked_by", "PASS")
                .containsEntry("pass_id", salma.pass()).doesNotContainKey("k_t");
        assertThat(walletRow(salma, s3)).containsEntry("status", "VALID").containsKey("k_t");
        assertThat(walletRow(hamza, h2)).containsEntry("status", "VALID").containsEntry("pass_id", null)
                .containsKey("k_t");
        assertThat(walletRow(karim, k1)).containsEntry("blocked_by", "STAFF");
        assertThat(walletRow(karim, k2)).containsEntry("blocked_by", "PASS");

        // B12: the lookup shows the effective status, the pass and whether staff blocked it.
        assertThat(lookup(ops, m1, salma)).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("status", "BLOCKED").containsEntry("staff_blocked", false)
                .containsEntry("pass", Map.of("status", "SUSPENDED")));
        assertThat(lookup(ops, m1, karim)).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("status", "BLOCKED").containsEntry("staff_blocked", true));

        // The scanner's view: the next delta carries exactly the holders' own rows of the unfrozen matches as BLOCKED.
        assertThat(deltaRows(org, m1)).containsExactly(row(s1, est, "BLOCKED", "Salma Tazi"));
        assertThat(deltaRows(org, m2)).containsExactly(row(k2, est, "BLOCKED", "Karim Alaoui"));
        assertThat(inManifest(org, m2, h2)).isEqualTo(Ticket.Status.VALID);
        assertThat(inManifest(org, m3, s3)).isEqualTo(Ticket.Status.VALID);
        assertThat(stored(s1)).isEqualTo("VALID");

        // B14: both suspended. The figures read the database clock, which the job's clock is 37 days ahead of, so
        // nothing reads overdue yet: overdue isn't checked here (InstallmentApiTest covers it).
        assertThat(JsonPath.<Map<String, Object>>read(body(call(ops, get("/org/products/" + pass))),
                "$.figures.passes")).containsOnly(Map.entry("pending", 0), Map.entry("active", 0),
                Map.entry("suspended", 2));
        assertThat(figures(ops, pass)).containsEntry("due_mad", 6 * (int) PART)
                .containsEntry("collected_mad", 2 * (int) PART);

        // A match published while suspended: no ticket.
        int m4 = match(ops, stade, series, Instant.parse("2099-03-01T19:00:00Z"), est);
        Instant after = suspendAt.plusSeconds(60);
        passJob.runAt(after);
        assertThat(ticketOrders(salma, m4)).isZero();
        assertThat(ticketOrders(karim, m4)).isZero();

        // ---------- (6) the pass sales close; Salma pays seq 2 by a cash code at the B11 till → ACTIVE ----------
        call(ops, patch("/org/products/" + pass), "{\"on_sale\": false}").andExpect(status().isOk());
        jdbc.sql("update products set sales_close_at = now() - interval '1 minute' where id = ?::uuid").param(pass)
                .update();
        String order = body(as(salma, post("/fan/passes/" + salma.pass() + "/installments"))
                .andExpect(status().isCreated()));
        String orderId = JsonPath.read(order, "$.id");
        assertThat(JsonPath.<Integer>read(order, "$.total_mad")).isEqualTo((int) PART);
        String read = body(as(salma, get("/fan/orders/" + orderId)).andExpect(status().isOk()));
        assertThat(JsonPath.<Map<String, Object>>read(read, "$.installment")).isEqualTo(Map.of("seq", 2, "of", 4));
        assertThat(JsonPath.<List<String>>read(read, "$.product.payment_methods")).contains("CASH_AGENCY");
        String code = JsonPath.read(body(as(salma, post("/fan/orders/" + orderId + "/pay"),
                "{\"method\": \"CASH_AGENCY\"}").andExpect(status().isOk())), "$.pay_code");
        // Held 24 h, though the pass sales have closed (an installment order has no sales close).
        assertThat(jdbc.sql("select abs(extract(epoch from reserved_until - now()) - 86400) < 60 from orders "
                + "where id = ?::uuid").param(orderId).query(Boolean.class).single()).isTrue();

        Caller cashier = caller(org, "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        String lookup = body(call(cashier, get("/org/pos/codes/" + code)).andExpect(status().isOk()));
        assertThat(JsonPath.<Object>read(lookup, "$.event")).isNull();
        assertThat(JsonPath.<String>read(lookup, "$.product.name")).isEqualTo(PRODUCT);
        assertThat(JsonPath.<Boolean>read(lookup, "$.payable")).isTrue();
        String collected = body(call(cashier, post("/org/pos/codes/" + code + "/collect"),
                "{\"amount_mad\": %d}".formatted(PART)).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(collected, "$.status")).isEqualTo("PAID");
        assertThat(passStatus(salma)).isEqualTo("ACTIVE");
        assertThat(audits("pass.reactivate", salma)).containsExactly("{\"seq\": 2}");

        // The next manifest version admits her; the missing match's ticket is issued on the next run.
        assertThat(deltaRows(org, m1)).containsExactly(row(s1, est, "VALID", "Salma Tazi"));
        assertThat(walletRow(salma, s1)).containsEntry("status", "VALID").containsEntry("blocked_by", null)
                .containsKey("k_t");
        passJob.runAt(after);
        assertThat(ticketOrders(salma, m4)).isOne();
        assertThat(ticketOrders(karim, m4)).isZero();

        // ---------- (7) Karim pays seq 2 by card → ACTIVE; his staff block stays ----------
        payByCard(karim, body(as(karim, post("/fan/passes/" + karim.pass() + "/installments"))
                .andExpect(status().isCreated())));
        assertThat(passStatus(karim)).isEqualTo("ACTIVE");
        assertThat(walletRow(karim, k1)).containsEntry("status", "BLOCKED").containsEntry("blocked_by", "STAFF");
        assertThat(walletRow(karim, k2)).containsEntry("status", "VALID");
        assertThat(inManifest(org, m1, k1)).isEqualTo(Ticket.Status.BLOCKED);
        assertThat(stored(k1)).isEqualTo("BLOCKED");
        passJob.runAt(after);
        assertThat(ticketOrders(karim, m4)).isOne();

        // ---------- Salma pays seq 3 and 4 ahead: PAID_UP ----------
        for (int seq = 3; seq <= 4; seq++) {
            String next = body(as(salma, post("/fan/passes/" + salma.pass() + "/installments"))
                    .andExpect(status().isCreated()));
            assertThat(JsonPath.<Map<String, Object>>read(
                    body(as(salma, get("/fan/orders/" + JsonPath.read(next, "$.id")))), "$.installment"))
                    .isEqualTo(Map.of("seq", seq, "of", 4));
            payByCard(salma, next);
        }
        as(salma, post("/fan/passes/" + salma.pass() + "/installments")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASS_STATUS")).andExpect(jsonPath("$.details.reason")
                        .value("PAID_UP"));
        owned = body(as(salma, get("/fan/passes")).andExpect(status().isOk()));
        assertThat(JsonPath.<Object>read(owned, "$.passes[0].next_due")).isNull();
        assertThat(JsonPath.<List<Object>>read(owned, "$.passes[0].installments[?(@.paid_at == null)]")).isEmpty();

        // B14: Salma's 4 and Karim's 2 collected, Karim's last 2 due.
        String detail = body(call(ops, get("/org/products/" + pass)).andExpect(status().isOk()));
        assertThat(JsonPath.<Map<String, Object>>read(detail, "$.figures.passes"))
                .containsOnly(Map.entry("pending", 0), Map.entry("active", 2), Map.entry("suspended", 0));
        assertThat(JsonPath.<Number>read(detail, "$.figures.revenue_mad").longValue()).isEqualTo(PRICE + 2 * PART);
        assertThat(figures(ops, pass)).isEqualTo(Map.of("overdue", 0, "due_mad", 2 * (int) PART,
                "collected_mad", (int) (PRICE + 2 * PART)));

        // ---------- (8) check.sql: no breach on the club's events or products ----------
        assertThat(breaches(org)).isEmpty();
    }

    // ---------- helpers ----------

    private ResultActions as(Holder h, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + h.token()));
    }

    private ResultActions as(Holder h, MockHttpServletRequestBuilder req, String json) throws Exception {
        return as(h, req.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** The {@code id} of a 201 response. */
    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r.andExpect(status().isCreated())), "$.id");
    }

    private static Instant later(Instant a, Instant b) {
        return Collections.max(List.of(a, b));
    }

    /** A published match of the series with "Abonnés" (PASS, 0 DH, quota 10) in the zone. */
    private int match(Caller c, String venue, String series, Instant startsAt, int zone) throws Exception {
        int id = JsonPath.read(body(call(c, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match %s", "starts_at": "%s",
                 "series_id": "%s"}""".formatted(venue, startsAt, startsAt, series))
                .andExpect(status().isCreated())), "$.id");
        call(c, post("/org/events/" + id + "/prices"), """
                {"zone_id": %d, "name": "Abonnés", "price_mad": 0, "quota": 10, "audience": "PASS"}""".formatted(zone))
                .andExpect(status().isCreated());
        call(c, post("/org/events/" + id + "/publish")).andExpect(status().isOk());
        return id;
    }

    /** A new fan orders the pass in 4 (total = seq 1, installment {1, 4}) and pays it by card → ACTIVE. */
    private Holder buyInFour(String product, String name, String channel) throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        UUID id = jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
        Holder h = new Holder(phone, token, id, null);
        String order = body(as(h, post("/fan/product-orders"), """
                {"product_id": "%s", "channel": "%s", "holder_name": "%s", "installments": 4}"""
                .formatted(product, channel, name)).andExpect(status().isCreated()));
        assertThat(JsonPath.<Integer>read(order, "$.total_mad")).isEqualTo((int) PART);
        String read = body(as(h, get("/fan/orders/" + JsonPath.read(order, "$.id"))).andExpect(status().isOk()));
        assertThat(JsonPath.<Map<String, Object>>read(read, "$.installment")).isEqualTo(Map.of("seq", 1, "of", 4));
        payByCard(h, order);
        h = new Holder(phone, token, id, JsonPath.read(order, "$.pass_id"));
        assertThat(passStatus(h)).isEqualTo("ACTIVE");
        return h;
    }

    /** Pays the order (its create response) by simulated card → PAID. */
    private void payByCard(Holder h, String order) throws Exception {
        String orderId = JsonPath.read(order, "$.id");
        String payment = JsonPath.read(body(as(h, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CARD\"}")
                .andExpect(status().isOk())), "$.payment_id");
        callback(PaymentTest.approve(payment, ((Number) JsonPath.read(order, "$.total_mad")).longValue()))
                .andExpect(status().isSeeOther());
        assertThat(jdbc.sql("select status::text from orders where id = ?::uuid").param(orderId).query(String.class)
                .single()).isEqualTo("PAID");
    }

    /** B12: the event's tickets held by the holder's phone. */
    private List<Map<String, Object>> lookup(Caller c, int event, Holder h) throws Exception {
        return JsonPath.read(body(call(c, post("/org/events/" + event + "/tickets/search"),
                "{\"q\": \"%s\"}".formatted(h.phone())).andExpect(status().isOk())), "$");
    }

    private Map<String, Object> figures(Caller c, String product) throws Exception {
        return JsonPath.read(body(call(c, get("/org/products/" + product)).andExpect(status().isOk())),
                "$.figures.installments");
    }

    private Instant due(Holder h, int seq) {
        return jdbc.sql("select due_at from pass_installments where pass_id = ?::uuid and seq = ?").params(h.pass(), seq)
                .query(OffsetDateTime.class).single().toInstant();
    }

    /** The pass's INSTALLMENT_DUE channels, in order. */
    private List<String> reminders(Holder h) {
        return jdbc.sql("select channel::text from fan_messages where pass_id = ?::uuid and kind = 'INSTALLMENT_DUE' "
                + "order by channel").param(h.pass()).query(String.class).list();
    }

    private String passStatus(Holder h) {
        return jdbc.sql("select status::text from passes where id = ?::uuid").param(h.pass()).query(String.class)
                .single();
    }

    private List<String> audits(String action, Holder h) {
        return jdbc.sql("select payload::text from audit_log where action = ? and target = ?").params(action, h.pass())
                .query(String.class).list();
    }

    /** The holder's own ticket of the pass for {@code event}. */
    private UUID ticket(Holder h, int event) {
        return jdbc.sql("select t.id from tickets t join orders o on o.id = t.order_id"
                        + " where o.pass_id = ?::uuid and o.event_id = ? and t.holder_user_id = ?")
                .params(h.pass(), event, h.id()).query(UUID.class).single();
    }

    private int ticketOrders(Holder h, int event) {
        return jdbc.sql("select count(*) from orders where pass_id = ?::uuid and event_id = ?").params(h.pass(), event)
                .query(Integer.class).single();
    }

    private String stored(UUID ticket) {
        return jdbc.sql("select status::text from tickets where id = ?").param(ticket).query(String.class).single();
    }

    private Map<String, Object> walletRow(Holder h, UUID ticket) throws Exception {
        String json = body(as(h, get("/fan/tickets?with_keys=true")).andExpect(status().isOk()));
        return JsonPath.<List<Map<String, Object>>>read(json, "$.tickets[?(@.id == '" + ticket + "')]").getFirst();
    }

    private Ticket.Status inManifest(UUID org, int event, UUID ticket) {
        return tickets.manifestRows(org, event).stream().filter(r -> r.id().equals(ticket)).findFirst().orElseThrow()
                .status();
    }

    /** A new manifest version of the event (the previous one made 10 min older) → the T lines of its delta. */
    private List<String> deltaRows(UUID org, int event) {
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(event)
                .update();
        int version = manifestService.build(org, event).orElseThrow();
        String delta = ManifestPayload.delta(manifests.find(org, event, version).orElseThrow().payload(),
                manifests.find(org, event, version - 1).orElseThrow().payload(), version - 1);
        return delta.lines().filter(l -> l.startsWith("T\t")).toList();
    }

    private static String row(UUID ticket, int zone, String status, String holder) {
        return "T\t" + ticket + "\t" + zone + "\t" + status + "\t" + HolderNames.initials(holder);
    }
}
