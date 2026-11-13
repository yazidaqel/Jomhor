package ma.jomhor.sales.rooms.application;

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
}
