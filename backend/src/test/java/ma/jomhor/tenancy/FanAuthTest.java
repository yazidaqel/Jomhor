package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import ma.jomhor.IntegrationTest;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.application.OtpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** Contract: POST /fan/auth/otp, POST /fan/auth/verify (docs/04-api.md §Fan). */
class FanAuthTest extends IntegrationTest {

    @Autowired
    OtpService otpService;

    @Test
    void otpReturns202AndSendsA6DigitCode() throws Exception {
        String phone = randomPhone();
        requestOtp("/fan/auth/otp", phone)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        assertThat(lastCode(phone)).matches("[0-9]{6}");
        assertThat(jdbc.sql("select code_hash from otp_challenges where phone_e164 = ?").param(phone)
                .query(String.class).single()).doesNotContain(lastCode(phone)).hasSize(64);
    }

    @Test
    void nonE164PhoneIs422() throws Exception {
        requestOtp("/fan/auth/otp", "0612345678")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.phone").isString());
    }

    @Test
    void verifyReturnsTokenAndUser() throws Exception {
        String phone = randomPhone();
        requestOtp("/fan/auth/otp", phone);
        String body = verifyOtp("/fan/auth/verify", phone, lastCode(phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expires_at").isString())
                .andExpect(jsonPath("$.user.id").isString())
                .andExpect(jsonPath("$.user.phone").value(phone))
                .andExpect(jsonPath("$.user", hasKey("display_name")))
                .andExpect(jsonPath("$.user.locale").value("fr"))
                .andReturn().getResponse().getContentAsString();
        Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expires_at"));
        assertThat(expiresAt).isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void firstLoginCreatesTheUserAndTheSecondReusesIt() throws Exception {
        String phone = randomPhone();
        String first = userId(login(phone));
        String second = userId(login(phone));
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("select count(*) from users where phone_e164 = ?").param(phone).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void fourthSendWithinTenMinutesIs429() throws Exception {
        String phone = randomPhone();
        for (int i = 0; i < 3; i++) {
            requestOtp("/fan/auth/otp", phone).andExpect(status().isAccepted());
        }
        requestOtp("/fan/auth/otp", phone)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OTP_RATE_LIMITED"))
                .andExpect(jsonPath("$.details").isMap());
        verify(otpSender, times(3)).send(eq(phone), anyString());
    }

    @Test
    void afterFiveWrongCodesEvenTheRightCodeFails() throws Exception {
        String phone = randomPhone();
        requestOtp("/fan/auth/otp", phone);
        String code = lastCode(phone);
        String wrong = "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
        for (int i = 0; i < 5; i++) {
            verifyOtp("/fan/auth/verify", phone, wrong).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("OTP_INVALID"));
        }
        verifyOtp("/fan/auth/verify", phone, code)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    @Test
    void parallelWrongGuessesAreAllCounted() throws Exception {
        String phone = randomPhone();
        requestOtp("/fan/auth/otp", phone);
        String wrong = "%06d".formatted((Integer.parseInt(lastCode(phone)) + 1) % 1_000_000);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var guesses = List.of(pool.submit(() -> guess(start, phone, wrong)), pool.submit(() -> guess(start, phone, wrong)));
            start.countDown();
            for (var g : guesses) {
                assertThat(g.get()).isEqualTo("OTP_INVALID");
            }
        }
        assertThat(jdbc.sql("select attempts from otp_challenges where phone_e164 = ?").param(phone)
                .query(Integer.class).single()).isEqualTo(2);
    }

    private String guess(CountDownLatch start, String phone, String code) throws InterruptedException {
        start.await();
        try {
            otpService.verify(phone, code);
            return "OK";
        } catch (ApiException e) {
            return e.code();
        }
    }

    @Test
    void aCodeWorksOnlyOnce() throws Exception {
        String phone = randomPhone();
        requestOtp("/fan/auth/otp", phone);
        String code = lastCode(phone);
        verifyOtp("/fan/auth/verify", phone, code).andExpect(status().isOk());
        verifyOtp("/fan/auth/verify", phone, code)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    @Test
    void expiredCodeIsOtpExpired() throws Exception {
        String phone = randomPhone();
        requestOtp("/fan/auth/otp", phone);
        jdbc.sql("update otp_challenges set expires_at = now() - interval '1 second' where phone_e164 = ?")
                .param(phone).update();
        verifyOtp("/fan/auth/verify", phone, lastCode(phone))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_EXPIRED"))
                .andExpect(jsonPath("$.message").isString());
    }

    private ResultActions login(String phone) throws Exception {
        requestOtp("/fan/auth/otp", phone);
        return verifyOtp("/fan/auth/verify", phone, lastCode(phone)).andExpect(status().isOk());
    }

    private static String userId(ResultActions login) throws Exception {
        return JsonPath.read(login.andReturn().getResponse().getContentAsString(), "$.user.id");
    }
}
