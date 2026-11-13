package ma.jomhor.notifications.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.tenancy.application.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan PUT /fan/me/push-token: {@code {token, platform}} → 204. The token belongs to the calling
 * session (the JWT's sid). token 1–4096 chars and not blank, platform android or ios, else 422 on the field.
 */
@RestController
class FanPushTokenController {

    record Put(@NotBlank @Size(max = 4096) String token, @NotNull @Pattern(regexp = "android|ios") String platform) {
        @Override
        public String toString() {
            return "Put[" + platform + "]";
        }
    }

    private final FanMessageService messages;

    FanPushTokenController(FanMessageService messages) {
        this.messages = messages;
    }

    @PutMapping("/fan/me/push-token")
    ResponseEntity<Void> put(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Put body) {
        messages.registerPushToken(UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(AuthService.SID)), body.token(), body.platform());
        return ResponseEntity.noContent().build();
    }
}
