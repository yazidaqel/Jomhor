package ma.jomhor.sales.waitlist.adapters.web;

import java.time.Instant;
import java.util.UUID;
import ma.jomhor.sales.waitlist.application.Waitlist;
import ma.jomhor.sales.waitlist.application.WaitlistService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/04-api.md §Fan GET, PUT and DELETE /fan/waitlist/{event_id} (fan JWT): {@code {entry: {...} | null}}. */
@RestController
class FanWaitlistController {

    record EntryDto(Instant joinedAt, Instant notifiedAt) {
        static EntryDto of(Waitlist.Entry e) {
            return new EntryDto(e.joinedAt(), e.notifiedAt());
        }
    }

    record WaitlistDto(EntryDto entry) {}

    private final WaitlistService waitlist;

    FanWaitlistController(WaitlistService waitlist) {
        this.waitlist = waitlist;
    }

    @GetMapping("/fan/waitlist/{eventId}")
    ResponseEntity<WaitlistDto> get(@AuthenticationPrincipal Jwt jwt, @PathVariable int eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new WaitlistDto(waitlist.get(user(jwt), eventId).map(EntryDto::of).orElse(null)));
    }

    @PutMapping("/fan/waitlist/{eventId}")
    ResponseEntity<WaitlistDto> join(@AuthenticationPrincipal Jwt jwt, @PathVariable int eventId) {
        Waitlist.Joined j = waitlist.join(user(jwt), eventId);
        return ResponseEntity.status(j.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(new WaitlistDto(EntryDto.of(j.entry())));
    }

    @DeleteMapping("/fan/waitlist/{eventId}")
    ResponseEntity<Void> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable int eventId) {
        waitlist.leave(user(jwt), eventId);
        return ResponseEntity.noContent().build();
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
