package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Contract: public GET /fan/organizers and /fan/organizers/{slug} (docs/04-api.md §Fan, §Club directory): listed
 * organizers only, by name then id, exactly {id, slug, name, kind, crest_url}, nothing else of settings, the crest
 * only under the configured base URL, no token needed and none checked.
 */
class FanOrganizerApiTest extends IntegrationTest {

    private UUID organizer(String name, String kind, String settings) {
        return jdbc.sql("insert into organizers (name, kind, settings) values (?, ?::organizer_kind, ?::jsonb) returning id")
                .params(name, kind, settings).query(UUID.class).single();
    }

    private String json(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Cache-Control"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void directoryListsListedOrganizersByNameThenIdWithTheExactShape() throws Exception {
        String prefix = "Dir " + UUID.randomUUID() + " ";
        UUID beta = organizer(prefix + "Beta", "SPORTS_CLUB", """
                {"theme": {"accent": "#FF0000", "on_accent": "#000000"},
                 "cash_points": [{"name": "Guichet", "address": "Porte 1", "hours": "SECRET-HOURS"}],
                 "SECRET": "do-not-leak", "crest_url": "https://assets.jomhor.ma/crests/beta.svg"}""");
        UUID alpha = organizer(prefix + "Alpha", "PROMOTER", "{}");
        UUID gamma1 = organizer(prefix + "Gamma", "OTHER", "{}");
        UUID gamma2 = organizer(prefix + "Gamma", "VENUE", "{}");
        UUID hidden = organizer(prefix + "Hidden", "SPORTS_CLUB", "{}"); // no slug: unlisted
        String betaSlug = listed(beta);
        for (UUID id : List.of(alpha, gamma1, gamma2)) {
            listed(id);
        }
        List<String> gammas = List.of(gamma1.toString(), gamma2.toString()).stream().sorted().toList();

        String json = json("/fan/organizers");
        List<Map<String, Object>> mine = JsonPath.<List<Map<String, Object>>>read(json, "$[*]").stream()
                .filter(o -> ((String) o.get("name")).startsWith(prefix)).toList();
        assertThat(mine).extracting(o -> o.get("id"))
                .containsExactly(alpha.toString(), beta.toString(), gammas.get(0), gammas.get(1));
        assertThat(json).doesNotContain(hidden.toString());
        for (Map<String, Object> o : mine) {
            assertThat(o.keySet()).containsExactlyInAnyOrder("id", "slug", "name", "kind", "crest_url");
        }
        assertThat(mine.get(1)).containsEntry("slug", betaSlug).containsEntry("name", prefix + "Beta")
                .containsEntry("kind", "SPORTS_CLUB")
                .containsEntry("crest_url", "https://assets.jomhor.ma/crests/beta.svg");
        assertThat(mine.get(0)).containsEntry("kind", "PROMOTER").containsEntry("crest_url", null);
        for (String leak : List.of("theme", "accent", "#FF0000", "cash_points", "Guichet", "SECRET", "do-not-leak",
                "settings")) {
            assertThat(json).doesNotContain(leak);
        }

        String one = json("/fan/organizers/" + betaSlug);
        assertThat(JsonPath.<Map<String, Object>>read(one, "$").keySet())
                .containsExactlyInAnyOrder("id", "slug", "name", "kind", "crest_url");
        assertThat(JsonPath.<String>read(one, "$.id")).isEqualTo(beta.toString());
        assertThat(one).doesNotContain("SECRET").doesNotContain("Guichet").doesNotContain("theme");
    }

    @Test
    void crestOnlyUnderTheBaseUrl() throws Exception {
        Map<String, String> cases = Map.of(
                "https://assets.jomhor.ma/crests/atlas-fc.svg", "https://assets.jomhor.ma/crests/atlas-fc.svg",
                "http://assets.jomhor.ma/crests/atlas-fc.svg", "",
                "https://evil.example/crests/atlas-fc.svg", "",
                "https://assets.jomhor.ma.evil/crests/atlas-fc.svg", "",
                "https://assets.jomhor.ma@evil.example/crests/atlas-fc.svg", "",
                "https://assets.jomhor.ma/crestsx/atlas-fc.svg", "",
                "https://assets.jomhor.ma/other/atlas-fc.svg", "",
                "https://assets.jomhor.ma/crests/a b.svg", "",
                "//assets.jomhor.ma/crests/atlas-fc.svg", "");
        for (var c : cases.entrySet()) {
            UUID id = organizer("Crest " + UUID.randomUUID(), "SPORTS_CLUB",
                    "{\"crest_url\": \"%s\"}".formatted(c.getKey()));
            String slug = listed(id);
            String json = json("/fan/organizers/" + slug);
            assertThat(JsonPath.<String>read(json, "$.crest_url")).as(c.getKey())
                    .isEqualTo(c.getValue().isEmpty() ? null : c.getValue());
        }
        // not a string at all
        String slug = listed(organizer("Crest " + UUID.randomUUID(), "OTHER", "{\"crest_url\": {\"a\": 1}}"));
        assertThat(JsonPath.<String>read(json("/fan/organizers/" + slug), "$.crest_url")).isNull();
    }

    @Test
    void unknownUnlistedOrMalformedSlugIs404() throws Exception {
        organizer("Unlisted " + UUID.randomUUID(), "SPORTS_CLUB", "{}");
        for (String slug : List.of("no-such-club-" + UUID.randomUUID().toString().substring(0, 8), "ATLAS", "a",
                "atlas--fc", "a_b")) {
            mvc.perform(get("/fan/organizers/" + slug)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        // "../x" is not one segment: the request firewall refuses the non-normalized path (400, 04 shape) before any
        // lookup, as on every route. Browsers and HTTP clients normalize it before sending anyway.
        mvc.perform(get("/fan/organizers/../x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void publicWithAnyAuthorizationHeaderIgnored() throws Exception {
        String slug = listed(organizer("Auth " + UUID.randomUUID(), "SPORTS_CLUB", "{}"));
        for (String path : List.of("/fan/organizers", "/fan/organizers/" + slug)) {
            mvc.perform(get(path).header("Authorization", "Bearer not-a-jwt")).andExpect(status().isOk());
            mvc.perform(get(path).header("Authorization", "Bearer " + fanToken(randomPhone())))
                    .andExpect(status().isOk());
        }
        // only the GETs are open
        mvc.perform(post("/fan/organizers"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/fan/organizers/" + slug + "/events")).andExpect(status().isUnauthorized());
    }
}
