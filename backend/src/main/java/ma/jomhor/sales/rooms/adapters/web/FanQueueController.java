package ma.jomhor.sales.rooms.adapters.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import ma.jomhor.common.web.ClientIp;
import ma.jomhor.sales.rooms.application.QueueService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan POST /fan/queue (fan JWT) and GET /fan/queue ({@code X-Queue-Token} only: SecurityConfig puts it
 * on the public chain, so an Authorization header there is ignored). Both answer the GET shape, never cached.
 */
@RestController
class FanQueueController {

    record JoinBody(@NotNull Integer eventId, @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{16,64}") String deviceId) {}

    private final QueueService queue;

    FanQueueController(QueueService queue) {
        this.queue = queue;
    }

    @PostMapping("/fan/queue")
    ResponseEntity<QueueService.View> join(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody JoinBody body,
                                           HttpServletRequest request) {
        QueueService.Joined joined = queue.join(UUID.fromString(jwt.getSubject()), body.eventId(), body.deviceId(),
                ClientIp.of(request));
        return ResponseEntity.status(joined.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(joined.view());
    }

    @GetMapping("/fan/queue")
    ResponseEntity<QueueService.View> poll(@RequestHeader(name = "X-Queue-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queue.poll(token));
    }
}
