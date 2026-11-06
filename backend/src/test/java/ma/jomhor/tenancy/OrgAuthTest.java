package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import org.junit.jupiter.api.Test;

/** Contract: POST /org/auth/otp, POST /org/auth/verify, GET /org/me (docs/04-api.md §Organizer). */
class OrgAuthTest extends IntegrationTest {

    @Test
    void otpForAPhoneWithoutActiveStaffIs202AndSendsNothing() throws Exception {
        String unknown = randomPhone();
        String inactive = randomPhone();
        staff(organizer("Inactive FC"), inactive, "ADMIN", false);

        requestOtp("/org/auth/otp", unknown).andExpect(status().isAccepted()).andExpect(content().string(""));
        requestOtp("/org/auth/otp", inactive).andExpect(status().isAccepted()).andExpect(content().string(""));

        verifyNoInteractions(otpSender);
        // Same DB path as for staff (no enumeration through the shared rate limit); the code just goes nowhere.
        assertThat(challenges(unknown)).isEqualTo(1);
        assertThat(challenges(inactive)).isEqualTo(1);
    }

    @Test
    void otpForActiveStaffSendsACode() throws Exception {
        String phone = randomPhone();
        staff(organizer("Atlas"), phone, "OPS", true);
        requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
        assertThat(lastCode(phone)).matches("[0-9]{6}");
    }

    @Test
    void fourthOtpWithinTenMinutesIsStill202AndNotSent() throws Exception {
        String phone = randomPhone();
        staff(organizer("Atlas"), phone, "OPS", true);
        for (int i = 0; i < 4; i++) {
            requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
        }
        verify(otpSender, times(3)).send(eq(phone), anyString());
        assertThat(challenges(phone)).isEqualTo(3);
    }

    @Test
    void sharedRateLimitDoesNotRevealStaff() throws Exception {
        String staffPhone = randomPhone();
        staff(organizer("Atlas"), staffPhone, "ADMIN", true);
        String otherPhone = randomPhone();
        for (String phone : List.of(staffPhone, otherPhone)) {
            for (int i = 0; i < 3; i++) {
                requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
            }
        }
        int staffStatus = requestOtp("/fan/auth/otp", staffPhone).andReturn().getResponse().getStatus();
        int otherStatus = requestOtp("/fan/auth/otp", otherPhone).andReturn().getResponse().getStatus();
        assertThat(staffStatus).isEqualTo(otherStatus).isEqualTo(429);
    }

    @Test
    void expiredChallengeGivesTheSameErrorForStaffAndNonStaff() throws Exception {
        String staffPhone = randomPhone();
        staff(organizer("Atlas"), staffPhone, "ADMIN", true);
        String otherPhone = randomPhone();
        for (String phone : List.of(staffPhone, otherPhone)) {
            requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
            jdbc.sql("update otp_challenges set expires_at = now() - interval '1 second' where phone_e164 = ?")
                    .param(phone).update();
            verifyOtp("/org/auth/verify", phone, "123456")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        }
    }

    @Test
    void verifyForAPhoneWhoseStaffRowsAreAllInactiveIsOtpInvalid() throws Exception {
        String phone = randomPhone();
        staff(organizer("Old club"), phone, "ADMIN", false);
        requestOtp("/fan/auth/otp", phone); // a real, valid code for this phone
        verifyOtp("/org/auth/verify", phone, lastCode(phone))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    private int challenges(String phone) {
        return jdbc.sql("select count(*) from otp_challenges where phone_e164 = ?").param(phone).query(Integer.class).single();
    }

    @Test
    void verifyReturnsTokenAndMemberships() throws Exception {
        String phone = randomPhone();
        UUID a = organizer("Org A");
        UUID b = organizer("Org B");
        staff(a, phone, "ADMIN", true);
        staff(b, phone, "FINANCE", true);
        staff(organizer("Org C"), phone, "OPS", false);

        requestOtp("/org/auth/otp", phone);
        String body = verifyOtp("/org/auth/verify", phone, lastCode(phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expires_at").isString())
                .andExpect(jsonPath("$.memberships.length()").value(2))
                .andExpect(jsonPath("$.memberships[*].organizer_id").value(containsInAnyOrder(a.toString(), b.toString())))
                .andExpect(jsonPath("$.memberships[*].organizer_name").value(containsInAnyOrder("Org A", "Org B")))
                .andExpect(jsonPath("$.memberships[*].role").value(containsInAnyOrder("ADMIN", "FINANCE")))
                .andReturn().getResponse().getContentAsString();
        Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expires_at"));
        assertThat(expiresAt).isCloseTo(Instant.now().plus(Duration.ofHours(12)), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void validCodeForAPhoneWithoutStaffIsOtpInvalidAndCreatesNothing() throws Exception {
        String phone = randomPhone();
        requestOtp("/fan/auth/otp", phone); // a real, valid code for this phone
        verifyOtp("/org/auth/verify", phone, lastCode(phone))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
        assertThat(jdbc.sql("select count(*) from users where phone_e164 = ?").param(phone).query(Integer.class).single())
                .isZero();
    }

    @Test
    void meReturnsUserAndMembershipsWithoutOrganizerHeader() throws Exception {
        String phone = randomPhone();
        UUID a = organizer("Org Me");
        UUID userId = staff(a, phone, "OPS", true);

        mvc.perform(get("/org/me").header("Authorization", "Bearer " + orgToken(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.user.phone").value(phone))
                .andExpect(jsonPath("$.user.locale").value("fr"))
                .andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].organizer_id").value(a.toString()))
                .andExpect(jsonPath("$.memberships[0].organizer_name").value("Org Me"))
                .andExpect(jsonPath("$.memberships[0].role").value("OPS"));
    }
}
