package ma.jomhor.tenancy.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.common.Secrets;
import ma.jomhor.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phone OTP: 6 digits, stored as HMAC-SHA256(OTP_SECRET, code), valid 5 min, 5 attempts, single use.
 * Counted per audience (fan | org, plan P7): a code verifies only on its own audience and org requests
 * never use up a fan's limits. Limits, the same for both audiences (04 §Fan /fan/auth/otp and §Organizer,
 * plan Q14): per phone 3 sends / 10 min and 10 / 24 h, locked once its codes of the last 24 h hold 15 failed
 * attempts; per IP (config knobs, per audience). Fans see 429; org stays silent (202, OTP_INVALID).
 * <p>
 * Dev only: {@code jomhor.dev.fixed-otp} (6 digits) is accepted for any phone and audience, and lifts the send limits,
 * so testers who can't read SMS can log in. Codes are still issued and logged. Set under any profile set other than
 * exactly {@code dev}, startup fails.
 */
@Service
public class OtpService {

    static final int MAX_SENDS_10M = 3;
    static final int MAX_SENDS_24H = 10;
    static final int MAX_FAILURES_24H = 15;
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;

    private final OtpChallenges challenges;
    private final ApplicationEventPublisher events;
    private final SecretKeySpec key;
    private final int fanIp10m;
    private final int fanIp24h;
    private final int orgIp10m;
    private final int orgIp24h;
    private final byte[] fixedOtp; // null unless dev
    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpChallenges challenges, ApplicationEventPublisher events,
                      @Value("${jomhor.otp-secret}") String secret,
                      @Value("${otp.per-ip.fan.sends-10m}") int fanIp10m,
                      @Value("${otp.per-ip.fan.sends-24h}") int fanIp24h,
                      @Value("${otp.per-ip.org.sends-10m}") int orgIp10m,
                      @Value("${otp.per-ip.org.sends-24h}") int orgIp24h,
                      @Value("${jomhor.dev.fixed-otp:}") String fixedOtp, Environment env) {
        this.challenges = challenges;
        this.events = events;
        this.key = new SecretKeySpec(Secrets.atLeast32Bytes("OTP_SECRET", secret), "HmacSHA256");
        this.fanIp10m = fanIp10m;
        this.fanIp24h = fanIp24h;
        this.orgIp10m = orgIp10m;
        this.orgIp24h = orgIp24h;
        this.fixedOtp = fixedOtp(fixedOtp, env);
    }

    static byte[] fixedOtp(String code, Environment env) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        if (!Arrays.equals(env.getActiveProfiles(), new String[] {"dev"})) {
            throw new IllegalStateException("jomhor.dev.fixed-otp is allowed under the dev profile only");
        }
        if (!code.matches("[0-9]{6}")) {
            throw new IllegalStateException("jomhor.dev.fixed-otp must be 6 digits");
        }
        return code.getBytes(StandardCharsets.UTF_8);
    }

    /** A code to send once the issuing transaction has committed ({@link OtpDelivery}). */
    public record OtpIssued(String phone, String code) {
        @Override
        public String toString() {
            return "OtpIssued[code hidden]";
        }
    }

    /**
     * Creates a challenge from {@code ip} and, if {@code deliver}, sends its code after commit, off this thread.
     * False, and nothing created or sent, over any limit. /org/auth/otp runs this for every phone and delivers
     * only to active staff, so the DB work and the limits do not reveal who is staff.
     */
    @Transactional
    public boolean issue(String phone, String audience, String ip, boolean deliver) {
        challenges.lock(phone, ip);
        if (fixedOtp == null && overLimit(phone, audience, ip)) {
            return false;
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        challenges.insert(phone, audience, ip, HexFormat.of().formatHex(hmac(code)), CODE_TTL);
        if (deliver) {
            events.publishEvent(new OtpIssued(phone, code));
        }
        return true;
    }

    private boolean overLimit(String phone, String audience, String ip) {
        boolean fan = AuthService.FAN.equals(audience);
        var byPhone = challenges.phoneUsage(phone, audience);
        if (byPhone.sends10m() >= MAX_SENDS_10M || byPhone.sends24h() >= MAX_SENDS_24H
                || byPhone.failures24h() >= MAX_FAILURES_24H) {
            return true;
        }
        var byIp = challenges.ipUsage(ip, audience);
        return byIp.sends10m() >= (fan ? fanIp10m : orgIp10m) || byIp.sends24h() >= (fan ? fanIp24h : orgIp24h);
    }

    /**
     * Checks the latest challenge of this phone and audience. Throws OTP_INVALID or OTP_EXPIRED (a wrong code
     * still counts). A locked phone fails before the code is looked at: OTP_RATE_LIMITED for a fan, OTP_INVALID
     * (the same as a wrong code) for org, which never reveals a limit.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void verify(String phone, String audience, String code) {
        if (fixedOtp != null && code != null && MessageDigest.isEqual(fixedOtp, code.getBytes(StandardCharsets.UTF_8))) {
            return; // dev only (constructor)
        }
        var c = challenges.latestForUpdate(phone, audience).orElseThrow(OtpService::invalid);
        // Counted under the row lock: parallel guesses on the live code queue up here, so they cannot overshoot 15.
        // ponytail: the 24 h window runs from each challenge's created_at, not from each failure (failures land
        // within the code's 5 min life), so the lock lifts up to 5 min early. A failures table if that matters.
        if (challenges.phoneUsage(phone, audience).failures24h() >= MAX_FAILURES_24H) {
            throw AuthService.FAN.equals(audience) ? rateLimited() : invalid();
        }
        if (c.consumed() || c.attempts() >= MAX_ATTEMPTS) {
            throw invalid();
        }
        if (c.expired()) {
            throw new ApiException("OTP_EXPIRED", HttpStatus.UNAUTHORIZED, "Code expired");
        }
        if (!MessageDigest.isEqual(hmac(code), HexFormat.of().parseHex(c.codeHash()))) {
            challenges.incrementAttempts(c.id());
            throw invalid();
        }
        challenges.consume(c.id());
    }

    static ApiException invalid() {
        return new ApiException("OTP_INVALID", HttpStatus.UNAUTHORIZED, "Invalid code");
    }

    static ApiException rateLimited() {
        return new ApiException("OTP_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many codes requested");
    }

    private byte[] hmac(String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
