package ma.jomhor.sales.rooms.application;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-entry poll limit (docs/04-api.md §Waiting room Limits, plan Q147): one GET /fan/queue per entry per
 * {@code rooms.poll-min-interval} on this instance, keyed by the verified token's entry id (never the IP: CGNAT).
 * In memory only, no SQL; {@link #sweep} (run by the tick) drops entries idle for {@link #IDLE}.
 */
// ponytail: per instance, so with N instances one entry can poll N times a second. Harmless: a ranked poll is one HMAC
// and one map read. Move the map to a shared store only if a caller-chosen instance spread ever matters.
@Component
public class PollLimiter {

    /** Longer than any poll_after_s (30 s at most): a fan still polling is never swept. */
    static final Duration IDLE = Duration.ofSeconds(60);

    private final Map<UUID, Instant> lastPoll = new ConcurrentHashMap<>();
    private final InstantSource clock;
    private final Duration interval;

    @Autowired
    PollLimiter(@Value("${rooms.poll-min-interval}") Duration interval) {
        this(InstantSource.system(), interval);
    }

    PollLimiter(InstantSource clock, Duration interval) {
        this.clock = clock;
        this.interval = interval;
    }

    /** Records the poll and says yes, unless this entry's last allowed poll here was less than the interval ago. */
    public boolean allow(UUID entryId) {
        Instant now = clock.instant();
        boolean[] allowed = {false};
        lastPoll.compute(entryId, (id, last) -> {
            allowed[0] = last == null || !now.isBefore(last.plus(interval));
            return allowed[0] ? now : last;
        });
        return allowed[0];
    }

    /** Drops entries whose last poll is older than {@link #IDLE}. */
    public void sweep() {
        Instant idleSince = clock.instant().minus(IDLE);
        lastPoll.values().removeIf(t -> t.isBefore(idleSince));
    }

    /** QUEUE_RATE_LIMITED POLL {@code retry_after_s}: the interval rounded up to whole seconds, at least 1. */
    public int retryAfterS() {
        return (int) Math.max(1, (interval.toMillis() + 999) / 1000);
    }

    /** Entries tracked on this instance (tests). */
    public int size() {
        return lastPoll.size();
    }
}
