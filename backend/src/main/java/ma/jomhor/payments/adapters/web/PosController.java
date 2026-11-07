package ma.jomhor.payments.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory.SalePrice;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.HolderNames;
import ma.jomhor.payments.application.PosService;
import ma.jomhor.payments.domain.PosSession;
import ma.jomhor.sales.application.OrderService.HolderRequest;
import ma.jomhor.sales.application.OrderService.ItemRequest;
import ma.jomhor.sales.application.OrderService.View;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.tenancy.domain.OrgContext;
import ma.jomhor.tickets.domain.Ticket;
import ma.jomhor.tickets.domain.TicketToken;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Point of sale: /org/pos/**. ADMIN, OPS, CASHIER (the org interceptor refuses every other role).
 * Responses are the records below: never a holder's full name or phone, never K_e or K_t; a slip's {@code qr} is
 * the static v1 token only, sent {@code no-store}.
 */
@RestController
class PosController {

    record OpenBody(String label) {}

    record CloseBody(@NotNull @PositiveOrZero Long declaredMad) {}

    record SaleBody(@NotNull UUID saleId, @NotNull Integer eventId, @NotEmpty List<@NotNull @Valid ItemCreate> items) {}

    /** holders are checked by OrderService, after the event and its sale window (04 check order). */
    record ItemCreate(@NotNull UUID priceCategoryId, @NotNull @Min(1) Integer qty, List<HolderCreate> holders) {}

    record HolderCreate(String name, String phone) {}

    record CollectBody(@NotNull Long amountMad) {}

    record SessionDto(UUID id, String label, Instant openedAt, long orders, long tickets, long prints,
                      long collectedMad) {
        static SessionDto of(PosSession s) {
            return new SessionDto(s.id(), s.label(), s.openedAt(), s.orders(), s.tickets(), s.prints(), s.collectedMad());
        }
    }

    record CurrentSession(SessionDto session) {}

    record StaffRef(UUID id, String name) {}

    /** The finance session shape (04 GET /org/finance/cash sessions[]). */
    record ClosedSession(UUID id, String label, StaffRef staff, Instant openedAt, Instant closedAt, long orders,
                         long tickets, long prints, Long expectedMad, Long declaredMad, Long varianceMad) {}

    record HolderDto(String label) {}

    record ItemDto(UUID priceCategoryId, int zoneId, String zoneName, String name, int qty, long unitPriceMad,
                   List<HolderDto> holders) {}

    record SaleDto(UUID id, Order.Status status, Order.Channel channel, long totalMad, List<ItemDto> items,
                   List<UUID> ticketIds) {}

    record EventRef(int id, String title, Instant startsAt, String timezone) {}

    record CodeDto(UUID orderId, Order.Status status, boolean payable, Instant reservedUntil, long amountMad, int places,
                   EventRef event, List<String> holders) {}

    record CollectedDto(UUID orderId, Order.Status status, List<UUID> ticketIds) {}

    record VenueRef(String name) {}

    record PrintEvent(int id, String title, Instant startsAt, String timezone, VenueRef venue) {}

    record ZoneRef(String code, String name) {}

    record SlipDto(UUID id, String ticketNo, Ticket.Status status, ZoneRef zone, List<String> gateCodes,
                   String holderLabel, long unitPriceMad, String qr) {
        @Override
        public String toString() {
            return "SlipDto[" + id + "]";
        }
    }

    record PrintDto(UUID orderId, PrintEvent event, List<SlipDto> tickets) {
        @Override
        public String toString() {
            return "PrintDto[" + orderId + "]";
        }
    }

    private final PosService pos;

    PosController(PosService pos) {
        this.pos = pos;
    }

    @GetMapping("/org/pos/session")
    CurrentSession session(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        return new CurrentSession(pos.session(org.organizerId(), org.userId()).map(SessionDto::of).orElse(null));
    }

    @PostMapping("/org/pos/sessions")
    ResponseEntity<SessionDto> open(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                                    @RequestBody OpenBody body) {
        PosService.Opened o = pos.open(org.organizerId(), org.userId(), body.label());
        return ResponseEntity.status(o.created() ? HttpStatus.CREATED : HttpStatus.OK).body(SessionDto.of(o.session()));
    }

    @PostMapping("/org/pos/sessions/{id}/close")
    ClosedSession close(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                        @Valid @RequestBody CloseBody body) {
        PosSession s = pos.close(org.organizerId(), org.userId(), org.role(), id, body.declaredMad());
        return new ClosedSession(s.id(), s.label(), new StaffRef(s.staffId(), s.staffName()), s.openedAt(),
                s.closedAt(), s.orders(), s.tickets(), s.prints(), s.expectedMad(), s.declaredMad(), s.varianceMad());
    }

    @PostMapping("/org/pos/orders")
    ResponseEntity<SaleDto> sale(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                                 @Valid @RequestBody SaleBody body) {
        PosService.Sold sold = pos.sale(org.organizerId(), org.userId(), body.saleId(), body.eventId(),
                body.items().stream().map(i -> new ItemRequest(i.priceCategoryId(), i.qty(), i.holders() == null ? null
                        : i.holders().stream().map(h -> h == null ? null : new HolderRequest(h.name(), h.phone())).toList()))
                        .toList());
        View v = sold.view();
        Order o = v.order();
        return ResponseEntity.status(sold.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new SaleDto(o.id(), o.status(), o.channel(), o.totalMad(), items(v), v.ticketIds()));
    }

    @GetMapping("/org/pos/codes/{code}")
    CodeDto lookup(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable String code) {
        PosService.Lookup l = pos.lookup(org.organizerId(), code);
        Order o = l.order();
        return new CodeDto(o.id(), o.status(), l.payable(), o.reservedUntil(), l.amountMad(), o.places(),
                new EventRef(l.event().id(), l.event().title(), l.event().startsAt(), l.event().timezone()), l.holders());
    }

    @PostMapping("/org/pos/codes/{code}/collect")
    CollectedDto collect(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable String code,
                         @Valid @RequestBody CollectBody body) {
        PosService.Collected c = pos.collect(org.organizerId(), org.userId(), code, body.amountMad());
        return new CollectedDto(c.orderId(), Order.Status.PAID, c.ticketIds());
    }

    @GetMapping("/org/pos/orders/{id}/tickets")
    ResponseEntity<PrintDto> print(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        PosService.Print p = pos.print(org.organizerId(), org.userId(), org.role(), id);
        Event.Listing e = p.slips().event();
        List<SlipDto> slips = p.slips().slips().stream().map(s -> {
            Ticket.OfOrder t = s.ticket();
            return new SlipDto(t.id(), TicketToken.ticketNo(t.id()), t.status(), new ZoneRef(s.zone().code(),
                    s.zone().name()), s.zone().gateCodes(), s.holderLabel(), p.unitPrices().get(t.priceCategoryId()),
                    s.qr());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PrintDto(p.order().id(),
                new PrintEvent(e.id(), e.title(), e.startsAt(), e.timezone(), new VenueRef(e.venueName())), slips));
    }

    private static List<ItemDto> items(View v) {
        return v.order().items().stream().map(i -> {
            SalePrice p = v.categories().get(i.priceCategoryId());
            return new ItemDto(i.priceCategoryId(), p.zoneId(), p.zoneName(), p.name(), i.qty(), i.unitPriceMad(),
                    i.holders().stream().map(h -> new HolderDto(HolderNames.label(h.name()))).toList());
        }).toList();
    }
}
