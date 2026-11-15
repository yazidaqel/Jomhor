package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.OrgApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-51): docs/04-api.md §Fan GET /fan/products and /fan/products/{id}: public (no token, no cache headers),
 * on-sale products only, the PASS block (only the series' published, upcoming events at the zone's venue), plans in
 * full, payment methods and cash_until from the product's sales_close_at.
 */
class FanProductApiTest extends OrgApiTest {

    private static final String[] ROW_KEYS = {"id", "organizer_id", "organizer", "timezone", "kind", "name",
            "price_mad", "sales_close_at",
            "remaining", "sold_out", "valid_until", "pass", "plans", "payment_methods", "cash_until"};
    private static final String CLOSE = "2099-06-30T22:00:00Z";

    record Club(Caller admin, String series, int zone, List<Integer> listed, String pass, String membership,
                String hidden) {}

    private String seriesOf(Caller a, String name) throws Exception {
        return JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"%s\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}".formatted(name))
                .andExpect(status().isCreated())), "$.id");
    }

    /** A match of the series at the venue, published with one ALL category in the zone. */
    private int match(Caller a, String venue, int zone, String series, String startsAt, boolean publish)
            throws Exception {
        int id = JsonPath.read(body(call(a, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match %s", "starts_at": "%s"%s}"""
                .formatted(venue, startsAt, startsAt, series == null ? "" : ", \"series_id\": \"" + series + "\""))
                .andExpect(status().isCreated())), "$.id");
        price(a, id, zone, "Plein tarif", 1);
        if (publish) {
            call(a, post("/org/events/" + id + "/publish")).andExpect(status().isOk());
        }
        return id;
    }

    private String product(Caller a, String body) throws Exception {
        return JsonPath.read(body(call(a, post("/org/products"), body).andExpect(status().isCreated())), "$.id");
    }

    private Club club() throws Exception {
        Caller a = admin();
        String stade = venue(a, "Stade");
        jdbc.sql("update venues set city = 'Rabat' where id = ?::uuid").param(stade).update();
        int est = zone(a, stade, "EST");
        String other = venue(a, "Salle");
        int salle = zone(a, other, "NORD");
        String series = seriesOf(a, "Saison 2026-27");
        String coupe = seriesOf(a, "Coupe");
        int second = match(a, stade, est, series, "2099-03-01T19:00:00Z", true);
        int first = match(a, stade, est, series, "2099-02-01T19:00:00Z", true);
        match(a, stade, est, series, "2099-04-01T19:00:00Z", false);   // DRAFT
        match(a, other, salle, series, "2099-02-15T19:00:00Z", true);  // another venue
        match(a, stade, est, coupe, "2099-02-20T19:00:00Z", true);     // another series
        match(a, stade, est, null, "2099-02-21T19:00:00Z", true);      // no series
        int started = match(a, stade, est, series, "2099-01-01T19:00:00Z", true);
        jdbc.sql("update events set starts_at = now() - interval '1 hour', status = 'GATES_OPEN' where id = ?")
                .param(started).update();                                    // under way: not upcoming
        int closed = match(a, stade, est, series, "2099-01-15T19:00:00Z", true);
        setStatus(closed, "CLOSED");
        int gatesOpen = match(a, stade, est, series, "2099-02-10T19:00:00Z", true);
        setStatus(gatesOpen, "GATES_OPEN");

        String pass = product(a, """
                {"kind": "PASS", "name": "Abonnement Tribune Est", "price_mad": 150000, "quota": 3, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "%s", "on_sale": true}""".formatted(series, est, CLOSE));
        String membership = product(a, """
                {"kind": "MEMBERSHIP", "name": "Carte membre", "price_mad": 20000, "valid_until": "2099-07-01T00:00:00Z",
                 "sales_close_at": "%s", "on_sale": true}""".formatted(CLOSE));
        String hidden = product(a, """
                {"kind": "PASS", "name": "Abonnement Virage", "price_mad": 100000, "quota": 3, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "%s"}""".formatted(series, est, CLOSE));
        return new Club(a, series, est, List.of(first, gatesOpen, second), pass, membership, hidden);
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get("/fan/products" + query));
    }

    @Test
    void publicListOfOnSaleProducts() throws Exception {
        Club c = club();
        String json = body(list("?organizer=" + c.admin().org()).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Cache-Control"))
                .andExpect(header().doesNotExist("Pragma"))
                .andExpect(header().doesNotExist("Expires")));
        assertThat(JsonPath.<List<String>>read(json, "$[*].id")).containsExactly(c.pass(), c.membership());
        assertKeys(json, "$[0]", ROW_KEYS);
        assertKeys(json, "$[1]", ROW_KEYS);

        // PASS row.
        assertThat(JsonPath.<String>read(json, "$[0].organizer_id")).isEqualTo(c.admin().org().toString());
        assertKeys(json, "$[0].organizer", "slug", "name", "crest_url");
        assertThat(JsonPath.<String>read(json, "$[0].organizer.name")).startsWith("Org ");
        assertThat(JsonPath.<Object>read(json, "$[0].organizer.slug")).isNull(); // unlisted: still answered
        assertThat(JsonPath.<String>read(json, "$[0].timezone")).isEqualTo("Africa/Casablanca"); // the zone venue's
        assertThat(JsonPath.<String>read(json, "$[0].kind")).isEqualTo("PASS");
        assertThat(JsonPath.<Integer>read(json, "$[0].price_mad")).isEqualTo(150000);
        assertThat(JsonPath.<Integer>read(json, "$[0].remaining")).isEqualTo(3);
        assertThat(JsonPath.<Boolean>read(json, "$[0].sold_out")).isFalse();
        assertThat(JsonPath.<Object>read(json, "$[0].valid_until")).isNull();
        assertThat(JsonPath.<List<Map<String, Object>>>read(json, "$[0].plans"))
                .containsExactly(Map.of("installments", 1, "amounts_mad", List.of(150000)));
        assertKeys(json, "$[0].pass", "series", "zone", "venue", "events");
        assertKeys(json, "$[0].pass.series", "id", "name", "starts_on", "ends_on");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$[0].pass.series")).containsEntry("id", c.series())
                .containsEntry("name", "Saison 2026-27").containsEntry("starts_on", "2026-08-01")
                .containsEntry("ends_on", "2099-06-30");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$[0].pass.zone"))
                .isEqualTo(Map.of("id", c.zone(), "code", "EST", "name", "Zone EST"));
        assertThat(JsonPath.<Map<String, Object>>read(json, "$[0].pass.venue"))
                .isEqualTo(Map.of("name", "Stade", "city", "Rabat", "timezone", "Africa/Casablanca"));
        // Published (ON_SALE or GATES_OPEN), upcoming, at the zone's venue, of the series, by starts_at.
        assertThat(JsonPath.<List<Integer>>read(json, "$[0].pass.events[*].id")).isEqualTo(c.listed());
        assertKeys(json, "$[0].pass.events[0]", "id", "title", "event_type", "starts_at");
        assertThat(JsonPath.<String>read(json, "$[0].pass.events[0].starts_at")).isEqualTo("2099-02-01T19:00:00Z");
        assertThat(JsonPath.<String>read(json, "$[0].pass.events[0].event_type")).isEqualTo("FOOTBALL_MATCH");

        // MEMBERSHIP row.
        assertThat(JsonPath.<String>read(json, "$[1].timezone")).isEqualTo("Africa/Casablanca");
        assertThat(JsonPath.<Object>read(json, "$[1].remaining")).isNull();
        assertThat(JsonPath.<Boolean>read(json, "$[1].sold_out")).isFalse();
        assertThat(JsonPath.<String>read(json, "$[1].valid_until")).isEqualTo("2099-07-01T00:00:00Z");
        assertThat(JsonPath.<Object>read(json, "$[1].pass")).isNull();
        assertThat(JsonPath.<List<Map<String, Object>>>read(json, "$[1].plans"))
                .containsExactly(Map.of("installments", 1, "amounts_mad", List.of(20000)));

        // No cash set up: card only, no cash_until.
        assertThat(JsonPath.<List<String>>read(json, "$[0].payment_methods")).containsExactly("CARD");
        assertThat(JsonPath.<Object>read(json, "$[0].cash_until")).isNull();

        list("?organizer=%s&kind=MEMBERSHIP".formatted(c.admin().org()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(c.membership()));
        list("?organizer=%s&kind=PASS".formatted(c.admin().org()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(c.pass()));
        // A token is ignored on the public chain.
        mvc.perform(get("/fan/products?organizer=" + c.admin().org()).header("Authorization", "Bearer garbage"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void detailAndNotFound() throws Exception {
        Club c = club();
        String row = body(list("?organizer=" + c.admin().org()));
        String json = body(mvc.perform(get("/fan/products/" + c.pass())).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Cache-Control")));
        assertKeys(json, "$", ROW_KEYS);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$"))
                .isEqualTo(JsonPath.<Map<String, Object>>read(row, "$[0]"));
        mvc.perform(get("/fan/products/" + c.hidden())).andExpect(status().isNotFound());
        mvc.perform(get("/fan/products/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/fan/products/not-a-uuid")).andExpect(status().isBadRequest());

        String slug = listed(c.admin().org());
        json = body(mvc.perform(get("/fan/products/" + c.membership())).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.organizer.slug")).isEqualTo(slug);
    }

    /** A PASS row's timezone is its zone venue's. */
    @Test
    void timezoneIsTheZoneVenues() throws Exception {
        Club c = club();
        jdbc.sql("update venues set timezone = 'Europe/Paris' where id = (select venue_id from zones where id = ?)")
                .param(c.zone()).update();
        String json = body(mvc.perform(get("/fan/products/" + c.pass())).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.timezone")).isEqualTo("Europe/Paris");
        assertThat(JsonPath.<String>read(json, "$.pass.venue.timezone")).isEqualTo("Europe/Paris");
    }

    @Test
    void organizerAndKindAreChecked() throws Exception {
        Club c = club();
        list("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        list("?organizer=").andExpect(status().isBadRequest());
        list("?organizer=not-a-uuid").andExpect(status().isBadRequest());
        list("?organizer=" + UUID.randomUUID()).andExpect(status().isNotFound());
        list("?organizer=%s&kind=TICKET".formatted(c.admin().org())).andExpect(status().isBadRequest());
        UUID empty = organizer("Sans produits"); // unlisted organizer: still answered
        list("?organizer=" + empty).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void remainingSoldOutAndCashFromTheProductsClose() throws Exception {
        Club c = club();
        jdbc.sql("update products set sold = 3 where id = ?::uuid").param(c.pass()).update();
        jdbc.sql("update organizers set settings = settings || jsonb_build_object('cash_points', ?::jsonb) where id = ?")
                .params("[{\"name\": \"Guichet Stade\"}]", c.admin().org()).update();
        String json = body(mvc.perform(get("/fan/products/" + c.pass())).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.remaining")).isZero();
        assertThat(JsonPath.<Boolean>read(json, "$.sold_out")).isTrue();
        assertThat(JsonPath.<List<String>>read(json, "$.payment_methods")).contains("CARD", "CASH_AGENCY");
        assertThat(Instant.parse(JsonPath.read(json, "$.cash_until")))
                .isEqualTo(Instant.parse(CLOSE).minusSeconds(2 * 3600)); // payments.cash.min-window

        // Sales close in 1 h: cash_until is already behind, so no CASH_AGENCY.
        jdbc.sql("update products set sales_close_at = now() + interval '1 hour' where id = ?::uuid")
                .param(c.membership()).update();
        json = body(mvc.perform(get("/fan/products/" + c.membership())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.payment_methods")).doesNotContain("CASH_AGENCY");
        assertThat(JsonPath.<String>read(json, "$.cash_until")).isNotNull();
    }
}
