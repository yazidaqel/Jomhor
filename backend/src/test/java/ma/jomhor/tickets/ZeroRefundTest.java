package ma.jomhor.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.products.application.PassTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BE-61 (plan lot-5 Q210; docs/04-api.md POST /org/tickets/{id}/refund Lot 5 step 4, GET /org/refunds): refunding a
 * FREE place (here a pass ticket) releases it and writes no refunds row; a paid ticket still owes its unit price.
 */
class ZeroRefundTest extends PaymentTest {

    private static final long PASS_PRICE = 150_000;

    @Autowired
    PassTicketService job;

    private long refundRows(UUID ticket) {
        return jdbc.sql("select count(*) from refunds where ticket_id = ?").param(ticket).query(Long.class).single();
    }

    @Test
    void aFreePlaceRefundWritesNoRowAndAPaidOneStillDoes() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int est = zone(a, venue, "EST");
        String series = JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"Saison\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}")
                .andExpect(status().isCreated())), "$.id");
        int event = JsonPath.read(body(call(a, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "2099-02-01T19:00:00Z",
                 "series_id": "%s"}""".formatted(venue, series)).andExpect(status().isCreated())), "$.id");
        String passPrice = JsonPath.read(body(call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Abonnés", "price_mad": 0, "quota": 2, "audience": "PASS"}""".formatted(est))
                .andExpect(status().isCreated())), "$.id");
        String sellable = price(a, event, est, "Plein tarif", 10);
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        String product = JsonPath.read(body(call(a, post("/org/products"), """
                {"kind": "PASS", "name": "Abonnement Est", "price_mad": %d, "quota": 10, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}"""
                .formatted(PASS_PRICE, series, est)).andExpect(status().isCreated())), "$.id");
        String holder = fanToken(randomPhone());
        String passOrder = JsonPath.read(body(fan(holder, post("/fan/product-orders"), """
                {"product_id": "%s", "channel": "APP", "holder_name": "Nadia Berrada"}""".formatted(product))
                .andExpect(status().isCreated())), "$.id");
        callback(approve(payment(holder, passOrder), PASS_PRICE)).andExpect(status().isSeeOther());
        job.run();
        UUID passTicket = jdbc.sql("select id from tickets where price_category_id = ?::uuid").param(passPrice)
                .query(UUID.class).single();
        assertThat(sold(passPrice)).isOne();

        // The pass ticket: REFUNDED, its place released, no refunds row, audit amount_mad 0, lookup refund null.
        String json = body(call(a, post("/org/tickets/" + passTicket + "/refund"), "{\"reason\": \"Doublon\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED")));
        assertThat(JsonPath.<Object>read(json, "$.refund")).isNull();
        assertThat(sold(passPrice)).isZero();
        assertThat(refundRows(passTicket)).isZero();
        String audit = jdbc.sql("select payload::text from audit_log where action = 'ticket.refund' and target = ?")
                .param(passTicket.toString()).query(String.class).single();
        assertThat(JsonPath.<Integer>read(audit, "$.amount_mad")).isZero();
        assertThat(JsonPath.<String>read(audit, "$.reason")).isEqualTo("Doublon");
        assertThat(JsonPath.<Map<String, Object>>read(audit, "$")).doesNotContainKey("refund_id");
        json = body(call(a, get("/org/refunds?event_id=" + event)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(json, "$[*]")).isEmpty();
        json = body(call(a, get("/org/events/" + event + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.refunded")).isOne();
        assertThat(JsonPath.<Integer>read(json, "$.refunded_mad")).isZero();

        // A paid ticket still writes REQUIRED at its unit price.
        String buyer = fanToken(randomPhone());
        String order = order(buyer, new Sale(a, est, event, sellable), 1);
        callback(approve(payment(buyer, order), total(order))).andExpect(status().isSeeOther());
        UUID paid = jdbc.sql("select id from tickets where order_id = ?::uuid").param(order).query(UUID.class).single();
        json = body(call(a, post("/org/tickets/" + paid + "/refund"), "{\"reason\": \"Match reporté\"}")
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.refund.status")).isEqualTo("REQUIRED");
        assertThat(JsonPath.<Integer>read(json, "$.refund.amount_mad")).isEqualTo(5000);
        assertThat(refundRows(paid)).isOne();
        json = body(call(a, get("/org/refunds?event_id=" + event)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[*].ticket_id")).containsExactly(paid.toString());
        json = body(call(a, get("/org/events/" + event + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.refunded")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$.refunded_mad")).isEqualTo(5000);
    }
}
