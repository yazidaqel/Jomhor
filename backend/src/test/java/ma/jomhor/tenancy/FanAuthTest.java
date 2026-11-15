package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import ma.jomhor.IntegrationTest;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.application.OtpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Contract: POST /fan/auth/otp, POST /fan/auth/verify (docs/04-api.md §Fan). */
class FanAuthTest extends IntegrationTest {

    @Autowired
    OtpService otpService;

    @Autowired
    TransactionTemplate tx;

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

    /** Lot 5 (04 /fan/auth/verify): the first fan verify sets users.verified_at, later ones keep it. */
    @Test
    void firstFanVerifySetsVerifiedAtAndALaterOneKeepsIt() throws Exception {
        String phone = randomPhone();
        // A holder typed by a buyer: the user exists but never logged in.
        jdbc.sql("insert into users (phone_e164) values (?)").param(phone).update();
        assertThat(verifiedAt(phone)).isNull();

        login(phone);
        assertThat(verifiedAt(phone)).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));

        jdbc.sql("update users set verified_at = '2020-01-01T00:00:00Z' where phone_e164 = ?").param(phone).update();
        login(phone);
        assertThat(verifiedAt(phone)).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void aStaffVerifyDoesNotSetVerifiedAt() throws Exception {
        String phone = randomPhone();
        staff(organizer("Org " + UUID.randomUUID()), phone, "ADMIN", true);
        requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
        verifyOtp("/org/auth/verify", phone, lastCode(phone)).andExpect(status().isOk());
        assertThat(verifiedAt(phone)).isNull();
    }

    private Instant verifiedAt(String phone) {
        return jdbc.sql("select verified_at from users where phone_e164 = ?").param(phone)
                .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
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
        awaitSends();
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
            otpService.verify(phone, "fan", code);
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

    @Test
    void eleventhSendIn24HoursIs429() throws Exception {
        String phone = randomPhone();
        for (int i = 0; i < 10; i++) {
            requestOtp("/fan/auth/otp", phone).andExpect(status().isAccepted());
            age(phone, Duration.ofMinutes(11)); // out of the 10 min window, still inside 24 h
        }
        rateLimited(requestOtp("/fan/auth/otp", phone));
        age(phone, Duration.ofHours(23));
        requestOtp("/fan/auth/otp", phone).andExpect(status().isAccepted());
    }

    @Test
    void afterFifteenFailuresEvenTheRightCodeIs429UntilTheOldestFailureIs24HoursOld() throws Exception {
        String phone = randomPhone();
        for (int failures : new int[] {5, 5, 4}) {
            requestOtp("/fan/auth/otp", phone).andExpect(status().isAccepted());
            String wrong = wrong(lastCode(phone));
            for (int i = 0; i < failures; i++) {
                verifyOtp("/fan/auth/verify", phone, wrong).andExpect(status().isUnauthorized());
            }
            age(phone, Duration.ofMinutes(11));
        }
        requestOtp("/fan/auth/otp", phone).andExpect(status().isAccepted()); // 14 failures: not locked yet
        String code = lastCode(phone);
        verifyOtp("/fan/auth/verify", phone, wrong(code)).andExpect(status().isUnauthorized()); // the 15th

        rateLimited(verifyOtp("/fan/auth/verify", phone, code));
        rateLimited(requestOtp("/fan/auth/otp", phone));

        // The oldest challenge (5 failures) leaves the 24 h window: 10 left, unlocked, the live code works.
        jdbc.sql("""
                update otp_challenges set created_at = now() - interval '25 hours'
                where id = (select id from otp_challenges where phone_e164 = ? order by created_at limit 1)
                """).param(phone).update();
        verifyOtp("/fan/auth/verify", phone, code).andExpect(status().isOk());
    }

    @Test
    void parallelWrongGuessesAtFourteenFailuresStopAtFifteen() throws Exception {
        String phone = randomPhone();
        for (int attempts : new int[] {5, 5, 4}) {
            jdbc.sql("""
                    insert into otp_challenges (phone_e164, audience, code_hash, attempts, expires_at, created_at)
                    values (?, 'fan', ?, ?, now() - interval '55 minutes', now() - interval '1 hour')
                    """).params(phone, "00".repeat(32), attempts).update();
        }
        requestOtp("/fan/auth/otp", phone).andExpect(status().isAccepted());
        String wrong = wrong(lastCode(phone));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var guesses = List.of(pool.submit(() -> guess(start, phone, wrong)), pool.submit(() -> guess(start, phone, wrong)),
                    pool.submit(() -> guess(start, phone, wrong)), pool.submit(() -> guess(start, phone, wrong)));
            start.countDown();
            var codes = new ArrayList<String>();
            for (var g : guesses) {
                codes.add(g.get());
            }
            assertThat(codes).containsExactlyInAnyOrder("OTP_INVALID", "OTP_RATE_LIMITED", "OTP_RATE_LIMITED",
                    "OTP_RATE_LIMITED");
        }
        assertThat(jdbc.sql("select sum(attempts) from otp_challenges where phone_e164 = ?").param(phone)
                .query(Integer.class).single()).isEqualTo(15);
    }

    @Test
    void aRolledBackIssueSendsNothing() {
        String phone = randomPhone();
        tx.executeWithoutResult(t -> {
            assertThat(otpService.issue(phone, "fan", "192.0.2.10", true)).isTrue();
            t.setRollbackOnly();
        });
        awaitSends();
        verify(otpSender, never()).send(eq(phone), anyString());
        assertThat(jdbc.sql("select count(*) from otp_challenges where phone_e164 = ?").param(phone)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void theCodeIsSentAfterTheResponseOffTheRequestThread() throws Exception {
        String phone = randomPhone();
        var sendThread = new AtomicReference<Thread>();
        doAnswer(inv -> {
            sendThread.set(Thread.currentThread());
            return null;
        }).when(otpSender).send(eq(phone), anyString());
        requestOtp("/fan/auth/otp", phone).andExpect(status().isAccepted()); // MockMvc runs on this thread
        awaitSends();
        assertThat(sendThread.get()).isNotNull().isNotSameAs(Thread.currentThread());
        assertThat(sendThread.get().getName()).startsWith("otp-send-");
    }

    private void age(String phone, Duration by) {
        jdbc.sql("update otp_challenges set created_at = created_at - ? * interval '1 second' where phone_e164 = ?")
                .params(by.toSeconds(), phone).update();
    }

    private static String wrong(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }

    private static void rateLimited(ResultActions result) throws Exception {
        result.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("OTP_RATE_LIMITED"));
    }

    private ResultActions login(String phone) throws Exception {
        requestOtp("/fan/auth/otp", phone);
        return verifyOtp("/fan/auth/verify", phone, lastCode(phone)).andExpect(status().isOk());
    }

    private static String userId(ResultActions login) throws Exception {
        return JsonPath.read(login.andReturn().getResponse().getContentAsString(), "$.user.id");
    }
}
