package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.gate.application.Manifests;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.tickets.domain.TicketToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lot 3 exit proof, backend side (plan lot-3 §1 and §5 BE-26): a cash code collected at a club till, another paid on
 * the simulated network (callback sent twice), a walk-up sale of two printed slips that verify with K_e and reach the
 * next manifest, a wallet payment, a block (BLOCKED in the manifest) and a refund done by FINANCE, an expired code,
 * then the till closed 10 DH short: the cash report shows the -10,00 DH gap and /sales the figures by method.
 */
@TestPropertySource(properties = {"payments.cash.networks=fake", "payments.wallet.provider=fake"})
class Lot3PaymentsFlowTest extends PaymentTest {

    @Autowired
    ManifestService manifestService;

    @Autowired
    Manifests manifests;

    @Autowired
    EventService events;

    @Autowired
    OrderService orderService;

    @Test
    void cashAtTheTillAndOnTheNetworkWalkUpWalletBlockRefundExpiryAndTheGap() throws Exception {
        // Organizer: one zone, 100 places at 50 DH, published, with a cash point.
        Sale s = onSale(100);
        Caller org = s.org();
        jdbc.sql("update organizers set settings = settings || jsonb_build_object('cash_points', ?::jsonb) where id = ?")
                .params("[{\"name\": \"Guichet Stade\", \"address\": \"Porte 1\", \"hours\": \"10:00-19:00\"}]", org.org())
                .update();

        // (1) Fan A chooses cash: AWAITING_CASH with a code. A cashier opens a till, looks the code up in lowercase
        // and collects it: PAID, a ticket, its WhatsApp delivery row.
        String fanA = fanToken(randomPhone());
        String orderA = order(fanA, s, 1);
        String codeA = cash(fanA, orderA);
        assertThat(orderStatus(orderA)).isEqualTo("AWAITING_CASH");
        Caller cashier = caller(org.org(), "CASHIER");
        String session = JsonPath.read(body(call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet Nord\"}")
                .andExpect(status().isCreated())), "$.id");
        String typed = "jmh " + codeA.toLowerCase();
        String lookup = body(call(cashier, get("/org/pos/codes/" + typed)).andExpect(status().isOk()));
        assertThat(JsonPath.<Boolean>read(lookup, "$.payable")).isTrue();
        assertThat(JsonPath.<Integer>read(lookup, "$.amount_mad")).isEqualTo(5000);
        String collected = body(call(cashier, post("/org/pos/codes/" + typed + "/collect"), "{\"amount_mad\": 5000}")
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(collected, "$.status")).isEqualTo("PAID");
        assertThat(orderStatus(orderA)).isEqualTo("PAID");
        assertThat(orderMethod(orderA)).isEqualTo("CASH_AGENCY");
        List<String> ticketsA = JsonPath.read(collected, "$.ticket_ids");
        assertThat(ticketsA).hasSize(1);
        assertThat(deliveries(orderA)).isEqualTo(1);

        // (3) Fan B's code is paid on the simulated network; the signed callback is replayed with no second effect.
        String fanB = fanToken(randomPhone());
        String orderB = order(fanB, s, 1);
        String codeB = cash(fanB, orderB);
        Map<String, String> confirmation = CashCallbackTest.signed(codeB, 5000, "NET-" + UUID.randomUUID());
        assertThat(body(cashCallback(confirmation).andExpect(status().isOk()))).contains("Encaissement enregistré");
        assertThat(body(cashCallback(confirmation).andExpect(status().isOk()))).contains("déjà enregistré");
        assertThat(orderStatus(orderB)).isEqualTo("PAID");
        assertThat(tickets(orderB)).isEqualTo(1);
        assertThat(deliveries(orderB)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from payments where order_id = ?::uuid and status = 'SUCCEEDED' and provider = 'FAKE_CASH'")
                .param(orderB).query(Integer.class).single()).isEqualTo(1);
        assertThat(sold(s.priceId())).isEqualTo(2);
        int v1 = manifestService.build(org.org(), s.eventId()).orElseThrow();

