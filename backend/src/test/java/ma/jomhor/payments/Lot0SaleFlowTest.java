package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Lot 0 exit proof (plan §1): an organizer creates and publishes an event through the API, a fan buys one ticket
 * by card on the simulated gateway, following the same redirect → hosted page → signed callback → PAID path a
 * real gateway takes.
 */
class Lot0SaleFlowTest extends PaymentTest {

    @Test
    void anOrganizerPublishesAndAFanBuysOneTicketByCard() throws Exception {
        // Organizer: staff OTP login, venue and zone, event, price, publish.
        Caller org = admin();
        String venueId = venue(org, "Grand Stade");
        int zoneId = zone(org, venueId, "NORD");
        int eventId = event(org, venueId);
        String priceId = price(org, eventId, zoneId, "Plein tarif", 100);
        call(org, post("/org/events/" + eventId + "/publish")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_SALE"));

        // Fan: OTP login, order, pay.
        String phone = randomPhone();
        String fan = fanToken(phone);
        String orderId = order(fan, new Sale(org, zoneId, eventId, priceId), 1);
        String json = body(pay(fan, orderId).andExpect(status().isOk()));
        assertKeys(json, "$", "payment_id", "redirect_url");
        String paymentId = JsonPath.read(json, "$.payment_id");
        String redirectUrl = JsonPath.read(json, "$.redirect_url");
        assertThat(redirectUrl).isEqualTo(BASE + "/payments/card/" + paymentId + "/redirect");
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");

        // Browser: redirect → simulated gateway page → "Approuver" (a signed form post) → /return.
        String gatewayUrl = browse(redirectUrl).andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        assertThat(gatewayUrl).isEqualTo(BASE + "/payments/fake/" + paymentId);
        String page = browse(gatewayUrl).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("50,00 DH")))
                .andReturn().getResponse().getContentAsString();
        Map<String, String> approve = formFields(page, "Approuver");
        assertThat(approve).containsEntry("payment_id", paymentId).containsEntry("outcome", "APPROVED")
                .containsEntry("amount_mad", "5000").containsKeys("provider_ref", "signature");
        String landing = callback(approve).andExpect(status().isSeeOther())
                .andReturn().getResponse().getHeader("Location");
        assertThat(landing).isEqualTo(BASE + "/payments/card/" + paymentId + "/return");
        browse(landing).andExpect(status().isOk()).andExpect(content().string(containsString("en cours de confirmation")));

        // Order PAID with exactly one VALID ticket held by the buyer; sold = 1.
        String got = body(fan(fan, get("/fan/orders/" + orderId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paid_at").isString())
                .andExpect(jsonPath("$.ticket_ids.length()").value(1)));
        String ticketId = JsonPath.<List<String>>read(got, "$.ticket_ids").getFirst();
        UUID buyer = jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
        assertThat(jdbc.sql("""
                select t.status::text || ' ' || t.event_id || ' ' || t.zone_id || ' ' || t.price_category_id
                       || ' ' || t.holder_user_id || ' ' || (t.organizer_id = ?)
                from tickets t where t.id = ?::uuid
                """).params(org.org(), ticketId).query(String.class).single())
                .isEqualTo("VALID %d %d %s %s true".formatted(eventId, zoneId, priceId, buyer));
        assertThat(tickets(orderId)).isEqualTo(1);
        assertThat(sold(priceId)).isEqualTo(1);
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("""
                select o.payment_method::text || ' ' || o.payment_ref || ' ' || p.provider_ref || ' ' || (p.completed_at is not null)
                from orders o join payments p on p.order_id = o.id where p.id = ?::uuid
                """).param(paymentId).query(String.class).single())
                .isEqualTo("CARD %s %s true".formatted(approve.get("provider_ref"), approve.get("provider_ref")));
        // The callback kept is the verified verdict only: no signature, nothing card-like.
        assertThat(jdbc.sql("select callback::text from payments where id = ?::uuid").param(paymentId).query(String.class).single())
                .doesNotContain("signature").contains("\"approved\": true");

        // The organizer sees the sale.
        call(org, get("/org/events/" + eventId + "/prices")).andExpect(jsonPath("$[0].sold").value(1));
        // The gateway page and the redirect are gone once the payment is done.
        browse(redirectUrl).andExpect(status().isGone());
        browse(gatewayUrl).andExpect(status().isGone()).andExpect(header().string("Cache-Control", "no-store"));
    }
}
