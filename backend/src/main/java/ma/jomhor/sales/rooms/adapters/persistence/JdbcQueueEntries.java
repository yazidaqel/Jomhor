package ma.jomhor.sales.rooms.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.rooms.application.QueueEntries;
import ma.jomhor.sales.rooms.domain.QueueEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every statement filters on organizer_id and room_id, except {@link #purge} (a job over every organizer). */
@Repository
class JdbcQueueEntries implements QueueEntries {

    private static final String COLUMNS = "id, user_id, rank, admitted_at";

    private final JdbcClient jdbc;

    JdbcQueueEntries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<QueueEntry> find(UUID org, UUID roomId, UUID entryId) {
        return jdbc.sql("select " + COLUMNS + " from queue_entries where organizer_id = ? and room_id = ? and id = ?")
                .params(org, roomId, entryId).query(JdbcQueueEntries::entry).optional();
    }

    @Override
    public Optional<QueueEntry> findByUser(UUID org, UUID roomId, UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from queue_entries where organizer_id = ? and room_id = ? and user_id = ?")
                .params(org, roomId, userId).query(JdbcQueueEntries::entry).optional();
    }

    @Override
    public Optional<QueueEntry> insert(UUID org, UUID roomId, UUID userId, byte[] deviceHash, byte[] ipHash,
                                       long drawKey) {
        return jdbc.sql("""
                        insert into queue_entries (organizer_id, room_id, user_id, device_hash, ip_hash, draw_key)
                        values (?, ?, ?, ?, ?, ?)
                        on conflict (room_id, user_id) do nothing
                        returning\s""" + COLUMNS)
                .params(org, roomId, userId, deviceHash, ipHash, drawKey).query(JdbcQueueEntries::entry).optional();
    }

    @Override
    public QueueEntry moveBack(UUID org, UUID roomId, UUID entryId, byte[] deviceHash, byte[] ipHash) {
        return jdbc.sql("""
                        update queue_entries set rank = null, admitted_at = null, joined_at = now(), device_hash = ?,
                                                 ip_hash = ?
                        where organizer_id = ? and room_id = ? and id = ?
                        returning\s""" + COLUMNS)
                .params(deviceHash, ipHash, org, roomId, entryId).query(JdbcQueueEntries::entry).single();
    }

    @Override
    public long devicePeers(UUID org, UUID roomId, byte[] deviceHash, UUID userId) {
        return jdbc.sql("""
                        select count(*) from queue_entries
                        where organizer_id = ? and room_id = ? and device_hash = ? and user_id <> ?""")
                .params(org, roomId, deviceHash, userId).query(Long.class).single();
    }

    @Override
    public long recentFromIp(UUID org, UUID roomId, byte[] ipHash, Duration window) {
        return jdbc.sql("""
                        select count(*) from queue_entries
                        where organizer_id = ? and room_id = ? and ip_hash = ?
                          and joined_at > now() - cast(? as bigint) * interval '1 second'""")
                .params(org, roomId, ipHash, window.toSeconds()).query(Long.class).single();
    }

    @Override
    public Optional<QueueEntry> admit(UUID org, UUID roomId, UUID entryId, int admittedThrough, Instant at) {
        return jdbc.sql("""
                        update queue_entries set admitted_at = ?
                        where organizer_id = ? and room_id = ? and id = ? and rank <= ? and admitted_at is null
                        returning\s""" + COLUMNS)
                .params(at.atOffset(ZoneOffset.UTC), org, roomId, entryId, admittedThrough)
                .query(JdbcQueueEntries::entry).optional();
    }

    @Override
    public int draw(UUID org, UUID roomId, int after) {
        return jdbc.sql("""
                        update queue_entries q set rank = ? + d.n
                        from (select id, row_number() over (order by draw_key, id) as n from queue_entries
                              where organizer_id = ? and room_id = ? and rank is null) d
                        where q.id = d.id""")
                .params(after, org, roomId).update();
    }

    @Override
    public int rankArrivals(UUID org, UUID roomId, int after, int max) {
        return jdbc.sql("""
                        update queue_entries q set rank = ? + d.n
                        from (select id, row_number() over (order by joined_at, id) as n
                              from (select id, joined_at from queue_entries
                                    where organizer_id = ? and room_id = ? and rank is null
                                    order by joined_at, id limit ?) s) d
                        where q.id = d.id""")
                .params(after, org, roomId, max).update();
    }

    @Override
    public long count(UUID org, UUID roomId) {
        return jdbc.sql("select count(*) from queue_entries where organizer_id = ? and room_id = ?")
                .params(org, roomId).query(Long.class).single();
    }

    @Override
    public long unranked(UUID org, UUID roomId) {
        return jdbc.sql("select count(*) from queue_entries where organizer_id = ? and room_id = ? and rank is null")
                .params(org, roomId).query(Long.class).single();
    }

    @Override
    public int purge(Duration retention, Instant now) {
        return jdbc.sql("""
                        delete from queue_entries q using waiting_rooms r, events e
                        where r.id = q.room_id and r.organizer_id = q.organizer_id
                          and e.id = r.event_id and e.organizer_id = r.organizer_id
                          and e.starts_at + cast(? as bigint) * interval '1 second' <= ?""")
                .params(retention.toSeconds(), now.atOffset(ZoneOffset.UTC)).update();
    }

    private static QueueEntry entry(ResultSet rs, int row) throws SQLException {
        int rank = rs.getInt("rank");
        Integer r = rs.wasNull() ? null : rank;
        OffsetDateTime admitted = rs.getObject("admitted_at", OffsetDateTime.class);
        return new QueueEntry(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), r,
                admitted == null ? null : admitted.toInstant());
    }
}
