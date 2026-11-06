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
 * Lot 1 exit proof (plan §1), on the simulated gateway: an organizer creates and publishes an event through the
 * API; a fan buys 2 named places by card, the second held by a friend's phone, following the same redirect →
 * hosted page → signed callback → /return path a real gateway takes. After PAID each holder holds their ticket.
 */
class Lot1SaleFlowTest extends PaymentTest {

    @Test
    void aFanBuysTwoNamedPlacesTheSecondForAFriend() throws Exception {
        // Organizer: staff OTP login, venue and zone, event, price, publish.
        Caller org = admin();
        String venueId = venue(org, "Grand Stade");
        int zoneId = zone(org, venueId, "NORD");
        int eventId = event(org, venueId);
        String priceId = price(org, eventId, zoneId, "Plein tarif", 100);
        call(org, post("/org/events/" + eventId + "/publish")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_SALE"));

        // Fan: OTP login, order 2 places (2nd for a friend who has no account yet), pay.
        String phone = randomPhone();
        String friendPhone = randomPhone();
        String fan = fanToken(phone);
        String reserved = body(fan(fan, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 2,
                  "holders": [{"name": "  Yazid   Aqel "}, {"name": "Samir Benali", "phone": "%s"}]}]}
                """.formatted(eventId, priceId, friendPhone)).andExpect(status().isCreated()));
        assertThat(JsonPath.<List<String>>read(reserved, "$.items[0].holders[*].label"))
                .containsExactly("Yazid A.", "Samir B.");
        assertKeys(reserved, "$.items[0].holders[1]", "label");
        assertThat(reserved).doesNotContain(friendPhone).doesNotContain("Benali").doesNotContain("user_id");
        String orderId = JsonPath.read(reserved, "$.id");

        String json = body(pay(fan, orderId).andExpect(status().isOk()));
        assertKeys(json, "$", "payment_id", "redirect_url");
        String paymentId = JsonPath.read(json, "$.payment_id");
        String redirectUrl = JsonPath.read(json, "$.redirect_url");
        assertThat(redirectUrl).isEqualTo(BASE + "/payments/card/" + paymentId + "/redirect");
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");

        // Browser: redirect → simulated gateway page → "Approuver" (a signed form post) → /return → the app.
        String gatewayUrl = browse(redirectUrl).andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        assertThat(gatewayUrl).isEqualTo(BASE + "/payments/fake/" + paymentId);
        String page = browse(gatewayUrl).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("100,00 DH")))
                .andReturn().getResponse().getContentAsString();
        Map<String, String> approve = formFields(page, "Approuver");
        assertThat(approve).containsEntry("payment_id", paymentId).containsEntry("outcome", "APPROVED")
                .containsEntry("amount_mad", "10000").containsKeys("provider_ref", "signature");
        String landing = callback(approve).andExpect(status().isSeeOther())
                .andReturn().getResponse().getHeader("Location");
        assertThat(landing).isEqualTo(BASE + "/payments/card/" + paymentId + "/return");
        browse(landing).andExpect(status().isFound()).andExpect(header().string("Location", "jomhor-fan://orders/" + orderId));

        // Order PAID with two VALID tickets: the buyer holds one, the friend's (new) account the other. The buyer's
        // order lists only the ticket the buyer holds (04: a friend's ticket is never listed to the buyer).
        String got = body(fan(fan, get("/fan/orders/" + orderId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.channel").value("APP"))
                .andExpect(jsonPath("$.paid_at").isString())
                .andExpect(jsonPath("$.ticket_ids.length()").value(1)));
        assertThat(JsonPath.<List<String>>read(got, "$.items[0].holders[*].label")).containsExactly("Yazid A.", "Samir B.");
        UUID buyer = userId(phone);
        UUID friend = userId(friendPhone);
        assertThat(friend).isNotEqualTo(buyer);
        assertThat(tickets(orderId)).isEqualTo(2);
        String listed = JsonPath.<List<String>>read(got, "$.ticket_ids").getFirst();
        assertThat(jdbc.sql("select holder_user_id from tickets where id = ?::uuid").param(listed).query(UUID.class)
                .single()).isEqualTo(buyer);
        List<String> issued = jdbc.sql("""
                select t.status::text || ' ' || t.event_id || ' ' || t.zone_id || ' ' || t.price_category_id
                       || ' ' || t.holder_user_id || ' ' || t.holder_name || ' ' || t.holder_initials
                       || ' ' || (t.organizer_id = ?)
                from tickets t where t.order_id = ?::uuid order by t.holder_name desc
                """).params(org.org(), orderId).query(String.class).list();
        String prefix = "VALID %d %d %s ".formatted(eventId, zoneId, priceId);
        assertThat(issued).containsExactly(
                prefix + buyer + " Yazid Aqel Y. A. true",
                prefix + friend + " Samir Benali S. B. true");
        // No phone in the snapshot: names and holding accounts only.
        String snapshot = jdbc.sql("select holders::text from order_items where order_id = ?::uuid").param(orderId)
                .query(String.class).single();
        assertThat(snapshot).doesNotContain(friendPhone).contains(friend.toString(), buyer.toString());
        assertThat(sold(priceId)).isEqualTo(2);
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("""
                select o.payment_method::text || ' ' || o.payment_ref || ' ' || p.provider_ref || ' ' || (p.completed_at is not null)
                from orders o join payments p on p.order_id = o.id where p.id = ?::uuid
                """).param(paymentId).query(String.class).single())
                .isEqualTo("CARD %s %s true".formatted(approve.get("provider_ref"), approve.get("provider_ref")));
        // The callback kept is the verified verdict only: no signature, nothing card-like.
        assertThat(jdbc.sql("select callback::text from payments where id = ?::uuid").param(paymentId).query(String.class).single())
                .doesNotContain("signature").contains("\"approved\": true");

        // The friend's order view is not theirs: the order belongs to the buyer.
        String friendToken = fanToken(friendPhone);
        fan(friendToken, get("/fan/orders/" + orderId)).andExpect(status().isNotFound());

        // The organizer sees the sale.
        call(org, get("/org/events/" + eventId + "/prices")).andExpect(jsonPath("$[0].sold").value(2));
        // The gateway page and the redirect are gone once the payment is done.
        browse(redirectUrl).andExpect(status().isGone());
        browse(gatewayUrl).andExpect(status().isGone()).andExpect(header().string("Cache-Control", "no-store"));
    }

    private UUID userId(String phone) {
        return jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
    }
}
