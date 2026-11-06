package ma.jomhor.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Security filters off: this tests the advice alone; SecurityTest covers 401/403 bodies.
@WebMvcTest(controllers = ApiExceptionHandlerTest.TestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandlerTest.TestController.class)
class ApiExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void beanValidationGives422WithFields() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"price_mad\": -1}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.details.fields.display_name").isString())
                .andExpect(jsonPath("$.details.fields.price_mad").isString());
    }

    @Test
    void malformedBodyGives422() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void apiExceptionKeepsCodeStatusAndDetails() throws Exception {
        mvc.perform(get("/test/sold-out"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.message").value("Plus de places"))
                .andExpect(jsonPath("$.details.price_category_id").value("pc-1"));
    }

    @Test
    void unknownRouteGives404() throws Exception {
        mvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void unexpectedExceptionGives500WithoutLeakingIt() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.details").isMap())
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void wrongTypePathVariableGives400() throws Exception {
        mvc.perform(get("/test/events/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void frameworkRejectionKeepsItsStatus() throws Exception {
        mvc.perform(post("/test/sold-out"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @RestController
    static class TestController {

        record Body(@NotBlank String displayName, @Min(0) long priceMad) {}

        @PostMapping("/test/body")
        void body(@Valid @RequestBody Body body) {}

        @GetMapping("/test/boom")
        void boom() {
            throw new RuntimeException("secret");
        }

        @GetMapping("/test/events/{id}")
        void event(@PathVariable int id) {}

        @GetMapping("/test/sold-out")
        void soldOut() {
            throw new ApiException("SOLD_OUT", HttpStatus.CONFLICT, "Plus de places",
                    Map.of("price_category_id", "pc-1"));
        }
    }
}
