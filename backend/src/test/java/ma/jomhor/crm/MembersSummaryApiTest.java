package ma.jomhor.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Contract (BE-33): docs/04-api.md GET /org/members/summary; plan lot-4 Q89, Q133. Counts only. */
class MembersSummaryApiTest extends CrmTest {

    private String summary(Club c) throws Exception {
        String json = body(call(caller(c.org(), "OPS"), get("/org/members/summary")).andExpect(status().isOk()));
        assertKeys(json, "$", "members", "joined_7d", "marketing_push", "marketing_whatsapp", "reachable_push", "tiers");
        List<Map<String, Object>> tiers = JsonPath.read(json, "$.tiers");
        assertThat(tiers).allSatisfy(t -> assertThat(t).containsOnlyKeys("code", "name", "members"));
        return json;
    }

    private static long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private void joinedDaysAgo(Club c, UUID user, int days) {
        jdbc.sql("update memberships set joined_at = now() - make_interval(days => ?) where organizer_id = ? and user_id = ?")
                .params(days, c.org(), user).update();
    }

    /** A CLOSED event where the user holds an admitted ticket. */
    private void attended(Club c, UUID user) {
        int e = event(c, "CLOSED");
        scan(c, e, ticket(c, e, user, "VALID"));
    }

    @Test
    void countsLiveMembersOptInsReachableAndRecentJoins() throws Exception {
        Club c = club();
        UUID fresh = user(); // joined now, push opt-in with a live token
        member(c, fresh, true, false, false);
        token(fresh, true);
        UUID old = user(); // joined a month ago, both opt-ins, no token
        member(c, old, true, true, false);
        joinedDaysAgo(c, old, 30);
        UUID rejoined = user(); // a rejoin keeps its old joined_at (Q89)
        member(c, rejoined, false, false, true);
        joinedDaysAgo(c, rejoined, 20);
        jdbc.sql("update memberships set left_at = null where organizer_id = ? and user_id = ?")
                .params(c.org(), rejoined).update();
        UUID revoked = user(); // push opt-in, only a revoked session's token
        member(c, revoked, true, false, false);
        joinedDaysAgo(c, revoked, 10);
        token(revoked, false);
        UUID left = user(); // joined now and left: not a member
        member(c, left, true, true, true);
        token(left, true);
        member(club(), user(), true, true, false); // another organizer's

        String json = summary(c);
        assertThat(number(json, "$.members")).isEqualTo(4);
        assertThat(number(json, "$.joined_7d")).isOne();
        assertThat(number(json, "$.marketing_push")).isEqualTo(3);
        assertThat(number(json, "$.marketing_whatsapp")).isOne();
        assertThat(number(json, "$.reachable_push")).isOne();
        for (UUID u : List.of(fresh, old, rejoined, revoked, left)) {
            assertThat(json).doesNotContain(phone(u)).doesNotContain(u.toString());
        }
    }

    @Test
    void tiersFromSettingsInFrenchWithZeros() throws Exception {
        Club c = club("""
                {"loyalty": {"tiers": [
                  {"code": "B", "name": {"fr": "Bronze", "ar": "x", "en": "Bronze"}, "min_events": 0},
                  {"code": "S", "name": {"fr": "Argent", "en": "Silver"}, "min_events": 1},
                  {"code": "G", "name": {"fr": "Or", "en": "Gold"}, "min_events": 3}]}}""");
        member(c, user(), false, false, false);
        for (int i = 0; i < 2; i++) {
            UUID regular = user();
            member(c, regular, false, false, false);
            attended(c, regular);
        }
        UUID gone = user(); // attended but left: not counted
        member(c, gone, false, false, true);
        attended(c, gone);

        List<Map<String, Object>> tiers = JsonPath.read(summary(c), "$.tiers");
        assertThat(tiers).containsExactly(Map.of("code", "B", "name", "Bronze", "members", 1),
                Map.of("code", "S", "name", "Argent", "members", 2), Map.of("code", "G", "name", "Or", "members", 0));
    }

    @Test
    void defaultTiersWhenTheSettingsHaveNone() throws Exception {
        Club c = club();
        List<Map<String, Object>> tiers = JsonPath.read(summary(c), "$.tiers");
        assertThat(tiers).containsExactly(Map.of("code", "T1", "name", "Membre", "members", 0),
                Map.of("code", "T2", "name", "Fidèle", "members", 0),
                Map.of("code", "T3", "name", "Pilier", "members", 0));
    }
}
