package ma.jomhor.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import ma.jomhor.catalog.OrgApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract: docs/04-api.md §Fan GET/PUT/DELETE /fan/me/membership; plan lot-4 BE-31 (Q57, Q58, Q89-Q91, Q99, Q100).
 * Clubs, events, tickets and scans are written as rows: only the membership calls go through the API.
 */
class MembershipApiTest extends OrgApiTest {

    private static final String[] VIEW = {"member", "loyalty"};
    private static final String[] MEMBER = {"number", "joined_at", "marketing_push", "marketing_whatsapp"};
    private static final String[] LOYALTY = {"events_attended", "tier", "next_tier", "last_events"};

    record Club(UUID org, UUID venue, short zone, UUID gate, UUID device, UUID staff) {}

    record Fan(UUID id, String token) {}

    // ---------- fixtures ----------

    private Club club(String settings, String timezone) {
        UUID org = jdbc.sql("insert into organizers (name, settings) values (?, ?::jsonb) returning id")
                .params("Club " + UUID.randomUUID(), settings).query(UUID.class).single();
        UUID venue = jdbc.sql("insert into venues (organizer_id, name, capacity, timezone) values (?, 'Stade', 1000, ?) "
                + "returning id").params(org, timezone).query(UUID.class).single();
        short zone = jdbc.sql("insert into zones (organizer_id, venue_id, code, name, capacity) "
                + "values (?, ?, 'NORD', 'Nord', 100) returning id").params(org, venue).query(Short.class).single();
        UUID gate = jdbc.sql("insert into gates (organizer_id, venue_id, code, name) values (?, ?, 'G1', 'Porte 1') "
                + "returning id").params(org, venue).query(UUID.class).single();
        byte[] key = new byte[32];
        ThreadLocalRandom.current().nextBytes(key);
        UUID device = jdbc.sql("insert into devices (organizer_id, label, public_key, status) "
                + "values (?, 'PDA', ?, 'ACTIVE') returning id").params(org, key).query(UUID.class).single();
        UUID staff = jdbc.sql("insert into staff (organizer_id, name, role) values (?, 'Agent', 'GATE') returning id")
                .param(org).query(UUID.class).single();
        return new Club(org, venue, zone, gate, device, staff);
    }

    private Club club() {
        return club("{}", "Africa/Casablanca");
    }

    private Club club(String settings) {
        return club(settings, "Africa/Casablanca");
    }

    private int event(Club c, String status, Instant startsAt) {
        return jdbc.sql("insert into events (organizer_id, venue_id, event_type, title, starts_at, status, event_key_enc) "
                        + "values (?, ?, 'FOOTBALL_MATCH', ?, ?, ?::event_status, ?) returning id")
                .params(c.org(), c.venue(), "Match " + startsAt, startsAt.atOffset(ZoneOffset.UTC), status,
                        new byte[32])
                .query(Integer.class).single();
    }

    private int closed(Club c) {
        return event(c, "CLOSED", Instant.now().minus(Duration.ofDays(7)));
    }

    /** A VALID ticket of the event held by {@code holder}, in its own paid order. */
    private UUID ticket(Club c, int event, UUID holder) {
        UUID price = jdbc.sql("insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota) "
                + "values (?, ?, ?, ?, 5000, 100) returning id")
                .params(c.org(), event, c.zone(), "Cat " + UUID.randomUUID()).query(UUID.class).single();
        UUID order = jdbc.sql("insert into orders (organizer_id, event_id, user_id, channel, status, total_mad, "
                        + "reserved_until) values (?, ?, ?, 'APP', 'PAID', 5000, now()) returning id")
                .params(c.org(), event, holder).query(UUID.class).single();
        return jdbc.sql("insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, holder_user_id, "
                        + "holder_name, holder_initials) values (?, ?, ?, ?, ?, ?, 'Fan Un', 'F. U.') returning id")
                .params(c.org(), order, event, c.zone(), price, holder).query(UUID.class).single();
    }

