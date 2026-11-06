package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract: /org/venues, /org/venues/{id}, /org/venues/{id}/zones[/{zone_id}], /org/venues/{id}/gates[/{gate_id}],
 * /org/gates/{id}/zones (docs/04-api.md §Organizer). Tenant scoping, CODE_TAKEN, ZONE_IN_USE, FINANCE read-only.
 */
class VenueApiTest extends OrgApiTest {

    @Autowired
    DataSource dataSource;

    private String gate(Caller c, String venueId, String code) throws Exception {
        String json = body(call(c, post("/org/venues/" + venueId + "/gates"),
                "{\"code\": \"%s\", \"name\": \"Porte %s\"}".formatted(code, code)).andExpect(status().isCreated()));
        return JsonPath.read(json, "$.id");
    }

    private ResultActions putGateZones(Caller c, String gateId, Object... zoneIds) throws Exception {
        String ids = String.join(", ", Arrays.stream(zoneIds).map(String::valueOf).toList());
        return call(c, put("/org/gates/" + gateId + "/zones"), "{\"zone_ids\": [%s]}".formatted(ids));
    }

    private int gateZoneRows(String column, Object id) {
        return jdbc.sql("select count(*) from gate_zones where " + column + " = ?").param(id).query(Integer.class).single();
    }

    // ---------- venues ----------

    @Test
    void createAndListVenues() throws Exception {
        Caller a = admin();
        Caller b = admin();
        venue(b, "Other club's stadium");

        String json = body(call(a, post("/org/venues"), "{\"name\": \" Grand Stade \", \"city\": \"Casablanca\", \"capacity\": 45000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.name").value("Grand Stade"))
                .andExpect(jsonPath("$.city").value("Casablanca"))
                .andExpect(jsonPath("$.capacity").value(45000)));
        assertKeys(json, "$", "id", "name", "city", "capacity");
        String id = JsonPath.read(json, "$.id");
        assertThat(jdbc.sql("select organizer_id from venues where id = ?::uuid").param(id).query(UUID.class).single())
                .isEqualTo(a.org());

