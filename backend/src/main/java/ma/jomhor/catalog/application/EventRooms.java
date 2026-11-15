package ma.jomhor.catalog.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What catalog needs from the on-sale waiting rooms, one per sale phase (docs/04-api.md §Waiting room Phases).
 * Implemented by the sales module: catalog never reads its tables.
 */
public interface EventRooms {

    /**
     * GET /fan/events* {@code room}: the current phase, its sale start and opens_at; {@code eligibility} null when the
     * phase sets no rule; {@code next} the following phase not ENDED, or null.
     */
    record PublicRoom(int phase, String label, String status, Instant opensAt, Instant saleStartsAt,
                      Eligibility eligibility, NextPhase next) {}

    /** {@code verifiedBefore} = sale_starts_at − min_account_age_days; each null = not required. */
    record Eligibility(Instant verifiedBefore, Instant memberJoinedBefore, Integer minEventsAttended) {}

    record NextPhase(int phase, String label, Instant opensAt, Instant saleStartsAt) {}

    /** The event's current phase as fans see it; empty when none or every phase has ENDED. May be one memo TTL old. */
    Optional<PublicRoom> publicRoom(int eventId);

    /**
     * Event PATCH moving sale_starts_at to {@code saleStartsAt}: 409 EVENT_NOT_EDITABLE {@code ROOM_STARTED} while phase
     * 1 is PRE_QUEUE, OPEN or PAUSED; while it is SCHEDULED, 422 on sale_starts_at ({@code PHASE_ORDER}) when the
     * first later phase not ENDED would no longer open after it. Holds phase 1's row until the caller's transaction ends, so the room
     * tick can't start it meanwhile.
     */
    void checkSaleStartMovable(UUID org, int eventId, Instant saleStartsAt);
}
