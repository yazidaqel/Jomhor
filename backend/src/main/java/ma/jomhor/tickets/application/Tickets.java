package ma.jomhor.tickets.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.tickets.domain.Ticket;

/** Persistence port for tickets. Scoped by organizer_id. Tickets are insert-only here (immutable after issue). */
public interface Tickets {

    UUID insert(UUID org, UUID orderId, int eventId, int zoneId, UUID priceCategoryId, UUID holderUserId,
                String holderName, String holderInitials);

    /** Ids of the order's tickets held by {@code holderUserId}, ascending. */
    List<UUID> heldInOrder(UUID org, UUID orderId, UUID holderUserId);

    /**
     * Every ticket held by {@code holderUserId}, by event then id. A fan read across organizers: the holder is the
     * scope, and each row's organizer_id scopes whatever is read for it next.
     */
    List<Ticket> heldBy(UUID holderUserId);

    /** The ticket {@code ticketId} if {@code holderUserId} holds it (same scope as {@link #heldBy(UUID)}). */
    Optional<Ticket> heldBy(UUID holderUserId, UUID ticketId);

    /** The holder of ticket {@code ticketId} of organizer {@code org}. */
    Optional<UUID> holder(UUID org, UUID ticketId);

    /** Every ticket of the event (any status), by id: id, zone, status and initials only. */
    List<Ticket.ManifestRow> manifestRows(UUID org, int eventId);

    /** The database clock (now()), so "past" agrees with every other time rule. */
    Instant now();
}
