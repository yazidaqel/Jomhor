package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ma.jomhor.gate.application.GateDevice;
import ma.jomhor.gate.application.ScanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Contract: docs/04-api.md §Gate POST /gate/events/{id}/scans (+ sync rules), POST /gate/events/{id}/closed-ack,
 * §Organizer POST /org/events/{id}/close; plan lot-2 §5 BE-20.
 */
class ScanIngestionTest extends ScanApiTest {

    @Autowired
    ScanService scanService;

    // ---------- helpers ----------

    /** Sessions waiting for the gate event lock of {@code eventId} (key (3 << 32) | event_id). */
    int lockWaiters(int eventId) {
        return jdbc.sql("""
                        select count(*) from pg_locks
                        where locktype = 'advisory' and not granted and classid = 3 and objid = ? and objsubid = 1
                        """)
                .param(eventId).query(Integer.class).single();
    }

    /** Until {@code f} is done or {@code waiters} sessions wait for the event lock. */
    void awaitDoneOrWaiting(Future<?> f, int eventId, int waiters) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!f.isDone() && lockWaiters(eventId) < waiters) {
            assertThat(System.nanoTime()).as("lock waiters").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    // ---------- batch rules ----------

    @Test
    void theSameBatchTwiceStoresOneRowEachAndAcksBothTimes() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        UUID t = ticket(s.f(), s.f().zone1(), "Fan One");
        var a = ok(s, t);
        var b = row(v7(), null, s.g1(), s.gateStaff(), "BAD_SIG");
        String body = batch(0, List.of(a, b));

        for (int i = 0; i < 2; i++) {
            String r = body(postScans(s.f().device(), s.f().event(), body).andExpect(status().isOk()));
            assertThat(JsonPath.<List<String>>read(r, "$.acked")).containsExactlyElementsOf(ids(a, b));
            assertThat(JsonPath.<List<Object>>read(r, "$.errors")).isEmpty();
            assertKeys(r, "$", "acked", "errors", "used_since", "cursor", "more", "manifest_version", "event_status",
                    "server_time");
            assertThat(JsonPath.<String>read(r, "$.event_status")).isEqualTo("ON_SALE");
        }
        assertThat(stored(UUID.fromString((String) a.get("scan_id")))).isEqualTo(1);
        assertThat(stored(UUID.fromString((String) b.get("scan_id")))).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from scans where event_id = ?").param(s.f().event()).query(Integer.class)
                .single()).isEqualTo(2);
        assertThat(jdbc.sql("select ticket_id is null and token_version = 1 and device_id = ? from scans where scan_id = ?")
                .params(s.f().device().id(), UUID.fromString((String) b.get("scan_id"))).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void moreThan500RowsIs422AndNothingIsStored() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(row(v7(), null, s.g1(), s.gateStaff(), "BAD_FORMAT"));
        }
        postScans(s.f().device(), s.f().event(), batch(0, rows))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.scans").exists());
        assertThat(jdbc.sql("select count(*) from scans where event_id = ?").param(s.f().event()).query(Integer.class)
                .single()).isZero();

        // 500 is fine, and an empty batch is a poll
        sync(s.f().device(), s.f().event(), 0, rows.subList(0, 500));
        sync(s.f().device(), s.f().event(), 0, List.of());
        postScans(s.f().device(), s.f().event(), "{\"cursor\": -1, \"scans\": []}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.cursor").exists());
        postScans(s.f().device(), s.f().event(), "{\"cursor\": 0}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.scans").exists());
    }

    @Test
    void oneBadRowAmongThreeIsAckedListedAndAuditedButNotStored() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        UUID t1 = ticket(s.f(), s.f().zone1(), "Fan One");
        UUID t2 = ticket(s.f(), s.f().zone1(), "Fan Two");
        var a = ok(s, t1);
        var bad = row(v7(), t2, UUID.randomUUID(), s.gateStaff(), "OK"); // not a gate of the venue
        var c = row(v7(), t2, s.g2(), s.gateStaff(), "WRONG_ZONE");

        String r = sync(s.f().device(), s.f().event(), 0, List.of(a, bad, c));

        assertThat(JsonPath.<List<String>>read(r, "$.acked")).containsExactlyElementsOf(ids(a, bad, c));
        assertThat(JsonPath.<List<Map<String, Object>>>read(r, "$.errors"))
                .containsExactly(Map.of("scan_id", bad.get("scan_id"), "field", "gate_id"));
        UUID badId = UUID.fromString((String) bad.get("scan_id"));
        assertThat(stored(UUID.fromString((String) a.get("scan_id")), UUID.fromString((String) c.get("scan_id"))))
                .isEqualTo(2);
        assertThat(stored(badId)).isZero();
        assertThat(audits("scan.refused", badId)).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select actor_type = 'DEVICE' and actor_id = ? and organizer_id = ?
                               and payload->>'field' = 'gate_id' and (payload->>'event_id')::int = ?
                        from audit_log where action = 'scan.refused' and target = ?
                        """)
                .params(s.f().device().id(), s.f().c().org(), s.f().event(), badId.toString())
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void eachRowCheckRefusesOnItsFirstFailingField() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        UUID t = ticket(s.f(), s.f().zone1(), "Fan One");
        // a ticket of another event of the same organizer
        String venueId = jdbc.sql("select venue_id::text from events where id = ?").param(s.f().event())
                .query(String.class).single();
        int other = event(s.f().c(), venueId, Instant.now().plus(Duration.ofHours(2)).toString());
        String otherPrice = price(s.f().c(), other, s.f().zone1(), "Normal", 10);
        Fixture of = new Fixture(s.f().c(), other, s.f().zone1(), s.f().zone2(), otherPrice, s.f().device());
        UUID otherTicket = ticket(of, s.f().zone1(), "Fan Other");
        Fixture otherOrg = fixture(Instant.now().plus(Duration.ofHours(1)));
        UUID otherOrgStaff = staffRow(otherOrg, "GATE");
        UUID otherOrgSupervisor = staffRow(otherOrg, "SUPERVISOR");
        UUID otherVenueGate = UUID.fromString(gate(s.f().c(), venue(s.f().c(), "Annexe"), "X1"));

        record Case(String field, Map<String, Object> row) {}
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("ticket_id", row(v7(), otherTicket, s.g1(), s.gateStaff(), "OK")));
        cases.add(new Case("scan_id", row(UUID.randomUUID(), t, s.g1(), s.gateStaff(), "OK"))); // v4
        cases.add(new Case("result", row(v7(), t, s.g1(), s.gateStaff(), "DUPLICATE_LATE")));
        var out = row(v7(), t, s.g1(), s.gateStaff(), "OK");
        out.put("direction", "OUT");
        cases.add(new Case("direction", out));
        cases.add(new Case("ticket_id", row(v7(), t, s.g1(), s.gateStaff(), "BAD_SIG"))); // not authenticated
        cases.add(new Case("ticket_id", row(v7(), null, s.g1(), s.gateStaff(), "UNKNOWN"))); // authenticated
        cases.add(new Case("gate_id", row(v7(), t, otherVenueGate, s.gateStaff(), "OK"))); // a real gate, other venue
        cases.add(new Case("staff_id", row(v7(), t, s.g1(), otherOrgStaff, "OK")));
        var okWithReason = row(v7(), t, s.g1(), s.gateStaff(), "OK");
        okWithReason.put("override_reason", "OTHER");
        cases.add(new Case("override_reason", okWithReason));
        var overrideNoReason = row(v7(), t, s.g1(), s.gateStaff(), "OVERRIDE");
        overrideNoReason.put("supervisor_id", s.supervisor().toString());
        cases.add(new Case("override_reason", overrideNoReason));
        var overrideByGate = row(v7(), t, s.g1(), s.gateStaff(), "OVERRIDE");
        overrideByGate.put("override_reason", "ID_CHECKED");
        overrideByGate.put("supervisor_id", s.gateStaff().toString());
        cases.add(new Case("supervisor_id", overrideByGate));
        var overrideByOtherOrg = row(v7(), t, s.g1(), s.gateStaff(), "OVERRIDE");
        overrideByOtherOrg.put("override_reason", "ID_CHECKED");
        overrideByOtherOrg.put("supervisor_id", otherOrgSupervisor.toString());
        cases.add(new Case("supervisor_id", overrideByOtherOrg));
        var badVersion = row(v7(), t, s.g1(), s.gateStaff(), "OK");
        badVersion.put("token_version", "1");
        cases.add(new Case("token_version", badVersion));
        var badTime = row(v7(), t, s.g1(), s.gateStaff(), "OK");
        badTime.put("device_time", "1700000000000");
        cases.add(new Case("device_time", badTime));

        String r = sync(s.f().device(), s.f().event(), 0, cases.stream().map(Case::row).toList());
        assertThat(JsonPath.<List<Map<String, Object>>>read(r, "$.errors")).containsExactlyElementsOf(cases.stream()
                .map(c -> Map.<String, Object>of("scan_id", c.row().get("scan_id"), "field", c.field())).toList());
        assertThat(JsonPath.<List<String>>read(r, "$.acked")).hasSize(cases.size());
        assertThat(jdbc.sql("select count(*) from scans where event_id = ?").param(s.f().event()).query(Integer.class)
                .single()).isZero();

        // the same override by a SUPERVISOR is stored
        var override = row(v7(), t, s.g1(), s.gateStaff(), "OVERRIDE");
        override.put("override_reason", "ID_CHECKED");
        override.put("supervisor_id", s.supervisor().toString());
        r = sync(s.f().device(), s.f().event(), 0, List.of(override));
        assertThat(JsonPath.<List<Object>>read(r, "$.errors")).isEmpty();
        assertThat(jdbc.sql("select override_reason::text || ' ' || supervisor_id from scans where scan_id = ?")
                .param(UUID.fromString((String) override.get("scan_id"))).query(String.class).single())
                .isEqualTo("ID_CHECKED " + s.supervisor());
    }

    @Test
    void aDeviceNotAssignedToTheEventGets404() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        Dev stranger = active(s.f().c());
        postScans(stranger, s.f().event(), batch(0, List.of())).andExpect(status().isNotFound());
        send(stranger, Req.now("POST", "/v1/gate/events/" + s.f().event() + "/closed-ack", ""))
                .andExpect(status().isNotFound());
        Site other = site(Instant.now().plus(Duration.ofHours(1)));
        postScans(other.f().device(), s.f().event(), batch(0, List.of())).andExpect(status().isNotFound());
    }

    // ---------- used_since ----------

    @Test
    void usedSinceListsAdmittedScansOfOtherDevicesOnly() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        Dev a = s.f().device();
        Dev b = active(s.f().c());
        assignAll(s.f(), s.g1(), a, b);
        UUID t1 = ticket(s.f(), s.f().zone1(), "Fan One");
        UUID t2 = ticket(s.f(), s.f().zone1(), "Fan Two");
        var admitted = ok(s, t1);
        String r = sync(a, s.f().event(), 0,
                List.of(admitted, row(v7(), t2, s.g1(), s.gateStaff(), "WRONG_ZONE")));
        assertThat(JsonPath.<List<Object>>read(r, "$.used_since")).isEmpty(); // its own rows
        assertThat(JsonPath.<Number>read(r, "$.cursor").longValue()).isZero();
        assertThat(JsonPath.<Boolean>read(r, "$.more")).isFalse();

        r = sync(b, s.f().event(), 0, List.of());
        assertThat(JsonPath.<List<Map<String, Object>>>read(r, "$.used_since")).containsExactly(Map.of(
                "ticket_id", t1.toString(), "direction", "IN", "first_at", admitted.get("device_time"),
                "gate_code", "G1"));
        long cursor = JsonPath.<Number>read(r, "$.cursor").longValue();
        assertThat(cursor).isPositive();

        r = sync(b, s.f().event(), cursor, List.of());
        assertThat(JsonPath.<List<Object>>read(r, "$.used_since")).isEmpty();
        assertThat(JsonPath.<Number>read(r, "$.cursor").longValue()).isEqualTo(cursor);
    }

    @Test
    void aPollerGetsEveryAdmittedTicketExactlyOnceWhileThreeDevicesPost() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        Dev[] posters = {s.f().device(), active(s.f().c()), active(s.f().c())};
        Dev poller = active(s.f().c());
        assignAll(s.f(), s.g1(), posters[0], posters[1], posters[2], poller);
        int batches = 30;
        int perBatch = 100; // wide insert-to-commit windows, so out-of-order commits would show
        tickets(s.f(), posters.length * batches * perBatch);
        List<UUID> all = ticketIds(s.f());

        List<UUID> received = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean posting = new AtomicBoolean(true);
        try (var pool = Executors.newFixedThreadPool(posters.length + 1)) {
            List<Future<?>> runs = new ArrayList<>();
            for (int p = 0; p < posters.length; p++) {
                int from = p * batches * perBatch;
                Dev d = posters[p];
                runs.add(pool.submit(() -> {
                    for (int i = 0; i < batches; i++) {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (int j = 0; j < perBatch; j++) {
                            rows.add(ok(s, all.get(from + i * perBatch + j)));
                        }
                        rows.add(row(v7(), null, s.g1(), s.gateStaff(), "BAD_SIG")); // refused at the gate, never used
                        sync(d, s.f().event(), 0, rows);
                    }
                    return null;
                }));
            }
            Future<?> polling = pool.submit(() -> {
                long cursor = 0;
                boolean last = false;
                while (true) {
                    boolean done = !posting.get();
                    String r = sync(poller, s.f().event(), cursor, List.of());
                    List<String> used = JsonPath.read(r, "$.used_since[*].ticket_id");
                    used.forEach(t -> received.add(UUID.fromString(t)));
                    cursor = JsonPath.<Number>read(r, "$.cursor").longValue();
                    if (last && !JsonPath.<Boolean>read(r, "$.more")) {
                        return null;
                    }
                    last = done; // one more full pass after the posters finished
                }
            });
            for (Future<?> f : runs) {
                f.get();
            }
            posting.set(false);
            polling.get();
        }
        assertThat(received).hasSize(all.size());
        assertThat(new HashSet<>(received)).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void theSameBatchSentTwiceAtOnceStoresOneRowEach() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        tickets(s.f(), 50);
        List<Map<String, Object>> rows = ticketIds(s.f()).stream().map(t -> ok(s, t)).toList();
        String body = batch(0, rows);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> sent = List.of(
                    pool.submit(() -> body(postScans(s.f().device(), s.f().event(), body).andExpect(status().isOk()))),
                    pool.submit(() -> body(postScans(s.f().device(), s.f().event(), body).andExpect(status().isOk()))));
            for (Future<String> r : sent) {
                assertThat(JsonPath.<List<String>>read(r.get(), "$.acked")).hasSize(rows.size());
                assertThat(JsonPath.<List<Object>>read(r.get(), "$.errors")).isEmpty();
            }
        }
        assertThat(jdbc.sql("select count(*) from scans where event_id = ?").param(s.f().event()).query(Integer.class)
                .single()).isEqualTo(rows.size());
    }

    /**
     * Deterministic cursor safety: batch A has drawn its seq values but not committed when B posts and C polls. Under
     * the event lock both wait for A; without it, B would commit seq values above A's, C's cursor would pass A's
     * rows, and A's tickets would never reach C.
     */
    @Test
    void aBatchStillOpenWhenAnotherPostsIsNotSkippedByThePoller() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        Dev a = s.f().device();
        Dev b = active(s.f().c());
        Dev c = active(s.f().c());
        int event = s.f().event();
        assignAll(s.f(), s.g1(), a, b, c);
        tickets(s.f(), 4);
        List<UUID> all = ticketIds(s.f());
        List<JsonNode> batchA = Stream.of(ok(s, all.get(0)), ok(s, all.get(1)))
                .map(m -> (JsonNode) json.valueToTree(m)).toList();

        CountDownLatch[] held = holdTransaction(
                () -> scanService.sync(new GateDevice(a.id(), s.f().c().org()), event, 0L, batchA));
        List<UUID> received = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> postB = pool.submit(() -> sync(b, event, 0, List.of(ok(s, all.get(2)), ok(s, all.get(3)))));
            awaitDoneOrWaiting(postB, event, 1);
            Future<String> poll = pool.submit(() -> sync(c, event, 0, List.of()));
            awaitDoneOrWaiting(poll, event, 2);
            held[0].countDown();
            assertThat(held[1].await(10, TimeUnit.SECONDS)).isTrue();
            postB.get(10, TimeUnit.SECONDS);
            String r = poll.get(10, TimeUnit.SECONDS);
            while (true) {
                JsonPath.<List<String>>read(r, "$.used_since[*].ticket_id").forEach(t -> received.add(UUID.fromString(t)));
                if (JsonPath.<List<Object>>read(r, "$.used_since").isEmpty()) {
                    break;
                }
                r = sync(c, event, JsonPath.<Number>read(r, "$.cursor").longValue(), List.of());
            }
        }
        assertThat(received).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void sixThousandRemoteAdmitsComeInTwoPagesWithMore() throws Exception {
        Site s = site(Instant.now().plus(Duration.ofHours(1)));
        Dev a = s.f().device();
        Dev b = active(s.f().c());
        assignAll(s.f(), s.g1(), a, b);
        tickets(s.f(), 6000);
        jdbc.sql("""
                        insert into scans (scan_id, organizer_id, event_id, ticket_id, device_id, gate_id, staff_id, result, device_time)
                        select gen_random_uuid(), organizer_id, event_id, id, ?, ?, ?, 'OK', now() from tickets where event_id = ?
                        """)
                .params(b.id(), s.g1(), s.gateStaff(), s.f().event()).update();

        String r = sync(a, s.f().event(), 0, List.of());
        List<String> first = JsonPath.read(r, "$.used_since[*].ticket_id");
        assertThat(first).hasSize(5000);
        assertThat(JsonPath.<Boolean>read(r, "$.more")).isTrue();
        long cursor = JsonPath.<Number>read(r, "$.cursor").longValue();

        r = sync(a, s.f().event(), cursor, List.of());
        List<String> second = JsonPath.read(r, "$.used_since[*].ticket_id");
        assertThat(second).hasSize(1000);
        assertThat(JsonPath.<Boolean>read(r, "$.more")).isFalse();
        Set<String> union = new HashSet<>(first);
        union.addAll(second);
        assertThat(union).hasSize(6000);
    }

    // ---------- close ----------

    @Test
    void closeAtOrAfterT0ThenScansStillStoredAndClosedAckIsIdempotent() throws Exception {
        Site s = site(Instant.now().minus(Duration.ofMinutes(30)));
        UUID t = ticket(s.f(), s.f().zone1(), "Fan One");
        String e = "/org/events/" + s.f().event();

        send(s.f().device(), Req.now("POST", "/v1/gate/events/" + s.f().event() + "/closed-ack", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_CLOSED"));
        call(caller(s.f().c().org(), "FINANCE"), post(e + "/close")).andExpect(status().isForbidden());
        call(admin(), post(e + "/close")).andExpect(status().isNotFound());

        String closed = body(call(caller(s.f().c().org(), "OPS"), post(e + "/close")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(closed, "$.status")).isEqualTo("CLOSED");
        assertThat(JsonPath.<Integer>read(closed, "$.id")).isEqualTo(s.f().event());
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'event.close' and target = ?")
                .param(String.valueOf(s.f().event())).query(Integer.class).single()).isEqualTo(1);
        call(s.f().c(), post(e + "/close")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"))
                .andExpect(jsonPath("$.details.reason").value("NOT_OPEN"));

        var late = ok(s, t);
        String r = sync(s.f().device(), s.f().event(), 0, List.of(late));
        assertThat(JsonPath.<String>read(r, "$.event_status")).isEqualTo("CLOSED");
        assertThat(stored(UUID.fromString((String) late.get("scan_id")))).isEqualTo(1);

        Req ack = Req.now("POST", "/v1/gate/events/" + s.f().event() + "/closed-ack", "");
        send(s.f().device(), ack).andExpect(status().isNoContent());
        Instant acked = jdbc.sql("select closed_ack_at from device_assignments where device_id = ? and event_id = ?")
                .params(s.f().device().id(), s.f().event()).query(OffsetDateTime.class).single().toInstant();
        send(s.f().device(), Req.now("POST", "/v1/gate/events/" + s.f().event() + "/closed-ack", ""))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("select closed_ack_at from device_assignments where device_id = ? and event_id = ?")
                .params(s.f().device().id(), s.f().event()).query(OffsetDateTime.class).single()
                .toInstant()).isEqualTo(acked);
    }

    @Test
    void closeBeforeT0OrFromDraftIs409() throws Exception {
        Instant startsAt = Instant.now().plus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.SECONDS);
        Site s = site(startsAt);
        String r = body(call(s.f().c(), post("/org/events/" + s.f().event() + "/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"))
                .andExpect(jsonPath("$.details.reason").value("NOT_STARTED")));
        assertThat(Instant.parse(JsonPath.read(r, "$.details.starts_at"))).isEqualTo(startsAt);
        setStatus(s.f().event(), "DRAFT");
        call(s.f().c(), post("/org/events/" + s.f().event() + "/close")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("NOT_OPEN")); // status first, even before T0
        jdbc.sql("update events set starts_at = now() - interval '1 hour' where id = ?").param(s.f().event()).update();
        call(s.f().c(), post("/org/events/" + s.f().event() + "/close")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"))
                .andExpect(jsonPath("$.details.reason").value("NOT_OPEN"));
        assertThat(jdbc.sql("select status::text from events where id = ?").param(s.f().event()).query(String.class)
                .single()).isEqualTo("DRAFT");
    }

    // ---------- append-only ----------

    @Test
    void noSourceFileUpdatesOrDeletesScans() throws IOException {
        Pattern write = Pattern.compile("(?is)\\b(update\\s+scans|delete\\s+from\\s+scans|truncate\\s+(table\\s+)?scans)\\b");
        List<String> offenders = new ArrayList<>();
        List<Path> walked;
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            walked = files.filter(Files::isRegularFile).toList();
        }
        for (Path p : walked) {
            // Latin-1 decodes any bytes (resources hold binary fonts); the pattern is ASCII.
            if (write.matcher(Files.readString(p, StandardCharsets.ISO_8859_1)).find()) {
                offenders.add(p.toString());
            }
        }
        assertThat(walked).anyMatch(p -> p.endsWith("JdbcScans.java")); // the walk did reach the gate code
        assertThat(offenders).isEmpty();
    }
}
