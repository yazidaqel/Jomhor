package ma.jomhor.sales.rooms.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One sale phase's waiting room (docs/04-api.md §Waiting room Phases). {@code startsAt}: the stored phase start, null
 * for phase 1; {@code saleStartsAt}: the phase's sale start (phase 1: the event's sale_starts_at);
 * {@code eventDraft}: its event is still DRAFT (not visible to fans). The counts are written by the room tick. Never
 * carries fan data.
 */
public record WaitingRoom(UUID id, UUID organizerId, int eventId, int phase, String label, Instant startsAt,
                          Status status, Instant saleStartsAt, Config config, int joinedCount, int rankedCount,
                          int admittedThrough, int measuredRatePerMin, boolean soldOut, Instant drawnAt,
                          boolean eventDraft) {

    public static final int MAX_PHASES = 5;

    public enum Status {
        SCHEDULED, PRE_QUEUE, OPEN, PAUSED, ENDED;

        /** Joins accepted and an admission token required (04 §Waiting room Lifecycle). */
        public boolean live() {
            return this == PRE_QUEUE || this == OPEN || this == PAUSED;
        }
    }

    /** What the organizer sets (PUT /org/events/{id}/rooms/{phase}) besides the label and the phase start. */
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

    /**
     * The status by time (04 ADMISSION_REQUIRED: fail closed): a room still SCHEDULED at or after {@link #opensAt}
     * because the tick hasn't caught up counts as PRE_QUEUE, or OPEN when it has no pre-queue. Else the stored status.
     * ponytail: callers on the request path pass the app clock while the tick and the sale window use Postgres's, so
     * the two agree only as far as NTP keeps them; pass the database clock if their skew ever matters.
     */
    public Status statusAt(Instant now) {
        if (status != Status.SCHEDULED || now.isBefore(opensAt())) {
            return status;
        }
        return config.preQueueMin() == 0 ? Status.OPEN : Status.PRE_QUEUE;
    }

    /** The account-age rule as a date: users.verified_at must be at or before it; null when not required. */
    public Instant verifiedBefore() {
        Integer days = config.rule().minAccountAgeDays();
        return days == null ? null : saleStartsAt.minus(Duration.ofDays(days));
    }

    /**
     * 04 §Waiting room Phases: the latest phase not ENDED whose opens_at has passed, else the earliest phase not ENDED;
     * empty when the event has no phase or every phase has ENDED. {@code rooms}: one event's, any order.
     */
    public static Optional<WaitingRoom> current(List<WaitingRoom> rooms, Instant now) {
        List<WaitingRoom> live = rooms.stream().filter(r -> r.status != Status.ENDED)
                .sorted(Comparator.comparingInt(WaitingRoom::phase)).toList();
        return live.reversed().stream().filter(r -> !now.isBefore(r.opensAt())).findFirst()
                .or(() -> live.stream().findFirst());
    }

    /** The phase after {@code phase} that isn't ENDED (the fan {@code next}, the phase whose opening ends this one). */
    public static Optional<WaitingRoom> next(List<WaitingRoom> rooms, int phase) {
        return rooms.stream().filter(r -> r.phase > phase && r.status != Status.ENDED)
                .min(Comparator.comparingInt(WaitingRoom::phase));
    }
}
