package ma.jomhor.gate;

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
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import ma.jomhor.common.HolderNames;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.gate.application.Manifests;
import ma.jomhor.gate.domain.ManifestPayload;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.products.application.PassTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lot 5 step 4a exit proof, backend side (plan lot-5 §12.1 Exit 4a, §12.5 BE-63): a venue zone PRESSE served by gate
 * P1 and INVITE categories "Presse" (quota 40) and "Invités" (quota 50); OPS issues 3 press accreditations (two with a
 * WhatsApp number, one without) and 10 partner invitations under one name with no phone. The journalist's wallet holds
 * hers with k_t; the partner slips print with their QR and category; the manifest carries both batches; a scanner at
 * P1 admits the journalist and the reconciliation counts her in PRESSE. The 41st press place is SOLD_OUT. An ADMIN
 * revokes the paper press ticket: REFUNDED in the next delta, no refunds row; a pass ticket's refund writes none
 * either. {@code loadtest/check.sql} finds no breach for the club.
 */
class Lot5InvitationsFlowTest extends ScanApiTest {

    private static final long PASS_PRICE = 150_000;

    @Autowired
    ManifestService manifestService;

    @Autowired
    Manifests manifests;

    @Autowired
    PassTicketService passJob;

    @Test
    void accreditationsAndInvitationsFromIssueToTheGateThenRevokeAndNoBreach() throws Exception {
        // ---------- (1) B10 + B2: PRESSE (60) served by P1; "Presse" and "Invités" INVITE; "Abonnés" PASS ----------
        Caller admin = admin();
        Caller ops = caller(admin.org(), "OPS");
        UUID org = admin.org();
        String stade = venue(admin, "Stade");
        int presse = zone(admin, stade, "PRESSE", 60);
        int honneur = zone(admin, stade, "HONNEUR");
        int est = zone(admin, stade, "EST");
        String p1 = gate(admin, stade, "P1", presse);
        gate(admin, stade, "H1", honneur, est);
        String series = id(call(ops, post("/org/series"),
                "{\"name\": \"Saison\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}"));
        // Inside the 3 h manifest lead, so the scanner downloads it; transfer_until is T0 − 90 min, still ahead.
        String startsAt = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS).toString();
        int match = JsonPath.read(body(call(ops, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "%s",
                 "series_id": "%s"}""".formatted(stade, startsAt, series)).andExpect(status().isCreated())), "$.id");
        String press = invitePrice(ops, match, presse, "Presse", 40);
        String guests = invitePrice(ops, match, honneur, "Invités", 50);
        id(call(ops, post("/org/events/" + match + "/prices"), """
                {"zone_id": %d, "name": "Abonnés", "price_mad": 0, "quota": 5, "audience": "PASS"}""".formatted(est)));
        call(ops, post("/org/events/" + match + "/publish")).andExpect(status().isOk());

        // ---------- (2) B7: 3 press accreditations (two phones, one paper), 10 partner places under one name ----------
        String journalist = randomPhone();
        String colleague = randomPhone();
        String pressBatch = body(invite(ops, match, UUID.randomUUID(), press, """
                [{"name": "Nadia Berrada", "phone": "%s"}, {"name": "Salma Tazi", "phone": "%s"},
                 {"name": "Karim El Idrissi"}]""".formatted(journalist, colleague)).andExpect(status().isCreated()));
        String pressOrder = JsonPath.read(pressBatch, "$.id");
        String partner = JsonPath.read(body(invite(ops, match, UUID.randomUUID(), guests,
                "[" + String.join(", ", Collections.nCopies(10, "{\"name\": \"Atlas Assurances\"}")) + "]")
                .andExpect(status().isCreated())), "$.id");
        String list = body(call(ops, get("/org/events/" + match + "/invitations")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(list, "$.invitations[*].id")).containsExactly(partner, pressOrder);
        assertThat(JsonPath.<List<String>>read(list, "$.invitations[1].tickets[*].delivery"))
                .containsExactlyInAnyOrder("WHATSAPP", "WHATSAPP", "PAPER");
        assertThat(JsonPath.<List<String>>read(list, "$.invitations[0].tickets[*].delivery")).containsOnly("PAPER");

        // ---------- (3) Delivery: the journalist's app holds hers with k_t; two WhatsApp deliveries queued ----------
        String wallet = body(mvc.perform(get("/fan/tickets").param("with_keys", "true")
                .header("Authorization", "Bearer " + fanToken(journalist))).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(wallet, "$.tickets[*].zone.code")).containsExactly("PRESSE");
        assertThat(JsonPath.<List<String>>read(wallet, "$.tickets[*].k_t")).hasSize(1);
        UUID nadia = UUID.fromString(JsonPath.<List<String>>read(wallet, "$.tickets[*].id").getFirst());
        assertThat(jdbc.sql("select count(*) from ticket_deliveries d join tickets t on t.id = d.ticket_id "
                + "where t.order_id = ?::uuid").param(pressOrder).query(Integer.class).single()).isEqualTo(2);

        // The partner's paper slips: a QR each, the category in place of a price.
        String slips = body(call(ops, get("/org/invitations/" + partner + "/tickets")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(slips, "$.tickets[*].qr")).hasSize(10).doesNotContainNull();
        assertThat(JsonPath.<List<String>>read(slips, "$.tickets[*].category.name")).containsOnly("Invités");
        assertThat(JsonPath.<List<String>>read(slips, "$.tickets[*].zone.code")).containsOnly("HONNEUR");
        assertThat(JsonPath.<List<Integer>>read(slips, "$.tickets[*].unit_price_mad")).containsOnly(0);

        // ---------- (4) Gate: the manifest carries both batches; P1 admits the journalist, counted in PRESSE ----------
        Dev scanner = active(admin);
        assign(admin, match, scanner, p1);
        int v1 = manifestService.build(org, match).orElseThrow();
        List<String> rows = ManifestApiTest.tLines(manifest(scanner, match));
        assertThat(rows).hasSize(13)
                .contains(ManifestApiTest.tLine(nadia, presse, "VALID", HolderNames.initials("Nadia Berrada")));
        assertThat(rows.stream().filter(r -> r.contains("\t" + honneur + "\tVALID\t"))).hasSize(10);
        UUID gateStaff = jdbc.sql("insert into staff (organizer_id, name, role) values (?, 'Agent P1', 'GATE') "
                + "returning id").param(org).query(UUID.class).single();
        Map<String, Object> admitted = row(v7(), nadia, UUID.fromString(p1), gateStaff, "OK");
        assertThat(JsonPath.<List<String>>read(sync(scanner, match, 0, List.of(admitted)), "$.acked"))
                .containsExactly((String) admitted.get("scan_id"));
        String recon = body(call(admin, get("/org/events/" + match + "/reconciliation")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(recon, "$.zones[?(@.code == 'PRESSE')].entered")).containsExactly(1);
        assertThat(JsonPath.<List<Integer>>read(recon, "$.zones[?(@.code == 'PRESSE')].sold")).containsExactly(3);

        // ---------- (5) Quota: 37 more press places fill "Presse"; the 41st is SOLD_OUT ----------
        invite(ops, match, UUID.randomUUID(), press,
                "[" + String.join(", ", Collections.nCopies(37, "{\"name\": \"Presse Locale\"}")) + "]")
                .andExpect(status().isCreated());
        invite(ops, match, UUID.randomUUID(), press, "[{\"name\": \"Omar Alaoui\"}]")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOLD_OUT"));
        String fanEvent = body(mvc.perform(get("/fan/events/" + match)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(fanEvent, "$.zones[*].prices[*].name")).isEmpty();

        // ---------- (6) Revoke: the paper press ticket REFUNDED, place back, no refunds row, REFUNDED delta ----------
        UUID karim = jdbc.sql("select id from tickets where order_id = ?::uuid and holder_user_id is null")
                .param(pressOrder).query(UUID.class).single();
        call(admin, post("/org/tickets/" + karim + "/refund"), "{\"reason\": \"Accréditation annulée\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refund").isEmpty());
        assertThat(jdbc.sql("select sold from price_categories where id = ?::uuid").param(press)
                .query(Integer.class).single()).isEqualTo(39);
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(match)
                .update();
        int v2 = manifestService.build(org, match).orElseThrow();
        String delta = ManifestPayload.delta(manifests.find(org, match, v2).orElseThrow().payload(),
                manifests.find(org, match, v1).orElseThrow().payload(), v1);
        assertThat(delta).contains("T\t" + karim + "\t" + presse + "\tREFUNDED\t");

        // A pass ticket's refund writes no row either.
        String product = id(call(ops, post("/org/products"), """
                {"kind": "PASS", "name": "Abonnement Est", "price_mad": %d, "quota": 5, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}"""
                .formatted(PASS_PRICE, series, est)));
        String fan = fanToken(randomPhone());
        String passOrder = JsonPath.read(body(mvc.perform(post("/fan/product-orders")
                .header("Authorization", "Bearer " + fan).contentType(MediaType.APPLICATION_JSON).content("""
                        {"product_id": "%s", "channel": "APP", "holder_name": "Hamza Chraibi"}""".formatted(product)))
                .andExpect(status().isCreated())), "$.id");
        String payment = JsonPath.read(body(mvc.perform(post("/fan/orders/" + passOrder + "/pay")
                .header("Authorization", "Bearer " + fan).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\": \"CARD\"}")).andExpect(status().isOk())), "$.payment_id");
        var callback = post("/payments/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        PaymentTest.approve(payment, PASS_PRICE).forEach(callback::param);
        mvc.perform(callback).andExpect(status().isSeeOther());
        passJob.run();
        UUID passTicket = jdbc.sql("select t.id from tickets t join price_categories p on p.id = t.price_category_id "
                + "where t.event_id = ? and p.audience = 'PASS'").param(match).query(UUID.class).single();
        call(admin, post("/org/tickets/" + passTicket + "/refund"), "{\"reason\": \"Doublon\"}")
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from refunds where event_id = ?").param(match).query(Integer.class)
                .single()).isZero();
        assertThat(JsonPath.<List<Object>>read(body(call(admin, get("/org/refunds?event_id=" + match))
                .andExpect(status().isOk())), "$[*]")).isEmpty();

        // ---------- (7) check.sql: no breach on the club's events or orders ----------
        assertThat(breaches(org)).isEmpty();
    }

    // ---------- helpers ----------

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r.andExpect(status().isCreated())), "$.id");
    }

    private String invitePrice(Caller c, int event, int zone, String name, int quota) throws Exception {
        return id(call(c, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "%s", "price_mad": 0, "quota": %d, "audience": "INVITE"}"""
                .formatted(zone, name, quota)));
    }

    private ResultActions invite(Caller c, int event, UUID id, String category, String holders) throws Exception {
        int qty = JsonPath.<List<Object>>read(holders, "$").size();
        return call(c, post("/org/events/" + event + "/invitations"), """
                {"invitation_id": "%s", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}"""
                .formatted(id, category, qty, holders));
    }

    private String manifest(Dev d, int eventId) throws Exception {
        byte[] gz = send(d, Req.now("GET", "/v1/gate/events/" + eventId + "/manifest", "")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
