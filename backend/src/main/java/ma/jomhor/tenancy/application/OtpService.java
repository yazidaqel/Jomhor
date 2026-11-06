package ma.jomhor.tenancy.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.common.Secrets;
import ma.jomhor.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Phone OTP: 6 digits, stored as HMAC-SHA256(OTP_SECRET, code), valid 5 min, 5 attempts, single use. */
@Service
public class OtpService {

    static final int MAX_SENDS = 3;
    static final Duration SEND_WINDOW = Duration.ofMinutes(10);
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;

    private final OtpChallenges challenges;
    private final OtpSender sender;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpChallenges challenges, OtpSender sender, @Value("${jomhor.otp-secret}") String secret) {
        this.challenges = challenges;
        this.sender = sender;
        this.key = new SecretKeySpec(Secrets.atLeast32Bytes("OTP_SECRET", secret), "HmacSHA256");
    }

    /**
     * Creates a challenge and, if {@code deliver}, sends its code. False, and nothing created, if the phone
     * already had 3 challenges in 10 min. /org/auth/otp runs this for every phone and delivers only to
     * active staff, so the DB work, the shared rate limit and the timing do not reveal who is staff.
     */
    @Transactional
    public boolean issue(String phone, boolean deliver) {
        if (challenges.lockAndCountSince(phone, SEND_WINDOW) >= MAX_SENDS) {
            return false;
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        challenges.insert(phone, HexFormat.of().formatHex(hmac(code)), CODE_TTL);
        if (deliver) {
            sender.send(phone, code);
        }
        return true;
    }

    /** Checks the phone's latest challenge. Throws OTP_INVALID or OTP_EXPIRED; a wrong code still counts. */
    @Transactional(noRollbackFor = ApiException.class)
    public void verify(String phone, String code) {
        var c = challenges.latestForUpdate(phone).orElseThrow(OtpService::invalid);
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
