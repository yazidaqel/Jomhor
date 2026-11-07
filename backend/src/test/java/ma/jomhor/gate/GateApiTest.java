package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import ma.jomhor.catalog.OrgApiTest;
import ma.jomhor.common.HolderNames;
import ma.jomhor.gate.domain.DeviceSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** /gate/** contract tests: software P-256 keys stand in for the Keystore; signed requests per 04 §Gate auth. */
abstract class GateApiTest extends OrgApiTest {

    record Req(String method, String path, long time, String body) {
        static Req now(String method, String path, String body) {
            return new Req(method, path, System.currentTimeMillis(), body);
        }

        Req time(long t) {
            return new Req(method, path, t, body);
        }
    }

    record Dev(KeyPair key, UUID id) {}

    // ---------- helpers ----------

    @Autowired
    PlatformTransactionManager transactions;

    /**
     * Runs {@code inside} in a transaction on another thread and keeps that transaction open (with any advisory
     * xact lock taken inside) until the returned latch is counted down. Returns once {@code inside} has run; the
     * second latch counts down when the transaction has committed.
     */
    CountDownLatch[] holdTransaction(Runnable inside) throws Exception {
        var ran = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        Thread.ofPlatform().start(() -> {
            new TransactionTemplate(transactions).executeWithoutResult(s -> {
                inside.run();
                ran.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            committed.countDown();
        });
        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
        return new CountDownLatch[] {release, committed};
    }

    /** An ON_SALE event starting at {@code startsAt}, two zones, gates G1 (zone 1) and G2 (both), one ACTIVE device on G1. */
    record Fixture(Caller c, int event, int zone1, int zone2, String price, Dev device) {}

    Fixture fixture(Instant startsAt) throws Exception {
        Caller c = admin();
        String v = venue(c, "Stade");
        int z1 = zone(c, v, "TRIB");
        int z2 = zone(c, v, "VIR");
        String g1 = gate(c, v, "G1", z1);
        gate(c, v, "G2", z1, z2);
        int e = event(c, v, startsAt.toString());
        String price = price(c, e, z1, "Normal", 100);
        setStatus(e, "ON_SALE");
        Dev d = active(c);
        assign(c, e, d, g1);
        return new Fixture(c, e, z1, z2, price, d);
    }

    String gate(Caller c, String venueId, String code, Integer... zoneIds) throws Exception {
        String id = JsonPath.read(body(call(c, post("/org/venues/" + venueId + "/gates"),
                "{\"code\": \"%s\", \"name\": \"Porte %s\"}".formatted(code, code)).andExpect(status().isCreated())),
                "$.id");
        call(c, put("/org/gates/" + id + "/zones"), "{\"zone_ids\": " + List.of(zoneIds) + "}")
                .andExpect(status().isOk());
        return id;
    }

    void assign(Caller c, int eventId, Dev d, String gateId) throws Exception {
        call(c, put("/org/events/" + eventId + "/devices"),
                "{\"assignments\": [{\"device_id\": \"%s\", \"gate_id\": \"%s\"}]}".formatted(d.id(), gateId))
                .andExpect(status().isOk());
    }

    UUID order(Fixture f) {
        return jdbc.sql("insert into orders (organizer_id, event_id, channel, status, total_mad, reserved_until) "
                        + "values (?, ?, 'WEB', 'PAID', 0, now()) returning id")
                .params(f.c().org(), f.event()).query(UUID.class).single();
    }

    UUID ticket(Fixture f, int zoneId, String holder) {
        return jdbc.sql("insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, holder_name, "
                        + "holder_initials) values (?, ?, ?, ?, ?::uuid, ?, ?) returning id")
                .params(f.c().org(), order(f), f.event(), zoneId, f.price(), holder,
                        holder == null ? null : HolderNames.initials(holder))
                .query(UUID.class).single();
    }

    void tickets(Fixture f, int count) {
        jdbc.sql("insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, holder_name, "
                        + "holder_initials) select ?, ?, ?, ?, ?::uuid, 'Fan Number', 'F. N.' from generate_series(1, ?)")
                .params(f.c().org(), order(f), f.event(), f.zone1(), f.price(), count).update();
    }

    static KeyPair newKey() throws Exception {
        var g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sign(KeyPair key, Req r) throws Exception {
        var s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key.getPrivate());
        s.update(DeviceSignature.canonical(r.method(), r.path(), r.time(), r.body().getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        return b64(s.sign());
    }

    /** Sends {@code sent} with the signature of {@code signed} (the same request unless a test tampers with it). */
    ResultActions send(KeyPair key, UUID deviceId, Req signed, Req sent) throws Exception {
        var b = request(HttpMethod.valueOf(sent.method()), URI.create(sent.path())).contextPath("/v1")
                .header("X-Device-Time", String.valueOf(sent.time()))
                .header("X-Device-Signature", sign(key, signed));
        if (deviceId != null) {
            b.header("X-Device-Id", deviceId.toString());
        }
        if (!sent.body().isEmpty()) {
            b.contentType(MediaType.APPLICATION_JSON).content(sent.body().getBytes(StandardCharsets.UTF_8));
        }
        return mvc.perform(b);
    }

    ResultActions send(Dev d, Req r) throws Exception {
        return send(d.key(), d.id(), r, r);
    }

    static String enrolBody(KeyPair key, String label, String code) {
        return "{\"public_key\": \"%s\", \"label\": \"%s\", \"enrol_code\": \"%s\"}"
                .formatted(b64(key.getPublic().getEncoded()), label, code);
    }

    static Req enrolReq(KeyPair key, String label, String code) {
        return Req.now("POST", "/v1/gate/devices/enrol", enrolBody(key, label, code));
    }

    ResultActions enrol(KeyPair key, String label, String code) throws Exception {
        Req r = enrolReq(key, label, code);
        return send(key, null, r, r);
    }

    String enrolCode(Caller c) throws Exception {
        return JsonPath.read(body(call(c, post("/org/devices/enrol-codes")).andExpect(status().isCreated())),
                "$.enrol_code");
    }

    Dev pending(Caller c, String label) throws Exception {
        KeyPair key = newKey();
        String json = body(enrol(key, label, enrolCode(c)).andExpect(status().isCreated()));
        return new Dev(key, UUID.fromString(JsonPath.read(json, "$.device_id")));
    }

    Dev active(Caller c) throws Exception {
        Dev d = pending(c, "PDA " + UUID.randomUUID().toString().substring(0, 4));
        call(c, post("/org/devices/" + d.id() + "/approve")).andExpect(status().isOk());
        return d;
    }

    int audits(String action, UUID target) {
        return jdbc.sql("select count(*) from audit_log where action = ? and target = ?")
                .params(action, target.toString()).query(Integer.class).single();
    }

    static void assertUnauthorized(ResultActions r) throws Exception {
        r.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Unauthorized"))
                .andExpect(jsonPath("$.details").value(anEmptyMap()));
    }
}
