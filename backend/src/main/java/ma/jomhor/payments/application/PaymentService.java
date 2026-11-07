package ma.jomhor.payments.application;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.payments.application.CardGateway.CallbackVerdict;
import ma.jomhor.payments.application.CardGateway.Checkout;
import ma.jomhor.payments.application.CardGateway.HttpCallback;
import ma.jomhor.payments.application.CardGateway.PaymentRef;
import ma.jomhor.payments.application.CashNetwork.CashVerdict;
import ma.jomhor.payments.application.CashNetwork.Outcome;
import ma.jomhor.payments.domain.CashCode;
import ma.jomhor.payments.domain.Payment;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.application.OrderService.Settlement;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.sales.domain.Order.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payments (docs/04-api.md §Fan pay, §Cash codes, §Payments): hosted-page attempts (CARD, WALLET) and cash codes.
 * Order state changes go through sales' OrderService in this module's transaction; tickets through sales → tickets.
 *
 * <p>Locks: pay takes the order row, then inserts the payment. A callback takes the payment row, then (in
 * settle) the order row, the buyer lock and price categories. Pay never touches an existing payment row, so
 * the two can't wait on each other in a circle; two callbacks of one payment queue on its row.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    /** Placeholder in payments.return-url.* templates. */
    private static final String ORDER_ID = "{order_id}";

    /** POST /pay CARD or WALLET: the attempt and its hosted page. */
    public record Started(UUID paymentId, PaymentMethod method, String redirectUrl) {}

    /** POST /pay CASH_AGENCY: the code (6 stored symbols), how long it can be paid, and the exact amount. */
    public record CashStarted(UUID paymentId, String payCode, Instant reservedUntil, long amountMad) {}

    /** A payment that is no longer PENDING: its redirect page is gone (410). */
    public static class NotPending extends RuntimeException {
        NotPending() {
            super("payment is not pending", null, false, false);
        }
    }

    private final Payments payments;
    private final OrderService orders;
    private final CardGateway gateway;
    private final PaymentOptions options;
    private final AuditLog audit;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final String baseUrl;
    private final Map<Order.Channel, String> returnUrls;

    public PaymentService(Payments payments, OrderService orders, CardGateway gateway, PaymentOptions options,
                          AuditLog audit, JsonMapper json, PlatformTransactionManager transactions,
                          @Value("${payments.public-base-url}") String baseUrl,
                          @Value("${payments.return-url.web}") String webReturnUrl,
                          @Value("${payments.return-url.app}") String appReturnUrl) {
        this.payments = payments;
        this.orders = orders;
        this.gateway = gateway;
        this.options = options;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.returnUrls = Map.of(Order.Channel.WEB, template("payments.return-url.web", webReturnUrl),
                Order.Channel.APP, template("payments.return-url.app", appReturnUrl));
    }

    /** Fails startup unless the template has {order_id} and, filled in, is an absolute URI. */
    public static String template(String key, String url) {
        if (!url.contains(ORDER_ID)) {
            throw new IllegalStateException(key + " must contain " + ORDER_ID);
        }
        try {
            if (!URI.create(url.replace(ORDER_ID, new UUID(0, 0).toString())).isAbsolute()) {
                throw new IllegalStateException(key + " must be an absolute URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(key + " is not a valid URL", e);
        }
        return url;
    }

    /**
     * Where the browser lands after the gateway (04 §Payments /return): the configured URL of the order's channel,
     * never a caller-supplied one. Reads only. Unknown payment → empty.
     */
    public Optional<URI> returnUrl(UUID paymentId) {
        return payments.find(paymentId).flatMap(p -> orders.channel(p.organizerId(), p.orderId())
                .map(c -> URI.create(returnUrls.get(c).replace(ORDER_ID, p.orderId().toString()))));
    }

    /** The API's public base (…/v1), for absolute URLs in responses and adapter pages. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * A new PENDING CARD or WALLET attempt for the buyer's RESERVED or AWAITING_CASH order, inserted under the order
     * lock (see lockForPayment). On an AWAITING_CASH order the code stays payable until one method succeeds.
     */
    @Transactional
    public Started payHosted(UUID userId, UUID orderId, PaymentMethod method) {
        Order order = orders.lockForPayment(userId, orderId);
        if (method == PaymentMethod.WALLET && !options.walletConfigured()) {
            throw OrderService.methodNotAvailable(Map.of("reason", "NOT_CONFIGURED"));
        }
        Payment p = payments.insertPending(order.organizerId(), order.id(), method, gateway.provider(), order.totalMad());
        return new Started(p.id(), method, baseUrl + "/payments/card/" + p.id() + "/redirect");
    }

    /**
     * A cash code for the buyer's order, under the order lock (04 POST /pay CASH_AGENCY). RESERVED → AWAITING_CASH
     * held until least(now + 24 h, sales close) with one PENDING code (NOT_CONFIGURED, TOO_LATE, CASH_CAP checked
     * first). An AWAITING_CASH order that has a PENDING code → that code again, hold unchanged. One whose code is no
     * longer PENDING (a failed network confirmation) gets a new code, hold unchanged.
     */
    @Transactional
    public CashStarted payCash(UUID userId, UUID orderId) {
        Order order = orders.lockForPayment(userId, orderId);
        UUID org = order.organizerId();
        Optional<Payment> pending = payments.pendingCode(org, order.id());
        if (order.status() == Order.Status.AWAITING_CASH && pending.isPresent()) {
            Payment p = pending.get();
            return new CashStarted(p.id(), p.payCode(), order.reservedUntil(), p.amountMad());
        }
        if (!options.cashConfigured(org)) {
            throw OrderService.methodNotAvailable(Map.of("reason", "NOT_CONFIGURED"));
        }
        Instant until;
        if (order.status() == Order.Status.RESERVED) {
            until = orders.awaitCash(order, options.minWindow(), options.maxHeldPercent());
        } else { // §Cash codes Payable: a reissue keeps the hold, and needs min-window of it left
            orders.requireCashWindow(order, options.minWindow());
            until = order.reservedUntil();
        }
        Payment p = CashCode.issue(CashCode::draw, code -> payments.insertCashPending(org, order.id(), code,
                order.totalMad()));
        return new CashStarted(p.id(), p.payCode(), until, p.amountMad());
    }

    /** Unknown id → 404; not PENDING → NotPending (410). */
    public Payment get(UUID paymentId) {
        return payments.find(paymentId).orElseThrow(() -> ApiException.notFound("Payment not found"));
    }

    /** A CARD or WALLET attempt: anything else (a cash code) has no hosted page → 404. */
    public Payment hosted(UUID paymentId) {
        Payment p = get(paymentId);
        if (p.method() != PaymentMethod.CARD && p.method() != PaymentMethod.WALLET) {
            throw ApiException.notFound("Payment not found");
        }
        return p;
    }

    /**
     * The hosted page of a CARD or WALLET attempt (a cash code has none: 404). Gone (410) once the attempt isn't
     * PENDING or its order's hold has passed, so a page opened late can't start a charge the order no longer
     * waits for. Marks the attempt redirected the first time, so the expiry waits for it (plan P38); a redirect
     * never otherwise changes state.
     */
    public Checkout checkout(UUID paymentId) {
        Payment p = hosted(paymentId);
        if (p.status() != Payment.Status.PENDING || !orders.holding(p.organizerId(), p.orderId())) {
            throw new NotPending();
        }
        payments.markRedirected(p.organizerId(), p.id());
        return gateway.checkout(new PaymentRef(p.id(), p.method(), p.amountMad()));
    }

    /**
     * A gateway callback: the signature is verified before anything is read or written (a bad one → 401 with a
     * fixed message). Then, in one transaction under the payment row lock: an attempt already completed is
     * acknowledged again with no second effect; a decline or an amount mismatch → FAILED (the order stays
     * payable); an approval → SUCCEEDED, then the order is settled (PAID + tickets) or, if it can't be, the
     * refund is flagged in audit_log.
     */
    public ResponseEntity<String> callback(String provider, HttpCallback request) {
        if (!gateway.provider().equalsIgnoreCase(provider)) {
            throw ApiException.notFound("Not found");
        }
        CallbackVerdict v = gateway.verify(request);
        if (!v.signatureOk()) {
            throw new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Invalid callback");
        }
        tx.executeWithoutResult(s -> apply(v));
        return gateway.acknowledge(v);
    }

    private void apply(CallbackVerdict v) {
        Payment p = payments.lock(v.paymentId()).filter(x -> x.provider().equals(gateway.provider()))
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        if (p.status() != Payment.Status.PENDING) {
            return;
        }
        String callback = callbackJson(v.approved(), v.amountMad(), v.providerRef());
        if (!v.approved()) {
            payments.complete(p.organizerId(), p.id(), Payment.Status.FAILED, p.provider(), v.providerRef(), callback);
            return;
        }
        if (v.amountMad() != p.amountMad()) {
            // The card was charged another amount: the attempt fails (the order stays payable) and the charge
            // is refunded by hand.
            payments.complete(p.organizerId(), p.id(), Payment.Status.FAILED, p.provider(), v.providerRef(), callback);
            refundRequired(p, p.provider(), v.providerRef(), v.amountMad(), "AMOUNT_MISMATCH");
            return;
        }
        payments.complete(p.organizerId(), p.id(), Payment.Status.SUCCEEDED, p.provider(), v.providerRef(), callback);
        Settlement settled = orders.settle(p.orderId(), p.method(), v.providerRef());
        if (settled != Settlement.PAID) {
            refundRequired(p, p.provider(), v.providerRef(), v.amountMad(), settled.name());
        }
    }

    /**
     * A cash network confirms a code paid at one of its agents (04 §Payments /payments/cash/{network}/callback). Only
     * an enabled network has the path (else 404). Its signature is verified before anything is read or written (a
     * bad one → 401, fixed message). Then, in one transaction under the code payment's row lock: unknown code →
     * UNKNOWN, nothing written; this network's ref already used for this payment → the first answer again (REFUSED
     * after an amount mismatch, else ALREADY), no effect; used for another payment (on it, or on its NOT_PAYABLE refund)
     * → REFUSED + payment.duplicate_ref, nothing else; a code no longer PENDING (collected at a till, by another ref,
     * or failed) → payment.refund_required NOT_PAYABLE; an amount that isn't the code's → FAILED +
     * AMOUNT_MISMATCH (the order stays payable); else SUCCEEDED (provider = the network, provider_ref = its ref) and
     * the order settled like a card success (an EXPIRED order per §Late success, a PAID one → NOT_PAYABLE).
     */
    public ResponseEntity<String> cashCallback(String name, HttpCallback request) {
        CashNetwork network = options.network(name).orElseThrow(() -> ApiException.notFound("Not found"));
        CashVerdict v = network.verify(request);
        if (!v.signatureOk()) {
            throw new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Invalid callback");
        }
        Optional<String> code = CashCode.normalise(v.payCode());
        if (code.isEmpty()) {
            return network.acknowledge(v, Outcome.UNKNOWN);
        }
        Outcome outcome;
        try {
            outcome = tx.execute(s -> applyCash(network, code.get(), v));
        } catch (DuplicateKeyException e) { // the ref was taken by a racing callback on another code after the check
            outcome = tx.execute(s -> {
                payments.findCode(code.get()).ifPresent(p -> duplicateRef(p, network, v));
                return Outcome.REFUSED;
            });
        }
        return network.acknowledge(v, outcome);
    }

    private Outcome applyCash(CashNetwork network, String code, CashVerdict v) {
        Optional<Payment> found = payments.lockCode(code);
        if (found.isEmpty()) {
            return Outcome.UNKNOWN;
        }
        Payment p = found.get();
        String provider = network.provider();
        // A ref is used once it is on a payment, or on a refund flagged for a code that was no longer payable.
        // ponytail: two racing NOT_PAYABLE callbacks with one ref can both flag (no unique key on audit rows); a
        // per-ref advisory lock closes it if a real network ever does that.
        String seen = json.writeValueAsString(Map.of("provider", provider, "provider_ref", v.networkRef()));
        Optional<UUID> onPayment = payments.withRef(provider, v.networkRef());
        Optional<UUID> holder = onPayment.or(() -> audit.target("payment.refund_required", seen).map(UUID::fromString));
        if (holder.filter(p.id()::equals).isPresent()) { // this confirmation again: the same answer, no effect
            boolean mismatched = onPayment.isPresent() && p.status() == Payment.Status.FAILED;
            return mismatched ? Outcome.REFUSED : Outcome.ALREADY;
        }
        if (holder.isPresent()) {
            duplicateRef(p, network, v);
            return Outcome.REFUSED;
        }
        if (p.status() != Payment.Status.PENDING) { // paid twice: at a till or by another ref (or a failed code)
            refundRequired(p, provider, v.networkRef(), v.amountMad(), Settlement.NOT_PAYABLE.name());
            return Outcome.ACCEPTED;
        }
        String callback = callbackJson(true, v.amountMad(), v.networkRef());
        if (v.amountMad() != p.amountMad()) { // the cash taken goes back by hand
            payments.complete(p.organizerId(), p.id(), Payment.Status.FAILED, provider, v.networkRef(), callback);
            refundRequired(p, provider, v.networkRef(), v.amountMad(), "AMOUNT_MISMATCH");
            return Outcome.REFUSED;
        }
        payments.complete(p.organizerId(), p.id(), Payment.Status.SUCCEEDED, provider, v.networkRef(), callback);
        Settlement settled = orders.settle(p.orderId(), PaymentMethod.CASH_AGENCY, code);
        if (settled != Settlement.PAID) {
            refundRequired(p, provider, v.networkRef(), v.amountMad(), settled.name());
        }
        return Outcome.ACCEPTED;
    }

    /** audit_log payment.duplicate_ref on the code's payment: the network ref is already another payment's. */
    private void duplicateRef(Payment p, CashNetwork network, CashVerdict v) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", p.id().toString());
        payload.put("amount_mad", v.amountMad());
        payload.put("provider", network.provider());
        payload.put("provider_ref", v.networkRef());
        audit.system(p.organizerId(), "payment.duplicate_ref", p.id().toString(), json.writeValueAsString(payload));
        log.warn("Cash network {} sent a ref already used, for payment {}: refused", network.provider(), p.id());
    }

    /**
     * audit_log payment.refund_required: amount_mad is what was charged or collected, provider who did it. No secret
     * in the payload.
     */
    private void refundRequired(Payment p, String provider, String providerRef, long amountMad, String reason) {
        // Read back by sales' JdbcOrders.refundsRequired (GET /org/events/{id}/sales): keep these keys.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", p.id().toString());
        payload.put("order_id", p.orderId().toString());
        payload.put("reason", reason);
        payload.put("amount_mad", amountMad);
        payload.put("provider", provider);
        payload.put("provider_ref", providerRef);
        audit.system(p.organizerId(), "payment.refund_required", p.id().toString(), json.writeValueAsString(payload));
        log.warn("Payment {} of order {} was charged but is not kept ({}): manual refund required", p.id(),
                p.orderId(), reason);
    }

    /** What payments.callback keeps: the verified verdict only, never the raw request (no card data can ride along). */
    private String callbackJson(boolean approved, long amountMad, String providerRef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approved", approved);
        m.put("amount_mad", amountMad);
        m.put("provider_ref", providerRef);
        return json.writeValueAsString(m);
    }
}
