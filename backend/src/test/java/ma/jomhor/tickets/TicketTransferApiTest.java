package ma.jomhor.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-28): docs/04-api.md §Fan POST /fan/tickets/{id}/transfer and /reissue, the Lot 4 fields of GET
 * /fan/tickets (transfer_until, transferred) and of POST /org/events/{id}/tickets/search (transferred_to,
 * transferred_from); plan lot-4 Q59.
 */
class TicketTransferApiTest extends PaymentTest {

    private static final String KARIM = "Karim Benali";

    @Autowired
    ManifestService manifests;

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

    private ResultActions transfer(String token, UUID ticket, String toPhone, String name) throws Exception {
        return fan(token, post("/fan/tickets/" + ticket + "/transfer"),
                "{\"to_phone\": \"%s\", \"name\": \"%s\"}".formatted(toPhone, name));
    }

    private ResultActions reissue(String token, UUID ticket) throws Exception {
        return fan(token, post("/fan/tickets/" + ticket + "/reissue"));
    }

    private String held(String token) throws Exception {
        return body(fan(token, get("/fan/tickets?with_keys=true")).andExpect(status().isOk()));
    }

    private UUID newTicket(UUID old) {
        return jdbc.sql("select new_ticket_id from ticket_transfers where ticket_id = ?").param(old).query(UUID.class)
                .single();
    }

    private String ticketStatus(UUID id) {
        return jdbc.sql("select status::text from tickets where id = ?").param(id).query(String.class).single();
    }

    private int deliveries(UUID ticket) {
        return jdbc.sql("select count(*) from ticket_deliveries where ticket_id = ?").param(ticket).query(Integer.class)
                .single();
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

    private static void conflict(ResultActions r, String code) throws Exception {
        r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }

    private long paid(Sale s) throws Exception {
        return JsonPath.<Integer>read(body(call(s.org(), get("/org/events/" + s.eventId() + "/sales"))
                .andExpect(status().isOk())), "$.paid");
    }

    private long liveSold(Sale s) throws Exception {
        return JsonPath.<Integer>read(body(call(s.org(), get("/org/events/" + s.eventId() + "/live"))
                .andExpect(status().isOk())), "$.sold");
    }

    // ---------- transfer ----------

    @Test
    void transferCancelsTheOldTicketAndIssuesANewOneToTheRecipient() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 2);
        UUID old = b.tickets().getFirst();
        String oldKey = JsonPath.<List<String>>read(held(b.token()), "$.tickets[?(@.id == '" + old + "')].k_t")
                .getFirst();
        assertThat(manifests.build(s.org().org(), s.eventId())).contains(1);
        int soldBefore = sold(s.priceId());
        long paidBefore = paid(s);
        long liveBefore = liveSold(s);
        String toPhone = randomPhone(); // unknown: the account is created
        assertThat(jdbc.sql("select count(*) from users where phone_e164 = ?").param(toPhone).query(Integer.class)
                .single()).isZero();

        String json = body(transfer(b.token(), old, toPhone, "  karim   Benali ").andExpect(status().isCreated()));
        assertKeys(json, "$", "transfer_id", "to_label", "transferred_at");
        assertThat(JsonPath.<String>read(json, "$.to_label")).isEqualTo("karim B.");
        UUID fresh = newTicket(old);
        assertThat(fresh).isNotEqualTo(old);

