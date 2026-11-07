package ma.jomhor.gate.adapters.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.gate.application.Manifests;
import ma.jomhor.gate.domain.ManifestPayload;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every statement filters on organizer_id. {@code body} is the gzip of the full payload text (03 manifests). */
@Repository
class JdbcManifests implements Manifests {

    /** Advisory key class 4 = manifest build of one event (3 is JdbcAssignments' event lock). */
    private static final long BUILD_LOCK = 4;

    private final JdbcClient jdbc;

    JdbcManifests(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryLockBuild(UUID org, int eventId) {
        return jdbc.sql("select pg_try_advisory_xact_lock(?)").param((BUILD_LOCK << 32) | (eventId & 0xFFFFFFFFL))
                .query(Boolean.class).single();
    }

    @Override
    public Optional<Latest> latest(UUID org, int eventId) {
        return jdbc.sql("""
                        select version, built_at from manifests where organizer_id = ? and event_id = ?
                        order by version desc limit 1
                        """)
                .params(org, eventId)
                .query((rs, n) -> new Latest(rs.getInt("version"),
                        rs.getObject("built_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public Optional<Stored> find(UUID org, int eventId, int version) {
        return jdbc.sql("""
                        select version, body, signature, row_count from manifests
                        where organizer_id = ? and event_id = ? and version = ?
                        """)
                .params(org, eventId, version)
                .query((rs, n) -> new Stored(rs.getInt("version"), rs.getBytes("body"),
                        rs.getBytes("signature"), rs.getInt("row_count")))
                .optional();
    }

    @Override
    public boolean insert(UUID org, int eventId, int version, Instant builtAt, String payload, byte[] signature,
                          int rowCount) {
        return jdbc.sql("""
                        insert into manifests (organizer_id, event_id, version, built_at, row_count, signature, body)
                        values (?, ?, ?, ?, ?, ?, ?) on conflict (event_id, version) do nothing
                        """)
                .params(org, eventId, version, builtAt.atOffset(ZoneOffset.UTC), rowCount, signature,
                        ManifestPayload.gzip(payload.getBytes(StandardCharsets.UTF_8)))
                .update() == 1;
    }

    @Override
    public Instant now() {
        return jdbc.sql("select now()").query(OffsetDateTime.class).single().toInstant();
    }

    @Override
    public boolean assigned(UUID org, UUID deviceId, int eventId) {
        return jdbc.sql("select count(*) from device_assignments where organizer_id = ? and device_id = ? and event_id = ?")
                .params(org, deviceId, eventId).query(Integer.class).single() > 0;
    }

    @Override
    public void recordDownload(UUID org, UUID deviceId, int eventId, int version) {
        jdbc.sql("""
                        update device_assignments set manifest_version = ?
                        where organizer_id = ? and device_id = ? and event_id = ?
                        """)
                .params(version, org, deviceId, eventId).update();
    }
}
