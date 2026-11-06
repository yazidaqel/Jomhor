package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract: /org/events/{id}/prices, /org/events/{id}/prices/{price_id} (docs/04-api.md §Organizer). Zone in the
 * event's venue, Σ quota per zone ≤ capacity (also under concurrency), QUOTA_BELOW_SOLD, PRICE_IN_USE, status rules.
 */
class PriceApiTest extends OrgApiTest {

    private static final String[] PRICE_KEYS = {"id", "zone_id", "name", "price_mad", "quota", "sold"};

    private static void invalid(ResultActions r, String field) throws Exception {
        r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields." + field).isString());
    }

    private static void error(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    private long zoneQuota(int eventId, int zoneId) {
        return jdbc.sql("select coalesce(sum(quota), 0) from price_categories where event_id = ? and zone_id = ?")
                .params(eventId, zoneId).query(Long.class).single();
    }

    @Test
    void createListPatchDelete() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int est = zone(a, venueId, "EST");
        int id = event(a, venueId);

        String json = body(call(a, post("/org/events/" + id + "/prices"),
                "{\"zone_id\": %d, \"name\": \" Plein tarif \", \"price_mad\": 5050, \"quota\": 40}".formatted(est))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.zone_id").value(est))
                .andExpect(jsonPath("$.name").value("Plein tarif"))
                .andExpect(jsonPath("$.price_mad").value(5050))
                .andExpect(jsonPath("$.quota").value(40))
                .andExpect(jsonPath("$.sold").value(0)));
        assertKeys(json, "$", PRICE_KEYS);
        String estPrice = JsonPath.read(json, "$.id");
        String nordPrice = price(a, id, nord, "Abonné", 10);

        json = body(call(a, get("/org/events/" + id + "/prices")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].zone_id").value(contains(Math.min(nord, est), Math.max(nord, est)))));
        assertKeys(json, "$[0]", PRICE_KEYS);

        json = body(call(a, patch("/org/events/" + id + "/prices/" + estPrice), "{\"price_mad\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Plein tarif"))
                .andExpect(jsonPath("$.price_mad").value(0))
                .andExpect(jsonPath("$.quota").value(40)));
        assertKeys(json, "$", PRICE_KEYS);
        call(a, patch("/org/events/" + id + "/prices/" + estPrice), "{\"name\": \"Réduit\", \"quota\": 5, \"zone_id\": %d}".formatted(est))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Réduit"))
                .andExpect(jsonPath("$.quota").value(5));

        call(a, delete("/org/events/" + id + "/prices/" + nordPrice))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        call(a, get("/org/events/" + id + "/prices")).andExpect(jsonPath("$[*].id").value(contains(estPrice)));
        call(a, delete("/org/events/" + id + "/prices/" + nordPrice)).andExpect(status().isNotFound());
    }

    @Test
    void validation() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int id = event(a, venueId);
        String path = "/org/events/" + id + "/prices";
        String row = "{\"zone_id\": %d, \"name\": \"P\", \"price_mad\": %s, \"quota\": 1}";

