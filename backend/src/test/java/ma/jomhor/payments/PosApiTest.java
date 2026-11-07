package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.notifications.application.TicketDeliveryService;
import ma.jomhor.payments.application.PosService;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.tickets.domain.TicketToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract (BE-23): docs/04-api.md §Point of sale (/org/pos/**), CASHIER confinement (§Organizer Lot 3), and the
 * plan's done-tests for the till: sessions, walk-up sale, cash code lookup and collect, print data.
 */
class PosApiTest extends PaymentTest {

    private static final String POINTS = "[{\"name\": \"Guichet Stade\"}]";

    @Autowired
    EventService events;

    @Autowired
    TicketDeliveryService deliveries;

    @Autowired
    PosService posService;

    @Autowired
    PlatformTransactionManager transactions;

    // ---------- fixtures ----------

    private String openSession(Caller c, String label) throws Exception {
        return JsonPath.read(body(call(c, post("/org/pos/sessions"), "{\"label\": \"%s\"}".formatted(label))
                .andExpect(status().isCreated())), "$.id");
    }

    private static String holder(String name) {
        return "{\"name\": \"%s\"}".formatted(name);
    }

    private static String holder(String name, String phone) {
        return "{\"name\": \"%s\", \"phone\": \"%s\"}".formatted(name, phone);
    }

    private ResultActions sell(Caller c, UUID saleId, Sale s, String... holders) throws Exception {
        return call(c, post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d, "items": [{"price_category_id": "%s", "qty": %d, "holders": [%s]}]}
                """.formatted(saleId, s.eventId(), s.priceId(), holders.length, String.join(", ", holders)));
    }

    private String sold(Caller c, Sale s, String... holders) throws Exception {
        return JsonPath.read(body(sell(c, UUID.randomUUID(), s, holders).andExpect(status().isCreated())), "$.id");
    }

    /** A fan order on {@code s} paid by cash code: its code (6 symbols) and order id. */
    private record Code(String code, String orderId) {}

    private Code cashCode(Sale s, int qty) throws Exception {
        jdbc.sql("update organizers set settings = settings || jsonb_build_object('cash_points', ?::jsonb) where id = ?")
                .params(POINTS, s.org().org()).update();
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, qty);
        String json = body(fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk()));
        return new Code(JsonPath.read(json, "$.pay_code"), orderId);
    }

    private ResultActions collect(Caller c, String code, long amountMad) throws Exception {
        return call(c, post("/org/pos/codes/" + code + "/collect"), "{\"amount_mad\": %d}".formatted(amountMad));
    }

    private static void conflict(ResultActions r, String code) throws Exception {
        r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }

    private int audits(String action, String target) {
        return jdbc.sql("select count(*) from audit_log where action = ? and target = ?").params(action, target)
                .query(Integer.class).single();
    }

    private int deliveryRows(String orderId) {
        return jdbc.sql("""
                select count(*) from ticket_deliveries d join tickets t on t.id = d.ticket_id
                where t.order_id = ?::uuid""").param(orderId).query(Integer.class).single();
    }

    // ---------- CASHIER confinement ----------

    @Test
    void aCashierReachesOnlyThePosAndTheEventReads() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        call(cashier, get("/org/pos/session")).andExpect(status().isOk());
        call(cashier, get("/org/events")).andExpect(status().isOk());
        call(cashier, get("/org/events/" + s.eventId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.per_account_limit").value(4));
        call(cashier, get("/org/events/" + s.eventId() + "/prices")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].zone.code").value("NORD"));
        for (var req : List.of(get("/org/venues"), get("/org/staff"), get("/org/devices"),
                get("/org/events/" + s.eventId() + "/sales"), get("/org/events/" + s.eventId() + "/live"),
                post("/org/events/" + s.eventId() + "/close"), patch("/org/events/" + s.eventId()),
                get("/org/events/" + s.eventId() + "/tickets?q=JMH"), post("/org/tickets/" + UUID.randomUUID() + "/refund"),
                post("/org/tickets/" + UUID.randomUUID() + "/block"),
                get(URI.create("/org/%76enues")))) { // decided on the matched handler, not the raw URI
            call(cashier, req, "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        call(cashier, post("/org/events/" + s.eventId() + "/prices"),
                "{\"zone_id\": %d, \"name\": \"Gratuit\", \"price_mad\": 0, \"quota\": 1}".formatted(s.zoneId()))
                .andExpect(status().isForbidden());
        // /org/pos/** is ADMIN, OPS, CASHIER: FINANCE, GATE, SUPERVISOR get 403
        call(caller(s.org().org(), "OPS"), get("/org/pos/session")).andExpect(status().isOk());
        call(s.org(), get("/org/pos/session")).andExpect(status().isOk());
        for (String role : List.of("FINANCE", "GATE", "SUPERVISOR")) {
            Caller c = caller(s.org().org(), role);
            call(c, get("/org/pos/session")).andExpect(status().isForbidden());
            // an encoded path routes to the same handler, so it gets the same answer
            call(c, get(URI.create("/org/%70os/session"))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        call(cashier, get(URI.create("/org/%70os/session"))).andExpect(status().isOk());
        call(caller(s.org().org(), "FINANCE"), get(URI.create("/org/%76enues"))).andExpect(status().isOk());
    }

    // ---------- sessions ----------

    @Test
    void openIsIdempotentPerCashierAndGetShowsTheFigures() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        call(cashier, get("/org/pos/session")).andExpect(status().isOk()).andExpect(jsonPath("$.session").isEmpty());
        String json = body(call(cashier, post("/org/pos/sessions"), "{\"label\": \" Guichet Nord \"}")
                .andExpect(status().isCreated()));
        assertKeys(json, "$", "id", "label", "opened_at", "orders", "tickets", "prints", "collected_mad");
        String id = JsonPath.read(json, "$.id");
        assertThat(JsonPath.<String>read(json, "$.label")).isEqualTo("Guichet Nord");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Autre\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.label").value("Guichet Nord"));
        assertThat(audits("pos.open", id)).isEqualTo(1);
        // another cashier gets their own till
        assertThat(openSession(caller(s.org().org(), "CASHIER"), "Guichet Sud")).isNotEqualTo(id);

        for (String bad : List.of("{}", "{\"label\": \"  \"}", "{\"label\": \"%s\"}".formatted("x".repeat(41)))) {
            call(caller(s.org().org(), "CASHIER"), post("/org/pos/sessions"), bad)
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.label").exists());
        }

        String order = sold(cashier, s, holder("Yazid Aqel"), holder("Salma Idrissi"));
        call(cashier, get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isOk());
        json = body(call(cashier, get("/org/pos/session")).andExpect(status().isOk()));
        assertKeys(json, "$.session", "id", "label", "opened_at", "orders", "tickets", "prints", "collected_mad");
        assertThat(JsonPath.<String>read(json, "$.session.id")).isEqualTo(id);
        assertThat(JsonPath.<Integer>read(json, "$.session.orders")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(json, "$.session.tickets")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$.session.prints")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(json, "$.session.collected_mad")).isEqualTo(10000);
    }

    @Test
    void closeFreezesExpectedAndAuditsTheGap() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        String id = openSession(cashier, "Guichet Nord");
        sold(cashier, s, holder("Yazid Aqel"));
        Code c = cashCode(s, 2);
        collect(cashier, c.code(), 10000).andExpect(status().isOk());

        // another cashier's (or OPS's) close → 404; nothing changes
        Caller other = caller(s.org().org(), "CASHIER");
        call(other, post("/org/pos/sessions/" + id + "/close"), "{\"declared_mad\": 0}").andExpect(status().isNotFound());
        call(caller(s.org().org(), "OPS"), post("/org/pos/sessions/" + id + "/close"), "{\"declared_mad\": 0}")
                .andExpect(status().isNotFound());
        call(admin(), post("/org/pos/sessions/" + id + "/close"), "{\"declared_mad\": 0}").andExpect(status().isNotFound());
        for (String bad : List.of("{}", "{\"declared_mad\": -1}", "{\"declared_mad\": 10.5}")) {
            call(cashier, post("/org/pos/sessions/" + id + "/close"), bad).andExpect(status().isUnprocessableContent());
        }

        String json = body(call(cashier, post("/org/pos/sessions/" + id + "/close"), "{\"declared_mad\": 14500}")
                .andExpect(status().isOk()));
        assertKeys(json, "$", "id", "label", "staff", "opened_at", "closed_at", "orders", "tickets", "prints",
                "expected_mad", "declared_mad", "variance_mad");
        assertKeys(json, "$.staff", "id", "name");
        assertThat(JsonPath.<Integer>read(json, "$.expected_mad")).isEqualTo(15000); // 5000 sale + 10000 code
        assertThat(JsonPath.<Integer>read(json, "$.declared_mad")).isEqualTo(14500);
        assertThat(JsonPath.<Integer>read(json, "$.variance_mad")).isEqualTo(-500);
        assertThat(JsonPath.<Integer>read(json, "$.orders")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$.tickets")).isEqualTo(3);
        assertThat(JsonPath.<String>read(json, "$.closed_at")).isNotNull();
        assertThat(jdbc.sql("select payload::text from audit_log where action = 'pos.close' and target = ?").param(id)
                .query(String.class).single())
                .contains("\"expected_mad\": 15000", "\"declared_mad\": 14500", "\"variance_mad\": -500");

        conflict(call(cashier, post("/org/pos/sessions/" + id + "/close"), "{\"declared_mad\": 15000}"),
                "POS_SESSION_CLOSED");
        assertThat(jdbc.sql("select declared_mad from pos_sessions where id = ?::uuid").param(id).query(Long.class)
                .single()).isEqualTo(14500);
        // closed: no more sales, no session; a new one can be opened
        conflict(sell(cashier, UUID.randomUUID(), s, holder("Yazid Aqel")), "POS_SESSION_REQUIRED");
        call(cashier, get("/org/pos/session")).andExpect(jsonPath("$.session").isEmpty());
        assertThat(openSession(cashier, "Guichet Nord")).isNotEqualTo(id);

        // ADMIN may close any till of the organizer; another organizer's is 404
        String otherId = openSession(other, "Guichet Sud");
        call(admin(), post("/org/pos/sessions/" + otherId + "/close"), "{\"declared_mad\": 0}")
                .andExpect(status().isNotFound());
        call(s.org(), post("/org/pos/sessions/" + otherId + "/close"), "{\"declared_mad\": 0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.expected_mad").value(0))
                .andExpect(jsonPath("$.variance_mad").value(0));
    }

    // ---------- walk-up sale ----------

    @Test
    void aSaleNeedsAnOpenSession() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        conflict(sell(cashier, UUID.randomUUID(), s, holder("Yazid Aqel")), "POS_SESSION_REQUIRED");
        assertThat(sold(s.priceId())).isZero();
    }

    @Test
    void aSaleIsPaidAtOnceWithACashPaymentOfTheSession() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        String session = openSession(cashier, "Guichet Nord");
        UUID saleId = UUID.randomUUID();
        String phone = randomPhone();
        String json = body(sell(cashier, saleId, s, holder("Yazid Aqel"), holder("Salma  Idrissi", phone))
                .andExpect(status().isCreated()));
        assertKeys(json, "$", "id", "status", "channel", "total_mad", "items", "ticket_ids");
        assertThat(JsonPath.<String>read(json, "$.id")).isEqualTo(saleId.toString());
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("PAID");
        assertThat(JsonPath.<String>read(json, "$.channel")).isEqualTo("POS");
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(10000);
        assertThat(JsonPath.<List<String>>read(json, "$.items[0].holders[*].label")).containsExactly("Yazid A.", "Salma I.");
        assertThat(json).doesNotContain("Aqel", "Idrissi", phone);
        List<String> ticketIds = JsonPath.read(json, "$.ticket_ids");
        assertThat(ticketIds).hasSize(2);

        String order = saleId.toString();
        assertThat(jdbc.sql("""
                select status || ' ' || channel || ' ' || (user_id is null) || ' ' || payment_method || ' '
                       || (paid_at is not null) || ' ' || (organizer_id = ?)
                from orders where id = ?::uuid""").params(s.org().org(), order).query(String.class).single())
                .isEqualTo("PAID POS true POS_CASH true true");
        assertThat(jdbc.sql("""
                select method || ' ' || provider || ' ' || status || ' ' || amount_mad || ' ' || pos_session_id || ' '
                       || (completed_at is not null)
                from payments where order_id = ?::uuid""").param(order).query(String.class).list())
                .containsExactly("POS_CASH POS SUCCEEDED 10000 " + session + " true");
        assertThat(tickets(order)).isEqualTo(2);
        assertThat(sold(s.priceId())).isEqualTo(2);
        assertThat(audits("pos.sale", order)).isEqualTo(1);

        // no phone → no holding account and no delivery; a phone → that account, one delivery
        assertThat(jdbc.sql("""
                select coalesce(u.phone_e164, 'none') from tickets t left join users u on u.id = t.holder_user_id
                where t.order_id = ?::uuid order by t.holder_name""").param(order).query(String.class).list())
                .containsExactly(phone, "none");
        assertThat(deliveryRows(order)).isEqualTo(1);
        assertThat(jdbc.sql("""
                select t.holder_user_id is not null from ticket_deliveries d join tickets t on t.id = d.ticket_id
                where t.order_id = ?::uuid""").param(order).query(Boolean.class).list()).containsExactly(true);
        deliveries.deliverDue(); // never throws for a holder-less ticket
        assertThat(jdbc.sql("""
                select d.status::text from ticket_deliveries d join tickets t on t.id = d.ticket_id
                where t.order_id = ?::uuid""").param(order).query(String.class).list()).containsExactly("SENT");

        // a paper-only sale queues nothing
        String paper = sold(cashier, s, holder("Yazid Aqel"));
        assertThat(deliveryRows(paper)).isZero();
        assertThat(jdbc.sql("select holder_user_id is null from tickets where order_id = ?::uuid").param(paper)
                .query(Boolean.class).list()).containsExactly(true);
    }

    @Test
    void theSameSaleIdIsOneOrderFromItsSessionOnly() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        openSession(cashier, "Guichet Nord");
        UUID saleId = UUID.randomUUID();
        String first = body(sell(cashier, saleId, s, holder("Yazid Aqel")).andExpect(status().isCreated()));
        String again = body(sell(cashier, saleId, s, holder("Yazid Aqel")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(again, "$.id")).isEqualTo(saleId.toString());
        assertThat(JsonPath.<List<String>>read(again, "$.ticket_ids"))
                .isEqualTo(JsonPath.<List<String>>read(first, "$.ticket_ids"));
        assertThat(jdbc.sql("select count(*) from payments where order_id = ?").param(saleId).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(sold(s.priceId())).isEqualTo(1);
        assertThat(audits("pos.sale", saleId.toString())).isEqualTo(1);

        // another session (another cashier) → 422 TAKEN; a fan order id too
        Caller other = caller(s.org().org(), "CASHIER");
        openSession(other, "Guichet Sud");
        sell(other, saleId, s, holder("Yazid Aqel")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.sale_id").exists())
                .andExpect(jsonPath("$.details.reason").value("TAKEN"));
        String fanOrder = order(fanToken(randomPhone()), s, 1);
        sell(cashier, UUID.fromString(fanOrder), s, holder("Yazid Aqel")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("TAKEN"));
        assertThat(sold(s.priceId())).isEqualTo(2);
    }

    @Test
    void aSaleFollowsTheOnlineWindowLimitAndQuota() throws Exception {
        Sale s = onSale(3);
        Caller cashier = caller(s.org().org(), "CASHIER");
        openSession(cashier, "Guichet Nord");
        String h = holder("Yazid Aqel");
        ResultActions r = sell(cashier, UUID.randomUUID(), s, h, h, h, h, h);
        conflict(r, "PER_ACCOUNT_LIMIT");
        r.andExpect(jsonPath("$.details.limit").value(4)).andExpect(jsonPath("$.details.held").value(0));
        conflict(sell(cashier, UUID.randomUUID(), s, holder("Yazid Aqel"), holder("Yazid Aqel"), holder("Yazid Aqel"),
                holder("Yazid Aqel")), "SOLD_OUT");
        // items and holders: same paths as /fan/orders
        sell(cashier, UUID.randomUUID(), s, holder("Y")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields['items[0].holders[0].name']").exists());
        sell(cashier, UUID.randomUUID(), s, holder("Yazid Aqel", "0612")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields['items[0].holders[0].phone']").exists());
        // another organizer's event → 404
        Sale b = onSale(3);
        sell(cashier, UUID.randomUUID(), b, holder("Yazid Aqel")).andExpect(status().isNotFound());
        // at or after sales close (T0 − 60) → 409 CLOSED
        jdbc.sql("update events set refund_cutoff_at = now() - interval '1 second' where id = ?").param(s.eventId()).update();
        ResultActions late = sell(cashier, UUID.randomUUID(), s, holder("Yazid Aqel"));
        conflict(late, "EVENT_NOT_ON_SALE");
        late.andExpect(jsonPath("$.details.reason").value("CLOSED"));
        assertThat(sold(s.priceId())).isZero();
        assertThat(sold(b.priceId())).isZero();
    }

    // ---------- cash codes ----------

    @Test
    void lookupIsNormalisedOrganizerScopedAndRevealsNoPersonalData() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 2);
        Caller cashier = caller(s.org().org(), "CASHIER");
        String typed = "jmh-" + c.code().substring(0, 4).toLowerCase().replace('0', 'o').replace('1', 'l') + " "
                + c.code().substring(4).replace('1', 'I');
        String json = body(call(cashier, get("/org/pos/codes/" + typed)).andExpect(status().isOk()));
        assertKeys(json, "$", "order_id", "status", "payable", "reserved_until", "amount_mad", "places", "event", "holders");
        assertKeys(json, "$.event", "id", "title", "starts_at", "timezone");
        assertThat(JsonPath.<String>read(json, "$.order_id")).isEqualTo(c.orderId());
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("AWAITING_CASH");
        assertThat(JsonPath.<Boolean>read(json, "$.payable")).isTrue();
        assertThat(JsonPath.<Integer>read(json, "$.amount_mad")).isEqualTo(10000);
        assertThat(JsonPath.<Integer>read(json, "$.places")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$.event.id")).isEqualTo(s.eventId());
        assertThat(JsonPath.<List<String>>read(json, "$.holders")).containsExactly("Yazid A.", "Yazid A.");
        assertThat(json).doesNotContain("Aqel", "+212");
        // without a session too (only collect, sale and print need one)

        call(caller(onSale(1).org().org(), "CASHIER"), get("/org/pos/codes/" + c.code())).andExpect(status().isNotFound());
        for (String bad : List.of("ZZZZZZ", "ABC", c.code() + "0", "U" + c.code().substring(1))) {
            call(cashier, get("/org/pos/codes/" + bad)).andExpect(status().isNotFound());
        }
        // past its hold: not payable any more
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(c.orderId()).update();
        call(cashier, get("/org/pos/codes/" + c.code())).andExpect(jsonPath("$.payable").value(false))
                .andExpect(jsonPath("$.status").value("AWAITING_CASH"));
    }

    @Test
    void collectPaysTheCodeAtTheTill() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 2);
        Caller cashier = caller(s.org().org(), "CASHIER");
        conflict(collect(cashier, c.code(), 10000), "POS_SESSION_REQUIRED");
        String session = openSession(cashier, "Guichet Nord");
        collect(cashier, c.code(), 9999).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.amount_mad").exists());
        Caller orgB = caller(onSale(1).org().org(), "CASHIER");
        openSession(orgB, "Guichet");
        collect(orgB, c.code(), 10000).andExpect(status().isNotFound());
        assertThat(orderStatus(c.orderId())).isEqualTo("AWAITING_CASH");

        String json = body(collect(cashier, "JMH " + c.code().substring(0, 4) + " " + c.code().substring(4), 10000)
                .andExpect(status().isOk()));
        assertKeys(json, "$", "order_id", "status", "ticket_ids");
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("PAID");
        assertThat(JsonPath.<List<String>>read(json, "$.ticket_ids")).hasSize(2);
        assertThat(jdbc.sql("select status || ' ' || payment_method || ' ' || payment_ref from orders where id = ?::uuid")
                .param(c.orderId()).query(String.class).single()).isEqualTo("PAID CASH_AGENCY " + c.code());
        assertThat(jdbc.sql("""
                select status || ' ' || provider || ' ' || pos_session_id || ' ' || (completed_at is not null)
                from payments where pay_code = ?""").param(c.code()).query(String.class).single())
                .isEqualTo("SUCCEEDED POS " + session + " true");
        assertThat(tickets(c.orderId())).isEqualTo(2);
        assertThat(deliveryRows(c.orderId())).isEqualTo(2);
        assertThat(audits("pos.collect", c.orderId())).isEqualTo(1);

        // the same session again → the same answer, nothing more
        collect(cashier, c.code(), 10000).andExpect(status().isOk()).andExpect(jsonPath("$.order_id").value(c.orderId()))
                .andExpect(jsonPath("$.ticket_ids").value(JsonPath.<List<String>>read(json, "$.ticket_ids")));
        assertThat(tickets(c.orderId())).isEqualTo(2);
        assertThat(audits("pos.collect", c.orderId())).isEqualTo(1);
        // another session → 409 with the order status
        Caller other = caller(s.org().org(), "CASHIER");
        openSession(other, "Guichet Sud");
        ResultActions r = collect(other, c.code(), 10000);
        conflict(r, "CODE_NOT_PAYABLE");
        r.andExpect(jsonPath("$.details.status").value("PAID"))
                .andExpect(jsonPath("$.details.collected_by_you").doesNotExist()); // another cashier: no flag
        // the slips: by the session that collected it, or a manager's reprint; not by another till
        call(cashier, get("/org/pos/orders/" + c.orderId() + "/tickets")).andExpect(status().isOk());
        call(other, get("/org/pos/orders/" + c.orderId() + "/tickets")).andExpect(status().isNotFound());
        call(s.org(), get("/org/pos/orders/" + c.orderId() + "/tickets")).andExpect(status().isOk());

        // the same cashier after closing and reopening the till: 409 PAID, collected_by_you (keep the cash)
        call(cashier, post("/org/pos/sessions/" + session + "/close"), "{\"declared_mad\": 10000}")
                .andExpect(status().isOk());
        conflict(collect(cashier, c.code(), 10000), "POS_SESSION_REQUIRED");
        openSession(cashier, "Guichet Nord");
        r = collect(cashier, c.code(), 10000);
        conflict(r, "CODE_NOT_PAYABLE");
        r.andExpect(jsonPath("$.details.status").value("PAID")).andExpect(jsonPath("$.details.collected_by_you").value(true));
        assertThat(audits("pos.collect", c.orderId())).isEqualTo(1);
    }

    @Test
    void anExpiredCodeIsNotPayable() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 1);
        Caller cashier = caller(s.org().org(), "CASHIER");
        openSession(cashier, "Guichet Nord");
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(c.orderId()).update();
        ResultActions r = collect(cashier, c.code(), 5000);
        conflict(r, "CODE_NOT_PAYABLE");
        r.andExpect(jsonPath("$.details.status").value("EXPIRED"));
        assertThat(jdbc.sql("select status::text from payments where pay_code = ?").param(c.code()).query(String.class)
                .single()).isEqualTo("PENDING");
        // once swept by the expiry job: the order status
        jdbc.sql("update orders set status = 'EXPIRED' where id = ?::uuid").param(c.orderId()).update();
        r = collect(cashier, c.code(), 5000);
        conflict(r, "CODE_NOT_PAYABLE");
        r.andExpect(jsonPath("$.details.status").value("EXPIRED"));
        assertThat(tickets(c.orderId())).isZero();
    }

    @Test
    void twoTillsCollectingOneCodeAtOnceGetOnePaid() throws Exception {
        Sale s = onSale(10);
        Code c = cashCode(s, 1);
        Caller a = caller(s.org().org(), "CASHIER");
        Caller b = caller(s.org().org(), "CASHIER");
        openSession(a, "Guichet Nord");
        openSession(b, "Guichet Sud");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (Caller c2 : List.of(a, b)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return collect(c2, c.code(), 5000).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(tickets(c.orderId())).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from payments where order_id = ?::uuid and status = 'SUCCEEDED'")
                .param(c.orderId()).query(Integer.class).single()).isEqualTo(1);
    }

    // ---------- print ----------

    @Test
    void printDataCarriesTheStaticTokenOfValidTicketsOnly() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        String fanOrder = order(fanToken(randomPhone()), s, 1);
        conflict(call(cashier, get("/org/pos/orders/" + fanOrder + "/tickets")), "POS_SESSION_REQUIRED");
        String session = openSession(cashier, "Guichet Nord");
        String order = sold(cashier, s, holder("Yazid Aqel"), holder("Salma Idrissi", randomPhone()));
        jdbc.sql("""
                update tickets set status = 'BLOCKED' where id = (select id from tickets where order_id = ?::uuid
                and holder_name = 'Salma Idrissi')""").param(order).update();

        String json = body(call(cashier, get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertKeys(json, "$", "order_id", "event", "tickets");
        assertKeys(json, "$.event", "id", "title", "starts_at", "timezone", "venue");
        assertKeys(json, "$.event.venue", "name");
        assertThat(JsonPath.<String>read(json, "$.event.venue.name")).isEqualTo("Stade");
        assertThat(JsonPath.<List<?>>read(json, "$.tickets")).hasSize(2);
        assertKeys(json, "$.tickets[0]", "id", "ticket_no", "status", "zone", "gate_codes", "holder_label",
                "unit_price_mad", "qr");
        assertKeys(json, "$.tickets[0].zone", "code", "name");
        assertThat(json).doesNotContain("Aqel", "Idrissi", "+212", "k_t", "holder_name");

        List<String> valid = JsonPath.read(json, "$.tickets[?(@.status == 'VALID')].qr");
        assertThat(valid).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(json, "$.tickets[?(@.status == 'BLOCKED')].qr")).containsExactly((Object) null);
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[?(@.status == 'VALID')].holder_label"))
                .containsExactly("Yazid A.");
        assertThat(JsonPath.<List<Integer>>read(json, "$.tickets[*].unit_price_mad")).containsOnly(5000);
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[*].zone.code")).containsOnly("NORD");
        String validId = JsonPath.<List<String>>read(json, "$.tickets[?(@.status == 'VALID')].id").getFirst();
        TicketToken.Parsed parsed = TicketToken.decode(valid.getFirst());
        assertThat(parsed.version()).isEqualTo(TicketToken.VERSION_STATIC);
        assertThat(parsed.ticketId()).isEqualTo(UUID.fromString(validId));
        assertThat(parsed.eventId()).isEqualTo(s.eventId());
        assertThat(parsed.zoneId()).isEqualTo(s.zoneId());
        Optional<TicketToken.Result> verdict = events.withEventKey(s.org().org(), s.eventId(),
                k -> TicketToken.verify(parsed, s.eventId(), k, TicketToken.slotAt(Instant.now())));
        assertThat(verdict).isEmpty();
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[*].ticket_no"))
                .contains(TicketToken.ticketNo(UUID.fromString(validId)));

        // every call is a print: audited and counted
        call(cashier, get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isOk());
        assertThat(audits("pos.print", session)).isEqualTo(2);
        call(cashier, get("/org/pos/session")).andExpect(jsonPath("$.session.prints").value(2));

        // a cashier prints only orders paid in their current session: not another till's
        Caller other = caller(s.org().org(), "CASHIER");
        openSession(other, "Guichet Sud");
        call(other, get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isNotFound());
        // ADMIN/OPS reprint without a session; the print is counted on the till that sold it
        call(s.org(), get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        call(caller(s.org().org(), "OPS"), get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isOk());
        assertThat(audits("pos.print", session)).isEqualTo(4);
        call(s.org(), get("/org/pos/orders/" + fanOrder + "/tickets")).andExpect(status().isNotFound());

        // only orders paid at a till of this organizer
        call(cashier, get("/org/pos/orders/" + fanOrder + "/tickets")).andExpect(status().isNotFound());
        call(cashier, get("/org/pos/orders/" + UUID.randomUUID() + "/tickets")).andExpect(status().isNotFound());
        Caller b = caller(onSale(1).org().org(), "CASHIER");
        openSession(b, "Guichet");
        call(b, get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isNotFound());
        call(caller(b.org(), "ADMIN"), get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isNotFound());
        assertThat(audits("pos.print", session)).isEqualTo(4);
        // after the cashier's next session opens, the old sale is a manager's reprint only
        call(cashier, post("/org/pos/sessions/" + session + "/close"), "{\"declared_mad\": 0}")
                .andExpect(status().isOk());
        openSession(cashier, "Guichet Nord");
        call(cashier, get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isNotFound());
    }

    /** Close waits for a sale that holds the till's lock: the frozen expected_mad includes it. */
    @Test
    void closeWaitsForASaleInFlight() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        String session = openSession(cashier, "Guichet Nord");
        UUID user = jdbc.sql("select user_id from staff where organizer_id = ? and role = 'CASHIER'")
                .param(s.org().org()).query(UUID.class).single();
        UUID saleId = UUID.randomUUID();
        var items = List.of(new OrderService.ItemRequest(UUID.fromString(s.priceId()), 1,
                List.of(new OrderService.HolderRequest("Yazid Aqel", null))));
        CountDownLatch sold = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> sale = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(t -> {
                posService.sale(s.org().org(), user, saleId, s.eventId(), items);
                sold.countDown();
                try {
                    commit.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(sold.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> close = pool.submit(() -> body(call(cashier, post("/org/pos/sessions/" + session + "/close"),
                    "{\"declared_mad\": 5000}")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.sql("""
                    select count(*) from pg_stat_activity
                    where wait_event_type = 'Lock' and query like '%pos_sessions%'""").query(Integer.class).single() == 0) {
                assertThat(System.nanoTime()).as("close never waited for the sale").isLessThan(deadline);
                Thread.sleep(20);
            }
            assertThat(close.isDone()).isFalse();
            commit.countDown();
            sale.get(10, TimeUnit.SECONDS);
            String json = close.get(10, TimeUnit.SECONDS);
            assertThat(JsonPath.<Integer>read(json, "$.expected_mad")).isEqualTo(5000);
            assertThat(JsonPath.<Integer>read(json, "$.variance_mad")).isZero();
            assertThat(JsonPath.<Integer>read(json, "$.orders")).isEqualTo(1);
        }
        assertThat(orderStatus(saleId.toString())).isEqualTo("PAID");
    }
}
