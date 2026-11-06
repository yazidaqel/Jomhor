package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** /org/** contract tests: a staff caller per organizer and the catalog fixtures they build through the API. */
abstract class OrgApiTest extends IntegrationTest {

    record Caller(UUID org, String token) {}

    protected Caller caller(UUID org, String role) throws Exception {
        String phone = randomPhone();
        staff(org, phone, role, true);
        return new Caller(org, orgToken(phone));
    }

    protected Caller admin() throws Exception {
        return caller(organizer("Org " + UUID.randomUUID()), "ADMIN");
    }

    protected ResultActions call(Caller c, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + c.token()).header("X-Organizer-Id", c.org()));
    }

    protected ResultActions call(Caller c, MockHttpServletRequestBuilder req, String body) throws Exception {
        return call(c, req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    /** The object at {@code path} has exactly these keys (the 04 shape, snake_case, nothing more). */
    protected static void assertKeys(String json, String path, String... keys) {
        Map<String, Object> object = JsonPath.read(json, path);
        assertThat(object.keySet()).containsExactlyInAnyOrder(keys);
    }

    protected String venue(Caller c, String name) throws Exception {
        String json = body(call(c, post("/org/venues"), "{\"name\": \"%s\", \"capacity\": 1000}".formatted(name))
                .andExpect(status().isCreated()));
        return JsonPath.read(json, "$.id");
    }

    protected int zone(Caller c, String venueId, String code) throws Exception {
        return zone(c, venueId, code, 100);
    }

    protected int zone(Caller c, String venueId, String code, int capacity) throws Exception {
        String json = body(call(c, post("/org/venues/" + venueId + "/zones"),
                "{\"code\": \"%s\", \"name\": \"Zone %s\", \"capacity\": %d}".formatted(code, code, capacity))
                .andExpect(status().isCreated()));
        return JsonPath.read(json, "$.id");
    }

    /** A DRAFT football match starting at {@code startsAt} (RFC 3339). */
    protected int event(Caller c, String venueId, String startsAt) throws Exception {
        String json = body(call(c, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "%s"}
                """.formatted(venueId, startsAt)).andExpect(status().isCreated()));
        return JsonPath.read(json, "$.id");
    }

    protected int event(Caller c, String venueId) throws Exception {
        return event(c, venueId, "2099-05-01T19:00:00Z");
    }

    protected ResultActions postPrice(Caller c, int eventId, int zoneId, String name, int quota) throws Exception {
        return call(c, post("/org/events/" + eventId + "/prices"),
                "{\"zone_id\": %d, \"name\": \"%s\", \"price_mad\": 5000, \"quota\": %d}".formatted(zoneId, name, quota));
    }

    protected String price(Caller c, int eventId, int zoneId, String name, int quota) throws Exception {
        return JsonPath.read(body(postPrice(c, eventId, zoneId, name, quota).andExpect(status().isCreated())), "$.id");
    }

    protected void setStatus(int eventId, String status) {
        jdbc.sql("update events set status = ?::event_status where id = ?").params(status, eventId).update();
    }

    protected void setSold(String priceId, int sold) {
        jdbc.sql("update price_categories set sold = ? where id = ?::uuid").params(sold, priceId).update();
    }
}
