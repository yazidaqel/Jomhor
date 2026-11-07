package ma.jomhor.tenancy.adapters.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import ma.jomhor.tenancy.application.FanSessions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFanSessions implements FanSessions {

    private final JdbcClient jdbc;

    JdbcFanSessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID create(UUID userId, Instant expiresAt) {
        return jdbc.sql("insert into fan_sessions (user_id, expires_at) values (?, ?) returning id")
                .params(userId, Timestamp.from(expiresAt))
                .query(UUID.class).single();
    }

    @Override
    public boolean isActive(UUID sessionId, UUID userId) {
        return jdbc.sql("""
                        select exists (select 1 from fan_sessions
                                       where id = ? and user_id = ? and revoked_at is null and expires_at > now())
                        """)
                .params(sessionId, userId)
                .query(Boolean.class).single();
    }

    @Override
    public void revoke(UUID sessionId, UUID userId) {
        jdbc.sql("update fan_sessions set revoked_at = now() where id = ? and user_id = ? and revoked_at is null")
                .params(sessionId, userId).update();
    }

    @Override
    public void revokeAll(UUID userId) {
        jdbc.sql("update fan_sessions set revoked_at = now() where user_id = ? and revoked_at is null")
                .param(userId).update();
    }

    @Override
    public void revokeOthers(UUID keep, UUID userId) {
        jdbc.sql("update fan_sessions set revoked_at = now() where user_id = ? and id <> ? and revoked_at is null")
                .params(userId, keep).update();
    }
}
