package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-54): docs/04-api.md §Products Member rule (plan lot-5 Q189, picking up Q171): once the organizer has a
 * MEMBERSHIP product (any status), MEMBERS places and the room rule {@code member_joined_before} need paid_until after
 * the sale start, the joined cutoff kept; {@code paid_required} in MEMBERS_ONLY, NOT_ELIGIBLE NOT_MEMBER,
 * GET /fan/me/membership and GET /org/members/summary. With no such product, step 2 is unchanged (FairLimitsApiTest,
 * Lot5FairSaleFlowTest and the room tests keep their cases).
 */
class PaidMembershipApiTest extends QueueFixture {

    private static final String JOINED_BEFORE = "2099-01-01T00:00:00Z";

    private record Sale(Caller admin, int event, String all, String members, Instant start) {}

    /** An ON_SALE event with an ALL and a MEMBERS category, on sale since a day ago (whole seconds). */
    private Sale sale() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int zone = zone(a, venue, "NORD");
        int event = event(a, venue);
        String all = price(a, event, zone, "Plein tarif", 50);
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        String members = JsonPath.read(body(call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Membres", "price_mad": 3000, "quota": 50, "audience": "MEMBERS"}"""
                .formatted(zone)).andExpect(status().isCreated())), "$.id");
        return new Sale(a, event, all, members, saleStartsAt(event, "1 day"));
    }

    /** sale_starts_at = now − {@code ago}, whole seconds; the stored value. */
    private Instant saleStartsAt(int event, String ago) {
        return jdbc.sql("update events set sale_starts_at = date_trunc('second', now()) - cast(? as interval) "
                        + "where id = ? returning sale_starts_at").params(ago, event)
                .query(OffsetDateTime.class).single().toInstant();
    }

    /** A MEMBERSHIP product, off sale: paid_required all the same. */
    private void membershipProduct(Caller a) throws Exception {
        call(a, post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "Cotisation", "price_mad": 20000, "valid_until": "2099-07-01T00:00:00Z",
                 "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": false}""").andExpect(status().isCreated());
    }

    /** A live membership; {@code paidUntil} null = never paid. */
    private void member(Caller a, UUID user, Instant joinedAt, Instant paidUntil) {
        jdbc.sql("""
                        insert into memberships (organizer_id, user_id, number, joined_at, paid_until)
                        values (?, ?, (select coalesce(max(number), 0) + 1 from memberships where organizer_id = ?),
                                ?, ?)""")
                .params(a.org(), user, a.org(), utc(joinedAt), utc(paidUntil)).update();
    }

    private static OffsetDateTime utc(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private ResultActions place(Fan f, int event, String price, String holders) throws Exception {
        return mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"event_id": %d, "channel": "APP",
                         "items": [{"price_category_id": "%s", "qty": 1, "holders": %s}]}"""
                        .formatted(event, price, holders)));
    }

    private ResultActions place(Fan f, Sale s) throws Exception {
        return place(f, s.event(), s.members(), holders(1));
    }

    private static Map<String, Object> refused(ResultActions r, String code) throws Exception {
        String json = body(r.andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo(code);
        return JsonPath.read(json, "$.details");
    }

    private static Map<String, Object> membersOnly(Instant joinedBefore, boolean paidRequired) {
        return Map.of("field", "items[0].holders[0]", "member_joined_before", joinedBefore.toString(),
                "paid_required", paidRequired);
    }

    // ---------- MEMBERS places (T = the event's sale_starts_at) ----------

    @Test
    void aMembersPlaceNeedsAPaidCotisationOnceTheClubSellsOne() throws Exception {
        Sale s = sale();
        Instant before = s.start().minus(Duration.ofDays(1));
        Fan free = fan();
        member(s.admin(), free.id(), before, null);

        // No MEMBERSHIP product: a free member who joined before the sale is a member (step 2).
        place(free, s).andExpect(status().isCreated());

        membershipProduct(s.admin());
        assertThat(refused(place(free, s), "MEMBERS_ONLY")).containsExactlyInAnyOrderEntriesOf(
                membersOnly(s.start(), true));

        Fan paid = fan();
        member(s.admin(), paid.id(), before, s.start().plusSeconds(1));
        place(paid, s).andExpect(status().isCreated());

        Fan lapsed = fan(); // paid_until ≤ T
        member(s.admin(), lapsed.id(), before, s.start());
        assertThat(refused(place(lapsed, s), "MEMBERS_ONLY")).containsExactlyInAnyOrderEntriesOf(
                membersOnly(s.start(), true));

        Fan late = fan(); // joined after the sale started, paid: the joined cutoff stays
        member(s.admin(), late.id(), s.start().plusSeconds(60), Instant.parse("2099-07-01T00:00:00Z"));
        assertThat(refused(place(late, s), "MEMBERS_ONLY")).containsExactlyInAnyOrderEntriesOf(
                membersOnly(s.start(), true));

        // An ALL place needs nothing.
        place(free, s.event(), s.all(), holders(1)).andExpect(status().isCreated());

        // No sale_starts_at: T = now, and any live member who has paid past now.
        jdbc.sql("update events set sale_starts_at = null where id = ?").param(s.event()).update();
        place(late, s).andExpect(status().isCreated());
        Fan expired = fan();
        member(s.admin(), expired.id(), before, Instant.now().minusSeconds(60));
        Map<String, Object> details = refused(place(expired, s), "MEMBERS_ONLY");
        assertThat(details).containsEntry("paid_required", true).containsEntry("member_joined_before", null);

        // POS: the cashier checks the card, paid or not.
        Caller cashier = caller(s.admin().org(), "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet Nord\"}").andExpect(status().isCreated());
        call(cashier, post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d, "items": [{"price_category_id": "%s", "qty": 1, "holders": %s}]}"""
                .formatted(UUID.randomUUID(), s.event(), s.members(), holders(1))).andExpect(status().isCreated());
    }

    // ---------- the room rule member_joined_before (paid at the phase's sale start) ----------

    private record Presale(Room room, Instant start) {}

    /** An OPEN members presale (joined before 2099-01-01) whose phase sale start is a minute ago. */
    private Presale presale() throws Exception {
        Room r = room("""
                {"pre_queue_min": 0, "admission_rate_per_min": 6000,
                 "eligibility": {"member_joined_before": "%s"}}""".formatted(JOINED_BEFORE));
        Instant start = saleStartsAt(r.event(), "1 minute");
        tick("2099-04-01T10:00:10Z");
        return new Presale(r, start);
    }

    private static Map<String, Object> notMember(boolean paidRequired) {
        return Map.of("reason", "NOT_MEMBER", "member_joined_before", JOINED_BEFORE, "paid_required", paidRequired);
    }

    @Test
    void thePresaleRuleNeedsAPaidCotisationAtThePhasesSaleStart() throws Exception {
        Presale p = presale();
        Caller a = p.room().admin();
        Instant before = p.start().minus(Duration.ofDays(1));

        Fan freeEarly = fan();
        member(a, freeEarly.id(), before, null);
        join(freeEarly, p.room().event()).andExpect(status().isCreated()); // no product: step 2
        Fan stranger = fan();
        assertThat(refused(join(stranger, p.room().event()), "NOT_ELIGIBLE"))
                .containsExactlyInAnyOrderEntriesOf(notMember(false));

        membershipProduct(a);
        Fan free = fan();
        member(a, free.id(), before, null);
        assertThat(refused(join(free, p.room().event()), "NOT_ELIGIBLE"))
                .containsExactlyInAnyOrderEntriesOf(notMember(true));

        Fan lapsed = fan();
        member(a, lapsed.id(), before, p.start());
        assertThat(refused(join(lapsed, p.room().event()), "NOT_ELIGIBLE"))
                .containsExactlyInAnyOrderEntriesOf(notMember(true));

        Fan late = fan(); // paid, but joined after member_joined_before
        member(a, late.id(), Instant.parse("2099-02-01T00:00:00Z"), Instant.parse("2099-07-01T00:00:00Z"));
        assertThat(refused(join(late, p.room().event()), "NOT_ELIGIBLE"))
                .containsExactlyInAnyOrderEntriesOf(notMember(true));

        Fan paid = fan();
        member(a, paid.id(), before, p.start().plusSeconds(1));
        String queueToken = JsonPath.read(body(join(paid, p.room().event()).andExpect(status().isCreated())),
                "$.queue_token");

        // A split pays member: the holder path only, never why.
        tick("2099-04-01T10:00:20Z");
        String json = body(poll(queueToken).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("ADMITTED");
        String admission = JsonPath.read(json, "$.admission.token");
        String friend = randomPhone();
        UUID friendId = jdbc.sql("insert into users (phone_e164, verified_at) values (?, now()) returning id")
                .param(friend).query(UUID.class).single();
        member(a, friendId, before, null);
        String holders = """
                [{"name": "Yazid Aqel"}, {"name": "Sara Bennani", "phone": "%s", "pays": true}]""".formatted(friend);
        ResultActions split = mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + paid.token())
                .header("X-Admission-Token", admission).contentType(MediaType.APPLICATION_JSON).content("""
                        {"event_id": %d, "channel": "APP",
                         "items": [{"price_category_id": "%s", "qty": 2, "holders": %s}]}"""
                        .formatted(p.room().event(), p.room().price(), holders)));
        assertThat(refused(split, "NOT_ELIGIBLE")).containsExactly(Map.entry("field", "items[0].holders[1]"));

        jdbc.sql("update memberships set paid_until = ? where user_id = ?")
                .params(utc(p.start().plusSeconds(1)), friendId).update();
        mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + paid.token())
                .header("X-Admission-Token", admission).contentType(MediaType.APPLICATION_JSON).content("""
                        {"event_id": %d, "channel": "APP",
                         "items": [{"price_category_id": "%s", "qty": 2, "holders": %s}]}"""
                        .formatted(p.room().event(), p.room().price(), holders))).andExpect(status().isCreated());
    }

    // ---------- GET /fan/me/membership and GET /org/members/summary ----------

    private String membership(Fan f, Caller a) throws Exception {
        return body(mvc.perform(get("/fan/me/membership").param("organizer", a.org().toString())
                .header("Authorization", "Bearer " + f.token())).andExpect(status().isOk()));
    }

    private String summary(Caller a) throws Exception {
        return body(call(caller(a.org(), "OPS"), get("/org/members/summary")).andExpect(status().isOk()));
    }

    @Test
    void membershipAndSummaryShowPaidUntilAndPaidRequired() throws Exception {
        Caller a = admin();
        Instant now = Instant.now();
        Instant until = Instant.parse("2099-07-01T00:00:00Z");
        Fan paid = fan();
        member(a, paid.id(), now.minus(Duration.ofDays(3)), until);
        Fan free = fan();
        member(a, free.id(), now.minus(Duration.ofDays(3)), null);
        Fan expired = fan();
        member(a, expired.id(), now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(1)));
        Fan left = fan();
        member(a, left.id(), now.minus(Duration.ofDays(3)), until);
        jdbc.sql("update memberships set left_at = now() where user_id = ?").param(left.id()).update();

        String json = membership(paid, a);
        assertThat(JsonPath.<Boolean>read(json, "$.paid_required")).isFalse();
        assertThat(Instant.parse(JsonPath.read(json, "$.member.paid_until"))).isEqualTo(until);
        assertThat((Object) JsonPath.read(membership(free, a), "$.member.paid_until")).isNull();
        json = summary(a);
        assertThat(JsonPath.<Integer>read(json, "$.members")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(json, "$.paid")).isEqualTo(1); // live, paid past now
        assertThat(JsonPath.<Boolean>read(json, "$.paid_required")).isFalse();

        membershipProduct(a);
        assertThat(JsonPath.<Boolean>read(membership(free, a), "$.paid_required")).isTrue();
        assertThat(JsonPath.<Boolean>read(membership(fan(), a), "$.paid_required")).isTrue(); // member null
        assertThat(JsonPath.<Boolean>read(summary(a), "$.paid_required")).isTrue();
        assertThat(JsonPath.<Boolean>read(summary(admin()), "$.paid_required")).isFalse(); // another club's product
    }
}
