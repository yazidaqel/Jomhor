package ma.jomhor.notifications.adapters.web;

import java.util.UUID;
import ma.jomhor.notifications.application.TicketDeliveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/04-api.md §Fan POST /fan/tickets/{id}/whatsapp: queues the ticket PDF to the holder's phone → 202. */
@RestController
class FanTicketDeliveryController {

    private final TicketDeliveryService deliveries;

    FanTicketDeliveryController(TicketDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @PostMapping("/fan/tickets/{id}/whatsapp")
    ResponseEntity<Void> resend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        deliveries.resend(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.accepted().build();
    }
}
