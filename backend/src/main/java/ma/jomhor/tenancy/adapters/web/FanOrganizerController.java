package ma.jomhor.tenancy.adapters.web;

import java.util.List;
import java.util.UUID;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.domain.Organizer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan, §Club directory: public GET /fan/organizers and /fan/organizers/{slug} (no token;
 * SecurityConfig's public chain). Exactly {id, slug, name, kind, crest_url}: nothing else of settings.
 */
@RestController
class FanOrganizerController {

    private final OrganizerService organizers;

    FanOrganizerController(OrganizerService organizers) {
        this.organizers = organizers;
    }

    record OrganizerDto(UUID id, String slug, String name, String kind, String crestUrl) {
        static OrganizerDto of(Organizer o) {
            return new OrganizerDto(o.id(), o.slug(), o.name(), o.kind(), o.crestUrl());
        }
    }

    @GetMapping("/fan/organizers")
    List<OrganizerDto> list() {
        return organizers.listed().stream().map(OrganizerDto::of).toList();
    }

    @GetMapping("/fan/organizers/{slug}")
    OrganizerDto get(@PathVariable String slug) {
        return OrganizerDto.of(organizers.listed(slug));
    }
}
