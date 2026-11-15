package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.gate.application.Manifests;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.products.application.PassTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Lot 5 step 3a exit proof, backend side (plan lot-5 §11.1 Exit 3a (1)-(8), §11.5 BE-56): OPS creates the series
 * "Saison 2026-27" with three matches (two published, one draft) and an "Abonnés" PASS category in Tribune Est in each,
 * a PASS product (quota 3) and a MEMBERSHIP product. Yazid buys the pass by card: the pass job issues a ticket for
 * each published match, in his wallet with {@code pass_id} and in the manifest; a second pass of the series is
 * PASS_HELD. Karim pays his by a cash code collected at the till. Salma's order expires and her pass place comes back
 * for Omar, whose tickets wait for PASS room (the product figures show it) until OPS raises a quota. Match 3 published:
 * every active pass gets its ticket. Yazid, a free member, pays the cotisation by cash code at the till and passes a
 * "Tarif membres"; Nadia, a free member who hasn't paid, gets MEMBERS_ONLY {@code paid_required}. Yazid transfers a
 * pass ticket: the recipient's ticket reads no pass. {@code loadtest/check.sql} finds no breach for the club's events
 * or products. The pass job on a fixed clock; card payments through the fake gateway, cash codes at a CASHIER's till.
 */
class Lot5PassesFlowTest extends QueueFixture {

    /** The pass job's clock: before every match's transfer_until. */
    private static final Instant NOW = Instant.parse("2099-01-01T00:00:00Z");
    private static final long PASS_PRICE = 150_000;
    private static final long CARD_PRICE = 20_000;
    private static final String VALID_UNTIL = "2099-07-01T00:00:00Z";
    private static final String CLOSE = "2099-06-30T22:00:00Z";

    @Autowired
    PassTicketService passJob;

    @Autowired
    OrderService orderService;

    @Autowired
    ManifestService manifestService;

    @Autowired
    Manifests manifests;

