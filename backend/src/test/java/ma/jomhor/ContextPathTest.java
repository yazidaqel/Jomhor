package ma.jomhor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Real HTTP (MockMvc ignores the context path): the API lives under /v1 (docs/04-api.md base), and the
 * security chains and the X-Organizer-Id interceptor still match their paths under it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {IntegrationTest.JWT_SECRET, IntegrationTest.OTP_SECRET,
                IntegrationTest.EVENT_KEY_MASTER})
class ContextPathTest extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    private int status(String method, String path, String token, UUID org, String body) {
        var req = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) {
            req.header("Authorization", "Bearer " + token).header("X-Organizer-Id", org.toString());
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return req.exchange((rq, rs) -> rs.getStatusCode().value());
    }

    @Test
    void apiIsServedUnderV1Only() {
        String otp = "{\"phone\": \"%s\"}".formatted(randomPhone());
        assertThat(status("POST", "/v1/org/auth/otp", null, null, otp)).isEqualTo(202);
        assertThat(status("POST", "/org/auth/otp", null, null, otp)).isEqualTo(404);
    }

    @Test
    void orgChainAndOrganizerContextApplyUnderV1() throws Exception {
        String phone = randomPhone();
        UUID org = organizer("Context path FC");
        staff(org, phone, "ADMIN", true);
        String token = orgToken(phone); // MockMvc, path without the context

        // 200 needs the aud=org chain (else 401) and the interceptor's OrgContext (else 400).
        assertThat(status("GET", "/v1/org/venues", token, org, null)).isEqualTo(200);
        assertThat(status("GET", "/v1/org/venues", null, null, null)).isEqualTo(401);
    }
}
