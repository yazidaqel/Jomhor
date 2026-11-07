package ma.jomhor.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.gate.application.GateDevice;
import ma.jomhor.gate.domain.DeviceSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract: docs/04-api.md §Gate auth (device signature, clock window, server check order), POST /gate/devices/enrol,
 * /org/devices, /org/devices/enrol-codes, /org/devices/{id}/approve|revoke. Software P-256 keys stand in for the
 * Keystore. A test-only /gate/test/echo route shows what a request that passed the filter carries.
 */
@Import(DeviceApiTest.Echo.class)
class DeviceApiTest extends GateApiTest {

    @TestConfiguration
    static class Echo {
        @Bean
        EchoController gateEcho() {
            return new EchoController();
        }
    }

    @RestController
    static class EchoController {
        @RequestMapping("/gate/test/echo")
        Map<String, String> echo(@RequestAttribute(GateDevice.ATTRIBUTE) GateDevice device,
                                 @RequestBody(required = false) String body) {
            return Map.of("device_id", device.id().toString(), "organizer_id", device.organizerId().toString(),
                    "body", body == null ? "" : body);
        }
    }

    static final String TEST_MANIFEST_PUBLIC_KEY = "9657JB-9nYlMlNMM2AYY8UzKMLcbfTkI3UKd6SDuEDc";
    static final String ECHO = "/v1/gate/test/echo";

    @Autowired
    JwtEncoder jwtEncoder;

    // ---------- enrolment ----------

