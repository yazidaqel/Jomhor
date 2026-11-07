package ma.jomhor.payments.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.HolderNames;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.payments.domain.CashCode;
import ma.jomhor.payments.domain.Payment;
import ma.jomhor.payments.domain.PosSession;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.application.OrderService.ItemRequest;
import ma.jomhor.sales.application.OrderService.Settlement;
import ma.jomhor.sales.application.OrderService.View;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tenancy.domain.StaffRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Club tills (docs/04-api.md §Point of sale, plan Q49/Q50): till sessions, walk-up cash sales, cash codes collected
 * at the till, and slip print data. {@code org} is always OrgContext.organizerId(), {@code user} the caller.
 *
 * <p>Locks: a sale or a collect first locks the caller's open session row (one at a time per till; a close waits
 * for them, so expected_mad misses none), then follows the Lot 0 order: the code payment row → the order row →
 * the buyer lock → price categories. Every sale, collect, print and close is audited with the session.
 */
@Service
public class PosService {

    /** A session opened now ({@code created}) or the caller's open one. */
    public record Opened(PosSession session, boolean created) {}

    /** A till sale; {@code replay} = the same sale_id again from the same session (200, nothing written). */
    public record Sold(View view, boolean replay) {}

    /** What a till may see of a cash code (04 §Cash codes Reveals): no name, no phone. */
    public record Lookup(Order order, boolean payable, long amountMad, Inventory.EventLabel event, List<String> holders) {}

    public record Collected(UUID orderId, List<UUID> ticketIds) {}

    /** Print data: the order, the unit price per category (snapshot) and the slips. Not for logs. */
    public record Print(Order order, Map<UUID, Long> unitPrices, TicketService.Slips slips) {
        @Override
        public String toString() {
            return "Print[" + order.id() + "]";
        }
    }

    private final PosSessions sessions;
    private final Payments payments;
    private final OrderService orders;
    private final TicketService tickets;
    private final Inventory inventory;
    private final AuditLog audit;
    private final JsonMapper json;

    public PosService(PosSessions sessions, Payments payments, OrderService orders, TicketService tickets,
                      Inventory inventory, AuditLog audit, JsonMapper json) {
        this.sessions = sessions;
        this.payments = payments;
        this.orders = orders;
        this.tickets = tickets;
        this.inventory = inventory;
        this.audit = audit;
        this.json = json;
    }

    // ---------- sessions ----------

    public Optional<PosSession> session(UUID org, UUID user) {
        return sessions.openOf(org, user);
    }

    /** {@code label} 1 to 40 characters (trimmed). The caller already has one open: that one, label unchanged. */
    @Transactional
    public Opened open(UUID org, UUID user, String label) {
        String l = label == null ? "" : label.strip();
        int length = l.codePointCount(0, l.length());
        if (length < 1 || length > 40) {
            throw invalid("label", "1 to 40 characters");
        }
        boolean created = sessions.insert(org, user, l);
        PosSession s = sessions.openOf(org, user).orElseThrow();
        if (created) {
            audit.staff(org, user, "pos.open", s.id().toString(), payload("session_id", s.id(), "label", l));
        }
        return new Opened(s, created);
    }

    /**
     * The caller's own session (ADMIN: any of the organizer; else 404). Already closed → 409 POS_SESSION_CLOSED.
     * Freezes expected_mad = sum of the session's SUCCEEDED payments; expected, declared and the gap are audited.
     */
    @Transactional
    public PosSession close(UUID org, UUID user, StaffRole role, UUID sessionId, long declaredMad) {
        PosSessions.Owned owned = sessions.lock(org, sessionId)
                .filter(o -> role == StaffRole.ADMIN || o.staffUserId().equals(user))
                .orElseThrow(() -> ApiException.notFound("Session not found"));
        if (owned.closed()) {
            throw new ApiException("POS_SESSION_CLOSED", HttpStatus.CONFLICT, "This till session is already closed");
        }
        sessions.close(org, sessionId, declaredMad);
        PosSession s = sessions.get(org, sessionId).orElseThrow();
        audit.staff(org, user, "pos.close", sessionId.toString(), payload("session_id", sessionId,
                "expected_mad", s.expectedMad(), "declared_mad", s.declaredMad(), "variance_mad", s.varianceMad()));
        return s;
    }

