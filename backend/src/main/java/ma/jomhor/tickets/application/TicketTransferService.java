package ma.jomhor.tickets.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.HolderNames;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.gate.application.ScanService;
import ma.jomhor.notifications.application.TicketDeliveryService;
import ma.jomhor.tenancy.application.AuthService;
import ma.jomhor.tenancy.application.UserService;
import ma.jomhor.tickets.domain.Ticket;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * docs/04-api.md §Fan POST /fan/tickets/{id}/transfer and /reissue (plan lot-4 Q59). One transaction, the ticket row
 * locked first: the old ticket goes CANCELLED (its status only), a new ticket is issued through
 * {@link TicketService#issue} in the same order, zone and price category (a new id, so a new K_t), and a
 * ticket_transfers row links them. Price categories, sold and quota are not touched. The next manifest version carries
 * both rows.
 */
@Service
public class TicketTransferService {

    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");

    /** 201 of a transfer: {@code toLabel} is the recipient's holder label. */
    public record Transferred(UUID transferId, String toLabel, Instant transferredAt) {}

    private final Tickets tickets;
    private final TicketService issuer;
    private final EventService events;
    private final UserService users;
    private final TicketDeliveryService deliveries;
    private final ScanService scans;
    private final AuditLog audit;
    private final JsonMapper json;
    private final AuthService auth;

    public TicketTransferService(Tickets tickets, TicketService issuer, EventService events, UserService users,
                                 TicketDeliveryService deliveries, ScanService scans, AuditLog audit,
                                 JsonMapper json, AuthService auth) {
        this.tickets = tickets;
        this.issuer = issuer;
        this.events = events;
        this.users = users;
        this.deliveries = deliveries;
        this.scans = scans;
        this.audit = audit;
        this.json = json;
        this.auth = auth;
    }

    /**
     * To the account of {@code toPhone} (created if new), named {@code name}; one WhatsApp delivery of the new ticket
     * to the recipient. The caller's own phone → 422 on to_phone, reason SELF (a re-issue).
     */
    @Transactional
    public Transferred transfer(UUID caller, UUID ticketId, String toPhone, String name) {
        Ticket.Locked t = lockOpen(caller, ticketId);
        if (toPhone == null || !E164.matcher(toPhone).matches()) {
            throw invalid("to_phone", "an E.164 phone number", null);
        }
        if (toPhone.equals(users.get(caller).phone())) {
            throw invalid("to_phone", "not your own phone: use a re-issue", "SELF");
        }
        String holderName = HolderNames.normalize(name).orElseThrow(() -> invalid("name", HolderNames.INVALID, null));
        UUID to = users.holder(toPhone);
        UUID newId = replace(t, to, holderName);
        Tickets.Transfer tr = tickets.insertTransfer(t.organizerId(), t.id(), newId, caller, to);
        deliveries.enqueue(t.organizerId(), List.of(newId));
        // BE-30: queue TICKET_RECEIVED push here
        audit.user(t.organizerId(), caller, "ticket.transfer", t.id().toString(), json.writeValueAsString(Map.of(
                "transfer_id", tr.id().toString(), "new_ticket_id", newId.toString(), "to_user_id", to.toString())));
        return new Transferred(tr.id(), HolderNames.label(holderName), tr.transferredAt());
    }

    /** Lost or replaced phone: the same checks, to the caller with the same name. No delivery. Returns the new id. */
    @Transactional
    public UUID reissue(UUID caller, UUID sessionId, UUID ticketId) {
        Ticket.Locked t = lockOpen(caller, ticketId);
        auth.fanRevokeOtherSessions(sessionId, caller); // the lost phone can't fetch the new K_t
        UUID newId = replace(t, caller, t.holderName());
        Tickets.Transfer tr = tickets.insertTransfer(t.organizerId(), t.id(), newId, caller, caller);
        audit.user(t.organizerId(), caller, "ticket.reissue", t.id().toString(), json.writeValueAsString(Map.of(
                "transfer_id", tr.id().toString(), "new_ticket_id", newId.toString())));
        return newId;
    }

    /**
     * Locks the caller's ticket (404 otherwise); not VALID → 409 TICKET_NOT_VALID (a parallel transfer that won the
     * lock left it CANCELLED); an admitted scan on the server, read under the lock → 409 TICKET_NOT_VALID, reason
     * ADMITTED; now (database clock) at or after transfer_until, or no cutoff → 409 TRANSFER_CLOSED.
     */
    private Ticket.Locked lockOpen(UUID caller, UUID ticketId) {
        Ticket.Locked t = tickets.lockHeld(caller, ticketId)
                .orElseThrow(() -> ApiException.notFound("Ticket not found"));
        if (t.status() != Ticket.Status.VALID) {
            throw new ApiException("TICKET_NOT_VALID", HttpStatus.CONFLICT, "Ticket is not valid");
        }
        if (scans.admitted(t.organizerId(), t.eventId(), t.id())) {
            throw new ApiException("TICKET_NOT_VALID", HttpStatus.CONFLICT, "Ticket was already admitted",
                    Map.of("reason", "ADMITTED"));
        }
        Instant until = issuer.transferUntil(events.get(t.organizerId(), t.eventId()).refundCutoffAt());
        if (until == null || !tickets.now().isBefore(until)) {
            Map<String, Object> details = new HashMap<>();
            details.put("transfer_until", until);
            throw new ApiException("TRANSFER_CLOSED", HttpStatus.CONFLICT, "Transfers are closed for this event",
                    details);
        }
        return t;
    }

    /** Cancels the old ticket (status only) and issues its replacement for {@code holder}. */
    private UUID replace(Ticket.Locked t, UUID holder, String holderName) {
        tickets.setStatus(t.organizerId(), t.id(), Ticket.Status.CANCELLED);
        return issuer.issue(t.organizerId(), t.orderId(), t.eventId(),
                List.of(new TicketService.Seat(t.zoneId(), t.priceCategoryId(), holder, holderName))).getFirst();
    }

    private static ApiException invalid(String field, String message, String reason) {
        Map<String, Object> details = new HashMap<>();
        details.put("fields", Map.of(field, message));
        if (reason != null) {
            details.put("reason", reason);
        }
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body", details);
    }
}
