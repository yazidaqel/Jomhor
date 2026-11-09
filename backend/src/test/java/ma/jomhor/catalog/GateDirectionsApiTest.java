package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.gate.application.ManifestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract: docs/04-api.md §Organizer POST/PATCH /org/venues/{id}/gates (directions, latitude, longitude), GET
 * /org/venues/{id}, §Fan GET /fan/tickets Lot 4 {@code gates[]}; plan lot-4 BE-32.
 */
class GateDirectionsApiTest extends OrgApiTest {

    /** = the {@code Gate} type of web/backoffice/src/features/venues/api.ts. */
    static final String[] GATE_KEYS = {"id", "code", "name", "directions", "latitude", "longitude", "zone_ids"};
    static final String[] FAN_GATE_KEYS = {"code", "name", "directions", "latitude", "longitude"};

    @Autowired
    ManifestService manifests;

    private ResultActions postGate(Caller c, String venueId, String json) throws Exception {
        return call(c, post("/org/venues/" + venueId + "/gates"), json);
    }

    private ResultActions patchGate(Caller c, String venueId, String gateId, String json) throws Exception {
        return call(c, patch("/org/venues/" + venueId + "/gates/" + gateId), json);
    }

    private String gate(Caller c, String venueId, String code) throws Exception {
        return JsonPath.read(body(postGate(c, venueId, "{\"code\": \"%s\", \"name\": \"Porte %s\"}".formatted(code, code))
                .andExpect(status().isCreated())), "$.id");
    }

    private static void invalid(ResultActions r, String field) throws Exception {
        r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields." + field).exists());
    }

    private Map<String, Object> row(UUID gateId) {
        return jdbc.sql("select directions, latitude, longitude, name from gates where id = ?").param(gateId)
                .query().singleRow();
    }

    // ---------- organizer writes ----------

