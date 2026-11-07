package ma.jomhor.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.payments.domain.CashCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract (BE-25): docs/04-api.md §Organizer POST /org/events/{id}/tickets/search, POST /org/tickets/{id}/block,
 * unblock, refund, GET /org/refunds and POST /org/refunds/{id}/done; the roles of §Organizer Lot 3; plan lot-3 Q52.
 */
class TicketAdminApiTest extends PaymentTest {

    private static final String[] ROW_KEYS = {"id", "ticket_no", "status", "zone", "holder_name", "holder_label",
            "unit_price_mad", "order_id", "channel", "payment_method", "issued_at", "refund", "transferred_to",
            "transferred_from"};
    private static final String[] REFUND_KEYS = {"id", "event_id", "ticket_id", "ticket_no", "order_id", "amount_mad",
            "method", "reason", "status", "requested_by", "requested_at", "done_by", "done_at", "reference"};

    @Autowired
    ManifestService manifests;

    /** A paid card order of {@code qty} places held by the buyer: its id and tickets. */
    private record Bought(String token, String phone, String orderId, List<UUID> tickets) {}

    private Bought buy(Sale s, int qty) throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        String orderId = order(token, s, qty);
        callback(approve(payment(token, orderId), total(orderId))).andExpect(status().isSeeOther());
        List<UUID> ids = jdbc.sql("select id from tickets where order_id = ?::uuid order by id").param(orderId)
                .query(UUID.class).list();
        return new Bought(token, phone, orderId, ids);
    }

    private ResultActions act(Caller c, String action, UUID ticket, String reason) throws Exception {
        return call(c, post("/org/tickets/" + ticket + "/" + action), "{\"reason\": \"%s\"}".formatted(reason));
    }

    private ResultActions search(Caller c, int eventId, String q) throws Exception {
        return call(c, post("/org/events/" + eventId + "/tickets/search"), "{\"q\": \"%s\"}".formatted(q));
    }

    private static void conflict(ResultActions r, String code) throws Exception {
        r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }

    private String ticketStatus(UUID id) {
        return jdbc.sql("select status::text from tickets where id = ?").param(id).query(String.class).single();
    }

    private void freeze(int eventId) {
        jdbc.sql("update events set refund_cutoff_at = now() - interval '1 second' where id = ?").param(eventId).update();
    }

    private String latestManifest(int eventId) {
        return jdbc.sql("select body from manifests where event_id = ? order by version desc limit 1").param(eventId)
                .query((rs, n) -> {
                    try (var in = new GZIPInputStream(new ByteArrayInputStream(rs.getBytes(1)))) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }).single();
    }

    private static String ticketNo(UUID id) {
        String hex = id.toString().substring(28).toUpperCase();
        return hex.substring(0, 4) + " " + hex.substring(4);
    }

    // ---------- block / unblock ----------

    @Test
    void blockStopsKeysPdfAndTheGateAndUnblockGivesItBack() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        UUID t = b.tickets().getFirst();
        Caller ops = caller(s.org().org(), "OPS");

        String json = body(act(ops, "block", t, " Fraude signalée ").andExpect(status().isOk()));
        assertKeys(json, "$", ROW_KEYS);
        assertKeys(json, "$.zone", "id", "code", "name");
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("BLOCKED");
        assertThat(JsonPath.<String>read(json, "$.ticket_no")).isEqualTo(ticketNo(t));
        assertThat(JsonPath.<Integer>read(json, "$.unit_price_mad")).isEqualTo(5000);
        assertThat(JsonPath.<Object>read(json, "$.refund")).isNull();
        assertThat(ticketStatus(t)).isEqualTo("BLOCKED");
        assertThat(jdbc.sql("select payload ->> 'reason' from audit_log where action = 'ticket.block' and target = ?")
                .param(t.toString()).query(String.class).single()).isEqualTo("Fraude signalée");
        assertThat(sold(s.priceId())).isEqualTo(1); // a block keeps the place

        // the next manifest version carries it; no k_t and no PDF from then on
        assertThat(manifests.build(s.org().org(), s.eventId())).isPresent();
        assertThat(latestManifest(s.eventId())).contains("T\t" + t + "\t" + s.zoneId() + "\tBLOCKED\t");
        String held = body(fan(b.token(), get("/fan/tickets?with_keys=true")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(held, "$.tickets[0].status")).isEqualTo("BLOCKED");
        assertThat(JsonPath.<Map<String, Object>>read(held, "$.tickets[0]")).doesNotContainKey("k_t");
        conflict(fan(b.token(), get("/fan/tickets/" + t + "/pdf")), "TICKET_NOT_VALID");

        // block a BLOCKED ticket → 409 TICKET_STATUS with its status
        act(ops, "block", t, "encore").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_STATUS")).andExpect(jsonPath("$.details.status").value("BLOCKED"));

        json = body(act(s.org(), "unblock", t, "Erreur").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("VALID");
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'ticket.unblock' and target = ?")
                .param(t.toString()).query(Integer.class).single()).isEqualTo(1);
        held = body(fan(b.token(), get("/fan/tickets?with_keys=true")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(held, "$.tickets[0].k_t")).isNotBlank();
        conflict(act(ops, "unblock", t, "encore"), "TICKET_STATUS");
    }

    @Test
    void aReasonIsRequiredUpTo200Characters() throws Exception {
        Sale s = onSale(10);
        UUID t = buy(s, 1).tickets().getFirst();
        for (String body : List.of("{}", "{\"reason\": \"   \"}", "{\"reason\": \"%s\"}".formatted("x".repeat(201)))) {
            for (String action : List.of("block", "unblock", "refund")) {
                call(s.org(), post("/org/tickets/" + t + "/" + action), body).andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.details.fields.reason").exists());
            }
        }
        act(s.org(), "block", t, "x".repeat(200)).andExpect(status().isOk());
    }

    @Test
    void nothingMovesAtOrAfterTheRefundCutoff() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 2);
        act(s.org(), "block", b.tickets().get(1), "avant").andExpect(status().isOk());
        freeze(s.eventId());
        conflict(act(s.org(), "block", b.tickets().get(0), "tard"), "REFUND_FROZEN");
        conflict(act(s.org(), "unblock", b.tickets().get(1), "tard"), "REFUND_FROZEN");
        conflict(act(s.org(), "refund", b.tickets().get(0), "tard"), "REFUND_FROZEN");
        assertThat(ticketStatus(b.tickets().get(0))).isEqualTo("VALID");
        assertThat(ticketStatus(b.tickets().get(1))).isEqualTo("BLOCKED");
        assertThat(sold(s.priceId())).isEqualTo(2);
    }

    // ---------- refund ----------

    @Test
    void refundReleasesThePlaceAndOwesTheUnitPrice() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 2);
        UUID t = b.tickets().getFirst();
        String paymentId = jdbc.sql("select id::text from payments where order_id = ?::uuid and status = 'SUCCEEDED'")
                .param(b.orderId()).query(String.class).single();
        assertThat(sold(s.priceId())).isEqualTo(2);
        // the price moves after the sale: the refund owes the order item's snapshot (5000), not the new price
        call(s.org(), patch("/org/events/" + s.eventId() + "/prices/" + s.priceId()), "{\"price_mad\": 9900}")
                .andExpect(status().isOk());

        // OPS may block but not refund
        call(caller(s.org().org(), "OPS"), post("/org/tickets/" + t + "/refund"), "{\"reason\": \"x\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(ticketStatus(t)).isEqualTo("VALID");

        String json = body(act(s.org(), "refund", t, "Match reporté").andExpect(status().isOk()));
        assertKeys(json, "$", ROW_KEYS);
        assertKeys(json, "$.refund", "id", "status", "amount_mad");
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("REFUNDED");
        assertThat(JsonPath.<String>read(json, "$.refund.status")).isEqualTo("REQUIRED");
        assertThat(JsonPath.<Integer>read(json, "$.refund.amount_mad")).isEqualTo(5000);
        assertThat(ticketStatus(t)).isEqualTo("REFUNDED");
        assertThat(sold(s.priceId())).isEqualTo(1);
        assertThat(orderStatus(b.orderId())).isEqualTo("PAID");
        Map<String, Object> row = jdbc.sql("""
                select amount_mad, payment_id::text as payment_id, order_id::text as order_id, event_id, reason, status::text
                as status from refunds where ticket_id = ?""").param(t).query().singleRow();
        assertThat(row).containsEntry("amount_mad", 5000L).containsEntry("payment_id", paymentId)
                .containsEntry("order_id", b.orderId()).containsEntry("event_id", s.eventId())
                .containsEntry("reason", "Match reporté").containsEntry("status", "REQUIRED");
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'ticket.refund' and target = ?")
                .param(t.toString()).query(Integer.class).single()).isEqualTo(1);

        // refund twice → 409, nothing more released
        conflict(act(s.org(), "refund", t, "encore"), "TICKET_STATUS");
        conflict(act(s.org(), "block", t, "encore"), "TICKET_STATUS");
        assertThat(sold(s.priceId())).isEqualTo(1);

        // a BLOCKED ticket can be refunded too
        UUID other = b.tickets().get(1);
        act(s.org(), "block", other, "doute").andExpect(status().isOk());
        act(s.org(), "refund", other, "rembourse").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(sold(s.priceId())).isZero();
        assertThat(orderStatus(b.orderId())).isEqualTo("PAID");
    }

    @Test
    void twoConcurrentRefundsOfOneTicketRefundItOnce() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        UUID t = b.tickets().getFirst();
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> codes = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return act(s.org(), "refund", t, "double").andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> f : runs) {
                codes.add(f.get(30, TimeUnit.SECONDS));
            }
        }
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(sold(s.priceId())).isZero(); // released once
        assertThat(jdbc.sql("select count(*) from refunds where ticket_id = ?").param(t).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(ticketStatus(t)).isEqualTo("REFUNDED");
    }

    @Test
    void financeListsAndMarksARefundDoneOnce() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 2);
        act(s.org(), "refund", b.tickets().get(0), "Premier").andExpect(status().isOk());
        act(s.org(), "refund", b.tickets().get(1), "Second").andExpect(status().isOk());
        Caller finance = caller(s.org().org(), "FINANCE");

        String json = body(call(finance, get("/org/refunds")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(json, "$")).hasSize(2);
        assertKeys(json, "$[0]", REFUND_KEYS);
        assertKeys(json, "$[0].requested_by", "id", "name");
        assertThat(JsonPath.<String>read(json, "$[0].reason")).isEqualTo("Second"); // newest first
        assertThat(JsonPath.<String>read(json, "$[0].method")).isEqualTo("CARD");
        assertThat(JsonPath.<Integer>read(json, "$[0].amount_mad")).isEqualTo(5000);
        assertThat(JsonPath.<String>read(json, "$[0].ticket_no")).isEqualTo(ticketNo(b.tickets().get(1)));
        assertThat(JsonPath.<String>read(json, "$[0].requested_by.name")).isEqualTo("Test");
        assertThat(JsonPath.<Object>read(json, "$[0].done_by")).isNull();
        String first = JsonPath.read(json, "$[1].id");

        // OPS reads but can't mark done; bad reference → 422
        call(caller(s.org().org(), "OPS"), get("/org/refunds?event_id=" + s.eventId())).andExpect(status().isOk());
        call(caller(s.org().org(), "OPS"), post("/org/refunds/" + first + "/done"), "{\"reference\": \"R1\"}")
                .andExpect(status().isForbidden());
        for (String bad : List.of("{}", "{\"reference\": \" \"}", "{\"reference\": \"%s\"}".formatted("r".repeat(101)))) {
            call(finance, post("/org/refunds/" + first + "/done"), bad).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.fields.reference").exists());
        }

        String done = body(call(finance, post("/org/refunds/" + first + "/done"), "{\"reference\": \"VIR-2026-001\"}")
                .andExpect(status().isOk()));
        assertKeys(done, "$", REFUND_KEYS);
        assertThat(JsonPath.<String>read(done, "$.status")).isEqualTo("DONE");
        assertThat(JsonPath.<String>read(done, "$.reference")).isEqualTo("VIR-2026-001");
        assertThat(JsonPath.<String>read(done, "$.done_at")).isNotBlank();
        assertKeys(done, "$.done_by", "id", "name");
        // again → 200 unchanged (the first reference stays)
        String again = body(call(finance, post("/org/refunds/" + first + "/done"), "{\"reference\": \"AUTRE\"}")
                .andExpect(status().isOk()));
        assertThat(again).isEqualTo(done);
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'refund.done' and target = ?").param(first)
                .query(Integer.class).single()).isEqualTo(1);

        // REQUIRED first, then newest; filters
        json = body(call(finance, get("/org/refunds")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[*].status")).containsExactly("REQUIRED", "DONE");
        assertThat(JsonPath.<List<String>>read(body(call(finance, get("/org/refunds?status=DONE"))), "$[*].id"))
                .containsExactly(first);
        assertThat(JsonPath.<List<Object>>read(body(call(finance, get("/org/refunds?event_id=" + (s.eventId() + 100000)))),
                "$")).isEmpty();
        // an ADMIN may mark done too
        String second = JsonPath.read(json, "$[0].id");
        call(s.org(), post("/org/refunds/" + second + "/done"), "{\"reference\": \"Caisse 12\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void aRefundWithoutAPaymentHasANullMethod() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        jdbc.sql("delete from payments where order_id = ?::uuid").param(b.orderId()).update();
        act(s.org(), "refund", b.tickets().getFirst(), "Invitation").andExpect(status().isOk());
        call(s.org(), get("/org/refunds")).andExpect(status().isOk()).andExpect(jsonPath("$[0].method").isEmpty());
    }

    // ---------- roles and tenancy ----------

    @Test
    void cashierAndGateGet403AndFinanceOnlyReads() throws Exception {
        Sale s = onSale(10);
        UUID t = buy(s, 1).tickets().getFirst();
        String refundId = JsonPath.read(body(act(s.org(), "refund", t, "x").andExpect(status().isOk())), "$.refund.id");
        UUID other = buy(s, 1).tickets().getFirst();
        List<MockHttpServletRequestBuilder> all = List.of(post("/org/events/" + s.eventId() + "/tickets/search"),
                post("/org/tickets/" + other + "/block"), post("/org/tickets/" + other + "/unblock"),
                post("/org/tickets/" + other + "/refund"), get("/org/refunds"), post("/org/refunds/" + refundId + "/done"));
        for (String role : List.of("CASHIER", "GATE", "SUPERVISOR")) {
            Caller c = caller(s.org().org(), role);
            for (var req : all) {
                call(c, req, "{\"q\": \"+212600000000\", \"reason\": \"x\", \"reference\": \"x\"}")
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }
        Caller finance = caller(s.org().org(), "FINANCE");
        for (String action : List.of("block", "unblock", "refund")) {
            act(finance, action, other, "x").andExpect(status().isForbidden());
        }
        search(finance, s.eventId(), ticketNo(other)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(other.toString()));
        assertThat(ticketStatus(other)).isEqualTo("VALID");
    }

    @Test
    void anotherOrganizersTicketEventAndRefundAre404() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 2);
        String refundId = JsonPath.read(body(act(s.org(), "refund", b.tickets().get(1), "x")), "$.refund.id");
        Caller orgB = admin();
        for (String action : List.of("block", "unblock", "refund")) {
            act(orgB, action, b.tickets().getFirst(), "x").andExpect(status().isNotFound());
        }
        act(orgB, "block", UUID.randomUUID(), "x").andExpect(status().isNotFound());
        search(orgB, s.eventId(), ticketNo(b.tickets().getFirst())).andExpect(status().isNotFound());
        call(orgB, post("/org/refunds/" + refundId + "/done"), "{\"reference\": \"R\"}").andExpect(status().isNotFound());
        call(orgB, get("/org/refunds")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        assertThat(ticketStatus(b.tickets().getFirst())).isEqualTo("VALID");
    }

    // ---------- lookup ----------

    @Test
    void lookupByTicketNumberCapsAtFiftyByTicketNo() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        // 60 tickets of the event ending 4d5c6b7a (inserted: an order can't hold 60)
        jdbc.sql("""
                insert into tickets (id, organizer_id, order_id, event_id, zone_id, price_category_id, holder_name)
                select (lpad(to_hex(g), 8, '0') || '-0000-4000-8000-00004d5c6b7a')::uuid, organizer_id, order_id,
                       event_id, zone_id, price_category_id, 'Samir Benali'
                from tickets, generate_series(1, 60) g where id = ?""").param(b.tickets().getFirst()).update();
        // another event's ticket with the same ending is not listed
        Sale other = onSale(10);
        UUID foreign = buy(other, 1).tickets().getFirst();
        jdbc.sql("""
                insert into tickets (id, organizer_id, order_id, event_id, zone_id, price_category_id)
                select 'ffffffff-0000-4000-8000-00004d5c6b7a'::uuid, organizer_id, order_id, event_id, zone_id,
                       price_category_id from tickets where id = ?""").param(foreign).update();

        String json = body(search(s.org(), s.eventId(), "4D5C 6B7A").andExpect(status().isOk()));
        List<String> ids = JsonPath.read(json, "$[*].id");
        assertThat(ids).hasSize(50).isSorted().allMatch(id -> id.endsWith("4d5c6b7a"))
                .doesNotContain("ffffffff-0000-4000-8000-00004d5c6b7a");
        assertKeys(json, "$[0]", ROW_KEYS);
        assertThat(JsonPath.<String>read(json, "$[0].ticket_no")).isEqualTo("4D5C 6B7A");
        assertThat(JsonPath.<String>read(json, "$[0].holder_name")).isEqualTo("Samir Benali");
        assertThat(JsonPath.<String>read(json, "$[0].holder_label")).isEqualTo("Samir B.");
        assertThat(JsonPath.<String>read(json, "$[0].channel")).isEqualTo("APP");
        assertThat(JsonPath.<String>read(json, "$[0].payment_method")).isEqualTo("CARD");
        assertThat(JsonPath.<String>read(json, "$[0].order_id")).isEqualTo(b.orderId());
        // any spacing and case
        assertThat(JsonPath.<List<Object>>read(body(search(s.org(), s.eventId(), " 4d5c6b7a ")), "$")).hasSize(50);
        assertThat(JsonPath.<List<Object>>read(body(search(s.org(), s.eventId(), "4 d 5 c 6 b 7 a")), "$")).hasSize(50);
        // the bought ticket by its own number
        search(s.org(), s.eventId(), ticketNo(b.tickets().getFirst())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(b.tickets().getFirst().toString()));
    }

    @Test
    void lookupByHolderPhoneAndByCashCode() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 2);
        buy(s, 1); // someone else's
        String json = body(search(s.org(), s.eventId(), b.phone()).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[*].id"))
                .containsExactlyElementsOf(b.tickets().stream().map(UUID::toString).sorted(
                        (x, y) -> x.substring(28).compareTo(y.substring(28))).toList());
        assertThat(JsonPath.<List<Object>>read(body(search(s.org(), s.eventId(), "+212699999999")), "$")).isEmpty();

        // a cash code collected at a till: the tickets of its order
        jdbc.sql("update organizers set settings = settings || '{\"cash_points\": [{\"name\": \"Guichet\"}]}'::jsonb where id = ?")
                .param(s.org().org()).update();
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 2);
        String code = JsonPath.read(body(fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk())), "$.pay_code");
        Caller cashier = caller(s.org().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        call(cashier, post("/org/pos/codes/" + code + "/collect"), "{\"amount_mad\": 10000}").andExpect(status().isOk());
        json = body(search(s.org(), s.eventId(), CashCode.display(code).toLowerCase()).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(json, "$")).hasSize(2);
        assertThat(JsonPath.<List<String>>read(json, "$[*].order_id")).containsOnly(orderId);
        assertThat(JsonPath.<List<String>>read(json, "$[*].payment_method")).containsOnly("CASH_AGENCY");

        // neither a ticket number, a phone nor a code → 422 on q
        for (String bad : List.of("", "Yazid", "4D5C 6B7", "+2126", "JMH 4F7K 2")) {
            search(s.org(), s.eventId(), bad).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.fields.q").exists());
        }
        call(s.org(), post("/org/events/" + s.eventId() + "/tickets/search"), "{}").andExpect(status().isUnprocessableContent());
    }
}
