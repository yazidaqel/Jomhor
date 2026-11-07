package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.jomhor.tenancy.domain.Pins;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Contract: docs/04-api.md §Organizer GET/PUT /org/events/{id}/devices and §Gate GET /gate/events (with the scanner
 * staff list of §Gate PINs).
 */
class AssignmentApiTest extends GateApiTest {

    String gate(Caller c, String venueId, String code) throws Exception {
        return JsonPath.read(body(call(c, post("/org/venues/" + venueId + "/gates"),
                "{\"code\": \"%s\", \"name\": \"Porte %s\"}".formatted(code, code)).andExpect(status().isCreated())),
                "$.id");
    }

    Dev active(Caller c, String label) throws Exception {
        Dev d = pending(c, label);
        call(c, post("/org/devices/" + d.id() + "/approve")).andExpect(status().isOk());
        return d;
    }

    static String rows(Object... deviceGatePairs) {
        StringBuilder s = new StringBuilder("{\"assignments\": [");
        for (int i = 0; i < deviceGatePairs.length; i += 2) {
            s.append(i == 0 ? "" : ", ").append("{\"device_id\": \"%s\", \"gate_id\": \"%s\"}"
                    .formatted(deviceGatePairs[i], deviceGatePairs[i + 1]));
        }
        return s.append("]}").toString();
    }

    String assign(Caller c, int eventId, Object... deviceGatePairs) throws Exception {
        return body(call(c, put("/org/events/" + eventId + "/devices"), rows(deviceGatePairs))
                .andExpect(status().isOk()));
    }

