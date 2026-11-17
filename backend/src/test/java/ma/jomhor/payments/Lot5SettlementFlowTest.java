package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.finance.Ledger;
import ma.jomhor.finance.application.SettlementService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lot 5 step 5b exit proof, backend side (plan lot-5 §13.1 Exit 5b (1)-(7), §13.5 BE-72). ATLAS FC's finance settings
 * (500 bp, from Mon 2 nov. 2026) are set by ops. Real payments through the API: card on two matches, a pass installment
 * by card, a membership by wallet, a code paid on the simulated network, a till sale and a code collected at the till,
 * an invitation (FREE, no payment); then a till-only Friday and a card payment on Saturday. Each payment's completed_at
 * is then moved to its demo day (the flows run today; the job reads only completed_at, and statements on a fixed clock
 * are what the demo shows).
 * <ul>
 *   <li>Mon 2 nov.: card 1 000 DH, due Wed 4 nov.: "En retard" whatever today is.</li>
 *   <li>Thu 26 nov.: online 4 000 DH, tills 1 000 DH, fees 250 + 50, "À verser 3 700 DH" due Mon 30 (Sat 28 →
 *   Monday); invoice JMH-2026-…, its PDF; lines per event and product × method × collector.</li>
 *   <li>Fri 27: tills 800 DH, fees 40 + 8, "Rien à verser"; the next statement opens with "Report −48 DH".</li>
 *   <li>Sat 28: card 250 DH, "Pas encore relevé" until Sun 01:00.</li>
 *   <li>FINANCE "Versement reçu" with "VIR 0042"; OPS gets 403; NONE is a conflict.</li>
 *   <li>An ADMIN refunds a card ticket: "Remboursements à faire", no issued statement changes.</li>
 *   <li>Invoice numbers with no gap, the CSV, and {@code loadtest/check.sql} clean for the club.</li>
 * </ul>
 */