    @Test
    void postReturnsTheFieldsAndGetCarriesThemAsPlainNumbers() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String json = body(postGate(a, venueId, """
                {"code": "P1", "name": "Porte 1", "directions": "  Entrée côté parvis  ", "latitude": 33.123456,
                 "longitude": -7.654321}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.directions").value("Entrée côté parvis")));
        assertKeys(json, "$", GATE_KEYS);
        assertThat(json).contains("\"latitude\":33.123456").contains("\"longitude\":-7.654321");

        String detail = body(call(a, get("/org/venues/" + venueId)).andExpect(status().isOk()));
        assertKeys(detail, "$.gates[0]", GATE_KEYS);
        assertThat(JsonPath.<String>read(detail, "$.gates[0].directions")).isEqualTo("Entrée côté parvis");
        assertThat(detail).contains("\"latitude\":33.123456").contains("\"longitude\":-7.654321");

        // Nothing optional sent, or "" / blank directions: all null.
        for (String extra : List.of("", ", \"directions\": \"\"", ", \"directions\": \"   \"",
                ", \"latitude\": null, \"longitude\": null")) {
            String code = "Q" + UUID.randomUUID().toString().substring(0, 4);
            String bare = body(postGate(a, venueId, "{\"code\": \"%s\", \"name\": \"N\"%s}".formatted(code, extra))
                    .andExpect(status().isCreated()));
            assertKeys(bare, "$", GATE_KEYS);
            assertThat(JsonPath.<Object>read(bare, "$.directions")).as(extra).isNull();
            assertThat(JsonPath.<Object>read(bare, "$.latitude")).as(extra).isNull();
            assertThat(JsonPath.<Object>read(bare, "$.longitude")).as(extra).isNull();
        }
    }

    @Test
    void directionsUpTo200CharsAndEmptyClearsOnPatch() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String g = gate(a, venueId, "P1");
        UUID gateId = UUID.fromString(g);

        patchGate(a, venueId, g, "{\"directions\": \"%s\"}".formatted("d".repeat(200))).andExpect(status().isOk());
        assertThat(row(gateId).get("directions")).isEqualTo("d".repeat(200));
        invalid(patchGate(a, venueId, g, "{\"directions\": \"%s\"}".formatted("d".repeat(201))), "directions");
        invalid(postGate(a, venueId, "{\"code\": \"P9\", \"name\": \"N\", \"directions\": \"%s\"}"
                .formatted("d".repeat(201))), "directions");

        patchGate(a, venueId, g, "{\"directions\": \"Allée des Palmiers\"}").andExpect(status().isOk());
        // absent: unchanged
        patchGate(a, venueId, g, "{\"name\": \"Porte Un\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.directions").value("Allée des Palmiers"));
        // explicit null: unchanged too (the null-unchanged rule)
        patchGate(a, venueId, g, "{\"directions\": null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.directions").value("Allée des Palmiers"));
        // "": cleared
        patchGate(a, venueId, g, "{\"directions\": \"\"}").andExpect(status().isOk());
        assertThat(row(gateId).get("directions")).isNull();
    }

    @Test
    void coordinatesBothOrNeitherInRangeAndAtMostSixDecimals() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String g = gate(a, venueId, "P1");
        UUID gateId = UUID.fromString(g);

        invalid(postGate(a, venueId, "{\"code\": \"P2\", \"name\": \"N\", \"latitude\": 33.5}"), "latitude");
        invalid(postGate(a, venueId, "{\"code\": \"P2\", \"name\": \"N\", \"longitude\": -7.5}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": 33.5}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"longitude\": -7.5}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": null}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"longitude\": null}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": null, \"longitude\": -7.5}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": 33.5, \"longitude\": null}"), "latitude");

        invalid(patchGate(a, venueId, g, "{\"latitude\": 33.1234567, \"longitude\": -7.5}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": 33.5, \"longitude\": -7.1234567}"), "longitude");
        invalid(postGate(a, venueId, "{\"code\": \"P2\", \"name\": \"N\", \"latitude\": 33.1234567, \"longitude\": 1}"),
                "latitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": 90.000001, \"longitude\": 0}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": -90.000001, \"longitude\": 0}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": 0, \"longitude\": -180.000001}"), "longitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": 0, \"longitude\": 180.000001}"), "longitude");
        assertThat(row(gateId).get("latitude")).isNull();

        // The bounds themselves are fine; a trailing zero past the 6th decimal is not a 7th decimal.
        patchGate(a, venueId, g, "{\"latitude\": 90, \"longitude\": -180}").andExpect(status().isOk());
        patchGate(a, venueId, g, "{\"latitude\": 33.1234560, \"longitude\": -7.6543210}").andExpect(status().isOk());
        assertThat((BigDecimal) row(gateId).get("latitude")).isEqualByComparingTo("33.123456");

        // Absent coordinates leave the location; both explicit nulls clear it.
        patchGate(a, venueId, g, "{\"name\": \"Porte Un\"}").andExpect(status().isOk());
        assertThat((BigDecimal) row(gateId).get("longitude")).isEqualByComparingTo("-7.654321");
        patchGate(a, venueId, g, "{\"latitude\": null, \"longitude\": null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(nullValue())).andExpect(jsonPath("$.longitude").value(nullValue()));
        assertThat(row(gateId).get("latitude")).isNull();
        assertThat(row(gateId).get("longitude")).isNull();
    }

    @Test
    void coordinatesMustBeJsonNumbers() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String g = gate(a, venueId, "P1");
        invalid(postGate(a, venueId, "{\"code\": \"P2\", \"name\": \"N\", \"latitude\": \"33.5\", \"longitude\": -7.5}"),
                "latitude");
        invalid(postGate(a, venueId, "{\"code\": \"P2\", \"name\": \"N\", \"latitude\": 33.5, \"longitude\": \"-7.5\"}"),
                "longitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": \"33.5\", \"longitude\": -7.5}"), "latitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": 33.5, \"longitude\": true}"), "longitude");
        invalid(patchGate(a, venueId, g, "{\"latitude\": [33.5], \"longitude\": -7.5}"), "latitude");
        assertThat(row(UUID.fromString(g)).get("latitude")).isNull();
        assertThat(jdbc.sql("select count(*) from gates where venue_id = ?::uuid").param(venueId).query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void numbersRoundTripExactlyAndStayPlain() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String g = gate(a, venueId, "P1");
        String json = body(patchGate(a, venueId, g, "{\"latitude\": 33.123456, \"longitude\": -7.000001}")
                .andExpect(status().isOk()));
        assertThat(json).contains("\"latitude\":33.123456").contains("\"longitude\":-7.000001");
        json = body(patchGate(a, venueId, g, "{\"latitude\": 0.000001, \"longitude\": -0.000001}")
                .andExpect(status().isOk()));
        assertThat(json).contains("\"latitude\":0.000001").contains("\"longitude\":-0.000001").doesNotContain("E-");
        String detail = body(call(a, get("/org/venues/" + venueId)).andExpect(status().isOk()));
        assertThat(detail).contains("\"latitude\":0.000001").contains("\"longitude\":-0.000001").doesNotContain("E-");
    }

    @Test
    void directionsAndCoordinatesBuildNoNewManifestVersionButARenameDoes() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int z = zone(a, venueId, "NORD");
        String g = gate(a, venueId, "P1");
        call(a, put("/org/gates/" + g + "/zones"), "{\"zone_ids\": [%d]}".formatted(z)).andExpect(status().isOk());
        int eventId = event(a, venueId, Instant.now().plus(Duration.ofHours(1)).toString());
        price(a, eventId, z, "Normal", 10);
        setStatus(eventId, "ON_SALE");
        assertThat(manifests.build(a.org(), eventId)).contains(1);
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(eventId)
                .update();

        patchGate(a, venueId, g, "{\"directions\": \"Par l'esplanade\", \"latitude\": 33.5, \"longitude\": -7.6}")
                .andExpect(status().isOk());
        assertThat(manifests.build(a.org(), eventId)).isEmpty();
        patchGate(a, venueId, g, "{\"name\": \"Porte Nord\"}").andExpect(status().isOk());
        assertThat(manifests.build(a.org(), eventId)).contains(2);
    }

    @Test
    void financeCannotPatchAndAnotherOrganizersGateIsNotFound() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String g = gate(a, venueId, "P1");
        Caller finance = caller(a.org(), "FINANCE");
        patchGate(finance, venueId, g, "{\"directions\": \"x\"}").andExpect(status().isForbidden());
        Caller b = admin();
        patchGate(b, venueId, g, "{\"directions\": \"x\", \"latitude\": 1, \"longitude\": 1}")
                .andExpect(status().isNotFound());
        String bVenue = venue(b, "Autre");
        patchGate(b, bVenue, g, "{\"directions\": \"x\"}").andExpect(status().isNotFound());
        assertThat(row(UUID.fromString(g)).get("directions")).isNull();
    }

    // ---------- fan reads ----------

    private UUID fanUser(String phone) {
        return jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
    }

    /** A PAID order's ticket of {@code zoneId} held by {@code holder}. */
    private UUID heldTicket(Caller c, int eventId, int zoneId, String priceId, UUID holder) {
        UUID orderId = jdbc.sql("insert into orders (organizer_id, event_id, channel, status, total_mad, reserved_until) "
                        + "values (?, ?, 'WEB', 'PAID', 0, now()) returning id")
                .params(c.org(), eventId).query(UUID.class).single();
        return jdbc.sql("insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, "
                        + "holder_user_id, holder_name, holder_initials) values (?, ?, ?, ?, ?::uuid, ?, 'Fan Un', 'F. U.') "
                        + "returning id")
                .params(c.org(), orderId, eventId, zoneId, priceId, holder).query(UUID.class).single();
    }

    private String fanTickets(String token) throws Exception {
        return body(mvc.perform(get("/fan/tickets").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
    }

    @Test
    void fanTicketsListTheGatesOfTheTicketsZoneAndPublicEventsDoNot() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int sud = zone(a, venueId, "SUD");
        String p2 = gate(a, venueId, "P2");
        String p1 = gate(a, venueId, "P1");
        String p3 = gate(a, venueId, "P3"); // serves no zone of the event's tickets
        for (String g : List.of(p1, p2)) {
            call(a, put("/org/gates/" + g + "/zones"), "{\"zone_ids\": [%d]}".formatted(nord)).andExpect(status().isOk());
        }
        patchGate(a, venueId, p2, "{\"directions\": \"Allée des Palmiers\", \"latitude\": 33.123456, "
                + "\"longitude\": -7.000001}").andExpect(status().isOk());
        patchGate(a, venueId, p3, "{\"directions\": \"Ne pas lister\"}").andExpect(status().isOk());
        int eventId = event(a, venueId);
        String nordPrice = price(a, eventId, nord, "Nord", 10);
        String sudPrice = price(a, eventId, sud, "Sud", 10);
        setStatus(eventId, "ON_SALE");
        String phone = randomPhone();
        String token = fanToken(phone);
        UUID nordTicket = heldTicket(a, eventId, nord, nordPrice, fanUser(phone));
        UUID sudTicket = heldTicket(a, eventId, sud, sudPrice, fanUser(phone));

        String json = fanTickets(token);
        String n = "$.tickets[?(@.id == '%s')]".formatted(nordTicket);
        String s = "$.tickets[?(@.id == '%s')]".formatted(sudTicket);
        assertThat(JsonPath.<List<String>>read(json, n + ".gates[*].code")).containsExactly("P1", "P2");
        assertThat(JsonPath.<List<String>>read(json, n + ".gate_codes[*]")).containsExactly("P1", "P2");
        List<Map<String, Object>> gates = JsonPath.read(json, n + ".gates[*]");
        gates.forEach(g -> assertThat(g.keySet()).containsExactlyInAnyOrder(FAN_GATE_KEYS));
        assertThat(gates.get(0).get("directions")).isNull();
        assertThat(gates.get(0).get("latitude")).isNull();
        assertThat(gates.get(1)).containsEntry("name", "Porte P2").containsEntry("directions", "Allée des Palmiers");
        assertThat(json).contains("\"latitude\":33.123456").contains("\"longitude\":-7.000001")
                .doesNotContain("Ne pas lister");
        assertThat(JsonPath.<List<Object>>read(json, s + ".gates[*]")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(json, s + ".gate_codes[*]")).isEmpty();
        assertThat(JsonPath.<List<List<Object>>>read(json, s + ".gates")).containsExactly(List.of());

        String event = body(mvc.perform(get("/fan/events/" + eventId)).andExpect(status().isOk()));
        assertKeys(event, "$.zones[0]", "id", "code", "name", "seated", "gate_codes", "prices");
        assertThat(event).doesNotContain("directions").doesNotContain("latitude").doesNotContain("Palmiers");
    }

    @Test
    void multiClubEachTicketListsOnlyItsOwnVenuesGates() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        UUID fan = fanUser(phone);
        UUID[] tickets = new UUID[2];
        String[] names = {"Atlas", "Tensift"};
        for (int i = 0; i < 2; i++) {
            Caller c = admin();
            String venueId = venue(c, "Stade " + names[i]);
            int z = zone(c, venueId, "NORD");
            String g = gate(c, venueId, "P1");
            call(c, put("/org/gates/" + g + "/zones"), "{\"zone_ids\": [%d]}".formatted(z)).andExpect(status().isOk());
            patchGate(c, venueId, g, "{\"directions\": \"Chez %s\"}".formatted(names[i])).andExpect(status().isOk());
            int eventId = event(c, venueId);
            String priceId = price(c, eventId, z, "Normal", 10);
            setStatus(eventId, "ON_SALE");
            tickets[i] = heldTicket(c, eventId, z, priceId, fan);
        }
        String json = fanTickets(token);
        for (int i = 0; i < 2; i++) {
            String t = "$.tickets[?(@.id == '%s')]".formatted(tickets[i]);
            assertThat(JsonPath.<List<String>>read(json, t + ".gates[*].code")).containsExactly("P1");
            assertThat(JsonPath.<List<String>>read(json, t + ".gates[*].directions")).containsExactly("Chez " + names[i]);
        }
    }
}