    Map<UUID, UUID> stored(int eventId) {
        return jdbc.sql("select device_id, gate_id from device_assignments where event_id = ?").param(eventId)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    UUID gateStaff(UUID org, String name, String role, boolean active, String verifier) {
        return jdbc.sql("insert into staff (organizer_id, name, role, active, pin_hash) values (?, ?, ?::staff_role, ?, ?) "
                        + "returning id")
                .params(org, name, role, active, verifier).query(UUID.class).single();
    }

    void scan(Caller c, int eventId, Dev d, String gateId) {
        UUID staff = gateStaff(c.org(), "Scan Staff", "GATE", true, null);
        jdbc.sql("insert into scans (scan_id, organizer_id, event_id, device_id, gate_id, staff_id, result, "
                        + "device_time) values (gen_random_uuid(), ?, ?, ?, ?::uuid, ?, 'BAD_FORMAT', now())")
                .params(c.org(), eventId, d.id(), gateId, staff).update();
    }

    // ---------- /org/events/{id}/devices ----------

    @Test
    void putReplacesTheSetAndGetListsItByGateCodeThenLabel() throws Exception {
        Caller a = admin();
        String v = venue(a, "Stade");
        String g1 = gate(a, v, "G1");
        String g2 = gate(a, v, "G2");
        int e = event(a, v);
        Dev pdaB = active(a, "PDA B");
        Dev pdaA = active(a, "PDA A");
        Dev pdaC = active(a, "PDA C");
        call(a, get("/org/events/" + e + "/devices")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        String json = assign(a, e, pdaC.id(), g2, pdaB.id(), g1, pdaA.id(), g1);
        assertThat(JsonPath.<List<String>>read(json, "$[*].label")).containsExactly("PDA A", "PDA B", "PDA C");
        assertThat(JsonPath.<List<String>>read(json, "$[*].gate_code")).containsExactly("G1", "G1", "G2");
        assertKeys(json, "$[0]", "device_id", "label", "status", "gate_id", "gate_code", "manifest_version",
                "last_seen_at", "closed_ack_at");
        assertThat(JsonPath.<String>read(json, "$[0].device_id")).isEqualTo(pdaA.id().toString());
        assertThat(JsonPath.<String>read(json, "$[0].status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<String>read(json, "$[0].gate_id")).isEqualTo(g1);
        assertThat(JsonPath.<Object>read(json, "$[0].manifest_version")).isNull();
        assertThat(JsonPath.<Object>read(json, "$[0].closed_ack_at")).isNull();
        assertThat(body(call(a, get("/org/events/" + e + "/devices")).andExpect(status().isOk()))).isEqualTo(json);
        assertThat(jdbc.sql("select count(*) from device_assignments where event_id = ? and organizer_id = ?")
                .params(e, a.org()).query(Integer.class).single()).isEqualTo(3);

        // pdaA moves to G2 and keeps what it downloaded; pdaC (no scans) is removed
        jdbc.sql("update device_assignments set manifest_version = 3 where device_id = ?").param(pdaA.id()).update();
        json = assign(a, e, pdaA.id(), g2, pdaB.id(), g1);
        assertThat(JsonPath.<List<String>>read(json, "$[*].label")).containsExactly("PDA B", "PDA A");
        assertThat(JsonPath.<Integer>read(json, "$[1].manifest_version")).isEqualTo(3);
        assertThat(stored(e)).isEqualTo(Map.of(pdaA.id(), UUID.fromString(g2), pdaB.id(), UUID.fromString(g1)));

        // pdaB never downloaded: removable; pdaA has (it may hold unsynced scans): 409, like a device with scans
        assertThat(JsonPath.<List<String>>read(assign(a, e, pdaA.id(), g2), "$[*].label")).containsExactly("PDA A");
        String json409 = body(call(a, put("/org/events/" + e + "/devices"), rows())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_IN_USE")));
        assertThat(JsonPath.<List<String>>read(json409, "$.details.device_ids")).containsExactly(pdaA.id().toString());
        assertThat(stored(e)).containsOnlyKeys(pdaA.id());

        jdbc.sql("update device_assignments set manifest_version = null where device_id = ?").param(pdaA.id()).update();
        assertThat(assign(a, e)).isEqualTo("[]");
        assertThat(stored(e)).isEmpty();
    }

    @Test
    void putRefusesBadRowsAtTheirIndexAndChangesNothing() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String v = venue(a, "Stade");
        String g1 = gate(a, v, "G1");
        String otherVenueGate = gate(a, venue(a, "Annexe"), "G9");
        String otherOrgGate = gate(b, venue(b, "Stade B"), "G1");
        int e = event(a, v);
        Dev ok = active(a, "PDA 1");
        Dev pending = pending(a, "PDA 2");
        Dev revoked = active(a, "PDA 3");
        call(a, post("/org/devices/" + revoked.id() + "/revoke")).andExpect(status().isOk());
        Dev otherOrg = active(b, "PDA B");
        assign(a, e, ok.id(), g1);

        record Bad(String body, String field) {}
        for (Bad bad : List.of(
                new Bad(rows(ok.id(), g1, otherOrg.id(), g1), "assignments[1].device_id"),
                new Bad(rows(pending.id(), g1), "assignments[0].device_id"),
                new Bad(rows(ok.id(), g1, revoked.id(), g1), "assignments[1].device_id"),
                new Bad(rows(UUID.randomUUID(), g1), "assignments[0].device_id"),
                new Bad(rows(ok.id(), otherVenueGate), "assignments[0].gate_id"),
                new Bad(rows(ok.id(), g1, active(a, "PDA 4").id(), otherOrgGate), "assignments[1].gate_id"),
                new Bad(rows(ok.id(), UUID.randomUUID()), "assignments[0].gate_id"),
                new Bad(rows(ok.id(), g1, ok.id(), g1), "assignments[1].device_id"),
                new Bad("{\"assignments\": [{\"gate_id\": \"%s\"}]}".formatted(g1), "assignments[0].device_id"),
                new Bad("{\"assignments\": [{\"device_id\": \"%s\"}]}".formatted(ok.id()), "assignments[0].gate_id"),
                new Bad("{}", "assignments"))) {
            String json = body(call(a, put("/org/events/" + e + "/devices"), bad.body())
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")));
            Map<String, Object> fields = JsonPath.read(json, "$.details.fields");
            assertThat(fields).as(bad.body()).containsOnlyKeys(bad.field());
        }
        assertThat(stored(e)).isEqualTo(Map.of(ok.id(), UUID.fromString(g1)));
    }

    @Test
    void removingADeviceWithAScanOnTheEventIs409() throws Exception {
        Caller a = admin();
        String v = venue(a, "Stade");
        String g1 = gate(a, v, "G1");
        String g2 = gate(a, v, "G2");
        int e = event(a, v);
        int other = event(a, v);
        Dev scanned = active(a, "PDA 1");
        Dev idle = active(a, "PDA 2");
        assign(a, e, scanned.id(), g1, idle.id(), g1);
        assign(a, other, scanned.id(), g1);
        scan(a, e, scanned, g1);

        String json = body(call(a, put("/org/events/" + e + "/devices"), rows(idle.id(), g1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_IN_USE")));
        assertThat(JsonPath.<List<String>>read(json, "$.details.device_ids")).containsExactly(scanned.id().toString());
        call(a, put("/org/events/" + e + "/devices"), rows()).andExpect(status().isConflict());
        assertThat(stored(e)).hasSize(2);

        // moving it to another gate keeps it; removing the idle one is fine; its scans are on e only
        assign(a, e, scanned.id(), g2);
        assertThat(stored(e)).isEqualTo(Map.of(scanned.id(), UUID.fromString(g2)));
        assign(a, other);
        assertThat(stored(other)).isEmpty();
    }

    /** 04: a device revoked mid-event stays listed while it is kept on the same gate. */
    @Test
    void aRevokedDeviceWithScansStaysOnItsGateButCantMoveOrLeave() throws Exception {
        Caller a = admin();
        String v = venue(a, "Stade");
        String g1 = gate(a, v, "G1");
        String g2 = gate(a, v, "G2");
        int e = event(a, v);
        Dev stolen = active(a, "PDA 1");
        Dev other = active(a, "PDA 2");
        Dev added = active(a, "PDA 3");
        assign(a, e, stolen.id(), g1, other.id(), g1);
        scan(a, e, stolen, g1);
        call(a, post("/org/devices/" + stolen.id() + "/revoke")).andExpect(status().isOk());

        String json = assign(a, e, stolen.id(), g1, other.id(), g2, added.id(), g2);
        assertThat(JsonPath.<String>read(json, "$[0].status")).isEqualTo("REVOKED");
        assertThat(stored(e)).isEqualTo(Map.of(stolen.id(), UUID.fromString(g1), other.id(), UUID.fromString(g2),
                added.id(), UUID.fromString(g2)));

        String moved = body(call(a, put("/org/events/" + e + "/devices"), rows(stolen.id(), g2, other.id(), g2))
                .andExpect(status().isUnprocessableContent()));
        assertThat(JsonPath.<Map<String, Object>>read(moved, "$.details.fields"))
                .containsOnlyKeys("assignments[0].device_id");
        String removed = body(call(a, put("/org/events/" + e + "/devices"), rows(other.id(), g2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_IN_USE")));
        assertThat(JsonPath.<List<String>>read(removed, "$.details.device_ids")).containsExactly(stolen.id().toString());
        // the exception covers this event's own assignment only: a revoked device can't join another event
        int next = event(a, v);
        call(a, put("/org/events/" + next + "/devices"), rows(stolen.id(), g1)).andExpect(status().isUnprocessableContent());
        assertThat(stored(e)).hasSize(3);
        assertThat(stored(next)).isEmpty();
    }

    @Test
    void closedEventIs409AndAnotherOrganizersEventIs404() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String v = venue(a, "Stade");
        String g1 = gate(a, v, "G1");
        int e = event(a, v);
        Dev d = active(a, "PDA 1");
        assign(a, e, d.id(), g1);

        call(b, get("/org/events/" + e + "/devices")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        call(b, put("/org/events/" + e + "/devices"), rows()).andExpect(status().isNotFound());
        call(a, get("/org/events/999999999/devices")).andExpect(status().isNotFound());

        setStatus(e, "CLOSED");
        call(a, put("/org/events/" + e + "/devices"), rows()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
        call(a, get("/org/events/" + e + "/devices")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertThat(stored(e)).hasSize(1);
    }

    @Test
    void onlyAdminAndOpsManageAssignments() throws Exception {
        Caller a = admin();
        String v = venue(a, "Stade");
        String g1 = gate(a, v, "G1");
        int e = event(a, v);
        Dev d = active(a, "PDA 1");
        Caller ops = caller(a.org(), "OPS");
        assign(ops, e, d.id(), g1);
        call(ops, get("/org/events/" + e + "/devices")).andExpect(status().isOk());
        for (String role : List.of("FINANCE", "GATE", "SUPERVISOR")) {
            Caller c = caller(a.org(), role);
            call(c, get("/org/events/" + e + "/devices")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            call(c, put("/org/events/" + e + "/devices"), rows()).andExpect(status().isForbidden());
        }
        assertThat(stored(e)).hasSize(1);
    }

    // ---------- GET /gate/events ----------

    @Test
    void gateEventsListsThisDevicesShownAssignmentsAndItsOrganizersScannerStaff() throws Exception {
        Caller a = admin(); // back-office ADMIN "Test": no PIN, not shipped
        Caller b = admin();
        String v = venue(a, "Grand Stade");
        String g1 = gate(a, v, "G1");
        String g2 = gate(a, v, "G2");
        int onSale = event(a, v, "2099-05-01T19:00:00Z");
        int closedNotAcked = event(a, v, "2099-04-01T19:00:00Z");
        int closedAcked = event(a, v, "2099-03-01T19:00:00Z");
        int draft = event(a, v, "2099-02-01T19:00:00Z");
        int notMine = event(a, v, "2099-01-01T19:00:00Z");
        Dev d = active(a, "PDA 12");
        Dev other = active(a, "PDA 13");
        assign(a, onSale, d.id(), g2);
        assign(a, closedNotAcked, d.id(), g1);
        assign(a, closedAcked, d.id(), g1);
        assign(a, draft, d.id(), g1);
        assign(a, notMine, other.id(), g1);
        setStatus(onSale, "ON_SALE");
        setStatus(notMine, "ON_SALE");
        setStatus(closedNotAcked, "CLOSED");
        setStatus(closedAcked, "CLOSED");
        jdbc.sql("update device_assignments set closed_ack_at = now() where event_id = ?").param(closedAcked).update();
        for (int version : List.of(1, 2)) {
            jdbc.sql("insert into manifests (organizer_id, event_id, version, row_count, signature, body) "
                    + "values (?, ?, ?, 0, '\\x00'::bytea, '\\x00'::bytea)").params(a.org(), onSale, version).update();
        }

        String yazid = Pins.verifier("4821");
        String salma = Pins.verifier("0042");
        UUID yazidId = gateStaff(a.org(), "yazid el aqel", "GATE", true, yazid);
        UUID salmaId = gateStaff(a.org(), "Salma Idrissi", "SUPERVISOR", true, salma);
        gateStaff(a.org(), "Karim Inactif", "GATE", false, Pins.verifier("1111"));
        gateStaff(a.org(), "Nadia Sanspin", "GATE", true, null);
        gateStaff(b.org(), "Omar Autreorg", "SUPERVISOR", true, Pins.verifier("2222"));

        String json = body(send(d, Req.now("GET", "/v1/gate/events", ""))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store"))));
        assertKeys(json, "$", "server_time", "device", "events", "staff");
        Instant serverTime = Instant.parse(JsonPath.read(json, "$.server_time"));
        assertThat(Duration.between(serverTime, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        assertKeys(json, "$.device", "id", "label", "status");
        assertThat(JsonPath.<String>read(json, "$.device.id")).isEqualTo(d.id().toString());
        assertThat(JsonPath.<String>read(json, "$.device.label")).isEqualTo("PDA 12");
        assertThat(JsonPath.<String>read(json, "$.device.status")).isEqualTo("ACTIVE");

        // by starts_at; DRAFT, acked CLOSED and other devices' events left out
        assertThat(JsonPath.<List<Integer>>read(json, "$.events[*].id")).containsExactly(closedNotAcked, onSale);
        assertKeys(json, "$.events[1]", "id", "title", "event_type", "status", "starts_at", "timezone", "venue", "gate",
                "reentry_policy", "manifest_available_at", "manifest_version");
        assertKeys(json, "$.events[1].venue", "name");
        assertKeys(json, "$.events[1].gate", "id", "code", "name");
        assertThat(JsonPath.<String>read(json, "$.events[1].title")).isEqualTo("Match");
        assertThat(JsonPath.<String>read(json, "$.events[1].event_type")).isEqualTo("FOOTBALL_MATCH");
        assertThat(JsonPath.<String>read(json, "$.events[1].status")).isEqualTo("ON_SALE");
        assertThat(JsonPath.<String>read(json, "$.events[1].starts_at")).isEqualTo("2099-05-01T19:00:00Z");
        assertThat(JsonPath.<String>read(json, "$.events[1].timezone")).isEqualTo("Africa/Casablanca");
        assertThat(JsonPath.<String>read(json, "$.events[1].venue.name")).isEqualTo("Grand Stade");
        assertThat(JsonPath.<String>read(json, "$.events[1].gate.id")).isEqualTo(g2);
        assertThat(JsonPath.<String>read(json, "$.events[1].gate.code")).isEqualTo("G2");
        assertThat(JsonPath.<String>read(json, "$.events[1].gate.name")).isEqualTo("Porte G2");
        assertThat(JsonPath.<String>read(json, "$.events[1].reentry_policy")).isEqualTo("NONE");
        assertThat(JsonPath.<String>read(json, "$.events[1].manifest_available_at")).isEqualTo("2099-05-01T16:00:00Z");
        assertThat(JsonPath.<Integer>read(json, "$.events[1].manifest_version")).isEqualTo(2);
        assertThat(JsonPath.<String>read(json, "$.events[0].status")).isEqualTo("CLOSED");
        assertThat(JsonPath.<Object>read(json, "$.events[0].manifest_version")).isNull();
        assertThat(JsonPath.<String>read(json, "$.events[0].gate.code")).isEqualTo("G1");

        // active GATE/SUPERVISOR staff of this organizer with a PIN, by name, label only
        assertThat(JsonPath.<List<String>>read(json, "$.staff[*].staff_id"))
                .containsExactly(salmaId.toString(), yazidId.toString());
        assertKeys(json, "$.staff[0]", "staff_id", "label", "role", "pin_verifier");
        assertThat(JsonPath.<List<String>>read(json, "$.staff[*].label")).containsExactly("Salma I.", "yazid A.");
        assertThat(JsonPath.<List<String>>read(json, "$.staff[*].role")).containsExactly("SUPERVISOR", "GATE");
        assertThat(JsonPath.<List<String>>read(json, "$.staff[*].pin_verifier")).containsExactly(salma, yazid);
        assertThat(Pins.matches("0042", JsonPath.read(json, "$.staff[0].pin_verifier"))).isTrue();
        assertThat(json).doesNotContain("Idrissi").doesNotContain("aqel").doesNotContain("Inactif")
                .doesNotContain("Sanspin").doesNotContain("Autreorg").doesNotContain("\"Test");

        // the other device sees its own event only; an org B device sees org B's staff only
        String otherJson = body(send(other, Req.now("GET", "/v1/gate/events", "")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(otherJson, "$.events[*].id")).containsExactly(notMine);
        Dev bDevice = active(b, "PDA B");
        String bJson = body(send(bDevice, Req.now("GET", "/v1/gate/events", "")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(bJson, "$.events")).isEmpty();
        assertThat(JsonPath.<List<String>>read(bJson, "$.staff[*].label")).containsExactly("Omar A.");
    }

    @Test
    void staffSharingAHolderLabelAreNumberedInNameOrder() throws Exception {
        Caller a = admin();
        Dev d = active(a, "PDA 1");
        String v = Pins.verifier("1234");
        UUID aqel = gateStaff(a.org(), "Yazid Aqel", "GATE", true, v);
        UUID alami = gateStaff(a.org(), "Yazid Alami", "SUPERVISOR", true, v);
        UUID amrani = gateStaff(a.org(), "Yazid Amrani", "GATE", true, v);
        UUID salma = gateStaff(a.org(), "Salma Idrissi", "GATE", true, v);
        gateStaff(a.org(), "Yazid Abbadi", "GATE", false, v); // inactive: not shipped, takes no number

        String json = body(send(d, Req.now("GET", "/v1/gate/events", "")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.staff[*].staff_id")).containsExactly(salma.toString(),
                alami.toString(), amrani.toString(), aqel.toString());
        assertThat(JsonPath.<List<String>>read(json, "$.staff[*].label"))
                .containsExactly("Salma I.", "Yazid A.", "Yazid A. \u00b7 2", "Yazid A. \u00b7 3");
    }

    @Test
    void aPinResetReachesTheDeviceAtItsNextCall() throws Exception {
        Caller a = admin();
        Dev d = active(a, "PDA 1");
        String created = body(call(a, post("/org/staff"), "{\"name\": \"Yazid Aqel\", \"role\": \"GATE\"}")
                .andExpect(status().isCreated()));
        String id = JsonPath.read(created, "$.id");
        String pin = JsonPath.read(created, "$.pin");
        String before = JsonPath.read(body(send(d, Req.now("GET", "/v1/gate/events", ""))), "$.staff[0].pin_verifier");
        assertThat(Pins.matches(pin, before)).isTrue();

        String newPin = JsonPath.read(body(call(a, post("/org/staff/" + id + "/pin")).andExpect(status().isOk())), "$.pin");
        String after = JsonPath.read(body(send(d, Req.now("GET", "/v1/gate/events", ""))), "$.staff[0].pin_verifier");
        assertThat(after).isNotEqualTo(before);
        assertThat(Pins.matches(newPin, after)).isTrue();

        call(a, patch("/org/staff/" + id),
                "{\"active\": false}").andExpect(status().isOk());
        assertThat(JsonPath.<List<Object>>read(body(send(d, Req.now("GET", "/v1/gate/events", ""))), "$.staff"))
                .isEmpty();
    }

    @Test
    void aRevokedDeviceGets403() throws Exception {
        Caller a = admin();
        Dev d = active(a, "PDA 1");
        call(a, post("/org/devices/" + d.id() + "/revoke")).andExpect(status().isOk());
        send(d, Req.now("GET", "/v1/gate/events", "")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"))
                .andExpect(jsonPath("$.details.status").value("REVOKED"));
    }
}
