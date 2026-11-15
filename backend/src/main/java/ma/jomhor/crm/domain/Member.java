package ma.jomhor.crm.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A memberships row (docs/03-schema.sql): a fan in an organizer's club; {@code leftAt} set = left; {@code paidUntil}
 * the latest MEMBERSHIP product's valid_until paid for, null = never paid.
 */
public record Member(UUID id, int number, Instant joinedAt, Instant leftAt, boolean marketingPush,
                     boolean marketingWhatsapp, Instant paidUntil) {

    public boolean live() {
        return leftAt == null;
    }
}
