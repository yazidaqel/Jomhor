package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** Contract: GET /org/events/{id}/sales (docs/04-api.md §Organizer). */
class SalesReportApiTest extends PaymentTest {

    @Autowired
    OrderService orderService;

    private ResultActions sales(Caller c, int eventId) throws Exception {
        return call(c, get("/org/events/" + eventId + "/sales"));
    }

    private String order(String fan, int eventId, String priceId, String channel, int qty) throws Exception {
        return JsonPath.read(body(fan(fan, post("/fan/orders"), """
                {"event_id": %d, "channel": "%s", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}
                """.formatted(eventId, channel, priceId, qty, holders(qty))).andExpect(status().isCreated())), "$.id");
    }

    private void paid(String fan, String orderId) throws Exception {
        callback(approve(payment(fan, orderId), total(orderId))).andExpect(status().isSeeOther());
        assertThat(statusOf(orderId)).isEqualTo("PAID");
    }

    private void holdPast(String orderId) {
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId).update();
    }

    private String statusOf(String orderId) {
        return jdbc.sql("select status::text from orders where id = ?::uuid").param(orderId).query(String.class).single();
    }

    @Test
    void paidHeldAndRevenuePerZoneAndChannel() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int sud = zone(a, venueId, "SUD");
        int est = zone(a, venueId, "EST");
        zone(a, venueId, "AAA"); // no price on the event: not listed
        int eventId = event(a, venueId);
        String sudPrice = price(a, eventId, sud, "Plein tarif", 10);
        String estPrice = price(a, eventId, est, "Plein tarif", 20);
        price(a, eventId, est, "Reduit", 5);
        call(a, post("/org/events/" + eventId + "/publish")).andExpect(status().isOk());

        String f1 = fanToken(randomPhone());
        paid(f1, order(f1, eventId, sudPrice, "APP", 2)); // 2 x 50 DH, APP
        // A price change after the sale: revenue keeps the order_items snapshot.
        call(a, patch("/org/events/" + eventId + "/prices/" + sudPrice), "{\"price_mad\": 9000}").andExpect(status().isOk());
        String f2 = fanToken(randomPhone());
        order(f2, eventId, sudPrice, "APP", 1); // RESERVED, held
        String f3 = fanToken(randomPhone());
        String expired = order(f3, eventId, estPrice, "WEB", 3);
        holdPast(expired);
        orderService.expireDue();
        assertThat(statusOf(expired)).isEqualTo("EXPIRED");
        String f4 = fanToken(randomPhone());
        String lapsed = order(f4, eventId, estPrice, "WEB", 2);
        holdPast(lapsed); // past its hold, not swept yet: not held
        String f5 = fanToken(randomPhone());
        paid(f5, order(f5, eventId, estPrice, "WEB", 1)); // 1 x 50 DH, WEB

        String json = body(sales(a, eventId).andExpect(status().isOk()));
        assertThat(statusOf(lapsed)).isEqualTo("RESERVED");
        assertKeys(json, "$", "event_id", "quota", "paid", "held", "revenue_mad", "zones", "channels", "refunds_required");
        assertKeys(json, "$.zones[0]", "zone_id", "code", "name", "quota", "paid", "held", "revenue_mad");
        assertKeys(json, "$.channels[0]", "channel", "paid", "revenue_mad");
        assertThat(JsonPath.<Integer>read(json, "$.event_id")).isEqualTo(eventId);
        assertThat(JsonPath.<Integer>read(json, "$.quota")).isEqualTo(35);
        assertThat(JsonPath.<Integer>read(json, "$.paid")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(json, "$.held")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(json, "$.revenue_mad")).isEqualTo(15000);
        assertThat(JsonPath.<List<Map<String, Object>>>read(json, "$.zones")).containsExactly(
                Map.of("zone_id", est, "code", "EST", "name", "Zone EST", "quota", 25, "paid", 1, "held", 0, "revenue_mad", 5000),
                Map.of("zone_id", sud, "code", "SUD", "name", "Zone SUD", "quota", 10, "paid", 2, "held", 1, "revenue_mad", 10000));
        assertThat(JsonPath.<List<Map<String, Object>>>read(json, "$.channels")).containsExactly(
                Map.of("channel", "APP", "paid", 2, "revenue_mad", 10000),
                Map.of("channel", "WEB", "paid", 1, "revenue_mad", 5000),
                Map.of("channel", "POS", "paid", 0, "revenue_mad", 0));
        assertThat(JsonPath.<List<Object>>read(json, "$.refunds_required")).isEmpty();
    }

    @Test
    void anEventWithNoSalesHasItsZonesAndEveryChannelAtZero() throws Exception {
        Sale s = onSale(10);
        sales(s.org(), s.eventId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.quota").value(10)).andExpect(jsonPath("$.paid").value(0))
                .andExpect(jsonPath("$.held").value(0)).andExpect(jsonPath("$.revenue_mad").value(0))
                .andExpect(jsonPath("$.zones.length()").value(1)).andExpect(jsonPath("$.zones[0].zone_id").value(s.zoneId()))
                .andExpect(jsonPath("$.channels[*].channel").value(contains("APP", "WEB", "POS")))
                .andExpect(jsonPath("$.refunds_required").isEmpty());
    }

    @Test
    void refundsRequiredAreThisEventsAuditRowsNewestFirst() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String first = order(fan, s, 1);
        String p1 = payment(fan, first);
        callback(approve(p1, total(first) - 1)).andExpect(status().isSeeOther()); // AMOUNT_MISMATCH
        String second = order(fan, s, 1);
        String p2 = payment(fan, second);
        callback(approve(p2, total(second) - 2)).andExpect(status().isSeeOther());
        // Another event of the same organizer: not listed.
        String venueId = venue(s.org(), "Salle");
        int otherEvent = event(s.org(), venueId);
        String otherPrice = price(s.org(), otherEvent, zone(s.org(), venueId, "NORD"), "Plein tarif", 10);
        call(s.org(), post("/org/events/" + otherEvent + "/publish")).andExpect(status().isOk());
        String o = order(fan, otherEvent, otherPrice, "APP", 1);
        callback(approve(payment(fan, o), total(o) - 1)).andExpect(status().isSeeOther());
        // Another organizer's refund (onSale makes a new organizer), and a row of another organizer naming this
        // event's order: not listed.
        Sale foreign = onSale(10);
        String f = order(fan, foreign, 1);
        callback(approve(payment(fan, f), total(f) - 1)).andExpect(status().isSeeOther());
        jdbc.sql("""
                insert into audit_log (organizer_id, actor_type, action, target, payload)
                values (?, 'SYSTEM', 'payment.refund_required', 'x',
                        jsonb_build_object('payment_id', gen_random_uuid(), 'order_id', cast(? as text), 'reason', 'SOLD_OUT', 'amount_mad', 1))
                """).params(foreign.org().org(), first).update();
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'payment.refund_required' and payload ->> 'order_id' = ?")
                .param(first).query(Integer.class).single()).isEqualTo(2);

        String json = body(sales(s.org(), s.eventId()).andExpect(status().isOk()));
        assertKeys(json, "$.refunds_required[0]", "payment_id", "order_id", "amount_mad", "reason", "at");
        assertThat(JsonPath.<List<String>>read(json, "$.refunds_required[*].payment_id")).containsExactly(p2, p1);
        assertThat(JsonPath.<List<String>>read(json, "$.refunds_required[*].order_id")).containsExactly(second, first);
        assertThat(JsonPath.<List<Integer>>read(json, "$.refunds_required[*].amount_mad"))
                .containsExactly((int) total(second) - 2, (int) total(first) - 1);
        assertThat(JsonPath.<List<String>>read(json, "$.refunds_required[*].reason"))
                .containsExactly("AMOUNT_MISMATCH", "AMOUNT_MISMATCH");
        assertThat(JsonPath.<String>read(json, "$.refunds_required[0].at")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        // An amount mismatch leaves both orders RESERVED and held.
        assertThat(JsonPath.<Integer>read(json, "$.held")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$.paid")).isZero();
    }

    @Test
    void financeAndOpsReadGateIs403AndOtherOrganizersEventIs404() throws Exception {
        Sale s = onSale(10);
        sales(caller(s.org().org(), "FINANCE"), s.eventId()).andExpect(status().isOk());
        sales(caller(s.org().org(), "OPS"), s.eventId()).andExpect(status().isOk());
        sales(caller(s.org().org(), "GATE"), s.eventId()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        sales(caller(s.org().org(), "SUPERVISOR"), s.eventId()).andExpect(status().isForbidden());
        sales(admin(), s.eventId()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        sales(s.org(), Integer.MAX_VALUE).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/org/events/" + s.eventId() + "/sales").header("X-Organizer-Id", s.org().org()))
                .andExpect(status().isUnauthorized());
    }
}
