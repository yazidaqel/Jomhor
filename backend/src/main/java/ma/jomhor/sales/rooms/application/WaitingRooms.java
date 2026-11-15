package ma.jomhor.sales.rooms.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.rooms.domain.WaitingRoom;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Config;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Status;

/** Persistence port for waiting_rooms. Every method but {@link #live()} is scoped to one organizer. */
public interface WaitingRooms {

    Optional<WaitingRoom> find(UUID org, int eventId);

    /** The room's status, its row locked against the tick and other writers until the transaction ends. */
    Optional<Status> lock(UUID org, int eventId);

    /** The room's status, its row held (for share) until the transaction ends: the tick skips it meanwhile. */
    Optional<Status> share(UUID org, int eventId);

    void insert(UUID org, int eventId, Config config);

    void update(UUID org, int eventId, Config config);

    void setStatus(UUID org, int eventId, Status status);

    /** Every organizer's rooms not ENDED (the memo). */
    List<WaitingRoom> live();

    // ---------- the room tick (04 §Waiting room Tick): by room id, each row carrying its organizer ----------

    /** What the tick reads and writes of a room; the event's times are read without a lock. */
    record Tick(UUID id, UUID organizerId, int eventId, Status status, int preQueueMin, int admissionRatePerMin,
                Instant saleStartsAt, Instant refundCutoffAt, int joinedCount, int rankedCount, int admittedThrough,
                int measuredRatePerMin, Instant rateMarkAt, int rateMarkThrough, boolean soldOut, Instant lastTickAt,
                Instant drawnAt) {}

    /** Ids of every organizer's rooms not ENDED. */
    List<UUID> liveIds();

    /**
     * The room unless ENDED, its row locked {@code for no key update skip locked}: empty when another instance holds it
     * (or an organizer write does), so one instance ticks a room at a time. Never locks the event row.
     */
    Optional<Tick> lockForTick(UUID roomId);

    /** The database's clock_timestamp(): the wall clock now, not the transaction's start (now()). */
    Instant clock();

    /** Writes the tick's columns (everything but the organizer's configuration). */
    void saveTick(Tick t);
}
