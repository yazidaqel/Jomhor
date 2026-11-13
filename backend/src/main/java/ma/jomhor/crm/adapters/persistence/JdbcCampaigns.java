package ma.jomhor.crm.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.common.Crests;
import ma.jomhor.crm.application.Campaigns;
import ma.jomhor.crm.domain.Campaign;
import ma.jomhor.notifications.application.PushTokens;
import ma.jomhor.notifications.domain.FanMessage.Channel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement filters on organizer_id, except the job's (claimDue, finishDrained: every organizer) and the inbox
 * (the caller's rows of every organizer). The audience, the counts and the inbox read memberships, tickets,
 * push_tokens, fan_sessions, fan_messages and organizers read-only (docs/01-architecture.md: the report-query
 * exception).
 */
@Repository
class JdbcCampaigns implements Campaigns {

    private static final String SELECT = """
            select c.id, c.organizer_id, c.channel::text as channel, c.audience::text as audience, c.event_id, c.title,
                   c.body, c.status::text as status, c.scheduled_at, c.started_at, c.finished_at, c.created_by,
                   s.name as created_by_name, n.recipients, n.sent, n.failed
            from campaigns c
            join staff s on s.id = c.created_by and s.organizer_id = c.organizer_id
            cross join lateral (select count(*) as recipients,
                                       count(*) filter (where m.status = 'SENT') as sent,
                                       count(*) filter (where m.status = 'FAILED') as failed
                                from fan_messages m
                                where m.organizer_id = c.organizer_id and m.campaign_id = c.id) n
            """;

    private final JdbcClient jdbc;
    private final Crests crests;

    JdbcCampaigns(JdbcClient jdbc, Crests crests) {
        this.jdbc = jdbc;
        this.crests = crests;
    }

    @Override
    public UUID insert(UUID org, UUID userId, Draft d) {
        return jdbc.sql("""
                        insert into campaigns (organizer_id, channel, audience, event_id, title, body, created_by)
                        values (:org, cast(:channel as message_channel), cast(:audience as campaign_audience), :event,
                                :title, :body, (select id from staff where organizer_id = :org and user_id = :user))
                        returning id
                        """)
                .param("org", org).param("user", userId).param("channel", d.channel().name())
                .param("audience", d.audience().name()).param("event", d.eventId()).param("title", d.title())
                .param("body", d.body())
                .query(UUID.class).single();
    }

    @Override
    public Optional<Campaign> get(UUID org, UUID id) {
        return jdbc.sql(SELECT + "where c.organizer_id = ? and c.id = ?").params(org, id)
                .query(JdbcCampaigns::campaign).optional();
    }

    @Override
    public List<Campaign> list(UUID org, int limit) {
        return jdbc.sql(SELECT + "where c.organizer_id = ? order by c.created_at desc, c.id desc limit ?")
                .params(org, limit).query(JdbcCampaigns::campaign).list();
    }

    @Override
    public Optional<Campaign.Status> lock(UUID org, UUID id) {
        return jdbc.sql("select status::text from campaigns where organizer_id = ? and id = ? for update")
                .params(org, id).query(String.class).optional().map(Campaign.Status::valueOf);
    }

    @Override
    public Optional<Draft> outbox(UUID org, UUID id) {
        return jdbc.sql("select channel::text as channel, audience::text as audience, event_id, title, body "
                        + "from campaigns where organizer_id = ? and id = ?")
                .params(org, id)
                .query((rs, n) -> new Draft(Channel.valueOf(rs.getString("channel")),
                        Campaign.Audience.valueOf(rs.getString("audience")), rs.getObject("event_id", Integer.class),
                        rs.getString("title"), rs.getString("body")))
                .optional();
    }

    @Override
    public void update(UUID org, UUID id, Draft d) {
        jdbc.sql("""
                        update campaigns set channel = cast(:channel as message_channel),
                                             audience = cast(:audience as campaign_audience), event_id = :event,
                                             title = :title, body = :body
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("id", id).param("channel", d.channel().name())
                .param("audience", d.audience().name()).param("event", d.eventId()).param("title", d.title())
                .param("body", d.body()).update();
    }

    @Override
    public void schedule(UUID org, UUID id, Instant at) {
        jdbc.sql("""
                        update campaigns set status = 'SCHEDULED', scheduled_at = coalesce(:at, now())
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("id", id).param("at", at == null ? null : at.atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public void cancel(UUID org, UUID id) {
        jdbc.sql("update campaigns set status = 'CANCELLED' where organizer_id = ? and id = ?").params(org, id).update();
    }

    /**
     * The one audience query (docs/04-api.md §Campaigns Audience), {@code :org} and {@code :event} bound: MEMBERS =
     * live members with the channel's opt-in; EVENT_HOLDERS = holders of a VALID ticket of the event, member or not;
     * PUSH also needs a live push token of the user.
     */
    private static String audienceSql(Campaign c) {
        String base = c.audience() == Campaign.Audience.MEMBERS
                ? "select m.user_id from memberships m where m.organizer_id = :org and m.left_at is null and m."
                        + (c.channel() == Channel.PUSH ? "marketing_push" : "marketing_whatsapp")
                : """
                        select distinct t.holder_user_id as user_id from tickets t
                        where t.organizer_id = :org and t.event_id = :event and t.status = 'VALID'
                          and t.holder_user_id is not null""";
        return "select a.user_id from (" + base + ") a" + (c.channel() == Channel.PUSH ? " where " + PushTokens.liveTokenOf("a.user_id") : "");
    }

    @Override
    public long audienceSize(Campaign c) {
        return jdbc.sql("select count(*) from (" + audienceSql(c) + ") r")
                .param("org", c.organizerId()).param("event", c.eventId()).query(Long.class).single();
    }

    @Override
    public List<UUID> audience(Campaign c) {
        return jdbc.sql(audienceSql(c))
                .param("org", c.organizerId()).param("event", c.eventId()).query(UUID.class).list();
    }

    @Override
    public Optional<Campaign> claimDue(Collection<UUID> skip) {
        record Ref(UUID org, UUID id) {}
        return jdbc.sql("""
                        select organizer_id, id from campaigns
                        where status = 'SCHEDULED' and scheduled_at <= now() and id <> all(cast(? as uuid[]))
                        order by scheduled_at, id
                        limit 1
                        for update skip locked""")
                .param(skip.toArray(UUID[]::new))
                .query((rs, n) -> new Ref(rs.getObject("organizer_id", UUID.class), rs.getObject("id", UUID.class)))
                .optional().flatMap(r -> get(r.org(), r.id()));
    }

    @Override
    public void start(UUID org, UUID id) {
        jdbc.sql("update campaigns set status = 'SENDING', started_at = now() where organizer_id = ? and id = ?")
                .params(org, id).update();
    }

    @Override
    public List<UUID> finishDrained() {
        return jdbc.sql("""
                        update campaigns c set status = 'SENT', finished_at = now()
                        where c.status = 'SENDING'
                          and not exists (select 1 from fan_messages m
                                          where m.organizer_id = c.organizer_id and m.campaign_id = c.id
                                            and m.status = 'PENDING')
                        returning c.id
                        """)
                .query(UUID.class).list();
    }

    @Override
    public List<InboxMessage> inbox(UUID userId, int limit) {
        return jdbc.sql("""
                        select c.id, o.id as organizer_id, o.slug, o.name, o.settings ->> 'crest_url' as crest_url,
                               c.title, c.body, c.event_id, m.sent_at
                        from fan_messages m
                        join campaigns c on c.id = m.campaign_id and c.organizer_id = m.organizer_id
                        join organizers o on o.id = m.organizer_id
                        where m.user_id = ? and m.kind = 'CAMPAIGN' and m.channel = 'PUSH' and m.status = 'SENT'
                        order by m.sent_at desc, m.id
                        limit ?
                        """)
                .params(userId, limit)
                .query((rs, n) -> new InboxMessage(rs.getObject("id", UUID.class),
                        new InboxOrganizer(rs.getObject("organizer_id", UUID.class), rs.getString("slug"),
                                rs.getString("name"), crests.url(rs.getString("crest_url"))),
                        rs.getString("title"), rs.getString("body"), rs.getObject("event_id", Integer.class),
                        instant(rs.getObject("sent_at", OffsetDateTime.class))))
                .list();
    }

    private static Campaign campaign(ResultSet rs, int n) throws SQLException {
        return new Campaign(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                Channel.valueOf(rs.getString("channel")), Campaign.Audience.valueOf(rs.getString("audience")),
                rs.getObject("event_id", Integer.class), rs.getString("title"), rs.getString("body"),
                Campaign.Status.valueOf(rs.getString("status")),
                instant(rs.getObject("scheduled_at", OffsetDateTime.class)),
                instant(rs.getObject("started_at", OffsetDateTime.class)),
                instant(rs.getObject("finished_at", OffsetDateTime.class)), rs.getObject("created_by", UUID.class),
                rs.getString("created_by_name"), rs.getLong("recipients"), rs.getLong("sent"), rs.getLong("failed"));
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