        // old CANCELLED (status only), new VALID for the recipient: same order, event, zone and category
        UUID recipient = jdbc.sql("select id from users where phone_e164 = ?").param(toPhone).query(UUID.class).single();
        Map<String, Object> o = jdbc.sql("select * from tickets where id = ?").param(old).query().singleRow();
        Map<String, Object> n = jdbc.sql("select * from tickets where id = ?").param(fresh).query().singleRow();
        assertThat(o.get("status").toString()).isEqualTo("CANCELLED");
        assertThat(o.get("holder_name")).isEqualTo("Yazid Aqel");
        assertThat(n.get("status").toString()).isEqualTo("VALID");
        assertThat(n.get("holder_user_id")).isEqualTo(recipient);
        assertThat(n.get("holder_name")).isEqualTo("karim Benali");
        assertThat(n.get("holder_initials")).isEqualTo("K. B.");
        for (String column : List.of("organizer_id", "order_id", "event_id", "zone_id", "price_category_id")) {
            assertThat(n.get(column)).as(column).isEqualTo(o.get(column));
        }
        Map<String, Object> tr = jdbc.sql("select * from ticket_transfers where ticket_id = ?").param(old).query()
                .singleRow();
        assertThat(tr.get("id").toString()).isEqualTo(JsonPath.read(json, "$.transfer_id"));
        assertThat(tr.get("organizer_id")).isEqualTo(s.org().org());
        assertThat(tr.get("to_user_id")).isEqualTo(recipient);
        assertThat(tr.get("from_user_id")).isEqualTo(jdbc.sql("select id from users where phone_e164 = ?")
                .param(b.phone()).query(UUID.class).single());
        assertThat(jdbc.sql("select payload::text from audit_log where action = 'ticket.transfer' and target = ?")
                .param(old.toString()).query(String.class).single()).contains(fresh.toString()).doesNotContain("Karim")
                .doesNotContain(toPhone);

        // one WhatsApp delivery, to the recipient's phone
        assertThat(deliveries(fresh)).isEqualTo(1);

        // the next manifest version carries both rows
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(s.eventId())
                .update();
        assertThat(manifests.build(s.org().org(), s.eventId())).contains(2);
        String manifest = latestManifest(s.eventId());
        assertThat(manifest).contains("T\t" + old + "\t" + s.zoneId() + "\tCANCELLED\t")
                .contains("T\t" + fresh + "\t" + s.zoneId() + "\tVALID\tK. B.");

