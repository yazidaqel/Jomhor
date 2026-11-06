package ma.jomhor.tickets.application;

import java.util.List;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues tickets for a paid order (one per place, status VALID) inside the caller's transaction. The holder is set
 * at insert and never changes (tickets are immutable after issue).
 */
@Service
public class TicketService {

    /**
     * One place: its zone and category, and who holds it. {@code holderName} is a normalized name (docs/04-api.md
     * §Holder names), or null for a Lot 0 order (no name, no initials).
     */
    public record Seat(int zoneId, UUID priceCategoryId, UUID holderUserId, String holderName) {}

    private final Tickets tickets;

    public TicketService(Tickets tickets) {
        this.tickets = tickets;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> issue(UUID org, UUID orderId, int eventId, List<Seat> seats) {
        return seats.stream().map(s -> tickets.insert(org, orderId, eventId, s.zoneId(), s.priceCategoryId(),
                s.holderUserId(), s.holderName(), s.holderName() == null ? null : HolderNames.initials(s.holderName())))
                .toList();
    }

    /** The order's tickets this user holds (04: a friend's ticket is never listed to the buyer). */
    public List<UUID> heldInOrder(UUID org, UUID orderId, UUID holderUserId) {
        return tickets.heldInOrder(org, orderId, holderUserId);
    }
}
