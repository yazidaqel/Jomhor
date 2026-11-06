package ma.jomhor.payments.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.payments.application.CardGateway.CallbackVerdict;
import ma.jomhor.payments.application.CardGateway.Checkout;
import ma.jomhor.payments.application.CardGateway.HttpCallback;
import ma.jomhor.payments.application.CardGateway.PaymentRef;
import ma.jomhor.payments.domain.Payment;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.application.OrderService.Settlement;
import ma.jomhor.sales.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Card payments (docs/04-api.md §Fan pay, §Payments). Order state changes go through sales' OrderService in this
 * module's transaction; tickets through sales → tickets.
 *
 * <p>Locks: pay takes the order row, then inserts the payment. A callback takes the payment row, then (in
 * settle) the order row, the buyer lock and price categories. Pay never touches an existing payment row, so
 * the two can't wait on each other in a circle; two callbacks of one payment queue on its row.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public record Started(UUID paymentId, String redirectUrl) {}

    /** A payment that is no longer PENDING: its redirect page is gone (410). */
    public static class NotPending extends RuntimeException {
        NotPending() {
            super("payment is not pending", null, false, false);
        }
    }

    private final Payments payments;
    private final OrderService orders;
    private final CardGateway gateway;
    private final AuditLog audit;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final String baseUrl;

    public PaymentService(Payments payments, OrderService orders, CardGateway gateway, AuditLog audit, JsonMapper json,
                          PlatformTransactionManager transactions,
                          @Value("${payments.public-base-url}") String baseUrl) {
        this.payments = payments;
        this.orders = orders;
        this.gateway = gateway;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** The API's public base (…/v1), for absolute URLs in responses and adapter pages. */
    public String baseUrl() {
        return baseUrl;
    }

    /** A new PENDING attempt for the buyer's order, inserted under the order lock (see lockForPayment). */
    @Transactional
    public Started payByCard(UUID userId, UUID orderId) {
        Order order = orders.lockForPayment(userId, orderId);
        Payment p = payments.insertPending(order.organizerId(), order.id(), gateway.provider(), order.totalMad());
        return new Started(p.id(), baseUrl + "/payments/card/" + p.id() + "/redirect");
    }

    /** Unknown id → 404; not PENDING → NotPending (410). */
    public Payment get(UUID paymentId) {
        return payments.find(paymentId).orElseThrow(() -> ApiException.notFound("Payment not found"));
    }

    public Checkout checkout(UUID paymentId) {
        Payment p = get(paymentId);
        if (p.status() != Payment.Status.PENDING) {
            throw new NotPending();
        }
        return gateway.checkout(new PaymentRef(p.id(), p.amountMad()));
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
        String callback = callbackJson(v);
        if (!v.approved()) {
            payments.complete(p.organizerId(), p.id(), Payment.Status.FAILED, v.providerRef(), callback);
            return;
        }
        if (v.amountMad() != p.amountMad()) {
            // The card was charged another amount: the attempt fails (the order stays payable) and the charge
            // is refunded by hand.
            payments.complete(p.organizerId(), p.id(), Payment.Status.FAILED, v.providerRef(), callback);
            refundRequired(p, v, "AMOUNT_MISMATCH");
            return;
        }
        payments.complete(p.organizerId(), p.id(), Payment.Status.SUCCEEDED, v.providerRef(), callback);
        Settlement settled = orders.settle(p.orderId(), v.providerRef());
        if (settled != Settlement.PAID) {
            refundRequired(p, v, settled.name());
        }
    }

    /** audit_log payment.refund_required; amount_mad is what the gateway charged. No secret in the payload. */
    private void refundRequired(Payment p, CallbackVerdict v, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", p.id().toString());
        payload.put("order_id", p.orderId().toString());
        payload.put("reason", reason);
        payload.put("amount_mad", v.amountMad());
        payload.put("provider", p.provider());
        payload.put("provider_ref", v.providerRef());
        audit.system(p.organizerId(), "payment.refund_required", p.id().toString(), json.writeValueAsString(payload));
        log.warn("Payment {} of order {} was charged but is not kept ({}): manual refund required", p.id(),
                p.orderId(), reason);
    }

    /** What payments.callback keeps: the verified verdict only, never the raw request (no card data can ride along). */
    private String callbackJson(CallbackVerdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approved", v.approved());
        m.put("amount_mad", v.amountMad());
        m.put("provider_ref", v.providerRef());
        return json.writeValueAsString(m);
    }
}
