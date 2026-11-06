package ma.jomhor.tenancy.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@code otp_challenges} (platform table, no organizer_id). Everything is per audience. */
public interface OtpChallenges {

    /** Serialises sends for this phone, then this IP, until the transaction ends (same order everywhere). */
    void lock(String phone, String ip);

    /** The phone's challenges of this audience in the last 24 h: sends in the last 10 min, sends, Σ failed attempts. */
    PhoneUsage phoneUsage(String phone, String audience);

    /** The IP's challenges of this audience: sends in the last 10 min and in the last 24 h. */
    IpUsage ipUsage(String ip, String audience);

    void insert(String phone, String audience, String ip, String codeHash, Duration ttl);

    /** The most recent challenge of this phone and audience, row-locked. */
    Optional<Challenge> latestForUpdate(String phone, String audience);

    void incrementAttempts(UUID id);

    void consume(UUID id);

    record PhoneUsage(int sends10m, int sends24h, int failures24h) {}

    record IpUsage(int sends10m, int sends24h) {}

    record Challenge(UUID id, String codeHash, int attempts, boolean consumed, boolean expired) {}
}
