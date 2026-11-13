package ma.jomhor.sales.rooms.application;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.jomhor.sales.rooms.domain.WaitingRoom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-instance copy of every room not ENDED (docs/04-api.md §Waiting room Memo), reloaded by one statement at most
 * once per {@code rooms.memo-ttl} whatever the number of readers. A cache, never a source of truth: it may be one
 * TTL behind the database (01 §Deployment). A write on this instance calls {@link #invalidate()} after its commit.
 */
@Component
public class RoomMemo {

    private record Snapshot(Map<Integer, WaitingRoom> rooms, Instant loadedAt) {}

    private static final Snapshot STALE = new Snapshot(Map.of(), null);

    private final WaitingRooms rooms;
    private final InstantSource clock;
    private final Duration ttl;
    private volatile Snapshot snapshot = STALE;

    @Autowired
    RoomMemo(WaitingRooms rooms, @Value("${rooms.memo-ttl}") Duration ttl) {
        this(rooms, InstantSource.system(), ttl);
    }

    RoomMemo(WaitingRooms rooms, InstantSource clock, Duration ttl) {
        this.rooms = rooms;
        this.clock = clock;
        this.ttl = ttl;
    }

    /** The event's room unless it has ENDED (or has none). */
    public Optional<WaitingRoom> get(int eventId) {
        return Optional.ofNullable(current().get(eventId));
    }

    /** The next read reloads. */
    public void invalidate() {
        snapshot = STALE;
    }

    private Map<Integer, WaitingRoom> current() {
        Snapshot s = snapshot;
        if (fresh(s)) {
            return s.rooms();
        }
        synchronized (this) {
            s = snapshot;
            if (!fresh(s)) {
                Instant now = clock.instant();
                s = new Snapshot(rooms.live().stream()
                        .collect(Collectors.toUnmodifiableMap(WaitingRoom::eventId, Function.identity())), now);
                snapshot = s;
            }
            return s.rooms();
        }
    }

    private boolean fresh(Snapshot s) {
        return s.loadedAt() != null && clock.instant().isBefore(s.loadedAt().plus(ttl));
    }
}