    @Test
    void enrolCreatesAPendingDeviceWithItsFingerprintAndThePinnedManifestKey() throws Exception {
        Caller a = admin();
        Caller b = admin();
        KeyPair key = newKey();
        byte[] spki = key.getPublic().getEncoded();

        String json = body(enrol(key, " PDA 12 ", enrolCode(a))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.fingerprint").value(DeviceSignature.fingerprint(spki)))
                .andExpect(jsonPath("$.manifest_public_key").value(TEST_MANIFEST_PUBLIC_KEY)));
        assertKeys(json, "$", "device_id", "status", "fingerprint", "manifest_public_key", "server_time");
        assertThat(DeviceSignature.fingerprint(spki)).matches("[0-9A-F]{4} [0-9A-F]{4}");
        Instant serverTime = Instant.parse(JsonPath.read(json, "$.server_time"));
        assertThat(Duration.between(serverTime, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        String id = JsonPath.read(json, "$.device_id");

        assertThat(jdbc.sql("select organizer_id from devices where id = ?::uuid").param(id).query(UUID.class).single())
                .isEqualTo(a.org());
        assertThat(jdbc.sql("select public_key from devices where id = ?::uuid").param(id).query(byte[].class).single())
                .isEqualTo(spki);
        String list = body(call(a, get("/org/devices")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(list, "$[*].id")).containsExactly(id);
        assertKeys(list, "$[0]", "id", "label", "status", "fingerprint", "enrolled_at", "approved_at", "last_seen_at");
        assertThat(JsonPath.<String>read(list, "$[0].label")).isEqualTo("PDA 12");
        assertThat(JsonPath.<String>read(list, "$[0].fingerprint")).isEqualTo(DeviceSignature.fingerprint(spki));
        assertThat(JsonPath.<Object>read(list, "$[0].approved_at")).isNull();
        call(b, get("/org/devices")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void enrolIsIdempotentOnThePublicKeyIncludingAnExactReplay() throws Exception {
        Caller a = admin();
        Caller b = admin();
        KeyPair key = newKey();
        Req first = enrolReq(key, "PDA 1", enrolCode(a));
        String id = JsonPath.read(body(send(key, null, first, first).andExpect(status().isCreated())), "$.device_id");

        // the same signed bytes again (replay inside the window): the same device, nothing new
        send(key, null, first, first).andExpect(status().isOk()).andExpect(jsonPath("$.device_id").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"));
        // a new request, another label, even another organizer's code: still the first device
        enrol(key, "Autre", enrolCode(b)).andExpect(status().isOk()).andExpect(jsonPath("$.device_id").value(id));
        call(a, post("/org/devices/" + id + "/approve")).andExpect(status().isOk());
        enrol(key, "PDA 1", enrolCode(a)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(jdbc.sql("select count(*) from devices where public_key = ?").param(key.getPublic().getEncoded())
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("select organizer_id from devices where id = ?::uuid").param(id).query(UUID.class).single())
                .isEqualTo(a.org());
        assertThat(jdbc.sql("select label from devices where id = ?::uuid").param(id).query(String.class).single())
                .isEqualTo("PDA 1");
    }

    @Test
    void enrolNeedsAValidEnrolCode() throws Exception {
        Caller a = admin();
        String phone = randomPhone();
        staff(a.org(), phone, "ADMIN", true);
        Instant past = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject(a.org().toString()).audience(List.of("enrol"))
                        .issuedAt(past).expiresAt(past.plus(Duration.ofMinutes(30))).build())).getTokenValue();
        String orgToken = orgToken(phone);
        String fanToken = fanToken(randomPhone());
        String valid = enrolCode(a);
        // a middle character of the signature: all 6 bits count there (the last one has padding bits)
        int at = valid.lastIndexOf('.') + 10;
        String tampered = valid.substring(0, at) + (valid.charAt(at) == 'A' ? 'B' : 'A') + valid.substring(at + 1);

        for (String code : new String[] {expired, orgToken, fanToken, tampered, "garbage", ""}) {
            KeyPair key = newKey();
            assertUnauthorized(enrol(key, "PDA", code));
        }
        KeyPair noCode = newKey();
        String body = "{\"public_key\": \"%s\", \"label\": \"PDA\"}".formatted(b64(noCode.getPublic().getEncoded()));
        Req r = Req.now("POST", "/v1/gate/devices/enrol", body);
        assertUnauthorized(send(noCode, null, r, r));
        assertThat(jdbc.sql("select count(*) from devices where organizer_id = ?").param(a.org())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void anEnrolCodeIsNotAnOrgToken() throws Exception {
        Caller a = admin();
        String code = enrolCode(a);
        mvc.perform(get("/org/devices").header("Authorization", "Bearer " + code).header("X-Organizer-Id", a.org()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/fan/me").header("Authorization", "Bearer " + code)).andExpect(status().isUnauthorized());
    }

    @Test
    void enrolChecksTheClockToo() throws Exception {
        Caller a = admin();
        KeyPair key = newKey();
        Req r = enrolReq(key, "PDA", enrolCode(a)).time(System.currentTimeMillis() + 360_000);
        send(key, null, r, r).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DEVICE_CLOCK_SKEW"))
                .andExpect(jsonPath("$.details.server_time").isString());
        assertThat(jdbc.sql("select count(*) from devices where organizer_id = ?").param(a.org())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void reEnrollingARevokedKeyReturnsTheRevokedDevice() throws Exception {
        Caller a = admin();
        Dev d = pending(a, "PDA");
        call(a, post("/org/devices/" + d.id() + "/revoke")).andExpect(status().isOk());
        enrol(d.key(), "PDA", enrolCode(a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.device_id").value(d.id().toString()))
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void enrolLabelIsOneToFortyCodePointsWithoutControlCharacters() throws Exception {
        Caller a = admin();
        String code = enrolCode(a);
        String stadium = Character.toString(0x1F3DF); // 2 UTF-16 units, 1 code point
        for (String bad : new String[] {"", "   ", "x".repeat(41), stadium.repeat(41), "PDA\\t1", "PDA\\n", "\\rPDA",
                "PDA\\u00071"} /* JSON escapes */) {
            enrol(newKey(), bad, code).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.fields.label").exists());
        }
        enrol(newKey(), "x".repeat(40), code).andExpect(status().isCreated());
        enrol(newKey(), stadium.repeat(40), code).andExpect(status().isCreated());
        enrol(newKey(), "x", code).andExpect(status().isCreated());
    }

    @Test
    void enrolIsSignedByTheKeyInItsBody() throws Exception {
        Caller a = admin();
        String code = enrolCode(a);
        KeyPair key = newKey();
        Req r = enrolReq(key, "PDA", code);
        assertUnauthorized(send(newKey(), null, r, r)); // signed by another key

        var p384 = KeyPairGenerator.getInstance("EC");
        p384.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair wrongCurve = p384.generateKeyPair();
        Req r384 = Req.now("POST", "/v1/gate/devices/enrol", enrolBody(wrongCurve, "PDA", code));
        var s = Signature.getInstance("SHA256withECDSA");
        s.initSign(wrongCurve.getPrivate());
        s.update(DeviceSignature.canonical(r384.method(), r384.path(), r384.time(),
                r384.body().getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        assertUnauthorized(mvc.perform(post(URI.create(r384.path())).contextPath("/v1")
                .header("X-Device-Time", String.valueOf(r384.time())).header("X-Device-Signature", b64(s.sign()))
                .contentType(MediaType.APPLICATION_JSON).content(r384.body())));

        Req noKey = Req.now("POST", "/v1/gate/devices/enrol", "{\"label\": \"PDA\", \"enrol_code\": \"" + code + "\"}");
        assertUnauthorized(send(key, null, noKey, noKey));
        Req notJson = Req.now("POST", "/v1/gate/devices/enrol", "not json");
        assertUnauthorized(send(key, null, notJson, notJson));
        assertThat(jdbc.sql("select count(*) from devices where organizer_id = ?").param(a.org())
                .query(Integer.class).single()).isZero();
    }

    // ---------- signature ----------

    @Test
    void aValidSignaturePassesWithTheDeviceAndTheExactBody() throws Exception {
        Caller a = admin();
        Dev d = active(a);
        assertThat(jdbc.sql("select last_seen_at from devices where id = ?").param(d.id()).query(Instant.class)
                .optional()).isEmpty();
        String body = "{\"cursor\": 0,\n \"note\": \"\u00e9t\u00e9\"}";

        send(d, Req.now("POST", ECHO + "?since=3&x=%2F", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device_id").value(d.id().toString()))
                .andExpect(jsonPath("$.organizer_id").value(a.org().toString()))
                .andExpect(jsonPath("$.body").value(body));
        send(d, Req.now("GET", ECHO, "")).andExpect(status().isOk());
        assertThat(jdbc.sql("select last_seen_at from devices where id = ?").param(d.id()).query(Instant.class).single())
                .isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
        // a signed request to a route that doesn't exist passes the filter and gets the usual 404
        send(d, Req.now("GET", "/v1/gate/nothing-here", "")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void oneChangedByteInMethodPathQueryTimeOrBodyIs401() throws Exception {
        Caller a = admin();
        Dev d = active(a);
        Req signed = Req.now("POST", ECHO + "?since=3", "{\"cursor\": 12}");
        send(d.key(), d.id(), signed, signed).andExpect(status().isOk());

        List<Req> tampered = List.of(
                new Req("PUT", signed.path(), signed.time(), signed.body()),
                new Req("POST", "/v1/gate/test/echO?since=3", signed.time(), signed.body()),
                new Req("POST", ECHO + "?since=4", signed.time(), signed.body()),
                new Req("POST", ECHO, signed.time(), signed.body()),
                new Req("POST", ECHO + "?since=3", signed.time() + 1, signed.body()),
                new Req("POST", ECHO + "?since=3", signed.time(), "{\"cursor\": 13}"),
                new Req("POST", ECHO + "?since=3", signed.time(), "{\"cursor\": 12} "));
        for (Req sent : tampered) {
            assertUnauthorized(send(d.key(), d.id(), signed, sent));
        }
        Dev other = active(a);
        assertUnauthorized(send(d.key(), other.id(), signed, signed)); // another device's id
        assertUnauthorized(send(d.key(), UUID.randomUUID(), signed, signed)); // unknown device
    }

    @Test
    void missingOrMalformedHeadersAre401() throws Exception {
        Caller a = admin();
        Dev d = active(a);
        Req r = Req.now("GET", ECHO, "");
        String sig = sign(d.key(), r);
        String time = String.valueOf(r.time());
        String id = d.id().toString();
        String[][] headers = {
                {null, time, sig}, {id, null, sig}, {id, time, null},
                {"not-a-uuid", time, sig}, {id, "12.5", sig}, {id, time, "!!!"}, {id, time, sig + "AA"},
                {id, "+" + time, sig}, {id, "0" + time, sig}, {id, " " + time, sig}, {id, "1".repeat(16), sig}};
        for (String[] h : headers) {
            var b = get(URI.create(ECHO)).contextPath("/v1");
            if (h[0] != null) {
                b.header("X-Device-Id", h[0]);
            }
            if (h[1] != null) {
                b.header("X-Device-Time", h[1]);
            }
            if (h[2] != null) {
                b.header("X-Device-Signature", h[2]);
            }
            assertUnauthorized(mvc.perform(b));
        }
        assertUnauthorized(mvc.perform(get(URI.create("/v1/gate/events")).contextPath("/v1")));
    }

    @Test
    void encodedOrOddGatePathsAreStillChecked() throws Exception {
        // the filter scopes on the decoded path, like routing: an encoded "g" doesn't skip it
        assertUnauthorized(mvc.perform(get(URI.create("/v1/%67ate/test/echo")).contextPath("/v1")));
        assertUnauthorized(mvc.perform(get(URI.create("/v1/gate/test/echo/")).contextPath("/v1")));
        assertUnauthorized(mvc.perform(post(URI.create("/v1/%67ate/devices/enrol")).contextPath("/v1")
                .contentType(MediaType.APPLICATION_JSON).content("{}")));
        mvc.perform(get(URI.create("/v1//gate/test/echo")).contextPath("/v1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void clockWindowIsFiveMinutesEitherWay() throws Exception {
        Caller a = admin();
        Dev d = active(a);
        long now = System.currentTimeMillis();
        // the server's clock reads at or after `now`: +5 min exactly is inside, -5 min + 1 s too
        send(d, Req.now("GET", ECHO, "").time(now + 300_000)).andExpect(status().isOk());
        send(d, Req.now("GET", ECHO, "").time(now - 299_000)).andExpect(status().isOk());

        for (long t : new long[] {now + 301_000, now + 360_000, now - 300_001, now - 3_600_000}) {
            String json = body(send(d, Req.now("GET", ECHO, "").time(t))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("DEVICE_CLOCK_SKEW")));
            assertKeys(json, "$.details", "server_time");
            Instant serverTime = Instant.parse(JsonPath.read(json, "$.details.server_time"));
            assertThat(Duration.between(serverTime, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        }
    }

    @Test
    void signatureIsCheckedBeforeTheClockAndTheClockBeforeTheStatus() throws Exception {
        Caller a = admin();
        Dev d = active(a);
        Req skewed = Req.now("GET", ECHO, "").time(System.currentTimeMillis() + 360_000);
        assertUnauthorized(send(newKey(), d.id(), skewed, skewed)); // bad signature: not DEVICE_CLOCK_SKEW

        Dev p = pending(a, "PDA pending");
        send(p.key(), p.id(), skewed, skewed).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DEVICE_CLOCK_SKEW"));
        call(a, post("/org/devices/" + p.id() + "/revoke")).andExpect(status().isOk());
        Req ok = Req.now("GET", "/v1/gate/events", "");
        assertUnauthorized(send(newKey(), p.id(), ok, ok)); // a revoked device's status is not shown unsigned
    }

    @Test
    void bodyAboveOneMebibyteIs413() throws Exception {
        Caller a = admin();
        Dev d = active(a);
        String big = "\"" + "x".repeat(1024 * 1024) + "\"";
        send(d, Req.now("POST", ECHO, big)).andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ---------- status ----------

    @Test
    void pendingDeviceGets403OnEveryGatePath() throws Exception {
        Caller a = admin();
        Dev p = pending(a, "PDA");
        for (Req r : List.of(Req.now("GET", "/v1/gate/events", ""), Req.now("GET", "/v1/gate/events/1/manifest", ""),
                Req.now("POST", "/v1/gate/events/1/scans", "{\"cursor\": 0, \"scans\": []}"))) {
            String json = body(send(p, r).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"))
                    .andExpect(jsonPath("$.details.status").value("PENDING")));
            assertKeys(json, "$.details", "status");
        }
        assertThat(jdbc.sql("select last_seen_at from devices where id = ?").param(p.id()).query(Instant.class)
                .optional()).isEmpty();
    }

    @Test
    void revokedDeviceGets403OnEveryGatePathScansIncluded() throws Exception {
        Caller a = admin();
        Dev d = active(a);
        send(d, Req.now("GET", ECHO, "")).andExpect(status().isOk());
        call(a, post("/org/devices/" + d.id() + "/revoke")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        for (Req r : List.of(Req.now("GET", ECHO, ""), Req.now("GET", "/v1/gate/events/1/manifest?since=2", ""),
                Req.now("POST", "/v1/gate/events/1/scans", "{\"cursor\": 0, \"scans\": []}"),
                Req.now("POST", "/v1/gate/events/1/closed-ack", ""))) {
            send(d, r).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"))
                    .andExpect(jsonPath("$.details.status").value("REVOKED"));
        }
    }

    // ---------- organizer ----------

    @Test
    void enrolCodeIsAThirtyMinuteEnrolJwtForTheOrganizer() throws Exception {
        Caller a = admin();
        String json = body(call(a, post("/org/devices/enrol-codes")).andExpect(status().isCreated()));
        assertKeys(json, "$", "enrol_code", "expires_at");
        Instant expiresAt = Instant.parse(JsonPath.read(json, "$.expires_at"));
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(30));
        String payload = new String(Base64.getUrlDecoder().decode(((String) JsonPath.read(json, "$.enrol_code"))
                .split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(payload, "$.sub")).isEqualTo(a.org().toString());
        assertThat(JsonPath.<Object>read(payload, "$.aud").toString()).contains("enrol").doesNotContain("org");
        // reusable for a batch
        String code = JsonPath.read(json, "$.enrol_code");
        enrol(newKey(), "PDA 1", code).andExpect(status().isCreated());
        enrol(newKey(), "PDA 2", code).andExpect(status().isCreated());
    }

    @Test
    void listIsPendingFirstThenByLabel() throws Exception {
        Caller a = admin();
        Dev b = pending(a, "B");
        Dev c = pending(a, "C");
        Dev x = pending(a, "A");
        call(a, post("/org/devices/" + x.id() + "/approve")).andExpect(status().isOk());
        Dev z = pending(a, "Z");
        call(a, post("/org/devices/" + z.id() + "/revoke")).andExpect(status().isOk());
        String json = body(call(a, get("/org/devices")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[*].label")).containsExactly("B", "C", "A", "Z");
        assertThat(JsonPath.<List<String>>read(json, "$[*].status")).containsExactly("PENDING", "PENDING", "ACTIVE",
                "REVOKED");
        assertThat(JsonPath.<List<String>>read(json, "$[*].id")).containsExactly(b.id().toString(), c.id().toString(),
                x.id().toString(), z.id().toString());
    }

    @Test
    void approveAndRevokeFollowTheStatusRulesWithAudit() throws Exception {
        Caller a = admin();
        Dev d = pending(a, "PDA");
        String approved = body(call(a, post("/org/devices/" + d.id() + "/approve")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
        assertKeys(approved, "$", "id", "label", "status", "fingerprint", "enrolled_at", "approved_at", "last_seen_at");
        String approvedAt = JsonPath.read(approved, "$.approved_at");
        assertThat(approvedAt).isNotNull();
        call(a, post("/org/devices/" + d.id() + "/approve")).andExpect(status().isOk()) // ACTIVE: unchanged
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.approved_at").value(approvedAt));
        assertThat(audits("device.approve", d.id())).isOne();

        call(a, post("/org/devices/" + d.id() + "/revoke")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        call(a, post("/org/devices/" + d.id() + "/revoke")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(audits("device.revoke", d.id())).isOne();
        assertThat(jdbc.sql("select revoked_at from devices where id = ?").param(d.id()).query(Instant.class).single())
                .isNotNull();

        call(a, post("/org/devices/" + d.id() + "/approve")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_REVOKED"));
        assertThat(jdbc.sql("select status::text from devices where id = ?").param(d.id()).query(String.class).single())
                .isEqualTo("REVOKED");

        Dev p = pending(a, "PDA 2"); // a PENDING device can be revoked too
        call(a, post("/org/devices/" + p.id() + "/revoke")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void anotherOrganizersDeviceIs404() throws Exception {
        Caller a = admin();
        Caller b = admin();
        Dev d = pending(a, "PDA");
        for (String action : new String[] {"approve", "revoke"}) {
            call(b, post("/org/devices/" + d.id() + "/" + action)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            call(a, post("/org/devices/" + UUID.randomUUID() + "/" + action)).andExpect(status().isNotFound());
            call(a, post("/org/devices/not-a-uuid/" + action)).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.sql("select status::text from devices where id = ?").param(d.id()).query(String.class).single())
                .isEqualTo("PENDING");
        assertThat(audits("device.revoke", d.id())).isZero();
    }

    @Test
    void devicesAreAdminAndOpsOnly() throws Exception {
        Caller a = admin();
        Dev d = pending(a, "PDA");
        Caller ops = caller(a.org(), "OPS");
        call(ops, get("/org/devices")).andExpect(status().isOk());
        call(ops, post("/org/devices/enrol-codes")).andExpect(status().isCreated());
        call(ops, post("/org/devices/" + d.id() + "/approve")).andExpect(status().isOk());
        for (String role : new String[] {"FINANCE", "GATE", "SUPERVISOR"}) {
            Caller c = caller(a.org(), role);
            call(c, get("/org/devices")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            call(c, post("/org/devices/enrol-codes")).andExpect(status().isForbidden());
            call(c, post("/org/devices/" + d.id() + "/revoke")).andExpect(status().isForbidden());
        }
        assertThat(audits("device.revoke", d.id())).isZero();
    }
}
