package ma.jomhor.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.notifications.application.TicketMessenger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-33): docs/04-api.md §Organizer /org/campaigns, §Campaigns; plan lot-4 Q62, Q63, Q101, Q131-Q138. The
 * job (CampaignService.runDue) and the outbox (FanMessageService.deliverDue) are called directly; the push and
 * WhatsApp providers are the IntegrationTest mocks.
 */
@ExtendWith(OutputCaptureExtension.class)
class CampaignApiTest extends CrmTest {

    private static final String[] ROW = {"id", "channel", "audience", "event_id", "title", "status", "scheduled_at",
            "started_at", "finished_at", "created_by", "recipients", "sent", "failed"};
    private static final String[] DETAIL = {"id", "channel", "audience", "event_id", "title", "status", "scheduled_at",
            "started_at", "finished_at", "created_by", "recipients", "sent", "failed", "body", "audience_estimate"};
    private static final String ARABIC = "الأبواب تفتح "
            + "على السادسة"; // "the gates open at six"

    // ---------- calls ----------

    private static String json(String channel, String audience, Integer event, String title, String body) {
        return "{\"channel\": \"%s\", \"audience\": \"%s\", \"event_id\": %s, \"title\": \"%s\", \"body\": \"%s\"}"
                .formatted(channel, audience, event, title, body);
    }

    private ResultActions create(Caller a, String json) throws Exception {
        return call(a, post("/org/campaigns"), json);
    }

    private String create(Caller a, String channel, String audience, Integer event) throws Exception {
        return JsonPath.read(body(create(a, json(channel, audience, event, "Titre", "Corps"))
                .andExpect(status().isCreated())), "$.id");
    }

    private String detail(Caller a, String id) throws Exception {
        String json = body(call(a, get("/org/campaigns/" + id)).andExpect(status().isOk()));
        assertKeys(json, "$", DETAIL);
        return json;
    }

    private ResultActions send(Caller a, String id) throws Exception {
        return call(a, post("/org/campaigns/" + id + "/send"));
    }

    /** Send now, then one job run (the snapshot). */
    private void sendNow(Caller a, String id) throws Exception {
        send(a, id).andExpect(status().isOk());
        campaigns.runDue();
    }

    private List<Map<String, Object>> rows(String campaign) {
        return jdbc.sql("""
                        select user_id, channel::text as channel, status::text as status, attempts, last_error
                        from fan_messages where campaign_id = ?::uuid order by user_id""")
                .param(campaign).query().listOfRows();
    }

    private List<UUID> recipients(String campaign) {
        return rows(campaign).stream().map(r -> (UUID) r.get("user_id")).toList();
    }

    private String campaignStatus(String id) {
        return jdbc.sql("select status::text from campaigns where id = ?::uuid").param(id).query(String.class).single();
    }

    private static long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private List<PushSender.Push> pushesTo(String token) {
        return pushes().stream().filter(p -> p.token().equals(token)).toList();
    }

    /** A member with a live token and the push opt-in; returns the token. */
    private String pushMember(Club c, UUID user) {
        member(c, user, true, false, false);
        return token(user, true);
    }

    // ---------- create ----------

