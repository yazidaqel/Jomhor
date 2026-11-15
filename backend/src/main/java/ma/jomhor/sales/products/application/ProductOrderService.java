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

    ProductOrderService(Products products, ProductService productService, Orders orders,
                        MembershipService memberships) {
        this.products = products;
        this.productService = productService;
        this.orders = orders;
        this.memberships = memberships;
    }

    /**
     * Checks in 04 order: holder_name and installments (422), the product visible as GET /fan/products/{id} (404),
     * now (database clock) before its sales_close_at (409 PRODUCT_NOT_ON_SALE), MEMBERSHIP already paid to its
     * valid_until (409 MEMBERSHIP_PAID). The buyer holds the item.
     */
    @Transactional
    public OrderService.View place(UUID userId, UUID productId, Order.Channel channel, String holderName,
                                   Integer installments) {
        String name = HolderNames.normalize(holderName).orElseThrow(() -> invalid("holder_name", HolderNames.INVALID));
        if (installments != null && installments != 1) {
            throw invalid("installments", "1 (in full)");
        }
        // ponytail: PASS orders come with the pass lifecycle (plan lot-5 BE-53); until then a PASS product is a 404.
        Product p = products.onSale(productId).filter(x -> x.kind() == Product.Kind.MEMBERSHIP)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
        if (!orders.now().isBefore(p.salesCloseAt())) {
            throw new ApiException("PRODUCT_NOT_ON_SALE", HttpStatus.CONFLICT, "This product is no longer on sale",
                    Map.of("sales_close_at", p.salesCloseAt().toString()));
        }
        UUID org = p.organizerId();
        Instant paidUntil = memberships.paidUntil(org, userId);
        if (paidUntil != null && !paidUntil.isBefore(p.validUntil())) {
            throw new ApiException("MEMBERSHIP_PAID", HttpStatus.CONFLICT, "Membership already paid",
                    Map.of("paid_until", paidUntil.toString()));
        }
        Order o = orders.insertProduct(org, userId, channel, null, new Order.Item(null, p.id(), 1, p.priceMad(),
                List.of(new Order.Holder(name, userId))));
        return new OrderService.View(o, Map.of(), List.of(), null, null, null, productService.ofOrder(org, p.id()));
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }
}
