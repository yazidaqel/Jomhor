package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Contract: GET, PATCH /fan/me (docs/04-api.md §Fan; display_name per §Holder names). */
class FanMeApiTest extends IntegrationTest {

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/fan/me").header("Authorization", "Bearer " + token));
    }

    private ResultActions patchMe(String token, String body) throws Exception {
        return mvc.perform(patch("/fan/me").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void assertMeKeys(ResultActions r) throws Exception {
        Map<String, Object> json = JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$");
        assertThat(json.keySet()).containsExactlyInAnyOrder("id", "phone", "display_name", "locale");
    }

    @Test
    void getReturnsTheCallersProfile() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        UUID id = jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
        ResultActions r = me(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.display_name").isEmpty())
                .andExpect(jsonPath("$.locale").value("fr"));
        assertMeKeys(r);
    }

    @Test
    void patchIsPartialAndNormalizesTheName() throws Exception {
        String token = fanToken(randomPhone());
        assertMeKeys(patchMe(token, "{\"display_name\": \"  Yazid   Aqel \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("Yazid Aqel"))
                .andExpect(jsonPath("$.locale").value("fr")));
        patchMe(token, "{\"locale\": \"ar\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("Yazid Aqel"))
                .andExpect(jsonPath("$.locale").value("ar"));
        patchMe(token, "{\"display_name\": null, \"locale\": null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("Yazid Aqel"))
                .andExpect(jsonPath("$.locale").value("ar"));
        patchMe(token, "{\"display_name\": \"Fe\u0301lix\", \"locale\": \"en\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("Félix"))
                .andExpect(jsonPath("$.locale").value("en"));
        me(token).andExpect(jsonPath("$.display_name").value("Félix")).andExpect(jsonPath("$.locale").value("en"));
        // "" clears an optional text field (04 conventions).
        patchMe(token, "{\"display_name\": \"\"}").andExpect(status().isOk()).andExpect(jsonPath("$.display_name").isEmpty());
    }

    @Test
    void badValuesAre422AndChangeNothing() throws Exception {
        String token = fanToken(randomPhone());
        patchMe(token, "{\"display_name\": \"Yazid Aqel\"}").andExpect(status().isOk());
        for (String bad : List.of("يزيد", "Y", "Yazid 3", "   ")) {
            patchMe(token, "{\"display_name\": \"%s\", \"locale\": \"ar\"}".formatted(bad))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.fields.display_name").isString());
        }
        for (String bad : List.of("de", "FR", "")) {
            patchMe(token, "{\"locale\": \"%s\"}".formatted(bad))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.fields.locale").isString());
        }
        me(token).andExpect(jsonPath("$.display_name").value("Yazid Aqel")).andExpect(jsonPath("$.locale").value("fr"));
    }

    @Test
    void fanTokenRequired() throws Exception {
        mvc.perform(get("/fan/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        String phone = randomPhone();
        staff(organizer("Me FC"), phone, "ADMIN", true);
        String org = orgToken(phone);
        me(org).andExpect(status().isUnauthorized());
        patchMe(org, "{\"locale\": \"en\"}").andExpect(status().isUnauthorized());
    }
}
