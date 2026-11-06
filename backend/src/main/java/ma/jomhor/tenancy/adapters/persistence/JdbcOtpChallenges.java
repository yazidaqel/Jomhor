package ma.jomhor.tenancy.adapters.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.tenancy.application.OtpChallenges;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOtpChallenges implements OtpChallenges {

    // Single-bigint advisory keys: (class << 32) | 32-bit hash, class 1 = phone, 2 = IP. Phone and IP keys never
    // collide, and the single-bigint key space is separate from the two-int4 one (sales' buyer lock).
    private static final long PHONE_LOCK = 1;
    private static final long IP_LOCK = 2;
    private static final String LOCK =
            "select 1 from pg_advisory_xact_lock((cast(? as bigint) << 32) | (hashtext(?) & 4294967295))";

    private final JdbcClient jdbc;

    JdbcOtpChallenges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(String phone, String ip) {
        jdbc.sql(LOCK).params(PHONE_LOCK, phone).query(Integer.class).single();
        jdbc.sql(LOCK).params(IP_LOCK, ip).query(Integer.class).single();
    }

    @Override
    public PhoneUsage phoneUsage(String phone, String audience) {
        return jdbc.sql("""
                        select count(*) filter (where created_at > now() - interval '10 minutes') as sends_10m,
                               count(*) as sends_24h,
                               coalesce(sum(attempts), 0) as failures
                        from otp_challenges
                        where phone_e164 = ? and audience = ? and created_at > now() - interval '24 hours'
                        """)
                .params(phone, audience)
                .query((rs, n) -> new PhoneUsage(rs.getInt("sends_10m"), rs.getInt("sends_24h"), rs.getInt("failures")))
                .single();
    }

    @Override
    public IpUsage ipUsage(String ip, String audience) {
        return jdbc.sql("""
                        select count(*) filter (where created_at > now() - interval '10 minutes') as sends_10m,
                               count(*) as sends_24h
                        from otp_challenges
                        where ip = cast(? as inet) and audience = ? and created_at > now() - interval '24 hours'
                        """)
                .params(ip, audience)
                .query((rs, n) -> new IpUsage(rs.getInt("sends_10m"), rs.getInt("sends_24h")))
                .single();
    }

    @Override
    public void insert(String phone, String audience, String ip, String codeHash, Duration ttl) {
        jdbc.sql("""
                        insert into otp_challenges (phone_e164, audience, ip, code_hash, expires_at)
                        values (?, ?, cast(? as inet), ?, now() + ? * interval '1 second')
                        """)
                .params(phone, audience, ip, codeHash, ttl.toSeconds())
                .update();
    }

    @Override
    public Optional<Challenge> latestForUpdate(String phone, String audience) {
        return jdbc.sql("""
                        select id, code_hash, attempts, consumed_at is not null as consumed, expires_at <= now() as expired
                        from otp_challenges where phone_e164 = ? and audience = ?
                        order by created_at desc limit 1
                        for update
                        """)
                .params(phone, audience)
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
