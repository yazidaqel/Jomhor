package ma.jomhor.sales.waitlist.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.waitlist.application.Waitlist;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every statement filters on organizer_id, except {@link #waiting} and {@link #purge} (a job over every organizer). */
@Repository
class JdbcWaitlist implements Waitlist {

    /** Advisory class 7 (EventLock lists the classes), single-bigint key (7 << 32) | event id. */
    private static final long LOCK_CLASS = 7;

    private static final RowMapper<Entry> ENTRY = (rs, n) -> new Entry(instant(rs, "joined_at"),
            instant(rs, "notified_at"));

    private final JdbcClient jdbc;

    JdbcWaitlist(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Entry> find(UUID org, int eventId, UUID userId) {
        return jdbc.sql("""
                        select joined_at, notified_at from waitlist_entries
                        where organizer_id = :org and event_id = :event and user_id = :user
                        """)
                .param("org", org).param("event", eventId).param("user", userId).query(ENTRY).optional();
    }

    /**
     * One statement: a new row, or a notified one moved back. A row still waiting is left as it is and returns
     * nothing, so it is read; if a concurrent leave deleted it in between, the join runs once more.
     */
    @Override
    public Joined join(UUID org, int eventId, UUID userId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<Entry> written = jdbc.sql("""
                            insert into waitlist_entries (organizer_id, event_id, user_id) values (:org, :event, :user)
                            on conflict (event_id, user_id) do update set joined_at = now(), notified_at = null
                            where waitlist_entries.organizer_id = :org and waitlist_entries.notified_at is not null
                            returning joined_at, notified_at
                            """)
                    .param("org", org).param("event", eventId).param("user", userId).query(ENTRY).optional();
            if (written.isPresent()) {
                return new Joined(true, written.get());
            }
            Optional<Entry> kept = find(org, eventId, userId);
            if (kept.isPresent()) {
                return new Joined(false, kept.get());
            }
        }
        throw new IllegalStateException("waitlist entry joined and left concurrently twice");
    }

    @Override
    public void delete(UUID org, int eventId, UUID userId) {
        jdbc.sql("delete from waitlist_entries where organizer_id = :org and event_id = :event and user_id = :user")
                .param("org", org).param("event", eventId).param("user", userId).update();
    }

    @Override
    public List<EventRef> waiting() {
        return jdbc.sql("""
                        select distinct organizer_id, event_id from waitlist_entries where notified_at is null
                        order by event_id
                        """)
                .query((rs, n) -> new EventRef(rs.getObject("organizer_id", UUID.class), rs.getInt("event_id")))
                .list();
    }

    @Override
    public boolean tryLock(int eventId) {
        return jdbc.sql("select pg_try_advisory_xact_lock(?)").param((LOCK_CLASS << 32) | (eventId & 0xFFFFFFFFL))
                .query(Boolean.class).single();
    }

    @Override
    public Instant clock() {
        return jdbc.sql("select clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    @Override
    public List<UUID> notifyOldest(UUID org, int eventId, int n, Instant now) {
        return jdbc.sql("""
                        update waitlist_entries set notified_at = :now
                        where organizer_id = :org and event_id = :event and notified_at is null
                          and id in (select id from waitlist_entries
                                     where organizer_id = :org and event_id = :event and notified_at is null
                                     order by joined_at, id limit :n)
                        returning user_id
                        """)
                .param("org", org).param("event", eventId).param("n", n).param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID.class).list();
    }

    @Override
    public int purge(Duration retention, Instant now) {
        return jdbc.sql("""
                        delete from waitlist_entries w using events e
                        where e.id = w.event_id and e.organizer_id = w.organizer_id
                          and e.starts_at + cast(:secs as bigint) * interval '1 second' <= :now
                        """)
                .param("secs", retention.toSeconds()).param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
