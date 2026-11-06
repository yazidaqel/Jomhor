package ma.jomhor.notifications.domain;

import java.time.Duration;
import java.util.UUID;

/**
 * One PENDING WhatsApp ticket message claimed for sending. {@code attempts} = sends tried so far. After a failed
 * try the next one waits 1 m, 5 m, 30 m, then 2 h; the 5th failure is final (FAILED).
 */
public record Delivery(UUID id, UUID organizerId, UUID ticketId, int attempts) {

    public static final int MAX_ATTEMPTS = 5;
    private static final Duration[] BACKOFF = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)};

    /** The wait before the next try once {@code failedAttempts} tries failed, or null when that was the last one. */
    public static Duration backoff(int failedAttempts) {
        return failedAttempts >= MAX_ATTEMPTS ? null : BACKOFF[failedAttempts - 1];
    }
}
