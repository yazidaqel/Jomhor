package ma.jomhor.sales.invitations.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.application.OrderService.ItemRequest;
import ma.jomhor.sales.application.OrderService.View;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tickets.domain.Ticket;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invitations and accreditations (docs/04-api.md §Invitations): named FREE tickets from INVITE categories, issued by
 * staff in one PAID POS order with no buyer and no payment ({@code orders.invited_by}). {@code org} is always
 * OrgContext.organizerId(), {@code user} the caller. Audits carry ids and counts only: never a name or phone.
 */
@Service
public class InvitationService {

    /** 04 GET /org/events/{id}/invitations: at most 200. */
    static final int LIST_MAX = 200;

    /** An issued invitation; {@code replay} = the same invitation_id again (200, nothing written). */
    public record Issued(View view, boolean replay) {}

    /** An invitation with its tickets (any status, by id). */
    public record Listed(Orders.Invitation invitation, List<Ticket.OfOrder> tickets) {}

    /** The event's categories by id (names and zones) and its invitations, newest first. */
    public record Listing(Map<UUID, Event.PriceCategory> categories, List<Listed> invitations) {}

    /** Print data: the order, the unit price per category (snapshot) and the slips. Not for logs. */
    public record Print(Order order, Map<UUID, Long> unitPrices, TicketService.Slips slips) {
        @Override
        public String toString() {
            return "Print[" + order.id() + "]";
        }
    }

    private final OrderService orders;
    private final Orders store;
    private final EventService events;
    private final TicketService tickets;
    private final AuditLog audit;
    private final JsonMapper json;

    public InvitationService(OrderService orders, Orders store, EventService events, TicketService tickets,
                             AuditLog audit, JsonMapper json) {
        this.orders = orders;
        this.store = store;
        this.events = events;
        this.tickets = tickets;
        this.audit = audit;
        this.json = json;
    }

    /**
     * 04 POST /org/events/{id}/invitations. Event of this organizer (404); {@code invitationId} already an invitation
     * of this event → it again (replay), any other order → 422 TAKEN on invitation_id; ON_SALE or GATES_OPEN (409
     * EVENT_NOT_ON_SALE NOT_ON_SALE) before transfer_until (CLOSED + transfer_until), database clock; then sales
     * checks and writes the rest ({@link OrderService#placeInvitation}), audited {@code invitation.create}.
     */
    @Transactional
    public Issued create(UUID org, UUID user, UUID invitationId, int eventId, List<ItemRequest> items) {
        Event e = events.get(org, eventId);
        if (orders.exists(invitationId)) {
            if (store.invitationEvent(org, invitationId).filter(id -> id == eventId).isEmpty()) {
                throw taken();
            }
            return new Issued(orders.inOrg(org, invitationId).orElseThrow(), true);
        }
        if (e.status() != Event.Status.ON_SALE && e.status() != Event.Status.GATES_OPEN) {
            throw notOnSale("NOT_ON_SALE", null);
        }
        Instant until = tickets.transferUntil(e.refundCutoffAt());
        if (until == null || !events.now().isBefore(until)) {
            throw notOnSale("CLOSED", until);
        }
        View v = orders.placeInvitation(org, user, invitationId, eventId, items).orElseThrow(InvitationService::taken);
        List<Map<String, Object>> lines = v.order().items().stream()
                .map(i -> Map.<String, Object>of("price_category_id", i.priceCategoryId().toString(), "qty", i.qty()))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", invitationId.toString());
        payload.put("places", v.order().places());
        payload.put("items", lines);
        audit.staff(org, user, "invitation.create", invitationId.toString(), json.writeValueAsString(payload));
        return new Issued(v, false);
    }

    /** 04 GET /org/events/{id}/invitations. Unknown or another organizer's event → 404. */
    public Listing list(UUID org, int eventId) {
        Map<UUID, Event.PriceCategory> categories = events.prices(org, eventId).stream()
                .collect(Collectors.toMap(Event.PriceCategory::id, Function.identity()));
        // ponytail: tickets read per invitation (at most 200 small reads); one query by order ids if it gets slow.
        return new Listing(categories, store.invitations(org, eventId, LIST_MAX).stream()
                .map(i -> new Listed(i, tickets.inOrder(org, i.order().id()))).toList());
    }

    /**
     * 04 GET /org/invitations/{id}/tickets: an invitation order of this organizer (else 404), its slips with the
     * static QR of each VALID ticket (signed inside withEventKey by TicketService). Audited {@code invitation.print}.
     */
    @Transactional
    public Print print(UUID org, UUID user, UUID orderId) {
        int eventId = store.invitationEvent(org, orderId).orElseThrow(() -> ApiException.notFound("Invitation not found"));
        Order o = orders.inOrg(org, orderId).orElseThrow().order();
        Map<UUID, Long> prices = new LinkedHashMap<>();
        o.items().forEach(i -> prices.put(i.priceCategoryId(), i.unitPriceMad()));
        TicketService.Slips slips = tickets.slips(org, eventId, orderId);
        audit.staff(org, user, "invitation.print", orderId.toString(), json.writeValueAsString(
                Map.of("order_id", orderId.toString(), "tickets", slips.slips().size())));
        return new Print(o, prices, slips);
    }

    private static ApiException taken() {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of("invitation_id", "already used"), "reason", "TAKEN"));
    }

    /** 04 EVENT_NOT_ON_SALE, Lot 5 step 4: NOT_ON_SALE, or CLOSED with transfer_until (null: no cutoff). */
    private static ApiException notOnSale(String reason, Instant transferUntil) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        if (reason.equals("CLOSED")) {
            details.put("transfer_until", transferUntil == null ? null : transferUntil.toString());
        }
        return new ApiException("EVENT_NOT_ON_SALE", HttpStatus.CONFLICT, "Invitations are closed for this event",
                details);
    }
}
