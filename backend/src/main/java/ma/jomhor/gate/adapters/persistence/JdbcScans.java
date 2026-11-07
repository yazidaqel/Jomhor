package ma.jomhor.gate.adapters.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import ma.jomhor.gate.application.Scans;
import ma.jomhor.gate.domain.Scan;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every statement filters on organizer_id. Insert and select only: scans are append-only. */
@Repository
class JdbcScans implements Scans {

    private final JdbcClient jdbc;

    JdbcScans(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insert(UUID org, int eventId, UUID deviceId, List<Scan> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        insert into scans (scan_id, organizer_id, event_id, device_id, ticket_id, gate_id, staff_id, direction,
                                           result, token_version, device_time, override_reason, supervisor_id)
                        select r.scan_id, ?, ?, ?, r.ticket_id, r.gate_id, r.staff_id, r.direction::scan_direction,
                               r.result::scan_result, r.token_version, r.device_time, r.override_reason::override_reason,
                               r.supervisor_id
                        from unnest(?::uuid[], ?::uuid[], ?::uuid[], ?::uuid[], ?::text[], ?::text[], ?::smallint[],
                                    ?::timestamptz[], ?::text[], ?::uuid[]) with ordinality
                             as r(scan_id, ticket_id, gate_id, staff_id, direction, result, token_version, device_time,
                                  override_reason, supervisor_id, n)
                        order by r.n
                        on conflict (scan_id) do nothing
                        """)
                .params(org, eventId, deviceId,
                        column(rows, Scan::scanId, UUID[]::new), column(rows, Scan::ticketId, UUID[]::new),
                        column(rows, Scan::gateId, UUID[]::new), column(rows, Scan::staffId, UUID[]::new),
                        column(rows, s -> s.direction().name(), String[]::new),
                        column(rows, s -> s.result().name(), String[]::new),
                        column(rows, Scan::tokenVersion, Integer[]::new),
                        column(rows, s -> s.deviceTime().toString(), String[]::new),
                        column(rows, s -> s.overrideReason() == null ? null : s.overrideReason().name(), String[]::new),
                        column(rows, Scan::supervisorId, UUID[]::new))
                .update();
    }

    @Override
    public List<Scan.Used> usedSince(UUID org, int eventId, UUID deviceId, long cursor, int limit) {
        return jdbc.sql("""
                        select s.seq, s.ticket_id, s.direction, s.device_time, g.code
                        from scans s join gates g on g.id = s.gate_id and g.organizer_id = s.organizer_id
                        where s.organizer_id = ? and s.event_id = ? and s.seq > ? and s.device_id <> ?
                          and s.result in ('OK', 'OVERRIDE')
                        order by s.seq
                        limit ?
                        """)
                .params(org, eventId, cursor, deviceId, limit)
                .query((rs, n) -> new Scan.Used(rs.getLong("seq"), rs.getObject("ticket_id", UUID.class),
                        Scan.Direction.valueOf(rs.getString("direction")),
                        rs.getObject("device_time", OffsetDateTime.class).toInstant(), rs.getString("code")))
                .list();
    }

    private static <T> T[] column(List<Scan> rows, Function<Scan, T> f, IntFunction<T[]> array) {
        return rows.stream().map(f).toArray(array);
    }
}
