package ma.jomhor.catalog.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What catalog needs from the on-sale waiting room (docs/04-api.md §Waiting room). Implemented by the sales module:
 * catalog never reads its tables.
 */
public interface EventRooms {

    /** GET /fan/events* {@code room}; {@code eligibility} null when the room sets no rule. */
    record PublicRoom(String status, Instant opensAt, Eligibility eligibility) {}

    /** {@code verifiedBefore} = sale_starts_at − min_account_age_days; each null = not required. */
    record Eligibility(Instant verifiedBefore, Instant memberJoinedBefore, Integer minEventsAttended) {}

    /** The event's room as fans see it; empty when it has none or it has ENDED. May be one memo TTL old. */
    Optional<PublicRoom> publicRoom(int eventId);

    /**
     * Event PATCH moving sale_starts_at: 409 EVENT_NOT_EDITABLE {@code ROOM_STARTED} while the room is PRE_QUEUE, OPEN
     * or PAUSED. Holds the room row until the caller's transaction ends, so the room tick can't start it meanwhile.
     */
    void checkSaleStartMovable(UUID org, int eventId);
}
