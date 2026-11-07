package ma.jomhor.tickets.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.payments.application.RefundService;
import ma.jomhor.payments.domain.CashCode;
import ma.jomhor.tickets.domain.Ticket;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Staff ticket actions (docs/04-api.md §Organizer tickets, plan Q52): lookup, block, unblock, refund. A ticket changes
 * by its {@code status} only. Each action locks the ticket row first, then (refund) its price category through
 * Inventory: no other path takes a category before a ticket, so nothing deadlocks.
 */
@Service
public class TicketAdminService {

    static final int SEARCH_LIMIT = 50;
    private static final Pattern TICKET_NO = Pattern.compile("[0-9A-Fa-f]{8}");
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");

    private final Tickets tickets;
    private final EventService events;
    private final Inventory inventory;
    private final RefundService refunds;
    private final AuditLog audit;
    private final JsonMapper json;

    public TicketAdminService(Tickets tickets, EventService events, Inventory inventory, RefundService refunds,
                              AuditLog audit, JsonMapper json) {
        this.tickets = tickets;
        this.events = events;
        this.inventory = inventory;
        this.refunds = refunds;
        this.audit = audit;
        this.json = json;
    }

    /**
     * 04 POST /org/events/{id}/tickets/search. {@code q}: a ticket number (8 hex, spaces ignored: every ticket of the
     * event ending so), else a holder phone (E.164, exact), else a cash code (04 §Cash codes Input); anything else →
     * 422 on q. Unknown or another organizer's event → 404.
     */
    public List<Tickets.Row> search(UUID org, int eventId, String q) {
        events.get(org, eventId);
        String s = q == null ? "" : q.strip();
        String hex = s.replaceAll("\\s", "");
        if (TICKET_NO.matcher(hex).matches()) {
            return tickets.search(org, eventId, Tickets.Match.TICKET_NO, hex.toLowerCase(Locale.ROOT),
                    SEARCH_LIMIT);
        }
        if (E164.matcher(s).matches()) {
            return tickets.search(org, eventId, Tickets.Match.PHONE, s, SEARCH_LIMIT);
        }
        String code = CashCode.normalise(s).orElseThrow(() -> invalid("q", "a ticket number, a phone or a cash code"));
        return tickets.search(org, eventId, Tickets.Match.CASH_CODE, code, SEARCH_LIMIT);
    }

    /** 04 POST /org/tickets/{id}/block: VALID → BLOCKED. The next manifest carries it; no K_t, no PDF from then on. */
    @Transactional
    public Tickets.Row block(UUID org, UUID user, UUID ticketId, String reason) {
        return move(org, user, ticketId, reason, Set.of(Ticket.Status.VALID), Ticket.Status.BLOCKED, "ticket.block");
    }

    /** 04 POST /org/tickets/{id}/unblock: BLOCKED → VALID. */
    @Transactional
    public Tickets.Row unblock(UUID org, UUID user, UUID ticketId, String reason) {
        return move(org, user, ticketId, reason, Set.of(Ticket.Status.BLOCKED), Ticket.Status.VALID, "ticket.unblock");
    }

    /**
     * 04 POST /org/tickets/{id}/refund: VALID or BLOCKED → REFUNDED, its place released (sold − 1) and a REQUIRED
     * refund of the item's unit price, in one transaction. The order stays PAID.
     */
    // ponytail: the refunded place still counts in the buyer's per_account_limit (plan Q52 residual, Lot 5).
    @Transactional
    public Tickets.Row refund(UUID org, UUID user, UUID ticketId, String reason) {
        String r = reason(reason);
        Ticket.Locked t = lockMovable(org, ticketId, Set.of(Ticket.Status.VALID, Ticket.Status.BLOCKED));
        tickets.setStatus(org, ticketId, Ticket.Status.REFUNDED);
        inventory.release(List.of(new Inventory.Hold(org, t.eventId(), t.priceCategoryId(), 1)));
        long amountMad = tickets.row(org, ticketId).orElseThrow().unitPriceMad();
        UUID refundId = refunds.request(org, user, t.eventId(), ticketId, t.orderId(), amountMad, r);
        audit.staff(org, user, "ticket.refund", ticketId.toString(), json.writeValueAsString(
                Map.of("reason", r, "refund_id", refundId.toString(), "amount_mad", amountMad)));
        return tickets.row(org, ticketId).orElseThrow();
    }

    private Tickets.Row move(UUID org, UUID user, UUID ticketId, String reason, Set<Ticket.Status> from,
                             Ticket.Status to, String action) {
        String r = reason(reason);
        lockMovable(org, ticketId, from);
        tickets.setStatus(org, ticketId, to);
        audit.staff(org, user, action, ticketId.toString(), json.writeValueAsString(Map.of("reason", r)));
        return tickets.row(org, ticketId).orElseThrow();
    }

    /**
     * Locks the ticket (another organizer's or unknown → 404); at or after the event's refund_cutoff_at (database
     * clock) → 409 REFUND_FROZEN; a status outside {@code from} → 409 TICKET_STATUS.
     */
    private Ticket.Locked lockMovable(UUID org, UUID ticketId, Set<Ticket.Status> from) {
        Ticket.Locked t = tickets.lock(org, ticketId).orElseThrow(() -> ApiException.notFound("Ticket not found"));
        Instant cutoff = events.get(org, t.eventId()).refundCutoffAt();
        if (cutoff == null || !tickets.now().isBefore(cutoff)) {
            throw new ApiException("REFUND_FROZEN", HttpStatus.CONFLICT, "Tickets can no longer change for this event");
        }
        if (!from.contains(t.status())) {
            throw new ApiException("TICKET_STATUS", HttpStatus.CONFLICT, "Not allowed for this ticket's status",
                    Map.of("status", t.status().name()));
        }
        return t;
    }

    /** {@code reason} 1 to 200 characters (trimmed), else 422 on reason. */
    private static String reason(String reason) {
        String r = reason == null ? "" : reason.strip();
        int length = r.codePointCount(0, r.length());
        if (length < 1 || length > 200) {
            throw invalid("reason", "1 to 200 characters");
        }
        return r;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }
}
