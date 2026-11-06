package ma.jomhor.notifications.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.notifications.domain.Delivery;
import ma.jomhor.tenancy.application.UserService;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tickets.domain.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WhatsApp ticket delivery (docs/04-api.md §Fan, plan BE-14) through the ticket_deliveries outbox: rows are queued
 * in the caller's transaction (the settle that issues the tickets, or a re-send) and sent after commit by
 * {@link #deliverDue}, at least once. The recipient is the ticket's holder, its phone read at send time.
 */
@Service
public class TicketDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(TicketDeliveryService.class);
    static final int BATCH = 50;
    static final int MAX_RESENDS = 3;
    static final Duration RESEND_WINDOW = Duration.ofHours(24);

    private final TicketDeliveries deliveries;
    private final TicketService tickets;
    private final UserService users;
    private final TicketMessenger messenger;
    private final TransactionTemplate tx;
    /** One savepoint per row: a database error while sending one row can't abort the batch's transaction. */
    private final TransactionTemplate perRow;

    public TicketDeliveryService(TicketDeliveries deliveries, TicketService tickets, UserService users,
                                 TicketMessenger messenger, PlatformTransactionManager transactions) {
        this.deliveries = deliveries;
        this.tickets = tickets;
        this.users = users;
        this.messenger = messenger;
        this.tx = new TransactionTemplate(transactions);
        this.perRow = new TransactionTemplate(transactions);
        this.perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** One delivery per issued ticket, in the caller's transaction: a rollback queues nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID org, List<UUID> ticketIds) {
        deliveries.enqueue(org, ticketIds);
    }

    /**
     * 04 POST /fan/tickets/{id}/whatsapp: holder only (404), VALID only (409 TICKET_NOT_VALID), at most 3
     * re-sends per ticket in 24 h (429 DELIVERY_RATE_LIMITED). The automatic delivery is not a re-send.
     */
    @Transactional
    public void resend(UUID holderUserId, UUID ticketId) {
        Ticket t = tickets.heldValid(holderUserId, ticketId);
        deliveries.lockTicket(t.organizerId(), t.id());
        if (deliveries.resendsWithin(t.organizerId(), t.id(), t.issuedAt(), RESEND_WINDOW) >= MAX_RESENDS) {
            throw new ApiException("DELIVERY_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
                    "Too many WhatsApp re-sends of this ticket");
        }
        deliveries.enqueue(t.organizerId(), List.of(t.id()));
    }

    /** Sends due deliveries batch after batch until a batch comes back short. Returns the number claimed. */
    public int deliverDue() {
        int total = 0;
        int claimed;
        do {
            claimed = tx.execute(s -> deliverBatch());
            total += claimed;
        } while (claimed == BATCH);
        return total;
    }

    /**
     * The claimed rows stay locked until the batch commits, so a parallel run (another instance) skips them and
     * a row is never sent twice by two runs. A crash between send and commit sends it again (at least once).
     * ponytail: provider calls run inside the transaction (up to 50 per connection); claim-then-lease when a slow
     * provider makes that hurt.
     */
    private int deliverBatch() {
        List<Delivery> due = deliveries.claimDue(BATCH);
        for (Delivery d : due) {
            String error = null;
            try {
                perRow.executeWithoutResult(s -> send(d));
            } catch (TicketMessenger.Failed e) {
                error = e.code();
            } catch (ApiException e) {
                error = e.code();
            } catch (RuntimeException e) {
                // The class only: an exception message could carry the phone or a provider response body.
                error = e.getClass().getSimpleName();
            }
            if (error == null) {
                deliveries.markSent(d.organizerId(), d.id());
            } else {
                log.warn("Ticket delivery {} failed: {}", d.id(), error);
                int attempts = d.attempts() + 1;
                // A ticket that is no longer VALID never will be again: no retry.
                deliveries.markFailed(d.organizerId(), d.id(), error,
                        "TICKET_NOT_VALID".equals(error) ? null : Delivery.backoff(attempts));
            }
        }
        return due.size();
    }

    /** Renders the holder's PDF and hands it to the messenger, to the holder's phone as it is now. */
    private void send(Delivery d) {
        UUID holder = tickets.holder(d.organizerId(), d.ticketId()).orElseThrow();
        TicketService.Pdf pdf = tickets.pdf(holder, d.ticketId());
        messenger.send(new TicketMessenger.Message(users.get(holder).phone(), pdf.ticketNo(), pdf.content()));
    }
}
