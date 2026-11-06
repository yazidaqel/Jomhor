package ma.jomhor.catalog.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.application.VenueService;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.catalog.domain.Venue.Detail;
import ma.jomhor.catalog.domain.Venue.Gate;
import ma.jomhor.catalog.domain.Venue.Zone;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Organizer: /org/venues*, /org/gates/{id}/zones. PATCH bodies are partial: an absent or
 * null field is left unchanged; venue {@code city: ""} clears the city.
 */
@RestController
class VenueController {

    private static final String NOT_BLANK = "(?s).*\\S.*";
    private static final String NOT_BLANK_MESSAGE = "must not be blank";

    private final VenueService venues;

    VenueController(VenueService venues) {
        this.venues = venues;
    }

    record VenueCreate(@NotBlank String name, String city, @NotNull @Positive Integer capacity, String timezone) {}

    record VenuePatch(@Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE) String name, String city,
                      @Positive Integer capacity, String timezone) {}

    record ZoneCreate(@NotBlank String code, @NotBlank String name, @NotNull @PositiveOrZero Integer capacity,
                      Boolean seated) {}

    record ZonePatch(@Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE) String code,
                     @Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE) String name,
                     @PositiveOrZero Integer capacity, Boolean seated) {}

    record GateCreate(@NotBlank String code, @NotBlank String name) {}

    record GatePatch(@Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE) String code,
                     @Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE) String name) {}

    record GateZones(@NotNull @Size(max = 1000) List<@NotNull Integer> zoneIds) {}

    @GetMapping("/org/venues")
    List<Venue> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        return venues.list(org.organizerId());
    }

    @PostMapping("/org/venues")
    @ResponseStatus(HttpStatus.CREATED)
    Venue create(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @Valid @RequestBody VenueCreate body) {
        return venues.create(org.organizerId(), body.name(), body.city(), body.capacity(), body.timezone());
    }

    @GetMapping("/org/venues/{id}")
    Detail get(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        return venues.get(org.organizerId(), id);
    }

    @PatchMapping("/org/venues/{id}")
    Detail update(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                  @Valid @RequestBody VenuePatch body) {
        return venues.update(org.organizerId(), id, body.name(), body.city(), body.capacity(), body.timezone());
    }

    @PostMapping("/org/venues/{id}/zones")
    @ResponseStatus(HttpStatus.CREATED)
    Zone createZone(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                    @Valid @RequestBody ZoneCreate body) {
        return venues.createZone(org.organizerId(), id, body.code(), body.name(), body.capacity(),
                Boolean.TRUE.equals(body.seated()));
    }

    @PatchMapping("/org/venues/{id}/zones/{zoneId}")
    Zone updateZone(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                    @PathVariable int zoneId, @Valid @RequestBody ZonePatch body) {
        return venues.updateZone(org.organizerId(), id, zoneId, body.code(), body.name(), body.capacity(), body.seated());
    }

    @DeleteMapping("/org/venues/{id}/zones/{zoneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteZone(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                    @PathVariable int zoneId) {
        venues.deleteZone(org.organizerId(), id, zoneId);
    }

    @PostMapping("/org/venues/{id}/gates")
    @ResponseStatus(HttpStatus.CREATED)
    Gate createGate(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                    @Valid @RequestBody GateCreate body) {
        return venues.createGate(org.organizerId(), id, body.code(), body.name());
    }

    @PatchMapping("/org/venues/{id}/gates/{gateId}")
    Gate updateGate(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                    @PathVariable UUID gateId, @Valid @RequestBody GatePatch body) {
        return venues.updateGate(org.organizerId(), id, gateId, body.code(), body.name());
    }

    @DeleteMapping("/org/venues/{id}/gates/{gateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteGate(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                    @PathVariable UUID gateId) {
        venues.deleteGate(org.organizerId(), id, gateId);
    }

    @PutMapping("/org/gates/{id}/zones")
    Gate replaceGateZones(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                          @Valid @RequestBody GateZones body) {
        return venues.replaceGateZones(org.organizerId(), id, body.zoneIds());
    }
}
