package ma.jomhor.sales.products.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.crm.application.MembershipService;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.sales.products.domain.Installment;
import ma.jomhor.sales.products.domain.Installments;
import ma.jomhor.sales.products.domain.Pass;
import ma.jomhor.sales.products.domain.Product;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 04 POST /fan/product-orders: an order with no event and one product item (04 §Products), held 8 min and paid with
 * /fan/orders/{id}/pay like any order. None of the event rules (admission, rooms, SALE_BUSY, limits) apply, so it takes
 * no per-account lock.
 */
@Service
public class ProductOrderService {

    private final Products products;
    private final ProductService productService;
    private final Orders orders;
    private final MembershipService memberships;
    private final PassService passes;
    private final Passes passRows;

    /** An installment order (04 POST /fan/passes/{id}/installments): {@code created} false = the live one again. */
    public record Placed(OrderService.View view, boolean created) {}

    ProductOrderService(Products products, ProductService productService, Orders orders,
                        MembershipService memberships, PassService passes, Passes passRows) {
        this.products = products;
        this.productService = productService;
        this.orders = orders;
        this.memberships = memberships;
        this.passes = passes;
        this.passRows = passRows;
    }

    /**
     * Checks in 04 order: holder_name and installments (422), the product visible as GET /fan/products/{id} (404),
     * now (database clock) before its sales_close_at (409 PRODUCT_NOT_ON_SALE), a PASS's live pass and quota
     * ({@link PassService#reserve}: 409 PASS_HELD, SOLD_OUT; the PENDING pass, its schedule, sold + 1 and the order
     * commit together), MEMBERSHIP already paid to its valid_until (409 MEMBERSHIP_PAID). The buyer holds the item.
     * {@code installments} absent = 1; else one of the product's plans ({@link ProductService#plans}), else 422 on
     * installments, reason NOT_OFFERED: a value outside 1-10 before the product is read, another after the
     * PRODUCT_NOT_ON_SALE check (a plan needs the product; a closed product says so first). The order's total = the
     * plan's first amount.
     */
    @Transactional
    public OrderService.View place(UUID userId, UUID productId, Order.Channel channel, String holderName,
                                   Integer installments) {
        String name = HolderNames.normalize(holderName).orElseThrow(() -> invalid("holder_name", HolderNames.INVALID));
        int n = installments == null ? 1 : installments;
        if (n < 1 || n > Installments.MAX) {
            throw notOffered();
        }
        Product p = products.onSale(productId).orElseThrow(() -> ApiException.notFound("Product not found"));
        Instant now = orders.now();
        if (!now.isBefore(p.salesCloseAt())) {
            throw new ApiException("PRODUCT_NOT_ON_SALE", HttpStatus.CONFLICT, "This product is no longer on sale",
                    Map.of("sales_close_at", p.salesCloseAt().toString()));
        }
        ProductService.Plan plan = productService.plans(p, productService.scope(p.organizerId(), p), now).stream()
                .filter(x -> x.installments() == n).findFirst().orElseThrow(ProductOrderService::notOffered);
        UUID org = p.organizerId();
        UUID passId = null;
        if (p.kind() == Product.Kind.PASS) {
            passId = passes.reserve(p, userId, name, plan).id();
        } else {
            Instant paidUntil = memberships.paidUntil(org, userId);
            if (paidUntil != null && !paidUntil.isBefore(p.validUntil())) {
                throw new ApiException("MEMBERSHIP_PAID", HttpStatus.CONFLICT, "Membership already paid",
                        Map.of("paid_until", paidUntil.toString()));
            }
        }
        Order o = orders.insertProduct(org, userId, channel, passId, new Order.Item(null, p.id(), 1,
                plan.amountsMad().getFirst(), List.of(new Order.Holder(name, userId))));
        return view(o);
    }

    /**
     * 04 POST /fan/passes/{id}/installments, under the pass row lock: the holder's pass (else 404) ACTIVE or SUSPENDED
     * (else 409 PASS_STATUS {status}) with an unpaid installment (else 409 PASS_STATUS {reason: PAID_UP}); its live
     * installment order again, or a new RESERVED one for the earliest unpaid installment (channel = the purchase's;
     * one item: the product, qty 1, that amount, held by the pass holder).
     */
    @Transactional
    public Placed installment(UUID userId, UUID passId) {
        Pass pass = passRows.lockHeld(userId, passId).orElseThrow(() -> ApiException.notFound("Pass not found"));
        if (pass.status() != Pass.Status.ACTIVE && pass.status() != Pass.Status.SUSPENDED) {
            throw new ApiException("PASS_STATUS", HttpStatus.CONFLICT, "This pass takes no installment",
                    Map.of("status", pass.status().name()));
        }
        UUID org = pass.organizerId();
        Installment next = passRows.installments(org, passId).stream().filter(i -> !i.paid()).findFirst()
                .orElseThrow(() -> new ApiException("PASS_STATUS", HttpStatus.CONFLICT, "This pass is paid up",
                        Map.of("reason", "PAID_UP")));
        UUID live = passRows.liveOrder(org, passId).orElse(null);
        if (live != null) {
            return new Placed(view(orders.findInOrg(org, live).orElseThrow()), false);
        }
        UUID purchase = passRows.purchaseOrder(org, passId).orElseThrow();
        Order o = orders.insertProduct(org, userId, orders.channel(org, purchase).orElseThrow(), passId,
                new Order.Item(null, pass.productId(), 1, next.amountMad(),
                        List.of(new Order.Holder(pass.holderName(), userId))));
        return new Placed(view(o), true);
    }

    private OrderService.View view(Order o) {
        return new OrderService.View(o, Map.of(), List.of(), null, null, null,
                productService.ofOrder(o.organizerId(), o.productId(), o.passId(), o.id()));
    }

    private static ApiException notOffered() {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("reason", "NOT_OFFERED", "fields", Map.of("installments", "not one of the product's plans")));
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }
}
