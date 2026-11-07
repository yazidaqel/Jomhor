package ma.jomhor.tickets.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.tickets.domain.Ticket;

/**
 * Persistence port for tickets. Scoped by organizer_id. Tickets are immutable after issue: only {@code status} moves
 * ({@link #setStatus}).
 */
public interface Tickets {

    UUID insert(UUID org, UUID orderId, int eventId, int zoneId, UUID priceCategoryId, UUID holderUserId,
                String holderName, String holderInitials);

    /** Every ticket of the order (any status, any holder), by id. */
    List<Ticket.OfOrder> inOrder(UUID org, UUID orderId);

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

    /** The ids among {@code ticketIds} of tickets of the event (any status). */
    Set<UUID> ofEvent(UUID org, int eventId, Collection<UUID> ticketIds);

    /** VALID tickets of the event per zone id (zones without one are absent). */
    Map<Integer, Long> validByZone(UUID org, int eventId);

    /** The ticket, its row locked ({@code for no key update}: scans may still reference it) until the end of the transaction. */
    Optional<Ticket.Locked> lock(UUID org, UUID ticketId);

    /** The only ticket write after issue: its status (block, unblock, refund). */
    void setStatus(UUID org, UUID ticketId, Ticket.Status status);

    /** What a staff lookup matches on (docs/04-api.md POST /org/events/{id}/tickets/search). */
    enum Match {
        /** 8 lowercase hex chars: the end of ticket_id. */
        TICKET_NO,
        /** The holder's E.164 phone, exact. */
        PHONE,
        /** A normalised cash code: the tickets of its order. */
        CASH_CODE
    }

    /** The event's tickets matching {@code value}, by ticket_no then id, at most {@code limit}. */
    List<Row> search(UUID org, int eventId, Match match, String value, int limit);

    /** One ticket as a lookup row. */
    Optional<Row> row(UUID org, UUID ticketId);

    /**
     * A staff lookup row: the ticket, its zone, its order (channel, payment method, the item's unit price snapshot in
     * centimes) and its refund, if any ({@code refundId} null = none). {@code holderName} is the full name: staff
     * only, never in a log.
     */
    record Row(UUID id, Ticket.Status status, int zoneId, String zoneCode, String zoneName, String holderName,
               long unitPriceMad, UUID orderId, String channel, String paymentMethod, Instant issuedAt,
               UUID refundId, String refundStatus, Long refundAmountMad) {
        @Override
        public String toString() {
            return "Row[" + id + ", " + status + "]";
        }
    }

    /** The database clock (now()), so "past" agrees with every other time rule. */
    Instant now();
}