    // ---------- walk-up sale ----------

    /**
     * 04 POST /org/pos/orders. Needs the caller's open session (409 POS_SESSION_REQUIRED). {@code saleId} becomes
     * the order id: already a POS sale of this session → that order again (replay); any other order → 422 on
     * sale_id (TAKEN). Then sales checks and creates the PAID order with its tickets, and this adds the SUCCEEDED
     * POS_CASH payment tied to the session, all in one transaction.
     */
    @Transactional
    public Sold sale(UUID org, UUID user, UUID saleId, int eventId, List<ItemRequest> items) {
        UUID session = sessions.lockOpenOf(org, user).orElseThrow(PosService::sessionRequired);
        if (orders.exists(saleId)) {
            View same = payments.tillSession(org, saleId).filter(session::equals)
                    .flatMap(s -> orders.inOrg(org, saleId))
                    .filter(v -> v.order().channel() == Order.Channel.POS)
                    .orElseThrow(PosService::saleIdTaken);
            return new Sold(same, true);
        }
        View v = orders.placePos(org, saleId, eventId, items).orElseThrow(PosService::saleIdTaken);
        payments.insertPos(org, saleId, v.order().totalMad(), session);
        audit.staff(org, user, "pos.sale", saleId.toString(), payload("session_id", session, "order_id", saleId,
                "total_mad", v.order().totalMad(), "tickets", v.ticketIds().size()));
        return new Sold(v, false);
    }

    // ---------- cash codes ----------

    /** 04 GET /org/pos/codes/{code}: normalised (04 §Cash codes Input); unknown or another organizer's → 404. */
    public Lookup lookup(UUID org, String code) {
        Payment p = payments.findCode(org, CashCode.normalise(code).orElseThrow(PosService::codeNotFound))
                .orElseThrow(PosService::codeNotFound);
        Order o = orders.inOrg(org, p.orderId()).orElseThrow().order();
        boolean payable = o.status() == Order.Status.AWAITING_CASH && p.status() == Payment.Status.PENDING
                && orders.holding(org, o.id());
        List<String> holders = o.items().stream().flatMap(i -> i.holders().stream())
                .map(h -> HolderNames.label(h.name())).toList();
        return new Lookup(o, payable, p.amountMad(), inventory.label(o.eventId()).orElseThrow(), holders);
    }

    /**
     * The simulated network terminal's lookup: by code alone (a network serves every organizer; codes are unique
     * across them). The caller shows the event title and amount only (04 §Cash codes Reveals).
     */
    public Optional<Lookup> networkLookup(String code) {
        return CashCode.normalise(code).flatMap(payments::findCode).map(p -> lookup(p.organizerId(), p.payCode()));
    }

