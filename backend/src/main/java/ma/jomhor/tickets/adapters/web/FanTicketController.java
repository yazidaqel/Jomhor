package ma.jomhor.tickets.adapters.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.HolderNames;
import ma.jomhor.tenancy.application.AuthService;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tickets.application.TicketTransferService;
import ma.jomhor.tickets.domain.Ticket;
import ma.jomhor.tickets.domain.TicketToken;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan GET /fan/tickets?with_keys=: the tickets held by the token's sub, field by field below: no
 * full name, no phone, no order, no K_e. {@code k_t} only when TicketService set it (with_keys, VALID, not CLOSED).
 * GET /fan/tickets/{id}/pdf: the holder's printable ticket. POST /fan/tickets/{id}/transfer and /reissue.
 */
@RestController
class FanTicketController {

    private final TicketService tickets;
    private final TicketTransferService transfers;

    FanTicketController(TicketService tickets, TicketTransferService transfers) {
        this.tickets = tickets;
        this.transfers = transfers;
    }

    record VenueDto(String name, String city) {}

    /** 04 §Club directory: with id, since the ticket's event has no organizer_id beside it. */
    record OrganizerDto(UUID id, String slug, String name, String crestUrl) {}

    record EventDto(int id, String title, Event.Type eventType, Event.Status status, Instant startsAt,
                    Instant doorsOpenAt, String timezone, VenueDto venue, @JsonRawValue String metadata,
                    OrganizerDto organizer) {}

    record ZoneDto(int id, String code, String name) {}

    record TransferredDto(String toLabel, Instant at) {}

    /** 04 Lot 4: a gate serving the ticket's zone; coordinates as JSON numbers (scale 6, plain) or null. */
    record GateDto(String code, String name, String directions, BigDecimal latitude, BigDecimal longitude) {}

    record TicketDto(UUID id, String ticketNo, Ticket.Status status, String holderLabel, String seatLabel,
                     EventDto event, ZoneDto zone, List<String> gateCodes, List<GateDto> gates, Instant transferUntil,
                     TransferredDto transferred,
                     @JsonProperty("k_t") @JsonInclude(JsonInclude.Include.NON_NULL) String ticketKey) {

        static TicketDto of(TicketService.Held h) {
            Ticket t = h.ticket();
            Event.Listing e = h.event();
            Event.ZoneListing z = h.zone();
            return new TicketDto(t.id(), TicketToken.ticketNo(t.id()), t.status(),
                    t.holderName() == null ? null : HolderNames.label(t.holderName()), t.seatLabel(),
                    new EventDto(e.id(), e.title(), e.eventType(), e.status(), e.startsAt(), e.doorsOpenAt(), e.timezone(),
                            new VenueDto(e.venueName(), e.venueCity()), e.metadata(),
                            new OrganizerDto(e.organizerId(), e.organizer().slug(), e.organizer().name(),
                                    e.organizer().crestUrl())),
                    new ZoneDto(z.id(), z.code(), z.name()), z.gateCodes(), z.gates().stream()
                            .map(g -> new GateDto(g.code(), g.name(), g.directions(), g.latitude(), g.longitude()))
                            .toList(),
                    h.transferUntil(),
                    t.transferred() == null ? null : new TransferredDto(
                            t.transferred().toName() == null ? null : HolderNames.label(t.transferred().toName()),
                            t.transferred().at()),
                    h.ticketKey());
        }

        @Override
        public String toString() {
            return "TicketDto[" + id + "]";
        }
    }

    record TicketsDto(Instant serverTime, List<TicketDto> tickets) {}

    record TransferBody(String toPhone, String name) {
        @Override
        public String toString() {
            return "TransferBody[]";
        }
    }

    record ReissuedDto(UUID ticketId) {}

    @GetMapping("/fan/tickets")
    ResponseEntity<TicketsDto> list(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(name = "with_keys", required = false) String withKeys) {
        // Exactly "true": not Spring's lenient boolean (on, yes, 1, any case), which would hand out keys loosely.
        List<TicketDto> held = tickets.heldBy(UUID.fromString(jwt.getSubject()), "true".equals(withKeys)).stream()
                .map(TicketDto::of).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TicketsDto(Instant.now(), held));
    }

    /** 04 GET /fan/tickets/{id}/pdf. Filename billet-XXXX-XXXX.pdf: the ticket_no with its space as a hyphen. */
    @GetMapping("/fan/tickets/{id}/pdf")
    ResponseEntity<byte[]> pdf(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TicketService.Pdf pdf = tickets.pdf(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename("billet-" + pdf.ticketNo().replace(' ', '-') + ".pdf").build().toString())
                .body(pdf.content());
    }

    /** 04 POST /fan/tickets/{id}/transfer → 201 {transfer_id, to_label, transferred_at}. */
    @PostMapping("/fan/tickets/{id}/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    TicketTransferService.Transferred transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @RequestBody TransferBody body) {
        return transfers.transfer(UUID.fromString(jwt.getSubject()), id, body.toPhone(), body.name());
    }

    /** 04 POST /fan/tickets/{id}/reissue → 201 {ticket_id}. */
    @PostMapping("/fan/tickets/{id}/reissue")
    @ResponseStatus(HttpStatus.CREATED)
    ReissuedDto reissue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return new ReissuedDto(transfers.reissue(UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(AuthService.SID)), id));
    }
}
