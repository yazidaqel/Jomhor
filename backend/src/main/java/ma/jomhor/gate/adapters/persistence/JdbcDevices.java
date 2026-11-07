package ma.jomhor.gate.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.gate.application.Devices;
import ma.jomhor.gate.domain.Device;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every statement filters on organizer_id, except the auth and enrol lookups (see {@link Devices}). */
@Repository
class JdbcDevices implements Devices {

    private static final String COLUMNS =
            "id, organizer_id, label, status, public_key, enrolled_at, approved_at, last_seen_at";

    private final JdbcClient jdbc;

    JdbcDevices(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Device> findForAuth(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from devices where id = ?").param(id).query(JdbcDevices::device).optional();
    }

    @Override
    public Optional<Device> findByPublicKey(byte[] publicKey) {
        return jdbc.sql("select " + COLUMNS + " from devices where public_key = ?").param(publicKey)
                .query(JdbcDevices::device).optional();
    }

    @Override
    public Optional<Device> insert(UUID org, String label, byte[] publicKey) {
        return jdbc.sql("insert into devices (organizer_id, label, public_key) values (?, ?, ?) "
                        + "on conflict (public_key) do nothing returning " + COLUMNS)
                .params(org, label, publicKey).query(JdbcDevices::device).optional();
    }

    @Override
    public void touch(UUID id) {
        jdbc.sql("update devices set last_seen_at = now() where id = ?").param(id).update();
    }

    @Override
    public List<Device> list(UUID org) {
        return jdbc.sql("select " + COLUMNS + " from devices where organizer_id = ? "
                        + "order by status <> 'PENDING', label, id")
                .param(org).query(JdbcDevices::device).list();
    }

    @Override
    public Optional<Device> find(UUID org, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from devices where organizer_id = ? and id = ?").params(org, id)
                .query(JdbcDevices::device).optional();
    }

    @Override
    public boolean approve(UUID org, UUID id) {
        return jdbc.sql("update devices set status = 'ACTIVE', approved_at = now() "
                        + "where organizer_id = ? and id = ? and status = 'PENDING'")
                .params(org, id).update() == 1;
    }

    @Override
    public boolean revoke(UUID org, UUID id) {
        return jdbc.sql("update devices set status = 'REVOKED', revoked_at = now() "
                        + "where organizer_id = ? and id = ? and status <> 'REVOKED'")
                .params(org, id).update() == 1;
    }

    private static Device device(ResultSet rs, int row) throws SQLException {
        return new Device(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                rs.getString("label"), Device.Status.valueOf(rs.getString("status")), rs.getBytes("public_key"),
                instant(rs, "enrolled_at"), instant(rs, "approved_at"), instant(rs, "last_seen_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
