package ma.jomhor.sales.rooms.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.rooms.application.WaitingRooms;
import ma.jomhor.sales.rooms.domain.WaitingRoom;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Config;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Rule;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement filters on organizer_id, except {@link #live()} (the memo: every organizer). The reads join events
 * for sale_starts_at, read-only (docs/01-architecture.md: the report-query exception), so the memo is one statement.
 */
@Repository
class JdbcWaitingRooms implements WaitingRooms {

    private static final String SELECT = """
            select r.id, r.event_id, r.status::text as status, e.sale_starts_at, r.pre_queue_min,
                   r.admission_rate_per_min, r.min_account_age_days, r.member_joined_before, r.min_events_attended,
                   r.joined_count, r.ranked_count, r.admitted_through, r.measured_rate_per_min, r.sold_out, r.drawn_at
            from waiting_rooms r
            join events e on e.id = r.event_id and e.organizer_id = r.organizer_id
            """;

    private final JdbcClient jdbc;

    JdbcWaitingRooms(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WaitingRoom> find(UUID org, int eventId) {
        return jdbc.sql(SELECT + "where r.organizer_id = ? and r.event_id = ?").params(org, eventId)
                .query(JdbcWaitingRooms::room).optional();
    }

    @Override
    public Optional<Status> lock(UUID org, int eventId) {
        return status("for no key update", org, eventId);
    }

    @Override
    public Optional<Status> share(UUID org, int eventId) {
        return status("for share", org, eventId);
    }

    private Optional<Status> status(String lock, UUID org, int eventId) {
        return jdbc.sql("select status::text from waiting_rooms where organizer_id = ? and event_id = ? " + lock)
                .params(org, eventId).query(String.class).optional().map(Status::valueOf);
    }

    @Override
    public void insert(UUID org, int eventId, Config c) {
        jdbc.sql("""
                        insert into waiting_rooms (organizer_id, event_id, pre_queue_min, admission_rate_per_min,
                                                   min_account_age_days, member_joined_before, min_events_attended)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(org, eventId, c.preQueueMin(), c.admissionRatePerMin(), c.rule().minAccountAgeDays(),
                        timestamp(c.rule().memberJoinedBefore()), c.rule().minEventsAttended())
                .update();
    }

    @Override
    public void update(UUID org, int eventId, Config c) {
        jdbc.sql("""
                        update waiting_rooms set pre_queue_min = ?, admission_rate_per_min = ?, min_account_age_days = ?,
                                                 member_joined_before = ?, min_events_attended = ?
                        where organizer_id = ? and event_id = ?""")
                .params(c.preQueueMin(), c.admissionRatePerMin(), c.rule().minAccountAgeDays(),
                        timestamp(c.rule().memberJoinedBefore()), c.rule().minEventsAttended(), org, eventId)
                .update();
    }

    @Override
    public void setStatus(UUID org, int eventId, Status status) {
        jdbc.sql("update waiting_rooms set status = ?::room_status where organizer_id = ? and event_id = ?")
                .params(status.name(), org, eventId).update();
    }

    @Override
    public List<WaitingRoom> live() {
        return jdbc.sql(SELECT + "where r.status <> 'ENDED'").query(JdbcWaitingRooms::room).list();
    }

    private static OffsetDateTime timestamp(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static WaitingRoom room(ResultSet rs, int row) throws SQLException {
        return new WaitingRoom(rs.getObject("id", UUID.class), rs.getInt("event_id"),
                Status.valueOf(rs.getString("status")), instant(rs, "sale_starts_at"),
                new Config(rs.getInt("pre_queue_min"), rs.getInt("admission_rate_per_min"),
                        new Rule(integer(rs, "min_account_age_days"), instant(rs, "member_joined_before"),
                                integer(rs, "min_events_attended"))),
                rs.getInt("joined_count"), rs.getInt("ranked_count"), rs.getInt("admitted_through"),
                rs.getInt("measured_rate_per_min"), rs.getBoolean("sold_out"), instant(rs, "drawn_at"));
    }
}
