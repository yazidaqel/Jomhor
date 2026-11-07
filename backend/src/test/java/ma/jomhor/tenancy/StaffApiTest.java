package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.OrgApiTest;
import ma.jomhor.tenancy.domain.Pins;
import org.junit.jupiter.api.Test;

/**
 * Contract: docs/04-api.md §Organizer /org/staff, /org/staff/{id}, /org/staff/{id}/pin and §Gate PINs. The PIN is
 * in the create and reset responses only; the verifier never leaves through /org.
 */
class StaffApiTest extends OrgApiTest {

    String createStaff(Caller c, String name, String role) throws Exception {
        return body(call(c, post("/org/staff"), "{\"name\": \"%s\", \"role\": \"%s\"}".formatted(name, role))
                .andExpect(status().isCreated()));
    }

    String verifier(String staffId) {
        return jdbc.sql("select pin_hash from staff where id = ?::uuid").param(staffId).query(String.class).single();
    }

    static String nextPin(String pin) {
        return "%04d".formatted((Integer.parseInt(pin) + 1) % 10_000);
    }

    @Test
    void createReturnsAFourDigitPinOnceAndStoresOnlyItsVerifier() throws Exception {
        Caller a = admin();
        String json = createStaff(a, "  Yazid   Aqel ", "GATE");
        assertKeys(json, "$", "id", "name", "role", "active", "has_pin", "pin");
        assertThat(JsonPath.<String>read(json, "$.name")).isEqualTo("Yazid Aqel");
        assertThat(JsonPath.<String>read(json, "$.role")).isEqualTo("GATE");
        assertThat(JsonPath.<Boolean>read(json, "$.active")).isTrue();
        assertThat(JsonPath.<Boolean>read(json, "$.has_pin")).isTrue();
        String pin = JsonPath.read(json, "$.pin");
        assertThat(pin).matches("[0-9]{4}");
        String id = JsonPath.read(json, "$.id");

        String v = verifier(id);
        assertThat(v).matches("pbkdf2-sha256\\$10000\\$[A-Za-z0-9_-]{22}\\$[A-Za-z0-9_-]{43}");
        assertThat(Pins.matches(pin, v)).isTrue();
        assertThat(Pins.matches(nextPin(pin), v)).isFalse();
        assertThat(jdbc.sql("select organizer_id from staff where id = ?::uuid").param(id).query(UUID.class).single())
                .isEqualTo(a.org());
        assertThat(jdbc.sql("select user_id from staff where id = ?::uuid").param(id).query(UUID.class).optional())
                .isEmpty();

        String sup = createStaff(a, "Salma", "SUPERVISOR");
        assertThat(JsonPath.<String>read(sup, "$.role")).isEqualTo("SUPERVISOR");
        // same PIN or not, every row gets its own salt
        assertThat(verifier(JsonPath.read(sup, "$.id"))).isNotEqualTo(v);
    }

    @Test
    void listShowsEveryStaffRowByNameWithoutPinOrVerifier() throws Exception {
        Caller a = admin(); // a back-office ADMIN row named "Test"
        String gate = createStaff(a, "Zineb Alaoui", "GATE");
        createStaff(a, "Amine Tazi", "SUPERVISOR");
        String pin = JsonPath.read(gate, "$.pin");

        String list = body(call(a, get("/org/staff")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(list, "$[*].name")).containsExactly("Amine Tazi", "Test", "Zineb Alaoui");
        assertThat(JsonPath.<List<Boolean>>read(list, "$[*].has_pin")).containsExactly(true, false, true);
        assertThat(JsonPath.<List<String>>read(list, "$[*].role")).containsExactly("SUPERVISOR", "ADMIN", "GATE");
        for (int i = 0; i < 3; i++) {
            assertKeys(list, "$[" + i + "]", "id", "name", "role", "active", "has_pin");
        }
        assertThat(list).doesNotContain("pbkdf2").doesNotContain(verifier(JsonPath.read(gate, "$.id")))
                .doesNotContain("\"" + pin + "\"");

        // another organizer sees none of these
        Caller b = admin();
        assertThat(JsonPath.<List<String>>read(body(call(b, get("/org/staff"))), "$[*].name")).containsExactly("Test");
    }

    @Test
    void createValidatesNameAndRole() throws Exception {
        Caller a = admin();
        for (String bad : List.of("{\"name\": \"Y\", \"role\": \"GATE\"}", "{\"name\": \"Yazid 2\", \"role\": \"GATE\"}",
                "{\"role\": \"GATE\"}")) {
            call(a, post("/org/staff"), bad).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.fields.name").exists());
        }
        for (String role : List.of("ADMIN", "OPS", "FINANCE")) {
            call(a, post("/org/staff"), "{\"name\": \"Yazid\", \"role\": \"%s\"}".formatted(role))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.fields.role").exists());
        }
        call(a, post("/org/staff"), "{\"name\": \"Yazid\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.role").exists());
        call(a, post("/org/staff"), "{\"name\": \"Yazid\", \"role\": \"BOSS\"}").andExpect(status().isUnprocessableContent());
        assertThat(JsonPath.<List<String>>read(body(call(a, get("/org/staff"))), "$[*].name")).containsExactly("Test");
    }

