package ma.jomhor.sales.rooms.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** docs/04-api.md §Waiting room Limits, per entry, polls: one per 1 s per instance; idle entries swept (fixed clock). */
class PollLimiterTest {

    @Test
    void onePollPerSecondPerEntryAndIdleEntriesSwept() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2099-04-01T10:00:00Z"));
        PollLimiter polls = new PollLimiter(now::get, Duration.ofSeconds(1));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(polls.allow(a)).isTrue();
        now.set(now.get().plusMillis(500));
        assertThat(polls.allow(a)).isFalse(); // 0.5 s later
        assertThat(polls.allow(b)).isTrue(); // another entry is its own
        now.set(now.get().plusMillis(500));
        assertThat(polls.allow(a)).isTrue(); // 1 s after the last allowed poll (the refused one doesn't count)
        assertThat(polls.allow(a)).isFalse();

        now.set(now.get().plusSeconds(59));
        polls.sweep();
        assertThat(polls.size()).isEqualTo(2); // b idle 59.5 s, a 59 s: kept
        now.set(now.get().plusSeconds(2));
        polls.sweep();
        assertThat(polls.size()).isZero(); // both idle > 60 s
        assertThat(polls.allow(a)).isTrue();
    }

    @Test
    void retryAfterIsTheIntervalRoundedUpToWholeSecondsAtLeastOne() {
        assertThat(new PollLimiter(Instant::now, Duration.ofSeconds(1)).retryAfterS()).isEqualTo(1);
        assertThat(new PollLimiter(Instant::now, Duration.ofMillis(1500)).retryAfterS()).isEqualTo(2);
        assertThat(new PollLimiter(Instant::now, Duration.ZERO).retryAfterS()).isEqualTo(1);
    }
}
