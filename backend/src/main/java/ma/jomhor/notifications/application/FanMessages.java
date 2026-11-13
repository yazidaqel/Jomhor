package ma.jomhor.notifications.application;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import ma.jomhor.notifications.domain.FanMessage;

/** Persistence port for the fan_messages outbox. Scoped by organizer_id. */
public interface FanMessages {

    /** One TICKET_RECEIVED PUSH to the ticket's holder. */
    void enqueueTicket(UUID org, UUID ticketId);

    /**
     * One {@code kind} row per order and channel, to the order's user (orders without one are skipped); a row already
     * queued for (order, kind, channel) is left as it is.
     */
    void enqueueOrders(UUID org, Collection<UUID> orderIds, FanMessage.Kind kind, List<FanMessage.Channel> channels);

    /**
     * Up to {@code limit} PENDING rows due now, transactional kinds before campaigns, oldest first, locked for the
     * caller's transaction ({@code for update skip locked}).
     */
    List<FanMessage> claimDue(int limit);

    void markSent(UUID org, UUID id);

    /** One more failed try: PENDING again after {@code retryIn}, or FAILED when it is null. */
    void markFailed(UUID org, UUID id, String error, Duration retryIn);
}
