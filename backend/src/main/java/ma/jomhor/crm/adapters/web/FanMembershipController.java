package ma.jomhor.crm.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import ma.jomhor.crm.application.MembershipService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan /fan/me/membership. Missing or malformed {@code organizer} → 400 (Spring's own); a missing
 * PUT field → 422 on it. The body is MembershipService.View as it is; MembershipApiTest locks every key set.
 */
@RestController
class FanMembershipController {

    record Put(@NotNull UUID organizerId, @NotNull Boolean marketingPush, @NotNull Boolean marketingWhatsapp) {}

    private final MembershipService memberships;

    FanMembershipController(MembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping("/fan/me/membership")
    MembershipService.View get(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID organizer) {
        return memberships.view(user(jwt), organizer);
    }

    @PutMapping("/fan/me/membership")
    ResponseEntity<MembershipService.View> put(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Put body) {
        UUID user = user(jwt);
        boolean joined = memberships.put(user, body.organizerId(), body.marketingPush(), body.marketingWhatsapp());
        return ResponseEntity.status(joined ? HttpStatus.CREATED : HttpStatus.OK)
                .body(memberships.view(user, body.organizerId()));
    }

    @DeleteMapping("/fan/me/membership")
    ResponseEntity<Void> leave(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID organizer) {
        memberships.leave(user(jwt), organizer);
        return ResponseEntity.noContent().build();
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
