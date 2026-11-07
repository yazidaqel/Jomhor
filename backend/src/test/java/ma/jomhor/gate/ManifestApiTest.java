package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.common.HolderNames;
import ma.jomhor.gate.application.Assignments;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.gate.application.Manifests;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.tickets.domain.TicketToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract: docs/04-api.md §Gate GET /gate/events/{id}/manifest and §Manifest payload; the build rules of plan lot-2
 * Q43 (v1 at T0 - 3 h, a new version only when the content changed, at most every 5 min, until T0 + 30 min).
 */
class ManifestApiTest extends GateApiTest {

    static final String HOLDER = "Zakaria Fixturename";
    static final String HOLDER_PHONE = "+212600000000";

    @Autowired
    ManifestService manifests;

    @Autowired
    EventService events;

    @Autowired
    Assignments assignments;

    @Autowired
    Manifests store;

    void ticketStatus(UUID ticketId, String status) {
        jdbc.sql("update tickets set status = ?::ticket_status where id = ?").params(status, ticketId).update();
    }

    /** Moves the latest version 10 min into the past, so the next build may make a new one. */
    void age(int eventId) {
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(eventId)
                .update();
    }

    int versions(int eventId) {
        return jdbc.sql("select count(*) from manifests where event_id = ?").param(eventId).query(Integer.class).single();
    }

    Integer downloaded(Dev d, int eventId) {
        return jdbc.sql("select manifest_version from device_assignments where device_id = ? and event_id = ?")
                .params(d.id(), eventId).query((rs, n) -> (Integer) rs.getObject(1)).list().getFirst();
    }

    MvcResult get(Dev d, int eventId, String query) throws Exception {
        return send(d, Req.now("GET", "/v1/gate/events/" + eventId + "/manifest" + query, "")).andReturn();
    }

