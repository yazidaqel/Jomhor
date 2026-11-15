package ma.jomhor.sales.products.adapters.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.HolderNames;
import ma.jomhor.sales.products.application.PassService;
import ma.jomhor.sales.products.domain.Pass;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan GET /fan/passes: the token's account's passes, no-store. The holder as a label only: no full
 * name, no phone.
 */
@RestController
class FanPassController {

    record ProductDto(UUID id, String name) {}

    record OrganizerDto(UUID id, String slug, String name, String crestUrl) {}

    record SeriesDto(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {}

    record ZoneDto(int id, String code, String name) {}

    record EventDto(int id, String title, Event.Type eventType, Instant startsAt, UUID ticketId) {}

    record PassDto(UUID id, Pass.Status status, ProductDto product, OrganizerDto organizer, SeriesDto series,
                   ZoneDto zone, String timezone, String holderLabel, Instant createdAt, UUID orderId,
                   List<EventDto> events) {

        static PassDto of(PassService.View v) {
            var o = v.organizer();
            var s = v.scope().series();
            var z = v.scope().zone();
            return new PassDto(v.pass().id(), v.pass().status(), new ProductDto(v.product().id(), v.product().name()),
                    new OrganizerDto(o.id(), o.slug(), o.name(), o.crestUrl()),
                    new SeriesDto(s.id(), s.name(), s.startsOn(), s.endsOn()), new ZoneDto(z.id(), z.code(), z.name()),
                    z.timezone(), HolderNames.label(v.pass().holderName()), v.pass().createdAt(), v.orderId(),
                    v.events().stream().map(e -> new EventDto(e.event().id(), e.event().title(),
                            e.event().eventType(), e.event().startsAt(), e.ticketId())).toList());
        }
    }

    record Passes(List<PassDto> passes) {}

    private final PassService passes;

    FanPassController(PassService passes) {
        this.passes = passes;
    }

    @GetMapping("/fan/passes")
    ResponseEntity<Passes> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Passes(
                passes.owned(UUID.fromString(jwt.getSubject())).stream().map(PassDto::of).toList()));
    }
}