    /** One IN scan row as ingestion stores it (OVERRIDE gets a reason and a supervisor). */
    private void scan(Club c, int event, UUID ticket, String result) {
        boolean override = result.equals("OVERRIDE");
        jdbc.sql("insert into scans (scan_id, organizer_id, event_id, device_id, ticket_id, gate_id, staff_id, direction, "
                        + "result, token_version, device_time, override_reason, supervisor_id) "
                        + "values (uuidv7(), ?, ?, ?, ?, ?, ?, 'IN', ?::scan_result, 1, now(), ?::override_reason, ?)")
                .params(c.org(), event, c.device(), ticket, c.gate(), c.staff(), result,
                        override ? "ID_CHECKED" : null, override ? c.staff() : null)
                .update();
    }

    /** A ticket of a new CLOSED event, admitted. */
    private int attended(Club c, Fan f) {
        int e = closed(c);
        scan(c, e, ticket(c, e, f.id()), "OK");
        return e;
    }

    private Fan fan() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        return new Fan(jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single(),
                token);
    }

    private Fan fan(String locale) throws Exception {
        Fan f = fan();
        jdbc.sql("update users set locale = ? where id = ?").params(locale, f.id()).update();
        return f;
    }

    // ---------- calls ----------

    private ResultActions getMembership(Fan f, Object org) throws Exception {
        return mvc.perform(get("/fan/me/membership").param("organizer", String.valueOf(org))
                .header("Authorization", "Bearer " + f.token()));
    }

    private String view(Fan f, UUID org) throws Exception {
        String json = body(getMembership(f, org).andExpect(status().isOk()));
        assertShape(json);
        return json;
    }

    private ResultActions putMembership(Fan f, String json) throws Exception {
        return mvc.perform(put("/fan/me/membership").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions putMembership(Fan f, UUID org, boolean push, boolean whatsapp) throws Exception {
        return putMembership(f, "{\"organizer_id\": \"%s\", \"marketing_push\": %b, \"marketing_whatsapp\": %b}"
                .formatted(org, push, whatsapp));
    }

    private String join(Fan f, UUID org) throws Exception {
        String json = body(putMembership(f, org, false, false).andExpect(status().isCreated()));
        assertShape(json);
        return json;
    }

    private ResultActions leave(Fan f, Object org) throws Exception {
        return mvc.perform(delete("/fan/me/membership").param("organizer", String.valueOf(org))
                .header("Authorization", "Bearer " + f.token()));
    }

    /** Exactly the 04 keys at every level. */
    private static void assertShape(String json) {
        assertKeys(json, "$", VIEW);
        if (JsonPath.read(json, "$.member") != null) {
            assertKeys(json, "$.member", MEMBER);
        }
        assertKeys(json, "$.loyalty", LOYALTY);
        assertKeys(json, "$.loyalty.tier", "code", "name");
        if (JsonPath.read(json, "$.loyalty.next_tier") != null) {
            assertKeys(json, "$.loyalty.next_tier", "code", "name", "min_events");
        }
        int n = JsonPath.<List<Object>>read(json, "$.loyalty.last_events").size();
        for (int i = 0; i < n; i++) {
            assertKeys(json, "$.loyalty.last_events[" + i + "]", "event_id", "title", "event_type", "starts_at",
                    "timezone", "attended");
        }
    }

    private static int attendedCount(String json) {
        return JsonPath.read(json, "$.loyalty.events_attended");
    }

    private List<String> audit(UUID org, UUID user, String action) {
        return jdbc.sql("select coalesce(payload::text, 'null') from audit_log "
                        + "where organizer_id = ? and actor_type = 'USER' and actor_id = ? and action = ? order by id")
                .params(org, user, action).query(String.class).list();
    }

    // ---------- join, numbers, rejoin ----------

    @Test
    void joinGivesTheNextNumberAndTheGetShape() throws Exception {
        Club c = club();
        Fan a = fan();
        Fan b = fan();
        getMembership(a, c.org()).andExpect(status().isOk());

        String json = body(putMembership(a, c.org(), true, false).andExpect(status().isCreated()));
        assertShape(json);
        assertThat(JsonPath.<Integer>read(json, "$.member.number")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(json, "$.member.marketing_push")).isTrue();
        assertThat(JsonPath.<Boolean>read(json, "$.member.marketing_whatsapp")).isFalse();
        assertThat(Instant.parse(JsonPath.read(json, "$.member.joined_at")))
                .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(JsonPath.<Integer>read(join(b, c.org()), "$.member.number")).isEqualTo(2);

        List<String> joins = audit(c.org(), a.id(), "membership.join");
        assertThat(joins).hasSize(1);
        assertKeys(joins.getFirst(), "$", "marketing_push", "marketing_whatsapp");
        assertThat(JsonPath.<Boolean>read(joins.getFirst(), "$.marketing_push")).isTrue();
        assertThat(audit(c.org(), a.id(), "membership.consent")).isEmpty();
        assertThat(view(a, c.org())).isEqualTo(json); // PUT answers the GET shape
    }

    @Test
    void parallelJoinsGetDistinctGapFreeNumbers() throws Exception {
        Club c = club();
        List<Fan> fans = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fans.add(fan());
        }
        List<Integer> statuses = parallel(fans.stream().map(f -> c.org()).toList(), fans);
        assertThat(statuses).containsOnly(201);
        assertThat(jdbc.sql("select number from memberships where organizer_id = ? order by number").param(c.org())
                .query(Integer.class).list()).containsExactlyElementsOf(IntStream.rangeClosed(1, 8).boxed().toList());
    }

    @Test
    void sameUserJoiningTwiceInParallelGetsOneRow() throws Exception {
        Club c = club();
        Fan f = fan();
        List<Integer> statuses = parallel(List.of(c.org(), c.org()), List.of(f, f));
        assertThat(statuses).containsExactlyInAnyOrder(201, 200);
        assertThat(jdbc.sql("select count(*) from memberships where organizer_id = ?").param(c.org())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(audit(c.org(), f.id(), "membership.join")).hasSize(1);
    }

    /** PUT (false, false) by {@code fans.get(i)} into {@code orgs.get(i)}, all at once; the statuses. */
    private List<Integer> parallel(List<UUID> orgs, List<Fan> fans) throws Exception {
        return parallel(orgs, fans, fans.stream().map(f -> false).toList());
    }

    /** PUT ({@code push.get(i)}, false) by {@code fans.get(i)} into {@code orgs.get(i)}, all at once; the statuses. */
    private List<Integer> parallel(List<UUID> orgs, List<Fan> fans, List<Boolean> push) throws Exception {
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(fans.size())) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < fans.size(); i++) {
                Fan f = fans.get(i);
                UUID org = orgs.get(i);
                boolean p = push.get(i);
                results.add(pool.submit(() -> {
                    start.await();
                    return putMembership(f, org, p, false).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> r : results) {
                statuses.add(r.get());
            }
        }
        return statuses;
    }

    @Test
    void parallelRejoinsOfOneUserWriteOneJoin() throws Exception {
        Club c = club();
        Fan f = fan();
        join(f, c.org());
        leave(f, c.org()).andExpect(status().isNoContent());
        assertThat(parallel(List.of(c.org(), c.org()), List.of(f, f))).containsExactlyInAnyOrder(201, 200);
        assertThat(audit(c.org(), f.id(), "membership.join")).hasSize(2); // the first join and one rejoin
    }

    @Test
    void racingOptInTogglesKeepTheAuditTrailInStepWithTheRow() throws Exception {
        Club c = club();
        Fan f = fan();
        join(f, c.org()); // push off
        List<Boolean> push = IntStream.range(0, 8).mapToObj(i -> i % 2 == 0).toList();
        assertThat(parallel(Collections.nCopies(8, c.org()), Collections.nCopies(8, f), push)).containsOnly(200);

        // Every consent row is a change from the one before it (the join was push off), and the last is the row.
        List<Boolean> trail = new ArrayList<>(List.of(false));
        audit(c.org(), f.id(), "membership.consent").forEach(p -> trail.add(JsonPath.read(p, "$.marketing_push")));
        assertThat(trail).hasSizeGreaterThan(1);
        for (int i = 1; i < trail.size(); i++) {
            assertThat(trail.get(i)).as("consent row %d", i).isNotEqualTo(trail.get(i - 1));
        }
        assertThat(jdbc.sql("select marketing_push from memberships where user_id = ?").param(f.id())
                .query(Boolean.class).single()).isEqualTo(trail.getLast());
    }

    @Test
    void leaveIsIdempotentAndRejoinKeepsNumberAndJoinedAt() throws Exception {
        Club c = club();
        join(fan(), c.org());
        Fan f = fan();
        String first = body(putMembership(f, c.org(), true, true).andExpect(status().isCreated()));
        String joinedAt = JsonPath.read(first, "$.member.joined_at");

        leave(f, c.org()).andExpect(status().isNoContent());
        leave(f, c.org()).andExpect(status().isNoContent());
        assertThat(audit(c.org(), f.id(), "membership.leave")).containsExactly("null"); // one row, no payload
        String left = view(f, c.org());
        assertThat(JsonPath.<Object>read(left, "$.member")).isNull();
        assertThat(jdbc.sql("select marketing_push or marketing_whatsapp from memberships where user_id = ?")
                .param(f.id()).query(Boolean.class).single()).isFalse();

        String back = body(putMembership(f, c.org(), false, true).andExpect(status().isCreated()));
        assertShape(back);
        assertThat(JsonPath.<Integer>read(back, "$.member.number")).isEqualTo(2);
        assertThat(JsonPath.<String>read(back, "$.member.joined_at")).isEqualTo(joinedAt);
        assertThat(JsonPath.<Boolean>read(back, "$.member.marketing_whatsapp")).isTrue();
        assertThat(audit(c.org(), f.id(), "membership.join")).hasSize(2);
        assertThat(audit(c.org(), f.id(), "membership.consent")).isEmpty();
        assertThat(JsonPath.<Integer>read(join(fan(), c.org()), "$.member.number")).isEqualTo(3); // never reused
    }

    @Test
    void optInChangeIsAuditedWithTheTwoBooleansOnly() throws Exception {
        Club c = club();
        Fan f = fan();
        join(f, c.org());
        String json = body(putMembership(f, c.org(), true, false).andExpect(status().isOk()));
        assertShape(json);
        assertThat(JsonPath.<Boolean>read(json, "$.member.marketing_push")).isTrue();
        putMembership(f, c.org(), true, false).andExpect(status().isOk()); // unchanged: no row

        List<String> consents = audit(c.org(), f.id(), "membership.consent");
        assertThat(consents).hasSize(1);
        assertKeys(consents.getFirst(), "$", "marketing_push", "marketing_whatsapp");
        assertThat(JsonPath.<Boolean>read(consents.getFirst(), "$.marketing_push")).isTrue();
        assertThat(JsonPath.<Boolean>read(consents.getFirst(), "$.marketing_whatsapp")).isFalse();
        assertThat(audit(c.org(), f.id(), "membership.join")).hasSize(1);
    }

    // ---------- events attended ----------

    @Test
    void eventsAttendedCountsAdmittedScansOncePerEvent() throws Exception {
        Club c = club();
        Fan f = fan();
        int ok = closed(c);
        scan(c, ok, ticket(c, ok, f.id()), "OK");
        int override = closed(c);
        scan(c, override, ticket(c, override, f.id()), "OVERRIDE");
        int twice = event(c, "GATES_OPEN", Instant.now()); // Q100: counts before CLOSED
        UUID t = ticket(c, twice, f.id());
        scan(c, twice, t, "OK");
        scan(c, twice, t, "OK"); // a DUPLICATE_LATE on the server: counted once
        int two = closed(c);
        scan(c, two, ticket(c, two, f.id()), "OK");
        scan(c, two, ticket(c, two, f.id()), "OVERRIDE");
        int refused = closed(c);
        UUID r = ticket(c, refused, f.id());
        scan(c, refused, r, "CANCELLED");
        scan(c, refused, r, "WRONG_ZONE");
        scan(c, refused, r, "DUPLICATE");
        assertThat(attendedCount(view(f, c.org()))).isEqualTo(4);

        // A → C transfer: the old ticket is CANCELLED and stays A's; C's new ticket is scanned.
        Fan a = fan();
        Fan to = fan();
        int e = closed(c);
        UUID old = ticket(c, e, a.id());
        UUID fresh = ticket(c, e, to.id());
        jdbc.sql("update tickets set status = 'CANCELLED' where id = ?").param(old).update();
        jdbc.sql("insert into ticket_transfers (organizer_id, ticket_id, new_ticket_id, from_user_id, to_user_id) "
                + "values (?, ?, ?, ?, ?)").params(c.org(), old, fresh, a.id(), to.id()).update();
        scan(c, e, fresh, "OK");
        assertThat(attendedCount(view(to, c.org()))).isEqualTo(1);
        assertThat(attendedCount(view(a, c.org()))).isZero();
    }

    // ---------- tiers ----------

    private static final String TIERS = """
            {"loyalty": {"tiers": [
              {"code": "BRONZE", "name": {"fr": "Bronze", "ar": "برونزي", "en": "Bronze EN"}, "min_events": 0},
              {"code": "ARGENT", "name": {"fr": "Argent", "ar": "فضي"}, "min_events": 1},
              {"code": "OR", "name": {"fr": "Or", "ar": "ذهبي", "en": "Gold"}, "min_events": 2}]}}""";

    @Test
    void tiersFromSettingsInTheCallersLocale() throws Exception {
        Club c = club(TIERS);
        Fan ar = fan("ar");
        String json = view(ar, c.org());
        assertThat(JsonPath.<String>read(json, "$.loyalty.tier.code")).isEqualTo("BRONZE");
        assertThat(JsonPath.<String>read(json, "$.loyalty.tier.name")).isEqualTo("برونزي");
        assertThat(JsonPath.<String>read(json, "$.loyalty.next_tier.name")).isEqualTo("فضي");
        assertThat(JsonPath.<Integer>read(json, "$.loyalty.next_tier.min_events")).isEqualTo(1);

        Fan en = fan("en");
        attended(c, en);
        json = view(en, c.org());
        assertThat(JsonPath.<String>read(json, "$.loyalty.tier.code")).isEqualTo("ARGENT");
        assertThat(JsonPath.<String>read(json, "$.loyalty.tier.name")).isEqualTo("Argent"); // en missing → fr
        assertThat(JsonPath.<String>read(json, "$.loyalty.next_tier.name")).isEqualTo("Gold");

        attended(c, en);
        json = view(en, c.org());
        assertThat(JsonPath.<String>read(json, "$.loyalty.tier.code")).isEqualTo("OR");
        assertThat(JsonPath.<Object>read(json, "$.loyalty.next_tier")).isNull(); // top tier
    }

    @Test
    void defaultTiersWhenAbsentOrMalformed() throws Exception {
        Fan f = fan();
        for (String settings : new String[] {"{}", "{\"loyalty\": \"x\"}", "{\"loyalty\": {}}",
                "{\"loyalty\": {\"tiers\": []}}",
                "{\"loyalty\": {\"tiers\": [{\"code\": \"A\", \"name\": {\"fr\": \"A\"}, \"min_events\": 1}]}}",
                "{\"loyalty\": {\"tiers\": [{\"code\": \"A\", \"name\": {\"fr\": \"A\"}, \"min_events\": 0}, "
                        + "{\"code\": \"B\", \"name\": {\"fr\": \"B\"}, \"min_events\": 0}]}}",
                "{\"loyalty\": {\"tiers\": [{\"code\": \"A\", \"name\": {\"en\": \"A\"}, \"min_events\": 0}]}}",
                "{\"loyalty\": {\"tiers\": [{\"code\": \"A\", \"name\": {\"fr\": \"A\"}, \"min_events\": \"0\"}]}}",
                "{\"loyalty\": {\"tiers\": [{\"code\": \"A\", \"name\": {\"fr\": \"A\"}, \"min_events\": 0.5}]}}",
                "{\"loyalty\": {\"tiers\": [\"A\"]}}"}) {
            String json = view(f, club(settings).org());
            assertThat(JsonPath.<String>read(json, "$.loyalty.tier.code")).as(settings).isEqualTo("T1");
            assertThat(JsonPath.<String>read(json, "$.loyalty.tier.name")).as(settings).isEqualTo("Membre");
            assertThat(JsonPath.<String>read(json, "$.loyalty.next_tier.code")).as(settings).isEqualTo("T2");
            assertThat(JsonPath.<String>read(json, "$.loyalty.next_tier.name")).as(settings).isEqualTo("Fidèle");
            assertThat(JsonPath.<Integer>read(json, "$.loyalty.next_tier.min_events")).as(settings).isEqualTo(3);
        }
    }

    // ---------- last events ----------

    @Test
    void lastEventsAreThe11LatestClosedOldestFirstInTheVenueZone() throws Exception {
        Club c = club("{}", "Europe/Paris");
        Fan f = fan();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Integer> closed = new ArrayList<>();
        for (int i = 12; i >= 1; i--) {
            closed.add(event(c, "CLOSED", now.minus(Duration.ofDays(7L * i))));
        }
        event(c, "ON_SALE", now.plus(Duration.ofDays(7)));
        event(c, "GATES_OPEN", now);
        scan(c, closed.get(5), ticket(c, closed.get(5), f.id()), "OK");
        ticket(c, closed.get(6), f.id()); // held, never scanned

        String json = view(f, c.org());
        assertThat(JsonPath.<List<Integer>>read(json, "$.loyalty.last_events[*].event_id"))
                .containsExactlyElementsOf(closed.subList(1, 12));
        assertThat(JsonPath.<List<String>>read(json, "$.loyalty.last_events[*].timezone")).containsOnly("Europe/Paris");
        assertThat(JsonPath.<List<String>>read(json, "$.loyalty.last_events[*].event_type"))
                .containsOnly("FOOTBALL_MATCH");
        assertThat(JsonPath.<List<Boolean>>read(json, "$.loyalty.last_events[*].attended"))
                .containsExactly(false, false, false, false, true, false, false, false, false, false, false);
        assertThat(Instant.parse(JsonPath.read(json, "$.loyalty.last_events[0].starts_at")))
                .isEqualTo(now.minus(Duration.ofDays(77)));
    }

    // ---------- members and non-members ----------

    @Test
    void nonMemberGetsLoyaltyAndNoMember() throws Exception {
        Club c = club();
        Fan f = fan();
        attended(c, f);
        String json = view(f, c.org());
        assertThat(JsonPath.<Object>read(json, "$.member")).isNull();
        assertThat(attendedCount(json)).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(json, "$.loyalty.last_events")).hasSize(1);
    }

    // ---------- errors ----------

    @Test
    void badOrUnknownOrganizerAndUnauthenticated() throws Exception {
        Fan f = fan();
        UUID unknown = UUID.randomUUID();
        assertThat(body(mvc.perform(get("/fan/me/membership").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isBadRequest()))).contains("\"BAD_REQUEST\"");
        assertThat(body(getMembership(f, "abc").andExpect(status().isBadRequest()))).contains("\"BAD_REQUEST\"");
        getMembership(f, unknown).andExpect(status().isNotFound());
        mvc.perform(get("/fan/me/membership").param("organizer", club().org().toString()))
                .andExpect(status().isUnauthorized());

        putMembership(f, unknown, true, true).andExpect(status().isNotFound());
        String missing = body(putMembership(f, "{\"organizer_id\": \"%s\", \"marketing_whatsapp\": true}"
                .formatted(club().org())).andExpect(status().isUnprocessableContent()));
        assertKeys(missing, "$.details.fields", "marketing_push");
        putMembership(f, "{\"organizer_id\": \"%s\", \"marketing_push\": \"yes\", \"marketing_whatsapp\": true}"
                .formatted(club().org())).andExpect(status().isUnprocessableContent());
        mvc.perform(put("/fan/me/membership").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        leave(f, unknown).andExpect(status().isNotFound());
        leave(f, "abc").andExpect(status().isBadRequest());
        mvc.perform(delete("/fan/me/membership").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isBadRequest());
    }

    // ---------- isolation ----------

    @Test
    void organizersAreIsolated() throws Exception {
        Club a = club();
        Club b = club();
        Fan f = fan();
        assertThat(JsonPath.<Integer>read(join(f, a.org()), "$.member.number")).isEqualTo(1);
        assertThat(JsonPath.<Object>read(view(f, b.org()), "$.member")).isNull();

        attended(b, f);
        assertThat(attendedCount(view(f, a.org()))).isZero();
        assertThat(JsonPath.<List<Object>>read(view(f, a.org()), "$.loyalty.last_events")).isEmpty();
        assertThat(attendedCount(view(f, b.org()))).isEqualTo(1);

        assertThat(JsonPath.<Integer>read(join(f, b.org()), "$.member.number")).isEqualTo(1);
        leave(f, b.org()).andExpect(status().isNoContent());
        assertThat(JsonPath.<Integer>read(view(f, a.org()), "$.member.number")).isEqualTo(1); // A untouched
        assertThat(audit(a.org(), f.id(), "membership.leave")).isEmpty();
    }
}
