package ma.jomhor.crm.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.UUID;
import ma.jomhor.crm.application.Memberships;
import ma.jomhor.crm.domain.Member;
import ma.jomhor.notifications.application.PushTokens;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement filters on organizer_id. The loyalty reads join tickets, scans, events and venues read-only, one
 * statement each (docs/01-architecture.md: the report-query exception).
 */
@Repository
class JdbcMemberships implements Memberships {

    private static final String COLUMNS = "id, number, joined_at, left_at, marketing_push, marketing_whatsapp";

    /** A ticket {@code t} with an admitted scan; DUPLICATE_LATE is derived, so any OK/OVERRIDE IN row will do. */
    private static final String ADMITTED = """
            exists (select 1 from scans s
                    where s.organizer_id = t.organizer_id and s.event_id = t.event_id and s.ticket_id = t.id
                      and s.result in ('OK', 'OVERRIDE') and s.direction = 'IN')""";

    private final JdbcClient jdbc;

    JdbcMemberships(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Member> find(UUID org, UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from memberships where organizer_id = ? and user_id = ?")
                .params(org, userId).query(JdbcMemberships::member).optional();
    }

    /** Advisory lock class 4 in the two-int key space (common/EventLock lists the classes). */
    private static final int LOCK_CLASS = 4;

    @Override
    public void lock(UUID org) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(?, hashtext(cast(? as text)))")
                .params(LOCK_CLASS, org.toString()).query(Integer.class).single();
    }

    @Override
    public Member insertNext(UUID org, UUID userId, boolean push, boolean whatsapp) {
        return jdbc.sql("""
                        insert into memberships (organizer_id, user_id, number, marketing_push, marketing_whatsapp)
                        select ?, ?, coalesce(max(number), 0) + 1, ?, ? from memberships where organizer_id = ?
                        returning\s""" + COLUMNS)
                .params(org, userId, push, whatsapp, org).query(JdbcMemberships::member).single();
    }

    @Override
    public boolean rejoin(UUID org, UUID id, boolean push, boolean whatsapp) {
        return jdbc.sql("update memberships set left_at = null, marketing_push = ?, marketing_whatsapp = ? "
                        + "where organizer_id = ? and id = ? and left_at is not null")
                .params(push, whatsapp, org, id).update() == 1;
    }

    @Override
    public boolean setOptIns(UUID org, UUID id, boolean push, boolean whatsapp) {
        return jdbc.sql("""
                        update memberships set marketing_push = ?, marketing_whatsapp = ?
                        where organizer_id = ? and id = ? and left_at is null
                          and (marketing_push, marketing_whatsapp) is distinct from (?, ?)""")
                .params(push, whatsapp, org, id, push, whatsapp).update() == 1;
    }

    @Override
    public Optional<UUID> leave(UUID org, UUID userId) {
        return jdbc.sql("""
                        update memberships set left_at = now(), marketing_push = false, marketing_whatsapp = false
                        where organizer_id = ? and user_id = ? and left_at is null
                        returning id""")
                .params(org, userId).query(UUID.class).optional();
    }

    @Override
    public long eventsAttended(UUID org, UUID userId) {
        return jdbc.sql("select count(distinct t.event_id) from tickets t "
                        + "where t.organizer_id = ? and t.holder_user_id = ? and " + ADMITTED)
                .params(org, userId).query(Long.class).single();
    }

    @Override
    public List<PastEvent> lastEvents(UUID org, UUID userId, int limit) {
        List<PastEvent> latest = jdbc.sql("""
                        select e.id, e.title, e.event_type::text as event_type, e.starts_at, v.timezone,
                               exists (select 1 from tickets t
                                       where t.organizer_id = e.organizer_id and t.event_id = e.id
                                         and t.holder_user_id = ? and\s""" + ADMITTED + """
                               ) as attended
                        from events e join venues v on v.id = e.venue_id and v.organizer_id = e.organizer_id
                        where e.organizer_id = ? and e.status = 'CLOSED'
                        order by e.starts_at desc, e.id desc
                        limit ?""")
                .params(userId, org, limit)
                .query((rs, n) -> new PastEvent(rs.getInt("id"), rs.getString("title"), rs.getString("event_type"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(), rs.getString("timezone"),
                        rs.getBoolean("attended")))
                .list();
        return latest.reversed();
    }

    /** reachable_push: the push opt-in and a live token (PushTokens.liveTokenOf, as the PUSH MEMBERS estimate). */
    @Override
    public Counts counts(UUID org) {
        return jdbc.sql("""
                        select count(*) as members,
                               count(*) filter (where m.joined_at > now() - interval '7 days') as joined_7d,
                               count(*) filter (where m.marketing_push) as marketing_push,
                               count(*) filter (where m.marketing_whatsapp) as marketing_whatsapp,
                               count(*) filter (where m.marketing_push and\s"""
                        + PushTokens.liveTokenOf("m.user_id") + """
                               ) as reachable_push
                        from memberships m
                        where m.organizer_id = ? and m.left_at is null""")
                .param(org)
                .query((rs, n) -> new Counts(rs.getLong("members"), rs.getLong("joined_7d"),
                        rs.getLong("marketing_push"), rs.getLong("marketing_whatsapp"), rs.getLong("reachable_push")))
                .single();
    }

    @Override
    public Map<Long, Long> membersByEventsAttended(UUID org) {
        return jdbc.sql("""
                        select a.attended, count(*) as members
                        from (select (select count(distinct t.event_id) from tickets t
                                      where t.organizer_id = m.organizer_id and t.holder_user_id = m.user_id
                                        and\s""" + ADMITTED + """
                                     ) as attended
                              from memberships m
                              where m.organizer_id = ? and m.left_at is null) a
                        group by a.attended""")
                .param(org)
                .query((rs, n) -> Map.entry(rs.getLong("attended"), rs.getLong("members"))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Member member(ResultSet rs, int n) throws SQLException {
        OffsetDateTime left = rs.getObject("left_at", OffsetDateTime.class);
        return new Member(rs.getObject("id", UUID.class), rs.getInt("number"),
                rs.getObject("joined_at", OffsetDateTime.class).toInstant(), left == null ? null : left.toInstant(),
                rs.getBoolean("marketing_push"), rs.getBoolean("marketing_whatsapp"));
    }
}
