package ma.jomhor.sales.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory.SalePrice;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.application.OrderService.ItemRequest;
import ma.jomhor.sales.application.OrderService.View;
import ma.jomhor.sales.domain.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/04-api.md §Fan: POST /fan/orders, GET /fan/orders/{id}. The buyer is the fan token's sub. */
@RestController
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    // holders[] arrives in Lot 1; unknown fields are ignored until then.
    record OrderCreate(@NotNull Integer eventId, @NotNull Order.Channel channel,
                       @NotEmpty List<@NotNull @Valid ItemCreate> items) {}

    record ItemCreate(@NotNull UUID priceCategoryId, @NotNull @Min(1) Integer qty) {}

    record ItemDto(UUID priceCategoryId, int zoneId, String name, int qty, long unitPriceMad) {}

    record Reserved(UUID id, Order.Status status, Instant reservedUntil, long totalMad, List<ItemDto> items) {}

    record OrderDto(UUID id, int eventId, Order.Status status, long totalMad, Instant reservedUntil, Instant paidAt,
                    List<ItemDto> items, List<UUID> ticketIds) {}

    @PostMapping("/fan/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Reserved create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OrderCreate body) {
        View v = orders.place(UUID.fromString(jwt.getSubject()), body.eventId(), body.channel(),
                body.items().stream().map(i -> new ItemRequest(i.priceCategoryId(), i.qty())).toList());
        Order o = v.order();
        return new Reserved(o.id(), o.status(), o.reservedUntil(), o.totalMad(), items(v));
    }

    // ponytail: ticket_ids stays [] until BE-6 issues tickets.
    @GetMapping("/fan/orders/{id}")
    OrderDto get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        View v = orders.get(UUID.fromString(jwt.getSubject()), id);
        Order o = v.order();
        return new OrderDto(o.id(), o.eventId(), o.status(), o.totalMad(), o.reservedUntil(), o.paidAt(), items(v),
                List.of());
    }

    private static List<ItemDto> items(View v) {
        return v.order().items().stream().map(i -> {
            SalePrice p = v.categories().get(i.priceCategoryId());
            return new ItemDto(i.priceCategoryId(), p.zoneId(), p.name(), i.qty(), i.unitPriceMad());
        }).toList();
    }
}
