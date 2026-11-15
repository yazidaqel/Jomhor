package ma.jomhor.sales.products.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.sales.products.application.ProductOrderService;
import ma.jomhor.sales.products.domain.Product;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/04-api.md §Fan POST /fan/product-orders. The buyer is the fan token's sub. */
@RestController
class FanProductOrderController {

    record ProductOrderCreate(@NotNull UUID productId, @NotNull Order.Channel channel, String holderName,
                              Integer installments) {}

    record ProductRef(UUID id, Product.Kind kind, String name) {}

    record Created(UUID id, Order.Status status, Instant reservedUntil, long totalMad, ProductRef product, UUID passId,
                   List<ProductItemDto> items, Object group) {}

    private final ProductOrderService orders;

    FanProductOrderController(ProductOrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/fan/product-orders")
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProductOrderCreate body) {
        if (body.channel() == Order.Channel.POS) { // APP | WEB only
            throw new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                    Map.of("fields", Map.of("channel", "APP or WEB")));
        }
        OrderService.View v = orders.place(UUID.fromString(jwt.getSubject()), body.productId(), body.channel(),
                body.holderName(), body.installments());
        Order o = v.order();
        Product p = v.product().product();
        return new Created(o.id(), o.status(), o.reservedUntil(), o.totalMad(), new ProductRef(p.id(), p.kind(),
                p.name()), o.passId(), ProductItemDto.of(v), null);
    }
}