        invalid(call(a, post(path), row.formatted(nord, "50.5")), "price_mad");
        invalid(call(a, post(path), row.formatted(nord, "5000.0")), "price_mad");
        invalid(call(a, post(path), row.formatted(nord, "\"5000\"")), "price_mad");
        invalid(call(a, post(path), row.formatted(nord, "-1")), "price_mad");
        call(a, post(path), "{\"name\": \" \", \"quota\": -1}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.zone_id").isString())
                .andExpect(jsonPath("$.details.fields.name").isString())
                .andExpect(jsonPath("$.details.fields.price_mad").isString())
                .andExpect(jsonPath("$.details.fields.quota").isString());
        invalid(call(a, post(path), "{\"zone_id\": %d, \"name\": \"P\", \"price_mad\": 1, \"quota\": 1.5}".formatted(nord)), "quota");

        // A zone of another venue of the same organizer, of another organizer, or unknown.
        int otherVenue = zone(a, venue(a, "Salle"), "NORD");
        int otherOrg = zone(b, venue(b, "B"), "NORD");
        for (int bad : List.of(otherVenue, otherOrg, 32_000)) {
            invalid(call(a, post(path), row.formatted(bad, "100")), "zone_id");
        }

        String p = price(a, id, nord, "P", 1);
        invalid(call(a, patch(path + "/" + p), "{\"price_mad\": 1.25}"), "price_mad");
        invalid(call(a, patch(path + "/" + p), "{\"name\": \"\"}"), "name");
        invalid(call(a, patch(path + "/" + p), "{\"quota\": -1}"), "quota");
        invalid(call(a, patch(path + "/" + p), "{\"zone_id\": %d}".formatted(otherVenue)), "zone_id");
        call(a, get(path)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].price_mad").value(5000));
    }

    @Test
    void duplicateNameInAZoneIsRefused() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int est = zone(a, venueId, "EST");
        int id = event(a, venueId);
        price(a, id, nord, "Plein", 1);
        price(a, id, est, "Plein", 1); // same name, other zone: fine
        invalid(postPrice(a, id, nord, "Plein", 1).andExpect(jsonPath("$.details.reason").value("NAME_TAKEN")), "name");
        String abonne = price(a, id, nord, "Abonné", 1);
        invalid(call(a, patch("/org/events/" + id + "/prices/" + abonne), "{\"name\": \"Plein\"}")
                .andExpect(jsonPath("$.details.reason").value("NAME_TAKEN")), "name");
        call(a, get("/org/events/" + id + "/prices")).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void quotaAboveZoneCapacityIsRefused() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD", 100);
        int est = zone(a, venueId, "EST", 50);
        int id = event(a, venueId);
        int other = event(a, venueId);

        error(postPrice(a, id, nord, "Plein", 101), 422, "QUOTA_EXCEEDS_CAPACITY");
        String plein = price(a, id, nord, "Plein", 60);
        price(a, id, est, "Plein", 50);
        price(a, other, nord, "Plein", 100); // another event's categories don't count
        error(postPrice(a, id, nord, "Abonné", 41), 422, "QUOTA_EXCEEDS_CAPACITY");
        String abonne = price(a, id, nord, "Abonné", 40);

        error(call(a, patch("/org/events/" + id + "/prices/" + plein), "{\"quota\": 61}"), 422, "QUOTA_EXCEEDS_CAPACITY");
        call(a, patch("/org/events/" + id + "/prices/" + plein), "{\"quota\": 60}").andExpect(status().isOk()); // itself not counted twice
        call(a, patch("/org/events/" + id + "/prices/" + abonne), "{\"quota\": 30}").andExpect(status().isOk());
        call(a, patch("/org/events/" + id + "/prices/" + plein), "{\"quota\": 70}").andExpect(status().isOk());
        assertThat(zoneQuota(id, nord)).isEqualTo(100);
    }

    @Test
    void parallelPostsNeverExceedZoneCapacity() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD", 100);
        int id = event(a, venueId);

        int threads = 10;
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String name = "Cat " + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return postPrice(a, id, nord, name, 30).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> r : results) {
                statuses.add(r.get());
            }
        }
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s != 201)).allMatch(s -> s == 422);
        assertThat(zoneQuota(id, nord)).isEqualTo(90);
    }

    @Test
    void quotaBelowSoldIsRefused() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int id = event(a, venueId);
        String p = price(a, id, zone(a, venueId, "NORD"), "Plein", 10);
        setSold(p, 5);
        error(call(a, patch("/org/events/" + id + "/prices/" + p), "{\"quota\": 4, \"name\": \"X\"}"), 422, "QUOTA_BELOW_SOLD");
        call(a, get("/org/events/" + id + "/prices")).andExpect(jsonPath("$[0].quota").value(10))
                .andExpect(jsonPath("$[0].name").value("Plein"));
        call(a, patch("/org/events/" + id + "/prices/" + p), "{\"quota\": 5}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.quota").value(5)).andExpect(jsonPath("$.sold").value(5));
    }

    @Test
    void deleteOnlyWhenUnsold() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int id = event(a, venueId);
        String p = price(a, id, zone(a, venueId, "NORD"), "Plein", 10);
        setSold(p, 1);
        error(call(a, delete("/org/events/" + id + "/prices/" + p)), 409, "PRICE_IN_USE");
        setSold(p, 0);
        call(a, delete("/org/events/" + id + "/prices/" + p)).andExpect(status().isNoContent());
    }

    @Test
    void pricesAreWritableInDraftAndOnSaleOnly() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int id = event(a, venueId);
        String p = price(a, id, nord, "Plein", 10);

        setStatus(id, "ON_SALE");
        String onSale = price(a, id, nord, "Abonné", 10);
        call(a, patch("/org/events/" + id + "/prices/" + p), "{\"quota\": 20}").andExpect(status().isOk());
        call(a, delete("/org/events/" + id + "/prices/" + onSale)).andExpect(status().isNoContent());

        for (String s : List.of("GATES_OPEN", "CLOSED")) {
            setStatus(id, s);
            error(postPrice(a, id, nord, "Tardif", 1), 409, "EVENT_NOT_EDITABLE");
            error(call(a, patch("/org/events/" + id + "/prices/" + p), "{\"quota\": 1}"), 409, "EVENT_NOT_EDITABLE");
            error(call(a, delete("/org/events/" + id + "/prices/" + p)), 409, "EVENT_NOT_EDITABLE");
            call(a, get("/org/events/" + id + "/prices")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].quota").value(contains(20)));
        }
    }

    @Test
    void priceOfAnotherEventOfTheSameOrganizerIs404() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int e1 = event(a, venueId);
        int e2 = event(a, venueId);
        String p = price(a, e1, nord, "Plein", 10);
        error(call(a, patch("/org/events/" + e2 + "/prices/" + p), "{\"quota\": 1}"), 404, "NOT_FOUND");
        error(call(a, delete("/org/events/" + e2 + "/prices/" + p)), 404, "NOT_FOUND");
        call(a, get("/org/events/" + e1 + "/prices")).andExpect(jsonPath("$[0].quota").value(10));
    }
}
