package ma.jomhor.sales.waitlist.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for waitlist_entries (docs/04-api.md §Waitlist), one per account per event. Every method is scoped
 * to one organizer except the job's {@link #waiting()} and {@link #purge}, which serve every organizer.
 */
public interface Waitlist {

    record Entry(Instant joinedAt, Instant notifiedAt) {}

    /** {@code created}: a new entry, or a notified one moved to the back (→ 201). */
    record Joined(boolean created, Entry entry) {}

    /** An event with waiting entries, with its organizer. */
    record EventRef(UUID organizerId, int eventId) {}

    Optional<Entry> find(UUID org, int eventId, UUID userId);

    /** New → created; notified → joined_at = now(), notified_at null, created; still waiting → unchanged. */
    Joined join(UUID org, int eventId, UUID userId);

    void delete(UUID org, int eventId, UUID userId);

    /** Every organizer's events with at least one entry not yet notified. */
    List<EventRef> waiting();

    /**
     * The waitlist try-lock (EventLock's class 7) on the event, until the caller's transaction ends: false when another
     * instance holds it.
     */
    boolean tryLock(int eventId);

    /** The database's clock_timestamp(): the wall clock now, not the transaction's start. */
    Instant clock();

    /** The {@code n} oldest waiting entries (joined_at, id) get notified_at = {@code now}; their user ids. */
    List<UUID> notifyOldest(UUID org, int eventId, int n, Instant now);

    /** Deletes the entries {@code retention} after their event's starts_at; rows deleted. */
    int purge(Duration retention, Instant now);
}
