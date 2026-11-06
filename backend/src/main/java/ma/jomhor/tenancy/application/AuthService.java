package ma.jomhor.tenancy.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.domain.Membership;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tenancy.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Phone-OTP login for fans (aud=fan, 30 d) and staff (aud=org, 12 h). No refresh token in Lot 0 (Q13). */
@Service
public class AuthService {

    public static final String FAN = "fan";
    public static final String ORG = "org";
    static final Duration FAN_TTL = Duration.ofDays(30);
    static final Duration ORG_TTL = Duration.ofHours(12);

    private final OtpService otp;
    private final Accounts accounts;
    private final JwtEncoder jwt;

    public AuthService(OtpService otp, Accounts accounts, JwtEncoder jwt) {
        this.otp = otp;
        this.accounts = accounts;
        this.jwt = jwt;
    }

    /** token and expiresAt are null for /org/me. */
    public record Session(String token, Instant expiresAt, User user, List<Membership> memberships) {}

    public void fanRequestOtp(String phone) {
        if (!otp.issue(phone, true)) {
            throw new ApiException("OTP_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many codes requested");
        }
    }

    public Session fanVerify(String phone, String code) {
        otp.verify(phone, code);
        return session(accounts.upsertByPhone(phone), List.of(), FAN, FAN_TTL);
    }

    /**
     * No staff enumeration: always 202, and every phone goes through the same challenge path and rate limit.
     * The code is delivered only to a phone with an active staff row.
     */
    public void orgRequestOtp(String phone) {
        otp.issue(phone, accounts.hasActiveStaff(phone));
    }

    /**
     * The challenge is checked first for every phone, so OTP_INVALID / OTP_EXPIRED do not depend on staff.
     * A correct code for a phone without an active staff row is then OTP_INVALID; nothing is created.
     */
    public Session orgVerify(String phone, String code) {
        otp.verify(phone, code);
        Optional<UUID> userId = accounts.findUserIdByPhone(phone);
        List<Membership> memberships = userId.map(accounts::activeMemberships).orElse(List.of());
        if (memberships.isEmpty()) {
            throw OtpService.invalid();
        }
        User user = accounts.findUser(userId.get()).orElseThrow(OtpService::invalid);
        return session(user, memberships, ORG, ORG_TTL);
    }

    public Session me(UUID userId) {
        User user = accounts.findUser(userId)
                .orElseThrow(() -> new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Unknown user"));
        return new Session(null, null, user, accounts.activeMemberships(userId));
    }

    public Optional<StaffRole> activeRole(UUID organizerId, UUID userId) {
        return accounts.activeRole(organizerId, userId);
    }

    private Session session(User user, List<Membership> memberships, String audience, Duration ttl) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder()
                .subject(user.id().toString())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        var token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new Session(token.getTokenValue(), now.plus(ttl), user, memberships);
    }
}
