package ma.jomhor.tenancy.adapters.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.tenancy.application.OtpChallenges;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOtpChallenges implements OtpChallenges {

    private final JdbcClient jdbc;

    JdbcOtpChallenges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int lockAndCountSince(String phone, Duration window) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(hashtext(?))").param(phone).query(Integer.class).single();
        return jdbc.sql("""
                        select count(*) from otp_challenges
                        where phone_e164 = ? and created_at > now() - ? * interval '1 second'
                        """)
                .params(phone, window.toSeconds())
                .query(Integer.class).single();
    }

    @Override
    public void insert(String phone, String codeHash, Duration ttl) {
        jdbc.sql("""
                        insert into otp_challenges (phone_e164, code_hash, expires_at)
                        values (?, ?, now() + ? * interval '1 second')
                        """)
                .params(phone, codeHash, ttl.toSeconds())
                .update();
    }

    @Override
    public Optional<Challenge> latestForUpdate(String phone) {
        return jdbc.sql("""
                        select id, code_hash, attempts, consumed_at is not null as consumed, expires_at <= now() as expired
                        from otp_challenges where phone_e164 = ?
                        order by created_at desc limit 1
                        for update
                        """)
                .param(phone)
                .query((rs, n) -> new Challenge(rs.getObject("id", UUID.class), rs.getString("code_hash"),
                        rs.getInt("attempts"), rs.getBoolean("consumed"), rs.getBoolean("expired")))
                .optional();
    }

    @Override
    public void incrementAttempts(UUID id) {
        jdbc.sql("update otp_challenges set attempts = attempts + 1 where id = ?").param(id).update();
    }

    @Override
    public void consume(UUID id) {
        jdbc.sql("update otp_challenges set consumed_at = now() where id = ?").param(id).update();
    }
}
