package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.ResultActions;

/** Contract: fan sessions (`sid`, plan Q13) and POST /fan/me/logout (docs/04-api.md §Fan). */
class FanSessionTest extends IntegrationTest {

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void verifyCreatesASessionThatMatchesTheToken() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        String claims = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        String sid = JsonPath.read(claims, "$.sid");
        long exp = ((Number) JsonPath.read(claims, "$.exp")).longValue();

        var row = jdbc.sql("""
                        select s.expires_at, s.revoked_at is null as live, u.phone_e164
                        from fan_sessions s join users u on u.id = s.user_id where s.id = ?
                        """)
                .param(UUID.fromString(sid))
                .query((rs, n) -> List.of(rs.getTimestamp(1).toInstant(), rs.getBoolean(2), rs.getString(3)))
                .single();
        assertThat(row).containsExactly(Instant.ofEpochSecond(exp), true, phone);
    }

    @Test
    void aFanTokenWithoutAnActiveSessionOfItsSubjectIs401() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        me(token).andExpect(status().isOk());
        UUID userId = UUID.fromString(JsonPath.read(me(token).andReturn().getResponse().getContentAsString(), "$.id"));
        String otherSid = sid(fanToken(randomPhone()));

        unauthorized(me(mint(userId, null)));                          // no sid
        unauthorized(me(mint(userId, UUID.randomUUID().toString())));  // unknown sid
        unauthorized(me(mint(userId, "not-a-uuid")));
        unauthorized(me(mint(userId, otherSid)));                      // another user's live session

        jdbc.sql("update fan_sessions set expires_at = now() - interval '1 second' where id = ?")
                .param(UUID.fromString(sid(token))).update();
        unauthorized(me(token));                                       // expired session
    }

    @Test
    void logoutRevokesThisSessionOnly() throws Exception {
        String phone = randomPhone();
        String first = fanToken(phone);
        String second = fanToken(phone);
        mvc.perform(post("/fan/me/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        unauthorized(me(first));
        me(second).andExpect(status().isOk());
    }

    @Test
    void logoutAllRevokesEverySessionOfTheUser() throws Exception {
        String phone = randomPhone();
        String first = fanToken(phone);
        String second = fanToken(phone);
        String someoneElse = fanToken(randomPhone());
        mvc.perform(post("/fan/me/logout").header("Authorization", "Bearer " + first)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"all\": true}"))
                .andExpect(status().isNoContent());
        unauthorized(me(first));
        unauthorized(me(second));
        me(someoneElse).andExpect(status().isOk());
    }

    @Test
    void logoutWithoutATokenIs401() throws Exception {
        unauthorized(mvc.perform(post("/fan/me/logout")));
    }

    @Test
    void aRevokedTokenDoesNotBlockLoginOrPublicReads() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        mvc.perform(post("/fan/me/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());

        mvc.perform(post("/fan/auth/otp").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\": \"%s\"}".formatted(phone)))
                .andExpect(status().isAccepted());
        mvc.perform(post("/fan/auth/verify").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\": \"%s\", \"code\": \"%s\"}".formatted(phone, lastCode(phone))))
                .andExpect(status().isOk());
        mvc.perform(get("/fan/events").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/fan/me").header("Authorization", "Bearer " + token));
    }

    private String mint(UUID userId, String sid) {
        var claims = JwtClaimsSet.builder().subject(userId.toString()).audience(List.of("fan"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600));
        if (sid != null) {
            claims.claim("sid", sid);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private static String sid(String token) {
        return JsonPath.read(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8),
                "$.sid");
    }

    private static void unauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