        // sender: the old ticket CANCELLED with transferred.to_label, no k_t; the other ticket untouched
        String sender = held(b.token());
        assertThat(JsonPath.<List<String>>read(sender, "$.tickets[*].id")).containsExactly(old.toString(),
                b.tickets().get(1).toString());
        assertKeys(sender, "$.tickets[0].transferred", "to_label", "at");
        assertThat(JsonPath.<String>read(sender, "$.tickets[0].status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<String>read(sender, "$.tickets[0].transferred.to_label")).isEqualTo("karim B.");
        assertThat(JsonPath.<Map<String, Object>>read(sender, "$.tickets[0]")).doesNotContainKey("k_t");
        assertThat(JsonPath.<Object>read(sender, "$.tickets[1].transferred")).isNull();

        // recipient lists it with a new k_t
        String rec = held(fanToken(toPhone));
        assertThat(JsonPath.<List<String>>read(rec, "$.tickets[*].id")).containsExactly(fresh.toString());
        assertThat(JsonPath.<String>read(rec, "$.tickets[0].holder_label")).isEqualTo("karim B.");
        assertThat(JsonPath.<Object>read(rec, "$.tickets[0].transferred")).isNull();
        assertThat(JsonPath.<String>read(rec, "$.tickets[0].k_t")).isNotBlank().isNotEqualTo(oldKey);

        // the buyer's order never lists the new ticket, and its PDF is not theirs
        String ord = body(fan(b.token(), get("/fan/orders/" + b.orderId())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(ord, "$.ticket_ids")).doesNotContain(fresh.toString());
        fan(b.token(), get("/fan/tickets/" + fresh + "/pdf")).andExpect(status().isNotFound());

        // sold, /sales and /live sold unchanged
        assertThat(sold(s.priceId())).isEqualTo(soldBefore);
        assertThat(paid(s)).isEqualTo(paidBefore);
        assertThat(liveSold(s)).isEqualTo(liveBefore);

        // staff lookup: both ticket numbers, linked
        String byOld = body(call(s.org(), post("/org/events/" + s.eventId() + "/tickets/search"),
                "{\"q\": \"%s\"}".formatted(ticketNo(old))).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(byOld, "$[0].transferred_to")).isEqualTo(ticketNo(fresh));
        assertThat(JsonPath.<Object>read(byOld, "$[0].transferred_from")).isNull();
        String byNew = body(call(s.org(), post("/org/events/" + s.eventId() + "/tickets/search"),
                "{\"q\": \"%s\"}".formatted(ticketNo(fresh))).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(byNew, "$[0].transferred_from")).isEqualTo(ticketNo(old));
        assertThat(JsonPath.<Object>read(byNew, "$[0].transferred_to")).isNull();

        // the old ticket can't move again; the recipient can pass it on (a chain)
        conflict(transfer(b.token(), old, randomPhone(), KARIM), "TICKET_NOT_VALID");
        transfer(fanToken(toPhone), fresh, randomPhone(), "Sara Idrissi").andExpect(status().isCreated());
        byNew = body(call(s.org(), post("/org/events/" + s.eventId() + "/tickets/search"),
                "{\"q\": \"%s\"}".formatted(ticketNo(fresh))).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(byNew, "$[0].transferred_from")).isEqualTo(ticketNo(old));
        assertThat(JsonPath.<String>read(byNew, "$[0].transferred_to")).isEqualTo(ticketNo(newTicket(fresh)));
    }

    @Test
    void transferUntilIsTheCutoffMinus30MinutesAndClosesTransfers() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        UUID t = b.tickets().getFirst();
        Instant cutoff = jdbc.sql("select refund_cutoff_at from events where id = ?").param(s.eventId())
                .query(java.time.OffsetDateTime.class).single().toInstant();
        String json = held(b.token());
        assertThat(Instant.parse(JsonPath.read(json, "$.tickets[0].transfer_until")))
                .isEqualTo(cutoff.minus(Duration.ofMinutes(30)));

        // at transfer_until (database clock): closed, for a re-issue too
        jdbc.sql("update events set refund_cutoff_at = now() + interval '30 minutes' where id = ?").param(s.eventId())
                .update();
        transfer(b.token(), t, randomPhone(), KARIM).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_CLOSED"))
                .andExpect(jsonPath("$.details.transfer_until").exists());
        conflict(reissue(b.token(), t), "TRANSFER_CLOSED");
        assertThat(ticketStatus(t)).isEqualTo("VALID");

        // no cutoff = closed, transfer_until null
        jdbc.sql("update events set refund_cutoff_at = null where id = ?").param(s.eventId()).update();
        conflict(transfer(b.token(), t, randomPhone(), KARIM), "TRANSFER_CLOSED");
        assertThat(JsonPath.<Object>read(held(b.token()), "$.tickets[0].transfer_until")).isNull();
        assertThat(jdbc.sql("select count(*) from ticket_transfers where ticket_id = ?").param(t).query(Integer.class)
                .single()).isZero();
    }

    @Test
    void ownPhoneBadPhoneAndBadNameAre422() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        UUID t = b.tickets().getFirst();
        transfer(b.token(), t, b.phone(), KARIM).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reason").value("SELF"))
                .andExpect(jsonPath("$.details.fields.to_phone").exists());
        transfer(b.token(), t, "0612345678", KARIM).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.to_phone").exists());
        for (String name : List.of("K", "Karim 2", "Karim عقل", "")) {
            transfer(b.token(), t, randomPhone(), name).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.fields.name").exists());
        }
        // an Arabic name is a name
        String json = body(transfer(b.token(), t, randomPhone(), "يزيد عقل").andExpect(status().isCreated()));
        assertThat(JsonPath.<String>read(json, "$.to_label")).isEqualTo("يزيد ع.");
    }

    @Test
    void blockedIs409AndAnotherHoldersTicketIs404() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        Bought other = buy(s, 1);
        UUID t = b.tickets().getFirst();
        call(s.org(), post("/org/tickets/" + t + "/block"), "{\"reason\": \"Fraude\"}").andExpect(status().isOk());
        conflict(transfer(b.token(), t, randomPhone(), KARIM), "TICKET_NOT_VALID");
        conflict(reissue(b.token(), t), "TICKET_NOT_VALID");

