package ma.jomhor.tenancy.adapters.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.application.AuthService;
import ma.jomhor.tenancy.application.AuthService.Session;
import ma.jomhor.tenancy.application.UserService;
import ma.jomhor.tenancy.domain.Membership;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tenancy.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/04-api.md: /fan/auth/*, GET/PATCH /fan/me, POST /fan/me/logout, /org/auth/*, /org/me. */
@RestController
class AuthController {

    static final String E164 = "\\+[1-9][0-9]{7,14}";

    private final AuthService auth;
    private final UserService users;

    AuthController(AuthService auth, UserService users) {
        this.auth = auth;
        this.users = users;
    }

    /** PATCH is partial: a missing or null field is left unchanged. */
    record ProfilePatch(String displayName, String locale) {}

    record OtpRequest(@NotNull @Pattern(regexp = E164) String phone) {}

    record LogoutRequest(Boolean all) {}

    record VerifyRequest(@NotNull @Pattern(regexp = E164) String phone,
                         @NotNull @Pattern(regexp = "[0-9]{6}") String code) {}

    record UserDto(UUID id, String phone, String displayName, String locale) {
        static UserDto of(User u) {
            return new UserDto(u.id(), u.phone(), u.displayName(), u.locale());
        }
    }

    record MembershipDto(UUID organizerId, String organizerName, StaffRole role) {
        static List<MembershipDto> of(List<Membership> ms) {
            return ms.stream().map(m -> new MembershipDto(m.organizerId(), m.organizerName(), m.role())).toList();
        }
    }

    record FanSession(String token, Instant expiresAt, UserDto user) {}

    record OrgSession(String token, Instant expiresAt, List<MembershipDto> memberships) {}

    record Me(UserDto user, List<MembershipDto> memberships) {}

    @PostMapping("/fan/auth/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void fanOtp(@Valid @RequestBody OtpRequest body, HttpServletRequest request) {
        auth.fanRequestOtp(body.phone(), clientIp(request));
    }

    @PostMapping("/fan/auth/verify")
    FanSession fanVerify(@Valid @RequestBody VerifyRequest body) {
        Session s = auth.fanVerify(body.phone(), body.code());
        return new FanSession(s.token(), s.expiresAt(), UserDto.of(s.user()));
    }

    @GetMapping("/fan/me")
    UserDto fanMe(@AuthenticationPrincipal Jwt jwt) {
        return UserDto.of(users.get(UUID.fromString(jwt.getSubject())));
    }

    @PatchMapping("/fan/me")
    UserDto fanMeUpdate(@AuthenticationPrincipal Jwt jwt, @RequestBody ProfilePatch body) {
        return UserDto.of(users.update(UUID.fromString(jwt.getSubject()), body.displayName(), body.locale()));
    }

    /** {all?} → 204. The token got here only with an active session (SecurityConfig), so sid is set. */
    @PostMapping("/fan/me/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void fanLogout(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) LogoutRequest body) {
        auth.fanLogout(UUID.fromString(jwt.getClaimAsString(AuthService.SID)), UUID.fromString(jwt.getSubject()),
                body != null && Boolean.TRUE.equals(body.all()));
    }

    @PostMapping("/org/auth/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void orgOtp(@Valid @RequestBody OtpRequest body, HttpServletRequest request) {
        auth.orgRequestOtp(body.phone(), clientIp(request));
    }

    @PostMapping("/org/auth/verify")
    OrgSession orgVerify(@Valid @RequestBody VerifyRequest body) {
        Session s = auth.orgVerify(body.phone(), body.code());
        return new OrgSession(s.token(), s.expiresAt(), MembershipDto.of(s.memberships()));
    }

    @GetMapping("/org/me")
    Me me(@AuthenticationPrincipal Jwt jwt) {
        Session s = auth.me(UUID.fromString(jwt.getSubject()));
        return new Me(UserDto.of(s.user()), MembershipDto.of(s.memberships()));
    }

    /**
     * The peer address as Tomcat's RemoteIpValve left it: X-Forwarded-For is applied only when the peer matches
     * server.tomcat.remoteip.internal-proxies, so a client-sent header from anywhere else is ignored.
     * Parsed as an IP literal (no DNS); anything else is a misconfigured proxy → 400. IPv6 is bucketed by /64
     * (low 64 bits zeroed): one subscriber usually holds a whole /64, so per-address buckets would be free to rotate.
     */
    private static String clientIp(HttpServletRequest request) {
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(request.getRemoteAddr());
        } catch (IllegalArgumentException e) {
            throw new ApiException("BAD_REQUEST", HttpStatus.BAD_REQUEST, "Unresolvable client address");
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            Arrays.fill(bytes, 8, 16, (byte) 0);
            try {
                address = InetAddress.getByAddress(bytes); // raw bytes: no lookup, no scope id
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e); // only thrown for a wrong length
            }
        }
        return address.getHostAddress();
    }
}
