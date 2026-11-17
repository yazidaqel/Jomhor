package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import ma.jomhor.catalog.application.Venues;
import ma.jomhor.gate.application.ManifestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract: PUT /org/venues/{id}/map and layout / map_slot on the venue reads (docs/04-api.md §Organizer, §Venue map):
 * the whole map replaced under the venue lock, clear then set (a swap never meets the unique key), the 04 check order
 * with its exact field keys and nothing written on a 422, no 500 under concurrency, roles, and never a manifest version.
 */
class VenueMapApiTest extends OrgApiTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    Venues venuesPort;

    @Autowired
    ManifestService manifests;

    private ResultActions putMap(Caller c, String venueId, String body) throws Exception {
        return call(c, put("/org/venues/" + venueId + "/map"), body);
    }

    /** {"layout": layout, "zones": [{zone_id, map_slot}...]} from zone id / slot pairs. */
    private static String map(String layout, Object... pairs) {
        List<String> zones = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            zones.add("{\"zone_id\": %s, \"map_slot\": \"%s\"}".formatted(pairs[i], pairs[i + 1]));
        }
        return "{\"layout\": %s, \"zones\": [%s]}".formatted(layout == null ? "null" : "\"" + layout + "\"",
                String.join(", ", zones));
    }

    /** "layout code=slot ..." as stored, zones by code (an unmapped zone reads code=null). */
    private String stored(String venueId) {
        String layout = jdbc.sql("select layout from venues where id = ?::uuid").param(venueId).query(String.class)
                .optional().orElse(null);
        Map<String, String> slots = new TreeMap<>();
        jdbc.sql("select code, map_slot from zones where venue_id = ?::uuid").param(venueId)
                .query((rs, n) -> slots.put(rs.getString("code"), rs.getString("map_slot"))).list();
        StringBuilder s = new StringBuilder(String.valueOf(layout));
        slots.forEach((code, slot) -> s.append(' ').append(code).append('=').append(slot));
        return s.toString();
    }

    private static void assertField(ResultActions r, String field) throws Exception {
        String json = body(r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")));
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.details.fields")).as(json).containsOnlyKeys(field);
    }

    // ---------- round trip ----------

    @Test
    void roundTripOmittedZoneSwapAndClear() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int sud = zone(a, venueId, "SUD");
        int est = zone(a, venueId, "EST");
        call(a, get("/org/venues/" + venueId)).andExpect(jsonPath("$.layout").value((Object) null))
                .andExpect(jsonPath("$.zones[0].map_slot").value((Object) null));

        String json = body(putMap(a, venueId, map("STADIUM", nord, "NORTH", sud, "SOUTH", est, "EAST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(venueId))
                .andExpect(jsonPath("$.layout").value("STADIUM"))
                .andExpect(jsonPath("$.zones[?(@.code == 'EST')].map_slot").value("EAST")));
        assertKeys(json, "$", "id", "name", "city", "capacity", "timezone", "layout", "zones", "gates");
        assertKeys(json, "$.zones[0]", "id", "code", "name", "capacity", "seated", "map_slot");
        assertThat(body(call(a, get("/org/venues/" + venueId)).andExpect(status().isOk()))).isEqualTo(json);
        call(a, get("/org/venues")).andExpect(jsonPath("$[0].layout").value("STADIUM"));
        call(a, patch("/org/venues/" + venueId), "{\"name\": \"Grand Stade\"}")
                .andExpect(jsonPath("$.layout").value("STADIUM"));
        assertThat(stored(venueId)).isEqualTo("STADIUM EST=EAST NORD=NORTH SUD=SOUTH");

        // an omitted zone loses its slot; two zones swap in one PUT (clear then set: no unique-key error)
        putMap(a, venueId, map("STADIUM", nord, "SOUTH", sud, "NORTH")).andExpect(status().isOk())
                .andExpect(jsonPath("$.zones[?(@.code == 'EST')].map_slot").value((Object) null));
        assertThat(stored(venueId)).isEqualTo("STADIUM EST=null NORD=SOUTH SUD=NORTH");

        // another layout keeps no old slot; a layout with no zone on it; then no map at all (layout absent = null)
        putMap(a, venueId, map("THEATRE", est, "ORCHESTRA")).andExpect(status().isOk());
        assertThat(stored(venueId)).isEqualTo("THEATRE EST=ORCHESTRA NORD=null SUD=null");
        putMap(a, venueId, map("END_STAGE")).andExpect(status().isOk()).andExpect(jsonPath("$.layout").value("END_STAGE"));
        assertThat(stored(venueId)).isEqualTo("END_STAGE EST=null NORD=null SUD=null");
        putMap(a, venueId, map("STADIUM", nord, "NORTH")).andExpect(status().isOk());
        putMap(a, venueId, "{\"zones\": []}").andExpect(status().isOk()).andExpect(jsonPath("$.layout").value((Object) null));
        assertThat(stored(venueId)).isEqualTo("null EST=null NORD=null SUD=null");
        putMap(a, venueId, map("STADIUM", nord, "NORTH")).andExpect(status().isOk());
        putMap(a, venueId, map(null)).andExpect(status().isOk());
        assertThat(stored(venueId)).isEqualTo("null EST=null NORD=null SUD=null");
    }

    /** Zone POST and PATCH don't take map_slot: it is set only by the PUT. */
    @Test
    void zoneWritesNeverSetASlot() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        putMap(a, venueId, map("STADIUM", nord, "NORTH")).andExpect(status().isOk());
        call(a, post("/org/venues/" + venueId + "/zones"),
                "{\"code\": \"SUD\", \"name\": \"Sud\", \"capacity\": 10, \"map_slot\": \"SOUTH\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.map_slot").value((Object) null));
        call(a, patch("/org/venues/" + venueId + "/zones/" + nord), "{\"name\": \"Nord\", \"map_slot\": \"WEST\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.map_slot").value("NORTH"));
        assertThat(stored(venueId)).isEqualTo("STADIUM NORD=NORTH SUD=null");
    }

    @Test
    void deletingAMappedZoneFreesItsSlot() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int sud = zone(a, venueId, "SUD");
        putMap(a, venueId, map("STADIUM", nord, "NORTH")).andExpect(status().isOk());
        call(a, delete("/org/venues/" + venueId + "/zones/" + nord)).andExpect(status().isNoContent());
        putMap(a, venueId, map("STADIUM", sud, "NORTH")).andExpect(status().isOk());
        assertThat(stored(venueId)).isEqualTo("STADIUM SUD=NORTH");
    }

    // ---------- 422, in the 04 order ----------

    @Test
    void everyFieldKeyInOrderAndNothingWritten() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int sud = zone(a, venueId, "SUD");
        int elsewhere = zone(a, venue(a, "Salle"), "NORD");
        int otherOrg = zone(b, venue(b, "Stade B"), "NORD");
        putMap(a, venueId, map("STADIUM", nord, "NORTH", sud, "SOUTH")).andExpect(status().isOk());
        String before = stored(venueId);
        String sixtyFive = String.join(", ", Collections.nCopies(65, "{\"zone_id\": %d, \"map_slot\": \"NORTH\"}"
                .formatted(nord)));

        // shape
        assertField(putMap(a, venueId, "{\"layout\": 5, \"zones\": []}"), "layout");
        assertField(putMap(a, venueId, "{\"layout\": [\"STADIUM\"]}"), "layout"); // layout before zones
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\"}"), "zones");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": null}"), "zones");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": {}}"), "zones");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": [" + sixtyFive + "]}"), "zones");
        assertField(putMap(a, venueId, "[]"), "zones");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": [{\"zone_id\": \"%d\", \"map_slot\": \"NORTH\"}]}"
                .formatted(nord)), "zones[0].zone_id");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": [{\"zone_id\": 1.5, \"map_slot\": \"NORTH\"}]}"),
                "zones[0].zone_id");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": [{\"map_slot\": \"NORTH\"}]}"), "zones[0].zone_id");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": [5]}"), "zones[0].zone_id");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": [{\"zone_id\": %d, \"map_slot\": null}]}"
                .formatted(nord)), "zones[0].map_slot");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": [{\"zone_id\": %d, \"map_slot\": 3}]}"
                .formatted(nord)), "zones[0].map_slot");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": [{\"zone_id\": %d}]}".formatted(nord)),
                "zones[0].map_slot");
        assertField(putMap(a, venueId, "{\"layout\": \"STADIUM\", \"zones\": [{\"zone_id\": %d, \"map_slot\": \"NORTH\"}, "
                .formatted(nord) + "{\"zone_id\": true, \"map_slot\": \"SOUTH\"}]}"), "zones[1].zone_id");
        // shape is checked before the venue: an unknown venue with a bad body is a 422, with a good one a 404
        assertField(putMap(a, UUID.randomUUID().toString(), "{\"layout\": 5, \"zones\": []}"), "layout");
        putMap(a, UUID.randomUUID().toString(), map("STADIUM")).andExpect(status().isNotFound());

        // content
        assertField(putMap(a, venueId, map("ARENA")), "layout");
        assertField(putMap(a, venueId, map("stadium")), "layout");
        assertField(putMap(a, venueId, map("ARENA", nord, "NORTH")), "layout");
        assertField(putMap(a, venueId, map(null, nord, "NORTH")), "zones");
        assertField(putMap(a, venueId, map("STADIUM", elsewhere, "NORTH")), "zones[0].zone_id");
        assertField(putMap(a, venueId, map("STADIUM", otherOrg, "NORTH")), "zones[0].zone_id");
        assertField(putMap(a, venueId, map("STADIUM", 32767, "NORTH")), "zones[0].zone_id");
        assertField(putMap(a, venueId, map("STADIUM", "123456789012345678901234567890", "NORTH")), "zones[0].zone_id");
        assertField(putMap(a, venueId, map("STADIUM", nord, "NORTH", nord, "SOUTH")), "zones[1].zone_id");
        assertField(putMap(a, venueId, map("STADIUM", nord, "ORCHESTRA")), "zones[0].map_slot");
        assertField(putMap(a, venueId, map("STADIUM", nord, "north")), "zones[0].map_slot");
        assertField(putMap(a, venueId, map("STADIUM", nord, "NORTH", sud, "NORTH")), "zones[1].map_slot");
        // each entry in order: entry 0's slot fails before entry 1's zone
        assertField(putMap(a, venueId, map("STADIUM", nord, "STAGE", elsewhere, "SOUTH")), "zones[0].map_slot");
        assertField(putMap(a, venueId, map("THEATRE", sud, "ORCHESTRA", nord, "NORTH")), "zones[1].map_slot");

        assertThat(stored(venueId)).isEqualTo(before);
    }

    /**
     * A zone deleted after the check (its delete still uncommitted while the PUT reads the zones): the PUT's update
     * waits for it, finds no row, answers 422 on that entry and rolls back the entries before it.
     */
    @Test
    void aZoneDeletedMeanwhileIsA422AndNothingIsWritten() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int sud = zone(a, venueId, "SUD");
        try (Connection tx = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            tx.setAutoCommit(false);
            try (var del = tx.prepareStatement("delete from zones where id = ?")) {
                del.setInt(1, sud);
                del.executeUpdate();
            }
            Future<MockHttpServletResponse> put = pool.submit(() ->
                    putMap(a, venueId, map("STADIUM", nord, "NORTH", sud, "SOUTH")).andReturn().getResponse());
            // only the PUT's slot update (pgjdbc sends it with $1 placeholders), not any other waiter in the database
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (jdbc.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                            + "and datname = current_database() and query like 'update zones set map_slot = $1 %'")
                    .query(Integer.class).single() == 0) {
                assertThat(System.nanoTime()).as("PUT should wait on the deleted zone").isLessThan(deadline);
                Thread.onSpinWait();
            }
            tx.commit();
            MockHttpServletResponse r = put.get(10, TimeUnit.SECONDS);
            assertThat(r.getStatus()).isEqualTo(422);
            assertThat(JsonPath.<Map<String, Object>>read(r.getContentAsString(), "$.details.fields"))
                    .containsOnlyKeys("zones[1].zone_id");
        }
        assertThat(stored(venueId)).isEqualTo("null NORD=null");
    }

    // ---------- concurrency ----------

    @Test
    void concurrentConflictingPutsBothSucceedAndOneWins() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int sud = zone(a, venueId, "SUD");
        String first = map("STADIUM", nord, "NORTH", sud, "SOUTH");
        String second = map("STADIUM", nord, "SOUTH", sud, "NORTH");

        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 10; round++) {
                var start = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (String payload : List.of(first, second)) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return putMap(a, venueId, payload).andReturn().getResponse().getStatus();
                    }));
                }
                start.countDown();
                for (Future<Integer> r : results) {
                    assertThat(r.get()).isEqualTo(200);
                }
                assertThat(stored(venueId)).isIn("STADIUM NORD=NORTH SUD=SOUTH", "STADIUM NORD=SOUTH SUD=NORTH");
            }
        }
    }

    /** Deterministic: a PUT waits for the venue lock held by an uncommitted swap, then replaces it. */
    @Test
    void aPutWaitsForTheVenueLock() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        UUID venue = UUID.fromString(venueId);
        int nord = zone(a, venueId, "NORD");
        int sud = zone(a, venueId, "SUD");
        putMap(a, venueId, map("STADIUM", nord, "NORTH", sud, "SOUTH")).andExpect(status().isOk());
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<MockHttpServletResponse> put = new TransactionTemplate(transactions).execute(t -> {
                venuesPort.lockVenue(a.org(), venue).orElseThrow();
                venuesPort.clearMapSlots(a.org(), venue);
                venuesPort.setMapSlot(a.org(), venue, nord, "SOUTH");
                venuesPort.setMapSlot(a.org(), venue, sud, "NORTH");
                Future<MockHttpServletResponse> f = pool.submit(() ->
                        putMap(a, venueId, map("STADIUM", nord, "WEST", sud, "SOUTH")).andReturn().getResponse());
                return stillBlocked(f); // then commit
            });
            assertThat(put.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        }
        assertThat(stored(venueId)).isEqualTo("STADIUM NORD=WEST SUD=SOUTH");
    }

    private static <T> Future<T> stillBlocked(Future<T> f) {
        try {
            f.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException expected) {
            return f;
        } catch (Exception e) {
            throw new AssertionError("PUT did not wait for the venue lock", e);
        }
        throw new AssertionError("PUT did not wait for the venue lock");
    }

    // ---------- roles and tenancy ----------

    @Test
    void rolesAndAnotherOrganizer() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        putMap(a, venueId, map("STADIUM", nord, "NORTH")).andExpect(status().isOk());
        putMap(caller(a.org(), "OPS"), venueId, map("STADIUM", nord, "EAST")).andExpect(status().isOk());

        Caller finance = caller(a.org(), "FINANCE");
        putMap(finance, venueId, map(null)).andExpect(status().isForbidden());
        call(finance, get("/org/venues/" + venueId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.layout").value("STADIUM"))
                .andExpect(jsonPath("$.zones[0].map_slot").value("EAST"));
        Caller cashier = caller(a.org(), "CASHIER");
        putMap(cashier, venueId, map(null)).andExpect(status().isForbidden());
        call(cashier, get("/org/venues/" + venueId)).andExpect(status().isForbidden());
        for (String role : List.of("GATE", "SUPERVISOR")) {
            String phone = randomPhone();
            staff(organizer("Org " + UUID.randomUUID()), phone, "ADMIN", true); // to get an org token at all
            staff(a.org(), phone, role, true);
            putMap(new Caller(a.org(), orgToken(phone)), venueId, map(null)).andExpect(status().isForbidden());
        }

        Caller b = admin();
        putMap(b, venueId, map(null)).andExpect(status().isNotFound());
        putMap(b, venueId, map("STADIUM", nord, "WEST")).andExpect(status().isNotFound());
        assertThat(stored(venueId)).isEqualTo("STADIUM NORD=EAST");
    }

    // ---------- the gate path never sees it ----------

    @Test
    void aMapChangeBuildsNoManifestVersion() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int eventId = event(a, venueId, Instant.now().plus(Duration.ofHours(1)).toString());
        price(a, eventId, nord, "Normal", 10);
        setStatus(eventId, "ON_SALE");
        assertThat(manifests.build(a.org(), eventId)).contains(1);
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(eventId)
                .update();

        putMap(a, venueId, map("STADIUM", nord, "NORTH")).andExpect(status().isOk());
        assertThat(manifests.build(a.org(), eventId)).isEmpty();
        putMap(a, venueId, map("THEATRE", nord, "BALCONY")).andExpect(status().isOk());
        assertThat(manifests.build(a.org(), eventId)).isEmpty();
        assertThat(jdbc.sql("select max(version) from manifests where event_id = ?").param(eventId)
                .query(Integer.class).single()).isEqualTo(1);
    }
}
