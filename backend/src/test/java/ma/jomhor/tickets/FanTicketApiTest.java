package ma.jomhor.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.tickets.domain.TicketToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Contract: GET /fan/tickets?with_keys= (docs/04-api.md §Fan; plan BE-12, §6 K_t/K_e). Holder only, k_t only with
 * with_keys for VALID tickets of events not CLOSED, k_t = HMAC(K_e, ticket_id) per organizer, no K_e anywhere.
 */
class FanTicketApiTest extends PaymentTest {

    private static final String[] TICKET_KEYS =
            {"id", "ticket_no", "status", "holder_label", "seat_label", "event", "zone", "gate_codes"};
    private static final String[] EVENT_KEYS =
            {"id", "title", "event_type", "status", "starts_at", "doors_open_at", "timezone", "venue", "metadata"};

    @Autowired
    EventService events;

    /** A published event starting at {@code startsAt}, one category on zone NORD, gates P2 and P1 on that zone. */
    private Sale sale(Caller org, String startsAt) throws Exception {
        String venueId = venue(org, "Grand Stade");
        int zoneId = zone(org, venueId, "NORD");
        for (String code : List.of("P2", "P1")) {
            String gate = JsonPath.read(body(call(org, post("/org/venues/" + venueId + "/gates"),
                    "{\"code\": \"%s\", \"name\": \"Porte %s\"}".formatted(code, code))
                    .andExpect(status().isCreated())), "$.id");
            call(org, put("/org/gates/" + gate + "/zones"), "{\"zone_ids\": [%d]}".formatted(zoneId))
                    .andExpect(status().isOk());
        }
        int eventId = event(org, venueId, startsAt);
        String priceId = price(org, eventId, zoneId, "Plein tarif", 100);
        call(org, post("/org/events/" + eventId + "/publish")).andExpect(status().isOk());
        return new Sale(org, zoneId, eventId, priceId);
    }

    private Sale sale(String startsAt) throws Exception {
        return sale(admin(), startsAt);
    }