    @Test
    void createReturnsTheGetShapeAsDraftTrimmedWithArabicKeptByteExact() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        int e = event(c, "ON_SALE");
        String created = body(create(a, json("PUSH", "MEMBERS", e, "  Derby ce soir  ", " " + ARABIC + " "))
                .andExpect(status().isCreated()));
        assertKeys(created, "$", DETAIL);
        assertKeys(created, "$.created_by", "id", "name");
        String id = JsonPath.read(created, "$.id");
        assertThat((String) JsonPath.read(created, "$.status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(created, "$.title")).isEqualTo("Derby ce soir");
        assertThat((String) JsonPath.read(created, "$.body")).isEqualTo(ARABIC);
        assertThat((Integer) JsonPath.read(created, "$.event_id")).isEqualTo(e);
        assertThat((String) JsonPath.read(created, "$.created_by.name")).isEqualTo("Test");
        assertThat((String) JsonPath.read(created, "$.created_by.id")).isEqualTo(jdbc.sql(
                "select s.id::text from staff s join campaigns c on c.created_by = s.id where c.id = ?::uuid")
                .param(id).query(String.class).single());
        assertThat(number(created, "$.recipients") + number(created, "$.sent") + number(created, "$.failed")).isZero();
        assertThat((Object) JsonPath.read(created, "$.scheduled_at")).isNull();
        assertThat(detail(a, id)).isEqualTo(created);
        assertThat(jdbc.sql("select body from campaigns where id = ?::uuid").param(id).query(String.class).single())
                .isEqualTo(ARABIC);

        String list = body(call(a, get("/org/campaigns")).andExpect(status().isOk()));
        assertThat((List<?>) JsonPath.read(list, "$")).hasSize(1);
        assertKeys(list, "$[0]", ROW);
        assertKeys(list, "$[0].created_by", "id", "name");
    }

    @Test
    void titleAndBodyAreCountedInCodePoints() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "OPS");
        String smiley = "😀"; // one code point, two UTF-16 units
        create(a, json("WHATSAPP", "MEMBERS", null, smiley.repeat(60), "ب".repeat(500)))
                .andExpect(status().isCreated());
        create(a, json("WHATSAPP", "MEMBERS", null, smiley.repeat(61), "Corps"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.title").exists());
        create(a, json("WHATSAPP", "MEMBERS", null, "Titre", "ب".repeat(501)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.body").exists());
        create(a, json("WHATSAPP", "MEMBERS", null, "   ", "Corps"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.title").exists());
        create(a, "{\"channel\": \"SMS\", \"audience\": \"MEMBERS\", \"title\": \"T\", \"body\": \"B\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.channel").exists());
        create(a, "{\"audience\": \"MEMBERS\", \"title\": \"T\", \"body\": \"B\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.channel").exists());
        assertThat(jdbc.sql("select count(*) from campaigns where organizer_id = ?").param(c.org())
                .query(Integer.class).single()).isOne();
    }

    @Test
    void theEventMustBeThisOrganizersAndNotDraftAndHoldersNeedOne() throws Exception {
        Club c = club();
        Club other = club();
        Caller a = caller(c.org(), "ADMIN");
        for (Integer e : new Integer[] {null, event(other, "ON_SALE"), event(c, "DRAFT"), 999_999_999}) {
            create(a, json("WHATSAPP", "EVENT_HOLDERS", e, "Portes", "Ouverture des portes"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.fields.event_id").exists());
        }
        create(a, json("PUSH", "MEMBERS", event(other, "ON_SALE"), "T", "B")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.event_id").exists());
        create(a, json("WHATSAPP", "EVENT_HOLDERS", event(c, "CLOSED"), "T", "B")).andExpect(status().isCreated()); // Q136
        create(a, json("PUSH", "MEMBERS", null, "T", "B")).andExpect(status().isCreated());
    }

    // ---------- PATCH ----------

    @Test
    void patchChangesOnlyWhatIsSentAndOnlyADraft() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        int e = event(c, "ON_SALE");
        String id = create(a, "PUSH", "MEMBERS", e);
        String patched = body(call(a, patch("/org/campaigns/" + id), "{\"title\": \" Nouveau \", \"event_id\": null}")
                .andExpect(status().isOk()));
        assertKeys(patched, "$", DETAIL);
        assertThat((String) JsonPath.read(patched, "$.title")).isEqualTo("Nouveau");
        assertThat((String) JsonPath.read(patched, "$.body")).isEqualTo("Corps");
        assertThat((Integer) JsonPath.read(patched, "$.event_id")).isEqualTo(e); // null = unchanged (Q101)
        assertThat((String) JsonPath.read(patched, "$.channel")).isEqualTo("PUSH");

        call(a, patch("/org/campaigns/" + id), "{\"channel\": \"WHATSAPP\", \"audience\": \"EVENT_HOLDERS\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.audience").value("EVENT_HOLDERS"));

        String bare = create(a, "PUSH", "MEMBERS", null);
        call(a, patch("/org/campaigns/" + bare), "{\"audience\": \"EVENT_HOLDERS\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.fields.event_id").exists());
        call(a, patch("/org/campaigns/" + bare), "{\"title\": \"" + "x".repeat(61) + "\"}")
                .andExpect(status().isUnprocessableContent());
        assertThat(JsonPath.<String>read(detail(a, bare), "$.audience")).isEqualTo("MEMBERS");

        send(a, id).andExpect(status().isOk());
        call(a, patch("/org/campaigns/" + id), "{\"title\": \"Trop tard\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_STATUS"))
                .andExpect(jsonPath("$.details.status").value("SCHEDULED"));
        call(a, patch("/org/campaigns/" + UUID.randomUUID()), "{\"title\": \"T\"}").andExpect(status().isNotFound());
    }

    // ---------- send ----------

    @Test
    void sendTakesNoBodyOrAnEmptyOneAndClampsThePastToNow() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        Instant before = Instant.now();
        String none = create(a, "WHATSAPP", "MEMBERS", null);
        String sent = body(send(a, none).andExpect(status().isOk()));
        assertKeys(sent, "$", DETAIL);
        assertThat((String) JsonPath.read(sent, "$.status")).isEqualTo("SCHEDULED");
        assertThat(Instant.parse(JsonPath.read(sent, "$.scheduled_at"))).isCloseTo(before, within(10, ChronoUnit.SECONDS));
        send(a, none).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPAIGN_STATUS"))
                .andExpect(jsonPath("$.details.status").value("SCHEDULED"));

        String empty = create(a, "WHATSAPP", "MEMBERS", null);
        call(a, post("/org/campaigns/" + empty + "/send"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        String past = create(a, "WHATSAPP", "MEMBERS", null);
        String json = body(call(a, post("/org/campaigns/" + past + "/send"), "{\"scheduled_at\": \"2020-01-01T00:00:00Z\"}")
                .andExpect(status().isOk()));
        assertThat(Instant.parse(JsonPath.read(json, "$.scheduled_at"))).isCloseTo(Instant.now(), within(10, ChronoUnit.SECONDS));

        String later = create(a, "WHATSAPP", "MEMBERS", null);
        call(a, post("/org/campaigns/" + later + "/send"),
                "{\"scheduled_at\": \"%s\"}".formatted(Instant.now().plus(Duration.ofDays(31))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.scheduled_at").exists());
        assertThat(campaignStatus(later)).isEqualTo("DRAFT");
        Instant at = Instant.now().plus(Duration.ofDays(29)).truncatedTo(ChronoUnit.SECONDS);
        call(a, post("/org/campaigns/" + later + "/send"), "{\"scheduled_at\": \"%s\"}".formatted(at))
                .andExpect(status().isOk());
        assertThat(Instant.parse(JsonPath.read(detail(a, later), "$.scheduled_at"))).isEqualTo(at);
        campaigns.runDue();
        assertThat(campaignStatus(later)).isEqualTo("SCHEDULED"); // not due yet

        send(a, UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    // ---------- audience ----------

    @Test
    void pushMembersAreOptedInLiveMembersWithALiveTokenOfThisOrganizerOnly() throws Exception {
        Club c = club();
        Club b = club();
        Caller a = caller(c.org(), "ADMIN");
        UUID reached = user();
        pushMember(c, reached);
        UUID noToken = user();
        member(c, noToken, true, true, false);
        UUID revokedOnly = user();
        member(c, revokedOnly, true, false, false);
        token(revokedOnly, false);
        UUID notOptedIn = user();
        member(c, notOptedIn, false, true, false);
        token(notOptedIn, true);
        UUID left = user();
        member(c, left, true, false, true);
        token(left, true);
        UUID otherClub = user(); // org B's opted-in member with a live token
        pushMember(b, otherClub);

        String id = create(a, "PUSH", "MEMBERS", null);
        assertThat(number(detail(a, id), "$.audience_estimate")).isOne();
        sendNow(a, id);
        assertThat(recipients(id)).containsExactly(reached);
        assertThat(rows(id)).allSatisfy(r -> assertThat(r).containsEntry("channel", "PUSH"));
        String after = detail(a, id);
        assertThat(number(after, "$.recipients")).isOne();
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("SENDING");
        assertThat((Object) JsonPath.read(after, "$.started_at")).isNotNull();

        // Org B's campaign reaches org B's opt-ins only.
        Caller ob = caller(b.org(), "ADMIN");
        String bId = create(ob, "PUSH", "MEMBERS", null);
        sendNow(ob, bId);
        assertThat(recipients(bId)).containsExactly(otherClub);
    }

    @Test
    void whatsappMembersNeedTheWhatsappOptInOnlyNoToken() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        UUID whatsapp = user();
        member(c, whatsapp, false, true, false);
        UUID pushOnly = user();
        pushMember(c, pushOnly);
        String id = create(a, "WHATSAPP", "MEMBERS", null);
        assertThat(number(detail(a, id), "$.audience_estimate")).isOne();
        sendNow(a, id);
        assertThat(recipients(id)).containsExactly(whatsapp);
        assertThat(rows(id)).allSatisfy(r -> assertThat(r).containsEntry("channel", "WHATSAPP"));
    }

    @Test
    void eventHoldersAreValidHoldersOncePerUserMembersOrNot() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        int e = event(c, "ON_SALE");
        int otherEvent = event(c, "ON_SALE");
        UUID holder = user();
        ticket(c, e, holder, "VALID");
        UUID memberHolder = user();
        member(c, memberHolder, false, false, false);
        ticket(c, e, memberHolder, "VALID");
        UUID sender = user(); // transferred away: the old ticket is CANCELLED, the recipient's VALID
        ticket(c, e, sender, "CANCELLED");
        UUID recipient = user();
        ticket(c, e, recipient, "VALID");
        UUID blocked = user();
        ticket(c, e, blocked, "BLOCKED");
        UUID refunded = user();
        ticket(c, e, refunded, "REFUNDED");
        UUID twoTickets = user();
        ticket(c, e, twoTickets, "VALID");
        ticket(c, e, twoTickets, "VALID");
        UUID elsewhere = user();
        ticket(c, otherEvent, elsewhere, "VALID");
        UUID memberOnly = user();
        member(c, memberOnly, true, true, false);

        String id = create(a, "WHATSAPP", "EVENT_HOLDERS", e);
        assertThat(number(detail(a, id), "$.audience_estimate")).isEqualTo(4);
        sendNow(a, id);
        assertThat(recipients(id)).containsExactlyInAnyOrder(holder, memberHolder, recipient, twoTickets);
        assertThat(number(detail(a, id), "$.recipients")).isEqualTo(4);
    }

    // ---------- the job ----------

    @Test
    void theSnapshotIsTakenOnceEvenWhenTheJobRunsAgainOrTwiceAtOnce() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        for (int i = 0; i < 3; i++) {
            member(c, user(), false, true, false);
        }
        String id = create(a, "WHATSAPP", "MEMBERS", null);
        sendNow(a, id);
        assertThat(rows(id)).hasSize(3);
        jdbc.sql("update campaigns set status = 'SCHEDULED' where id = ?::uuid").param(id).update();
        assertThat(campaigns.runDue()).isOne();
        assertThat(rows(id)).hasSize(3); // on conflict do nothing

        String twice = create(a, "WHATSAPP", "MEMBERS", null);
        send(a, twice).andExpect(status().isOk());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> runs = List.of(pool.submit(() -> {
                start.await();
                return campaigns.runDue();
            }), pool.submit(() -> {
                start.await();
                return campaigns.runDue();
            }));
            start.countDown();
            int started = 0;
            for (Future<Integer> r : runs) {
                started += r.get();
            }
            assertThat(started).isOne();
        }
        assertThat(rows(twice)).hasSize(3);
        assertThat(campaignStatus(twice)).isEqualTo("SENDING");
    }

    @Test
    void aSnapshotThatFailsDoesNotBlockTheNextCampaign(CapturedOutput output) throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        member(c, user(), false, true, false);
        String broken = create(a, "WHATSAPP", "MEMBERS", null);
        String fine = create(a, "WHATSAPP", "MEMBERS", null);
        send(a, broken).andExpect(status().isOk());
        send(a, fine).andExpect(status().isOk());
        // The broken one is due first; a test-only trigger makes its SENDING update fail.
        jdbc.sql("update campaigns set scheduled_at = now() - interval '2 minutes' where id = ?::uuid").param(broken)
                .update();
        jdbc.sql("update campaigns set scheduled_at = now() - interval '1 minute' where id = ?::uuid").param(fine)
                .update();
        jdbc.sql("create function test_campaign_fail() returns trigger language plpgsql as "
                + "$$ begin raise exception 'test: snapshot refused'; end $$").update();
        jdbc.sql("create trigger test_campaign_fail before update on campaigns for each row when (new.id = '"
                + UUID.fromString(broken) + "') execute function test_campaign_fail()").update();
        try {
            assertThat(campaigns.runDue()).isOne();
        } finally {
            jdbc.sql("drop trigger test_campaign_fail on campaigns").update();
            jdbc.sql("drop function test_campaign_fail()").update();
        }
        assertThat(campaignStatus(broken)).isEqualTo("SCHEDULED");
        assertThat(rows(broken)).isEmpty();
        assertThat(campaignStatus(fine)).isEqualTo("SENDING");
        assertThat(rows(fine)).hasSize(1);
        assertThat(output.getAll()).contains("Campaign " + broken + " snapshot failed")
                .doesNotContain("snapshot refused");
        assertThat(campaigns.runDue()).isOne(); // retried next run
        assertThat(rows(broken)).hasSize(1);
    }

    @Test
    void aMemberWhoLeavesOrOptsOutAfterTheSnapshotIsSkippedWithoutRetry() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        UUID stays = user();
        String staysToken = pushMember(c, stays);
        UUID leaves = user();
        String leavesToken = pushMember(c, leaves);
        UUID optsOut = user();
        String optsOutToken = pushMember(c, optsOut);
        String id = create(a, "PUSH", "MEMBERS", null);
        sendNow(a, id);
        assertThat(rows(id)).hasSize(3);
        jdbc.sql("update memberships set left_at = now(), marketing_push = false where organizer_id = ? and user_id = ?")
                .params(c.org(), leaves).update();
        jdbc.sql("update memberships set marketing_push = false where organizer_id = ? and user_id = ?")
                .params(c.org(), optsOut).update();

        messages.deliverDue();
        assertThat(pushesTo(staysToken)).hasSize(1);
        assertThat(pushesTo(leavesToken)).isEmpty();
        assertThat(pushesTo(optsOutToken)).isEmpty();
        for (UUID skipped : List.of(leaves, optsOut)) {
            Map<String, Object> r = rows(id).stream().filter(x -> x.get("user_id").equals(skipped)).findFirst().orElseThrow();
            assertThat(r).containsEntry("status", "FAILED").containsEntry("last_error", "OPTED_OUT");
            assertThat(((Number) r.get("attempts")).intValue()).isOne();
        }
        jdbc.sql("update fan_messages set next_attempt_at = now() - interval '1 hour' where campaign_id = ?::uuid")
                .param(id).update();
        messages.deliverDue();
        assertThat(pushes()).hasSize(1); // no retry

        campaigns.runDue();
        String done = detail(a, id);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("SENT");
        assertThat((Object) JsonPath.read(done, "$.finished_at")).isNotNull();
        assertThat(number(done, "$.recipients")).isEqualTo(3);
        assertThat(number(done, "$.sent")).isOne();
        assertThat(number(done, "$.failed")).isEqualTo(2);
    }

    @Test
    void anEventHoldersRowIsSentEvenIfTheTicketMovedAfterTheSnapshot() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        int e = event(c, "ON_SALE");
        UUID holder = user();
        UUID t = ticket(c, e, holder, "VALID");
        String id = create(a, "WHATSAPP", "EVENT_HOLDERS", e);
        sendNow(a, id);
        jdbc.sql("update tickets set status = 'CANCELLED' where id = ?").param(t).update(); // transferred away
        messages.deliverDue();
        assertThat(rows(id)).singleElement().satisfies(r -> assertThat(r).containsEntry("status", "SENT"));
        assertThat(templates()).singleElement().satisfies(m -> assertThat(m.userId()).isEqualTo(holder));
    }

