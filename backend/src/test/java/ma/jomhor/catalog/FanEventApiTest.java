package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import ma.jomhor.catalog.application.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract: public GET /fan/events and GET /fan/events/{id} (docs/04-api.md §Fan, plan P1): no token, exact field
 * lists, window flags on the database clock, remaining instead of sold/quota, never a key.
 */
class FanEventApiTest extends OrgApiTest {

    private static final String[] EVENT_KEYS = {"id", "organizer_id", "status", "event_type", "title", "starts_at",
            "doors_open_at", "timezone", "venue", "metadata", "sale_starts_at", "sales_close_at", "on_sale", "sold_out",
            "from_price_mad", "per_account_limit"};
    private static final Pattern JSON_KEY = Pattern.compile("\"([A-Za-z_]+)\"\\s*:");
    private static final Set<String> FORBIDDEN = Set.of("key", "k_e", "k_t", "event_key", "event_key_enc", "sold", "quota");

    @Autowired
    EventService eventService;

    @Autowired
    PlatformTransactionManager transactions;

    private ResultActions list(String organizer, String from) throws Exception {
        var req = get("/fan/events");
        if (organizer != null) {
            req.param("organizer", organizer);
        }
        if (from != null) {
            req.param("from", from);
        }
        return mvc.perform(req);
    }

    private String cityVenue(Caller c) throws Exception {
        return JsonPath.read(body(call(c, post("/org/venues"), """
                {"name": "Grand Stade", "city": "Tanger", "capacity": 1000, "timezone": "Africa/Casablanca"}
                """).andExpect(status().isCreated())), "$.id");
    }

    private String gate(Caller c, String venueId, String code, int... zoneIds) throws Exception {
        String id = JsonPath.read(body(call(c, post("/org/venues/" + venueId + "/gates"),
                "{\"code\": \"%s\", \"name\": \"Porte %s\"}".formatted(code, code)).andExpect(status().isCreated())), "$.id");
        StringBuilder ids = new StringBuilder();
        for (int z : zoneIds) {
            ids.append(ids.isEmpty() ? "" : ",").append(z);
        }
        call(c, put("/org/gates/" + id + "/zones"), "{\"zone_ids\": [%s]}".formatted(ids)).andExpect(status().isOk());
        return id;
    }

    private String priced(Caller c, int eventId, int zoneId, String name, long priceMad, int quota) throws Exception {
        return JsonPath.read(body(call(c, post("/org/events/" + eventId + "/prices"),
                "{\"zone_id\": %d, \"name\": \"%s\", \"price_mad\": %d, \"quota\": %d}".formatted(zoneId, name, priceMad, quota))
                .andExpect(status().isCreated())), "$.id");
    }

    private void publish(Caller c, int eventId) throws Exception {
        call(c, post("/org/events/" + eventId + "/publish")).andExpect(status().isOk());
    }