    /**
     * 04 POST /org/pos/codes/{code}/collect. Needs the caller's open session. Under the code payment's lock, then
     * the order's: collected by this session already → the same answer; not payable → 409 CODE_NOT_PAYABLE with the
     * order status (or EXPIRED once the hold has passed, database clock); {@code amountMad} ≠ total → 422. Else the
     * payment SUCCEEDED at this till (provider POS) and the order settled as any cash success: PAID with method
     * CASH_AGENCY and the code as payment_ref, tickets, deliveries.
     */
    @Transactional
    public Collected collect(UUID org, UUID user, String code, long amountMad) {
        UUID session = sessions.lockOpenOf(org, user).orElseThrow(PosService::sessionRequired);
        String c = CashCode.normalise(code).orElseThrow(PosService::codeNotFound);
        Payment p = payments.lockCode(org, c).orElseThrow(PosService::codeNotFound);
        if (p.status() == Payment.Status.SUCCEEDED && session.equals(p.posSessionId())) {
            return new Collected(p.orderId(), tickets.ofOrder(org, p.orderId()));
        }
        Orders.Locked locked = orders.lockInOrg(org, p.orderId()).orElseThrow();
        Order o = locked.order();
        if (p.status() != Payment.Status.PENDING || o.status() != Order.Status.AWAITING_CASH) {
            // Collected in an earlier till session of this cashier: the cash is in that till, keep it (04 Collect order).
            boolean byYou = p.posSessionId() != null
                    && sessions.staffUserOf(org, p.posSessionId()).filter(user::equals).isPresent();
            throw byYou ? new ApiException("CODE_NOT_PAYABLE", HttpStatus.CONFLICT, "This code can't be paid",
                    Map.of("status", o.status().name(), "collected_by_you", true)) : notPayable(o.status().name());
        }
        if (!locked.holding()) {
            throw notPayable("EXPIRED");
        }
        if (amountMad != o.totalMad()) {
            throw invalid("amount_mad", "the order total");
        }
        payments.collect(org, p.id(), session);
        if (orders.settle(o.id(), Order.PaymentMethod.CASH_AGENCY, c) != Settlement.PAID) {
            throw new IllegalStateException("payable order " + o.id() + " not settled");
        }
        List<UUID> ticketIds = tickets.ofOrder(org, o.id());
        audit.staff(org, user, "pos.collect", o.id().toString(), payload("session_id", session, "order_id", o.id(),
                "payment_id", p.id(), "amount_mad", amountMad));
        return new Collected(o.id(), ticketIds);
    }

    // ---------- print ----------

    /**
     * 04 GET /org/pos/orders/{id}/tickets. A CASHIER needs an open session (409) and prints only orders paid in it;
     * ADMIN/OPS need no session and may reprint any order paid in a till session of this organizer; else 404. Each
     * call is a print: audited {@code pos.print} on the session the order was paid in, which counts it (so a
     * reprint by a manager shows on the till that sold it).
     */
    @Transactional
    public Print print(UUID org, UUID user, StaffRole role, UUID orderId) {
        boolean manager = role == StaffRole.ADMIN || role == StaffRole.OPS;
        Optional<UUID> open = manager ? Optional.empty()
                : Optional.of(sessions.openOf(org, user).map(PosSession::id).orElseThrow(PosService::sessionRequired));
        UUID session = payments.tillSession(org, orderId).filter(s -> manager || open.get().equals(s))
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        Order o = orders.inOrg(org, orderId).orElseThrow().order();
        Map<UUID, Long> prices = new LinkedHashMap<>();
        o.items().forEach(i -> prices.put(i.priceCategoryId(), i.unitPriceMad()));
        TicketService.Slips slips = tickets.slips(org, o.eventId(), orderId);
        audit.staff(org, user, "pos.print", session.toString(), payload("session_id", session, "order_id", orderId,
                "tickets", slips.slips().size()));
        return new Print(o, prices, slips);
    }

    // ---------- helpers ----------

    /** A JSON object from key, value pairs (ids and numbers only: never a name, phone or token). */
    private String payload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof UUID u ? u.toString() : v);
        }
        return json.writeValueAsString(m);
    }

    private static ApiException sessionRequired() {
        return new ApiException("POS_SESSION_REQUIRED", HttpStatus.CONFLICT, "Open a till session first");
    }

    private static ApiException saleIdTaken() {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of("sale_id", "already used"), "reason", "TAKEN"));
    }

    private static ApiException codeNotFound() {
        return ApiException.notFound("Code not found");
    }

    private static ApiException notPayable(String status) {
        return new ApiException("CODE_NOT_PAYABLE", HttpStatus.CONFLICT, "This code can't be paid",
                Map.of("status", status));
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }
}
