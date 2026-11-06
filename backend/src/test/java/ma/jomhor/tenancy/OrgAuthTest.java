package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Contract: POST /org/auth/otp, POST /org/auth/verify, GET /org/me (docs/04-api.md §Organizer). */
class OrgAuthTest extends IntegrationTest {

    @Test
    void otpForAPhoneWithoutActiveStaffIs202AndSendsNothing() throws Exception {
        String unknown = randomPhone();
        String inactive = randomPhone();
        staff(organizer("Inactive FC"), inactive, "ADMIN", false);

        requestOtp("/org/auth/otp", unknown).andExpect(status().isAccepted()).andExpect(content().string(""));
        requestOtp("/org/auth/otp", inactive).andExpect(status().isAccepted()).andExpect(content().string(""));

        awaitSends();
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
        awaitSends();
        verify(otpSender, times(3)).send(eq(phone), anyString());
        assertThat(challenges(phone)).isEqualTo(3);
    }

    @Test
    void rateLimitIsTheSameForStaffAndNonStaff() throws Exception {
        String staffPhone = randomPhone();
        staff(organizer("Atlas"), staffPhone, "ADMIN", true);
        String otherPhone = randomPhone();
        for (String phone : List.of(staffPhone, otherPhone)) {
            for (int i = 0; i < 4; i++) {
                requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
            }
            assertThat(challenges(phone)).isEqualTo(3);
        }
    }

    /** Plan P7: staff requests never use up a fan's limits (they used to share one per-phone count). */
    @Test
    void tenOrgOtpCallsForAFansPhoneLeaveFanOtpWorking() throws Exception {
        String phone = randomPhone();
        for (int i = 0; i < 10; i++) {
            requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
        }
        requestOtp("/fan/auth/otp", phone).andExpect(status().isAccepted());
        verifyOtp("/fan/auth/verify", phone, lastCode(phone)).andExpect(status().isOk());
    }

    @Test
    void aFanCodeDoesNotVerifyOnOrgNorAnOrgCodeOnFan() throws Exception {
        String phone = randomPhone();
        staff(organizer("Atlas"), phone, "ADMIN", true);
        requestOtp("/fan/auth/otp", phone);
        String fanCode = lastCode(phone);
        verifyOtp("/org/auth/verify", phone, fanCode)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));

        String other = randomPhone();
        staff(organizer("Atlas"), other, "ADMIN", true);
        requestOtp("/org/auth/otp", other);
        verifyOtp("/fan/auth/verify", other, lastCode(other))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
        // Each code still works on its own audience.
        verifyOtp("/fan/auth/verify", phone, fanCode).andExpect(status().isOk());
        verifyOtp("/org/auth/verify", other, lastCode(other)).andExpect(status().isOk());
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
        staff(organizer("Old club"), phone, "ADMIN", true);
        requestOtp("/org/auth/otp", phone); // a real, valid org code, sent while the row was active
        jdbc.sql("update staff set active = false where user_id = (select id from users where phone_e164 = ?)")
                .param(phone).update();
        verifyOtp("/org/auth/verify", phone, lastCode(phone))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    /** 04 §Organizer: the fan per-phone limits apply to staff too, silently, the same for any phone. */
    @Test
    void dailyCapIsSilentAndTheSameForStaffAndNonStaff() throws Exception {
        String staffPhone = randomPhone();
        staff(organizer("Atlas"), staffPhone, "ADMIN", true);
        String otherPhone = randomPhone();
        for (String phone : List.of(staffPhone, otherPhone)) {
            for (int i = 0; i < 11; i++) {
                requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted()).andExpect(content().string(""));
                age(phone, Duration.ofMinutes(11)); // out of the 10 min window, still inside 24 h
            }
            assertThat(challenges(phone)).isEqualTo(10);
        }
        awaitSends();
        verify(otpSender, times(10)).send(eq(staffPhone), anyString());
        verify(otpSender, never()).send(eq(otherPhone), anyString());
    }

    @Test
    void afterFifteenFailuresEvenTheRightCodeIsOtpInvalidForStaffAndNonStaff() throws Exception {
        String staffPhone = randomPhone();
        staff(organizer("Atlas"), staffPhone, "ADMIN", true);
        String otherPhone = randomPhone();
        for (String phone : List.of(staffPhone, otherPhone)) {
            for (int failures : new int[] {5, 5, 4}) {
                requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
                plantOrgCode(phone, "123456");
                for (int i = 0; i < failures; i++) {
                    invalid(verifyOtp("/org/auth/verify", phone, "654321"));
                }
                age(phone, Duration.ofMinutes(11));
            }
            requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted()); // 14 failures: not locked yet
            plantOrgCode(phone, "123456");
            invalid(verifyOtp("/org/auth/verify", phone, "654321")); // the 15th

            invalid(verifyOtp("/org/auth/verify", phone, "123456"));
            int before = challenges(phone);
            requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
            assertThat(challenges(phone)).isEqualTo(before); // locked: nothing created
        }
        // It was the lock: once the oldest failures leave the 24 h window, the staff's live code works.
        jdbc.sql("""
                update otp_challenges set created_at = now() - interval '25 hours'
                where id = (select id from otp_challenges where phone_e164 = ? order by created_at limit 1)
                """).param(staffPhone).update();
        verifyOtp("/org/auth/verify", staffPhone, "123456").andExpect(status().isOk());
    }

    private void plantOrgCode(String phone, String code) throws Exception {
        jdbc.sql("""
                update otp_challenges set code_hash = ?
                where id = (select id from otp_challenges where phone_e164 = ? and audience = 'org'
                            order by created_at desc limit 1)
                """).params(codeHash(code), phone).update();
    }

    private void age(String phone, Duration by) {
        jdbc.sql("update otp_challenges set created_at = created_at - ? * interval '1 second' where phone_e164 = ?")
                .params(by.toSeconds(), phone).update();
    }

    private static void invalid(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    private static String codeHash(String code) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(OTP_SECRET.substring(OTP_SECRET.indexOf('=') + 1).getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
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
        requestOtp("/org/auth/otp", phone); // nothing is sent: plant a known code on that org challenge
        jdbc.sql("update otp_challenges set code_hash = ? where phone_e164 = ? and audience = 'org'")
                .params(codeHash("123456"), phone).update();
        verifyOtp("/org/auth/verify", phone, "123456")
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
