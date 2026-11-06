package ma.jomhor.catalog.adapters.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.domain.Event;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan: public GET /fan/events and /fan/events/{id} (no token; SecurityConfig's public chain).
 * Responses are the DTO records below, field by field: no key, no sold or quota, only remaining.
 */
@RestController
class FanEventController {

    private final EventService events;

    FanEventController(EventService events) {
        this.events = events;
    }

    record VenueDto(String name, String city) {}

    record EventDto(int id, UUID organizerId, Event.Type eventType, String title, Instant startsAt,
                    Instant doorsOpenAt, String timezone, VenueDto venue, @JsonRawValue String metadata,
                    Instant saleStartsAt, Instant salesCloseAt, boolean onSale, boolean soldOut, Long fromPriceMad,
                    int perAccountLimit) {
        static EventDto of(Event.Listing e) {
            return new EventDto(e.id(), e.organizerId(), e.eventType(), e.title(), e.startsAt(), e.doorsOpenAt(),
                    e.timezone(), new VenueDto(e.venueName(), e.venueCity()), e.metadata(), e.saleStartsAt(),
                    e.salesCloseAt(), e.onSale(), e.soldOut(), e.fromPriceMad(), e.perAccountLimit());
        }
    }

    record PriceDto(UUID priceCategoryId, String name, long priceMad, int remaining) {}

    record ZoneDto(int id, String code, String name, boolean seated, List<String> gateCodes, List<PriceDto> prices) {
        static ZoneDto of(Event.ZoneListing z) {
            return new ZoneDto(z.id(), z.code(), z.name(), z.seated(), z.gateCodes(), z.prices().stream()
                    .map(p -> new PriceDto(p.priceCategoryId(), p.name(), p.priceMad(), p.remaining())).toList());
        }
    }

    record EventDetailDto(@JsonUnwrapped EventDto event, List<ZoneDto> zones) {}

    @GetMapping("/fan/events")
    List<EventDto> list(@RequestParam(required = false) UUID organizer, @RequestParam(required = false) Instant from) {
        return events.publicList(organizer, from).stream().map(EventDto::of).toList();
    }

    @GetMapping("/fan/events/{id}")
    EventDetailDto get(@PathVariable int id) {
        EventService.PublicEvent e = events.publicEvent(id);
        return new EventDetailDto(EventDto.of(e.event()), e.zones().stream().map(ZoneDto::of).toList());
    }
}
