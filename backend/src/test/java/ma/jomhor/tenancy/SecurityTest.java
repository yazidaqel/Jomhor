package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import ma.jomhor.common.Secrets;
import ma.jomhor.tenancy.domain.OrgContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Audience per prefix, 401/403 in the 04 shape, X-Organizer-Id tenant check and Lot 0 roles. */
@Import(SecurityTest.Probe.class)
class SecurityTest extends IntegrationTest {

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void noTokenIs401() throws Exception {
        unauthorized(mvc.perform(get("/org/me")));
        unauthorized(mvc.perform(get("/fan/probe")));
    }

    @Test
    void fanTokenOnOrgIs401() throws Exception {
        unauthorized(mvc.perform(get("/org/me").header("Authorization", "Bearer " + fanToken(randomPhone()))));
    }

    @Test
    void orgTokenOnFanIs401() throws Exception {
        String phone = randomPhone();
        staff(organizer("Org"), phone, "ADMIN", true);
        String token = orgToken(phone);
        mvc.perform(get("/org/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        unauthorized(mvc.perform(get("/fan/probe").header("Authorization", "Bearer " + token)));
    }

    @Test
    void expiredTokenIs401() throws Exception {
        var claims = JwtClaimsSet.builder().subject(UUID.randomUUID().toString()).audience(List.of("fan"))
                .issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        unauthorized(mvc.perform(get("/fan/probe").header("Authorization", "Bearer " + token)));
    }

    @Test
    void tamperedTokenIs401() throws Exception {
        String token = fanToken(randomPhone());
        mvc.perform(get("/fan/probe").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        int i = token.lastIndexOf('.') + 5;
        String tampered = token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);
        unauthorized(mvc.perform(get("/fan/probe").header("Authorization", "Bearer " + tampered)));
    }

    @Test
    void staffOfAnotherOrganizerIs403() throws Exception {
        String phone = randomPhone();
        UUID a = organizer("Org A");
        UUID b = organizer("Org B");
        UUID userId = staff(a, phone, "ADMIN", true);
        String token = orgToken(phone);

        forbidden(mvc.perform(asOrg(get("/org/probe"), token, b)));
        mvc.perform(asOrg(get("/org/probe"), token, a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizer_id").value(a.toString()))
                .andExpect(jsonPath("$.user_id").value(userId.toString()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void inactiveStaffIs403() throws Exception {
        String phone = randomPhone();
        UUID a = organizer("Org A");
        staff(a, phone, "ADMIN", true);
        UUID b = organizer("Org B");
        staff(b, phone, "ADMIN", false);
        forbidden(mvc.perform(asOrg(get("/org/probe"), orgToken(phone), b)));
    }

    @Test
    void missingOrMalformedOrganizerHeaderIs400() throws Exception {
        String phone = randomPhone();
        staff(organizer("Org"), phone, "ADMIN", true);
        String token = orgToken(phone);
        mvc.perform(get("/org/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/org/probe").header("Authorization", "Bearer " + token).header("X-Organizer-Id", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void financeIsReadOnly() throws Exception {
        String phone = randomPhone();
        UUID org = organizer("Org");
        staff(org, phone, "FINANCE", true);
        String token = orgToken(phone);
        mvc.perform(asOrg(get("/org/probe"), token, org)).andExpect(status().isOk());
        forbidden(mvc.perform(asOrg(post("/org/probe"), token, org)));
    }

    @Test
    void opsCanWrite() throws Exception {
        String phone = randomPhone();
        UUID org = organizer("Org");
        staff(org, phone, "OPS", true);
        mvc.perform(asOrg(post("/org/probe"), orgToken(phone), org)).andExpect(status().isOk());
    }

    @Test
    void gateAndSupervisorAre403() throws Exception {
        for (String role : List.of("GATE", "SUPERVISOR")) {
            String phone = randomPhone();
            UUID org = organizer("Org");
            staff(org, phone, "ADMIN", true); // to get an org token at all
            UUID other = organizer("Org " + role);
            staff(other, phone, role, true);
            forbidden(mvc.perform(asOrg(get("/org/probe"), orgToken(phone), other)));
        }
    }

    @Test
    void firewallRejectionUsesTheErrorShape() throws Exception {
        mvc.perform(get("/fan/auth/otp;jsessionid=x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void secretsShorterThan32BytesAreRefused() {
        assertThatThrownBy(() -> Secrets.atLeast32Bytes("JWT_SECRET", "too-short"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Secrets.atLeast32Bytes("OTP_SECRET", null))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MockHttpServletRequestBuilder asOrg(MockHttpServletRequestBuilder request, String token, UUID org) {
        return request.header("Authorization", "Bearer " + token).header("X-Organizer-Id", org.toString());
    }

    private static void unauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.details").isMap());
    }

    private static void forbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.details").isMap());
    }

    /** Test-only endpoints standing in for the Lot 0 /fan and /org endpoints of later tasks. */
    @RestController
    static class Probe {

        @GetMapping("/fan/probe")
        Map<String, String> fan(@AuthenticationPrincipal Jwt jwt) {
            return Map.of("sub", jwt.getSubject());
        }

        @GetMapping("/org/probe")
        OrgContext read(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
            return org;
        }

        @PostMapping("/org/probe")
        OrgContext write(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
            return org;
        }
    }
}
