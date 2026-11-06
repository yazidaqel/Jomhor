package ma.jomhor.tickets.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Issues tickets for a paid order (one per place, status VALID) inside the caller's transaction. */
@Service
public class TicketService {

    /** {@code qty} places of one price category on one zone. */
    public record Line(int zoneId, UUID priceCategoryId, int qty) {}

    private final Tickets tickets;

    public TicketService(Tickets tickets) {
        this.tickets = tickets;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> issue(UUID org, UUID orderId, int eventId, UUID holderUserId, List<Line> lines) {
        List<UUID> ids = new ArrayList<>();
        for (Line line : lines) {
            for (int i = 0; i < line.qty(); i++) {
                ids.add(tickets.insert(org, orderId, eventId, line.zoneId(), line.priceCategoryId(), holderUserId));
            }
        }
        return ids;
    }

    public List<UUID> ofOrder(UUID org, UUID orderId) {
        return tickets.ofOrder(org, orderId);
    }
}
