package ma.jomhor.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contract (BE-33): docs/04-api.md GET /fan/me/inbox: the caller's SENT PUSH campaign rows of every organizer, newest
 * first, at most 30, each with its organizer block (plan lot-4 Q129, Q130). Campaigns and rows are written as rows.
 */
class FanInboxApiTest extends CrmTest {

    private static final String CREST = "https://assets.jomhor.ma/crests/club.png";

    record Fan(UUID id, String token) {}

    private Fan fan() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        return new Fan(jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single(),
                token);
    }

    private UUID campaign(Club c, String channel, Integer event, String title) {
        return jdbc.sql("""
                        insert into campaigns (organizer_id, channel, audience, event_id, title, body, status, created_by)
                        values (?, ?::message_channel, 'MEMBERS', ?, ?, 'Corps', 'SENT', ?) returning id""")
                .params(c.org(), channel, event, title, c.staff()).query(UUID.class).single();
    }

    /** One fan_messages row of the campaign for the user, sent at {@code sentAt} if SENT. */
    private void row(Club c, UUID campaign, UUID user, String channel, String status, String error, Instant sentAt) {
        jdbc.sql("""
                        insert into fan_messages (organizer_id, user_id, channel, kind, campaign_id, status, last_error,
                                                  sent_at)
                        values (?, ?, ?::message_channel, 'CAMPAIGN', ?, ?::delivery_status, ?, ?)""")
                .params(c.org(), user, channel, campaign, status, error,
                        sentAt == null ? null : sentAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private String inbox(Fan f) throws Exception {
        return body(mvc.perform(get("/fan/me/inbox").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")));
    }

    @Test
    void sentPushCampaignsOfEveryOrganizerNewestFirstWithTheirOrganizer() throws Exception {
        Club listed = club("{\"crest_url\": \"" + CREST + "\"}");
        String slug = listed(listed.org());
        Club unlisted = club();
        Fan f = fan();
        Fan other = fan();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres rounds, not truncates
        int e = event(listed, "ON_SALE");
        UUID older = campaign(unlisted, "PUSH", null, "Ancien");
        row(unlisted, older, f.id(), "PUSH", "SENT", null, now.minusSeconds(60));
        UUID newer = campaign(listed, "PUSH", e, "Derby");
        row(listed, newer, f.id(), "PUSH", "SENT", null, now);
        row(listed, newer, other.id(), "PUSH", "SENT", null, now.plusSeconds(1)); // another user's row
        UUID whatsapp = campaign(listed, "WHATSAPP", null, "WhatsApp");
        row(listed, whatsapp, f.id(), "WHATSAPP", "SENT", null, now.plusSeconds(2));
        int i = 0;
        for (String[] s : new String[][] {{"PENDING", null}, {"FAILED", "OPTED_OUT"}, {"FAILED", "NO_DEVICE"}}) {
            row(listed, campaign(listed, "PUSH", null, "Non " + i++), f.id(), "PUSH", s[0], s[1], null);
        }

        String json = inbox(f);
        List<Map<String, Object>> messages = JsonPath.read(json, "$.messages");
        assertThat(messages).hasSize(2);
        assertKeys(json, "$", "messages");
        assertKeys(json, "$.messages[0]", "id", "organizer", "title", "body", "event_id", "sent_at");
        assertKeys(json, "$.messages[0].organizer", "id", "slug", "name", "crest_url");
        assertKeys(json, "$.messages[1].organizer", "id", "slug", "name", "crest_url");
        assertThat((String) JsonPath.read(json, "$.messages[0].id")).isEqualTo(newer.toString());
        assertThat((String) JsonPath.read(json, "$.messages[0].title")).isEqualTo("Derby");
        assertThat((String) JsonPath.read(json, "$.messages[0].body")).isEqualTo("Corps");
        assertThat((Integer) JsonPath.read(json, "$.messages[0].event_id")).isEqualTo(e);
        assertThat((String) JsonPath.read(json, "$.messages[0].organizer.id")).isEqualTo(listed.org().toString());
        assertThat((String) JsonPath.read(json, "$.messages[0].organizer.slug")).isEqualTo(slug);
        assertThat((String) JsonPath.read(json, "$.messages[0].organizer.crest_url")).isEqualTo(CREST);
        assertThat((String) JsonPath.read(json, "$.messages[1].id")).isEqualTo(older.toString());
        assertThat((Object) JsonPath.read(json, "$.messages[1].event_id")).isNull();
        assertThat((Object) JsonPath.read(json, "$.messages[1].organizer.slug")).isNull();
        assertThat((Object) JsonPath.read(json, "$.messages[1].organizer.crest_url")).isNull();
        assertThat(Instant.parse(JsonPath.read(json, "$.messages[1].sent_at")))
                .isEqualTo(now.minusSeconds(60).truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void atMostThirty() throws Exception {
        Club c = club();
        Fan f = fan();
        Instant now = Instant.now();
        for (int i = 0; i < 31; i++) {
            row(c, campaign(c, "PUSH", null, "N" + i), f.id(), "PUSH", "SENT", null, now.plusSeconds(i));
        }
        List<String> titles = JsonPath.read(inbox(f), "$.messages[*].title");
        assertThat(titles).hasSize(30).first().isEqualTo("N30");
        assertThat(titles).last().isEqualTo("N1");
    }

    @Test
    void emptyForAFanWithNothing() throws Exception {
        assertThat((List<?>) JsonPath.read(inbox(fan()), "$.messages")).isEmpty();
    }
}
