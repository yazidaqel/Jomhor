package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lot 5 step 4b exit proof, backend side (plan lot-5 §12.1 Exit 4b, §12.5 BE-66): OPS sets up "ABONNES20" (20 % on
 * "Plein tarif" in Tribune Est, 100 uses, 1 per account) and "DERBY50" (50 DH off the event, 2 uses). Yazid pays 240 DH
 * by card for 2 places at 150 DH, and a second order with the code is ACCOUNT_LIMIT. Karim's 300 DH become 250 DH,
 * paid by cash code at a till. A third fan's hold takes DERBY50's last use, so a fourth gets USED_UP until that hold
 * expires. A typo is UNKNOWN, ABONNES20 on a Virage place NOT_APPLICABLE, and neither leaves an order. An ADMIN
 * refunds one of Yazid's tickets: 120 DH, listed to pay back. /sales shows discount_mad 11,000 and the code list each
 * code's uses and discount. {@code loadtest/check.sql} finds no breach for the club.
 */
class Lot5PromoFlowTest extends PaymentTest {

    @Autowired
    OrderService orderService;

    @Test
    void twoCodesFromSetUpToRefundsAndFiguresWithNoBreach() throws Exception {
        // ---------- (1) B2 Codes promo: ABONNES20 on Plein tarif (Tribune Est), DERBY50 on the whole event ----------
        Caller admin = admin();
        Caller ops = caller(admin.org(), "OPS");
        String stade = venue(admin, "Stade");
        int est = zone(admin, stade, "EST");
        int virageZone = zone(admin, stade, "VIRAGE");
        int match = event(ops, stade);
        String plein = category(ops, match, est, "Plein tarif", 15_000);
        String virage = category(ops, match, virageZone, "Virage", 5_000);
        call(ops, post("/org/events/" + match + "/publish")).andExpect(status().isOk());
        jdbc.sql("update organizers set settings = settings || jsonb_build_object('cash_points', ?::jsonb) where id = ?")
                .params("[{\"name\": \"Guichet Stade\"}]", admin.org()).update();
        String abonnes = id(call(ops, post("/org/events/" + match + "/promo-codes"), """
                {"code": "ABONNES20", "kind": "PERCENT", "percent": 20, "price_category_id": "%s", "max_uses": 100,
                 "per_account_max": 1}""".formatted(plein)));
        String derby = id(call(ops, post("/org/events/" + match + "/promo-codes"),
                "{\"code\": \"DERBY50\", \"kind\": \"AMOUNT\", \"amount_mad\": 5000, \"max_uses\": 2}"));

        // ---------- (2) App: Yazid, 2 × 150 DH with "abonnes20" → −60 DH, 240 DH by card ----------
        String yazid = fanToken(randomPhone());
        String json = body(order(yazid, match, plein, 2, "abonnes20").andExpect(status().isCreated()));
        assertThat(JsonPath.<String>read(json, "$.promo_code")).isEqualTo("ABONNES20");
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(6_000);
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(24_000);
        String yazidOrder = JsonPath.read(json, "$.id");
        callback(PaymentTest.approve(payment(yazid, yazidOrder), 24_000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(yazidOrder)).isEqualTo("PAID");
        json = body(fan(yazid, get("/fan/orders/" + yazidOrder)).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.promo_code")).isEqualTo("ABONNES20");
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(6_000);
        assertThat(JsonPath.<List<String>>read(json, "$.ticket_ids")).hasSize(2);
        refused(order(yazid, match, plein, 1, "ABONNES20"), "ACCOUNT_LIMIT"); // "Tu as déjà utilisé ce code"

        // ---------- (3) Shop: Karim, DERBY50 on 300 DH → 250 DH, a cash code paid at the B11 till ----------
        String karim = fanToken(randomPhone());
        json = body(order(karim, match, plein, 2, "DERBY50").andExpect(status().isCreated()));
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo(25_000);
        String karimOrder = JsonPath.read(json, "$.id");
        json = body(fan(karim, post("/fan/orders/" + karimOrder + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.amount_mad")).isEqualTo(25_000);
        String payCode = JsonPath.read(json, "$.pay_code");
        Caller cashier = caller(admin.org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet Est\"}").andExpect(status().isCreated());
        call(cashier, post("/org/pos/codes/" + payCode + "/collect"), "{\"amount_mad\": 25000}")
                .andExpect(status().isOk());
        assertThat(orderStatus(karimOrder)).isEqualTo("PAID");

        // ---------- (4) Caps: a third fan's hold takes DERBY50's last use; a fourth gets it once that hold expires ----
        String third = fanToken(randomPhone());
        String thirdOrder = JsonPath.read(body(order(third, match, virage, 1, "DERBY50")
                .andExpect(status().isCreated())), "$.id");
        String fourth = fanToken(randomPhone());
        refused(order(fourth, match, virage, 1, "DERBY50"), "USED_UP"); // "Ce code a atteint sa limite"
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(thirdOrder)
                .update();
        orderService.expireDue();
        assertThat(orderStatus(thirdOrder)).isEqualTo("EXPIRED");
        json = body(order(fourth, match, virage, 1, "DERBY50").andExpect(status().isCreated()));
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(4_900); // 1 DH stays to pay

        // ---------- (5) Refusals: a typo, and ABONNES20 on a Virage place; neither leaves an order ----------
        String typoPhone = randomPhone();
        String typo = fanToken(typoPhone);
        refused(order(typo, match, plein, 1, "ABONNES2O"), "UNKNOWN"); // "Code inconnu ou expiré"
        refused(order(typo, match, virage, 1, "ABONNES20"), "NOT_APPLICABLE"); // "Ce code ne s'applique pas…"
        assertThat(jdbc.sql("select count(*) from orders o join users u on u.id = o.user_id where u.phone_e164 = ?")
                .param(typoPhone).query(Long.class).single()).isZero();

        // ---------- (6) Refund: one of Yazid's tickets → ⌊15,000 × 24,000 ÷ 30,000⌋ = 12,000, to pay back ----------
        String ticket = JsonPath.<List<String>>read(body(fan(yazid, get("/fan/orders/" + yazidOrder))
                .andExpect(status().isOk())), "$.ticket_ids").getFirst();
        call(admin, post("/org/tickets/" + ticket + "/refund"), "{\"reason\": \"Empêchement\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.refund.amount_mad").value(12_000));
        json = body(call(admin, get("/org/refunds?event_id=" + match)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Map<String, Object>>>read(json, "$")).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("amount_mad", 12_000).containsEntry("status", "REQUIRED")
                        .containsEntry("order_id", yazidOrder));

        // ---------- (7) Figures: B3 "Remises accordées −110 DH"; the B2 list's uses and discount per code ----------
        json = body(call(ops, get("/org/events/" + match + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isEqualTo(11_000);
        assertThat(JsonPath.<Integer>read(json, "$.revenue_mad")).isEqualTo(60_000);
        assertThat(JsonPath.<Integer>read(json, "$.refunded_mad")).isEqualTo(12_000);
        json = body(call(caller(admin.org(), "FINANCE"), get("/org/events/" + match + "/promo-codes"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[*].id")).containsExactly(abonnes, derby);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$[0].uses")).containsEntry("paid", 1)
                .containsEntry("held", 0);
        assertThat(JsonPath.<Integer>read(json, "$[0].discount_mad")).isEqualTo(6_000);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$[1].uses")).containsEntry("paid", 1)
                .containsEntry("held", 1);
        assertThat(JsonPath.<Integer>read(json, "$[1].discount_mad")).isEqualTo(5_000);

        // ---------- (8) check.sql: no breach for the club ----------
        assertThat(breaches(match)).isEmpty();
        assertThat(breaches(admin.org())).isEmpty();
    }

    // ---------- helpers ----------

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r.andExpect(status().isCreated())), "$.id");
    }

    private String category(Caller c, int event, int zone, String name, long priceMad) throws Exception {
        return id(call(c, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "%s", "price_mad": %d, "quota": 100}""".formatted(zone, name, priceMad)));
    }

    private ResultActions order(String token, int event, String category, int qty, String promo) throws Exception {
        return fan(token, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "promo_code": "%s",
                 "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}"""
                .formatted(event, promo, category, qty, holders(qty)));
    }

    private static void refused(ResultActions r, String reason) throws Exception {
        r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROMO_REFUSED"))
                .andExpect(jsonPath("$.details.field").value("promo_code"))
                .andExpect(jsonPath("$.details.reason").value(reason));
    }
}
