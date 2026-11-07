package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.payments.PaymentTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Lot 2 exit proof, backend side (plan lot-2 §1 and §5 BE-21): three tickets sold by card (the Lot 1 flow), two
 * scanners enrolled, approved and assigned to two gates, both download the signed manifest; offline, both admit the
 * same ticket; after sync the earlier device time is canonical and the other is a late duplicate; B's sync returns
 * A's admit; a supervisor override; /live shows the figures and a silent device; the event closes, both devices drain
 * (B's last queued scan lands after the close and moves the provisional totals) and ack, and the reconciliation is
 * final with the right totals in JSON and CSV.
 */
class Lot2GateFlowTest extends ScanApiTest {

    @Autowired
    ManifestService manifests;

    @Test
    void twoOfflineScannersOneTicketTwiceThenCloseAndAFinalReconciliation() throws Exception {
        // Organizer: venue, zone, gates P1 and P2, event at T0 = now + 2 h (inside the 3 h manifest lead), publish.
        Caller org = admin();
        String venueId = venue(org, "Grand Stade");
        int nord = zone(org, venueId, "NORD");
        UUID p1 = UUID.fromString(gate(org, venueId, "P1", nord));
        UUID p2 = UUID.fromString(gate(org, venueId, "P2", nord));
        int e = event(org, venueId, Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS).toString());
        String priceId = price(org, e, nord, "Plein tarif", 100);
        call(org, post("/org/events/" + e + "/publish")).andExpect(status().isOk());

        // Lot 1: a fan buys 3 places by card on the simulated gateway (3 x 50 DH).
        String fan = fanToken(randomPhone());
        String orderId = JsonPath.read(body(mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + fan)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 3, "holders": %s}]}
                        """.formatted(e, priceId, holders(3)))).andExpect(status().isCreated())), "$.id");
        String paymentId = JsonPath.read(body(mvc.perform(post("/fan/orders/" + orderId + "/pay")
                .header("Authorization", "Bearer " + fan).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\": \"CARD\"}")).andExpect(status().isOk())), "$.payment_id");
        var callback = post("/payments/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        PaymentTest.approve(paymentId, 15000).forEach(callback::param);
        mvc.perform(callback).andExpect(status().isSeeOther());
        List<UUID> t = jdbc.sql("select id from tickets where order_id = ?::uuid and status = 'VALID' order by id")
                .param(orderId).query(UUID.class).list();
        assertThat(t).hasSize(3);

        // Staff with PINs, two scanners enrolled from B6, approved after the fingerprint check, assigned to P1 / P2.
        UUID gateStaff = staffId(org, "Karim Gate", "GATE");
        UUID supervisor = staffId(org, "Salma Superviseur", "SUPERVISOR");
        Dev a = pending(org, "PDA A");
        Dev b = pending(org, "PDA B");
        String devices = body(call(org, get("/org/devices")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(devices, "$[?(@.label in ['PDA A', 'PDA B'])].status"))
                .containsExactly("PENDING", "PENDING");
        call(org, post("/org/devices/" + a.id() + "/approve")).andExpect(status().isOk());
        call(org, post("/org/devices/" + b.id() + "/approve")).andExpect(status().isOk());
        call(org, put("/org/events/" + e + "/devices"), """
                {"assignments": [{"device_id": "%s", "gate_id": "%s"}, {"device_id": "%s", "gate_id": "%s"}]}
                """.formatted(a.id(), p1, b.id(), p2)).andExpect(status().isOk());

        // Manifest v1 built and downloaded by both: full, with K_e, signature verified against the pinned key.
        assertThat(manifests.build(org.org(), e)).contains(1);
        for (Dev d : List.of(a, b)) {
            String m = manifest(d, e);
            assertThat(JsonPath.<Integer>read(m, "$.version")).isEqualTo(1);
            assertThat(JsonPath.<String>read(m, "$.event_key")).isNotEmpty();
            assertThat(ManifestApiTest.verifies(m)).isTrue();
            assertThat(ManifestApiTest.tLines(m)).hasSize(3);
        }

        // Offline. A: OK t1 at T-2 min, WRONG_ZONE t2. B: OK t1 at T-3 min (earlier), OVERRIDE t3 by the supervisor.
        Instant now = Instant.now();
        var aT1 = at(row(v7(), t.get(0), p1, gateStaff, "OK"), now.minus(Duration.ofMinutes(2)));
        var aT2 = at(row(v7(), t.get(1), p1, gateStaff, "WRONG_ZONE"), now.minus(Duration.ofSeconds(110)));
        var bT1 = at(row(v7(), t.get(0), p2, gateStaff, "OK"), now.minus(Duration.ofMinutes(3)));
        var bT3 = at(row(v7(), t.get(2), p2, gateStaff, "OVERRIDE"), now.minus(Duration.ofMinutes(1)));
        bT3.put("override_reason", "ID_CHECKED");
        bT3.put("supervisor_id", supervisor.toString());

        // Back online: A syncs first, then B, whose sync returns A's admit of t1 (gate P1, A's device time).
        String syncA = sync(a, e, 0, List.of(aT1, aT2));
        assertThat(JsonPath.<List<String>>read(syncA, "$.acked")).containsExactlyElementsOf(ids(aT1, aT2));
        assertThat(JsonPath.<List<Object>>read(syncA, "$.used_since")).isEmpty();
        String syncB = sync(b, e, 0, List.of(bT1, bT3));
        assertThat(JsonPath.<List<String>>read(syncB, "$.acked")).containsExactlyElementsOf(ids(bT1, bT3));
        assertThat(JsonPath.<List<Object>>read(syncB, "$.errors")).isEmpty();
        assertThat(JsonPath.<List<String>>read(syncB, "$.used_since[*].ticket_id"))
                .containsExactly(t.get(0).toString());
        assertThat(JsonPath.<String>read(syncB, "$.used_since[0].gate_code")).isEqualTo("P1");
        assertThat(Instant.parse(JsonPath.read(syncB, "$.used_since[0].first_at")))
                .isEqualTo(Instant.parse((String) aT1.get("device_time")));

        // A goes silent for 6 min: B4 flags it. 1 entered + 1 late duplicate for t1, canonical = B (earlier).
        jdbc.sql("update devices set last_seen_at = now() - interval '6 minutes' where id = ?").param(a.id()).update();
        String live = body(call(org, get("/org/events/" + e + "/live")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(live, "$.sold")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(live, "$.entered")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(live, "$.duplicates_late")).isEqualTo(1);
        assertThat(JsonPath.<Map<String, Object>>read(live, "$.refused.by_result")).isEqualTo(Map.of("WRONG_ZONE", 1));
        assertThat(JsonPath.<Integer>read(live, "$.overrides.total")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(live, "$.overrides.by_device[*].device_id"))
                .containsExactly(b.id().toString());
        assertThat(JsonPath.<List<String>>read(live, "$.gates[*].code")).containsExactly("P1", "P2");
        assertThat(JsonPath.<List<Integer>>read(live, "$.gates[*].entered")).containsExactly(0, 2);
        assertThat(JsonPath.<List<Integer>>read(live, "$.gates[*].refused")).containsExactly(1, 0);
        assertThat(JsonPath.<List<Integer>>read(live, "$.gates[*].devices_online")).containsExactly(0, 1);
        assertThat(JsonPath.<List<String>>read(live, "$.devices_stale[*].device_id"))
                .containsExactly(a.id().toString());
        assertThat(JsonPath.<String>read(live, "$.devices_stale[0].gate_code")).isEqualTo("P1");

        // T0 has passed: the admin closes. Provisional until both devices ack.
        jdbc.sql("update events set starts_at = now() - interval '1 minute' where id = ?").param(e).update();
        call(org, post("/org/events/" + e + "/close")).andExpect(status().isOk());
        String recon = reconciliation(org, e);
        assertThat(JsonPath.<Boolean>read(recon, "$.final")).isFalse();
        assertThat(JsonPath.<List<String>>read(recon, "$.devices.unsynced[*].gate_code")).containsExactly("P1", "P2");

        // Both drain, wipe and ack. A's queue is empty: its poll says CLOSED.
        assertThat(JsonPath.<String>read(sync(a, e, 0, List.of()), "$.event_status")).isEqualTo("CLOSED");
        send(a, Req.now("POST", "/v1/gate/events/" + e + "/closed-ack", "")).andExpect(status().isNoContent());
        // B still holds one scan made before the close (t2, OK at P2): stored after CLOSED, and the provisional
        // reconciliation moves with it before final (Q37: computed on read, late syncs keep correcting it).
        var bT2 = at(row(v7(), t.get(1), p2, gateStaff, "OK"), now.minus(Duration.ofSeconds(30)));
        String drainB = sync(b, e, 0, List.of(bT2));
        assertThat(JsonPath.<String>read(drainB, "$.event_status")).isEqualTo("CLOSED");
        assertThat(stored(UUID.fromString((String) bT2.get("scan_id")))).isEqualTo(1);
        recon = reconciliation(org, e);
        assertThat(JsonPath.<Boolean>read(recon, "$.final")).isFalse();
        assertThat(JsonPath.<Integer>read(recon, "$.totals.entered")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(recon, "$.totals.no_show")).isZero();
        assertThat(JsonPath.<List<String>>read(recon, "$.devices.unsynced[*].device_id"))
                .containsExactly(b.id().toString());
        send(b, Req.now("POST", "/v1/gate/events/" + e + "/closed-ack", "")).andExpect(status().isNoContent());

        recon = reconciliation(org, e);
        assertThat(JsonPath.<Boolean>read(recon, "$.final")).isTrue();
        assertThat(JsonPath.<String>read(recon, "$.status")).isEqualTo("CLOSED");
        assertThat(JsonPath.<Integer>read(recon, "$.devices.acked")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(recon, "$.totals.sold")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(recon, "$.totals.entered")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(recon, "$.totals.no_show")).isZero();
        assertThat(JsonPath.<Map<String, Object>>read(recon, "$.totals.refused_by_result"))
                .isEqualTo(Map.of("WRONG_ZONE", 1));
        assertThat(JsonPath.<Integer>read(recon, "$.totals.duplicates_late")).isEqualTo(1);
        assertThat(JsonPath.<Map<String, Object>>read(recon, "$.totals.overrides_by_reason"))
                .isEqualTo(Map.of("ID_CHECKED", 1));

        MockHttpServletResponse csv = call(org, get("/org/events/" + e + "/reconciliation.csv"))
                .andExpect(status().isOk()).andReturn().getResponse();
        byte[] bytes = csv.getContentAsByteArray();
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8).split("\r\n")).containsExactly(
                "kind;code;nom;vendus;entres;absents;refuses;doublons_tardifs;derogations",
                "zone;NORD;Zone NORD;3;3;0;;;1",
                "gate;P1;Porte P1;;0;;1;1;0",
                "gate;P2;Porte P2;;3;;0;0;1",
                "total;;;3;3;0;1;1;1");
    }

    // ---------- helpers ----------

    UUID staffId(Caller c, String name, String role) throws Exception {
        String json = body(call(c, post("/org/staff"), "{\"name\": \"%s\", \"role\": \"%s\"}".formatted(name, role))
                .andExpect(status().isCreated()));
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    String manifest(Dev d, int eventId) throws Exception {
        byte[] gz = send(d, Req.now("GET", "/v1/gate/events/" + eventId + "/manifest", "")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    String reconciliation(Caller c, int eventId) throws Exception {
        return body(call(c, get("/org/events/" + eventId + "/reconciliation")).andExpect(status().isOk()));
    }
}
