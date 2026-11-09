package ma.jomhor.gate.adapters.web;

import java.util.UUID;
import ma.jomhor.gate.application.GateLoad;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan GET /fan/gates/{event_id}/load → {event_id, server_time, gates:[{code, level}],
 * entries:[{ticket_id, entered_at, gate_code}]}, no-store. The body is GateLoad's records as they are (read models
 * only); GateLoadApiTest locks every key set.
 */
@RestController
class FanGateController {

    private final GateLoad load;

    FanGateController(GateLoad load) {
        this.load = load;
    }

    @GetMapping("/fan/gates/{eventId}/load")
    ResponseEntity<GateLoad.Load> load(@AuthenticationPrincipal Jwt jwt, @PathVariable int eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(load.load(UUID.fromString(jwt.getSubject()), eventId));
    }
}
