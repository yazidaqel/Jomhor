package ma.jomhor.gate.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.gate.application.Assignments;
import ma.jomhor.gate.domain.Assignment;
import ma.jomhor.gate.domain.Device;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every statement filters on organizer_id. Reads the gate module's own tables only. */
@Repository
class JdbcAssignments implements Assignments {

    private final JdbcClient jdbc;

    JdbcAssignments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Single-bigint advisory key (class << 32) | event_id, class 3 = gate event lock (1 and 2 are JdbcOtpChallenges'
     * phone and IP). Assignment PUTs, scan ingestion and close all take it, so they run one at a time per event: a
     * removed device can't slip a scan in between the DEVICE_IN_USE check and the delete, nor a close past the
     * PUT's status check.
     */
    private static final long EVENT_LOCK = 3;

    @Override
    public void lockEvent(UUID org, int eventId) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(?)").param((EVENT_LOCK << 32) | (eventId & 0xFFFFFFFFL))
                .query(Integer.class).single();
    }

    @Override
    public List<Assignment> list(UUID org, int eventId) {
        return jdbc.sql("""
                        select a.device_id, d.label, d.status, a.gate_id, a.manifest_version, d.last_seen_at, a.closed_ack_at
                        from device_assignments a join devices d on d.id = a.device_id and d.organizer_id = a.organizer_id
                        where a.organizer_id = ? and a.event_id = ?
                        """)
                .params(org, eventId)
                .query((rs, n) -> new Assignment(rs.getObject("device_id", UUID.class), rs.getString("label"),
                        Device.Status.valueOf(rs.getString("status")), rs.getObject("gate_id", UUID.class),
                        (Integer) rs.getObject("manifest_version"), instant(rs, "last_seen_at"),
                        instant(rs, "closed_ack_at")))
                .list();
    }

    @Override
    public Set<UUID> activeDevices(UUID org, Collection<UUID> deviceIds) {
        return new HashSet<>(jdbc.sql("select id from devices where organizer_id = ? and status = 'ACTIVE' and id = any(?)")
                .params(org, deviceIds.toArray(UUID[]::new)).query(UUID.class).list());
    }

    @Override
    public Set<UUID> withScans(UUID org, int eventId, Collection<UUID> deviceIds) {
        return new HashSet<>(jdbc.sql("""
                        select distinct device_id from scans
                        where organizer_id = ? and event_id = ? and device_id = any(?)
                        """)
                .params(org, eventId, deviceIds.toArray(UUID[]::new)).query(UUID.class).list());
    }

    @Override
    public void replace(UUID org, int eventId, Map<UUID, UUID> gateByDevice) {
        jdbc.sql("delete from device_assignments where organizer_id = ? and event_id = ? and not (device_id = any(?))")
                .params(org, eventId, gateByDevice.keySet().toArray(UUID[]::new)).update();
        gateByDevice.forEach((device, gate) -> jdbc.sql("""
                        insert into device_assignments (organizer_id, device_id, event_id, gate_id) values (?, ?, ?, ?)
                        on conflict (device_id, event_id) do update set gate_id = excluded.gate_id
                        """)
                .params(org, device, eventId, gate).update());
    }

    @Override
    public List<Assignment.OfDevice> ofDevice(UUID org, UUID deviceId) {
        return jdbc.sql("""
                        select a.event_id, a.gate_id, a.closed_ack_at,
                               (select max(m.version) from manifests m
                                where m.organizer_id = a.organizer_id and m.event_id = a.event_id) as latest
                        from device_assignments a
                        where a.organizer_id = ? and a.device_id = ?
                        """)
                .params(org, deviceId)
                .query((rs, n) -> new Assignment.OfDevice(rs.getInt("event_id"), rs.getObject("gate_id", UUID.class),
                        instant(rs, "closed_ack_at"), (Integer) rs.getObject("latest")))
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
