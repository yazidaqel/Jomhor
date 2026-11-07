package ma.jomhor.tickets.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An issued ticket as its holder sees it. Immutable after issue; only {@code status} moves. {@code transferred} is
 * set when this holder transferred it away (it is then CANCELLED), else null.
 */
public record Ticket(UUID id, UUID organizerId, int eventId, int zoneId, Status status, String holderName,
                     String seatLabel, Instant issuedAt, Transferred transferred) {

    public enum Status { VALID, CANCELLED, REFUNDED, BLOCKED }

    /** A transfer away: the new ticket's holder name (full: shown as its label only) and when. */
    public record Transferred(String toName, Instant at) {
        @Override
        public String toString() {
            return "Transferred[" + at + "]";
        }
    }

    /** A ticket of an order as a till prints it: {@code holderName} goes on paper as its label only. */
    public record OfOrder(UUID id, int eventId, int zoneId, UUID priceCategoryId, Status status, String holderName) {
        /** Without the holder's name: it stays out of logs. */
        @Override
        public String toString() {
            return "OfOrder[id=" + id + ", status=" + status + "]";
        }
    }

    /**
     * A ticket locked for a status change: what block, unblock, refund, transfer and re-issue need. {@code holderName}
     * stays out of logs.
     */
    public record Locked(UUID id, UUID organizerId, int eventId, int zoneId, UUID orderId, UUID priceCategoryId,
                         Status status, String holderName) {
        @Override
        public String toString() {
            return "Locked[" + id + ", " + status + "]";
        }
    }

    /** What a gate manifest carries of a ticket: never the holder's name, phone or id number. */
    public record ManifestRow(UUID id, int zoneId, Status status, String holderInitials) {}
}