    @Test
    void anEmptyAudienceIsSentInTheSameRun() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        String id = create(a, "PUSH", "MEMBERS", null);
        sendNow(a, id);
        String done = detail(a, id);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("SENT");
        assertThat((Object) JsonPath.read(done, "$.started_at")).isNotNull();
        assertThat((Object) JsonPath.read(done, "$.finished_at")).isNotNull();
        assertThat(number(done, "$.recipients")).isZero();
    }

    @Test
    void transactionalMessagesGoFirstAndARunSendsAtMost100() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        int base = ThreadLocalRandom.current().nextInt(10_000);
        jdbc.sql("""
                        with u as (insert into users (phone_e164)
                                   select '+2129' || lpad(?::text, 4, '0') || lpad(g::text, 4, '0')
                                   from generate_series(1, 150) g returning id)
                        insert into memberships (organizer_id, user_id, number, marketing_whatsapp)
                        select ?, id, row_number() over (), true from u""")
                .params(base, c.org()).update();
        String id = create(a, "WHATSAPP", "MEMBERS", null);
        sendNow(a, id);
        assertThat(rows(id)).hasSize(150);

        int e = event(c, "ON_SALE");
        UUID buyer = user();
        String buyerToken = token(buyer, true);
        UUID order = jdbc.sql("insert into orders (organizer_id, event_id, user_id, channel, status, total_mad, "
                        + "reserved_until) values (?, ?, ?, 'APP', 'PAID', 5000, now()) returning id")
                .params(c.org(), e, buyer).query(UUID.class).single();
        jdbc.sql("insert into fan_messages (organizer_id, user_id, channel, kind, order_id) "
                + "values (?, ?, 'PUSH', 'ORDER_PAID', ?)").params(c.org(), buyer, order).update();

        assertThat(messages.deliverDue()).isEqualTo(100);
        assertThat(pushesTo(buyerToken)).singleElement()
                .satisfies(p -> assertThat(p.path()).isEqualTo("/orders/" + order));
        assertThat(rows(id).stream().filter(r -> r.get("status").equals("SENT"))).hasSize(99);
        campaigns.runDue();
        assertThat(campaignStatus(id)).isEqualTo("SENDING");
        assertThat(messages.deliverDue()).isEqualTo(51);
        campaigns.runDue();
        assertThat(campaignStatus(id)).isEqualTo("SENT");
    }

    // ---------- content ----------

    @Test
    void pushCarriesTheTypedTextAndOpensTheEventOrHome(CapturedOutput output) throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        int e = event(c, "ON_SALE");
        UUID fan = user();
        String token = pushMember(c, fan);
        String withEvent = JsonPath.read(body(create(a, json("PUSH", "MEMBERS", e, "Derby", ARABIC))
                .andExpect(status().isCreated())), "$.id");
        sendNow(a, withEvent);
        messages.deliverDue();
        String home = JsonPath.read(body(create(a, json("PUSH", "MEMBERS", null, "Boutique", "Nouveau maillot"))
                .andExpect(status().isCreated())), "$.id");
        sendNow(a, home);
        messages.deliverDue();
        assertThat(pushesTo(token)).containsExactly(new PushSender.Push(token, "Derby", ARABIC, "/events/" + e),
                new PushSender.Push(token, "Boutique", "Nouveau maillot", "/"));
        assertThat(output.getAll()).doesNotContain(phone(fan));
    }

    @Test
    void whatsappUsesClubNewsForMembersAndEventInfoForHolders(CapturedOutput output) throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        int e = event(c, "ON_SALE");
        UUID fan = user();
        member(c, fan, false, true, false);
        UUID holder = user();
        ticket(c, e, holder, "VALID");
        String news = JsonPath.read(body(create(a, json("WHATSAPP", "MEMBERS", null, "Nouvelles", "Le club recrute"))
                .andExpect(status().isCreated())), "$.id");
        String info = JsonPath.read(body(create(a, json("WHATSAPP", "EVENT_HOLDERS", e, "Portes",
                "Ouverture des portes à 18 h")).andExpect(status().isCreated())), "$.id");
        sendNow(a, news);
        sendNow(a, info);
        messages.deliverDue();
        List<TicketMessenger.Template> sent = templates();
        assertThat(sent).containsExactlyInAnyOrder(
                new TicketMessenger.Template(fan, phone(fan), "fr", "club_news", List.of("Nouvelles", "Le club recrute")),
                new TicketMessenger.Template(holder, phone(holder), "fr", "event_info",
                        List.of("Portes", "Ouverture des portes à 18 h")));
        assertThat(sent).allSatisfy(t -> assertThat(String.join("|", t.params()))
                .doesNotContain(phone(fan), phone(holder), "Fan Un"));
        assertThat(output.getAll()).doesNotContain(phone(fan), phone(holder), "Fan Un");
    }

    // ---------- cancel ----------

    @Test
    void cancelOnlyAScheduledCampaignWhichThenNeverSnapshots() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        member(c, user(), false, true, false);
        String id = create(a, "WHATSAPP", "MEMBERS", null);
        call(a, post("/org/campaigns/" + id + "/cancel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_STATUS")).andExpect(jsonPath("$.details.status").value("DRAFT"));
        call(a, post("/org/campaigns/" + id + "/send"), "{\"scheduled_at\": \"%s\"}"
                .formatted(Instant.now().plus(Duration.ofDays(1)))).andExpect(status().isOk());
        String cancelled = body(call(a, post("/org/campaigns/" + id + "/cancel")).andExpect(status().isOk()));
        assertKeys(cancelled, "$", DETAIL);
        assertThat((String) JsonPath.read(cancelled, "$.status")).isEqualTo("CANCELLED");
        jdbc.sql("update campaigns set scheduled_at = now() - interval '1 minute' where id = ?::uuid").param(id).update();
        campaigns.runDue();
        assertThat(campaignStatus(id)).isEqualTo("CANCELLED");
        assertThat(rows(id)).isEmpty();

        String sent = create(a, "PUSH", "MEMBERS", null);
        sendNow(a, sent);
        assertThat(campaignStatus(sent)).isEqualTo("SENT");
        call(a, post("/org/campaigns/" + sent + "/cancel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.status").value("SENT"));
    }

    // ---------- roles and tenancy ----------

    @Test
    void financeReadsOnlyAndCashierGateSupervisorGetNothing() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "ADMIN");
        String id = create(a, "WHATSAPP", "MEMBERS", null);
        Caller finance = caller(c.org(), "FINANCE");
        call(finance, get("/org/campaigns")).andExpect(status().isOk());
        call(finance, get("/org/campaigns/" + id)).andExpect(status().isOk());
        call(finance, get("/org/members/summary")).andExpect(status().isOk());
        for (String role : List.of("FINANCE", "CASHIER", "GATE", "SUPERVISOR")) {
            Caller r = role.equals("FINANCE") ? finance : caller(c.org(), role);
            if (!role.equals("FINANCE")) {
                call(r, get("/org/campaigns")).andExpect(status().isForbidden());
                call(r, get("/org/campaigns/" + id)).andExpect(status().isForbidden());
                call(r, get("/org/members/summary")).andExpect(status().isForbidden());
            }
            create(r, json("WHATSAPP", "MEMBERS", null, "T", "B")).andExpect(status().isForbidden());
            call(r, patch("/org/campaigns/" + id), "{\"title\": \"T\"}").andExpect(status().isForbidden());
            send(r, id).andExpect(status().isForbidden());
            call(r, post("/org/campaigns/" + id + "/cancel")).andExpect(status().isForbidden());
        }
        assertThat(campaignStatus(id)).isEqualTo("DRAFT");
    }

    @Test
    void anotherOrganizersCampaignIsNotFound() throws Exception {
        Caller a = caller(club().org(), "ADMIN");
        Caller b = caller(club().org(), "ADMIN");
        String theirs = create(b, "WHATSAPP", "MEMBERS", null);
        call(a, get("/org/campaigns/" + theirs)).andExpect(status().isNotFound());
        call(a, patch("/org/campaigns/" + theirs), "{\"title\": \"T\"}").andExpect(status().isNotFound());
        send(a, theirs).andExpect(status().isNotFound());
        call(a, post("/org/campaigns/" + theirs + "/cancel")).andExpect(status().isNotFound());
        assertThat((List<?>) JsonPath.read(body(call(a, get("/org/campaigns"))), "$")).isEmpty();
        assertThat(campaignStatus(theirs)).isEqualTo("DRAFT");
    }

    // ---------- audit ----------

    @Test
    void createScheduleAndCancelAreAuditedWithoutTheText() throws Exception {
        Club c = club();
        Caller a = caller(c.org(), "OPS");
        String id = create(a, "PUSH", "MEMBERS", null);
        call(a, patch("/org/campaigns/" + id), "{\"title\": \"Autre\"}").andExpect(status().isOk());
        String at = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS).toString();
        call(a, post("/org/campaigns/" + id + "/send"), "{\"scheduled_at\": \"%s\"}".formatted(at)).andExpect(status().isOk());
        call(a, post("/org/campaigns/" + id + "/cancel")).andExpect(status().isOk());
        List<Map<String, Object>> audit = jdbc.sql("""
                        select action, actor_type, payload::text as payload from audit_log
                        where organizer_id = ? and target = ? order by id""")
                .params(c.org(), id).query().listOfRows();
        assertThat(audit).extracting(r -> r.get("action"))
                .containsExactly("campaign.create", "campaign.schedule", "campaign.cancel");
        assertThat(audit).allSatisfy(r -> assertThat(r).containsEntry("actor_type", "STAFF"));
        assertThat(JsonPath.<Map<String, Object>>read((String) audit.get(0).get("payload"), "$"))
                .isEqualTo(Map.of("channel", "PUSH", "audience", "MEMBERS"));
        Map<String, Object> schedule = JsonPath.read((String) audit.get(1).get("payload"), "$");
        assertThat(schedule).containsOnlyKeys("scheduled_at");
        assertThat(OffsetDateTime.parse((String) schedule.get("scheduled_at")).toInstant()).isEqualTo(Instant.parse(at));
        assertThat(audit.get(2).get("payload")).isNull();
    }
}