    private void buy(String fan, int eventId, String priceId, int qty) throws Exception {
        mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + fan).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_id\": %d, \"channel\": \"WEB\", \"items\": [{\"price_category_id\": \"%s\", \"qty\": %d, \"holders\": %s}]}"
                                .formatted(eventId, priceId, qty, holders(qty))))
                .andExpect(status().isCreated());
    }

    private static void assertNoSecretOrCount(String json) {
        Set<String> keys = new HashSet<>();
        JSON_KEY.matcher(json).results().forEach(m -> keys.add(m.group(1)));
        assertThat(keys).isNotEmpty().doesNotContainAnyElementsOf(FORBIDDEN);
    }

    // ---------- auth ----------

    @Test
    void publicWithoutTokenAndAnyAuthorizationHeaderIsIgnored() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int id = event(a, venueId);
        price(a, id, zone(a, venueId, "NORD"), "Plein", 10);
        publish(a, id);

        mvc.perform(get("/fan/events").param("organizer", a.org().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(id)));
        mvc.perform(get("/fan/events/" + id)).andExpect(status().isOk());
        // public reads: no cache headers (04), unlike the authenticated chains' no-store default
        for (String path : new String[] {"/fan/events", "/fan/events/" + id}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Cache-Control"))
                    .andExpect(header().doesNotExist("Pragma"))
                    .andExpect(header().doesNotExist("Expires"));
        }
        mvc.perform(get("/fan/orders/" + UUID.randomUUID())).andExpect(header().exists("Cache-Control"));
        mvc.perform(get("/fan/events/" + id).header("Authorization", "Bearer not-a-jwt")).andExpect(status().isOk());
        mvc.perform(get("/fan/events").header("Authorization", "Bearer " + a.token())).andExpect(status().isOk());
        mvc.perform(get("/fan/events/" + id).header("Authorization", "Bearer " + fanToken(randomPhone())))
                .andExpect(status().isOk());

        // only those two GETs are open
        mvc.perform(post("/fan/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/fan/events/" + id + "/zones")).andExpect(status().isUnauthorized());
        mvc.perform(get("/fan/orders/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/fan/events/")).andExpect(status().isUnauthorized());
        mvc.perform(head("/fan/events/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(head("/fan/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/fan/events/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ---------- list ----------

    @Test
    void listShapeOrderStatusesAndFilters() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String venueId = cityVenue(a);
        int zoneId = zone(a, venueId, "NORD");
        int march = event(a, venueId, "2160-03-01T19:00:00Z");
        int feb = event(a, venueId, "2160-02-01T19:00:00Z");
        int gatesOpen = event(a, venueId, "2160-04-01T19:00:00Z");
        int closed = event(a, venueId, "2160-05-01T19:00:00Z");
        int draft = event(a, venueId, "2160-01-15T19:00:00Z");
        for (int e : new int[] {march, feb, gatesOpen, closed}) {
            priced(a, e, zoneId, "Plein", 5000, 10);
            publish(a, e);
        }
        setStatus(gatesOpen, "GATES_OPEN");
        setStatus(closed, "CLOSED");
        String bVenue = venue(b, "B");
        int other = event(b, bVenue, "2160-02-15T19:00:00Z");
        priced(b, other, zone(b, bVenue, "SUD"), "Plein", 3000, 10);
        publish(b, other);

        String json = body(list(a.org().toString(), "2160-01-01T00:00:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(feb, march, gatesOpen)))
                .andExpect(jsonPath("$[0].organizer_id").value(a.org().toString()))
                .andExpect(jsonPath("$[0].status").value("ON_SALE"))
                .andExpect(jsonPath("$[0].event_type").value("FOOTBALL_MATCH"))
                .andExpect(jsonPath("$[0].title").value("Match"))
                .andExpect(jsonPath("$[0].starts_at").value("2160-02-01T19:00:00Z"))
                .andExpect(jsonPath("$[0].doors_open_at").isEmpty())
                .andExpect(jsonPath("$[0].timezone").value("Africa/Casablanca"))
                .andExpect(jsonPath("$[0].venue.name").value("Grand Stade"))
                .andExpect(jsonPath("$[0].venue.city").value("Tanger"))
                .andExpect(jsonPath("$[0].metadata").isMap())
                .andExpect(jsonPath("$[0].sale_starts_at").isEmpty())
                .andExpect(jsonPath("$[0].sales_close_at").value("2160-02-01T18:00:00Z"))
                .andExpect(jsonPath("$[0].on_sale").value(true))
                .andExpect(jsonPath("$[0].sold_out").value(false))
                .andExpect(jsonPath("$[0].from_price_mad").value(5000))
                .andExpect(jsonPath("$[0].per_account_limit").value(4))
                .andExpect(jsonPath("$[2].status").value("GATES_OPEN"))
                .andExpect(jsonPath("$[2].on_sale").value(false))); // GATES_OPEN: listed, not on sale
        assertKeys(json, "$[0]", EVENT_KEYS);
        assertKeys(json, "$[0].venue", "name", "city");
        assertNoSecretOrCount(json);

        list(a.org().toString(), "2160-02-02T00:00:00Z").andExpect(jsonPath("$[*].id").value(contains(march, gatesOpen)));
        list(null, "2160-01-01T00:00:00Z").andExpect(jsonPath("$[*].id").value(contains(feb, other, march, gatesOpen)));
        list(UUID.randomUUID().toString(), null).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
        // from defaults to now: an event that already started drops out
        jdbc.sql("update events set starts_at = now() - interval '1 minute' where id = ?").param(feb).update();
        list(a.org().toString(), null).andExpect(jsonPath("$[*].id").value(contains(march, gatesOpen)));
        list(null, "tomorrow").andExpect(status().isBadRequest());
        list("not-a-uuid", null).andExpect(status().isBadRequest());
        mvc.perform(get("/fan/events/" + draft)).andExpect(status().isNotFound());
    }

    @Test
    void atMost50() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        jdbc.sql("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc, status, refund_cutoff_at)
                select ?, ?::uuid, 'E' || i, timestamptz '2100-01-01' + i * interval '1 day', '\\x00'::bytea, 'ON_SALE',
                       timestamptz '2100-01-01' + i * interval '1 day' - interval '60 minutes'
                from generate_series(1, 51) i
                """).params(a.org(), venueId).update();
        list(a.org().toString(), null).andExpect(jsonPath("$.length()").value(50))
                .andExpect(jsonPath("$[0].title").value("E1"))
                .andExpect(jsonPath("$[49].title").value("E50"))
                .andExpect(jsonPath("$[0].sold_out").value(true)) // no category: nothing remains
                .andExpect(jsonPath("$[0].from_price_mad").isEmpty());
    }

    // ---------- detail ----------

    @Test
    void detailZonesGatesPricesAndRemainingAfterOrders() throws Exception {
        Caller a = admin();
        String venueId = cityVenue(a);
        int nord = zone(a, venueId, "NORD");
        int est = zone(a, venueId, "EST");
        zone(a, venueId, "SUD"); // no price for this event: not listed
        gate(a, venueId, "P4", nord);
        gate(a, venueId, "P10", nord, est);
        gate(a, venueId, "P2", est);
        int id = event(a, venueId);
        String virage = priced(a, id, nord, "Virage", 5000, 2);
        String tribune = priced(a, id, nord, "Tribune", 12000, 1);
        String est1 = priced(a, id, est, "Est", 8000, 1);
        publish(a, id);

        String json = body(mvc.perform(get("/fan/events/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ON_SALE"))
                .andExpect(jsonPath("$.timezone").value("Africa/Casablanca"))
                .andExpect(jsonPath("$.on_sale").value(true))
                .andExpect(jsonPath("$.from_price_mad").value(5000))
                .andExpect(jsonPath("$.zones[*].id").value(contains(Math.min(nord, est), Math.max(nord, est))))
                .andExpect(jsonPath("$.zones[?(@.id == %d)].gate_codes[*]".formatted(nord)).value(contains("P10", "P4")))
                .andExpect(jsonPath("$.zones[?(@.id == %d)].gate_codes[*]".formatted(est)).value(contains("P10", "P2")))
                .andExpect(jsonPath("$.zones[?(@.id == %d)].prices[*].name".formatted(nord)).value(contains("Tribune", "Virage")))
                .andExpect(jsonPath("$.zones[?(@.id == %d)].prices[*].remaining".formatted(nord)).value(contains(1, 2))));
        String[] detailKeys = new String[EVENT_KEYS.length + 3];
        System.arraycopy(EVENT_KEYS, 0, detailKeys, 0, EVENT_KEYS.length);
        detailKeys[EVENT_KEYS.length] = "zones";
        detailKeys[EVENT_KEYS.length + 1] = "payment_methods";
        detailKeys[EVENT_KEYS.length + 2] = "cash_until";
        assertKeys(json, "$", detailKeys);
        assertKeys(json, "$.venue", "name", "city");
        assertKeys(json, "$.zones[0]", "id", "code", "name", "seated", "gate_codes", "prices");
        assertKeys(json, "$.zones[0].prices[0]", "price_category_id", "name", "price_mad", "remaining");
        assertNoSecretOrCount(json);

        String fan = fanToken(randomPhone());
        buy(fan, id, virage, 2);
        mvc.perform(get("/fan/events/" + id))
                .andExpect(jsonPath("$.zones[?(@.id == %d)].prices[*].remaining".formatted(nord)).value(contains(1, 0)))
                .andExpect(jsonPath("$.from_price_mad").value(8000))
                .andExpect(jsonPath("$.sold_out").value(false));
        buy(fan, id, est1, 1);
        buy(fan, id, tribune, 1);
        mvc.perform(get("/fan/events/" + id))
                .andExpect(jsonPath("$.sold_out").value(true))
                .andExpect(jsonPath("$.from_price_mad").isEmpty())
                .andExpect(jsonPath("$.on_sale").value(true));
        list(a.org().toString(), null).andExpect(jsonPath("$[0].sold_out").value(true))
                .andExpect(jsonPath("$[0].from_price_mad").isEmpty());
    }

    @Test
    void draftAndUnknownAre404ClosedIsReturnedNotOnSale() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int zoneId = zone(a, venueId, "NORD");
        int draft = event(a, venueId);
        priced(a, draft, zoneId, "Plein", 5000, 10);
        mvc.perform(get("/fan/events/" + draft)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/fan/events/999999999")).andExpect(status().isNotFound());
        list(a.org().toString(), null).andExpect(jsonPath("$", empty()));

        publish(a, draft);
        setStatus(draft, "CLOSED");
        mvc.perform(get("/fan/events/" + draft)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.on_sale").value(false));
        list(a.org().toString(), null).andExpect(jsonPath("$", empty()));
    }

    @Test
    void onSaleFollowsTheWindowOnTheDatabaseClock() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int id = event(a, venueId);
        priced(a, id, zone(a, venueId, "NORD"), "Plein", 5000, 10);
        publish(a, id);
        call(a, patch("/org/events/" + id), "{\"sale_starts_at\": \"2099-04-01T10:00:00Z\"}").andExpect(status().isOk());
        mvc.perform(get("/fan/events/" + id))
                .andExpect(jsonPath("$.on_sale").value(false))
                .andExpect(jsonPath("$.sale_starts_at").value("2099-04-01T10:00:00Z"))
                .andExpect(jsonPath("$.sales_close_at").value("2099-05-01T18:00:00Z"));
        // Boundaries exactly: in one transaction now() is fixed, so now() = sale_starts_at (open) and
        // now() = refund_cutoff_at (closed) are what the query sees. Rolled back.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.sql("update events set sale_starts_at = now() where id = ?").param(id).update();
            assertThat(eventService.publicEvent(id).event().onSale()).isTrue();
            jdbc.sql("update events set refund_cutoff_at = now() where id = ?").param(id).update();
            assertThat(eventService.publicEvent(id).event().onSale()).isFalse();
            tx.setRollbackOnly();
        });
        jdbc.sql("update events set sale_starts_at = now() where id = ?").param(id).update();
        mvc.perform(get("/fan/events/" + id)).andExpect(jsonPath("$.on_sale").value(true));
    }
}
