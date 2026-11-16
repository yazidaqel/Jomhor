package ma.jomhor.sales.products.adapters.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.sales.products.application.ProductService;
import ma.jomhor.sales.products.domain.Product;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan: public GET /fan/products and /fan/products/{id} (no token; SecurityConfig's public chain, no
 * cache headers). {@code organizer} missing or malformed, or another {@code kind} → 400 (Spring's own). Field by field:
 * no quota or sold, only remaining.
 */
@RestController
class FanProductController {

    /** 04 §Club directory: no id here, organizer_id sits beside it; slug null when unlisted. */
    record OrganizerDto(String slug, String name, String crestUrl) {}

    record SeriesDto(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {}

    record ZoneDto(int id, String code, String name) {}

    record VenueDto(String name, String city, String timezone) {}

    record EventDto(int id, String title, Event.Type eventType, Instant startsAt) {}

    record PassDto(SeriesDto series, ZoneDto zone, VenueDto venue, List<EventDto> events) {}

    /** One way to pay: {@code installments} payments of {@code amounts_mad}, every {@code interval_days} (null in full). */
    record PlanDto(int installments, List<Long> amountsMad, Long intervalDays) {
        static PlanDto of(ProductService.Plan p) {
            return new PlanDto(p.installments(), p.amountsMad(), p.interval() == null ? null : p.interval().toDays());
        }
    }

    record ProductDto(UUID id, UUID organizerId, OrganizerDto organizer, String timezone, Product.Kind kind,
                      String name, long priceMad, Instant salesCloseAt,
                      Integer remaining, boolean soldOut, Instant validUntil, PassDto pass, List<PlanDto> plans,
                      List<String> paymentMethods, Instant cashUntil) {
        static ProductDto of(ProductService.FanView v) {
            Product p = v.product();
            var s = v.pass();
            PassDto pass = s == null ? null : new PassDto(
                    new SeriesDto(s.series().id(), s.series().name(), s.series().startsOn(), s.series().endsOn()),
                    new ZoneDto(s.zone().id(), s.zone().code(), s.zone().name()),
                    new VenueDto(s.zone().venueName(), s.zone().venueCity(), s.zone().timezone()),
                    v.events().stream().map(e -> new EventDto(e.id(), e.title(), e.eventType(), e.startsAt())).toList());
            Integer remaining = p.remaining();
            return new ProductDto(p.id(), p.organizerId(), new OrganizerDto(v.organizer().slug(),
                    v.organizer().name(), v.organizer().crestUrl()), v.timezone(), p.kind(), p.name(), p.priceMad(),
                    p.salesCloseAt(),
                    remaining, remaining != null && remaining == 0, p.validUntil(), pass,
                    v.plans().stream().map(PlanDto::of).toList(), v.payment().methods(), v.payment().cashUntil());
        }
    }

    private final ProductService products;

    FanProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping("/fan/products")
    List<ProductDto> list(@RequestParam UUID organizer, @RequestParam(required = false) Product.Kind kind) {
        return products.onSale(organizer, kind).stream().map(ProductDto::of).toList();
    }

    @GetMapping("/fan/products/{id}")
    ProductDto get(@PathVariable UUID id) {
        return ProductDto.of(products.onSale(id));
    }
}