    /** A 200 manifest: no-store, gzip-encoded JSON; returns the inflated JSON. */
    String manifest(Dev d, int eventId, String query) throws Exception {
        MvcResult r = get(d, eventId, query);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("Content-Encoding")).isEqualTo("gzip");
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        try (var in = new GZIPInputStream(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static boolean verifies(String json) throws Exception {
        byte[] raw = Base64.getUrlDecoder().decode(DeviceApiTest.TEST_MANIFEST_PUBLIC_KEY);
        var v = Signature.getInstance("Ed25519");
        v.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                HexFormat.of().parseHex("302a300506032b6570032100" + HexFormat.of().formatHex(raw)))));
        v.update(JsonPath.<String>read(json, "$.payload").getBytes(StandardCharsets.UTF_8));
        return v.verify(Base64.getUrlDecoder().decode(JsonPath.<String>read(json, "$.signature")));
    }

    static List<String> tLines(String json) {
        return JsonPath.<String>read(json, "$.payload").lines().filter(l -> l.startsWith("T\t")).toList();
    }

    static String tLine(UUID ticket, int zone, String status, String initials) {
        return "T\t" + ticket + "\t" + zone + "\t" + status + "\t" + initials;
    }

    // ---------- full and delta ----------

    @Test
    void fullCarriesTheEventKeyAndDeltasOnlyTheNewAndChangedTickets() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        UUID t1 = ticket(f, f.zone1(), HOLDER);
        UUID t2 = ticket(f, f.zone1(), null);
        assertThat(manifests.build(f.c().org(), f.event())).contains(1);

        String full = manifest(f.device(), f.event(), "");
        assertKeys(full, "$", "event_id", "version", "since", "rows", "payload", "signature", "event_key", "server_time");
        assertThat(JsonPath.<Integer>read(full, "$.event_id")).isEqualTo(f.event());
        assertThat(JsonPath.<Integer>read(full, "$.version")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(full, "$.since")).isEqualTo(0);
        assertThat(JsonPath.<Integer>read(full, "$.rows")).isEqualTo(2);
        assertThat(verifies(full)).isTrue();
        assertThat(tLines(full)).containsExactlyInAnyOrder(tLine(t1, f.zone1(), "VALID", HolderNames.initials(HOLDER)),
                tLine(t2, f.zone1(), "VALID", ""));
        String payload = JsonPath.read(full, "$.payload");
        assertThat(payload).startsWith("JMH-MANIFEST\t1\nevent\t" + f.event() + "\nversion\t1\nsince\t0\nbuilt_at\t")
                .contains("\nreentry\tNONE\nrows\t2\n");
        assertThat(downloaded(f.device(), f.event())).isEqualTo(1);

        // event_key = K_e: a PDF token signed with it verifies against the event's key
        byte[] key = Base64.getUrlDecoder().decode(JsonPath.<String>read(full, "$.event_key"));
        assertThat(key).hasSize(32);
        String token = TicketToken.encodeStatic(key, f.event(), t1, f.zone1());
        byte[] real = events.withEventKey(f.c().org(), f.event(), byte[]::clone);
        assertThat(TicketToken.verify(TicketToken.decode(token), f.event(), real, TicketToken.slotAt(Instant.now())))
                .isEmpty();

        // a sale and a cancel after v1 → v2; the delta from 1 has exactly those two T lines, without K_e
        UUID t3 = ticket(f, f.zone2(), "Salma Bennani");
        ticketStatus(t2, "CANCELLED");
        age(f.event());
        assertThat(manifests.build(f.c().org(), f.event())).contains(2);
        String delta = manifest(f.device(), f.event(), "?since=1");
        assertKeys(delta, "$", "event_id", "version", "since", "rows", "payload", "signature", "server_time");
        assertThat(JsonPath.<Integer>read(delta, "$.version")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(delta, "$.since")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(delta, "$.rows")).isEqualTo(3);
        assertThat(verifies(delta)).isTrue();
        assertThat(tLines(delta)).containsExactlyInAnyOrder(tLine(t2, f.zone1(), "CANCELLED", ""),
                tLine(t3, f.zone2(), "VALID", "S. B."));
        assertThat(JsonPath.<String>read(delta, "$.payload")).contains("\nversion\t2\nsince\t1\n")
                .contains("\nrows\t3\n").contains("\tG1\tPorte G1\t" + f.zone1() + "\n")
                .contains("\tG2\tPorte G2\t" + f.zone1() + "," + f.zone2() + "\n");
        assertThat(downloaded(f.device(), f.event())).isEqualTo(2);

        // since = latest → Z and G lines, no T
        String none = manifest(f.device(), f.event(), "?since=2");
        assertThat(tLines(none)).isEmpty();
        assertThat(verifies(none)).isTrue();
        assertThat(JsonPath.<Integer>read(none, "$.rows")).isEqualTo(3);

        // since absent, unknown or above the latest → full with K_e
        for (String q : List.of("", "?since=99", "?since=0", "?since=-1")) {
            String again = manifest(f.device(), f.event(), q);
            assertThat(JsonPath.<Integer>read(again, "$.since")).as(q).isEqualTo(0);
            assertThat(JsonPath.<Integer>read(again, "$.version")).isEqualTo(2);
            assertThat(JsonPath.<String>read(again, "$.event_key")).isEqualTo(JsonPath.read(full, "$.event_key"));
            assertThat(tLines(again)).hasSize(3);
            assertThat(verifies(again)).isTrue();
        }
    }

    @Test
    void neitherPayloadNorJsonCarriesTheHolderNameOrPhone() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        jdbc.sql("insert into users (phone_e164) values (?) on conflict do nothing").param(HOLDER_PHONE).update();
        UUID user = jdbc.sql("select id from users where phone_e164 = ?").param(HOLDER_PHONE).query(UUID.class).single();
        UUID t = ticket(f, f.zone1(), HOLDER);
        jdbc.sql("update tickets set holder_user_id = ? where id = ?").params(user, t).update();
        manifests.build(f.c().org(), f.event());
        UUID t2 = ticket(f, f.zone2(), HOLDER);
        age(f.event());
        manifests.build(f.c().org(), f.event());
        for (String json : List.of(manifest(f.device(), f.event(), ""), manifest(f.device(), f.event(), "?since=1"))) {
            assertThat(json).doesNotContain("Zakaria").doesNotContain("Fixturename").doesNotContain("600000000")
                    .doesNotContain("holder_name").doesNotContain(user.toString());
        }
        String payload = jdbc.sql("select body from manifests where event_id = ? and version = 2").param(f.event())
                .query((rs, n) -> gunzip(rs.getBytes(1))).single();
        assertThat(payload).doesNotContain("Zakaria").doesNotContain("Fixturename")
                .contains(tLine(t2, f.zone2(), "VALID", "Z. F."));
    }

    /** 04 §Holder names, Arabic labels (lot-4 Q66): ordered and paid through the fan API, the T row has Arabic initials. */
    @Test
    void anArabicHolderGetsArabicInitials() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(2)));
        // The fixture sets ON_SALE without publishing: give it the publish default cutoff (T0 - 60 min).
        jdbc.sql("update events set refund_cutoff_at = starts_at - interval '60 minutes' where id = ?").param(f.event())
                .update();
        String fan = fanToken(randomPhone());
        String orderId = JsonPath.read(body(mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + fan)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 1,
                         "holders": [{"name": "يزيد عقل"}]}]}
                        """.formatted(f.event(), f.price()))).andExpect(status().isCreated())), "$.id");
        String paymentId = JsonPath.read(body(mvc.perform(post("/fan/orders/" + orderId + "/pay")
                .header("Authorization", "Bearer " + fan).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\": \"CARD\"}")).andExpect(status().isOk())), "$.payment_id");
        long total = jdbc.sql("select total_mad from orders where id = ?::uuid").param(orderId).query(Long.class).single();
        var callback = post("/payments/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        PaymentTest.approve(paymentId, total).forEach(callback::param);
        mvc.perform(callback).andExpect(status().isSeeOther());
        UUID t = jdbc.sql("select id from tickets where order_id = ?::uuid and status = 'VALID'").param(orderId)
                .query(UUID.class).single();

        assertThat(manifests.build(f.c().org(), f.event())).contains(1);
        assertThat(tLines(manifest(f.device(), f.event(), ""))).containsExactly(tLine(t, f.zone1(), "VALID", "ي. ع."));
    }

    // ---------- build rules ----------

    @Test
    void aVersionIsBuiltOnlyWhenTheContentChangedAndTheLatestIsFiveMinutesOld() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        ticket(f, f.zone1(), HOLDER);
        assertThat(manifests.build(f.c().org(), f.event())).contains(1);
        assertThat(manifests.build(f.c().org(), f.event())).isEmpty(); // no change
        UUID t = ticket(f, f.zone1(), null);
        assertThat(manifests.build(f.c().org(), f.event())).isEmpty(); // changed, but v1 is < 5 min old
        ticketStatus(t, "BLOCKED");
        age(f.event());
        assertThat(manifests.build(f.c().org(), f.event())).contains(2);
        age(f.event());
        assertThat(manifests.build(f.c().org(), f.event())).isEmpty(); // old enough, nothing changed
        assertThat(versions(f.event())).isEqualTo(2);
        // a gate rename is a change too
        jdbc.sql("update gates set name = 'Porte Nord' where code = 'G2' and organizer_id = ?").param(f.c().org()).update();
        assertThat(manifests.build(f.c().org(), f.event())).contains(3);
        assertThat(jdbc.sql("select row_count from manifests where event_id = ? and version = 3").param(f.event())
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void theJobBuildsOnSaleEventsFromTheLeadUntilThirtyMinutesAfterTheStart() throws Exception {
        Fixture inLead = fixture(Instant.now().plus(Duration.ofMinutes(170)));
        Fixture beforeLead = fixture(Instant.now().plus(Duration.ofMinutes(190)));
        Fixture justStarted = fixture(Instant.now().minus(Duration.ofMinutes(29)));
        Fixture over = fixture(Instant.now().minus(Duration.ofMinutes(31)));
        Fixture draft = fixture(Instant.now().plus(Duration.ofHours(1)));
        setStatus(draft.event(), "DRAFT");
        Fixture gatesOpen = fixture(Instant.now().plus(Duration.ofHours(1)));
        setStatus(gatesOpen.event(), "GATES_OPEN");
        manifests.buildDue();
        assertThat(versions(inLead.event())).isEqualTo(1);
        assertThat(versions(justStarted.event())).isEqualTo(1);
        assertThat(versions(gatesOpen.event())).isEqualTo(1);
        assertThat(versions(beforeLead.event())).isZero();
        assertThat(versions(over.event())).isZero();
        assertThat(versions(draft.event())).isZero();
        manifests.buildDue();
        assertThat(versions(inLead.event())).isEqualTo(1);
    }

    @Test
    void parallelBuildsMakeOneVersion() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        tickets(f, 2000);
        assertThat(parallelBuilds(f, 4)).containsExactly(Optional.of(1));
        tickets(f, 1);
        age(f.event());
        assertThat(parallelBuilds(f, 4)).containsExactly(Optional.of(2));
        assertThat(versions(f.event())).isEqualTo(2);
    }

    @Test
    void aBuildSkipsWhileAnotherInstanceHoldsTheBuildLockAndAnExistingVersionIsNotInserted() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        ticket(f, f.zone1(), HOLDER);
        CountDownLatch[] held = holdTransaction(() -> jdbc.sql("select pg_advisory_xact_lock(?)")
                .param((4L << 32) | f.event()).query((rs, n) -> 1).list());
        try {
            assertThat(manifests.build(f.c().org(), f.event())).isEmpty(); // try-lock: skipped, not waiting
        } finally {
            held[0].countDown();
        }
        assertThat(held[1].await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(versions(f.event())).isZero();
        assertThat(manifests.build(f.c().org(), f.event())).contains(1);
        // the unique (event_id, version) turns a lost race into false, not an error
        assertThat(store.insert(f.c().org(), f.event(), 1, Instant.now(), "JMH-MANIFEST\t1\n", new byte[64], 0))
                .isFalse();
        assertThat(versions(f.event())).isEqualTo(1);
    }

    /** The distinct results of {@code n} builds started together (an instance either builds or skips). */
    List<Optional<Integer>> parallelBuilds(Fixture f, int n) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(n)) {
            List<Future<Optional<Integer>>> runs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return manifests.build(f.c().org(), f.event());
                }));
            }
            start.countDown();
            List<Optional<Integer>> built = new ArrayList<>();
            for (var r : runs) {
                Optional<Integer> v = r.get();
                if (v.isPresent()) {
                    built.add(v);
                }
            }
            return built;
        }
    }

    // ---------- event lock ----------

    @Test
    void aDownloadWaitsForAnAssignmentChangeHoldingTheEventLock() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        manifests.build(f.c().org(), f.event());
        // what PUT /org/events/{id}/devices does to a removed device, under the exclusive event lock
        CountDownLatch[] held = holdTransaction(() -> {
            assignments.lockEvent(f.c().org(), f.event());
            jdbc.sql("delete from device_assignments where device_id = ?").param(f.device().id()).update();
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> download = pool.submit(() -> get(f.device(), f.event(), ""));
            Thread.sleep(500);
            assertThat(download.isDone()).as("the download waits for the PUT to commit").isFalse();
            held[0].countDown();
            MvcResult r = download.get(10, TimeUnit.SECONDS);
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("event_key");
        }
        assertThat(jdbc.sql("select count(*) from device_assignments where device_id = ?").param(f.device().id())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aRecordedDownloadMakesTheDeviceInUseForTheNextPut() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        manifests.build(f.c().org(), f.event());
        manifest(f.device(), f.event(), "");
        call(f.c(), put("/org/events/" + f.event() + "/devices"), "{\"assignments\": []}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_IN_USE"));
    }

    // ---------- errors ----------

    @Test
    void pendingAndRevokedDevicesGet403() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        manifests.build(f.c().org(), f.event());
        Dev pending = pending(f.c(), "PDA P");
        send(pending, Req.now("GET", "/v1/gate/events/" + f.event() + "/manifest", ""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"))
                .andExpect(jsonPath("$.details.status").value("PENDING"));
        call(f.c(), post("/org/devices/" + f.device().id() + "/revoke")).andExpect(status().isOk());
        send(f.device(), Req.now("GET", "/v1/gate/events/" + f.event() + "/manifest", ""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"))
                .andExpect(jsonPath("$.details.status").value("REVOKED"));
        assertThat(downloaded(f.device(), f.event())).isNull();
    }

    @Test
    void aNonIntegerSinceIsABadRequest() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        manifests.build(f.c().org(), f.event());
        for (String q : List.of("?since=abc", "?since=1.5")) {
            String json = body(send(f.device(), Req.now("GET", "/v1/gate/events/" + f.event() + "/manifest" + q, ""))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST")));
            assertKeys(json, "$", "code", "message", "details");
        }
        assertThat(downloaded(f.device(), f.event())).isNull();
    }

    @Test
    void notReadyBeforeTheLeadOrBeforeAnyBuild() throws Exception {
        Instant startsAt = Instant.parse(Instant.now().plus(Duration.ofHours(5)).toString().replaceAll("\\.\\d+", ""));
        Fixture f = fixture(startsAt);
        String json = body(send(f.device(), Req.now("GET", "/v1/gate/events/" + f.event() + "/manifest", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANIFEST_NOT_READY")));
        assertThat(Instant.parse(JsonPath.read(json, "$.details.available_at")))
                .isEqualTo(startsAt.minus(Duration.ofHours(3)));

        // inside the lead but nothing built yet
        jdbc.sql("update events set starts_at = now() + interval '1 hour' where id = ?").param(f.event()).update();
        send(f.device(), Req.now("GET", "/v1/gate/events/" + f.event() + "/manifest", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANIFEST_NOT_READY"));
        assertThat(manifests.build(f.c().org(), f.event())).contains(1);
        manifest(f.device(), f.event(), "");

        // built, then the start moves later: not available again until the new T0 - 3 h
        jdbc.sql("update events set starts_at = now() + interval '3 hours 1 minute' where id = ?").param(f.event()).update();
        send(f.device(), Req.now("GET", "/v1/gate/events/" + f.event() + "/manifest?since=1", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANIFEST_NOT_READY"));
        jdbc.sql("update events set starts_at = now() + interval '2 hours 59 minutes' where id = ?").param(f.event())
                .update();
        manifest(f.device(), f.event(), "?since=1");
    }

    @Test
    void closedEventIs409() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        manifests.build(f.c().org(), f.event());
        setStatus(f.event(), "CLOSED");
        send(f.device(), Req.now("GET", "/v1/gate/events/" + f.event() + "/manifest", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_CLOSED"))
                .andExpect(header().doesNotExist("Content-Encoding"));
        assertThat(downloaded(f.device(), f.event())).isNull();
    }

    @Test
    void unassignedDevicesAndOtherOrganizersGet404() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        manifests.build(f.c().org(), f.event());
        Dev unassigned = active(f.c());
        Fixture other = fixture(Instant.now().plus(Duration.ofHours(1)));
        manifests.build(other.c().org(), other.event());
        for (Dev d : List.of(unassigned, other.device())) {
            String json = body(send(d, Req.now("GET", "/v1/gate/events/" + f.event() + "/manifest", ""))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND")));
            assertThat(json).doesNotContain("event_key").doesNotContain("JMH-MANIFEST");
        }
        send(f.device(), Req.now("GET", "/v1/gate/events/2147483647/manifest", "")).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select count(*) from device_assignments where device_id = ? and manifest_version is not null")
                .param(other.device().id()).query(Integer.class).single()).isZero();
    }

    // ---------- size ----------

    @Test
    void fortyFiveThousandTicketsFitInOneAndAHalfMegabytesGzipped() throws Exception {
        Fixture f = fixture(Instant.now().plus(Duration.ofHours(1)));
        // both zones, every status, 676 distinct initials
        jdbc.sql("""
                        insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, holder_name,
                                             holder_initials, status)
                        select ?, ?, ?, case when i % 3 = 0 then ? else ? end, ?::uuid, 'Fan Number',
                               chr(65 + (i * 7) % 26) || '. ' || chr(65 + (i / 26 * 11) % 26) || '.',
                               (case when i % 50 = 0 then 'CANCELLED' when i % 97 = 0 then 'BLOCKED'
                                     when i % 101 = 0 then 'REFUNDED' else 'VALID' end)::ticket_status
                        from generate_series(1, 45000) i
                        """)
                .params(f.c().org(), order(f), f.event(), f.zone2(), f.zone1(), f.price()).update();
        assertThat(manifests.build(f.c().org(), f.event())).contains(1);
        MvcResult r = get(f.device(), f.event(), "");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        int size = r.getResponse().getContentAsByteArray().length;
        System.out.println("Manifest of 45,000 mixed tickets: " + size + " bytes gzipped");
        assertThat(size).isLessThan(1_500_000);
        String json = manifest(f.device(), f.event(), "");
        assertThat(JsonPath.<Integer>read(json, "$.rows")).isEqualTo(45_000);
        assertThat(tLines(json)).hasSize(45_000);
        assertThat(verifies(json)).isTrue();
    }

    private static String gunzip(byte[] body) {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
