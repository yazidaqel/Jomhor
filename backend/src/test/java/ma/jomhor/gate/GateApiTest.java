package ma.jomhor.gate;

import static org.hamcrest.Matchers.anEmptyMap;
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
import java.util.Base64;
import java.util.UUID;
import ma.jomhor.catalog.OrgApiTest;
import ma.jomhor.gate.domain.DeviceSignature;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

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
