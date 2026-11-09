package ma.jomhor.gate.adapters.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import ma.jomhor.gate.application.Scans;
import ma.jomhor.gate.domain.Scan;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement filters on organizer_id. Insert and select only: scans are append-only. The report reads join
 * tickets (zone and status) and usedSince joins gates, read-only, as one statement each, so 45,000 tickets and
 * ~100k scans are aggregated in the database rather than shipped to the JVM.
 */
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

    // Index scans (event_id, ticket_id, device_time): a handful of ticket ids, a handful of rows each.
    @Override
    public List<Entry> entries(UUID org, int eventId, Collection<UUID> ticketIds) {
        return jdbc.sql("""
                        select distinct on (s.ticket_id) s.ticket_id, s.device_time, g.code
                        from scans s join gates g on g.id = s.gate_id and g.organizer_id = s.organizer_id
                        where s.organizer_id = ? and s.event_id = ? and s.ticket_id = any(?)
                          and s.result in ('OK', 'OVERRIDE') and s.direction = 'IN'
                        order by s.ticket_id, s.device_time, s.scan_id
                        """)
                .params(org, eventId, ticketIds.toArray(UUID[]::new))
                .query((rs, n) -> new Entry(rs.getObject("ticket_id", UUID.class),
                        rs.getObject("device_time", OffsetDateTime.class).toInstant(), rs.getString("code")))
                .list();
    }

    @Override
    public List<Admitted> admitted(UUID org, int eventId) {
        return jdbc.sql("""
                        with adm as (
                          select s.gate_id, s.ticket_id, s.device_time,
                                 row_number() over (partition by s.ticket_id, s.direction
                                                    order by s.device_time, s.scan_id) = 1 as canonical
                          from scans s
                          where s.organizer_id = ? and s.event_id = ? and s.result in ('OK', 'OVERRIDE')
                        )
                        select a.gate_id, t.zone_id, t.status = 'VALID' as valid, count(*) as admitted,
                               count(*) filter (where a.canonical) as canonical,
                               count(*) filter (where a.device_time > now() - interval '5 minutes'
                                                  and a.device_time <= now()) as last5,
                               count(*) filter (where a.device_time > now() - interval '10 minutes'
                                                  and a.device_time <= now() - interval '5 minutes') as prev5
                        from adm a join tickets t on t.id = a.ticket_id and t.organizer_id = ?
                        group by a.gate_id, t.zone_id, t.status = 'VALID'
                        """)
                .params(org, eventId, org)
                .query((rs, n) -> new Admitted(rs.getObject("gate_id", UUID.class), rs.getInt("zone_id"),
                        rs.getBoolean("valid"), rs.getLong("admitted"), rs.getLong("canonical"), rs.getLong("last5"),
                        rs.getLong("prev5")))
                .list();
    }

    @Override
    public List<Other> others(UUID org, int eventId) {
        return jdbc.sql("""
                        select s.gate_id, s.device_id, t.zone_id, s.result, s.override_reason, count(*) as n
                        from scans s left join tickets t on t.id = s.ticket_id and t.organizer_id = s.organizer_id
                        where s.organizer_id = ? and s.event_id = ? and s.result <> 'OK'
                        group by s.gate_id, s.device_id, t.zone_id, s.result, s.override_reason
                        """)
                .params(org, eventId)
                .query((rs, n) -> new Other(rs.getObject("gate_id", UUID.class), rs.getObject("device_id", UUID.class),
                        (Integer) rs.getObject("zone_id"), Scan.Result.valueOf(rs.getString("result")),
                        rs.getString("override_reason") == null ? null
                                : Scan.OverrideReason.valueOf(rs.getString("override_reason")),
                        rs.getLong("n")))
                .list();
    }

    @Override
    public boolean admitted(UUID org, int eventId, UUID ticketId) {
        return jdbc.sql("""
                        select exists (select 1 from scans where organizer_id = ? and event_id = ? and ticket_id = ?
                                                            and result in ('OK', 'OVERRIDE'))
                        """)
                .params(org, eventId, ticketId).query(Boolean.class).single();
    }

    @Override
    public Instant now() {
        return jdbc.sql("select now()").query(OffsetDateTime.class).single().toInstant();
    }

    private static <T> T[] column(List<Scan> rows, Function<Scan, T> f, IntFunction<T[]> array) {
        return rows.stream().map(f).toArray(array);
    }
}
