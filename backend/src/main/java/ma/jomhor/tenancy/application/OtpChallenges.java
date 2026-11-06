package ma.jomhor.tenancy.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@code otp_challenges} (platform table, no organizer_id). */
public interface OtpChallenges {

    /** Serialises sends for one phone until the transaction ends, then counts its sends in the window. */
    int lockAndCountSince(String phone, Duration window);

    void insert(String phone, String codeHash, Duration ttl);

    /** The phone's most recent challenge, row-locked. */
    Optional<Challenge> latestForUpdate(String phone);

    void incrementAttempts(UUID id);

    void consume(UUID id);

    record Challenge(UUID id, String codeHash, int attempts, boolean consumed, boolean expired) {}
}
