package ma.jomhor.crm;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import ma.jomhor.catalog.OrgApiTest;
import ma.jomhor.crm.application.CampaignService;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.notifications.application.TicketMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/** CRM fixtures: clubs, events, tickets, members, push tokens and campaigns as rows; the calls under test go through the API. */
abstract class CrmTest extends OrgApiTest {

    record Club(UUID org, UUID venue, short zone, UUID gate, UUID device, UUID staff) {}

    @Autowired
    protected FanMessageService messages;

    @Autowired
    protected CampaignService campaigns;

    /** What other test classes left due is sent first (to the mocks), so each test sees only its own. */
    @BeforeEach
    void drain() {
        campaigns.runDue();
        messages.deliverDue(Integer.MAX_VALUE);
        campaigns.runDue();
        clearInvocations(pushSender, ticketMessenger);
    }

    protected Club club(String settings) {
        UUID org = jdbc.sql("insert into organizers (name, settings) values (?, ?::jsonb) returning id")
                .params("Club " + UUID.randomUUID(), settings).query(UUID.class).single();
        UUID venue = jdbc.sql("insert into venues (organizer_id, name, capacity) values (?, 'Stade', 1000) returning id")
                .param(org).query(UUID.class).single();
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

    protected Club club() {
        return club("{}");
    }

    protected int event(Club c, String status) {
        Instant startsAt = Instant.now().plusSeconds(86_400);
        return jdbc.sql("insert into events (organizer_id, venue_id, event_type, title, starts_at, status, event_key_enc) "
                        + "values (?, ?, 'FOOTBALL_MATCH', 'Match', ?, ?::event_status, ?) returning id")
                .params(c.org(), c.venue(), startsAt.atOffset(ZoneOffset.UTC), status, new byte[32])
                .query(Integer.class).single();
    }

    /** A user who never logged in (no session, no token). */
    protected UUID user() {
        return jdbc.sql("insert into users (phone_e164) values (?) returning id").param(randomPhone())
                .query(UUID.class).single();
    }

    protected String phone(UUID user) {
        return jdbc.sql("select phone_e164 from users where id = ?").param(user).query(String.class).single();
    }

    /** A push token on a new session of the user: live, or revoked. Returns the token. */
    protected String token(UUID user, boolean live) {
        UUID session = jdbc.sql("insert into fan_sessions (user_id, expires_at, revoked_at) "
                        + "values (?, now() + interval '30 days', case when ? then null else now() end) returning id")
                .params(user, live).query(UUID.class).single();
        String token = "fcm-" + UUID.randomUUID();
        jdbc.sql("insert into push_tokens (user_id, session_id, token, platform) values (?, ?, ?, 'android')")
                .params(user, session, token).update();
        return token;
    }

    /** A live membership (or a left one) with these opt-ins. */
    protected void member(Club c, UUID user, boolean push, boolean whatsapp, boolean left) {
        jdbc.sql("""
                        insert into memberships (organizer_id, user_id, number, marketing_push, marketing_whatsapp,
                                                 left_at)
                        select ?, ?, coalesce(max(number), 0) + 1, ?, ?, case when ? then now() end
                        from memberships where organizer_id = ?""")
                .params(c.org(), user, push, whatsapp, left, c.org()).update();
    }

    /** A ticket of the event held by {@code holder} with this status, in its own paid order. */
    protected UUID ticket(Club c, int event, UUID holder, String status) {
        UUID price = jdbc.sql("insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota) "
                        + "values (?, ?, ?, ?, 5000, 100) returning id")
                .params(c.org(), event, c.zone(), "Cat " + UUID.randomUUID()).query(UUID.class).single();
        UUID order = jdbc.sql("insert into orders (organizer_id, event_id, user_id, channel, status, total_mad, "
                        + "reserved_until) values (?, ?, ?, 'APP', 'PAID', 5000, now()) returning id")
                .params(c.org(), event, holder).query(UUID.class).single();
        return jdbc.sql("insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, "
                        + "holder_user_id, holder_name, holder_initials, status) "
                        + "values (?, ?, ?, ?, ?, ?, 'Fan Un', 'F. U.', ?::ticket_status) returning id")
                .params(c.org(), order, event, c.zone(), price, holder, status).query(UUID.class).single();
    }

    /** One admitted IN scan of the ticket. */
    protected void scan(Club c, int event, UUID ticket) {
        jdbc.sql("insert into scans (scan_id, organizer_id, event_id, device_id, ticket_id, gate_id, staff_id, direction, "
                        + "result, token_version, device_time) values (uuidv7(), ?, ?, ?, ?, ?, ?, 'IN', 'OK', 1, now())")
                .params(c.org(), event, c.device(), ticket, c.gate(), c.staff()).update();
    }

    protected List<PushSender.Push> pushes() {
        var captor = ArgumentCaptor.forClass(PushSender.Push.class);
        verify(pushSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    protected List<TicketMessenger.Template> templates() {
        var captor = ArgumentCaptor.forClass(TicketMessenger.Template.class);
        verify(ticketMessenger, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }
}
