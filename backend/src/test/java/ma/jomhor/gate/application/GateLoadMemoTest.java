package ma.jomhor.gate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import ma.jomhor.gate.application.GateReports.GateLevel;
import ma.jomhor.gate.application.GateReports.Level;
import ma.jomhor.tickets.application.TicketService;
import org.junit.jupiter.api.Test;

/** GateLoad's gate memo (04: once per event per 15 s) on a fixed clock, and the level thresholds in integers. */
class GateLoadMemoTest {

    @Test
    void theGatePartIsRecomputedOnlyOnce15SecondsHavePassedPerEvent() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-07T19:00:00Z"));
        GateReports reports = mock(GateReports.class);
        UUID org = UUID.randomUUID();
        List<GateLevel> low = List.of(new GateLevel("P1", Level.LOW));
        List<GateLevel> high = List.of(new GateLevel("P1", Level.HIGH));
        when(reports.fanGateLevels(org, 7)).thenReturn(low).thenReturn(high);
        when(reports.fanGateLevels(org, 8)).thenReturn(high);
        GateLoad load = new GateLoad(mock(TicketService.class), reports, mock(Scans.class), now::get);

        assertThat(load.gates(org, 7)).isEqualTo(low);
        now.set(now.get().plus(Duration.ofMillis(14_999)));
        assertThat(load.gates(org, 7)).isEqualTo(low);
        assertThat(load.gates(org, 8)).isEqualTo(high); // another event has its own entry
        verify(reports, times(1)).fanGateLevels(org, 7);
        now.set(now.get().plusMillis(1)); // exactly 15 s after the first computation
        assertThat(load.gates(org, 7)).isEqualTo(high);
        verify(reports, times(2)).fanGateLevels(org, 7);
    }

    @Test
    void concurrentCallersPastTheTtlShareOneComputation() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-07T19:00:00Z"));
        GateReports reports = mock(GateReports.class);
        UUID org = UUID.randomUUID();
        List<GateLevel> low = List.of(new GateLevel("P1", Level.LOW));
        List<GateLevel> high = List.of(new GateLevel("P1", Level.HIGH));
        CountDownLatch release = new CountDownLatch(1);
        when(reports.fanGateLevels(org, 7)).thenReturn(low).thenAnswer(inv -> {
            release.await();
            return high;
        });
        GateLoad load = new GateLoad(mock(TicketService.class), reports, mock(Scans.class), now::get);
        assertThat(load.gates(org, 7)).isEqualTo(low);
        now.set(now.get().plusSeconds(15)); // expired

        int n = 16;
        List<CompletableFuture<List<GateLevel>>> results = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CompletableFuture<List<GateLevel>> r = new CompletableFuture<>();
            results.add(r);
            threads.add(Thread.ofPlatform().start(() -> r.complete(load.gates(org, 7))));
        }
        // Every thread parked: the winner inside fanGateLevels, the others joining its future.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (threads.stream().anyMatch(t -> t.getState() != Thread.State.WAITING)) {
            assertThat(System.nanoTime()).as("threads parked").isLessThan(deadline);
            Thread.onSpinWait();
        }
        release.countDown();
        for (CompletableFuture<List<GateLevel>> r : results) {
            assertThat(r.get(10, TimeUnit.SECONDS)).isEqualTo(high);
        }
        verify(reports, times(2)).fanGateLevels(org, 7); // the first call, then one for all 16
    }

    @Test
    void aFailedComputationIsNotCachedAndStaleEventsAreDropped() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-07T19:00:00Z"));
        GateReports reports = mock(GateReports.class);
        UUID org = UUID.randomUUID();
        List<GateLevel> low = List.of(new GateLevel("P1", Level.LOW));
        when(reports.fanGateLevels(org, 7)).thenThrow(new IllegalStateException("db down")).thenReturn(low);
        when(reports.fanGateLevels(org, 8)).thenReturn(low);
        GateLoad load = new GateLoad(mock(TicketService.class), reports, mock(Scans.class), now::get);

        assertThatThrownBy(() -> load.gates(org, 7)).isInstanceOf(IllegalStateException.class);
        assertThat(load.memoSize()).isZero();
        assertThat(load.gates(org, 7)).isEqualTo(low); // retried at once, not 15 s later
        verify(reports, times(2)).fanGateLevels(org, 7);

        now.set(now.get().plusSeconds(15));
        assertThat(load.gates(org, 8)).isEqualTo(low);
        assertThat(load.memoSize()).isEqualTo(1); // event 7's stale entry dropped
    }

    @Test
    void levelThresholdsAreExactAtTheBoundaries() {
        assertThat(GateReports.level(1000, 0)).isEqualTo(Level.UNKNOWN);
        assertThat(GateReports.level(23, 2)).isEqualTo(Level.LOW);     // 23 / 48 < 0.5
        assertThat(GateReports.level(24, 2)).isEqualTo(Level.MEDIUM);  // 24 / 48 = 0.5
        assertThat(GateReports.level(40, 2)).isEqualTo(Level.MEDIUM);  // 40 / 48 < 0.85
        assertThat(GateReports.level(41, 2)).isEqualTo(Level.HIGH);    // 41 / 48 > 0.85
        assertThat(GateReports.level(20, 1)).isEqualTo(Level.MEDIUM);  // 20 / 24 < 0.85
        assertThat(GateReports.level(21, 1)).isEqualTo(Level.HIGH);
        assertThat(GateReports.level(0, 1)).isEqualTo(Level.LOW);
    }
}
