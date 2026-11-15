package ma.jomhor.catalog.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Organizer /org/series*. Roles from OrgContextInterceptor: ADMIN and OPS read and write, FINANCE
 * reads, CASHIER, GATE and SUPERVISOR get 403.
 */
@RestController
class SeriesController {

    record SeriesCreate(@NotNull String name, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn) {}

    record SeriesPatch(String name, LocalDate startsOn, LocalDate endsOn) {}

    record SeriesDto(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {
        static SeriesDto of(Event.Series s) {
            return new SeriesDto(s.id(), s.name(), s.startsOn(), s.endsOn());
        }
    }

    private final EventService events;

    SeriesController(EventService events) {
        this.events = events;
    }

    @GetMapping("/org/series")
    List<SeriesDto> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        return events.series(org.organizerId()).stream().map(SeriesDto::of).toList();
    }

    @PostMapping("/org/series")
    @ResponseStatus(HttpStatus.CREATED)
    SeriesDto create(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @Valid @RequestBody SeriesCreate body) {
        return SeriesDto.of(events.createSeries(org.organizerId(), body.name(), body.startsOn(), body.endsOn()));
    }

    @PatchMapping("/org/series/{id}")
    SeriesDto update(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                     @RequestBody SeriesPatch body) {
        return SeriesDto.of(events.updateSeries(org.organizerId(), id, body.name(), body.startsOn(), body.endsOn()));
    }
}