        json = body(call(a, post("/org/venues"), "{\"name\": \"Salle\", \"capacity\": 300}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.city").doesNotExist()));
        assertKeys(json, "$", "id", "name", "city", "capacity");

        json = body(call(a, get("/org/venues")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Grand Stade", "Salle"))));
        assertKeys(json, "$[0]", "id", "name", "city", "capacity");
    }

    @Test
    void createVenueValidation() throws Exception {
        Caller a = admin();
        call(a, post("/org/venues"), "{\"name\": \"  \", \"capacity\": 0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.name").exists())
                .andExpect(jsonPath("$.details.fields.capacity").exists());
        call(a, post("/org/venues"), "{\"name\": \"Stade\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.capacity").exists());
    }

    @Test
    void getVenueReturnsZonesAndGatesWithZoneIds() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int est = zone(a, venueId, "EST");
        String p1 = gate(a, venueId, "P1");
        gate(a, venueId, "P2");
        putGateZones(a, p1, nord, est).andExpect(status().isOk());

        String json = body(call(a, get("/org/venues/" + venueId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(venueId))
                .andExpect(jsonPath("$.zones[*].code").value(contains("EST", "NORD")))
                .andExpect(jsonPath("$.gates[*].code").value(contains("P1", "P2")))
                .andExpect(jsonPath("$.gates[0].zone_ids").value(contains(Math.min(nord, est), Math.max(nord, est))))
                .andExpect(jsonPath("$.gates[1].zone_ids", empty())));
        assertKeys(json, "$", "id", "name", "city", "capacity", "zones", "gates");
        assertKeys(json, "$.zones[0]", "id", "code", "name", "capacity", "seated");
        assertKeys(json, "$.gates[0]", "id", "code", "name", "zone_ids");
    }

    @Test
    void patchVenueIsPartial() throws Exception {
        Caller a = admin();
        String venueId = body(call(a, post("/org/venues"), "{\"name\": \"Stade\", \"city\": \"Rabat\", \"capacity\": 1000}")
                .andExpect(status().isCreated()));
        venueId = JsonPath.read(venueId, "$.id");

        String json = body(call(a, patch("/org/venues/" + venueId), "{\"capacity\": 2000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Stade"))
                .andExpect(jsonPath("$.city").value("Rabat"))
                .andExpect(jsonPath("$.capacity").value(2000)));
        assertKeys(json, "$", "id", "name", "city", "capacity", "zones", "gates");

        call(a, patch("/org/venues/" + venueId), "{\"name\": \"Nouveau\", \"city\": \"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nouveau"))
                .andExpect(jsonPath("$.city").doesNotExist());
        call(a, patch("/org/venues/" + venueId), "{\"name\": \" \", \"capacity\": -1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.name").exists())
                .andExpect(jsonPath("$.details.fields.capacity").exists());
    }

    // ---------- zones ----------

    @Test
    void zoneCreatePatchDelete() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");

        String json = body(call(a, post("/org/venues/" + venueId + "/zones"), "{\"code\": \" nord \", \"name\": \"Nord\", \"capacity\": 0}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value("nord"))
                .andExpect(jsonPath("$.name").value("Nord"))
                .andExpect(jsonPath("$.capacity").value(0))
                .andExpect(jsonPath("$.seated").value(false)));
        assertKeys(json, "$", "id", "code", "name", "capacity", "seated");
        int zoneId = JsonPath.read(json, "$.id");
        assertThat(jdbc.sql("select organizer_id from zones where id = ?").param(zoneId).query(UUID.class).single())
                .isEqualTo(a.org());

        json = body(call(a, patch("/org/venues/" + venueId + "/zones/" + zoneId), "{\"capacity\": 500, \"seated\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(zoneId))
                .andExpect(jsonPath("$.code").value("nord"))
                .andExpect(jsonPath("$.capacity").value(500))
                .andExpect(jsonPath("$.seated").value(true)));
        assertKeys(json, "$", "id", "code", "name", "capacity", "seated");

        String gateId = gate(a, venueId, "P1");
        putGateZones(a, gateId, zoneId).andExpect(status().isOk());
        assertThat(gateZoneRows("zone_id", zoneId)).isEqualTo(1);

        call(a, delete("/org/venues/" + venueId + "/zones/" + zoneId))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(gateZoneRows("zone_id", zoneId)).isZero();
        call(a, get("/org/venues/" + venueId)).andExpect(jsonPath("$.zones", empty()))
                .andExpect(jsonPath("$.gates[0].zone_ids", empty()));
        call(a, delete("/org/venues/" + venueId + "/zones/" + zoneId)).andExpect(status().isNotFound());
    }

    @Test
    void zoneValidation() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        call(a, post("/org/venues/" + venueId + "/zones"), "{\"code\": \"\", \"name\": \" \", \"capacity\": -1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.code").exists())
                .andExpect(jsonPath("$.details.fields.name").exists())
                .andExpect(jsonPath("$.details.fields.capacity").exists());
        int zoneId = zone(a, venueId, "NORD");
        call(a, patch("/org/venues/" + venueId + "/zones/" + zoneId), "{\"code\": \"  \", \"capacity\": -5}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.code").exists())
                .andExpect(jsonPath("$.details.fields.capacity").exists());
    }

    @Test
    void zoneReferencedByAPriceCategoryIsInUse() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int zoneId = zone(a, venueId, "NORD");
        String gateId = gate(a, venueId, "P1");
        putGateZones(a, gateId, zoneId).andExpect(status().isOk());
        int eventId = jdbc.sql("""
                        insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                        values (?, ?::uuid, 'Match', now() + interval '7 days', '\\x00'::bytea) returning id
                        """)
                .params(a.org(), venueId).query(Integer.class).single();
        jdbc.sql("insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota) values (?, ?, ?, 'Plein', 5000, 10)")
                .params(a.org(), eventId, zoneId).update();

        call(a, delete("/org/venues/" + venueId + "/zones/" + zoneId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ZONE_IN_USE"));
        assertThat(jdbc.sql("select count(*) from zones where id = ?").param(zoneId).query(Integer.class).single()).isOne();
        assertThat(gateZoneRows("zone_id", zoneId)).isOne(); // rolled back with the refused delete
    }

    // ---------- gates ----------

    @Test
    void gateCreatePatchDelete() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int zoneId = zone(a, venueId, "NORD");

        String json = body(call(a, post("/org/venues/" + venueId + "/gates"), "{\"code\": \" P4 \", \"name\": \"Porte 4\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.code").value("P4"))
                .andExpect(jsonPath("$.name").value("Porte 4"))
                .andExpect(jsonPath("$.zone_ids", empty())));
        assertKeys(json, "$", "id", "code", "name", "zone_ids");
        String gateId = JsonPath.read(json, "$.id");
        assertThat(jdbc.sql("select organizer_id from gates where id = ?::uuid").param(gateId).query(UUID.class).single())
                .isEqualTo(a.org());
        putGateZones(a, gateId, zoneId).andExpect(status().isOk());

        json = body(call(a, patch("/org/venues/" + venueId + "/gates/" + gateId), "{\"name\": \"Porte Quatre\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("P4"))
                .andExpect(jsonPath("$.name").value("Porte Quatre"))
                .andExpect(jsonPath("$.zone_ids").value(contains(zoneId))));
        assertKeys(json, "$", "id", "code", "name", "zone_ids");
        call(a, patch("/org/venues/" + venueId + "/gates/" + gateId), "{\"code\": \"\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.code").exists());
        call(a, post("/org/venues/" + venueId + "/gates"), "{\"name\": \"Sans code\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.code").exists());

        call(a, delete("/org/venues/" + venueId + "/gates/" + gateId))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(gateZoneRows("gate_id", UUID.fromString(gateId))).isZero();
        call(a, get("/org/venues/" + venueId)).andExpect(jsonPath("$.gates", empty()));
    }

    @Test
    void putGateZonesReplacesTheSet() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int est = zone(a, venueId, "EST");
        int sud = zone(a, venueId, "SUD");
        String gateId = gate(a, venueId, "P1");

        putGateZones(a, gateId, nord, est, nord).andExpect(status().isOk());
        String json = body(putGateZones(a, gateId, sud, est)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(gateId))
                .andExpect(jsonPath("$.code").value("P1"))
                .andExpect(jsonPath("$.zone_ids").value(contains(Math.min(est, sud), Math.max(est, sud)))));
        assertKeys(json, "$", "id", "code", "name", "zone_ids");
        assertThat(jdbc.sql("select count(*) from gate_zones where gate_id = ? and organizer_id = ?")
                .params(UUID.fromString(gateId), a.org()).query(Integer.class).single()).isEqualTo(2);

        putGateZones(a, gateId).andExpect(status().isOk()).andExpect(jsonPath("$.zone_ids", empty()));
        call(a, put("/org/gates/" + gateId + "/zones"), "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.zone_ids").exists());
        putGateZones(a, gateId, Collections.nCopies(1001, nord).toArray())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.zone_ids").exists());
    }

    @Test
    void putGateZonesWithAZoneOfAnotherVenueIs422() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int elsewhere = zone(a, venue(a, "Salle"), "NORD");
        Caller b = admin();
        int otherOrgs = zone(b, venue(b, "Autre"), "NORD");
        String gateId = gate(a, venueId, "P1");
        putGateZones(a, gateId, nord).andExpect(status().isOk());

        for (int bad : List.of(elsewhere, otherOrgs, 32_000)) {
            putGateZones(a, gateId, nord, bad)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.fields.zone_ids").exists());
        }
        call(a, get("/org/venues/" + venueId)).andExpect(jsonPath("$.gates[0].zone_ids").value(contains(nord)));
    }

    @Test
    void concurrentPutsOnOneGateEndWithExactlyOnePayload() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        int est = zone(a, venueId, "EST");
        int sud = zone(a, venueId, "SUD");
        List<Integer> first = List.of(Math.min(nord, est), Math.max(nord, est));
        List<Integer> second = List.of(Math.min(est, sud), Math.max(est, sud));
        String gateId = gate(a, venueId, "P1");

        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 10; round++) {
                var start = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (List<Integer> payload : List.of(first, second)) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return putGateZones(a, gateId, payload.toArray()).andReturn().getResponse().getStatus();
                    }));
                }
                start.countDown();
                for (Future<Integer> r : results) {
                    assertThat(r.get()).isEqualTo(200);
                }
                List<Integer> stored = jdbc.sql("select zone_id::int from gate_zones where gate_id = ? order by 1")
                        .param(UUID.fromString(gateId)).query(Integer.class).list();
                assertThat(stored).isIn(first, second);
            }
        }
    }

    @Test
    void deleteRacingAPutOnOneGateIsNever500() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");

        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 10; round++) {
                String gateId = gate(a, venueId, "P" + round);
                var start = new CountDownLatch(1);
                Future<Integer> put = pool.submit(() -> {
                    start.await();
                    return putGateZones(a, gateId, nord).andReturn().getResponse().getStatus();
                });
                Future<Integer> delete = pool.submit(() -> {
                    start.await();
                    return call(a, delete("/org/venues/" + venueId + "/gates/" + gateId)).andReturn().getResponse().getStatus();
                });
                start.countDown();
                assertThat(delete.get()).isEqualTo(204);
                assertThat(put.get()).isIn(200, 404); // 200 if it ran first, 404 if the gate was already gone
                assertThat(gateZoneRows("gate_id", UUID.fromString(gateId))).isZero();
            }
        }
    }

    /** Deterministic version of the race: a PUT's gate_zones insert is still uncommitted when the DELETE starts. */
    @Test
    void deleteWaitsForAnInFlightGateZonesWrite() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD");
        String gateId = gate(a, venueId, "P1");

        try (Connection tx = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            tx.setAutoCommit(false);
            try (var insert = tx.prepareStatement("insert into gate_zones (organizer_id, gate_id, zone_id) values (?, ?, ?)")) {
                insert.setObject(1, a.org());
                insert.setObject(2, UUID.fromString(gateId));
                insert.setInt(3, nord);
                insert.executeUpdate();
            }
            Future<Integer> delete = pool.submit(() ->
                    call(a, delete("/org/venues/" + venueId + "/gates/" + gateId)).andReturn().getResponse().getStatus());
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (jdbc.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()")
                    .query(Integer.class).single() == 0) {
                assertThat(System.nanoTime()).as("DELETE should block on the gate").isLessThan(deadline);
                Thread.onSpinWait();
            }
            tx.commit();
            assertThat(delete.get()).isEqualTo(204);
        }
        assertThat(gateZoneRows("gate_id", UUID.fromString(gateId))).isZero();
    }

    // ---------- CODE_TAKEN ----------

    @Test
    void duplicateZoneOrGateCodeInAVenueIsCodeTaken() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        zone(a, venueId, "NORD");
        int est = zone(a, venueId, "EST");
        gate(a, venueId, "P1");
        String p2 = gate(a, venueId, "P2");

        call(a, post("/org/venues/" + venueId + "/zones"), "{\"code\": \" NORD\", \"name\": \"Bis\", \"capacity\": 1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODE_TAKEN"));
        call(a, patch("/org/venues/" + venueId + "/zones/" + est), "{\"code\": \"NORD\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODE_TAKEN"));
        call(a, post("/org/venues/" + venueId + "/gates"), "{\"code\": \"P1\", \"name\": \"Bis\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODE_TAKEN"));
        call(a, patch("/org/venues/" + venueId + "/gates/" + p2), "{\"code\": \"P1\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODE_TAKEN"));

        // The same code in another venue is fine.
        String other = venue(a, "Salle");
        zone(a, other, "NORD");
        gate(a, other, "P1");
    }

    // ---------- tenant isolation ----------

    @Test
    void anotherOrganizersIdsAre404OnEveryEndpoint() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String bVenue = venue(b, "B stadium");
        int bZone = zone(b, bVenue, "NORD");
        String bGate = gate(b, bVenue, "P1");
        putGateZones(b, bGate, bZone).andExpect(status().isOk()); // a stray gate_zones delete would clear it
        String aVenue = venue(a, "A stadium");

        List<ResultActions> calls = List.of(
                call(a, get("/org/venues/" + bVenue)),
                call(a, patch("/org/venues/" + bVenue), "{\"name\": \"Pwned\"}"),
                call(a, post("/org/venues/" + bVenue + "/zones"), "{\"code\": \"X\", \"name\": \"X\", \"capacity\": 1}"),
                call(a, patch("/org/venues/" + bVenue + "/zones/" + bZone), "{\"name\": \"Pwned\"}"),
                call(a, patch("/org/venues/" + aVenue + "/zones/" + bZone), "{\"name\": \"Pwned\"}"),
                call(a, delete("/org/venues/" + bVenue + "/zones/" + bZone)),
                call(a, delete("/org/venues/" + aVenue + "/zones/" + bZone)),
                call(a, post("/org/venues/" + bVenue + "/gates"), "{\"code\": \"X\", \"name\": \"X\"}"),
                call(a, patch("/org/venues/" + bVenue + "/gates/" + bGate), "{\"name\": \"Pwned\"}"),
                call(a, patch("/org/venues/" + aVenue + "/gates/" + bGate), "{\"name\": \"Pwned\"}"),
                call(a, delete("/org/venues/" + bVenue + "/gates/" + bGate)),
                call(a, delete("/org/venues/" + aVenue + "/gates/" + bGate)),
                call(a, put("/org/gates/" + bGate + "/zones"), "{\"zone_ids\": []}"));
        for (ResultActions r : calls) {
            r.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        call(a, get("/org/venues")).andExpect(jsonPath("$[*].name").value(contains("A stadium")));

        // B's data is untouched.
        call(b, get("/org/venues/" + bVenue)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("B stadium"))
                .andExpect(jsonPath("$.zones[0].name").value("Zone NORD"))
                .andExpect(jsonPath("$.zones.length()").value(1))
                .andExpect(jsonPath("$.gates[0].name").value("Porte P1"))
                .andExpect(jsonPath("$.gates.length()").value(1))
                .andExpect(jsonPath("$.gates[0].zone_ids").value(contains(bZone)));
    }

    @Test
    void zoneOrGateOfAnotherVenueOfTheSameOrganizerIs404() throws Exception {
        Caller a = admin();
        String v1 = venue(a, "Stade");
        String v2 = venue(a, "Salle");
        int zone = zone(a, v1, "NORD");
        String gate = gate(a, v1, "P1");
        call(a, patch("/org/venues/" + v2 + "/zones/" + zone), "{\"name\": \"X\"}").andExpect(status().isNotFound());
        call(a, delete("/org/venues/" + v2 + "/zones/" + zone)).andExpect(status().isNotFound());
        call(a, patch("/org/venues/" + v2 + "/gates/" + gate), "{\"name\": \"X\"}").andExpect(status().isNotFound());
        call(a, delete("/org/venues/" + v2 + "/gates/" + gate)).andExpect(status().isNotFound());
        call(a, get("/org/venues/" + v1)).andExpect(jsonPath("$.zones.length()").value(1))
                .andExpect(jsonPath("$.gates.length()").value(1));
    }

    // ---------- roles ----------

    @Test
    void financeCanReadButNotWrite() throws Exception {
        Caller admin = admin();
        Caller finance = caller(admin.org(), "FINANCE");
        String venueId = venue(admin, "Stade");
        int zoneId = zone(admin, venueId, "NORD");
        String gateId = gate(admin, venueId, "P1");

        call(finance, get("/org/venues")).andExpect(status().isOk());
        call(finance, get("/org/venues/" + venueId)).andExpect(status().isOk());

        List<ResultActions> writes = List.of(
                call(finance, post("/org/venues"), "{\"name\": \"X\", \"capacity\": 1}"),
                call(finance, patch("/org/venues/" + venueId), "{\"name\": \"X\"}"),
                call(finance, post("/org/venues/" + venueId + "/zones"), "{\"code\": \"X\", \"name\": \"X\", \"capacity\": 1}"),
                call(finance, patch("/org/venues/" + venueId + "/zones/" + zoneId), "{\"name\": \"X\"}"),
                call(finance, delete("/org/venues/" + venueId + "/zones/" + zoneId)),
                call(finance, post("/org/venues/" + venueId + "/gates"), "{\"code\": \"X\", \"name\": \"X\"}"),
                call(finance, patch("/org/venues/" + venueId + "/gates/" + gateId), "{\"name\": \"X\"}"),
                call(finance, delete("/org/venues/" + venueId + "/gates/" + gateId)),
                call(finance, put("/org/gates/" + gateId + "/zones"), "{\"zone_ids\": [%d]}".formatted(zoneId)));
        for (ResultActions r : writes) {
            r.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        call(admin, get("/org/venues/" + venueId))
                .andExpect(jsonPath("$.name").value("Stade"))
                .andExpect(jsonPath("$.zones[0].name").value("Zone NORD"))
                .andExpect(jsonPath("$.gates[0].name").value("Porte P1"))
                .andExpect(jsonPath("$.gates[0].zone_ids", empty()));
        call(admin, get("/org/venues")).andExpect(jsonPath("$.length()").value(1));
    }
}
