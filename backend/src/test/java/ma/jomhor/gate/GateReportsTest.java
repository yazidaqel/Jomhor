package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Contract: docs/04-api.md §Gate reports (GET /org/events/{id}/live, /reconciliation, /reconciliation.csv) and the
 * roles line of §Organizer; plan lot-2 §5 BE-21 done-tests (Q32 polling, Q34 DUPLICATE_LATE derived, Q37 final).
 */
class GateReportsTest extends ScanApiTest {

    static final String[] LIVE_KEYS = {"event_id", "status", "starts_at", "server_time", "sold", "entered",
            "rate_per_min", "rate_per_min_prev", "refused", "duplicates_late", "overrides", "gates", "devices_stale"};
    static final String[] LIVE_GATE_KEYS = {"gate_id", "code", "zone_codes", "devices_total", "devices_online",
            "entered", "refused", "overrides", "rate_per_min", "last_sync_at"};
    static final String[] RECON_KEYS = {"event_id", "status", "generated_at", "final", "devices", "totals", "zones",
            "gates"};
    static final String[] RECON_GATE_KEYS = {"gate_id", "code", "name", "zone_codes", "entered", "refused_by_result",
            "duplicates_late", "overrides"};

    // ---------- helpers ----------

    String live(Fixture f) throws Exception {
        return body(call(f.c(), get("/org/events/" + f.event() + "/live")).andExpect(status().isOk()));
    }

    String reconciliation(Fixture f) throws Exception {
        return body(call(f.c(), get("/org/events/" + f.event() + "/reconciliation")).andExpect(status().isOk()));
    }

    Map<String, Object> override(Site s, UUID ticketId, UUID gateId, String reason) {
        Map<String, Object> m = row(v7(), ticketId, gateId, s.gateStaff(), "OVERRIDE");
        m.put("override_reason", reason);
        m.put("supervisor_id", s.supervisor().toString());
        return m;
    }