        UUID theirs = other.tickets().getFirst();
        transfer(b.token(), theirs, randomPhone(), KARIM).andExpect(status().isNotFound());
        reissue(b.token(), theirs).andExpect(status().isNotFound());
        transfer(b.token(), UUID.randomUUID(), randomPhone(), KARIM).andExpect(status().isNotFound());
        assertThat(ticketStatus(theirs)).isEqualTo("VALID");
        assertThat(jdbc.sql("select count(*) from ticket_transfers where ticket_id in (?, ?)").params(t, theirs)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void twoParallelTransfersOfOneTicketMakeOne() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        UUID t = b.tickets().getFirst();
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> codes = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String to = randomPhone();
                runs.add(pool.submit(() -> {
                    start.await();
                    return transfer(b.token(), t, to, KARIM).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> f : runs) {
                codes.add(f.get(30, TimeUnit.SECONDS));
            }
        }
        assertThat(codes).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.sql("select count(*) from tickets where order_id = ?::uuid").param(b.orderId())
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from ticket_transfers where ticket_id = ?").param(t).query(Integer.class)
                .single()).isEqualTo(1);
    }

    /** An admitted (OK/OVERRIDE) or refused scan of {@code ticket} on the server, as a device sync would store it. */
    private void scan(Sale s, UUID ticket, String result) {
        UUID org = s.org().org();
        UUID device = jdbc.sql("insert into devices (organizer_id, label, public_key) values (?, 'PDA', ?) returning id")
                .params(org, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)).query(UUID.class).single();
        UUID gate = jdbc.sql("""
                        insert into gates (organizer_id, venue_id, code, name)
                        select organizer_id, venue_id, ?, 'Porte' from events where id = ? returning id""")
                .params("G" + UUID.randomUUID().toString().substring(0, 6), s.eventId()).query(UUID.class).single();
        UUID staff = jdbc.sql("insert into staff (organizer_id, name, role, active) values (?, 'Gate', 'SUPERVISOR', true) "
                        + "returning id").param(org).query(UUID.class).single();
        boolean override = result.equals("OVERRIDE");
        jdbc.sql("""
                        insert into scans (scan_id, organizer_id, event_id, ticket_id, device_id, gate_id, staff_id, result,
                                           device_time, override_reason, supervisor_id)
                        values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?::scan_result, now(), ?::override_reason, ?)
                        """)
                .params(org, s.eventId(), ticket, device, gate, staff, result, override ? "ID_CHECKED" : null,
                        override ? staff : null)
                .update();
    }

    @Test
    void anAdmittedTicketCantBeTransferredOrReissued() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 3);
        UUID ok = b.tickets().get(0);
        UUID overridden = b.tickets().get(1);
        UUID refused = b.tickets().get(2);
        scan(s, ok, "OK");
        scan(s, overridden, "OVERRIDE");
        scan(s, refused, "DUPLICATE"); // a refusal is not an entry
        for (UUID t : List.of(ok, overridden)) {
            for (ResultActions r : List.of(transfer(b.token(), t, randomPhone(), KARIM), reissue(b.token(), t))) {
                r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_NOT_VALID"))
                        .andExpect(jsonPath("$.details.reason").value("ADMITTED"));
            }
            assertThat(ticketStatus(t)).isEqualTo("VALID");
        }
        assertThat(jdbc.sql("select count(*) from ticket_transfers where ticket_id in (?, ?)").params(ok, overridden)
                .query(Integer.class).single()).isZero();
        transfer(b.token(), refused, randomPhone(), KARIM).andExpect(status().isCreated());
    }

    @Test
    void aPosTicketTransferKeepsTheTillFigures() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet Nord\"}").andExpect(status().isCreated());
        String phone = randomPhone();
        String order = JsonPath.read(body(call(cashier, post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d, "items": [{"price_category_id": "%s", "qty": 1,
                 "holders": [{"name": "Salma Idrissi", "phone": "%s"}]}]}
                """.formatted(UUID.randomUUID(), s.eventId(), s.priceId(), phone)).andExpect(status().isCreated())),
                "$.id");
        UUID old = jdbc.sql("select id from tickets where order_id = ?::uuid").param(order).query(UUID.class).single();
        int tickets = JsonPath.read(body(call(cashier, get("/org/pos/session")).andExpect(status().isOk())),
                "$.session.tickets");

        transfer(fanToken(phone), old, randomPhone(), KARIM).andExpect(status().isCreated());

        assertThat(JsonPath.<Integer>read(body(call(cashier, get("/org/pos/session")).andExpect(status().isOk())),
                "$.session.tickets")).isEqualTo(tickets);
        String slips = body(call(cashier, get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(slips, "$.tickets[*].id")).containsExactly(old.toString());
        assertThat(JsonPath.<String>read(slips, "$.tickets[0].status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<Object>read(slips, "$.tickets[0].qr")).isNull();
    }

    @Test
    void aStaffRefundOfAReplacementRefundsTheItemPrice() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        UUID old = b.tickets().getFirst();
        transfer(b.token(), old, randomPhone(), KARIM).andExpect(status().isCreated());
        UUID fresh = newTicket(old);
        int soldBefore = sold(s.priceId());

        String json = body(call(s.org(), post("/org/tickets/" + fresh + "/refund"), "{\"reason\": \"Annulation\"}")
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("REFUNDED");
        assertThat(JsonPath.<Integer>read(json, "$.refund.amount_mad")).isEqualTo(5000);
        assertThat(JsonPath.<String>read(json, "$.transferred_from")).isEqualTo(ticketNo(old));
        assertThat(sold(s.priceId())).isEqualTo(soldBefore - 1);
        assertThat(ticketStatus(old)).isEqualTo("CANCELLED");
    }

    // ---------- re-issue ----------

    @Test
    void reissueGivesANewIdToTheSameHolderWithoutDelivery() throws Exception {
        Sale s = onSale(10);
        Bought b = buy(s, 1);
        UUID old = b.tickets().getFirst();
        String oldKey = JsonPath.read(held(b.token()), "$.tickets[0].k_t");
        int soldBefore = sold(s.priceId());

        String json = body(reissue(b.token(), old).andExpect(status().isCreated()));
        assertKeys(json, "$", "ticket_id");
        UUID fresh = UUID.fromString(JsonPath.read(json, "$.ticket_id"));
        assertThat(fresh).isNotEqualTo(old).isEqualTo(newTicket(old));
        assertThat(ticketStatus(old)).isEqualTo("CANCELLED");
        assertThat(deliveries(fresh)).isZero();
        Map<String, Object> tr = jdbc.sql("select * from ticket_transfers where ticket_id = ?").param(old).query()
                .singleRow();
        assertThat(tr.get("to_user_id")).isEqualTo(tr.get("from_user_id"));
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'ticket.reissue' and target = ?")
                .param(old.toString()).query(Integer.class).single()).isEqualTo(1);

        // the old row is not listed; the new one has the same holder and a new k_t
        String list = held(b.token());
        assertThat(JsonPath.<List<String>>read(list, "$.tickets[*].id")).containsExactly(fresh.toString());
        assertThat(JsonPath.<String>read(list, "$.tickets[0].holder_label")).isEqualTo("Yazid A.");
        assertThat(JsonPath.<String>read(list, "$.tickets[0].k_t")).isNotEqualTo(oldKey);
        assertThat(JsonPath.<Object>read(list, "$.tickets[0].transferred")).isNull();
        assertThat(jdbc.sql("select holder_initials from tickets where id = ?").param(fresh).query(String.class)
                .single()).isEqualTo("Y. A.");
        assertThat(sold(s.priceId())).isEqualTo(soldBefore);

        // the staff lookup links them
        String row = body(call(s.org(), post("/org/events/" + s.eventId() + "/tickets/search"),
                "{\"q\": \"%s\"}".formatted(b.phone())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(row, "$[*].transferred_to")).contains(ticketNo(fresh));
        assertThat(JsonPath.<List<String>>read(row, "$[*].transferred_from")).contains(ticketNo(old));
    }
}
