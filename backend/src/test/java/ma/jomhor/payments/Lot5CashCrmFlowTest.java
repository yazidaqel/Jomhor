package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.sales.products.application.PassTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lot 5 step 5a exit proof, backend side (plan lot-5 §13.1 Exit 5a (1)-(6), (8); §13.5 BE-69). Salma's "Guichet Nord"
 * sells 3 places (450 DH) and collects a 250 DH cash code, then closes declaring 700 DH; "Guichet Sud" sells 2 places
 * of the second match and closes declaring 300 DH. B8 Encaissements: both "Non déposé", "À déposer 1 000 DH". FINANCE
 * records "Bordereau 448812" at 990 DH over both (écart −10 DH); Sud again is DEPOSITED. An ADMIN voids it, FINANCE
 * records it again at 1 000 DH (écart 0); OPS reads but can't record. B3 on the second match: APP 4, POS 2, free
 * {passes 2, invitations 3}, paid 11. The CRM file holds Yazid, Karim (with his phone) and Nadia (abonné), and the
 * summary's exportable is 3. {@code loadtest/check.sql} finds no breach for the club. Item (7) is the app (FL-33).
 */
class Lot5CashCrmFlowTest extends PaymentTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    @Autowired
    PassTicketService passJob;

    // ---------- fixtures ----------

    private String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r.andExpect(status().isCreated())), "$.id");
    }

    private String category(Caller c, int event, int zone, String name, long priceMad, String audience)
            throws Exception {
        return id(call(c, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "%s", "price_mad": %d, "quota": 20, "audience": "%s"}"""
                .formatted(zone, name, priceMad, audience)));
    }

    private int match(Caller c, String venue, String series, String startsAt) throws Exception {
        String json = body(call(c, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "%s"%s}"""
                .formatted(venue, startsAt, series == null ? "" : ", \"series_id\": \"" + series + "\""))
                .andExpect(status().isCreated()));
        return JsonPath.read(json, "$.id");
    }

    private String fanOrder(String fan, int event, String price, int qty) throws Exception {
        return id(fan(fan, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}
                """.formatted(event, price, qty, holders(qty))));
    }

    private void tillSale(Caller cashier, int event, String price, int qty) throws Exception {
        String holders = String.join(", ", Collections.nCopies(qty, "{\"name\": \"Yazid Aqel\"}"));
        call(cashier, post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d, "items": [{"price_category_id": "%s", "qty": %d, "holders": [%s]}]}
                """.formatted(UUID.randomUUID(), event, price, qty, holders)).andExpect(status().isCreated());
    }

    private String openTill(Caller cashier, String label) throws Exception {
        return id(call(cashier, post("/org/pos/sessions"), "{\"label\": \"%s\"}".formatted(label)));
    }

    private void closeTill(Caller cashier, String session, long declared) throws Exception {
        call(cashier, post("/org/pos/sessions/" + session + "/close"), "{\"declared_mad\": %d}".formatted(declared))
                .andExpect(status().isOk());
    }

    private String cash(Caller c) throws Exception {
        String today = LocalDate.now(CASABLANCA).toString();
        return body(call(c, get("/org/finance/cash?from=" + today + "&to=" + today + "&timezone=Africa/Casablanca"))
                .andExpect(status().isOk()));
    }

    private ResultActions deposit(Caller c, long amount, String... sessions) throws Exception {
        return call(c, post("/org/finance/deposits"), """
                {"deposited_on": "%s", "amount_mad": %d, "reference": "Bordereau 448812", "session_ids": ["%s"]}"""
                .formatted(LocalDate.now(CASABLANCA), amount, String.join("\", \"", sessions)));
    }

    /** A fan with an account: logs in, then names itself (PATCH /fan/me); returns its token and user id. */
    private record Fan(String phone, String token, UUID id) {}

    private Fan fan(String name, String locale) throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        fan(token, patch("/fan/me"), "{\"display_name\": \"%s\", \"locale\": \"%s\"}".formatted(name, locale))
                .andExpect(status().isOk());
        return new Fan(phone, token, jdbc.sql("select id from users where phone_e164 = ?").param(phone)
                .query(UUID.class).single());
    }

    private void join(Fan f, UUID org, boolean push, boolean whatsapp) throws Exception {
        fan(f.token(), put("/fan/me/membership"), "{\"organizer_id\": \"%s\", \"marketing_push\": %b, "
                .formatted(org, push) + "\"marketing_whatsapp\": %b}".formatted(whatsapp)).andExpect(status().isCreated());
    }

    @Test
    void tillsDepositsVoidFreePlacesAndTheCrmFileWithNoBreach() throws Exception {
        // ---------- set-up: two matches, the second in a series with Abonnés (PASS) and Presse (INVITE) ----------
        Caller admin = admin();
        UUID org = admin.org();
        Caller ops = caller(org, "OPS");
        Caller finance = caller(org, "FINANCE");
        jdbc.sql("update organizers set settings = settings || '{\"cash_points\": [{\"name\": \"Guichet\"}]}'::jsonb "
                + "where id = ?").param(org).update();
        String stade = venue(admin, "Stade");
        int nord = zone(admin, stade, "NORD");
        String series = id(call(ops, post("/org/series"),
                "{\"name\": \"Saison 2026-27\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}"));
        int m1 = match(ops, stade, null, "2099-02-01T19:00:00Z");
        int m2 = match(ops, stade, series, "2099-03-01T19:00:00Z");
        String plein1 = category(ops, m1, nord, "Plein tarif", 15_000, "ALL");
        String tribune1 = category(ops, m1, nord, "Tribune", 25_000, "ALL");
        String plein2 = category(ops, m2, nord, "Plein tarif", 15_000, "ALL");
        category(ops, m2, nord, "Abonnés", 0, "PASS");
        String presse2 = category(ops, m2, nord, "Presse", 0, "INVITE");
        for (int m : List.of(m1, m2)) {
            call(ops, post("/org/events/" + m + "/publish")).andExpect(status().isOk());
        }

        // ---------- (1) tills: Nord 3 × 150 DH + a 250 DH cash code, declares 700; Sud 2 × 150 DH, declares 300 ----------
        Caller salma = caller(org, "CASHIER");
        String guichetNord = openTill(salma, "Guichet Nord");
        tillSale(salma, m1, plein1, 3);
        String buyer = fanToken(randomPhone());
        String codeOrder = fanOrder(buyer, m1, tribune1, 1);
        String payCode = JsonPath.read(body(fan(buyer, post("/fan/orders/" + codeOrder + "/pay"),
                "{\"method\": \"CASH_AGENCY\"}").andExpect(status().isOk())), "$.pay_code");
        call(salma, post("/org/pos/codes/" + payCode + "/collect"), "{\"amount_mad\": 25000}").andExpect(status().isOk());
        closeTill(salma, guichetNord, 70_000);
        Caller sudCashier = caller(org, "CASHIER");
        String guichetSud = openTill(sudCashier, "Guichet Sud");
        tillSale(sudCashier, m2, plein2, 2);
        closeTill(sudCashier, guichetSud, 30_000);

        // ---------- (2) B8 Encaissements: both "Non déposé", "À déposer 1 000 DH" ----------
        String json = cash(finance);
        assertThat(JsonPath.<List<String>>read(json, "$.sessions[*].id")).containsExactly(guichetNord, guichetSud);
        assertThat(JsonPath.<List<Integer>>read(json, "$.sessions[*].expected_mad")).containsExactly(70_000, 30_000);
        assertThat(JsonPath.<List<Object>>read(json, "$.sessions[*].deposit")).containsExactly(null, null);
        assertThat(JsonPath.<Integer>read(json, "$.totals.undeposited_mad")).isEqualTo(100_000);

        // ---------- (3) B8 Dépôts: both sessions, 990 DH → écart −10 DH; Sud again → DEPOSITED ----------
        String undeposited = body(call(finance, get("/org/finance/deposits?from=" + LocalDate.now(CASABLANCA) + "&to="
                + LocalDate.now(CASABLANCA))).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(undeposited, "$.undeposited[*].id")).containsExactly(guichetNord, guichetSud);
        json = body(deposit(finance, 99_000, guichetNord, guichetSud).andExpect(status().isCreated()));
        String first = JsonPath.read(json, "$.id");
        assertThat(JsonPath.<Integer>read(json, "$.declared_mad")).isEqualTo(100_000);
        assertThat(JsonPath.<Integer>read(json, "$.variance_mad")).isEqualTo(-1_000);
        json = cash(finance);
        assertThat(JsonPath.<List<String>>read(json, "$.sessions[*].deposit.reference"))
                .containsExactly("Bordereau 448812", "Bordereau 448812");
        assertThat(JsonPath.<Integer>read(json, "$.totals.undeposited_mad")).isZero();
        deposit(finance, 30_000, guichetSud).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("DEPOSITED"))
                .andExpect(jsonPath("$.details.fields['session_ids[0]']").exists());

        // ---------- (4) void (ADMIN), sessions back; FINANCE records 1 000 DH → écart 0; OPS read-only ----------
        call(finance, post("/org/finance/deposits/" + first + "/void"), "{\"reason\": \"montant mal saisi\"}")
                .andExpect(status().isForbidden());
        call(admin, post("/org/finance/deposits/" + first + "/void"), "{\"reason\": \"montant mal saisi\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions").isEmpty());
        assertThat(JsonPath.<Integer>read(cash(finance), "$.totals.undeposited_mad")).isEqualTo(100_000);
        deposit(ops, 100_000, guichetNord, guichetSud).andExpect(status().isForbidden());
        json = body(deposit(finance, 100_000, guichetNord, guichetSud).andExpect(status().isCreated()));
        assertThat(JsonPath.<Integer>read(json, "$.variance_mad")).isZero();
        String second = JsonPath.read(json, "$.id");
        json = body(call(ops, get("/org/finance/deposits?from=" + LocalDate.now(CASABLANCA) + "&to="
                + LocalDate.now(CASABLANCA))).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.deposits[*].id")).containsExactlyInAnyOrder(first, second);
        assertThat(JsonPath.<List<String>>read(json, "$.deposits[?(@.voided_at != null)].void_reason"))
                .containsExactly("montant mal saisi");
        assertThat(JsonPath.<List<Object>>read(json, "$.undeposited")).isEmpty();
        assertThat(JsonPath.<List<String>>read(cash(ops), "$.sessions[*].deposit.id")).containsOnly(second);

        // ---------- (6) CRM members: Yazid (push), Karim (WhatsApp, paid), Nadia (pass), Amine, Sara (left) ----------
        Fan yazid = fan("Yazid", "fr");
        Fan karim = fan("Karim", "fr");
        Fan nadia = fan("Nadia", "ar");
        Fan amine = fan("Amine", "fr");
        Fan sara = fan("Sara", "fr");
        join(yazid, org, true, false);
        join(karim, org, false, true);
        join(nadia, org, false, false);
        join(amine, org, false, false);
        join(sara, org, true, true);
        fan(sara.token(), delete("/fan/me/membership").param("organizer", org.toString())).andExpect(status().isNoContent());
        // Karim's and Sara's cotisations: paid_until as a MEMBERSHIP product would set it (paid flows: Lot5PassesFlowTest)
        jdbc.sql("update memberships set paid_until = '2099-06-30T12:00:00Z' where organizer_id = ? and user_id in (?, ?)")
                .params(org, karim.id(), sara.id()).update();

        // ---------- (5) B3 on match 2: 2 pass tickets (Nadia, a non-member), 3 invitations, 4 app places, 2 till ----------
        UUID product = jdbc.sql("""
                        insert into products (organizer_id, kind, name, price_mad, sales_close_at, quota, sold, series_id,
                                              zone_id)
                        values (?, 'PASS', 'Abonnement', 150000, '2099-01-01T00:00:00Z', 10, 2, ?::uuid, ?) returning id""")
                .params(org, series, nord).query(UUID.class).single();
        Fan omar = fan("Omar", "fr"); // a pass holder who isn't a member: never in the CRM file
        for (Fan holder : List.of(nadia, omar)) { // ACTIVE passes, as a paid purchase leaves them (Lot5PassesFlowTest)
            jdbc.sql("insert into passes (organizer_id, product_id, series_id, user_id, holder_name, status) "
                    + "values (?, ?, ?::uuid, ?, 'Abonné', 'ACTIVE')").params(org, product, series, holder.id()).update();
        }
        passJob.runAt(Instant.now());
        call(ops, post("/org/events/" + m2 + "/invitations"), """
                {"invitation_id": "%s", "items": [{"price_category_id": "%s", "qty": 3, "holders": [
                  {"name": "Nadia Berrada"}, {"name": "Salma Tazi"}, {"name": "Karim El Idrissi"}]}]}"""
                .formatted(UUID.randomUUID(), presse2)).andExpect(status().isCreated());
        String app = fanOrder(yazid.token(), m2, plein2, 4);
        callback(approve(payment(yazid.token(), app), total(app))).andExpect(status().isSeeOther());
        assertThat(orderStatus(app)).isEqualTo("PAID");

        json = body(call(finance, get("/org/events/" + m2 + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(json, "$.channels[*].paid")).containsExactly(4, 0, 2); // APP, WEB, POS
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.free")).isEqualTo(Map.of("passes", 2, "invitations", 3));
        assertThat(JsonPath.<Integer>read(json, "$.paid")).isEqualTo(11);
        assertThat(JsonPath.<List<Integer>>read(json, "$.methods[?(@.method == 'FREE')].paid")).containsExactly(5);

        // ---------- (6) B9 "Exporter (CSV)": 3 fans, 1 with a phone; ADMIN only; audited without PII ----------
        String summary = body(call(admin, get("/org/members/summary")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(summary, "$.exportable")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(summary, "$.marketing_whatsapp")).isEqualTo(1);
        for (Caller c : List.of(finance, ops)) {
            call(c, get("/org/crm/export.csv")).andExpect(status().isForbidden());
        }
        String file = new String(call(admin, get("/org/crm/export.csv")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String joined = LocalDate.now(CASABLANCA).toString(); // joined during the test (same local day, to the second)
        String paidUntil = Instant.parse("2099-06-30T12:00:00Z").atZone(CASABLANCA).toLocalDate().toString();
        assertThat(file).isEqualTo("\uFEFF"
                + "numero;nom;telephone;langue;membre_depuis;notifications;whatsapp;cotisation_jusqu_au;abonne;matchs\r\n"
                + "1;Yazid;;fr;" + joined + ";oui;non;;non;0\r\n"
                + "2;Karim;" + karim.phone() + ";fr;" + joined + ";non;oui;" + paidUntil + ";non;0\r\n"
                + "3;Nadia;;ar;" + joined + ";non;non;;oui;0\r\n");
        assertThat(file).doesNotContain("Amine", "Sara", "Omar", yazid.phone(), nadia.phone());
        assertThat(jdbc.sql("select payload::text from audit_log where organizer_id = ? and action = 'crm.export'")
                .param(org).query(String.class).list()).containsExactly("{\"rows\": 3, \"with_phone\": 1}");

        // ---------- (8) correctness: check.sql is clean for the club (its sessions included) ----------
        assertThat(breaches(org)).isEmpty();
    }
}
