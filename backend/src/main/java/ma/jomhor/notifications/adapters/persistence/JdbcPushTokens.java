package ma.jomhor.notifications.adapters.persistence;

import java.util.List;
import java.util.UUID;
import ma.jomhor.notifications.application.PushTokens;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** push_tokens is a platform table (no organizer_id, plan lot-4 Q73): scoped by user and session instead. */
@Repository
class JdbcPushTokens implements PushTokens {

    private final JdbcClient jdbc;

    JdbcPushTokens(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The session row is locked first, so two PUTs of one session run one after the other: one token survives. */
    @Override
    public void register(UUID userId, UUID sessionId, String token, String platform) {
        jdbc.sql("select id from fan_sessions where id = :sid and user_id = :user for update")
                .param("sid", sessionId).param("user", userId).query(UUID.class).optional();
        jdbc.sql("delete from push_tokens where session_id = :sid and token <> :token")
                .param("sid", sessionId).param("token", token).update();
        jdbc.sql("""
                        insert into push_tokens (user_id, session_id, token, platform)
                        values (:user, :sid, :token, :platform)
                        on conflict (token) do update
                        set user_id = excluded.user_id, session_id = excluded.session_id,
                            platform = excluded.platform, updated_at = now()
                        """)
                .param("user", userId).param("sid", sessionId).param("token", token).param("platform", platform)
                .update();
    }

    @Override
    public List<String> live(UUID userId) {
        return jdbc.sql("""
                        select t.token from push_tokens t
                        join fan_sessions s on s.id = t.session_id and s.user_id = t.user_id
                        where t.user_id = :user and s.revoked_at is null and s.expires_at > now()
                        order by t.updated_at, t.id
                        """)
                .param("user", userId).query(String.class).list();
    }

    @Override
    public void delete(String token) {
        jdbc.sql("delete from push_tokens where token = :token").param("token", token).update();
    }
}