        // (2) Walk-up sale of 2 at the till, one holder without a phone: PAID, paper only for that one. The print
        // data's two qr verify with K_e, and the next manifest version carries both tickets.
        String friendPhone = randomPhone();
        String sale = body(call(cashier, post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d, "items": [{"price_category_id": "%s", "qty": 2,
                 "holders": [{"name": "Salma Bennani", "phone": "%s"}, {"name": "Karim Tazi"}]}]}
                """.formatted(UUID.randomUUID(), s.eventId(), s.priceId(), friendPhone)).andExpect(status().isCreated()));
        String walkUp = JsonPath.read(sale, "$.id");
        assertThat(JsonPath.<String>read(sale, "$.status")).isEqualTo("PAID");
        assertThat(JsonPath.<String>read(sale, "$.channel")).isEqualTo("POS");
        assertThat(jdbc.sql("select count(*) from tickets where order_id = ?::uuid and holder_user_id is null")
                .param(walkUp).query(Integer.class).single()).isEqualTo(1);
        assertThat(deliveries(walkUp)).isEqualTo(1);
        String print = body(call(cashier, get("/org/pos/orders/" + walkUp + "/tickets")).andExpect(status().isOk()));
        List<String> walkUpTickets = JsonPath.read(print, "$.tickets[*].id");
        List<String> qrs = JsonPath.read(print, "$.tickets[*].qr");
        assertThat(qrs).hasSize(2).doesNotContainNull();
        for (int i = 0; i < 2; i++) {
            TicketToken.Parsed parsed = TicketToken.decode(qrs.get(i));
            assertThat(parsed.ticketId()).isEqualTo(UUID.fromString(walkUpTickets.get(i)));
            Optional<TicketToken.Result> verdict = events.withEventKey(org.org(), s.eventId(),
                    k -> TicketToken.verify(parsed, s.eventId(), k, TicketToken.slotAt(Instant.now())));
            assertThat(verdict).isEmpty();
        }
        int v2 = rebuild(s);
        assertThat(v2).isEqualTo(v1 + 1);
        assertThat(tLines(s, v1)).hasSize(2).noneMatch(l -> walkUpTickets.stream().anyMatch(l::contains));
        for (String t : walkUpTickets) {
            assertThat(tLines(s, v2)).anyMatch(l -> l.startsWith("T\t" + t + "\t" + s.zoneId() + "\tVALID\t"));
        }

        // (4) Fan C pays by wallet on the simulated hosted page.
        String fanC = fanToken(randomPhone());
        String orderC = order(fanC, s, 1);
        String walletPay = body(fan(fanC, post("/fan/orders/" + orderC + "/pay"), "{\"method\": \"WALLET\"}")
                .andExpect(status().isOk()));
        callback(approve(JsonPath.read(walletPay, "$.payment_id"), 5000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderC)).isEqualTo("PAID");
        assertThat(orderMethod(orderC)).isEqualTo("WALLET");
        String ticketC = ticketOf(orderC);

        // (5) ADMIN blocks A's ticket (BLOCKED in the next manifest) and refunds C's: the place returns to sale, a
        // REQUIRED refund, which FINANCE marks DONE with a reference.
        call(org, post("/org/tickets/" + ticketsA.getFirst() + "/block"), "{\"reason\": \"Fraude signalée\"}")
                .andExpect(status().isOk());
        int soldBefore = sold(s.priceId());
        call(org, post("/org/tickets/" + ticketC + "/refund"), "{\"reason\": \"Empêchement\"}").andExpect(status().isOk());
        assertThat(sold(s.priceId())).isEqualTo(soldBefore - 1);
        String refundId = jdbc.sql("select id::text from refunds where ticket_id = ?::uuid").param(ticketC)
                .query(String.class).single();
        assertThat(refundStatus(refundId)).isEqualTo("REQUIRED");
        String done = body(call(caller(org.org(), "FINANCE"), post("/org/refunds/" + refundId + "/done"),
                "{\"reference\": \"WALLET-RB-001\"}").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(done, "$.status")).isEqualTo("DONE");
        assertThat(JsonPath.<String>read(done, "$.method")).isEqualTo("WALLET");
        assertThat(refundStatus(refundId)).isEqualTo("DONE");
        int v3 = rebuild(s);
        assertThat(tLines(s, v3)).anyMatch(l -> l.startsWith("T\t" + ticketsA.getFirst() + "\t" + s.zoneId() + "\tBLOCKED\t"))
                .anyMatch(l -> l.startsWith("T\t" + ticketC + "\t" + s.zoneId() + "\tREFUNDED\t"));

        // Fan D's code expires: EXPIRED, the place back.
        String fanD = fanToken(randomPhone());
        String orderD = order(fanD, s, 1);
        cash(fanD, orderD);
        int soldWithD = sold(s.priceId());
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderD).update();
        orderService.expireDue();
        assertThat(orderStatus(orderD)).isEqualTo("EXPIRED");
        assertThat(sold(s.priceId())).isEqualTo(soldWithD - 1);

        // (6) The cashier closes the till declaring 10 DH less than expected (A's code 50 DH + the walk-up 100 DH).
        String closed = body(call(cashier, post("/org/pos/sessions/" + session + "/close"), "{\"declared_mad\": 14000}")
                .andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(closed, "$.expected_mad")).isEqualTo(15000);
        assertThat(JsonPath.<Integer>read(closed, "$.variance_mad")).isEqualTo(-1000);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String report = body(call(caller(org.org(), "FINANCE"), get("/org/finance/cash?from=" + today.minusDays(1)
                + "&to=" + today.plusDays(1) + "&timezone=Africa/Casablanca")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(report, "$.sessions[*].id")).containsExactly(session);
        assertThat(JsonPath.<Integer>read(report, "$.sessions[0].expected_mad")).isEqualTo(15000);
        assertThat(JsonPath.<Integer>read(report, "$.sessions[0].declared_mad")).isEqualTo(14000);
        assertThat(JsonPath.<Integer>read(report, "$.sessions[0].variance_mad")).isEqualTo(-1000);
        assertThat(JsonPath.<Map<String, Object>>read(report, "$.totals")).isEqualTo(Map.of("collected_mad", 20000,
                "declared_mad", 14000, "variance_mad", -1000, "open_sessions", 0));
        assertThat(JsonPath.<List<String>>read(report, "$.days[*].collector_name"))
                .containsExactlyInAnyOrder("Réseau cash simulé", "Guichet", "Guichet");
        String csv = call(caller(org.org(), "FINANCE"), get("/org/finance/cash.csv?from=" + today.minusDays(1)
                + "&to=" + today.plusDays(1) + "&timezone=Africa/Casablanca")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> lines = csv.substring(1).lines().toList(); // after the BOM
        assertThat(lines.getFirst())
                .isEqualTo("kind;jour;encaisseur;moyen;paiements;montant;caisse;caissier;ouverture;cloture;attendu;declare;ecart");
        assertThat(lines).anyMatch(l -> l.matches("jour;\\d{4}-\\d{2}-\\d{2};Guichet;POS_CASH;1;100,00;;;;;;;"))
                .anyMatch(l -> l.startsWith("caisse;;;;;;Guichet Nord;") && l.endsWith(";150,00;140,00;-10,00"));

        // B3: nothing awaiting cash; paid places by method (gross, C's refunded place included); 1 refunded.
        String sales = body(call(org, get("/org/events/" + s.eventId() + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(sales, "$.awaiting_cash")).isZero();
        assertThat(JsonPath.<Integer>read(sales, "$.held")).isZero();
        assertThat(JsonPath.<Integer>read(sales, "$.paid")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(sales, "$.revenue_mad")).isEqualTo(25000);
        assertThat(JsonPath.<Integer>read(sales, "$.refunded")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(sales, "$.refunded_mad")).isEqualTo(5000);
        assertThat(JsonPath.<List<Map<String, Object>>>read(sales, "$.methods")).containsExactly(
                Map.of("method", "CARD", "paid", 0, "revenue_mad", 0),
                Map.of("method", "CASH_AGENCY", "paid", 2, "revenue_mad", 10000),
                Map.of("method", "WALLET", "paid", 1, "revenue_mad", 5000),
                Map.of("method", "POS_CASH", "paid", 2, "revenue_mad", 10000),
                Map.of("method", "FREE", "paid", 0, "revenue_mad", 0));
        // In orders: CASH_AGENCY 2 (A at the till, B on the network), POS_CASH 1 (the walk-up), WALLET 1.
        assertThat(jdbc.sql("""
                select payment_method::text || ' ' || count(*) from orders where event_id = ? and status = 'PAID'
                group by payment_method order by payment_method""").param(s.eventId()).query(String.class).list())
                .containsExactly("CASH_AGENCY 2", "WALLET 1", "POS_CASH 1");
    }

    // ---------- helpers ----------

    /** /pay CASH_AGENCY → the code (6 symbols). */
    private String cash(String fan, String orderId) throws Exception {
        return JsonPath.read(body(fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk())), "$.pay_code");
    }

    private ResultActions cashCallback(Map<String, String> fields) throws Exception {
        var req = post("/payments/cash/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        fields.forEach(req::param);
        return mvc.perform(req);
    }

    /** Ages the latest version past the 5 min rebuild gap, then builds the next one. */
    private int rebuild(Sale s) {
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(s.eventId())
                .update();
        return manifestService.build(s.org().org(), s.eventId()).orElseThrow();
    }

    private List<String> tLines(Sale s, int version) {
        return manifests.find(s.org().org(), s.eventId(), version).orElseThrow().payload().lines()
                .filter(l -> l.startsWith("T\t")).toList();
    }

    private String orderMethod(String orderId) {
        return jdbc.sql("select payment_method::text from orders where id = ?::uuid").param(orderId).query(String.class)
                .single();
    }

    private String ticketOf(String orderId) {
        return jdbc.sql("select id::text from tickets where order_id = ?::uuid").param(orderId).query(String.class).single();
    }

    private String refundStatus(String refundId) {
        return jdbc.sql("select status::text from refunds where id = ?::uuid").param(refundId).query(String.class).single();
    }

    private int deliveries(String orderId) {
        return jdbc.sql("""
                select count(*) from ticket_deliveries d join tickets t on t.id = d.ticket_id where t.order_id = ?::uuid""")
                .param(orderId).query(Integer.class).single();
    }
}
