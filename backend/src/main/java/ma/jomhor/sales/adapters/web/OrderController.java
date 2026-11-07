package ma.jomhor.sales.adapters.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.catalog.application.Inventory.SalePrice;
import ma.jomhor.common.HolderNames;
import ma.jomhor.sales.application.CashInstructions;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.application.OrderService.HolderRequest;
import ma.jomhor.sales.application.OrderService.ItemRequest;
import ma.jomhor.sales.application.OrderService.View;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.application.PendingPayments.AttemptStatus;
import ma.jomhor.sales.domain.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/04-api.md §Fan: POST /fan/orders, GET /fan/orders/{id}, GET /fan/orders. The buyer is the fan token's sub. */
@RestController
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    record OrderCreate(@NotNull Integer eventId, @NotNull Order.Channel channel,
                       @NotEmpty List<@NotNull @Valid ItemCreate> items) {}

    /** holders are checked by OrderService, after the event and its sale window (04 check order). */
    record ItemCreate(@NotNull UUID priceCategoryId, @NotNull @Min(1) Integer qty, List<HolderCreate> holders) {}

    record HolderCreate(String name, String phone) {}

    /** Only the label: never the full name, the phone or the holding account. */
    record HolderDto(String label) {}

    record ItemDto(UUID priceCategoryId, int zoneId, String zoneName, String name, int qty, long unitPriceMad,
                   List<HolderDto> holders) {}

    record Reserved(UUID id, Order.Status status, Instant reservedUntil, long totalMad, List<ItemDto> items) {}

    record OrderDto(UUID id, int eventId, Order.Channel channel, Order.Status status, AttemptStatus paymentStatus,
                    long totalMad, Instant reservedUntil, Instant paidAt, List<ItemDto> items, List<UUID> ticketIds,
                    Order.PaymentMethod paymentMethod, CashDto cash) {}

    /** {@code {name, address?, hours?}}: a field ops left out of the settings is left out here too. */
    record CashPointDto(String name, @JsonInclude(JsonInclude.Include.NON_NULL) String address,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String hours) {}

    /** {@code pay_code}: the 6 stored symbols, uppercase; clients show it as {@code JMH 4F7K 29} (04 §Cash codes). */
    record CashDto(String payCode, long amountMad, List<CashPointDto> points, List<String> networks) {}

    record EventRefDto(int id, String title, Instant startsAt, String timezone) {}

    record SummaryDto(UUID id, EventRefDto event, Order.Channel channel, Order.Status status, long totalMad,
                      Instant reservedUntil, Order.PaymentMethod paymentMethod, Instant createdAt) {}

    record OrderList(List<SummaryDto> orders) {}

    @PostMapping("/fan/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Reserved create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OrderCreate body) {
        View v = orders.place(UUID.fromString(jwt.getSubject()), body.eventId(), body.channel(),
                body.items().stream().map(i -> new ItemRequest(i.priceCategoryId(), i.qty(), i.holders() == null ? null
                        : i.holders().stream().map(h -> h == null ? null : new HolderRequest(h.name(), h.phone())).toList()))
                        .toList());
        Order o = v.order();
        return new Reserved(o.id(), o.status(), o.reservedUntil(), o.totalMad(), items(v));
    }

    @GetMapping("/fan/orders/{id}")
    OrderDto get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        View v = orders.get(UUID.fromString(jwt.getSubject()), id);
        Order o = v.order();
        CashInstructions.Cash c = v.cash();
        return new OrderDto(o.id(), o.eventId(), o.channel(), o.status(),
                v.attempt() == null ? null : v.attempt().status(), o.totalMad(), o.reservedUntil(), o.paidAt(), items(v),
                v.ticketIds(), v.attempt() == null ? null : v.attempt().method(),
                c == null ? null : new CashDto(c.payCode(), c.amountMad(),
                        c.points().stream().map(p -> new CashPointDto(p.name(), p.address(), p.hours())).toList(),
                        c.networks()));
    }

    @GetMapping("/fan/orders")
    ResponseEntity<OrderList> list(@AuthenticationPrincipal Jwt jwt) {
        List<SummaryDto> rows = orders.list(UUID.fromString(jwt.getSubject())).stream().map(r -> {
            Orders.Listed o = r.order();
            Inventory.EventLabel e = r.event();
            return new SummaryDto(o.id(), new EventRefDto(e.id(), e.title(), e.startsAt(), e.timezone()), o.channel(),
                    o.status(), o.totalMad(), o.reservedUntil(), r.paymentMethod(), o.createdAt());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new OrderList(rows));
    }

    private static List<ItemDto> items(View v) {
        return v.order().items().stream().map(i -> {
            SalePrice p = v.categories().get(i.priceCategoryId());
            return new ItemDto(i.priceCategoryId(), p.zoneId(), p.zoneName(), p.name(), i.qty(), i.unitPriceMad(),
                    i.holders().stream().map(h -> new HolderDto(HolderNames.label(h.name()))).toList());
        }).toList();
    }
}
