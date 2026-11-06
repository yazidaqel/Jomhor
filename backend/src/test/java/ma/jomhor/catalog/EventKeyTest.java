package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.KeyEncryptor;
import ma.jomhor.common.Secrets;
import ma.jomhor.common.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * K_e (docs/04-api.md POST /org/events, 02 §Keys): generated per event, AES-256-GCM at rest bound to the
 * organizer, master key from env only, and absent from every /org/events* response.
 */
class EventKeyTest extends OrgApiTest {

    private static final Pattern KEYISH_NAME = Pattern.compile("key|enc|secret", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE64_40 = Pattern.compile("[A-Za-z0-9+/_-]{40,}={0,2}");

    @Autowired
    KeyEncryptor keys;

    @Autowired
    EventService events;

    private byte[] keyEnc(int eventId) {
        return jdbc.sql("select event_key_enc from events where id = ?").param(eventId).query(byte[].class).single();
    }

    @Test
    void eachEventGetsItsOwnKeyBoundToItsOrganizerAndEvent() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int e1 = event(a, venueId);
        int e2 = event(a, venueId);

        byte[] enc1 = keyEnc(e1);
        byte[] enc2 = keyEnc(e2);
        assertThat(enc1).hasSize(60); // nonce 12 | ciphertext 32 | tag 16
        assertThat(enc2).hasSize(60);
        byte[] k1 = keys.decrypt(enc1, a.org(), e1);
        byte[] k2 = keys.decrypt(enc2, a.org(), e2);
        assertThat(k1).hasSize(32);
        assertThat(k2).hasSize(32).isNotEqualTo(k1);
        assertThat(Arrays.copyOf(enc1, 12)).isNotEqualTo(Arrays.copyOf(enc2, 12)); // fresh nonces

        // AAD = organizer_id | event_id: copied to another organizer or another event, it doesn't decrypt.
        assertThatThrownBy(() -> keys.decrypt(enc1, UUID.randomUUID(), e1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> keys.decrypt(enc1, a.org(), e2)).isInstanceOf(IllegalStateException.class);
        byte[] tampered = enc1.clone();
        tampered[20] ^= 1;
        assertThatThrownBy(() -> keys.decrypt(tampered, a.org(), e1)).isInstanceOf(IllegalStateException.class);

        // Same plaintext twice → different ciphertexts (random nonce).
        assertThat(keys.encrypt(k1, a.org(), e1)).isNotEqualTo(keys.encrypt(k1, a.org(), e1));
    }

    @Test
    void aKeyRowCopiedOntoAnotherEventOfTheSameOrganizerDoesNotDecrypt() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int e1 = event(a, venueId);
        int e2 = event(a, venueId);
        jdbc.sql("update events set event_key_enc = ? where id = ?").params(keyEnc(e1), e2).update();

        assertThat(events.withEventKey(a.org(), e1, byte[]::clone)).hasSize(32);
        assertThatThrownBy(() -> events.withEventKey(a.org(), e2, byte[]::clone))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withEventKeyLendsTheKeyAndZeroesItAfterwards() throws Exception {
        Caller a = admin();
        Caller b = admin();
        int id = event(a, venue(a, "Stade"));
        byte[] expected = keys.decrypt(keyEnc(id), a.org(), id);

        AtomicReference<byte[]> lent = new AtomicReference<>();
        byte[] seen = events.withEventKey(a.org(), id, k -> {
            lent.set(k);
            return k.clone();
        });
        assertThat(seen).isEqualTo(expected);
        assertThat(lent.get()).hasSize(32).containsOnly(0);

        lent.set(null);
        assertThatThrownBy(() -> events.withEventKey(a.org(), id, k -> {
            lent.set(k);
            throw new IllegalArgumentException("boom");
        })).hasMessage("boom");
        assertThat(lent.get()).hasSize(32).containsOnly(0);

        // Tenant-scoped: another organizer's event is unknown, the callback never runs.
        assertThatThrownBy(() -> events.withEventKey(b.org(), id, k -> fail("callback ran")))
                .isInstanceOf(ApiException.class).hasMessageContaining("not found");
    }

    /** K_e leaves catalog only through EventService.withEventKey: no other module touches the port or its adapter. */
    @Test
    void onlyCatalogUsesTheKeyEncryptor() throws IOException {
        Path main = Path.of("src/main/java/ma/jomhor");
        try (var files = Files.walk(main)) {
            List<Path> offenders = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.startsWith(main.resolve("catalog")))
                    .filter(f -> {
                        try {
                            String src = Files.readString(f);
                            return src.contains("KeyEncryptor") || src.contains("eventKeyEnc");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }

    @Test
    void noOrgEventsResponseCarriesTheKey() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int nord = zone(a, venueId, "NORD", 100);
        List<ResultActions> calls = new ArrayList<>();
        String future = Instant.now().plus(Duration.ofDays(7)).toString();

        // 2xx, including the 204 of a DELETE
        ResultActions created = call(a, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match",
                 "starts_at": "%s", "metadata": {"opponent": "Raja"}}
                """.formatted(venueId, future)).andExpect(status().isCreated());
        calls.add(created);
        int id = JsonPath.read(body(created), "$.id");
        ResultActions priceCreated = postPrice(a, id, nord, "Plein", 10).andExpect(status().isCreated());
        calls.add(priceCreated);
        String priceId = JsonPath.read(body(priceCreated), "$.id");
        calls.add(call(a, patch("/org/events/" + id + "/prices/" + priceId), "{\"quota\": 12}").andExpect(status().isOk()));
        calls.add(call(a, get("/org/events/" + id + "/prices")).andExpect(status().isOk()));
        calls.add(call(a, patch("/org/events/" + id), "{\"title\": \"Derby\"}").andExpect(status().isOk()));
        calls.add(call(a, post("/org/events/" + id + "/publish")).andExpect(status().isOk()));
        calls.add(call(a, get("/org/events/" + id)).andExpect(status().isOk()));
        calls.add(call(a, get("/org/events")).andExpect(status().isOk()));
        String spare = price(a, id, nord, "Spare", 1);
        calls.add(call(a, delete("/org/events/" + id + "/prices/" + spare)).andExpect(status().isNoContent()));

        // one of each error body
        calls.add(call(a, get("/org/events/999999999")).andExpect(status().isNotFound()));
        calls.add(call(a, post("/org/events/" + id + "/publish")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE")));
        calls.add(call(a, post("/org/events"), "{\"title\": \" \"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")));
        int draft = event(a, venueId, future);
        calls.add(call(a, post("/org/events/" + draft + "/publish")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_QUOTA")));
        calls.add(postPrice(a, id, nord, "Trop", 1000).andExpect(jsonPath("$.code").value("QUOTA_EXCEEDS_CAPACITY")));
        setSold(priceId, 5);
        calls.add(call(a, patch("/org/events/" + id + "/prices/" + priceId), "{\"quota\": 4}")
                .andExpect(jsonPath("$.code").value("QUOTA_BELOW_SOLD")));
        calls.add(call(a, delete("/org/events/" + id + "/prices/" + priceId))
                .andExpect(jsonPath("$.code").value("PRICE_IN_USE")));

        List<byte[]> secrets = List.of(keyEnc(id), keys.decrypt(keyEnc(id), a.org(), id),
                keyEnc(draft), keys.decrypt(keyEnc(draft), a.org(), draft));
        List<String> encodings = new ArrayList<>();
        for (byte[] b : secrets) {
            encodings.addAll(List.of(Base64.getEncoder().encodeToString(b),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(b), HexFormat.of().formatHex(b)));
        }
        JsonMapper json = JsonMapper.builder().build();
        for (ResultActions r : calls) {
            String body = body(r);
            if (r.andReturn().getResponse().getStatus() == 204) {
                assertThat(body).isEmpty();
                continue;
            }
            assertNoKey(json.readTree(body), "$");
            for (String e : encodings) {
                assertThat(body).doesNotContainIgnoringCase(e);
            }
        }
    }

    private static void assertNoKey(JsonNode node, String path) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> p : node.properties()) {
                assertThat(KEYISH_NAME.matcher(p.getKey()).find()).as("field name at %s.%s", path, p.getKey()).isFalse();
                assertNoKey(p.getValue(), path + "." + p.getKey());
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node) {
                assertNoKey(child, path + "[" + i++ + "]");
            }
        } else if (node.isString()) {
            assertThat(BASE64_40.matcher(node.asString()).find()).as("base64-like value at %s", path).isFalse();
        }
    }

    // ---------- master key ----------

    @Test
    void masterKeyMustBeBase64Of32Bytes() {
        byte[] ok = Secrets.base64Of32Bytes("EVENT_KEY_MASTER", Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(ok).hasSize(32);
        for (String bad : new String[] {null, "", "not base64 at all!", Base64.getEncoder().encodeToString(new byte[31]),
                Base64.getEncoder().encodeToString(new byte[33])}) {
            assertThatThrownBy(() -> Secrets.base64Of32Bytes("EVENT_KEY_MASTER", bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("EVENT_KEY_MASTER must be base64 of exactly 32 bytes")
                    .hasNoCause();
        }
    }

    @Test
    void startupFailsWithoutAValidMasterKey() {
        assertThatThrownBy(() -> start(null).close()).hasStackTraceContaining("EVENT_KEY_MASTER");
        String shortKey = Base64.getEncoder().encodeToString("only-16-bytes-ab".getBytes());
        assertThatThrownBy(() -> start(shortKey).close())
                .hasStackTraceContaining("EVENT_KEY_MASTER must be base64 of exactly 32 bytes")
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain(shortKey));
    }

    private static String stackTrace(Throwable e) {
        var w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    /** Staging profile: the only secret missing is the one under test (no dev defaults). */
    private static AutoCloseable start(String master) {
        var pg = IntegrationTest.POSTGRES;
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=staging",
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--" + IntegrationTest.JWT_SECRET,
                "--" + IntegrationTest.OTP_SECRET));
        if (master != null) {
            args.add("--jomhor.event-key-master=" + master);
        }
        return SpringApplication.run(JomhorApplication.class, args.toArray(String[]::new));
    }
}
