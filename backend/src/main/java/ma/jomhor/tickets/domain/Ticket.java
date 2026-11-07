package ma.jomhor.tickets.domain;

import java.time.Instant;
import java.util.UUID;

/** An issued ticket as its holder sees it. Immutable after issue; only {@code status} moves. */
public record Ticket(UUID id, UUID organizerId, int eventId, int zoneId, Status status, String holderName,
                     String seatLabel, Instant issuedAt) {

    public enum Status { VALID, CANCELLED, REFUNDED, BLOCKED }

    /** What a gate manifest carries of a ticket: never the holder's name, phone or id number. */
    public record ManifestRow(UUID id, int zoneId, Status status, String holderInitials) {}
}
