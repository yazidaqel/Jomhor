package ma.jomhor.tickets.adapters.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.HolderNames;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tickets.domain.Ticket;
import ma.jomhor.tickets.domain.TicketToken;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan GET /fan/tickets?with_keys=: the tickets held by the token's sub, field by field below: no
 * full name, no phone, no order, no K_e. {@code k_t} only when TicketService set it (with_keys, VALID, not CLOSED).
 */
@RestController
class FanTicketController {

    private final TicketService tickets;

    FanTicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    record VenueDto(String name, String city) {}

    record EventDto(int id, String title, Event.Type eventType, Instant startsAt, Instant doorsOpenAt, String timezone,
                    VenueDto venue, @JsonRawValue String metadata) {}

    record ZoneDto(int id, String code, String name) {}

    record TicketDto(UUID id, String ticketNo, Ticket.Status status, String holderLabel, String seatLabel,
                     EventDto event, ZoneDto zone, List<String> gateCodes,
                     @JsonProperty("k_t") @JsonInclude(JsonInclude.Include.NON_NULL) String ticketKey) {

        static TicketDto of(TicketService.Held h) {
            Ticket t = h.ticket();
            Event.Listing e = h.event();
            Event.ZoneListing z = h.zone();
            return new TicketDto(t.id(), TicketToken.ticketNo(t.id()), t.status(),
                    t.holderName() == null ? null : HolderNames.label(t.holderName()), t.seatLabel(),
                    new EventDto(e.id(), e.title(), e.eventType(), e.startsAt(), e.doorsOpenAt(), e.timezone(),
                            new VenueDto(e.venueName(), e.venueCity()), e.metadata()),
                    new ZoneDto(z.id(), z.code(), z.name()), z.gateCodes(), h.ticketKey());
        }

        @Override
        public String toString() {
            return "TicketDto[" + id + "]";
        }
    }

    record TicketsDto(Instant serverTime, List<TicketDto> tickets) {}

    @GetMapping("/fan/tickets")
    ResponseEntity<TicketsDto> list(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(name = "with_keys", required = false) String withKeys) {
        // Exactly "true": not Spring's lenient boolean (on, yes, 1, any case), which would hand out keys loosely.
        List<TicketDto> held = tickets.heldBy(UUID.fromString(jwt.getSubject()), "true".equals(withKeys)).stream()
                .map(TicketDto::of).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TicketsDto(Instant.now(), held));
    }
}
