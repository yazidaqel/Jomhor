package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.OrgApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-51): docs/04-api.md §Organizer /org/products* and §Products. PASS and MEMBERSHIP fields per kind (the
 * products_kind rule as 422 per field), this organizer's series and zone, fixed fields, quota ≥ sold, roles, audits.
 * GET /org/products/{id} figures and refunds_required come with BE-55.
 */
class ProductOrgApiTest extends OrgApiTest {

    private static final String[] PRODUCT_KEYS = {"id", "kind", "name", "price_mad", "on_sale", "sales_close_at", "quota",
            "sold", "valid_until", "series", "zone"};
    private static final String[] AUDIT_KEYS = {"kind", "name", "price_mad", "on_sale", "sales_close_at", "quota",
            "series_id", "zone_id", "valid_until"};
    private static final String CLOSE = "2099-06-30T22:00:00Z";
    private static final String VALID = "2099-07-01T00:00:00Z";

    record Setup(Caller admin, String series, int zone) {}

    private Setup setup() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int zone = zone(a, venue, "EST");
        String series = JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"Saison 2026-27\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2027-06-30\"}")
                .andExpect(status().isCreated())), "$.id");
        return new Setup(a, series, zone);
    }

    private static String passBody(Setup s, String name, int quota) {
        return """
                {"kind": "PASS", "name": "%s", "price_mad": 150000, "quota": %d, "series_id": "%s", "zone_id": %d,
                 "sales_close_at": "%s"}""".formatted(name, quota, s.series(), s.zone(), CLOSE);
    }

    private static String membershipBody(String name) {
        return """
                {"kind": "MEMBERSHIP", "name": "%s", "price_mad": 20000, "valid_until": "%s", "sales_close_at": "%s",
                 "on_sale": true}""".formatted(name, VALID, CLOSE);
    }

    private String create(Caller c, String body) throws Exception {
        return JsonPath.read(body(call(c, post("/org/products"), body).andExpect(status().isCreated())), "$.id");
    }

    private static void invalid(ResultActions r, String... fields) throws Exception {
        String json = r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andReturn().getResponse().getContentAsString();
        Map<String, Object> got = JsonPath.read(json, "$.details.fields");
        assertThat(got.keySet()).containsExactlyInAnyOrder(fields);
    }

    private List<Map<String, Object>> audits(String product) {
        return jdbc.sql("select action, actor_type::text as actor_type, payload::text as payload from audit_log"
                        + " where target = ? order by id").param(product).query().listOfRows();
    }

    @Test
    void createBothKindsAndList() throws Exception {
        Setup s = setup();
        Caller a = s.admin();
        String json = body(call(a, post("/org/products"), passBody(s, " Abonnement Tribune Est ", 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PASS"))
                .andExpect(jsonPath("$.name").value("Abonnement Tribune Est"))
                .andExpect(jsonPath("$.price_mad").value(150000))
                .andExpect(jsonPath("$.on_sale").value(false))
                .andExpect(jsonPath("$.sales_close_at").value(CLOSE))
                .andExpect(jsonPath("$.quota").value(3))
                .andExpect(jsonPath("$.sold").value(0))
                .andExpect(jsonPath("$.valid_until").isEmpty())
                .andExpect(jsonPath("$.series.id").value(s.series()))
                .andExpect(jsonPath("$.series.name").value("Saison 2026-27"))
                .andExpect(jsonPath("$.zone.id").value(s.zone()))
                .andExpect(jsonPath("$.zone.code").value("EST"))
                .andExpect(jsonPath("$.zone.name").value("Zone EST")));
        assertKeys(json, "$", PRODUCT_KEYS);
        assertKeys(json, "$.series", "id", "name");
        assertKeys(json, "$.zone", "id", "code", "name", "venue");
        assertKeys(json, "$.zone.venue", "id", "name", "timezone");
        assertThat(JsonPath.<String>read(json, "$.zone.venue.name")).isEqualTo("Stade");
        assertThat(JsonPath.<String>read(json, "$.zone.venue.timezone")).isEqualTo("Africa/Casablanca");
        String pass = JsonPath.read(json, "$.id");

        json = body(call(a, post("/org/products"), membershipBody("Carte membre 2026-27"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("MEMBERSHIP"))
                .andExpect(jsonPath("$.on_sale").value(true))
                .andExpect(jsonPath("$.quota").isEmpty())
                .andExpect(jsonPath("$.sold").value(0))
                .andExpect(jsonPath("$.valid_until").value(VALID))
                .andExpect(jsonPath("$.series").isEmpty())
                .andExpect(jsonPath("$.zone").isEmpty()));
        assertKeys(json, "$", PRODUCT_KEYS);
        String membership = JsonPath.read(json, "$.id");
        String other = create(a, passBody(s, "Abonnement Virage", 5));

        // By kind (PASS, then MEMBERSHIP), then name.
        json = body(call(a, get("/org/products")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[*].id")).containsExactly(pass, other, membership);
        assertKeys(json, "$[0]", PRODUCT_KEYS);
        call(a, get("/org/products?kind=MEMBERSHIP")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(membership));
        call(a, get("/org/products?kind=TICKET")).andExpect(status().isBadRequest());
        json = body(call(a, get("/org/products/" + pass)).andExpect(status().isOk()));
        assertKeys(json, "$", PRODUCT_KEYS);

        List<Map<String, Object>> rows = audits(pass);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("action", "product.create").containsEntry("actor_type", "STAFF");
        Map<String, Object> payload = JsonPath.read((String) rows.getFirst().get("payload"), "$");
        assertThat(payload.keySet()).containsExactlyInAnyOrder(AUDIT_KEYS);
        assertThat(payload).containsEntry("name", "Abonnement Tribune Est").containsEntry("quota", 3)
                .containsEntry("series_id", s.series()).containsEntry("zone_id", s.zone());
    }

    @Test
    void fieldsPerKindAndRules() throws Exception {
        Setup s = setup();
        Caller a = s.admin();
        Caller b = admin();
        String bVenue = venue(b, "B");
        int foreignZone = zone(b, bVenue, "EST");
        String foreignSeries = JsonPath.read(body(call(b, post("/org/series"),
                "{\"name\": \"B\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2027-06-30\"}")), "$.id");

        // products_kind: what PASS needs, what MEMBERSHIP needs, and neither takes the other's fields.
        invalid(call(a, post("/org/products"), """
                {"kind": "PASS", "name": "P", "price_mad": 100, "sales_close_at": "%s"}""".formatted(CLOSE)),
                "quota", "series_id", "zone_id");
        invalid(call(a, post("/org/products"), passBody(s, "P", 3).replace("}", ", \"valid_until\": \"%s\"}"
                .formatted(VALID))), "valid_until");
        invalid(call(a, post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "M", "price_mad": 100, "sales_close_at": "%s"}""".formatted(CLOSE)),
                "valid_until");
        invalid(call(a, post("/org/products"), membershipBody("M").replace("}", ", \"quota\": 3}")), "quota");
        invalid(call(a, post("/org/products"), "{\"name\": \"M\"}"), "kind", "price_mad", "sales_close_at");
        invalid(call(a, post("/org/products"), membershipBody("M").replace("\"MEMBERSHIP\"", "\"TICKET\"")), "kind");

        invalid(call(a, post("/org/products"), passBody(s, "P", 3).replace("150000", "0")), "price_mad");
        invalid(call(a, post("/org/products"), passBody(s, "P", 3).replace("150000", "1500.5")), "price_mad");
        invalid(call(a, post("/org/products"), passBody(s, "P", -1)), "quota");
        invalid(call(a, post("/org/products"), passBody(s, " ", 3)), "name");
        invalid(call(a, post("/org/products"), passBody(s, "x".repeat(61), 3)), "name");
        invalid(call(a, post("/org/products"), passBody(s, "P", 3).replace(s.series(), foreignSeries)), "series_id");
        invalid(call(a, post("/org/products"), passBody(s, "P", 3).replace(s.series(), UUID.randomUUID().toString())),
                "series_id");
        invalid(call(a, post("/org/products"), passBody(s, "P", 3).replace("\"zone_id\": " + s.zone(),
                "\"zone_id\": " + foreignZone)), "zone_id");
        invalid(call(a, post("/org/products"), passBody(s, "P", 3).replace(CLOSE, "2020-01-01T00:00:00Z")),
                "sales_close_at");
        invalid(call(a, post("/org/products"), membershipBody("M").replace(VALID, CLOSE)), "valid_until");
        call(a, get("/org/products")).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.sql("select count(*) from audit_log where organizer_id = ? and action like 'product.%'")
                .param(a.org()).query(Long.class).single()).isZero();
    }

    @Test
    void patchKeepsFixedFieldsAndQuotaAboveSold() throws Exception {
        Setup s = setup();
        Caller a = s.admin();
        String pass = create(a, passBody(s, "Abonnement", 3));
        String membership = create(a, membershipBody("Carte"));
        String other = JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"Coupe\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2027-06-30\"}")), "$.id");
        int otherZone = zone(a, venue(a, "Salle"), "NORD");

        String json = body(call(a, patch("/org/products/" + pass),
                "{\"name\": \"Abonnement Est\", \"price_mad\": 160000, \"on_sale\": true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Abonnement Est"))
                .andExpect(jsonPath("$.price_mad").value(160000))
                .andExpect(jsonPath("$.on_sale").value(true))
                .andExpect(jsonPath("$.quota").value(3))
                .andExpect(jsonPath("$.series.id").value(s.series())));
        assertKeys(json, "$", PRODUCT_KEYS);
        call(a, patch("/org/products/" + pass), "{\"kind\": \"PASS\", \"series_id\": \"%s\", \"zone_id\": %d}"
                .formatted(s.series(), s.zone())).andExpect(status().isOk()); // the same values: unchanged

        invalid(call(a, patch("/org/products/" + pass), "{\"kind\": \"MEMBERSHIP\"}"), "kind");
        invalid(call(a, patch("/org/products/" + pass), "{\"series_id\": \"%s\"}".formatted(other)), "series_id");
        invalid(call(a, patch("/org/products/" + pass), "{\"zone_id\": %d}".formatted(otherZone)), "zone_id");
        invalid(call(a, patch("/org/products/" + pass), "{\"price_mad\": 0}"), "price_mad");
        invalid(call(a, patch("/org/products/" + pass), "{\"valid_until\": \"%s\"}".formatted(VALID)), "valid_until");
        invalid(call(a, patch("/org/products/" + pass), "{\"sales_close_at\": \"2020-01-01T00:00:00Z\"}"),
                "sales_close_at");
        invalid(call(a, patch("/org/products/" + membership), "{\"quota\": 5}"), "quota");
        invalid(call(a, patch("/org/products/" + membership), "{\"valid_until\": \"2099-06-01T00:00:00Z\"}"),
                "valid_until");
        call(a, patch("/org/products/" + membership), "{\"valid_until\": \"2099-08-01T00:00:00Z\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid_until").value("2099-08-01T00:00:00Z"));

        jdbc.sql("update products set sold = 2 where id = ?::uuid").param(pass).update();
        call(a, patch("/org/products/" + pass), "{\"quota\": 1}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUOTA_BELOW_SOLD"));
        call(a, patch("/org/products/" + pass), "{\"quota\": 2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.quota").value(2)).andExpect(jsonPath("$.sold").value(2));

        List<Map<String, Object>> rows = audits(pass);
        assertThat(rows).extracting(r -> r.get("action"))
                .containsExactly("product.create", "product.update", "product.update", "product.update");
        Map<String, Object> payload = JsonPath.read((String) rows.getLast().get("payload"), "$");
        assertThat(payload.keySet()).containsExactlyInAnyOrder(AUDIT_KEYS);
        assertThat(payload).containsEntry("quota", 2).containsEntry("name", "Abonnement Est")
                .containsEntry("price_mad", 160000).containsEntry("on_sale", true);
    }

    @Test
    void rolesAndTenancy() throws Exception {
        Setup s = setup();
        Caller a = s.admin();
        String pass = create(a, passBody(s, "Abonnement", 3));
        Caller finance = caller(a.org(), "FINANCE");
        call(finance, get("/org/products")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(pass));
        call(finance, get("/org/products/" + pass)).andExpect(status().isOk());
        call(finance, post("/org/products"), passBody(s, "F", 1)).andExpect(status().isForbidden());
        call(finance, patch("/org/products/" + pass), "{\"name\": \"F\"}").andExpect(status().isForbidden());
        for (String role : List.of("CASHIER", "GATE", "SUPERVISOR")) {
            call(caller(a.org(), role), get("/org/products")).andExpect(status().isForbidden());
        }
        create(caller(a.org(), "OPS"), passBody(s, "Ops", 1));

        Caller b = admin();
        call(b, get("/org/products")).andExpect(jsonPath("$.length()").value(0));
        call(b, get("/org/products/" + pass)).andExpect(status().isNotFound());
        call(b, patch("/org/products/" + pass), "{\"name\": \"B\"}").andExpect(status().isNotFound());
        call(a, get("/org/products/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
