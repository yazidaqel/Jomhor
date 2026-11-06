package ma.jomhor.tickets.application;

import java.util.List;
import java.util.UUID;

/** Persistence port for tickets. Scoped by organizer_id. Tickets are insert-only here (immutable after issue). */
public interface Tickets {

    UUID insert(UUID org, UUID orderId, int eventId, int zoneId, UUID priceCategoryId, UUID holderUserId);

    /** Ids of the order's tickets, ascending. */
    List<UUID> ofOrder(UUID org, UUID orderId);
}
