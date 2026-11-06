package ma.jomhor.tenancy.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.tenancy.application.AuthService;
import ma.jomhor.tenancy.application.AuthService.Session;
import ma.jomhor.tenancy.domain.Membership;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tenancy.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/04-api.md: /fan/auth/*, /org/auth/*, /org/me. */
@RestController
class AuthController {

    static final String E164 = "\\+[1-9][0-9]{7,14}";

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    record OtpRequest(@NotNull @Pattern(regexp = E164) String phone) {}

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
    void fanOtp(@Valid @RequestBody OtpRequest body) {
        auth.fanRequestOtp(body.phone());
    }

    @PostMapping("/fan/auth/verify")
    FanSession fanVerify(@Valid @RequestBody VerifyRequest body) {
        Session s = auth.fanVerify(body.phone(), body.code());
        return new FanSession(s.token(), s.expiresAt(), UserDto.of(s.user()));
    }

    @PostMapping("/org/auth/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void orgOtp(@Valid @RequestBody OtpRequest body) {
        auth.orgRequestOtp(body.phone());
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
}