    @Test
    void patchRenamesAndDeactivatesGateStaffOnly() throws Exception {
        Caller a = admin();
        String id = JsonPath.read(createStaff(a, "Yazid Aqel", "GATE"), "$.id");
        String v = verifier(id);

        String json = body(call(a, patch("/org/staff/" + id), "{\"name\": \"Yazid  Benani\", \"active\": false}")
                .andExpect(status().isOk()));
        assertKeys(json, "$", "id", "name", "role", "active", "has_pin");
        assertThat(JsonPath.<String>read(json, "$.name")).isEqualTo("Yazid Benani");
        assertThat(JsonPath.<Boolean>read(json, "$.active")).isFalse();
        assertThat(JsonPath.<Boolean>read(json, "$.has_pin")).isTrue();
        assertThat(verifier(id)).isEqualTo(v);

        // partial: {} changes nothing; active alone
        call(a, patch("/org/staff/" + id), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Yazid Benani")).andExpect(jsonPath("$.active").value(false));
        call(a, patch("/org/staff/" + id), "{\"active\": true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Yazid Benani")).andExpect(jsonPath("$.active").value(true));
        call(a, patch("/org/staff/" + id), "{\"name\": \"\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.name").exists());

        // a back-office row is not patchable here
        UUID ops = staff(a.org(), randomPhone(), "OPS", true);
        UUID opsStaffId = jdbc.sql("select id from staff where user_id = ?").param(ops).query(UUID.class).single();
        call(a, patch("/org/staff/" + opsStaffId), "{\"active\": false}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        call(a, post("/org/staff/" + opsStaffId + "/pin")).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select active from staff where id = ?").param(opsStaffId).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void resetPinGivesANewPinAndVerifierAndIsAudited() throws Exception {
        Caller a = admin();
        String created = createStaff(a, "Yazid Aqel", "SUPERVISOR");
        String id = JsonPath.read(created, "$.id");
        String oldVerifier = verifier(id);

        String json = body(call(a, post("/org/staff/" + id + "/pin")).andExpect(status().isOk()));
        assertKeys(json, "$", "pin");
        String pin = JsonPath.read(json, "$.pin");
        assertThat(pin).matches("[0-9]{4}");
        String v = verifier(id);
        assertThat(v).isNotEqualTo(oldVerifier);
        assertThat(Pins.matches(pin, v)).isTrue();
        assertThat(Pins.matches(nextPin(pin), v)).isFalse();

        var audit = jdbc.sql("select action, target, coalesce(payload::text, '') from audit_log where organizer_id = ? "
                        + "and action like 'staff.%'")
                .param(a.org()).query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        assertThat(audit).containsExactly(List.of("staff.pin_reset", id, ""));
    }

    @Test
    void anotherOrganizersStaffIs404() throws Exception {
        Caller a = admin();
        Caller b = admin();
        String id = JsonPath.read(createStaff(a, "Yazid Aqel", "GATE"), "$.id");
        String v = verifier(id);
        call(b, patch("/org/staff/" + id), "{\"active\": false}").andExpect(status().isNotFound());
        call(b, post("/org/staff/" + id + "/pin")).andExpect(status().isNotFound());
        call(a, patch("/org/staff/" + UUID.randomUUID()), "{\"active\": false}").andExpect(status().isNotFound());
        assertThat(verifier(id)).isEqualTo(v);
        assertThat(jdbc.sql("select active from staff where id = ?::uuid").param(id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void onlyAdminAndOpsManageStaff() throws Exception {
        Caller a = admin();
        String id = JsonPath.read(createStaff(a, "Yazid Aqel", "GATE"), "$.id");
        Caller ops = caller(a.org(), "OPS");
        createStaff(ops, "Salma Idrissi", "GATE");
        call(ops, get("/org/staff")).andExpect(status().isOk());
        call(ops, post("/org/staff/" + id + "/pin")).andExpect(status().isOk());

        for (String role : List.of("FINANCE", "GATE", "SUPERVISOR")) {
            Caller c = caller(a.org(), role);
            call(c, get("/org/staff")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            call(c, post("/org/staff"), "{\"name\": \"Yazid\", \"role\": \"GATE\"}").andExpect(status().isForbidden());
            call(c, patch("/org/staff/" + id), "{\"active\": false}").andExpect(status().isForbidden());
            call(c, post("/org/staff/" + id + "/pin")).andExpect(status().isForbidden());
        }
    }
}
