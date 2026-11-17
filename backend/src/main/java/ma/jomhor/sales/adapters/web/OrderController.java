package ma.jomhor.sales.adapters.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.catalog.application.Inventory.SalePrice;
import ma.jomhor.common.HolderNames;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.application.CashInstructions;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.application.OrderService.HolderRequest;
import ma.jomhor.sales.application.OrderService.ItemRequest;
import ma.jomhor.sales.application.OrderService.View;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.application.PendingPayments.AttemptStatus;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.sales.products.adapters.web.ProductItemDto;
import ma.jomhor.sales.products.application.ProductService;
import ma.jomhor.sales.products.domain.Product;
import ma.jomhor.sales.promos.domain.PromoCode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan: POST /fan/orders ({@code X-Admission-Token} while the event's waiting room is live),
 * GET /fan/orders/{id}, GET /fan/orders. The buyer is the fan token's sub.
 */
@RestController
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /** {@code promo_code}: absent, null or blank = none (04 §Promo codes). */
    record OrderCreate(@NotNull Integer eventId, @NotNull Order.Channel channel,
                       @NotEmpty List<@NotNull @Valid ItemCreate> items, String promoCode) {}

    /** holders are checked by OrderService, after the event and its sale window (04 check order). */
    record ItemCreate(@NotNull UUID priceCategoryId, @NotNull @Min(1) Integer qty, List<HolderCreate> holders) {}

    /** {@code pays}: split pay (04 §Split pay); absent = false. */
    record HolderCreate(String name, String phone, Boolean pays) {}

    /** Only the label: never the full name, the phone or the holding account. */
    record HolderDto(String label) {}

    record ItemDto(UUID priceCategoryId, int zoneId, String zoneName, String name, int qty, long unitPriceMad,
                   List<HolderDto> holders) {}

    /** {@code group}: {@link InitiatorGroupDto}, {@link MemberGroupDto} or null (04 §Fan, Lot 4). */
    record Reserved(UUID id, Order.Status status, Instant reservedUntil, long totalMad, List<ItemDto> items,
                    Object group, String promoCode, long discountMad) {}

    /** {@code items}: {@link ItemDto}s, or {@link ProductItemDto}s on a product order (event_id null). */
    record OrderDto(UUID id, Integer eventId, Order.Channel channel, Order.Status status, AttemptStatus paymentStatus,
                    long totalMad, Instant reservedUntil, Instant paidAt, List<?> items, List<UUID> ticketIds,
                    Order.PaymentMethod paymentMethod, CashDto cash, Object group, ProductDto product,
                    InstallmentDto installment, UUID passId, String promoCode, long discountMad) {}

    /** An installment order's place in its pass's schedule (04 GET /fan/orders/{id}, plan lot-5 3b). */
    record InstallmentDto(int seq, int of) {}

    /** 04 §Club directory organizer, no id. */
    record OrganizerDto(String slug, String name, String crestUrl) {}

    /**
     * A product order's product (04 GET /fan/orders/{id}, Lot 5 step 3), read even once it is off sale;
     * {@code payment_methods} and {@code cash_until} as on /fan/products.
     */
    record ProductDto(UUID id, Product.Kind kind, String name, OrganizerDto organizer, String timezone,
                      List<String> paymentMethods, Instant cashUntil) {}

    /** A product order's row in GET /fan/orders. */
    record ProductRefDto(UUID id, Product.Kind kind, String name, String timezone) {}

    /** Labels only: never a member's phone or full name. {@code pay_url} comes from server config. */
    record MemberDto(UUID orderId, String holderLabel, int places, long totalMad, Order.Status status,
                     Instant reservedUntil, String payUrl) {}

    record InitiatorGroupDto(Order.GroupRole role, List<MemberDto> members) {}

    record MemberGroupDto(Order.GroupRole role, String initiatorLabel, Order.Status initiatorStatus) {}

    /** {@code {name, address?, hours?}}: a field ops left out of the settings is left out here too. */
    record CashPointDto(String name, @JsonInclude(JsonInclude.Include.NON_NULL) String address,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String hours) {}

    /** {@code pay_code}: the 6 stored symbols, uppercase; clients show it as {@code JMH 4F7K 29} (04 §Cash codes). */
    record CashDto(String payCode, long amountMad, List<CashPointDto> points, List<String> networks) {}

    record EventRefDto(int id, String title, Instant startsAt, String timezone) {}

    record SummaryDto(UUID id, EventRefDto event, Order.Channel channel, Order.Status status, long totalMad,
                      Instant reservedUntil, Order.PaymentMethod paymentMethod, String payCode, Instant createdAt,
                      Order.GroupRole groupRole, ProductRefDto product) {}

    record OrderList(List<SummaryDto> orders) {}

    @PostMapping("/fan/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Reserved create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OrderCreate body,
                    @RequestHeader(name = "X-Admission-Token", required = false) String admissionToken) {
        if (body.channel() == Order.Channel.POS) { // APP | WEB only: a till sale goes through /org/pos/orders
            throw invalid("channel", "APP or WEB");
        }
        // The body's shape, before admission and any SQL: trimmed, uppercased, then ^[A-Z0-9]{4,20}$.
        String promoCode = PromoCode.normalize(body.promoCode());
        if (promoCode != null && promoCode.isEmpty()) {
            promoCode = null;
        } else if (promoCode != null && !PromoCode.wellFormed(promoCode)) {
            throw invalid("promo_code", "4-20 letters A-Z or digits");
        }
        View v = orders.place(UUID.fromString(jwt.getSubject()), body.eventId(), body.channel(), admissionToken,
                body.items().stream().map(i -> new ItemRequest(i.priceCategoryId(), i.qty(), i.holders() == null ? null
                        : i.holders().stream().map(h -> h == null ? null : new HolderRequest(h.name(), h.phone(),
                                Boolean.TRUE.equals(h.pays()))).toList()))
                        .toList(), promoCode);
        Order o = v.order();
        return new Reserved(o.id(), o.status(), o.reservedUntil(), o.totalMad(), items(v), group(v.group()),
                o.promoCode(), o.discountMad());
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }

    @GetMapping("/fan/orders/{id}")
    OrderDto get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        View v = orders.get(UUID.fromString(jwt.getSubject()), id);
        Order o = v.order();
        CashInstructions.Cash c = v.cash();
        return new OrderDto(o.id(), o.eventId(), o.channel(), o.status(),
                v.attempt() == null ? null : v.attempt().status(), o.totalMad(), o.reservedUntil(), o.paidAt(),
                v.product() == null ? items(v) : ProductItemDto.of(v),
                v.ticketIds(), v.attempt() == null ? null : v.attempt().method(),
                c == null ? null : new CashDto(c.payCode(), c.amountMad(),
                        c.points().stream().map(p -> new CashPointDto(p.name(), p.address(), p.hours())).toList(),
                        c.networks()),
                group(v.group()), product(v.product()),
                v.product() == null || v.product().installment() == null ? null
                        : new InstallmentDto(v.product().installment().seq(), v.product().installment().of()),
                o.passId(), o.promoCode(), o.discountMad());
    }

    private static ProductDto product(ProductService.FanView f) {
        if (f == null) {
            return null;
        }
        Product p = f.product();
        return new ProductDto(p.id(), p.kind(), p.name(), new OrganizerDto(f.organizer().slug(), f.organizer().name(),
                f.organizer().crestUrl()), f.timezone(), f.payment().methods(), f.payment().cashUntil());
    }

    private static Object group(OrderService.Group g) {
        if (g == null) {
            return null;
        }
        if (g.role() == Order.GroupRole.MEMBER) {
            return new MemberGroupDto(g.role(), g.initiatorLabel(), g.initiatorStatus());
        }
        return new InitiatorGroupDto(g.role(), g.members().stream().map(m -> new MemberDto(m.orderId(), m.holderLabel(),
                m.places(), m.totalMad(), m.status(), m.reservedUntil(), m.payUrl())).toList());
    }

    @GetMapping("/fan/orders")
    ResponseEntity<OrderList> list(@AuthenticationPrincipal Jwt jwt) {
        List<SummaryDto> rows = orders.list(UUID.fromString(jwt.getSubject())).stream().map(r -> {
            Orders.Listed o = r.order();
            Inventory.EventLabel e = r.event();
            ProductService.FanView f = r.product();
            return new SummaryDto(o.id(), e == null ? null : new EventRefDto(e.id(), e.title(), e.startsAt(),
                    e.timezone()), o.channel(), o.status(), o.totalMad(), o.reservedUntil(), r.paymentMethod(),
                    r.payCode(), o.createdAt(), o.groupRole(), f == null ? null : new ProductRefDto(f.product().id(),
                    f.product().kind(), f.product().name(), f.timezone()));
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
