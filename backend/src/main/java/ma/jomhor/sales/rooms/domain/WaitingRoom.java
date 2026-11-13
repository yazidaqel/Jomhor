package ma.jomhor.sales.rooms.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * An event's on-sale waiting room (docs/04-api.md §Waiting room) with its event's {@code saleStartsAt}. The counts
 * are written by the room tick. Never carries fan data.
 */
public record WaitingRoom(UUID id, UUID organizerId, int eventId, Status status, Instant saleStartsAt, Config config,
                          int joinedCount, int rankedCount, int admittedThrough, int measuredRatePerMin,
                          boolean soldOut, Instant drawnAt) {

    public enum Status {
        SCHEDULED, PRE_QUEUE, OPEN, PAUSED, ENDED;

        /** Joins accepted and an admission token required (04 §Waiting room Lifecycle). */
        public boolean live() {
            return this == PRE_QUEUE || this == OPEN || this == PAUSED;
        }
    }

    /** What the organizer sets (PUT /org/events/{id}/room). */
    public record Config(int preQueueMin, int admissionRatePerMin, Rule rule) {

        public static final int DEFAULT_PRE_QUEUE_MIN = 30;
        public static final int DEFAULT_ADMISSION_RATE_PER_MIN = 500;
    }

    /** Eligibility, ANDed; each null = not required. */
    public record Rule(Integer minAccountAgeDays, Instant memberJoinedBefore, Integer minEventsAttended) {

        public static final Rule NONE = new Rule(null, null, null);

        public boolean any() {
            return minAccountAgeDays != null || memberJoinedBefore != null || minEventsAttended != null;
        }
    }

    /** Joins open at sale_starts_at − pre_queue_min. */
    public Instant opensAt() {
        return saleStartsAt.minus(Duration.ofMinutes(config.preQueueMin()));
    }

    /** The account-age rule as a date: users.verified_at must be at or before it; null when not required. */
    public Instant verifiedBefore() {
        Integer days = config.rule().minAccountAgeDays();
        return days == null ? null : saleStartsAt.minus(Duration.ofDays(days));
    }
}