    /** {@code pairs}: device, gate, device, gate... become the event's whole assignment set. */
    void assignments(Fixture f, Object... pairs) throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            rows.add("{\"device_id\": \"%s\", \"gate_id\": \"%s\"}".formatted(((Dev) pairs[i]).id(), pairs[i + 1]));
        }
        call(f.c(), put("/org/events/" + f.event() + "/devices"), "{\"assignments\": " + rows + "}")
                .andExpect(status().isOk());
    }

    void lastSeen(Dev d, Duration ago) {
        jdbc.sql("update devices set last_seen_at = now() - ? * interval '1 second' where id = ?")
                .params(ago == null ? null : ago.toSeconds(), d.id()).update();
    }

    void ticketStatus(UUID ticketId, String status) {
        jdbc.sql("update tickets set status = ?::ticket_status where id = ?").params(status, ticketId).update();
    }

    void close(Fixture f) throws Exception {
        call(f.c(), post("/org/events/" + f.event() + "/close")).andExpect(status().isOk());
    }

    void ack(Dev d, int eventId) throws Exception {
        send(d, Req.now("POST", "/v1/gate/events/" + eventId + "/closed-ack", "")).andExpect(status().isNoContent());
    }

    // ---------- canonical scan and DUPLICATE_LATE (Q34) ----------

    @Test
    void theEarliestDeviceTimeIsCanonicalEvenWhenItSyncsLastAndTiesGoToTheLowestScanId() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        Dev a = s.f().device();
        Dev b = active(s.f().c());
        assignments(s.f(), a, s.g1(), b, s.g2());
        UUID t = ticket(s.f(), s.f().zone1(), "Fan One");
        Instant t0 = Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);

        // B: OK at t0 + 5 s, synced first; A: OK at t0 + 10 s, synced second → B is canonical.
        sync(b, s.f().event(), 0, List.of(at(row(v7(), t, s.g2(), s.gateStaff(), "OK"), t0.plusSeconds(5))));
        sync(a, s.f().event(), 0, List.of(at(ok(s, t), t0.plusSeconds(10))));

        String live = live(s.f());
        assertThat(JsonPath.<Integer>read(live, "$.entered")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(live, "$.duplicates_late")).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].code")).containsExactly("G1", "G2");
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].entered")).containsExactly(0, 1);
        String recon = reconciliation(s.f());
        assertThat(JsonPath.<Integer>read(recon, "$.totals.entered")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(recon, "$.totals.duplicates_late")).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(recon, "$.gates[*].entered")).containsExactly(0, 1);
        assertThat(JsonPath.<List<Object>>read(recon, "$.gates[*].duplicates_late")).containsExactly(1, 0);

        // Same device_time on both: the lower scan_id (byte order, as Postgres compares uuids) wins.
        UUID u = ticket(s.f(), s.f().zone1(), "Fan Two");
        List<UUID> ids = Stream.of(v7(), v7()).sorted(Comparator.comparing(UUID::toString)).toList();
        Instant same = t0.plusSeconds(20);
        sync(a, s.f().event(), 0, List.of(at(row(ids.get(1), u, s.g1(), s.gateStaff(), "OK"), same)));
        sync(b, s.f().event(), 0, List.of(at(row(ids.get(0), u, s.g2(), s.gateStaff(), "OK"), same)));
        live = live(s.f());
        assertThat(JsonPath.<Integer>read(live, "$.entered")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(live, "$.duplicates_late")).isEqualTo(2);
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].entered")).containsExactly(0, 2);
    }

    // ---------- rates and device thresholds ----------

    @Test
    void ratesCountAdmittedScansByDeviceTimeInTheLastFiveMinutesAndTheFiveBefore() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        tickets(s.f(), 26);
        List<UUID> t = ticketIds(s.f());
        Instant now = Instant.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < 10; i++) { // G1, last 5 min: 10 → 2 per min
            rows.add(at(ok(s, t.get(n++)), now.minus(Duration.ofSeconds(30 + i * 20))));
        }
        for (int i = 0; i < 5; i++) { // G2, last 5 min: 5 → 1 per min (one of them an override)
            var r = i == 0 ? override(s, t.get(n++), s.g2(), "ID_CHECKED")
                    : row(v7(), t.get(n++), s.g2(), s.gateStaff(), "OK");
            rows.add(at(r, now.minus(Duration.ofMinutes(4))));
        }
        for (int i = 0; i < 7; i++) { // the 5 min before: 7 → 1 per min
            rows.add(at(ok(s, t.get(n++)), now.minus(Duration.ofSeconds(330 + i * 30))));
        }
        for (int i = 0; i < 3; i++) { // older than 10 min: in neither window
            rows.add(at(ok(s, t.get(n++)), now.minus(Duration.ofMinutes(11 + i))));
        }
        // A device clock 1 min ahead: after now, so in neither window
        rows.add(at(ok(s, t.get(n++)), now.plus(Duration.ofMinutes(1))));
        // Refused rows in the window are not admitted: no rate
        rows.add(at(row(v7(), t.getFirst(), s.g1(), s.gateStaff(), "DUPLICATE"), now.minus(Duration.ofMinutes(1))));
        sync(s.f().device(), s.f().event(), 0, rows);

        String live = live(s.f());
        assertThat(JsonPath.<Integer>read(live, "$.rate_per_min")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(live, "$.rate_per_min_prev")).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].rate_per_min")).containsExactly(2, 1);
        assertThat(JsonPath.<Integer>read(live, "$.entered")).isEqualTo(26);
        assertThat(JsonPath.<Integer>read(live, "$.refused.total")).isEqualTo(1);
    }

    @Test
    void onlineMeansSeenWithinTwoMinutesAndStaleMeansSilentForMoreThanFiveOrNeverSeen() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        Dev d1 = s.f().device();
        Dev d2 = active(s.f().c());
        Dev d3 = active(s.f().c());
        Dev d4 = active(s.f().c());
        assignments(s.f(), d1, s.g1(), d2, s.g1(), d3, s.g1(), d4, s.g2());
        lastSeen(d1, Duration.ofSeconds(90)); // online
        lastSeen(d2, Duration.ofMinutes(3)); // offline, not stale yet
        lastSeen(d3, Duration.ofMinutes(6)); // stale
        lastSeen(d4, null); // never seen: stale

        String live = live(s.f());
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].code")).containsExactly("G1", "G2");
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].devices_total")).containsExactly(3, 1);
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].devices_online")).containsExactly(1, 0);
        Instant d1Seen = jdbc.sql("select last_seen_at from devices where id = ?").param(d1.id())
                .query(OffsetDateTime.class).single().toInstant();
        assertThat(Instant.parse(JsonPath.<String>read(live, "$.gates[0].last_sync_at"))).isEqualTo(d1Seen);
        assertThat(JsonPath.<Object>read(live, "$.gates[1].last_sync_at")).isNull();
        // by gate code, then label
        assertThat(JsonPath.<List<Object>>read(live, "$.devices_stale[*].device_id"))
                .containsExactly(d3.id().toString(), d4.id().toString());
        assertKeys(live, "$.devices_stale[0]", "device_id", "label", "gate_id", "gate_code", "last_seen_at");
        assertThat(JsonPath.<String>read(live, "$.devices_stale[0].gate_code")).isEqualTo("G1");
        assertThat(JsonPath.<String>read(live, "$.devices_stale[1].gate_id")).isEqualTo(s.g2().toString());
        assertThat(JsonPath.<Object>read(live, "$.devices_stale[1].last_seen_at")).isNull();

        // A signed call brings a device back: no longer stale, online again.
        sync(d3, s.f().event(), 0, List.of());
        live = live(s.f());
        assertThat(JsonPath.<List<Object>>read(live, "$.devices_stale[*].device_id"))
                .containsExactly(d4.id().toString());
        assertThat(JsonPath.<Integer>read(live, "$.gates[0].devices_online")).isEqualTo(2);
    }

    @Test
    void aDeviceThatAckedTheCloseOrWasRevokedIsNotStale() throws Exception {
        Site s = site(Instant.now().minus(Duration.ofMinutes(30)));
        Dev acked = s.f().device();
        Dev revoked = active(s.f().c());
        Dev silent = active(s.f().c());
        assignments(s.f(), acked, s.g1(), revoked, s.g1(), silent, s.g2());
        call(s.f().c(), post("/org/devices/" + revoked.id() + "/revoke")).andExpect(status().isOk());
        close(s.f());
        ack(acked, s.f().event());
        for (Dev d : List.of(acked, revoked, silent)) {
            lastSeen(d, Duration.ofMinutes(6));
        }

        String live = live(s.f());
        assertThat(JsonPath.<List<Object>>read(live, "$.devices_stale[*].device_id"))
                .containsExactly(silent.id().toString());
        // the acked device still counts on its gate, the revoked one does not (ACTIVE devices only)
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].devices_total")).containsExactly(1, 1);
    }

    // ---------- reconciliation ----------

    @Test
    void finalOnlyOnceTheEventIsClosedAndEveryAssignedDeviceHasAcked() throws Exception {
        Site s = site(Instant.now().minus(Duration.ofMinutes(30)));
        Dev a = s.f().device();
        Dev b = active(s.f().c());
        assignments(s.f(), a, s.g1(), b, s.g2());

        String r = reconciliation(s.f());
        assertThat(JsonPath.<Boolean>read(r, "$.final")).isFalse();
        assertThat(JsonPath.<String>read(r, "$.status")).isEqualTo("ON_SALE");
        assertThat(JsonPath.<Integer>read(r, "$.devices.assigned")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(r, "$.devices.acked")).isZero();
        assertThat(JsonPath.<List<Object>>read(r, "$.devices.unsynced[*].device_id"))
                .containsExactly(a.id().toString(), b.id().toString());
        assertKeys(r, "$.devices.unsynced[0]", "device_id", "label", "gate_code", "status", "last_seen_at");
        assertThat(JsonPath.<List<Object>>read(r, "$.devices.unsynced[*].gate_code")).containsExactly("G1", "G2");

        close(s.f());
        r = reconciliation(s.f());
        assertThat(JsonPath.<Boolean>read(r, "$.final")).isFalse();
        assertThat(JsonPath.<String>read(r, "$.status")).isEqualTo("CLOSED");
        assertThat(JsonPath.<List<Object>>read(r, "$.devices.unsynced")).hasSize(2);

        ack(a, s.f().event());
        r = reconciliation(s.f());
        assertThat(JsonPath.<Boolean>read(r, "$.final")).isFalse();
        assertThat(JsonPath.<Integer>read(r, "$.devices.acked")).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(r, "$.devices.unsynced[*].device_id"))
                .containsExactly(b.id().toString());

        ack(b, s.f().event());
        r = reconciliation(s.f());
        assertThat(JsonPath.<Boolean>read(r, "$.final")).isTrue();
        assertThat(JsonPath.<Integer>read(r, "$.devices.acked")).isEqualTo(2);
        assertThat(JsonPath.<List<Object>>read(r, "$.devices.unsynced")).isEmpty();
    }

    @Test
    void aRevokedDeviceWithScansStaysUnsyncedAsRevokedWithoutBlockingFinalAndLeavesTheLiveDeviceCounts()
            throws Exception {
        Site s = site(Instant.now().minus(Duration.ofMinutes(30)));
        Dev a = s.f().device();
        Dev lost = active(s.f().c());
        assignments(s.f(), a, s.g1(), lost, s.g1());
        UUID t = ticket(s.f(), s.f().zone1(), "Fan One");
        sync(lost, s.f().event(), 0, List.of(ok(s, t)));
        call(s.f().c(), post("/org/devices/" + lost.id() + "/revoke")).andExpect(status().isOk());
        lastSeen(a, Duration.ofSeconds(30));

        String live = live(s.f());
        assertThat(JsonPath.<Integer>read(live, "$.gates[0].devices_total")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(live, "$.gates[0].devices_online")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(live, "$.gates[0].entered")).isEqualTo(1); // its synced scans still count

        close(s.f());
        String r = reconciliation(s.f());
        assertThat(JsonPath.<Boolean>read(r, "$.final")).isFalse();
        ack(a, s.f().event());
        r = reconciliation(s.f());
        assertThat(JsonPath.<Boolean>read(r, "$.final")).isTrue();
        assertThat(JsonPath.<Integer>read(r, "$.devices.assigned")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(r, "$.devices.acked")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(r, "$.devices.revoked")).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(r, "$.devices.unsynced[*].device_id"))
                .containsExactly(lost.id().toString());
        assertThat(JsonPath.<String>read(r, "$.devices.unsynced[0].status")).isEqualTo("REVOKED");
        assertThat(JsonPath.<Integer>read(r, "$.totals.entered")).isEqualTo(1);
    }

    /**
     * t1 VALID admitted, t2 VALID never scanned, t3 CANCELLED let in by a supervisor, t4 VALID refused as a duplicate,
     * plus a BAD_SIG with no ticket. Shapes exactly as 04, both endpoints and the CSV.
     */
    @Test
    void entriesCountValidTicketsOnlyAndEveryFigureAddsUpInJsonAndCsv() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        UUID t1 = ticket(s.f(), s.f().zone1(), "Fan One");
        ticket(s.f(), s.f().zone1(), "Fan Two");
        UUID t3 = ticket(s.f(), s.f().zone1(), "Fan Three");
        UUID t4 = ticket(s.f(), s.f().zone1(), "Fan Four");
        ticketStatus(t3, "CANCELLED");
        sync(s.f().device(), s.f().event(), 0, List.of(ok(s, t1), override(s, t3, s.g2(), "DAMAGED_TICKET"),
                row(v7(), t4, s.g1(), s.gateStaff(), "DUPLICATE"), row(v7(), null, s.g1(), s.gateStaff(), "BAD_SIG")));
        Dev d = s.f().device();

        String live = live(s.f());
        assertKeys(live, "$", LIVE_KEYS);
        assertKeys(live, "$.refused", "total", "by_result");
        assertKeys(live, "$.overrides", "total", "cap_per_device", "by_device");
        assertKeys(live, "$.overrides.by_device[0]", "device_id", "label", "count");
        assertKeys(live, "$.gates[0]", LIVE_GATE_KEYS);
        assertThat(JsonPath.<Integer>read(live, "$.event_id")).isEqualTo(s.f().event());
        assertThat(JsonPath.<String>read(live, "$.status")).isEqualTo("ON_SALE");
        assertThat(JsonPath.<Integer>read(live, "$.sold")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(live, "$.entered")).isEqualTo(2); // live: any ticket with a canonical scan
        assertThat(JsonPath.<Integer>read(live, "$.duplicates_late")).isZero();
        assertThat(JsonPath.<Integer>read(live, "$.refused.total")).isEqualTo(2);
        assertThat(JsonPath.<Map<String, Object>>read(live, "$.refused.by_result"))
                .isEqualTo(Map.of("DUPLICATE", 1, "BAD_SIG", 1));
        assertThat(JsonPath.<Integer>read(live, "$.overrides.total")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(live, "$.overrides.cap_per_device")).isEqualTo(20);
        assertThat(JsonPath.<String>read(live, "$.overrides.by_device[0].device_id")).isEqualTo(d.id().toString());
        assertThat(JsonPath.<Integer>read(live, "$.overrides.by_device[0].count")).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].code")).containsExactly("G1", "G2");
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[0].zone_codes")).containsExactly("TRIB");
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[1].zone_codes")).containsExactly("TRIB", "VIR");
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].entered")).containsExactly(1, 1);
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].refused")).containsExactly(2, 0);
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].overrides")).containsExactly(0, 1);
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].devices_total")).containsExactly(1, 0);
        assertThat(Instant.parse(JsonPath.<String>read(live, "$.starts_at"))).isNotNull();
        assertThat(Instant.parse(JsonPath.<String>read(live, "$.server_time")))
                .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));

        String r = reconciliation(s.f());
        assertKeys(r, "$", RECON_KEYS);
        assertKeys(r, "$.devices", "assigned", "acked", "revoked", "unsynced");
        assertKeys(r, "$.totals", "sold", "entered", "no_show", "refused_by_result", "duplicates_late",
                "overrides_by_reason");
        assertKeys(r, "$.zones[0]", "zone_id", "code", "name", "sold", "entered", "no_show", "overrides");
        assertKeys(r, "$.gates[0]", RECON_GATE_KEYS);
        assertThat(Instant.parse(JsonPath.<String>read(r, "$.generated_at"))).isNotNull();
        assertThat(JsonPath.<Integer>read(r, "$.totals.sold")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(r, "$.totals.entered")).isEqualTo(1); // t3 is not VALID
        assertThat(JsonPath.<Integer>read(r, "$.totals.no_show")).isEqualTo(2);
        assertThat(JsonPath.<Map<String, Object>>read(r, "$.totals.refused_by_result"))
                .isEqualTo(Map.of("DUPLICATE", 1, "BAD_SIG", 1));
        assertThat(JsonPath.<Integer>read(r, "$.totals.duplicates_late")).isZero();
        assertThat(JsonPath.<Map<String, Object>>read(r, "$.totals.overrides_by_reason"))
                .isEqualTo(Map.of("DAMAGED_TICKET", 1));
        // zones with a price category only (VIR has none), by code
        assertThat(JsonPath.<List<Object>>read(r, "$.zones[*].code")).containsExactly("TRIB");
        assertThat(JsonPath.<Integer>read(r, "$.zones[0].zone_id")).isEqualTo(s.f().zone1());
        assertThat(JsonPath.<String>read(r, "$.zones[0].name")).isEqualTo("Zone TRIB");
        assertThat(Stream.of("sold", "entered", "no_show", "overrides")
                .map(k -> JsonPath.<Integer>read(r, "$.zones[0]." + k))).containsExactly(3, 1, 2, 1);
        assertThat(JsonPath.<List<Object>>read(r, "$.gates[*].code")).containsExactly("G1", "G2");
        assertThat(JsonPath.<List<Object>>read(r, "$.gates[*].name")).containsExactly("Porte G1", "Porte G2");
        assertThat(JsonPath.<List<Object>>read(r, "$.gates[1].zone_codes")).containsExactly("TRIB", "VIR");
        assertThat(JsonPath.<List<Object>>read(r, "$.gates[*].entered")).containsExactly(1, 0);
        assertThat(JsonPath.<Map<String, Object>>read(r, "$.gates[0].refused_by_result"))
                .isEqualTo(Map.of("DUPLICATE", 1, "BAD_SIG", 1));
        assertThat(JsonPath.<Map<String, Object>>read(r, "$.gates[1].refused_by_result")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(r, "$.gates[*].overrides")).containsExactly(0, 1);

        byte[] csv = call(s.f().c(), get("/org/events/" + s.f().event() + "/reconciliation.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"reconciliation-" + s.f().event() + ".csv\""))
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(csv).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8)).isEqualTo("""
                kind;code;nom;vendus;entres;absents;refuses;doublons_tardifs;derogations\r
                zone;TRIB;Zone TRIB;3;1;2;;;1\r
                gate;G1;Porte G1;;1;;2;0;0\r
                gate;G2;Porte G2;;0;;0;0;1\r
                total;;;3;1;2;2;0;1\r
                """);
    }

    @Test
    void aNameWithTheSeparatorOrAQuoteIsQuotedInTheCsv() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        jdbc.sql("update zones set name = 'Tribune \"A\"; Nord' where id = ?").param(s.f().zone1()).update();
        String csv = body(call(s.f().c(), get("/org/events/" + s.f().event() + "/reconciliation.csv"))
                .andExpect(status().isOk()));
        assertThat(csv.lines().toList().get(1)).isEqualTo("zone;TRIB;\"Tribune \"\"A\"\"; Nord\";0;0;0;;;0");
    }

    @Test
    void aNameThatStartsLikeAFormulaIsEscapedInTheCsv() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        jdbc.sql("update gates set name = '@SUM(1)' where id = ?").param(s.g1()).update();
        jdbc.sql("update zones set name = '=1+1' where id = ?").param(s.f().zone1()).update();
        String csv = body(call(s.f().c(), get("/org/events/" + s.f().event() + "/reconciliation.csv"))
                .andExpect(status().isOk()));
        assertThat(csv.lines()).anyMatch(l -> l.startsWith("gate;G1;'@SUM(1);"))
                .anyMatch(l -> l.startsWith("zone;TRIB;'=1+1;"));
    }

    // ---------- roles and tenancy ----------

    @Test
    void adminOpsAndFinanceReadGateAndSupervisorGet403AnotherOrganizerGets404() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        int e = s.f().event();
        List<String> paths = List.of("/live", "/reconciliation", "/reconciliation.csv");
        for (String p : paths) {
            for (String role : List.of("OPS", "FINANCE")) {
                call(caller(s.f().c().org(), role), get("/org/events/" + e + p)).andExpect(status().isOk());
            }
            for (String role : List.of("GATE", "SUPERVISOR")) {
                call(caller(s.f().c().org(), role), get("/org/events/" + e + p)).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
            call(admin(), get("/org/events/" + e + p)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            call(s.f().c(), get("/org/events/999999999" + p)).andExpect(status().isNotFound());
        }
        // An event with no scans yet: the gate with an assigned device only, empty counts.
        String live = live(s.f());
        assertThat(JsonPath.<List<Object>>read(live, "$.gates[*].code")).containsExactly("G1");
        assertThat(JsonPath.<Map<String, Object>>read(live, "$.refused.by_result")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(live, "$.overrides.by_device")).isEmpty();
        assertThat(JsonPath.<Integer>read(live, "$.entered")).isZero();
    }
}
