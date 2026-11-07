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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Phone-OTP login for fans (aud=fan, 30 d, revocable through its {@code sid} session, plan Q13) and staff
 * (aud=org, 12 h; the per-request staff-row check cuts off removed staff). No refresh token.
 * Scanner enrol codes (aud=enrol, sub = organizer, 30 min, reusable; plan lot-2 Q30) use the same key: the audience
 * keeps an enrol code out of /org and /fan, and an org or fan token out of enrolment.
 */
@Service
public class AuthService {

    public static final String FAN = "fan";
    public static final String ORG = "org";
    public static final String ENROL = "enrol";
    public static final String SID = "sid";
    static final Duration FAN_TTL = Duration.ofDays(30);
    static final Duration ORG_TTL = Duration.ofHours(12);
    static final Duration ENROL_TTL = Duration.ofMinutes(30);

    private final OtpService otp;
    private final Accounts accounts;
    private final FanSessions sessions;
    private final JwtEncoder jwt;
    private final JwtDecoder enrolCodes;

    public AuthService(OtpService otp, Accounts accounts, FanSessions sessions, JwtEncoder jwt, JwtDecoder enrolCodes) {
        this.otp = otp;
        this.accounts = accounts;
        this.sessions = sessions;
        this.jwt = jwt;
        this.enrolCodes = enrolCodes;
    }

    public record EnrolCode(String code, Instant expiresAt) {}

    /** token and expiresAt are null for /org/me. */
    public record Session(String token, Instant expiresAt, User user, List<Membership> memberships) {}

    /** {@code ip}: the client address as resolved through trusted proxies only. */
    public void fanRequestOtp(String phone, String ip) {
        if (!otp.issue(phone, FAN, ip, true)) {
            throw OtpService.rateLimited();
        }
    }

    /** A new fan session on every login; its id is the token's {@code sid}, its expiry the token's. */
    public Session fanVerify(String phone, String code) {
        otp.verify(phone, FAN, code);
        return session(accounts.upsertByPhone(phone), List.of(), FAN, FAN_TTL);
    }

    public boolean fanSessionActive(UUID sessionId, UUID userId) {
        return sessions.isActive(sessionId, userId);
    }

    /** Revokes this session, or every session of the user when {@code all}. */
    public void fanLogout(UUID sessionId, UUID userId, boolean all) {
        if (all) {
            sessions.revokeAll(userId);
        } else {
            sessions.revoke(sessionId, userId);
        }
    }

    /**
     * No staff enumeration: always 202, over any limit too, and every phone goes through the same challenge
     * path and limits (org audience: never a fan's). The code is delivered only to a phone with an active staff row.
     */
    public void orgRequestOtp(String phone, String ip) {
        otp.issue(phone, ORG, ip, accounts.hasActiveStaff(phone));
    }

    /**
     * The challenge is checked first for every phone, so OTP_INVALID / OTP_EXPIRED do not depend on staff.
     * A correct code for a phone without an active staff row is then OTP_INVALID; nothing is created.
     */
    public Session orgVerify(String phone, String code) {
        otp.verify(phone, ORG, code);
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

    public EnrolCode enrolCode(UUID organizerId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().subject(organizerId.toString()).audience(List.of(ENROL))
                .issuedAt(now).expiresAt(now.plus(ENROL_TTL)).build();
        return new EnrolCode(encode(claims), now.plus(ENROL_TTL));
    }

    /** The organizer of a valid, unexpired aud=enrol code; empty for anything else (org or fan token included). */
    public Optional<UUID> enrolOrganizer(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(enrolCodes.decode(code).getSubject()));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private String encode(JwtClaimsSet claims) {
        return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private Session session(User user, List<Membership> memberships, String audience, Duration ttl) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder()
                .subject(user.id().toString())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(ttl));
        if (FAN.equals(audience)) {
            claims.claim(SID, sessions.create(user.id(), now.plus(ttl)).toString());
        }
        return new Session(encode(claims.build()), now.plus(ttl), user, memberships);
    }
}
