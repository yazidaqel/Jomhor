package ma.jomhor.sales.application;

import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The holder limit of a transfer's recipient (docs/04-api.md POST /fan/tickets/{id}/transfer, Lot 5), for tickets:
 * only Orders and Inventory, so tickets doesn't depend on {@link OrderService} (which issues through tickets).
 */
@Service
public class HolderLimit {

    private final Orders orders;
    private final Inventory inventory;

    HolderLimit(Orders orders, Inventory inventory) {
        this.orders = orders;
        this.inventory = inventory;
    }

    /**
     * In the transfer's transaction, after its ticket row: takes the recipient's per-account lock, then the recipient
     * must hold at most per_account_limit places of the event with the ticket, else 409 PER_ACCOUNT_LIMIT, reason
     * HOLDER, {@code field}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRoom(UUID org, int eventId, UUID recipient, String field) {
        orders.lockBuyer(recipient, eventId);
        int limit = inventory.saleEvent(eventId).orElseThrow().perAccountLimit();
        if (orders.holding(org, recipient, eventId) + 1 > limit) {
            throw refused(field, limit);
        }
    }

    /** 409 PER_ACCOUNT_LIMIT, reason HOLDER: {@code field} and {@code limit} only, never {@code held}. */
    static ApiException refused(String field, int limit) {
        return new ApiException("PER_ACCOUNT_LIMIT", HttpStatus.CONFLICT, "Per-account limit reached",
                Map.of("reason", "HOLDER", "field", field, "limit", limit));
    }
}