    /** Orders {@code holders} (a POST /fan/orders holders array) and approves the card payment. */
    private String buy(String token, Sale s, int qty, String holders) throws Exception {
        String orderId = JsonPath.read(body(fan(token, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}
                """.formatted(s.eventId(), s.priceId(), qty, holders)).andExpect(status().isCreated())), "$.id");
        callback(approve(payment(token, orderId), total(orderId))).andExpect(status().isSeeOther());
        return orderId;
    }

    private String buy(String token, Sale s) throws Exception {
        return buy(token, s, 1, holders(1));
    }

    private String tickets(String token, String query) throws Exception {
        return body(fan(token, get("/fan/tickets" + query)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
    }

    private byte[] ticketKey(UUID org, int eventId, UUID ticketId) {
        return events.withEventKey(org, eventId, k -> TicketToken.deriveTicketKey(k, ticketId));
    }

    private static byte[] b64url(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    @Test
    void theHolderSeesOnlyTheTicketsTheyHold() throws Exception {
        Sale s = sale("2099-05-01T19:00:00Z");
        String buyerPhone = randomPhone();
        String friendPhone = randomPhone();
        String buyer = fanToken(buyerPhone);
        buy(buyer, s, 2, """
                [{"name": "Yazid Aqel"}, {"name": "Samir Benali", "phone": "%s"}]""".formatted(friendPhone));

        Instant before = Instant.now();
        String json = tickets(buyer, "");
        assertThat(Instant.parse(JsonPath.read(json, "$.server_time"))).isCloseTo(before, within(5, ChronoUnit.SECONDS));
        assertKeys(json, "$", "server_time", "tickets");
        assertThat(JsonPath.<List<Object>>read(json, "$.tickets")).hasSize(1);
        assertKeys(json, "$.tickets[0]", TICKET_KEYS);
        assertKeys(json, "$.tickets[0].event", EVENT_KEYS);
        assertKeys(json, "$.tickets[0].event.venue", "name", "city");
        assertKeys(json, "$.tickets[0].zone", "id", "code", "name");
        String id = JsonPath.read(json, "$.tickets[0].id");
        assertThat(JsonPath.<String>read(json, "$.tickets[0].status")).isEqualTo("VALID");
        assertThat(JsonPath.<String>read(json, "$.tickets[0].holder_label")).isEqualTo("Yazid A.");
        String ticketNo = JsonPath.read(json, "$.tickets[0].ticket_no");
        assertThat(ticketNo).matches("[0-9A-F]{4} [0-9A-F]{4}")
                .isEqualTo((id.substring(28, 32) + " " + id.substring(32)).toUpperCase());
        assertThat(JsonPath.<Integer>read(json, "$.tickets[0].event.id")).isEqualTo(s.eventId());
        assertThat(JsonPath.<String>read(json, "$.tickets[0].event.event_type")).isEqualTo("FOOTBALL_MATCH");
        assertThat(JsonPath.<String>read(json, "$.tickets[0].event.status")).isEqualTo("ON_SALE");
        assertThat(JsonPath.<String>read(json, "$.tickets[0].event.venue.name")).isEqualTo("Grand Stade");
        assertThat(JsonPath.<Integer>read(json, "$.tickets[0].zone.id")).isEqualTo(s.zoneId());
        assertThat(JsonPath.<String>read(json, "$.tickets[0].zone.code")).isEqualTo("NORD");
        assertThat(JsonPath.<String>read(json, "$.tickets[0].zone.name")).isEqualTo("Zone NORD");
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[0].gate_codes")).containsExactly("P1", "P2");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.tickets[0].event.metadata")).isEmpty();
        // Nothing of the friend's: no name, phone, ticket or account.
        UUID friendTicket = jdbc.sql("select t.id from tickets t join users u on u.id = t.holder_user_id"
                + " where u.phone_e164 = ?").param(friendPhone).query(UUID.class).single();
        assertThat(json).doesNotContain("Benali").doesNotContain("Samir").doesNotContain(friendPhone)
                .doesNotContain(friendTicket.toString()).doesNotContain("Aqel");

        // The friend sees theirs, with its key when asking for keys; the buyer never gets the friend's key.
        String friend = tickets(fanToken(friendPhone), "?with_keys=true");
        assertThat(JsonPath.<List<String>>read(friend, "$.tickets[*].id")).containsExactly(friendTicket.toString());
        assertThat(JsonPath.<String>read(friend, "$.tickets[0].holder_label")).isEqualTo("Samir B.");
        String buyerKeyed = tickets(buyer, "?with_keys=true");
        assertThat(JsonPath.<List<String>>read(buyerKeyed, "$.tickets[*].id")).containsExactly(id);
        String friendKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ticketKey(s.org().org(), s.eventId(), friendTicket));
        assertThat(buyerKeyed).doesNotContain(friendKey);
        assertThat(JsonPath.<String>read(friend, "$.tickets[0].k_t")).isEqualTo(friendKey);
    }

    @Test
    void kTIsHmacOfTheEventKeyAndSignsAVerifiableV2Token() throws Exception {
        Sale s = sale("2099-05-01T19:00:00Z");
        String fan = fanToken(randomPhone());
        buy(fan, s);
        String json = tickets(fan, "?with_keys=true");
        assertKeys(json, "$.tickets[0]", concat(TICKET_KEYS, "k_t"));
        UUID ticketId = UUID.fromString(JsonPath.read(json, "$.tickets[0].id"));
        String kt = JsonPath.read(json, "$.tickets[0].k_t");
        assertThat(kt).matches("[A-Za-z0-9_-]{43}");
        byte[] key = b64url(kt);
        assertThat(key).hasSize(32).isEqualTo(ticketKey(s.org().org(), s.eventId(), ticketId));

        long slot = TicketToken.slotAt(Instant.now());
        String token = TicketToken.encodeRotating(key, s.eventId(), ticketId, s.zoneId(), slot);
        boolean verified = events.withEventKey(s.org().org(), s.eventId(),
                k -> TicketToken.verify(TicketToken.decode(token), s.eventId(), k, slot).isEmpty());
        assertThat(verified).isTrue();

        // No event key in any form: no field name, not its bytes in base64 or base64url.
        byte[] eventKey = events.withEventKey(s.org().org(), s.eventId(), byte[]::clone);
        assertThat(json.toLowerCase()).doesNotContain("k_e").doesNotContain("event_key").doesNotContain("eventkey");
        assertThat(json).doesNotContain(Base64.getEncoder().withoutPadding().encodeToString(eventKey))
                .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(eventKey));
    }

    @Test
    void noKeyWithoutWithKeysNorForATicketNotValidNorAClosedEvent() throws Exception {
        Caller org = admin();
        Sale may = sale(org, "2099-05-01T19:00:00Z");
        Sale june = sale(org, "2099-06-01T19:00:00Z");
        Sale april = sale(org, "2099-04-01T19:00:00Z");
        Sale march = sale(org, "2099-03-01T19:00:00Z");
        String fan = fanToken(randomPhone());
        buy(fan, may, 2, holders(2));
        buy(fan, june);
        buy(fan, april);
        buy(fan, march);
        UUID blocked = jdbc.sql("select id from tickets where event_id = ? order by id limit 1").param(may.eventId())
                .query(UUID.class).single();
        jdbc.sql("update tickets set status = 'BLOCKED' where id = ?").param(blocked).update();
        setStatus(april.eventId(), "CLOSED");
        setStatus(march.eventId(), "CLOSED");

        for (String query : List.of("", "?with_keys=false", "?with_keys=", "?with_keys=1", "?with_keys=TRUE",
                "?with_keys=True", "?with_keys=yes", "?with_keys=on", "?with_keys=maybe")) {
            String json = tickets(fan, query);
            assertThat(json).as(query).doesNotContain("k_t");
            assertThat(JsonPath.<List<Object>>read(json, "$.tickets")).hasSize(5);
        }

        String json = tickets(fan, "?with_keys=true");
        // Not CLOSED by starts_at (May, June), then CLOSED latest first (April, March).
        assertThat(JsonPath.<List<Integer>>read(json, "$.tickets[*].event.id")).containsExactly(may.eventId(),
                may.eventId(), june.eventId(), april.eventId(), march.eventId());
        List<Map<String, Object>> tickets = JsonPath.read(json, "$.tickets");
        for (Map<String, Object> t : tickets) {
            boolean keyed = t.get("id").equals(blocked.toString()) ? false
                    : !List.of(april.eventId(), march.eventId()).contains(((Map<?, ?>) t.get("event")).get("id"));
            assertThat(t.containsKey("k_t")).as("k_t on %s", t.get("id")).isEqualTo(keyed);
        }
        assertThat(tickets.stream().filter(t -> t.get("id").equals(blocked.toString())).findFirst().orElseThrow()
                .get("status")).isEqualTo("BLOCKED");
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[*].event.status"))
                .containsExactly("ON_SALE", "ON_SALE", "ON_SALE", "CLOSED", "CLOSED");
    }

    /** Past = CLOSED or started more than 12 h ago (database clock); past latest first, after the upcoming ones. */
    @Test
    void anEventThatStartedMoreThan12HoursAgoIsPast() throws Exception {
        Caller org = admin();
        Sale justPast = sale(org, "2099-05-01T19:00:00Z");
        Sale stillUpcoming = sale(org, "2099-05-01T19:00:00Z");
        Sale longPast = sale(org, "2099-05-01T19:00:00Z");
        Sale future = sale(org, "2099-05-01T19:00:00Z");
        String fan = fanToken(randomPhone());
        for (Sale s : List.of(justPast, stillUpcoming, longPast, future)) {
            buy(fan, s);
        }
        startsAgo(justPast, "12 hours 1 minute");
        startsAgo(stillUpcoming, "11 hours 59 minutes");
        startsAgo(longPast, "3 days");

        String json = tickets(fan, "?with_keys=true");
        assertThat(JsonPath.<List<Integer>>read(json, "$.tickets[*].event.id")).containsExactly(
                stillUpcoming.eventId(), future.eventId(), justPast.eventId(), longPast.eventId());
        // Past by time only: not CLOSED, so the key is still served (04: k_t unless CLOSED).
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[*].k_t")).hasSize(4);
    }

    private void startsAgo(Sale s, String interval) {
        jdbc.sql("update events set starts_at = now() - cast(? as interval) where id = ?").params(interval, s.eventId())
                .update();
    }

    @Test
    void eachOrganizerTicketUsesItsOwnEventKey() throws Exception {
        Sale a = sale("2099-05-01T19:00:00Z");
        Sale b = sale("2099-05-02T19:00:00Z");
        assertThat(a.org().org()).isNotEqualTo(b.org().org());
        String fan = fanToken(randomPhone());
        buy(fan, a);
        buy(fan, b);
        String json = tickets(fan, "?with_keys=true");
        for (Sale s : List.of(a, b)) {
            Sale other = s == a ? b : a;
            UUID id = UUID.fromString(JsonPath.<List<String>>read(json,
                    "$.tickets[?(@.event.id == %d)].id".formatted(s.eventId())).getFirst());
            byte[] kt = b64url(JsonPath.<List<String>>read(json,
                    "$.tickets[?(@.event.id == %d)].k_t".formatted(s.eventId())).getFirst());
            assertThat(kt).isEqualTo(ticketKey(s.org().org(), s.eventId(), id))
                    .isNotEqualTo(ticketKey(other.org().org(), other.eventId(), id));
        }
    }

    @Test
    void aFanTokenIsRequired() throws Exception {
        mvc.perform(get("/fan/tickets")).andExpect(status().isUnauthorized());
        Caller org = admin();
        mvc.perform(get("/fan/tickets").header("Authorization", "Bearer " + org.token()))
                .andExpect(status().isUnauthorized());
        String json = tickets(fanToken(randomPhone()), "?with_keys=true");
        assertThat(JsonPath.<List<Object>>read(json, "$.tickets")).isEmpty();

        // A logged-out session's token no longer reads tickets (plan Q13).
        String revoked = fanToken(randomPhone());
        tickets(revoked, "?with_keys=true");
        fan(revoked, post("/fan/me/logout")).andExpect(status().isNoContent());
        fan(revoked, get("/fan/tickets?with_keys=true")).andExpect(status().isUnauthorized());
    }

    /** A Lot 0 ticket has no holder name: holder_label is JSON null, the key still present. */
    @Test
    void aLot0TicketHasANullHolderLabel() throws Exception {
        Sale s = sale("2099-05-01T19:00:00Z");
        String fan = fanToken(randomPhone());
        String orderId = buy(fan, s);
        jdbc.sql("update tickets set holder_name = null, holder_initials = null where order_id = ?::uuid")
                .param(orderId).update();
        String json = tickets(fan, "");
        assertKeys(json, "$.tickets[0]", TICKET_KEYS);
        assertThat(json).contains("\"holder_label\":null");
        assertThat(JsonPath.<Object>read(json, "$.tickets[0].holder_label")).isNull();
    }

    private static String[] concat(String[] keys, String extra) {
        String[] out = java.util.Arrays.copyOf(keys, keys.length + 1);
        out[keys.length] = extra;
        return out;
    }
}
