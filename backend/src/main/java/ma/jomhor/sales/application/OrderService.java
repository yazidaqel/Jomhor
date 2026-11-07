package ma.jomhor.sales.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.catalog.application.Inventory.SalePrice;
import ma.jomhor.common.HolderNames;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.notifications.application.TicketDeliveryService;
import ma.jomhor.sales.application.Orders.Expired;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.tenancy.application.UserService;
import ma.jomhor.tickets.application.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fan orders (docs/04-api.md §Fan). Quota goes through catalog's Inventory in this module's transaction, so an
 * order, its items and the places it takes commit together or not at all.
 *
 * <p>Lock order on one order, everywhere: the payment row (payments' callback) → the order row → the buyer lock
 * → price categories in UUID order. Placing an order takes the buyer lock → categories; the expiry claims order
 * rows with skip locked (it never waits for one) → categories. So none of them waits in a circle.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final int EXPIRY_BATCH = 1000;
    /**
     * Plan Q8 late-success window, CARD/WALLET only: an order whose hosted page was opened less than this ago is not
     * expired (plan P38), and GET /fan/orders/{id} reports a PENDING attempt this old as FAILED (never one the bank
     * can still confirm).
     */
    static final Duration PAYMENT_GRACE = Duration.ofMinutes(20);
    /** docs/04-api.md GET /fan/orders: at most 20. */
    static final int FAN_LIST_MAX = 20;

    /** {@code holders} as sent: validated and resolved by {@link #place}. */
    public record ItemRequest(UUID priceCategoryId, int qty, List<HolderRequest> holders) {}

    /** {@code phone} absent → the buyer holds the place. */
    public record HolderRequest(String name, String phone) {}

    /** Same as the fan auth endpoints. */
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");

    /**
     * An order with what the fan sees of each category, its tickets, its attempt as
     * {@link PendingPayments#latestAttempt} reports it (null before any /pay) and, while it waits for cash, its code.
     */
    public record View(Order order, Map<UUID, SalePrice> categories, List<UUID> ticketIds,
                       PendingPayments.Attempt attempt, CashInstructions.Cash cash) {}

    /**
     * A row of GET /fan/orders: the order, its event, its payment method (null before any /pay) and, while it is
     * AWAITING_CASH, its PENDING code (else null).
     */
    public record Summary(Orders.Listed order, Inventory.EventLabel event, Order.PaymentMethod paymentMethod,
                          String payCode) {}

    /** What became of an order whose card payment succeeded. Anything but PAID needs a manual refund. */
    public enum Settlement { PAID, SOLD_OUT, PER_ACCOUNT_LIMIT, NOT_PAYABLE }

    private final Orders orders;
    private final Inventory inventory;
    private final TicketService tickets;
    private final PendingPayments pendingPayments;
    private final CashInstructions cashInstructions;
    private final UserService users;
    private final TicketDeliveryService deliveries;
    private final TransactionTemplate tx;
    private final long maxActiveHolds;

    public OrderService(Orders orders, Inventory inventory, TicketService tickets, PendingPayments pendingPayments,
                        CashInstructions cashInstructions, UserService users, TicketDeliveryService deliveries,
                        PlatformTransactionManager transactions,
                        @Value("${sales.max-active-holds-per-event}") long maxActiveHolds) {
        this.orders = orders;
        this.inventory = inventory;
        this.tickets = tickets;
        this.pendingPayments = pendingPayments;
        this.cashInstructions = cashInstructions;
        this.users = users;
        this.deliveries = deliveries;
        this.tx = new TransactionTemplate(transactions);
        this.maxActiveHolds = maxActiveHolds;
    }

    /**
     * Checks in this order: event (404, 409 EVENT_NOT_ON_SALE), items and holders (422), queue (429 SALE_BUSY),
     * per-account limit (409), quota (409 SOLD_OUT). The buyer lock is taken before the limit is read, so two
     * parallel orders of one buyer are counted one after the other. A holder phone's account is found or created
     * once everything else passed (it rolls back with the order); the response is the same either way, so it can't
     * be used to probe whether a phone has an account.
     */
    @Transactional
    public View place(UUID userId, int eventId, Order.Channel channel, List<ItemRequest> items) {
        Inventory.SaleEvent event = inventory.saleEvent(eventId)
                .orElseThrow(() -> ApiException.notFound("Event not found"));
        if (event.window() != Inventory.SaleWindow.OPEN) {
            throw notOnSale(event);
        }
        UUID org = event.organizerId();
        Checked checked = check(byId(inventory.prices(org, eventId)), items);

        // ponytail: soft cap on a plain count, no lock (04 §On-sale Busy); the ranked queue is Lot 5.
        if (orders.activeHolds(org, eventId) >= maxActiveHolds) {
            throw new ApiException("SALE_BUSY", HttpStatus.TOO_MANY_REQUESTS, "Too many fans buying right now",
                    Map.of("retry_after_s", ThreadLocalRandom.current().nextInt(5, 31)));
        }

        orders.lockBuyer(userId, eventId);
        long held = orders.held(org, userId, eventId);
        if (held + checked.places() > event.perAccountLimit()) {
            throw new ApiException("PER_ACCOUNT_LIMIT", HttpStatus.CONFLICT, "Per-account limit reached",
                    Map.of("limit", event.perAccountLimit(), "held", held));
        }

        Map<UUID, SalePrice> taken = inventory.reserve(org, eventId, checked.qty());
        List<Order.Item> lines = lines(items, checked.holders(), taken, userId);
        long total = lines.stream().mapToLong(l -> l.qty() * l.unitPriceMad()).sum();
        return new View(orders.insert(org, eventId, userId, channel, total, lines), taken, List.of(), null, null);
    }

    /**
     * A walk-up cash sale at a till (04 POST /org/pos/orders), in the caller's transaction (payments adds the
     * POS_CASH payment). Same checks and order as {@link #place}, but the event must be the organizer's (404),
     * there is no SALE_BUSY, and the limit is on this sale alone (no buyer: sum of qty <= per_account_limit, held 0).
     * Then the order is PAID at once (id = {@code orderId}, channel POS, no buyer) with its tickets; a holder with a
     * phone gets that account and a delivery, one without is paper only (holder_user_id null, no delivery). Empty if
     * {@code orderId} is already an order id (the caller's transaction must then roll back).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<View> placePos(UUID org, UUID orderId, int eventId, List<ItemRequest> items) {
        Inventory.SaleEvent event = inventory.saleEvent(eventId).filter(e -> e.organizerId().equals(org))
                .orElseThrow(() -> ApiException.notFound("Event not found"));
        if (event.window() != Inventory.SaleWindow.OPEN) {
            throw notOnSale(event);
        }
        Checked checked = check(byId(inventory.prices(org, eventId)), items);
        if (checked.places() > event.perAccountLimit()) {
            throw new ApiException("PER_ACCOUNT_LIMIT", HttpStatus.CONFLICT, "Per-account limit reached",
                    Map.of("limit", event.perAccountLimit(), "held", 0));
        }
        Map<UUID, SalePrice> taken = inventory.reserve(org, eventId, checked.qty());
        List<Order.Item> lines = lines(items, checked.holders(), taken, null);
        long total = lines.stream().mapToLong(l -> l.qty() * l.unitPriceMad()).sum();
        return orders.insertPos(orderId, org, eventId, total, lines)
                .map(o -> {
                    issue(o, taken);
                    return new View(o, taken, tickets.ofOrder(org, o.id()), null, null);
                });
    }

    /** Whether any order, of any organizer, has this id (a till's sale_id must be new). */
    public boolean exists(UUID orderId) {
        return orders.exists(orderId);
    }

    /** The organizer's order with its categories and every ticket of it (the till's view: no buyer). */
    public Optional<View> inOrg(UUID org, UUID orderId) {
        return orders.findInOrg(org, orderId).map(o -> new View(o, byId(inventory.prices(org, o.eventId())),
                tickets.ofOrder(org, o.id()), null, null));
    }

    /**
     * The organizer's order, locked like {@link #lockForPayment} (a till collecting its cash code), with whether its
     * hold still runs by the database clock. Another organizer's: empty.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Orders.Locked> lockInOrg(UUID org, UUID orderId) {
        return orders.lock(orderId).filter(l -> l.order().organizerId().equals(org));
    }

    public View get(UUID userId, UUID orderId) {
        Order order = orders.find(userId, orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        UUID org = order.organizerId();
        return new View(order, byId(inventory.prices(org, order.eventId())),
                tickets.heldInOrder(org, order.id(), userId),
                pendingPayments.latestAttempt(org, order.id(), PAYMENT_GRACE).orElse(null),
                order.status() == Order.Status.AWAITING_CASH ? cashInstructions.pending(org, order.id()).orElse(null) : null);
    }

    /** GET /fan/orders: the buyer's orders, AWAITING_CASH first, then newest first, at most 20. */
    public List<Summary> list(UUID userId) {
        List<Orders.Listed> listed = orders.listed(userId, FAN_LIST_MAX);
        Map<UUID, UUID> orgByOrder = new HashMap<>();
        listed.forEach(o -> orgByOrder.put(o.id(), o.organizerId()));
        Map<UUID, Order.PaymentMethod> methods = pendingPayments.latestMethods(orgByOrder);
        Map<UUID, UUID> awaiting = new HashMap<>();
        listed.stream().filter(o -> o.status() == Order.Status.AWAITING_CASH)
                .forEach(o -> awaiting.put(o.id(), o.organizerId()));
        Map<UUID, String> codes = pendingPayments.pendingCodes(awaiting);
        // ponytail: one event read per distinct event (a fan has a handful); batch it if lists grow.
        Map<Integer, Inventory.EventLabel> events = new HashMap<>();
        listed.forEach(o -> events.computeIfAbsent(o.eventId(), id -> inventory.label(id).orElseThrow()));
        return listed.stream()
                .map(o -> new Summary(o, events.get(o.eventId()), methods.get(o.id()), codes.get(o.id()))).toList();
    }

    // ---------- payment (called by payments) ----------

    /**
     * The buyer's order, locked for the caller's transaction, if it can take a payment now: RESERVED or
     * AWAITING_CASH and still held. The lock makes a pay and an expiry pass on the same order run one after the
     * other, so a payment is never accepted for an order the expiry is expiring. Another user's order → 404.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Order lockForPayment(UUID userId, UUID orderId) {
        Orders.Locked locked = orders.lock(orderId).filter(x -> userId.equals(x.order().userId()))
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        Order o = locked.order();
        // Database clock, like the expiry claim (reserved_until < now()): a pay that passes here can't be claimed.
        boolean held = o.status() == Order.Status.RESERVED || o.status() == Order.Status.AWAITING_CASH;
        if (o.status() == Order.Status.EXPIRED || (held && !locked.holding())) {
            throw new ApiException("ORDER_EXPIRED", HttpStatus.CONFLICT, "The hold on this order has expired");
        }
        if (!held) {
            throw new ApiException("ORDER_NOT_PAYABLE", HttpStatus.CONFLICT, "Order can't be paid in its current status");
        }
        return o;
    }

    /**
     * A cash code is being issued for this RESERVED order, locked by {@link #lockForPayment} (04 POST /pay
     * CASH_AGENCY): → AWAITING_CASH, held until least(now + 24 h, sales close). Refused with METHOD_NOT_AVAILABLE
     * TOO_LATE if that leaves less than {@code minWindow}, or CASH_CAP if the event's live cash holds, this order
     * included, pass {@code maxHeldPercent} % of its quota (counted without a lock: a soft cap, like SALE_BUSY). Both
     * on the database clock; a refusal rolls the status change back with the caller's transaction. Returns the new
     * reserved_until.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Instant awaitCash(Order o, Duration minWindow, int maxHeldPercent) {
        Inventory.SaleEvent event = inventory.saleEvent(o.eventId()).orElseThrow();
        Instant until = event.refundCutoffAt() == null ? null
                : orders.awaitCash(o.organizerId(), o.id(), event.refundCutoffAt(), minWindow).orElse(null);
        if (until == null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "TOO_LATE");
            details.put("sales_close_at", event.refundCutoffAt() == null ? null : event.refundCutoffAt().toString());
            throw methodNotAvailable(details);
        }
        // This order is AWAITING_CASH already (above): the count includes its places.
        if (orders.cashHeld(o.organizerId(), o.eventId()) * 100 > (long) maxHeldPercent * event.quota()) {
            throw methodNotAvailable(Map.of("reason", "CASH_CAP"));
        }
        return until;
    }

    /**
     * A new code for an AWAITING_CASH order whose code is no longer PENDING keeps the hold (04 §Cash codes Payable):
     * refused with TOO_LATE unless at least {@code minWindow} of it is left (database clock).
     */
    public void requireCashWindow(Order o, Duration minWindow) {
        if (!orders.holdsFor(o.organizerId(), o.id(), minWindow)) {
            Instant close = inventory.saleEvent(o.eventId()).orElseThrow().refundCutoffAt();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "TOO_LATE");
            details.put("sales_close_at", close == null ? null : close.toString());
            throw methodNotAvailable(details);
        }
    }

    /** The order's hold still runs: RESERVED or AWAITING_CASH with reserved_until > now() (payments' /redirect). */
    public boolean holding(UUID org, UUID orderId) {
        return orders.holdsFor(org, orderId, Duration.ZERO);
    }

    /** 409 METHOD_NOT_AVAILABLE with details.reason TOO_LATE, CASH_CAP or NOT_CONFIGURED (04 §Errors). */
    public static ApiException methodNotAvailable(Map<String, Object> details) {
        return new ApiException("METHOD_NOT_AVAILABLE", HttpStatus.CONFLICT,
                "Payment method not available for this order", details);
    }

    /**
     * A payment of the order succeeded (payments, inside its transaction): RESERVED or AWAITING_CASH → PAID with
     * the payment's method and reference, one ticket per place, each with one WhatsApp delivery queued to its
     * holder. EXPIRED (a late success, plan Q8) re-reserves the places under the buyer lock with the per-account
     * limit checked again. Never throws for a business outcome: the payment commits as SUCCEEDED either way, and
     * anything but PAID is for a manual refund.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement settle(UUID orderId, Order.PaymentMethod method, String paymentRef) {
        Order o = orders.lock(orderId).orElseThrow().order();
        if (o.status() == Order.Status.EXPIRED) {
            Settlement refused = reReserve(o);
            if (refused != null) {
                return refused;
            }
        } else if (o.status() != Order.Status.RESERVED && o.status() != Order.Status.AWAITING_CASH) {
            return Settlement.NOT_PAYABLE;
        }
        orders.markPaid(o.organizerId(), o.id(), method, paymentRef);
        issue(o, byId(inventory.prices(o.organizerId(), o.eventId())));
        return Settlement.PAID;
    }

    /**
     * One ticket per place of a paid order, and one WhatsApp delivery per ticket that has a holding account (a till
     * holder without a phone has none: paper only, nothing queued). Returns the ticket ids in place order.
     */
    private List<UUID> issue(Order o, Map<UUID, SalePrice> categories) {
        List<TicketService.Seat> seats = new ArrayList<>();
        for (Order.Item i : o.items()) {
            int zone = categories.get(i.priceCategoryId()).zoneId();
            if (i.holders().isEmpty()) { // a Lot 0 order: every place to the buyer, no name
                for (int n = 0; n < i.qty(); n++) {
                    seats.add(new TicketService.Seat(zone, i.priceCategoryId(), o.userId(), null));
                }
            } else {
                i.holders().forEach(h -> seats.add(new TicketService.Seat(zone, i.priceCategoryId(), h.userId(), h.name())));
            }
        }
        List<UUID> ids = tickets.issue(o.organizerId(), o.id(), o.eventId(), seats);
        List<UUID> delivered = new ArrayList<>();
        for (int n = 0; n < ids.size(); n++) {
            if (seats.get(n).holderUserId() != null) {
                delivered.add(ids.get(n));
            }
        }
        deliveries.enqueue(o.organizerId(), delivered);
        return ids;
    }

    /** The channel of an order (payments' /return picks its landing URL by it). Reads only. */
    public Optional<Order.Channel> channel(UUID org, UUID orderId) {
        return orders.channel(org, orderId);
    }

    /** Takes the expired order's places again; null if done, else why not. */
    private Settlement reReserve(Order o) {
        orders.lockBuyer(o.userId(), o.eventId());
        int limit = inventory.saleEvent(o.eventId()).orElseThrow().perAccountLimit();
        if (orders.held(o.organizerId(), o.userId(), o.eventId()) + o.places() > limit) {
            return Settlement.PER_ACCOUNT_LIMIT;
        }
        Map<UUID, Integer> qty = o.items().stream()
                .collect(Collectors.toMap(Order.Item::priceCategoryId, Order.Item::qty));
        return inventory.tryReserve(o.organizerId(), o.eventId(), qty).isPresent() ? null : Settlement.SOLD_OUT;
    }

    // ---------- expiry ----------

    /** One expiry pass in batches of 1000. Returns the number of orders expired. */
    public int expireDue() {
        return expireDue(EXPIRY_BATCH);
    }

    /**
     * Expires due orders batch after batch, each in its own transaction, until a batch comes back short. In a
     * batch the status flip and the release commit together, so places come back exactly once (a later pass
     * finds nothing); rows claimed by another instance are skipped. A release that doesn't fit rolls its batch
     * back and is logged with the orders involved; the next pass meets the same orders again.
     */
    public int expireDue(int batchSize) {
        int total = 0;
        Batch batch;
        do {
            batch = tx.execute(s -> expireBatch(batchSize));
            total += batch.expired();
        } while (batch.claimed() == batchSize);
        return total;
    }

    private record Batch(int claimed, int expired) {}

    /**
     * Orders whose hosted page was opened less than 20 min ago (plan P38) are left out of the claim (so they can't
     * fill every batch), and read again once the claimed rows are locked: a redirect that committed in between is
     * seen then, and its order is kept. A cash code never keeps its order: it expires at reserved_until, and the
     * code payment stays PENDING so a racing confirmation follows the late-success path.
     */
    private Batch expireBatch(int batchSize) {
        List<UUID> due = orders.claimDue(batchSize, pendingPayments.ordersRedirectedWithin(PAYMENT_GRACE));
        Set<UUID> paying = pendingPayments.ordersRedirectedWithin(PAYMENT_GRACE);
        List<Expired> expired = orders.expire(due.stream().filter(id -> !paying.contains(id)).toList());
        try {
            inventory.release(expired.stream().map(Expired::hold).toList());
        } catch (Inventory.ReleaseFailed e) {
            List<UUID> orderIds = expired.stream().filter(d -> d.hold().equals(e.hold())).findFirst()
                    .map(Expired::orderIds).orElse(List.of());
            log.error("Expiry batch rolled back: event {}, price category {}, orders {}", e.hold().eventId(),
                    e.hold().priceCategoryId(), orderIds, e);
            throw e;
        }
        return new Batch(due.size(), (int) expired.stream().flatMap(d -> d.orderIds().stream()).distinct().count());
    }

    /** Items checked against the event's categories: qty per category, total places, holders per item (normalized). */
    private record Checked(Map<UUID, Integer> qty, long places, List<List<HolderRequest>> holders) {}

    /** Each category of the event, at most once (422 on items[i].price_category_id), then the holders. */
    private static Checked check(Map<UUID, SalePrice> ofEvent, List<ItemRequest> items) {
        Map<UUID, Integer> qty = new LinkedHashMap<>();
        long places = 0;
        for (int i = 0; i < items.size(); i++) {
            ItemRequest item = items.get(i);
            if (!ofEvent.containsKey(item.priceCategoryId())) {
                throw invalid("items[%d].price_category_id".formatted(i), "not a price category of this event");
            }
            if (qty.put(item.priceCategoryId(), item.qty()) != null) {
                throw invalid("items[%d].price_category_id".formatted(i), "duplicate price category");
            }
            places += item.qty();
        }
        return new Checked(qty, places, validHolders(items));
    }

    /**
     * The order lines at the reserved prices. A holder with a phone: that phone's account (found or created); one
     * without: {@code noPhone} (the buyer online, null at a till).
     */
    private List<Order.Item> lines(List<ItemRequest> items, List<List<HolderRequest>> holders,
                                   Map<UUID, SalePrice> taken, UUID noPhone) {
        // Each distinct phone once, in sorted order: two orders naming the same new phones insert their users rows
        // in the same order, so they queue on the unique index instead of deadlocking.
        Map<String, UUID> byPhone = new TreeMap<>();
        holders.stream().flatMap(List::stream).map(HolderRequest::phone).filter(Objects::nonNull)
                .forEach(p -> byPhone.put(p, null));
        byPhone.replaceAll((phone, none) -> users.holder(phone));
        List<Order.Item> lines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            UUID category = items.get(i).priceCategoryId();
            List<Order.Holder> holding = holders.get(i).stream()
                    .map(h -> new Order.Holder(h.name(), h.phone() == null ? noPhone : byPhone.get(h.phone())))
                    .toList();
            lines.add(new Order.Item(category, items.get(i).qty(), taken.get(category).priceMad(), holding));
        }
        return lines;
    }

    /**
     * docs/04-api.md §Fan POST /fan/orders and §Holder names: one holder per place, each name normalized and
     * in one script (Latin or Arabic), each phone absent or E.164. Returns the holders per item with normalized names.
     */
    private static List<List<HolderRequest>> validHolders(List<ItemRequest> items) {
        List<List<HolderRequest>> all = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemRequest item = items.get(i);
            if (item.holders() == null || item.holders().size() != item.qty()) {
                throw invalid("items[%d].holders".formatted(i), "one holder per place");
            }
            List<HolderRequest> holders = new ArrayList<>();
            for (int j = 0; j < item.holders().size(); j++) {
                HolderRequest h = item.holders().get(j);
                String path = "items[%d].holders[%d].".formatted(i, j);
                String name = HolderNames.normalize(h == null ? null : h.name()).orElseThrow(() -> invalid(path + "name",
                        HolderNames.INVALID));
                if (h.phone() != null && !E164.matcher(h.phone()).matches()) {
                    throw invalid(path + "phone", "an E.164 phone number");
                }
                holders.add(new HolderRequest(name, h.phone()));
            }
            all.add(holders);
        }
        return all;
    }

    private static Map<UUID, SalePrice> byId(List<SalePrice> prices) {
        Map<UUID, SalePrice> map = new HashMap<>();
        prices.forEach(p -> map.put(p.id(), p));
        return map;
    }

    /** 04: details.reason NOT_ON_SALE / NOT_STARTED (+ sale_starts_at) / CLOSED. */
    private static ApiException notOnSale(Inventory.SaleEvent event) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", event.window().name());
        if (event.window() == Inventory.SaleWindow.NOT_STARTED) {
            details.put("sale_starts_at", event.saleStartsAt().toString());
        }
        return new ApiException("EVENT_NOT_ON_SALE", HttpStatus.CONFLICT, "Event is not on sale", details);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }
}
