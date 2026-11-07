package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract: /org/events, /org/events/{id}, /org/events/{id}/publish (docs/04-api.md §Organizer, §Event metadata).
 * Validation, metadata type checks, immutable fields, publish rules and audit, tenant scoping, FINANCE read-only.
 */
class EventApiTest extends OrgApiTest {

    private static final String[] EVENT_KEYS = {"id", "venue_id", "timezone", "series_id", "event_type", "title", "starts_at",
            "doors_open_at", "sale_starts_at", "status", "refund_cutoff_at", "reentry_policy", "per_account_limit",
            "metadata", "created_at"};

    private static String future(Duration d) {
        return Instant.now().plus(d).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private ResultActions create(Caller c, String body) throws Exception {
        return call(c, post("/org/events"), body);
    }

    private static void invalid(ResultActions r, String field) throws Exception {
        r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields['" + field + "']").isString());
    }

    private int auditRows(int eventId) {
        return jdbc.sql("select count(*) from audit_log where action = 'event.publish' and target = ?")
                .param(String.valueOf(eventId)).query(Integer.class).single();
    }

    // ---------- create, get, list ----------

    @Test
    void createReturnsTheDraftEvent() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String json = body(create(a, """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": " ATLAS FC – Raja ",
                 "starts_at": "2099-05-01T19:00:00Z", "doors_open_at": "2099-05-01T17:00:00Z",
                 "sale_starts_at": "2099-04-01T10:00:00Z", "reentry_policy": "NONE", "per_account_limit": 6,
                 "metadata": {"opponent": "Raja", "competition": "Botola Pro", "matchday": 12, "tv": "Arryadia"}}
                """.formatted(venueId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.venue_id").value(venueId))
                .andExpect(jsonPath("$.series_id").isEmpty())
                .andExpect(jsonPath("$.event_type").value("FOOTBALL_MATCH"))
                .andExpect(jsonPath("$.title").value("ATLAS FC – Raja"))
                .andExpect(jsonPath("$.starts_at").value("2099-05-01T19:00:00Z"))
                .andExpect(jsonPath("$.doors_open_at").value("2099-05-01T17:00:00Z"))
                .andExpect(jsonPath("$.sale_starts_at").value("2099-04-01T10:00:00Z"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.refund_cutoff_at").isEmpty())
                .andExpect(jsonPath("$.reentry_policy").value("NONE"))
                .andExpect(jsonPath("$.per_account_limit").value(6))
                .andExpect(jsonPath("$.metadata.opponent").value("Raja"))
                .andExpect(jsonPath("$.metadata.matchday").value(12))
                .andExpect(jsonPath("$.metadata.tv").value("Arryadia")) // unknown keys kept
                .andExpect(jsonPath("$.created_at").isString()));
        assertKeys(json, "$", EVENT_KEYS);
        int id = JsonPath.read(json, "$.id");
        assertThat(jdbc.sql("select organizer_id from events where id = ?").param(id).query(UUID.class).single())
                .isEqualTo(a.org());

        String got = body(call(a, get("/org/events/" + id)).andExpect(status().isOk()));
        assertKeys(got, "$", EVENT_KEYS);
        assertThat(JsonPath.<Object>read(got, "$")).isEqualTo(JsonPath.read(json, "$"));
    }

    /** timezone is the venue's, read-only, on every event representation and in list rows (plan Q7). */
    @Test
    void eventsCarryTheirVenuesTimezone() throws Exception {
        Caller a = admin();
        String casa = venue(a, "Grand Stade");
        String paris = JsonPath.read(body(call(a, post("/org/venues"),
                "{\"name\": \"Parc\", \"capacity\": 1000, \"timezone\": \"Europe/Paris\"}").andExpect(status().isCreated())), "$.id");
        int zone = zone(a, paris, "NORD");
        int casaEvent = event(a, casa, "2099-03-01T19:00:00Z");
        String json = body(create(a, """
                {"venue_id": "%s", "event_type": "CONCERT", "title": "Concert", "starts_at": "2099-04-01T19:00:00Z"}
                """.formatted(paris)).andExpect(status().isCreated()).andExpect(jsonPath("$.timezone").value("Europe/Paris")));
        int parisEvent = JsonPath.read(json, "$.id");

        call(a, get("/org/events/" + casaEvent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Africa/Casablanca"));
        call(a, patch("/org/events/" + parisEvent), "{\"title\": \"Concert 2\", \"timezone\": \"Asia/Tokyo\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Concert 2"))
                .andExpect(jsonPath("$.timezone").value("Europe/Paris")); // read-only: ignored on input
        price(a, parisEvent, zone, "Plein", 10);
        call(a, post("/org/events/" + parisEvent + "/publish")).andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Europe/Paris"))
                .andExpect(jsonPath("$.refund_cutoff_at").value("2099-04-01T18:00:00Z")); // an instant, zone-independent
        call(a, get("/org/events")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].venue.timezone").value(contains("Africa/Casablanca", "Europe/Paris")));
    }

    @Test
    void createDefaults() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        create(a, """
                {"venue_id": "%s", "event_type": "CONCERT", "title": "Concert", "starts_at": "2099-06-01T20:00:00+01:00"}
                """.formatted(venueId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.starts_at").value("2099-06-01T19:00:00Z")) // UTC on the wire
                .andExpect(jsonPath("$.doors_open_at").isEmpty())
                .andExpect(jsonPath("$.reentry_policy").value("NONE"))
                .andExpect(jsonPath("$.per_account_limit").value(4))
                .andExpect(jsonPath("$.metadata").isMap())
                .andExpect(jsonPath("$.metadata.length()").value(0));
    }

    @Test
    void createValidation() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String venueId = venue(a, "Stade");
        String start = "\"starts_at\": \"2099-05-01T19:00:00Z\"";

        create(a, "{\"title\": \" \"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.venue_id").isString())
                .andExpect(jsonPath("$.details.fields.event_type").isString())
                .andExpect(jsonPath("$.details.fields.title").isString())
                .andExpect(jsonPath("$.details.fields.starts_at").isString());
        String base = "\"venue_id\": \"%s\", \"event_type\": \"OTHER\", \"title\": \"T\", %s".formatted(venueId, start);
        invalid(create(a, "{" + base + ", \"per_account_limit\": 0}"), "per_account_limit");
        invalid(create(a, "{" + base + ", \"per_account_limit\": 21}"), "per_account_limit");
        invalid(create(a, "{" + base + ", \"reentry_policy\": \"ALWAYS\"}"), "reentry_policy");
        invalid(create(a, "{" + base + ", \"doors_open_at\": \"2099-05-01T19:00:01Z\"}"), "doors_open_at");
        invalid(create(a, "{\"venue_id\": \"%s\", \"event_type\": \"MATCH\", \"title\": \"T\", %s}".formatted(venueId, start)),
                "event_type");
        invalid(create(a, "{\"venue_id\": \"%s\", \"event_type\": \"OTHER\", \"title\": \"T\", %s}"
                .formatted(venue(b, "B"), start)), "venue_id"); // another organizer's venue
        invalid(create(a, "{\"venue_id\": \"%s\", \"event_type\": \"OTHER\", \"title\": \"T\", %s}"
                .formatted(UUID.randomUUID(), start)), "venue_id");
        create(a, "{" + base + ", \"doors_open_at\": \"2099-05-01T19:00:00Z\", \"per_account_limit\": 20}")
                .andExpect(status().isCreated());
    }

    @Test
    void listIsOrderedByStartsAtWithVenueAndSums() throws Exception {
        Caller a = admin();
        Caller b = admin();
        event(b, venue(b, "B"), "2099-01-01T00:00:00Z");
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int est = zone(a, venueId, "EST");
        int late = event(a, venueId, "2099-09-01T19:00:00Z");
        int early = event(a, venueId, "2099-03-01T19:00:00Z");
        price(a, early, nord, "Plein", 30);
        setSold(price(a, early, est, "Plein", 20), 7);

        String json = body(call(a, get("/org/events")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(early, late)))
                .andExpect(jsonPath("$[0].venue.id").value(venueId))
                .andExpect(jsonPath("$[0].venue.name").value("Stade"))
                .andExpect(jsonPath("$[0].quota").value(50))
                .andExpect(jsonPath("$[0].sold").value(7))
                .andExpect(jsonPath("$[1].quota").value(0))
                .andExpect(jsonPath("$[1].sold").value(0)));
        assertKeys(json, "$[0]", "id", "title", "event_type", "starts_at", "status", "venue", "quota", "sold");
        assertKeys(json, "$[0].venue", "id", "name", "timezone");

        call(a, get("/org/events").param("from", "2099-06-01T00:00:00Z"))
                .andExpect(jsonPath("$[*].id").value(contains(late)));
        setStatus(late, "ON_SALE");
        call(a, get("/org/events").param("status", "ON_SALE")).andExpect(jsonPath("$[*].id").value(contains(late)));
        call(a, get("/org/events").param("status", "DRAFT").param("from", "2099-06-01T00:00:00Z"))
                .andExpect(jsonPath("$.length()").value(0));
        call(a, get("/org/events").param("status", "SOLD")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        call(a, get("/org/events").param("from", "tomorrow")).andExpect(status().isBadRequest());
    }

    // ---------- metadata ----------

    @Test
    void metadataIsTypeCheckedPerEventType() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String football = "{\"venue_id\": \"%s\", \"event_type\": \"FOOTBALL_MATCH\", \"title\": \"T\", \"starts_at\": \"2099-05-01T19:00:00Z\", \"metadata\": %s}";
        invalid(create(a, football.formatted(venueId, "{\"matchday\": \"12\"}")), "metadata.matchday");
        invalid(create(a, football.formatted(venueId, "{\"matchday\": 12.5}")), "metadata.matchday");
        invalid(create(a, football.formatted(venueId, "{\"matchday\": 99999999999}")), "metadata.matchday");
        invalid(create(a, football.formatted(venueId, "{\"opponent\": 5}")), "metadata.opponent");
        invalid(create(a, football.formatted(venueId, "{\"competition\": null}")), "metadata.competition");
        invalid(create(a, football.formatted(venueId, "[1, 2]")), "metadata");
        invalid(create(a, football.formatted(venueId, "\"Raja\"")), "metadata");

        String concert = "{\"venue_id\": \"%s\", \"event_type\": \"%s\", \"title\": \"T\", \"starts_at\": \"2099-05-01T19:00:00Z\", \"metadata\": %s}";
        invalid(create(a, concert.formatted(venueId, "CONCERT", "{\"headliner\": 1}")), "metadata.headliner");
        invalid(create(a, concert.formatted(venueId, "FESTIVAL_DAY", "{\"headliner\": [\"A\"]}")), "metadata.headliner");
        create(a, concert.formatted(venueId, "CONCERT", "{\"headliner\": \"Hoba Hoba Spirit\", \"matchday\": \"x\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.metadata.matchday").value("x"));
        create(a, concert.formatted(venueId, "CONFERENCE", "{\"opponent\": 1, \"headliner\": 2}"))
                .andExpect(status().isCreated()); // free for other types
    }

    @Test
    void metadataIsAtMost4KB() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String body = "{\"venue_id\": \"%s\", \"event_type\": \"OTHER\", \"title\": \"T\", \"starts_at\": \"2099-05-01T19:00:00Z\", \"metadata\": {\"x\": \"%s\"}}";
        // {"x":"…"} is 8 bytes of JSON around the value.
        create(a, body.formatted(venueId, "a".repeat(4096 - 8))).andExpect(status().isCreated());
        invalid(create(a, body.formatted(venueId, "a".repeat(4096 - 7))), "metadata");
        invalid(create(a, body.formatted(venueId, "é".repeat(2045))), "metadata"); // bytes, not chars
    }

    // ---------- patch ----------

    @Test
    void patchIsPartialAndReturnsTheEvent() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int id = event(a, venueId);

        String json = body(call(a, patch("/org/events/" + id), """
                {"title": "Derby", "doors_open_at": "2099-05-01T17:30:00Z", "per_account_limit": 2,
                 "metadata": {"opponent": "Wydad"}}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Derby"))
                .andExpect(jsonPath("$.starts_at").value("2099-05-01T19:00:00Z"))
                .andExpect(jsonPath("$.doors_open_at").value("2099-05-01T17:30:00Z"))
                .andExpect(jsonPath("$.per_account_limit").value(2))
                .andExpect(jsonPath("$.reentry_policy").value("NONE"))
                .andExpect(jsonPath("$.metadata.opponent").value("Wydad"))
                .andExpect(jsonPath("$.refund_cutoff_at").isEmpty())); // DRAFT: set on publish only
        assertKeys(json, "$", EVENT_KEYS);

        call(a, patch("/org/events/" + id), "{\"reentry_policy\": \"NONE\", \"metadata\": null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Derby"))
                .andExpect(jsonPath("$.reentry_policy").value("NONE"))
                .andExpect(jsonPath("$.metadata.opponent").value("Wydad"));
        // venue_id and event_type may be sent unchanged
        call(a, patch("/org/events/" + id), "{\"venue_id\": \"%s\", \"event_type\": \"FOOTBALL_MATCH\"}".formatted(venueId))
                .andExpect(status().isOk());
    }

    /** Lot 2: re-entry is not built (plan lot-2 Q33). */
    @Test
    void oneExitIsRefusedOnCreateAndPatch() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String json = body(create(a, """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "2099-05-01T19:00:00Z",
                 "reentry_policy": "ONE_EXIT"}
                """.formatted(venueId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reason").value("NOT_SUPPORTED"))
                .andExpect(jsonPath("$.details.fields.reentry_policy").isString()));
        assertKeys(json, "$.details", "reason", "fields");
        assertThat(jdbc.sql("select count(*) from events e join venues v on v.id = e.venue_id where v.id = ?::uuid")
                .param(venueId).query(Integer.class).single()).isZero();

        int id = event(a, venueId);
        call(a, patch("/org/events/" + id), "{\"reentry_policy\": \"ONE_EXIT\", \"title\": \"Derby\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reason").value("NOT_SUPPORTED"))
                .andExpect(jsonPath("$.details.fields.reentry_policy").isString());
        call(a, get("/org/events/" + id)).andExpect(jsonPath("$.reentry_policy").value("NONE"))
                .andExpect(jsonPath("$.title").value("Match"));
    }

    @Test
    void patchMetadataReplacesTheWholeObject() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int id = JsonPath.read(body(create(a, """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "2099-05-01T19:00:00Z",
                 "metadata": {"opponent": "Raja", "tv": "X"}}
                """.formatted(venueId)).andExpect(status().isCreated())), "$.id");
        call(a, patch("/org/events/" + id), "{\"metadata\": {\"opponent\": \"Wydad\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.opponent").value("Wydad"))
                .andExpect(jsonPath("$.metadata.tv").doesNotExist());
        call(a, get("/org/events/" + id)).andExpect(jsonPath("$.metadata.tv").doesNotExist())
                .andExpect(jsonPath("$.metadata.length()").value(1));
    }

    @Test
    void patchValidation() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int id = event(a, venueId);
        invalid(call(a, patch("/org/events/" + id), "{\"venue_id\": \"%s\"}".formatted(venue(a, "Salle"))), "venue_id");
        invalid(call(a, patch("/org/events/" + id), "{\"event_type\": \"CONCERT\"}"), "event_type");
        invalid(call(a, patch("/org/events/" + id), "{\"title\": \"  \"}"), "title");
        invalid(call(a, patch("/org/events/" + id), "{\"per_account_limit\": 21}"), "per_account_limit");
        invalid(call(a, patch("/org/events/" + id), "{\"doors_open_at\": \"2099-05-01T19:30:00Z\"}"), "doors_open_at");
        invalid(call(a, patch("/org/events/" + id), "{\"starts_at\": \"2099-04-01T19:00:00Z\", \"doors_open_at\": \"2099-04-02T00:00:00Z\"}"),
                "doors_open_at");
        invalid(call(a, patch("/org/events/" + id), "{\"metadata\": {\"matchday\": \"1\"}}"), "metadata.matchday");
        call(a, get("/org/events/" + id))
                .andExpect(jsonPath("$.venue_id").value(venueId))
                .andExpect(jsonPath("$.event_type").value("FOOTBALL_MATCH"))
                .andExpect(jsonPath("$.title").value("Match"));
    }

    /** Lot 1 (plan Q19, Q20): sale_starts_at < starts_at − 60 min on every write that changes either field. */
    @Test
    void saleStartsAtMustBeBeforeTheSalesClose() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String base = "\"venue_id\": \"%s\", \"event_type\": \"OTHER\", \"title\": \"T\", \"starts_at\": \"2099-05-01T19:00:00Z\""
                .formatted(venueId);
        invalid(create(a, "{" + base + ", \"sale_starts_at\": \"2099-05-01T18:00:00Z\"}"), "sale_starts_at"); // = T0 − 60
        invalid(create(a, "{" + base + ", \"sale_starts_at\": \"2099-05-02T00:00:00Z\"}"), "sale_starts_at");
        create(a, "{" + base + "}").andExpect(status().isCreated()).andExpect(jsonPath("$.sale_starts_at").isEmpty());
        int id = JsonPath.read(body(create(a, "{" + base + ", \"sale_starts_at\": \"2099-05-01T17:59:59Z\"}")
                .andExpect(status().isCreated())), "$.id");

        invalid(call(a, patch("/org/events/" + id), "{\"starts_at\": \"2099-05-01T18:59:59Z\"}"), "sale_starts_at");
        invalid(call(a, patch("/org/events/" + id), "{\"sale_starts_at\": \"2099-05-01T18:00:00Z\"}"), "sale_starts_at");
        call(a, patch("/org/events/" + id), "{\"starts_at\": \"2099-04-01T19:00:00Z\", \"sale_starts_at\": \"2099-03-01T10:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sale_starts_at").value("2099-03-01T10:00:00Z"));
        // null = unchanged: it can't be cleared
        call(a, patch("/org/events/" + id), "{\"sale_starts_at\": null, \"title\": \"U\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sale_starts_at").value("2099-03-01T10:00:00Z"));

        price(a, id, zone(a, venueId, "NORD"), "Plein", 10);
        call(a, post("/org/events/" + id + "/publish")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sale_starts_at").value("2099-03-01T10:00:00Z"));
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        call(a, patch("/org/events/" + id), "{\"sale_starts_at\": \"%s\"}".formatted(now)) // ON_SALE: may move it
                .andExpect(status().isOk()).andExpect(jsonPath("$.sale_starts_at").value(now));
        invalid(call(a, patch("/org/events/" + id), "{\"starts_at\": \"%s\"}".formatted(now)), "sale_starts_at");
    }

    @Test
    void patchOutsideDraftOrOnSaleIsNotEditable() throws Exception {
        Caller a = admin();
        int id = event(a, venue(a, "Stade"));
        for (String s : List.of("GATES_OPEN", "CLOSED")) {
            setStatus(id, s);
            call(a, patch("/org/events/" + id), "{\"title\": \"X\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
        }
        call(a, get("/org/events/" + id)).andExpect(jsonPath("$.title").value("Match"));
    }

    @Test
    void changingStartsAtWhileOnSaleMovesTheRefundCutoff() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int id = event(a, venueId, future(Duration.ofDays(10)));
        price(a, id, zone(a, venueId, "NORD"), "Plein", 10);
        call(a, post("/org/events/" + id + "/publish")).andExpect(status().isOk());

        String later = future(Duration.ofDays(12));
        call(a, patch("/org/events/" + id), "{\"starts_at\": \"%s\"}".formatted(later))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_SALE"))
                .andExpect(jsonPath("$.starts_at").value(later))
                .andExpect(jsonPath("$.refund_cutoff_at").value(Instant.parse(later).minus(Duration.ofMinutes(60)).toString()));
    }

    // ---------- publish ----------

    @Test
    void publishSetsOnSaleRefundCutoffAndAudit() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        String startsAt = future(Duration.ofDays(7));
        int id = event(a, venueId, startsAt);
        price(a, id, zone(a, venueId, "NORD"), "Plein", 10);

        String json = body(call(a, post("/org/events/" + id + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_SALE"))
                .andExpect(jsonPath("$.refund_cutoff_at")
                        .value(Instant.parse(startsAt).minus(Duration.ofMinutes(60)).toString())));
        assertKeys(json, "$", EVENT_KEYS);

        var row = jdbc.sql("select organizer_id, actor_type, actor_id, action, target from audit_log where target = ? and action = 'event.publish'")
                .param(String.valueOf(id)).query().singleRow();
        assertThat(row.get("organizer_id")).isEqualTo(a.org());
        assertThat(row.get("actor_type")).isEqualTo("STAFF");
        UUID userId = jdbc.sql("select user_id from staff where organizer_id = ?").param(a.org()).query(UUID.class).single();
        assertThat(row.get("actor_id")).isEqualTo(userId);

        call(a, post("/org/events/" + id + "/publish"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
        assertThat(auditRows(id)).isOne();
    }

    @Test
    void publishPreconditions() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");

        int noPrices = event(a, venueId, future(Duration.ofDays(7)));
        call(a, post("/org/events/" + noPrices + "/publish"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reason").value("NO_QUOTA"));
        price(a, noPrices, nord, "Invitation", 0); // quota 0 doesn't count
        call(a, post("/org/events/" + noPrices + "/publish"))
                .andExpect(jsonPath("$.details.reason").value("NO_QUOTA"));

        int past = event(a, venueId, Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS).toString());
        price(a, past, nord, "Plein", 10);
        call(a, post("/org/events/" + past + "/publish"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("STARTS_IN_PAST"))
                .andExpect(jsonPath("$.details.fields.starts_at").isString());

        for (int id : List.of(noPrices, past)) {
            call(a, get("/org/events/" + id)).andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.refund_cutoff_at").isEmpty());
            assertThat(auditRows(id)).isZero();
        }
        int closed = event(a, venueId, future(Duration.ofDays(7)));
        price(a, closed, nord, "Plein", 10);
        setStatus(closed, "CLOSED");
        call(a, post("/org/events/" + closed + "/publish"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
    }

    @Test
    void concurrentPublishesTransitionOnce() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int id = event(a, venueId, future(Duration.ofDays(7)));
        price(a, id, zone(a, venueId, "NORD"), "Plein", 10);

        int threads = 6;
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call(a, post("/org/events/" + id + "/publish")).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> r : results) {
                statuses.add(r.get());
            }
        }
        assertThat(statuses).containsOnlyOnce(200).filteredOn(s -> s != 200).containsOnly(409);
        assertThat(auditRows(id)).isOne();
    }

    // ---------- tenant isolation and roles ----------

    @Test
    void anotherOrganizersEventIs404OnEveryEndpoint() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String bVenue = venue(b, "B");
        int bZone = zone(b, bVenue, "NORD");
        int bEvent = event(b, bVenue, future(Duration.ofDays(7)));
        String bPrice = price(b, bEvent, bZone, "Plein", 10);
        int aEvent = event(a, venue(a, "A"));

        List<ResultActions> calls = List.of(
                call(a, get("/org/events/" + bEvent)),
                call(a, patch("/org/events/" + bEvent), "{\"title\": \"Pwned\"}"),
                call(a, post("/org/events/" + bEvent + "/publish")),
                call(a, get("/org/events/" + bEvent + "/prices")),
                call(a, post("/org/events/" + bEvent + "/prices"),
                        "{\"zone_id\": %d, \"name\": \"X\", \"price_mad\": 1, \"quota\": 1}".formatted(bZone)),
                call(a, patch("/org/events/" + bEvent + "/prices/" + bPrice), "{\"quota\": 1}"),
                call(a, patch("/org/events/" + aEvent + "/prices/" + bPrice), "{\"quota\": 1}"),
                call(a, delete("/org/events/" + bEvent + "/prices/" + bPrice)),
                call(a, delete("/org/events/" + aEvent + "/prices/" + bPrice)),
                call(a, get("/org/events/999999999")));
        for (ResultActions r : calls) {
            r.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        call(a, get("/org/events")).andExpect(jsonPath("$[*].id").value(contains(aEvent)));

        call(b, get("/org/events/" + bEvent)).andExpect(jsonPath("$.title").value("Match"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        call(b, get("/org/events/" + bEvent + "/prices")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quota").value(10));
    }

    @Test
    void financeCanReadButNotWrite() throws Exception {
        Caller admin = admin();
        Caller finance = caller(admin.org(), "FINANCE");
        String venueId = venue(admin, "Stade");
        int zoneId = zone(admin, venueId, "NORD");
        int id = event(admin, venueId, future(Duration.ofDays(7)));
        String priceId = price(admin, id, zoneId, "Plein", 10);

        call(finance, get("/org/events")).andExpect(status().isOk());
        call(finance, get("/org/events/" + id)).andExpect(status().isOk());
        call(finance, get("/org/events/" + id + "/prices")).andExpect(status().isOk());

        List<ResultActions> writes = List.of(
                call(finance, post("/org/events"), "{\"venue_id\": \"%s\", \"event_type\": \"OTHER\", \"title\": \"X\", \"starts_at\": \"2099-01-01T00:00:00Z\"}".formatted(venueId)),
                call(finance, patch("/org/events/" + id), "{\"title\": \"X\"}"),
                call(finance, post("/org/events/" + id + "/publish")),
                call(finance, post("/org/events/" + id + "/prices"),
                        "{\"zone_id\": %d, \"name\": \"X\", \"price_mad\": 1, \"quota\": 1}".formatted(zoneId)),
                call(finance, patch("/org/events/" + id + "/prices/" + priceId), "{\"quota\": 1}"),
                call(finance, delete("/org/events/" + id + "/prices/" + priceId)));
        for (ResultActions r : writes) {
            r.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        call(admin, get("/org/events")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].quota").value(10));
    }
}