@TestPropertySource(properties = {"payments.cash.networks=fake", "payments.wallet.provider=fake"})
class Lot5SettlementFlowTest extends PaymentTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    @Autowired
    SettlementService settlements;

    private static Instant local(String at) {
        return LocalDateTime.parse(at).atZone(CASABLANCA).toInstant();
    }

    private String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r.andExpect(status().isCreated())), "$.id");
    }

    private int match(Caller c, String venue, String title, String startsAt) throws Exception {
        return JsonPath.read(body(call(c, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "%s", "starts_at": "%s"}"""
                .formatted(venue, title, startsAt)).andExpect(status().isCreated())), "$.id");
    }

    private String category(Caller c, int event, int zone, String name, long priceMad, String audience)
            throws Exception {
        return id(call(c, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "%s", "price_mad": %d, "quota": 20, "audience": "%s"}"""
                .formatted(zone, name, priceMad, audience)));
    }

    private String fanOrder(String fan, int event, String price, int qty) throws Exception {
        return id(fan(fan, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}
                """.formatted(event, price, qty, holders(qty))));
    }

    private String productOrder(String fan, String product, String extra) throws Exception {
        return id(fan(fan, post("/fan/product-orders"), """
                {"product_id": "%s", "channel": "APP", "holder_name": "Nadia Berrada"%s}""".formatted(product, extra)));
    }

    /** Pays the order on the simulated hosted page ({@code method} CARD or WALLET) → PAID. */
    private void hosted(String fan, String order, String method) throws Exception {
        String paymentId = JsonPath.read(body(fan(fan, post("/fan/orders/" + order + "/pay"),
                "{\"method\": \"%s\"}".formatted(method)).andExpect(status().isOk())), "$.payment_id");
        callback(approve(paymentId, total(order))).andExpect(status().isSeeOther());
        assertThat(orderStatus(order)).isEqualTo("PAID");
    }

    private String code(String fan, String order) throws Exception {
        return JsonPath.read(body(fan(fan, post("/fan/orders/" + order + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk())), "$.pay_code");
    }

    private String tillSale(Caller cashier, int event, String price, int qty) throws Exception {
        String holders = String.join(", ", Collections.nCopies(qty, "{\"name\": \"Yazid Aqel\"}"));
        return id(call(cashier, post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d, "items": [{"price_category_id": "%s", "qty": %d, "holders": [%s]}]}
                """.formatted(UUID.randomUUID(), event, price, qty, holders)));
    }

    /** The order's SUCCEEDED payment completed at {@code at} (local, Africa/Casablanca). */
    private void on(String order, String at) {
        assertThat(jdbc.sql("update payments set completed_at = ? where order_id = ?::uuid and status = 'SUCCEEDED'")
                .params(OffsetDateTime.ofInstant(local(at), ZoneOffset.UTC), order).update()).isEqualTo(1);
    }

    private String list(Caller c) throws Exception {
        return body(call(c, get("/org/finance/settlements?from=2026-11-01&to=2026-11-30")).andExpect(status().isOk()));
    }

    private Map<String, Object> row(String list, String day) {
        List<Map<String, Object>> rows = JsonPath.read(list, "$.settlements[?(@.day == '" + day + "')]");
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private String invoiceNumber(UUID org, String day) {
        return jdbc.sql("""
                        select i.number from invoices i join settlements s on s.id = i.settlement_id
                        where s.organizer_id = ? and s.day = ?::date""")
                .params(org, day).query(String.class).single();
    }

    @Test
    void aWeekOfPaymentsIssuesStatementsInvoicesAndPayoutsWithNoBreach() throws Exception {
        // ---------- set-up: the club, its finance settings (ops, SQL), two matches, a pass and a membership ----------
        // The job serves every activated organizer: earlier classes' organizers are deactivated so this club's invoice
        // numbers follow one another (test classes don't run in parallel).
        jdbc.sql("update organizers set settings = settings - 'finance' where settings -> 'finance' is not null")
                .update();
        Caller admin = admin();
        UUID org = admin.org();
        Caller ops = caller(org, "OPS");
        Caller finance = caller(org, "FINANCE");
        Caller cashier = caller(org, "CASHIER");
        jdbc.sql("update organizers set settings = settings || '{\"cash_points\": [{\"name\": \"Guichet\"}]}'::jsonb "
                + "where id = ?").param(org).update();
        Ledger.activate(jdbc, org, 500, "2026-11-02");
        String stade = venue(admin, "Stade");
        int nord = zone(admin, stade, "NORD", 200);
        int matchA = match(ops, stade, "Match A", "2099-02-01T19:00:00Z");
        int matchB = match(ops, stade, "Match B", "2099-03-01T19:00:00Z");
        String pleinA = category(ops, matchA, nord, "Plein tarif", 1_000_00, "ALL");
        String tribuneA = category(ops, matchA, nord, "Tribune", 450_00, "ALL");
        String pelouseA = category(ops, matchA, nord, "Pelouse", 250_00, "ALL");
        String presseA = category(ops, matchA, nord, "Presse", 0, "INVITE");
        String pleinB = category(ops, matchB, nord, "Plein tarif", 1_000_00, "ALL");
        String pelouseB = category(ops, matchB, nord, "Pelouse", 250_00, "ALL");
        String virageB = category(ops, matchB, nord, "Virage", 400_00, "ALL");
        for (int m : List.of(matchA, matchB)) {
            call(ops, post("/org/events/" + m + "/publish")).andExpect(status().isOk());
        }
        String series = id(call(ops, post("/org/series"),
                "{\"name\": \"Saison 2026-27\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}"));
        String pass = id(call(ops, post("/org/products"), """
                {"kind": "PASS", "name": "Abonnement Tribune", "price_mad": 160000, "quota": 10, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}""".formatted(series, nord)));
        call(ops, patch("/org/products/" + pass), "{\"max_installments\": 4}").andExpect(status().isOk());
        String membership = id(call(ops, post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "Cotisation 2026", "price_mad": 15000, "valid_until": "2099-07-01T00:00:00Z",
                 "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}"""));

        // ---------- Mon 2 nov.: a card payment, due Wed 4 nov. ----------
        String early = fanToken(randomPhone());
        String earlyOrder = fanOrder(early, matchB, pleinB, 1);
        hosted(early, earlyOrder, "CARD");
        on(earlyOrder, "2026-11-02T15:00");

        // ---------- Thu 26 nov.: every kind of payment ----------
        String yazid = fanToken(randomPhone());
        String cardA = fanOrder(yazid, matchA, pleinA, 2); // 2 000 DH
        hosted(yazid, cardA, "CARD");
        on(cardA, "2026-11-26T10:00");
        String karim = fanToken(randomPhone());
        String cardB = fanOrder(karim, matchB, pleinB, 1); // 1 000 DH
        hosted(karim, cardB, "CARD");
        on(cardB, "2026-11-26T10:30");
        String nadia = fanToken(randomPhone());
        String installment = productOrder(nadia, pass, ", \"installments\": 4"); // seq 1 of 4: 400 DH
        assertThat(total(installment)).isEqualTo(400_00);
        hosted(nadia, installment, "CARD");
        on(installment, "2026-11-26T11:00");
        String cotisation = productOrder(nadia, membership, ""); // 150 DH by wallet
        hosted(nadia, cotisation, "WALLET");
        on(cotisation, "2026-11-26T12:00");
        String amine = fanToken(randomPhone());
        String network = fanOrder(amine, matchA, tribuneA, 1); // 450 DH paid on the simulated network
        var callback = post("/payments/cash/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        CashCallbackTest.signed(code(amine, network), 450_00, "NET-" + UUID.randomUUID()).forEach(callback::param);
        mvc.perform(callback).andExpect(status().isOk());
        assertThat(orderStatus(network)).isEqualTo("PAID");
        on(network, "2026-11-26T13:00");
        String guichet = id(call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet Nord\"}"));
        String walkUp = tillSale(cashier, matchA, pelouseA, 3); // 750 DH at the till
        on(walkUp, "2026-11-26T18:00");
        String sara = fanToken(randomPhone());
        String collected = fanOrder(sara, matchB, pelouseB, 1); // a 250 DH code collected at the till
        call(cashier, post("/org/pos/codes/" + code(sara, collected) + "/collect"), "{\"amount_mad\": 25000}")
                .andExpect(status().isOk());
        on(collected, "2026-11-26T19:00");
        call(ops, post("/org/events/" + matchA + "/invitations"), """
                {"invitation_id": "%s", "items": [{"price_category_id": "%s", "qty": 2, "holders": [
                  {"name": "Salma Tazi"}, {"name": "Omar Idrissi"}]}]}""".formatted(UUID.randomUUID(), presseA))
                .andExpect(status().isCreated()); // FREE: no payment, no line

        // ---------- Fri 27: till cash only; Sat 28: a card payment ----------
        String friday = tillSale(cashier, matchB, virageB, 2); // 800 DH
        on(friday, "2026-11-27T16:00");
        call(cashier, post("/org/pos/sessions/" + guichet + "/close"), "{\"declared_mad\": 180000}")
                .andExpect(status().isOk());
        String hamza = fanToken(randomPhone());
        String saturday = fanOrder(hamza, matchB, pelouseB, 1); // 250 DH
        hosted(hamza, saturday, "CARD");
        on(saturday, "2026-11-28T10:00");

        // ---------- the job, day by day ----------
        settlements.runAt(local("2026-11-03T01:00"));
        settlements.runAt(local("2026-11-27T00:59"));
        assertThat(JsonPath.<List<String>>read(list(finance), "$.settlements[*].day")).containsExactly("2026-11-02");
        settlements.runAt(local("2026-11-27T01:00"));
        settlements.runAt(local("2026-11-28T01:00"));
        LocalDate today = LocalDate.now(CASABLANCA);
        boolean afterNov30 = LocalDate.parse("2026-11-30").isBefore(today);

        // (1) Thursday's statement
        String json = list(finance);
        assertThat(JsonPath.<List<String>>read(json, "$.settlements[*].day"))
                .containsExactly("2026-11-27", "2026-11-26", "2026-11-02");
        Map<String, Object> thu = row(json, "2026-11-26");
        assertThat(thu).containsEntry("status", "DUE").containsEntry("collected_mad", 4_000_00)
                .containsEntry("till_mad", 1_000_00).containsEntry("fee_rate_bp", 500).containsEntry("fee_ht_mad", 250_00)
                .containsEntry("fee_vat_mad", 50_00).containsEntry("carried_in_mad", 0).containsEntry("payout_mad", 3_700_00)
                .containsEntry("due_on", "2026-11-30").containsEntry("overdue", afterNov30);
        String thuId = (String) thu.get("id");
        String detail = body(call(finance, get("/org/finance/settlements/" + thuId)).andExpect(status().isOk()));
        List<Map<String, Object>> lines = JsonPath.read(detail, "$.lines");
        assertThat(lines.stream().map(l -> l.get("title") + " " + l.get("method") + " " + l.get("collector_name") + " "
                + l.get("payments") + " " + l.get("amount_mad")).toList()).containsExactly(
                "Abonnement Tribune CARD Payzone 1 40000",
                "Cotisation 2026 WALLET Payzone 1 15000",
                "Match A CARD Payzone 1 200000",
                "Match A CASH_AGENCY Réseau cash simulé 1 45000",
                "Match A POS_CASH Guichet 1 75000",
                "Match B CARD Payzone 1 100000",
                "Match B CASH_AGENCY Guichet 1 25000");

        // (2) Thursday's invoice and its PDF
        String number = invoiceNumber(org, "2026-11-26");
        assertThat(JsonPath.<String>read(thu, "$.invoice.number")).isEqualTo(number);
        byte[] pdf = call(finance, get("/org/finance/invoices/" + JsonPath.read(thu, "$.invoice.id") + ".pdf"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("N° " + number, "Date d'émission : 27/11/2026",
                    "Atlas FC SA", "ICE 001234567000089", "Jomhor Test SARL", "ICE 444444444444444",
                    "Frais de service billetterie · relevé du 26/11/2026", "250,00 DH", "TVA 20 %", "50,00 DH",
                    "300,00 DH", "Réglée par compensation sur le versement du relevé du 26/11/2026")
                    .doesNotContain(Ledger.RIB);
        }

        // (3) Friday: till cash only, nothing to pay out
        Map<String, Object> fri = row(json, "2026-11-27");
        assertThat(fri).containsEntry("status", "NONE").containsEntry("collected_mad", 0)
                .containsEntry("till_mad", 800_00).containsEntry("fee_ht_mad", 40_00).containsEntry("fee_vat_mad", 8_00)
                .containsEntry("payout_mad", 0).containsEntry("due_on", "2026-11-30").containsEntry("overdue", false);

        // (4) Saturday not yet issued: "Pas encore relevé"; the 2 nov. statement is overdue; "À recevoir"
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.open")).isEqualTo(Map.of(
                "since", local("2026-11-28T00:00").toString(), "collected_mad", 250_00, "till_mad", 0, "payments", 1));
        assertThat(row(json, "2026-11-02")).containsEntry("status", "DUE").containsEntry("payout_mad", 940_00)
                .containsEntry("due_on", "2026-11-04").containsEntry("overdue", true);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.due")).isEqualTo(Map.of("count", 2,
                "amount_mad", 940_00 + 3_700_00, "overdue", afterNov30 ? 2 : 1));
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.settings"))
                .containsEntry("rib_last4", "1234").containsEntry("fee_rate_bp", 500);
        assertThat(json).doesNotContain(Ledger.RIB);

        // Sunday 01:00: Saturday opens with the carried debt
        settlements.runAt(local("2026-11-29T01:00"));
        json = list(finance);
        assertThat(row(json, "2026-11-28")).containsEntry("carried_in_mad", -48_00).containsEntry("fee_ht_mad", 12_50)
                .containsEntry("fee_vat_mad", 2_50).containsEntry("payout_mad", 250_00 - 15_00 - 48_00)
                .containsEntry("due_on", "2026-11-30").containsEntry("status", "DUE");

        // (5) payout: OPS can't, NONE is a conflict, FINANCE records "VIR 0042"
        call(ops, get("/org/finance/settlements?from=2026-11-01&to=2026-11-30")).andExpect(status().isForbidden());
        call(ops, post("/org/finance/settlements/" + thuId + "/paid"), "{\"reference\": \"VIR 0042\"}")
                .andExpect(status().isForbidden());
        call(finance, post("/org/finance/settlements/" + fri.get("id") + "/paid"), "{\"reference\": \"VIR 0042\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTLEMENT_STATUS"));
        String paid = body(call(finance, post("/org/finance/settlements/" + thuId + "/paid"),
                "{\"reference\": \"VIR 0042\"}").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(paid, "$.status")).isEqualTo("PAID");
        assertThat(JsonPath.<String>read(paid, "$.payout_reference")).isEqualTo("VIR 0042");
        assertThat(JsonPath.<String>read(paid, "$.paid_at")).isNotNull();
        json = list(finance);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.due")).isEqualTo(Map.of("count", 2,
                "amount_mad", 940_00 + 187_00, "overdue", afterNov30 ? 2 : 1));

        // (6) an ADMIN refunds a card ticket: "Remboursements à faire", no issued statement changes
        List<Object> before = JsonPath.read(json, "$.settlements");
        String ticket = jdbc.sql("select min(id::text) from tickets where order_id = ?::uuid").param(cardA)
                .query(String.class).single();
        call(admin, post("/org/tickets/" + ticket + "/refund"), "{\"reason\": \"Empêchement\"}")
                .andExpect(status().isOk());
        settlements.runAt(local("2026-12-05T01:00"));
        json = list(finance);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.refunds_required"))
                .isEqualTo(Map.of("count", 1, "amount_mad", 1_000_00));
        assertThat(JsonPath.<List<Object>>read(json, "$.settlements")).isEqualTo(before);

        // (7) invoice numbers follow one another (fees on every day); the CSV; check.sql is clean for the club
        List<Integer> seqs = jdbc.sql("select i.seq from invoices i join settlements s on s.id = i.settlement_id "
                + "where s.organizer_id = ? order by s.day").param(org).query(Integer.class).list();
        assertThat(seqs).hasSize(4);
        assertThat(seqs).containsExactly(seqs.getFirst(), seqs.getFirst() + 1, seqs.getFirst() + 2, seqs.getFirst() + 3);
        String csv = new String(call(finance, get("/org/finance/settlements.csv?from=2026-11-26&to=2026-11-28"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String empty = ";;;;;;;;;;";
        assertThat(csv).isEqualTo("\uFEFF" + String.join("\r\n",
                "kind;jour;libelle;moyen;encaisseur;paiements;montant;en_ligne;guichets;frais_ht;tva;report;a_verser;"
                        + "echeance;statut;facture;reference",
                "releve;2026-11-28;;;;;;250,00;0,00;12,50;2,50;-48,00;187,00;2026-11-30;DUE;"
                        + invoiceNumber(org, "2026-11-28") + ";",
                "ligne;2026-11-28;Match B;CARD;Payzone;1;250,00" + empty,
                "releve;2026-11-27;;;;;;0,00;800,00;40,00;8,00;0,00;0,00;2026-11-30;NONE;"
                        + invoiceNumber(org, "2026-11-27") + ";",
                "ligne;2026-11-27;Match B;POS_CASH;Guichet;1;800,00" + empty,
                "releve;2026-11-26;;;;;;4000,00;1000,00;250,00;50,00;0,00;3700,00;2026-11-30;PAID;" + number
                        + ";VIR 0042",
                "ligne;2026-11-26;Abonnement Tribune;CARD;Payzone;1;400,00" + empty,
                "ligne;2026-11-26;Cotisation 2026;WALLET;Payzone;1;150,00" + empty,
                "ligne;2026-11-26;Match A;CARD;Payzone;1;2000,00" + empty,
                "ligne;2026-11-26;Match A;CASH_AGENCY;Réseau cash simulé;1;450,00" + empty,
                "ligne;2026-11-26;Match A;POS_CASH;Guichet;1;750,00" + empty,
                "ligne;2026-11-26;Match B;CARD;Payzone;1;1000,00" + empty,
                "ligne;2026-11-26;Match B;CASH_AGENCY;Guichet;1;250,00" + empty) + "\r\n");
        assertThat(breaches(org)).isEmpty();
    }
}
