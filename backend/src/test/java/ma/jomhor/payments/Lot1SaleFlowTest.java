package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.notifications.application.TicketDeliveryService;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.tickets.TicketPdfApiTest;
import ma.jomhor.tickets.domain.TicketToken;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Lot 1 exit proof (plan §1), on the simulated gateway: an organizer creates and publishes an event through the
 * API; a fan buys 2 named places by card, the second held by a friend's phone, following the same redirect →
 * hosted page → signed callback → /return path a real gateway takes. After PAID each holder holds their ticket
 * (rotating QR key in the app, PDF), each gets it on WhatsApp, and the organizer's sales page (B3) shows the sale.
 */
class Lot1SaleFlowTest extends PaymentTest {

    @Autowired
    EventService events;

    @Autowired
    TicketDeliveryService deliveries;

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
        assertKeys(json, "$", "payment_id", "method", "redirect_url");
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

        // Each holder holds exactly their own ticket, VALID, with K_t = HMAC(K_e, ticket_id) only with with_keys:
        // a rotating (v2) token signed with it verifies against K_e, as the gate will.
        UUID buyerTicket = UUID.fromString(listed);
        UUID friendTicket = ticketHeldBy(orderId, friend);
        Map<String, UUID> held = Map.of(fan, buyerTicket, friendToken, friendTicket);
        for (var e : held.entrySet()) {
            String keyed = body(fan(e.getKey(), get("/fan/tickets?with_keys=true")).andExpect(status().isOk()));
            assertThat(JsonPath.<List<String>>read(keyed, "$.tickets[*].id")).containsExactly(e.getValue().toString());
            assertThat(JsonPath.<String>read(keyed, "$.tickets[0].status")).isEqualTo("VALID");
            byte[] kt = Base64.getUrlDecoder().decode(JsonPath.<String>read(keyed, "$.tickets[0].k_t"));
            assertThat(kt).isEqualTo(events.withEventKey(org.org(), eventId,
                    k -> TicketToken.deriveTicketKey(k, e.getValue())));
            long slot = TicketToken.slotAt(Instant.now());
            String rotating = TicketToken.encodeRotating(kt, eventId, e.getValue(), zoneId, slot);
            boolean rotatingOk = events.withEventKey(org.org(), eventId,
                    k -> TicketToken.verify(TicketToken.decode(rotating), eventId, k, slot).isEmpty());
            assertThat(rotatingOk).isTrue();
            assertThat(body(fan(e.getKey(), get("/fan/tickets")).andExpect(status().isOk()))).doesNotContain("k_t");
        }

        // The friend's PDF: its static (v1) QR decodes to their ticket and verifies against K_e.
        byte[] pdf = fan(friendToken, get("/fan/tickets/" + friendTicket + "/pdf")).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        TicketToken.Parsed printed = TicketToken.decode(TicketPdfApiTest.qr(pdf).getText());
        assertThat(printed.version()).isEqualTo(TicketToken.VERSION_STATIC);
        assertThat(printed.ticketId()).isEqualTo(friendTicket);
        boolean printedOk = events.withEventKey(org.org(), eventId,
                k -> TicketToken.verify(printed, eventId, k, 0).isEmpty());
        assertThat(printedOk).isTrue();

        // WhatsApp: the delivery job sends both tickets, each to its own holder's phone, and marks them SENT.
        deliveries.deliverDue();
        assertThat(jdbc.sql("""
                select d.status::text from ticket_deliveries d join tickets t on t.id = d.ticket_id
                where t.order_id = ?::uuid
                """).param(orderId).query(String.class).list()).containsExactly("SENT", "SENT");
        var messages = ArgumentCaptor.forClass(TicketMessenger.Message.class);
        verify(ticketMessenger, atLeastOnce()).send(messages.capture());
        Map<String, String> phoneByTicketNo = Map.of(TicketToken.ticketNo(buyerTicket), phone,
                TicketToken.ticketNo(friendTicket), friendPhone);
        List<TicketMessenger.Message> ours = messages.getAllValues().stream()
                .filter(m -> phoneByTicketNo.containsKey(m.ticketNo())).toList();
        assertThat(ours).hasSize(2)
                .allSatisfy(m -> assertThat(m.phoneE164()).isEqualTo(phoneByTicketNo.get(m.ticketNo())));

        // B3: the organizer's sales page shows the sale (2 x 50 DH on the app, nothing held).
        call(org, get("/org/events/" + eventId + "/sales")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(2)).andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.revenue_mad").value(10000))
                .andExpect(jsonPath("$.channels[?(@.channel == 'APP')].paid").value(2));
    }

    private UUID ticketHeldBy(String orderId, UUID holder) {
        return jdbc.sql("select id from tickets where order_id = ?::uuid and holder_user_id = ?").params(orderId, holder)
                .query(UUID.class).single();
    }

    private UUID userId(String phone) {
        return jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
    }
}