    @Test
    void passesByCardAndCashFollowEveryPublishedMatchThenPaidMembershipTransferAndNoBreach() throws Exception {
        // ---------- (1) B2: the series, three matches (two published), "Abonnés" (PASS) in Tribune Est ----------
        Caller admin = admin();
        Caller ops = caller(admin.org(), "OPS");
        UUID org = admin.org();
        jdbc.sql("update organizers set settings = settings || '{\"cash_points\": [{\"name\": \"Guichet\"}]}'::jsonb "
                + "where id = ?").param(org).update();
        String stade = venue(admin, "Stade");
        int est = zone(admin, stade, "EST");
        int nord = zone(admin, stade, "NORD");
        String series = id(call(ops, post("/org/series"),
                "{\"name\": \"Saison 2026-27\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}"));
        int m1 = match(ops, stade, series, "2099-02-01T19:00:00Z");
        int m2 = match(ops, stade, series, "2099-03-01T19:00:00Z");
        int m3 = match(ops, stade, series, "2099-04-01T19:00:00Z");
        String abo1 = price(ops, m1, est, "{\"name\": \"Abonnés\", \"price_mad\": 0, \"quota\": 2, \"audience\": \"PASS\"}");
        price(ops, m2, est, "{\"name\": \"Abonnés\", \"price_mad\": 0, \"quota\": 2, \"audience\": \"PASS\"}");
        price(ops, m3, est, "{\"name\": \"Abonnés\", \"price_mad\": 0, \"quota\": 3, \"audience\": \"PASS\"}");
        String members = price(ops, m1, nord,
                "{\"name\": \"Tarif membres\", \"price_mad\": 3000, \"quota\": 10, \"audience\": \"MEMBERS\"}");
        publish(ops, m1);
        publish(ops, m2); // m3 stays DRAFT

        // ---------- (2) B14: the pass (1 500 DH, quota 3) and the cotisation (200 DH), both on sale ----------
        String pass = id(call(ops, post("/org/products"), """
                {"kind": "PASS", "name": "Abonnement Tribune Est", "price_mad": %d, "quota": 3, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "%s", "on_sale": true}""".formatted(PASS_PRICE, series, est, CLOSE)));
        String card = id(call(ops, post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "Carte membre 2026-27", "price_mad": %d, "valid_until": "%s",
                 "sales_close_at": "%s", "on_sale": true}""".formatted(CARD_PRICE, VALID_UNTIL, CLOSE)));
        String shop = body(mvc.perform(get("/fan/products").param("organizer", org.toString()).param("kind", "PASS"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(shop, "$[*].id")).containsExactly(pass);
        assertThat(JsonPath.<List<Integer>>read(shop, "$[0].pass.events[*].id")).containsExactly(m1, m2);
        assertThat(JsonPath.<Integer>read(shop, "$[0].remaining")).isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(shop, "$[0].payment_methods")).contains("CARD", "CASH_AGENCY");

        // ---------- (3) Yazid, a free member, buys the pass by card: ACTIVE, a ticket per published match ----------
        Fan yazid = fan();
        as(yazid, put("/fan/me/membership"), """
                {"organizer_id": "%s", "marketing_push": false, "marketing_whatsapp": false}""".formatted(org))
                .andExpect(status().isCreated());
        String yazidOrder = body(buy(yazid, pass, "Yazid Aqel").andExpect(status().isCreated()));
        String yazidPass = JsonPath.read(yazidOrder, "$.pass_id");
        assertThat(JsonPath.<Integer>read(yazidOrder, "$.total_mad")).isEqualTo((int) PASS_PRICE);
        payByCard(yazid, yazidOrder);
        assertThat(passStatus(yazidPass)).isEqualTo("ACTIVE");
        String passes = body(as(yazid, get("/fan/passes")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(passes, "$.passes[0].status")).isEqualTo("ACTIVE");

        passJob.runAt(NOW);
        String wallet = body(as(yazid, get("/fan/tickets").param("with_keys", "true")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(wallet, "$.tickets[?(@.pass_id == '" + yazidPass + "')].event.id"))
                .containsExactlyInAnyOrder(m1, m2);
        assertThat(JsonPath.<List<String>>read(wallet, "$.tickets[*].k_t")).hasSize(2);
        String yazidM1 = ticketOf(wallet, m1);
        String yazidM2 = ticketOf(wallet, m2);
        // The scanner's view: the next manifest of match 1 carries it, VALID, initials only.
        int version = manifestService.build(org, m1).orElseThrow();
        assertThat(manifests.find(org, m1, version).orElseThrow().payload().lines())
                .contains(String.join("\t", "T", yazidM1, Integer.toString(est), "VALID",
                        HolderNames.initials("Yazid Aqel")));

        // A second pass of the series: PASS_HELD, nothing taken.
        String held = body(buy(yazid, pass, "Yazid Aqel").andExpect(status().isConflict()));
        assertThat(JsonPath.<String>read(held, "$.code")).isEqualTo("PASS_HELD");
        assertThat(JsonPath.<Map<String, Object>>read(held, "$.details"))
                .containsOnly(Map.entry("pass_id", yazidPass), Map.entry("status", "ACTIVE"));

        // ---------- (4) Salma's order is held; Karim pays his by a cash code at the till ----------
        Fan salma = fan();
        String salmaOrder = body(buy(salma, pass, "Salma Tazi").andExpect(status().isCreated()));
        Fan karim = fan();
        String karimOrder = body(buy(karim, pass, "Karim Alaoui", "WEB").andExpect(status().isCreated()));
        String karimPass = JsonPath.read(karimOrder, "$.pass_id");
        String karimCode = payCode(karim, JsonPath.read(karimOrder, "$.id"));

        Caller cashier = caller(org, "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        String lookup = body(call(cashier, get("/org/pos/codes/" + karimCode)).andExpect(status().isOk()));
        assertThat(JsonPath.<Object>read(lookup, "$.event")).isNull();
        assertThat(JsonPath.<String>read(lookup, "$.product.name")).isEqualTo("Abonnement Tribune Est");
        assertThat(JsonPath.<Boolean>read(lookup, "$.payable")).isTrue();
        collect(cashier, karimCode, PASS_PRICE);
        assertThat(passStatus(karimPass)).isEqualTo("ACTIVE");
        passJob.runAt(NOW);
        wallet = body(as(karim, get("/fan/tickets")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(wallet, "$.tickets[?(@.pass_id == '" + karimPass + "')].event.id"))
                .containsExactlyInAnyOrder(m1, m2);

        // ---------- (5) quota 3 reached; Salma's order expires and her place goes to Omar ----------
        Fan omar = fan();
        String soldOut = body(buy(omar, pass, "Omar Idrissi").andExpect(status().isConflict()));
        assertThat(JsonPath.<String>read(soldOut, "$.code")).isEqualTo("SOLD_OUT");
        assertThat(JsonPath.<String>read(soldOut, "$.details.product_id")).isEqualTo(pass);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid")
                .param(JsonPath.<String>read(salmaOrder, "$.id")).update();
        orderService.expireDue();
        assertThat(passStatus(JsonPath.read(salmaOrder, "$.pass_id"))).isEqualTo("CANCELLED");
        assertThat(JsonPath.<Integer>read(body(mvc.perform(get("/fan/products/" + pass)).andExpect(status().isOk())),
                "$.remaining")).isEqualTo(1);

        String omarOrder = body(buy(omar, pass, "Omar Idrissi").andExpect(status().isCreated()));
        String omarPass = JsonPath.read(omarOrder, "$.pass_id");
        payByCard(omar, omarOrder);
        passJob.runAt(NOW);
        assertThat(passTickets(omarPass)).isZero(); // "Abonnés" is full on both matches

        // B14: 2 of 3 issued on each published match, no PASS room left; match 3 (DRAFT) has room.
        String figures = body(call(ops, get("/org/products/" + pass)).andExpect(status().isOk()));
        assertThat(JsonPath.<Map<String, Object>>read(figures, "$.figures.passes"))
                .containsOnly(Map.entry("pending", 0), Map.entry("active", 3), Map.entry("suspended", 0));
        assertThat(JsonPath.<Number>read(figures, "$.figures.revenue_mad").longValue()).isEqualTo(3 * PASS_PRICE);
        assertThat(JsonPath.<List<Integer>>read(figures, "$.figures.events[*].event_id")).containsExactly(m1, m2, m3);
        assertThat(JsonPath.<List<Integer>>read(figures, "$.figures.events[*].issued")).containsExactly(2, 2, 0);
        assertThat(JsonPath.<List<Integer>>read(figures, "$.figures.events[*].expected")).containsExactly(3, 3, 3);
        assertThat(JsonPath.<List<Integer>>read(figures, "$.figures.events[*].pass_remaining"))
                .containsExactly(0, 0, 3);
        assertThat(JsonPath.<List<Object>>read(figures, "$.refunds_required")).isEmpty();

        // OPS raises match 1's "Abonnés" to 3: Omar's ticket follows on the next run.
        call(ops, patch("/org/events/" + m1 + "/prices/" + abo1), "{\"quota\": 3}").andExpect(status().isOk());
        passJob.runAt(NOW);
        assertThat(passTickets(omarPass)).isOne();
        assertThat(JsonPath.<List<Integer>>read(body(call(ops, get("/org/products/" + pass))),
                "$.figures.events[*].issued")).containsExactly(3, 2, 0);

        // ---------- (6) OPS publishes match 3: every active pass gets its ticket ----------
        publish(ops, m3);
        passJob.runAt(NOW);
        for (String p : List.of(yazidPass, karimPass, omarPass)) {
            assertThat(jdbc.sql("select count(*) from orders where pass_id = ?::uuid and event_id = ?")
                    .params(p, m3).query(Integer.class).single()).as("pass %s", p).isOne();
        }
        passJob.runAt(NOW); // nothing more
        assertThat(JsonPath.<List<Integer>>read(body(call(ops, get("/org/products/" + pass))),
                "$.figures.events[*].issued")).containsExactly(3, 2, 3);

        // ---------- (7) Yazid pays the cotisation by a cash code at the till; members prices need it ----------
        String nonPaid = body(place(yazid, m1, members).andExpect(status().isConflict()));
        assertThat(JsonPath.<String>read(nonPaid, "$.code")).isEqualTo("MEMBERS_ONLY");
        assertThat(JsonPath.<Boolean>read(nonPaid, "$.details.paid_required")).isTrue();

        String cardOrder = body(buy(yazid, card, "Yazid Aqel").andExpect(status().isCreated()));
        assertThat(JsonPath.<Object>read(cardOrder, "$.pass_id")).isNull();
        String cardCode = payCode(yazid, JsonPath.read(cardOrder, "$.id"));
        assertThat(JsonPath.<String>read(body(call(cashier, get("/org/pos/codes/" + cardCode))), "$.product.name"))
                .isEqualTo("Carte membre 2026-27");
        collect(cashier, cardCode, CARD_PRICE);
        String membership = body(as(yazid, get("/fan/me/membership").param("organizer", org.toString()))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(membership, "$.member.paid_until")).isEqualTo(VALID_UNTIL);
        assertThat(JsonPath.<Boolean>read(membership, "$.paid_required")).isTrue();
        String again = body(buy(yazid, card, "Yazid Aqel").andExpect(status().isConflict()));
        assertThat(JsonPath.<String>read(again, "$.code")).isEqualTo("MEMBERSHIP_PAID");

        payByCard(yazid, body(place(yazid, m1, members).andExpect(status().isCreated())));

        Fan nadia = fan();
        as(nadia, put("/fan/me/membership"), """
                {"organizer_id": "%s", "marketing_push": false, "marketing_whatsapp": false}""".formatted(org))
                .andExpect(status().isCreated());
        String refused = body(place(nadia, m1, members).andExpect(status().isConflict()));
        assertThat(JsonPath.<String>read(refused, "$.code")).isEqualTo("MEMBERS_ONLY");
        assertThat(JsonPath.<Map<String, Object>>read(refused, "$.details")).hasSize(3)
                .containsEntry("field", "items[0].holders[0]").containsEntry("member_joined_before", null)
                .containsEntry("paid_required", true);

        String cardFigures = body(call(ops, get("/org/products/" + card)).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(cardFigures, "$.figures.paid_members")).isOne();
        assertThat(JsonPath.<Number>read(cardFigures, "$.figures.revenue_mad").longValue()).isEqualTo(CARD_PRICE);

        // ---------- Yazid transfers his match 2 pass ticket: the recipient's ticket reads no pass ----------
        String hamza = randomPhone();
        as(yazid, post("/fan/tickets/" + yazidM2 + "/transfer"),
                "{\"to_phone\": \"%s\", \"name\": \"Hamza Chraibi\"}".formatted(hamza)).andExpect(status().isCreated());
        String received = body(mvc.perform(get("/fan/tickets").header("Authorization", "Bearer " + fanToken(hamza)))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(received, "$.tickets[*].event.id")).containsExactly(m2);
        assertThat(JsonPath.<Object>read(received, "$.tickets[0].pass_id")).isNull();
        passJob.runAt(NOW); // the pass already has its match 2 order: no new ticket
        assertThat(jdbc.sql("select count(*) from orders where pass_id = ?::uuid and event_id = ?")
                .params(yazidPass, m2).query(Integer.class).single()).isOne();
        // Yazid keeps the old one, CANCELLED (its QR scans ANNULÉ).
        wallet = body(as(yazid, get("/fan/tickets")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(wallet, "$.tickets[?(@.id == '" + yazidM2 + "')].status"))
                .containsExactly("CANCELLED");
        assertThat(JsonPath.<List<Integer>>read(wallet,
                "$.tickets[?(@.pass_id == '" + yazidPass + "' && @.status == 'VALID')].event.id"))
                .containsExactlyInAnyOrder(m1, m3);

        // ---------- (8) check.sql: no breach on the club's events or products ----------
        assertThat(breaches(org)).isEmpty();
        // The scope reaches this club's product rows (event_id null) and only these: the same drift planted on this
        // pass and on another club's product shows for this one alone.
        Caller other = admin();
        String elsewhere = id(call(other, post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "Carte membre", "price_mad": %d, "valid_until": "%s",
                 "sales_close_at": "%s", "on_sale": true}""".formatted(CARD_PRICE, VALID_UNTIL, CLOSE)));
        try {
            jdbc.sql("update products set sold = sold - 1 where id = ?::uuid").param(pass).update();
            jdbc.sql("update products set sold = sold + 1 where id = ?::uuid").param(elsewhere).update();
            assertThat(breaches(other.org())).extracting(r -> r.get("breach"), r -> r.get("ref"))
                    .containsExactly(tuple("PRODUCT_SOLD", elsewhere));
            assertThat(breaches(org)).extracting(r -> r.get("breach"), r -> r.get("ref"))
                    .containsExactly(tuple("PRODUCT_SOLD", pass));
        } finally { // back to the true counts: 3 live passes, none for a membership
            jdbc.sql("update products set sold = 3 where id = ?::uuid").param(pass).update();
            jdbc.sql("update products set sold = 0 where id = ?::uuid").param(elsewhere).update();
        }
    }

    // ---------- helpers ----------

    private ResultActions as(Fan f, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + f.token()));
    }

    private ResultActions as(Fan f, MockHttpServletRequestBuilder req, String json) throws Exception {
        return as(f, req.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** The {@code id} of a 201 response. */
    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r.andExpect(status().isCreated())), "$.id");
    }

    private int match(Caller c, String venue, String series, String startsAt) throws Exception {
        return JsonPath.read(body(call(c, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match %s", "starts_at": "%s",
                 "series_id": "%s"}""".formatted(venue, startsAt, startsAt, series))
                .andExpect(status().isCreated())), "$.id");
    }

    /** POST /org/events/{id}/prices with {@code json} plus the zone → its id. */
    private String price(Caller c, int event, int zone, String json) throws Exception {
        return id(call(c, post("/org/events/" + event + "/prices"),
                "{\"zone_id\": %d, %s".formatted(zone, json.substring(1))));
    }

    private void publish(Caller c, int event) throws Exception {
        call(c, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
    }

    private ResultActions buy(Fan f, String product, String holderName) throws Exception {
        return buy(f, product, holderName, "APP");
    }

    private ResultActions buy(Fan f, String product, String holderName, String channel) throws Exception {
        return as(f, post("/fan/product-orders"), """
                {"product_id": "%s", "channel": "%s", "holder_name": "%s"}""".formatted(product, channel, holderName));
    }

    /** POST /fan/orders of one place of {@code price}, held by the buyer. */
    private ResultActions place(Fan f, int event, String price) throws Exception {
        return as(f, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 1, "holders": %s}]}"""
                .formatted(event, price, holders(1)));
    }

    /** Pays the order (its create response) by simulated card → PAID. */
    private void payByCard(Fan f, String order) throws Exception {
        String orderId = JsonPath.read(order, "$.id");
        String payment = JsonPath.read(body(as(f, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CARD\"}")
                .andExpect(status().isOk())), "$.payment_id");
        callback(PaymentTest.approve(payment, ((Number) JsonPath.read(order, "$.total_mad")).longValue()))
                .andExpect(status().isSeeOther());
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
    }

    /** /pay with a cash code → the code. */
    private String payCode(Fan f, String orderId) throws Exception {
        return JsonPath.read(body(as(f, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk())), "$.pay_code");
    }

    /** The till collects the code → PAID, no ticket (a product order). */
    private void collect(Caller cashier, String code, long amount) throws Exception {
        String json = body(call(cashier, post("/org/pos/codes/" + code + "/collect"),
                "{\"amount_mad\": %d}".formatted(amount)).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("PAID");
        assertThat(JsonPath.<List<Object>>read(json, "$.ticket_ids")).isEmpty();
    }

    private String orderStatus(String orderId) {
        return jdbc.sql("select status::text from orders where id = ?::uuid").param(orderId).query(String.class)
                .single();
    }

    private String passStatus(String passId) {
        return jdbc.sql("select status::text from passes where id = ?::uuid").param(passId).query(String.class)
                .single();
    }

    /** The pass's ticket orders (one per match it has a ticket for). */
    private int passTickets(String passId) {
        return jdbc.sql("select count(*) from orders where pass_id = ?::uuid and event_id is not null").param(passId)
                .query(Integer.class).single();
    }

    /** The wallet's one ticket of {@code event}. */
    private static String ticketOf(String wallet, int event) {
        List<String> ids = JsonPath.read(wallet, "$.tickets[?(@.event.id == " + event + ")].id");
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }
}
