package ma.jomhor.notifications.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import ma.jomhor.notifications.domain.FanMessage.Channel;
import ma.jomhor.notifications.domain.FanMessage.Kind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues fan messages (docs/04-api.md §Notifications) in the caller's transaction: a rollback queues nothing. Sent
 * after commit by {@link FanMessageService#deliverDue}. Its only dependency is the outbox, so sales, payments and
 * tickets can call it without a constructor cycle through the sender (which reads them).
 */
@Service
public class FanMessageQueue {

    private final FanMessages messages;

    public FanMessageQueue(FanMessages messages) {
        this.messages = messages;
    }

    /** A transfer: PUSH to the new ticket's holder. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ticketReceived(UUID org, UUID newTicketId) {
        messages.enqueueTicket(org, newTicketId);
    }

    /** The buyer's order is PAID: WHATSAPP and PUSH to each member order still held. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void splitInvites(UUID org, Collection<UUID> memberOrderIds) {
        messages.enqueueOrders(org, memberOrderIds, Kind.SPLIT_INVITE, List.of(Channel.WHATSAPP, Channel.PUSH));
    }

    /** The first cash code of an order: WHATSAPP to the order's user (a later code adds nothing, plan Q128). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void payCode(UUID org, UUID orderId) {
        messages.enqueueOrders(org, List.of(orderId), Kind.PAY_CODE, List.of(Channel.WHATSAPP));
    }

    /** AWAITING_CASH → PAID, or a late success reviving an EXPIRED order: PUSH to the buyer. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void orderPaid(UUID org, UUID orderId) {
        messages.enqueueOrders(org, List.of(orderId), Kind.ORDER_PAID, List.of(Channel.PUSH));
    }

    /** A campaign's audience snapshot: one row per user (the job, docs/04-api.md §Campaigns). Rows added. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int campaign(UUID org, UUID campaignId, Channel channel, Collection<UUID> userIds) {
        return messages.enqueueCampaign(org, campaignId, channel, userIds);
    }
}
