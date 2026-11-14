package ma.jomhor.sales.rooms.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.rooms.domain.QueueEntry;

/** Persistence port for queue_entries. Every method but {@link #purge} is scoped to one organizer and one room. */
public interface QueueEntries {

    Optional<QueueEntry> find(UUID org, UUID roomId, UUID entryId);

    Optional<QueueEntry> findByUser(UUID org, UUID roomId, UUID userId);

    /** A new entry (joined_at = now()); empty when the user already has one in the room. */
    Optional<QueueEntry> insert(UUID org, UUID roomId, UUID userId, byte[] deviceHash, byte[] ipHash, long drawKey);

    /** Back of the queue after an expired turn: rank and admitted_at cleared, joined_at = now(), hashes of this join. */
    QueueEntry moveBack(UUID org, UUID roomId, UUID entryId, byte[] deviceHash, byte[] ipHash);

    /** Entries of other users in the room with this device hash. */
    long devicePeers(UUID org, UUID roomId, byte[] deviceHash, UUID userId);

    /** Entries in the room with this IP hash and joined_at within {@code window} of now(). */
    long recentFromIp(UUID org, UUID roomId, byte[] ipHash, Duration window);

    /**
     * The turn: admitted_at = {@code at} if the entry's rank is at most {@code admittedThrough} and its turn hasn't
     * started; empty otherwise (already admitted, not yet due, or gone), so a poll after the turn writes nothing.
     */
    Optional<QueueEntry> admit(UUID org, UUID roomId, UUID entryId, int admittedThrough, Instant at);

    /** The draw: every unranked entry gets after + row_number() over (order by draw_key, id). Returns how many. */
    int draw(UUID org, UUID roomId, int after);

    /** Later joins: at most {@code max} unranked entries get after + row_number() over (order by joined_at, id). */
    int rankArrivals(UUID org, UUID roomId, int after, int max);

    long count(UUID org, UUID roomId);

    long unranked(UUID org, UUID roomId);

    /** Every organizer: deletes the entries of events whose starts_at + {@code retention} is at or before {@code now}. */
    int purge(Duration retention, Instant now);
}
