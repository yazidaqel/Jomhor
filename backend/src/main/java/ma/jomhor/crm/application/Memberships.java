package ma.jomhor.crm.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.crm.domain.Member;

/** Persistence port of memberships, and the loyalty reads over tickets and scans (read-only, tenant-filtered). */
public interface Memberships {

    /** The user's row in this organizer's club, left or not. */
    Optional<Member> find(UUID org, UUID userId);

    /**
     * The organizer's membership lock, until the transaction ends: advisory, two-int key (class 4, hashtext of the
     * organizer id); see common/EventLock for the classes.
     */
    void lock(UUID org);

    /** Inserts the user with the organizer's next number; under {@link #lock}, so the number is free. */
    Member insertNext(UUID org, UUID userId, boolean push, boolean whatsapp);

    /** A left membership back: clears left_at, sets both opt-ins (number and joined_at kept). False if it was live. */
    boolean rejoin(UUID org, UUID id, boolean push, boolean whatsapp);

    /** Sets both opt-ins of a live membership; true only if one of them changed. */
    boolean setOptIns(UUID org, UUID id, boolean push, boolean whatsapp);

    /** Leaves a live membership (left_at = now, opt-ins off); its id, or empty if none was live. */
    Optional<UUID> leave(UUID org, UUID userId);

    /** Distinct events of the organizer where the user holds a ticket with an admitted (OK/OVERRIDE, IN) scan. */
    long eventsAttended(UUID org, UUID userId);

    /** The organizer's {@code limit} latest CLOSED events (starts_at, id), oldest first. */
    List<PastEvent> lastEvents(UUID org, UUID userId, int limit);

    record PastEvent(int eventId, String title, String eventType, Instant startsAt, String timezone,
                     boolean attended) {}
}
