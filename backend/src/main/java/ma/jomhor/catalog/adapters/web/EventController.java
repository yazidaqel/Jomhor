package ma.jomhor.catalog.adapters.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.EventService.EventInput;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Event.PriceCategory;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * docs/04-api.md §Organizer: /org/events*, /org/events/{id}/prices*, /org/events/{id}/publish, /close. Responses are
 * the DTO records below, built field by field: never the domain record, so K_e can't ride along.
 */
@RestController
class EventController {

    private static final String NOT_BLANK = "(?s).*\\S.*";
    private static final String NOT_BLANK_MESSAGE = "must not be blank";

    private final EventService events;

    EventController(EventService events) {
        this.events = events;
    }

    // ---------- requests ----------

    record EventCreate(@NotNull UUID venueId, @NotNull Event.Type eventType, @NotBlank String title,
                       @NotNull Instant startsAt, Instant doorsOpenAt, Instant saleStartsAt,
                       Event.ReentryPolicy reentryPolicy, @Min(1) @Max(20) Integer perAccountLimit, JsonNode metadata) {}

    record EventPatch(UUID venueId, Event.Type eventType,
                      @Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE) String title, Instant startsAt,
                      Instant doorsOpenAt, Instant saleStartsAt, Event.ReentryPolicy reentryPolicy,
                      @Min(1) @Max(20) Integer perAccountLimit, JsonNode metadata) {}

    record PriceCreate(@NotNull Integer zoneId, @NotBlank String name, @NotNull @PositiveOrZero Long priceMad,
                       @NotNull @PositiveOrZero Integer quota) {}

    record PricePatch(Integer zoneId, @Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE) String name,
                      @PositiveOrZero Long priceMad, @PositiveOrZero Integer quota) {}

    // ---------- responses ----------

    record VenueRef(UUID id, String name, String timezone) {}

    record EventRow(int id, String title, Event.Type eventType, Instant startsAt, Event.Status status, VenueRef venue,
                    long quota, long sold) {
        static EventRow of(Event.Summary s) {
            return new EventRow(s.id(), s.title(), s.eventType(), s.startsAt(), s.status(),
                    new VenueRef(s.venueId(), s.venueName(), s.venueTimezone()), s.quota(), s.sold());
        }
    }

    record EventDto(int id, UUID venueId, String timezone, UUID seriesId, Event.Type eventType, String title,
                    Instant startsAt, Instant doorsOpenAt, Instant saleStartsAt, Event.Status status,
                    Instant refundCutoffAt, Event.ReentryPolicy reentryPolicy, int perAccountLimit,
                    @JsonRawValue String metadata, Instant createdAt) {
        static EventDto of(Event e) {
            return new EventDto(e.id(), e.venueId(), e.timezone(), e.seriesId(), e.eventType(), e.title(), e.startsAt(),
                    e.doorsOpenAt(), e.saleStartsAt(), e.status(), e.refundCutoffAt(), e.reentryPolicy(), e.perAccountLimit(),
                    e.metadata(), e.createdAt());
        }
    }

    record PriceDto(UUID id, int zoneId, String name, long priceMad, int quota, int sold) {
        static PriceDto of(PriceCategory p) {
            return new PriceDto(p.id(), p.zoneId(), p.name(), p.priceMad(), p.quota(), p.sold());
        }
    }

    // ---------- events ----------

    @GetMapping("/org/events")
    List<EventRow> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                        @RequestParam(required = false) Event.Status status,
                        @RequestParam(required = false) Instant from) {
        return events.list(org.organizerId(), status, from).stream().map(EventRow::of).toList();
    }

    @PostMapping("/org/events")
    @ResponseStatus(HttpStatus.CREATED)
    EventDto create(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @Valid @RequestBody EventCreate body) {
        return EventDto.of(events.create(org.organizerId(), new EventInput(body.venueId(), body.eventType(),
                body.title(), body.startsAt(), body.doorsOpenAt(), body.saleStartsAt(), body.reentryPolicy(), body.perAccountLimit(),
                body.metadata())));
    }

    @GetMapping("/org/events/{id}")
    EventDto get(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return EventDto.of(events.get(org.organizerId(), id));
    }

    @PatchMapping("/org/events/{id}")
    EventDto update(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                    @Valid @RequestBody EventPatch body) {
        return EventDto.of(events.update(org.organizerId(), id, new EventInput(body.venueId(), body.eventType(),
                body.title(), body.startsAt(), body.doorsOpenAt(), body.saleStartsAt(), body.reentryPolicy(), body.perAccountLimit(),
                body.metadata())));
    }

    @PostMapping("/org/events/{id}/publish")
    EventDto publish(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return EventDto.of(events.publish(org.organizerId(), org.userId(), id));
    }

    @PostMapping("/org/events/{id}/close")
    EventDto close(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        org.adminOrOps();
        return EventDto.of(events.close(org.organizerId(), org.userId(), id));
    }

    // ---------- prices ----------

    @GetMapping("/org/events/{id}/prices")
    List<PriceDto> prices(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return events.prices(org.organizerId(), id).stream().map(PriceDto::of).toList();
    }

    @PostMapping("/org/events/{id}/prices")
    @ResponseStatus(HttpStatus.CREATED)
    PriceDto createPrice(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                         @Valid @RequestBody PriceCreate body) {
        return PriceDto.of(events.createPrice(org.organizerId(), id, body.zoneId(), body.name(), body.priceMad(),
                body.quota()));
    }

    @PatchMapping("/org/events/{id}/prices/{priceId}")
    PriceDto updatePrice(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                         @PathVariable UUID priceId, @Valid @RequestBody PricePatch body) {
        return PriceDto.of(events.updatePrice(org.organizerId(), id, priceId, body.zoneId(), body.name(),
                body.priceMad(), body.quota()));
    }

    @DeleteMapping("/org/events/{id}/prices/{priceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePrice(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                     @PathVariable UUID priceId) {
        events.deletePrice(org.organizerId(), id, priceId);
    }
}
