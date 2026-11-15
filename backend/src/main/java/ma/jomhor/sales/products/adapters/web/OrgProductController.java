package ma.jomhor.sales.products.adapters.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.sales.products.application.ProductService;
import ma.jomhor.sales.products.domain.Product;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Organizer /org/products*. Roles from OrgContextInterceptor: ADMIN and OPS read and write, FINANCE
 * reads, CASHIER, GATE and SUPERVISOR get 403. GET /org/products/{id} is the list row + figures + refunds_required;
 * POST and PATCH return the list row. Money in centimes.
 */
@RestController
class OrgProductController {

    record ProductCreate(@NotNull Product.Kind kind, @NotNull String name, @NotNull @Positive Long priceMad,
                         @PositiveOrZero Integer quota, UUID seriesId, Integer zoneId, @NotNull Instant salesCloseAt,
                         Instant validUntil, Boolean onSale) {
        ProductService.Input input() {
            return new ProductService.Input(kind, name, priceMad, quota, seriesId, zoneId, salesCloseAt, validUntil,
                    onSale);
        }
    }

    record ProductPatch(Product.Kind kind, String name, @Positive Long priceMad, @PositiveOrZero Integer quota,
                        UUID seriesId, Integer zoneId, Instant salesCloseAt, Instant validUntil, Boolean onSale) {
        ProductService.Input input() {
            return new ProductService.Input(kind, name, priceMad, quota, seriesId, zoneId, salesCloseAt, validUntil,
                    onSale);
        }
    }

    record SeriesRef(UUID id, String name) {}

    record VenueRef(UUID id, String name, String timezone) {}

    record ZoneRef(int id, String code, String name, VenueRef venue) {}

    record ProductDto(UUID id, Product.Kind kind, String name, long priceMad, boolean onSale, Instant salesCloseAt,
                      Integer quota, int sold, Instant validUntil, SeriesRef series, ZoneRef zone) {
        static ProductDto of(ProductService.View v) {
            Product p = v.product();
            var pass = v.pass();
            return new ProductDto(p.id(), p.kind(), p.name(), p.priceMad(), p.onSale(), p.salesCloseAt(), p.quota(),
                    p.sold(), p.validUntil(),
                    pass == null ? null : new SeriesRef(pass.series().id(), pass.series().name()),
                    pass == null ? null : new ZoneRef(pass.zone().id(), pass.zone().code(), pass.zone().name(),
                            new VenueRef(pass.zone().venueId(), pass.zone().venueName(), pass.zone().timezone())));
        }
    }

    record PassCountsDto(long pending, long active, long suspended) {}

    record EventFiguresDto(int eventId, String title, Instant startsAt, Event.Status status, long issued, long expected,
                           long passRemaining) {}

    record PassFiguresDto(PassCountsDto passes, long revenueMad, List<EventFiguresDto> events) {}

    record MembershipFiguresDto(long paidMembers, long revenueMad) {}

    record RefundDto(UUID paymentId, UUID orderId, long amountMad, String reason, Instant at) {}

    /** The list row's fields, then figures (by kind) and refunds_required. */
    record ProductDetailDto(@JsonUnwrapped ProductDto product, Object figures, List<RefundDto> refundsRequired) {
        static ProductDetailDto of(ProductService.Detail d) {
            Object figures = switch (d.figures()) {
                case ProductService.PassFigures f -> new PassFiguresDto(
                        new PassCountsDto(f.passes().pending(), f.passes().active(), f.passes().suspended()),
                        f.revenueMad(), f.events().stream().map(e -> new EventFiguresDto(e.eventId(), e.title(),
                                e.startsAt(), e.status(), e.issued(), e.expected(), e.passRemaining())).toList());
                case ProductService.MembershipFigures f -> new MembershipFiguresDto(f.paidMembers(), f.revenueMad());
            };
            return new ProductDetailDto(ProductDto.of(d.view()), figures, d.refundsRequired().stream()
                    .map(r -> new RefundDto(r.paymentId(), r.orderId(), r.amountMad(), r.reason(), r.at())).toList());
        }
    }

    private final ProductService products;

    OrgProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping("/org/products")
    List<ProductDto> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                          @RequestParam(required = false) Product.Kind kind) {
        return products.list(org.organizerId(), kind).stream().map(ProductDto::of).toList();
    }

    @PostMapping("/org/products")
    @ResponseStatus(HttpStatus.CREATED)
    ProductDto create(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @Valid @RequestBody ProductCreate body) {
        return ProductDto.of(products.create(org.organizerId(), org.userId(), body.input()));
    }

    @GetMapping("/org/products/{id}")
    ProductDetailDto get(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        return ProductDetailDto.of(products.detail(org.organizerId(), id));
    }

    @PatchMapping("/org/products/{id}")
    ProductDto update(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                      @Valid @RequestBody ProductPatch body) {
        return ProductDto.of(products.update(org.organizerId(), org.userId(), id, body.input()));
    }
}
