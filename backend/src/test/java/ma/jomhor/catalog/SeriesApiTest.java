package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-51): docs/04-api.md §Organizer /org/series*, and series_id on POST/PATCH /org/events (Lot 5 step 3:
 * this organizer's series only, never cleared, fixed once a pass ticket order exists).
 */
class SeriesApiTest extends OrgApiTest {

    private static final String[] SERIES_KEYS = {"id", "name", "starts_on", "ends_on"};

    private static void invalid(ResultActions r, String field) throws Exception {
        r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields." + field).isString());
    }

    private ResultActions postSeries(Caller c, String name, String from, String to) throws Exception {
        return call(c, post("/org/series"),
                "{\"name\": \"%s\", \"starts_on\": \"%s\", \"ends_on\": \"%s\"}".formatted(name, from, to));
    }

    private String series(Caller c, String name, String from, String to) throws Exception {
        return JsonPath.read(body(postSeries(c, name, from, to).andExpect(status().isCreated())), "$.id");
    }

    @Test
    void createListPatch() throws Exception {
        Caller a = admin();
        String json = body(postSeries(a, " Saison 2026-27 ", "2026-08-01", "2027-06-30")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Saison 2026-27"))
                .andExpect(jsonPath("$.starts_on").value("2026-08-01"))
                .andExpect(jsonPath("$.ends_on").value("2027-06-30")));
        assertKeys(json, "$", SERIES_KEYS);
        String saison = JsonPath.read(json, "$.id");
        String coupe = series(a, "Coupe", "2026-08-01", "2026-12-31"); // same start: by name, byte-wise
        String accent = series(a, "\u00c9t\u00e9", "2026-08-01", "2026-12-31"); // "E acute" sorts after "S" in "C"
        String early = series(a, "Zeta", "2025-08-01", "2026-06-30");

        json = body(call(a, get("/org/series")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[*].id")).containsExactly(early, coupe, saison, accent);
        assertKeys(json, "$[0]", SERIES_KEYS);

        call(a, patch("/org/series/" + coupe), "{\"name\": \"Coupe du Trône\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Coupe du Trône"))
                .andExpect(jsonPath("$.starts_on").value("2026-08-01"))
                .andExpect(jsonPath("$.ends_on").value("2026-12-31"));
        call(a, patch("/org/series/" + coupe), "{\"ends_on\": \"2027-01-15\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Coupe du Trône"))
                .andExpect(jsonPath("$.ends_on").value("2027-01-15"));
    }

    @Test
    void validation() throws Exception {
        Caller a = admin();
        invalid(postSeries(a, "S", "2026-08-01", "2026-07-31"), "ends_on");
        invalid(postSeries(a, " ", "2026-08-01", "2026-08-01"), "name");
        invalid(postSeries(a, "x".repeat(61), "2026-08-01", "2026-08-01"), "name");
        invalid(postSeries(a, "S", "01/08/2026", "2026-08-01"), "starts_on");
        invalid(call(a, post("/org/series"), "{\"name\": \"S\"}"), "starts_on");
        series(a, "x".repeat(60), "2026-08-01", "2026-08-01"); // one day, 60 characters: fine

        String s = series(a, "S", "2026-08-01", "2026-12-31");
        invalid(call(a, patch("/org/series/" + s), "{\"ends_on\": \"2026-07-01\"}"), "ends_on");
        invalid(call(a, patch("/org/series/" + s), "{\"starts_on\": \"2027-01-01\"}"), "ends_on");
        invalid(call(a, patch("/org/series/" + s), "{\"name\": \"\"}"), "name");
        call(a, get("/org/series")).andExpect(jsonPath("$[?(@.id == '%s')].name".formatted(s)).value("S"));
    }

    @Test
    void rolesAndTenancy() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String s = series(a, "S", "2026-08-01", "2026-12-31");
        Caller finance = caller(a.org(), "FINANCE");
        call(finance, get("/org/series")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(s));
        postSeries(finance, "F", "2026-08-01", "2026-12-31").andExpect(status().isForbidden());
        call(finance, patch("/org/series/" + s), "{\"name\": \"F\"}").andExpect(status().isForbidden());
        Caller cashier = caller(a.org(), "CASHIER");
        call(cashier, get("/org/series")).andExpect(status().isForbidden());
        postSeries(caller(a.org(), "OPS"), "Ops", "2026-08-01", "2026-12-31").andExpect(status().isCreated());

        call(b, get("/org/series")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        call(b, patch("/org/series/" + s), "{\"name\": \"B\"}").andExpect(status().isNotFound());
        call(a, patch("/org/series/" + UUID.randomUUID()), "{\"name\": \"B\"}").andExpect(status().isNotFound());
    }

    @Test
    void anEventTakesOneOfTheOrganizersSeries() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String venue = venue(a, "Stade");
        String mine = series(a, "Saison", "2026-08-01", "2027-06-30");
        String other = series(a, "Coupe", "2026-08-01", "2027-06-30");
        String foreign = series(b, "Saison B", "2026-08-01", "2027-06-30");
        String create = """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "2099-05-01T19:00:00Z",
                 "series_id": "%s"}""";

        invalid(call(a, post("/org/events"), create.formatted(venue, foreign)), "series_id");
        invalid(call(a, post("/org/events"), create.formatted(venue, UUID.randomUUID())), "series_id");
        int id = JsonPath.read(body(call(a, post("/org/events"), create.formatted(venue, mine))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.series_id").value(mine))), "$.id");

        invalid(call(a, patch("/org/events/" + id), "{\"series_id\": \"%s\"}".formatted(foreign)), "series_id");
        call(a, patch("/org/events/" + id), "{\"title\": \"Derby\", \"series_id\": null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.series_id").value(mine)); // null = unchanged, never cleared
        call(a, patch("/org/events/" + id), "{\"series_id\": \"%s\"}".formatted(other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.series_id").value(other));
        call(a, get("/org/events/" + id)).andExpect(jsonPath("$.series_id").value(other));
        int plain = event(a, venue);
        call(a, get("/org/events/" + plain)).andExpect(jsonPath("$.series_id").isEmpty());
    }

    /** A pass ticket order for the event (pass_id and event_id set) fixes its series_id: 409 PASS_TICKETS. */
    @Test
    void passTicketsFixTheSeries() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int zone = zone(a, venue, "EST");
        String saison = series(a, "Saison", "2026-08-01", "2027-06-30");
        String coupe = series(a, "Coupe", "2026-08-01", "2027-06-30");
        int id = JsonPath.read(body(call(a, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "2099-05-01T19:00:00Z",
                 "series_id": "%s"}""".formatted(venue, saison)).andExpect(status().isCreated())), "$.id");
        int untouched = event(a, venue);
        UUID user = jdbc.sql("insert into users (phone_e164) values (?) returning id").param(randomPhone())
                .query(UUID.class).single();
        UUID product = jdbc.sql("""
                        insert into products (organizer_id, kind, name, price_mad, sales_close_at, quota, series_id,
                                              zone_id)
                        values (?, 'PASS', 'Abonnement', 150000, now() + interval '30 days', 10, ?::uuid, ?)
                        returning id""").params(a.org(), saison, zone).query(UUID.class).single();
        UUID pass = jdbc.sql("""
                        insert into passes (organizer_id, product_id, series_id, user_id, holder_name, status)
                        values (?, ?, ?::uuid, ?, 'Yazid Aqel', 'ACTIVE') returning id""")
                .params(a.org(), product, saison, user).query(UUID.class).single();
        jdbc.sql("""
                        insert into orders (organizer_id, event_id, pass_id, user_id, channel, status, total_mad,
                                            payment_method, reserved_until, paid_at)
                        values (?, ?, ?, ?, 'APP', 'PAID', 0, 'FREE', now(), now())""")
                .params(a.org(), id, pass, user).update();

        call(a, get("/org/events/" + id)).andExpect(jsonPath("$.pass_tickets").value(true));
        call(a, get("/org/events/" + untouched)).andExpect(jsonPath("$.pass_tickets").value(false));
        call(a, patch("/org/events/" + id), "{\"series_id\": \"%s\"}".formatted(coupe))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"))
                .andExpect(jsonPath("$.details.reason").value("PASS_TICKETS"));
        call(a, patch("/org/events/" + id), "{\"series_id\": \"%s\", \"title\": \"Derby\"}".formatted(saison))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Derby")); // same series: no change
        call(a, patch("/org/events/" + untouched), "{\"series_id\": \"%s\"}".formatted(coupe))
                .andExpect(status().isOk()); // another event of the organizer: free
        call(a, get("/org/events/" + id)).andExpect(jsonPath("$.series_id").value(saison));
    }
}
