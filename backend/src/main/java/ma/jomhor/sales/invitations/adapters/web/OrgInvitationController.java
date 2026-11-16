package ma.jomhor.sales.invitations.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory.SalePrice;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.HolderNames;
import ma.jomhor.sales.application.OrderService.HolderRequest;
import ma.jomhor.sales.application.OrderService.ItemRequest;
import ma.jomhor.sales.application.OrderService.View;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.sales.invitations.application.InvitationService;
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
 * docs/04-api.md §Organizer invitations. POST and print: ADMIN, OPS (the interceptor already refuses FINANCE writes
 * and CASHIER); the list: ADMIN, OPS, FINANCE. Never a phone; a slip's {@code qr} is the static v1 token only, sent
 * {@code no-store}.
 */
@RestController
class OrgInvitationController {

    record Body(@NotNull UUID invitationId, @NotEmpty List<@NotNull @Valid ItemCreate> items) {}

    /** holders are checked by OrderService, after the event and its window (04 check order). */
    record ItemCreate(@NotNull UUID priceCategoryId, @NotNull @Min(1) Integer qty, List<HolderCreate> holders) {}

    /** {@code pays}: accepted only to be refused (04: pays → 422). */
    record HolderCreate(String name, String phone, Boolean pays) {}

    // The §Point of sale sale answer.
    record HolderDto(String label) {}

    record ItemDto(UUID priceCategoryId, int zoneId, String zoneName, String name, int qty, long unitPriceMad,
                   List<HolderDto> holders) {}

    record SaleDto(UUID id, Order.Status status, Order.Channel channel, long totalMad, List<ItemDto> items,
                   List<UUID> ticketIds) {}

    // The list.
    record StaffRef(UUID id, String name) {}

    record ZoneDto(int id, String code, String name) {}

    record LineDto(UUID priceCategoryId, String name, ZoneDto zone, int qty) {}

    enum Delivery { WHATSAPP, PAPER }

    record TicketDto(UUID id, String ticketNo, Ticket.Status status, String holderName, String holderLabel,
                     Delivery delivery) {}

    record InvitationDto(UUID id, Instant createdAt, StaffRef invitedBy, int places, List<LineDto> items,
                         List<TicketDto> tickets) {}

    record ListDto(List<InvitationDto> invitations) {}

    // The print data, the shape of GET /org/pos/orders/{id}/tickets.
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

    private final InvitationService invitations;

    OrgInvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/org/events/{id}/invitations")
    ResponseEntity<SaleDto> create(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                                   @Valid @RequestBody Body body) {
        InvitationService.Issued issued = invitations.create(org.organizerId(), org.userId(), body.invitationId(), id,
                body.items().stream().map(i -> new ItemRequest(i.priceCategoryId(), i.qty(), i.holders() == null ? null
                        : i.holders().stream().map(h -> h == null ? null
                                : new HolderRequest(h.name(), h.phone(), h.pays() != null)).toList()))
                        .toList());
        View v = issued.view();
        Order o = v.order();
        List<ItemDto> items = o.items().stream().map(i -> {
            SalePrice p = v.categories().get(i.priceCategoryId());
            return new ItemDto(i.priceCategoryId(), p.zoneId(), p.zoneName(), p.name(), i.qty(), i.unitPriceMad(),
                    i.holders().stream().map(h -> new HolderDto(HolderNames.label(h.name()))).toList());
        }).toList();
        return ResponseEntity.status(issued.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new SaleDto(o.id(), o.status(), o.channel(), o.totalMad(), items, v.ticketIds()));
    }

    @GetMapping("/org/events/{id}/invitations")
    ListDto list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        InvitationService.Listing l = invitations.list(org.organizerId(), id);
        return new ListDto(l.invitations().stream().map(x -> {
            Order o = x.invitation().order();
            List<LineDto> lines = o.items().stream().map(i -> {
                Event.PriceCategory c = l.categories().get(i.priceCategoryId());
                return new LineDto(c.id(), c.name(), new ZoneDto(c.zoneId(), c.zoneCode(), c.zoneName()), i.qty());
            }).toList();
            List<TicketDto> tickets = x.tickets().stream()
                    .map(t -> new TicketDto(t.id(), TicketToken.ticketNo(t.id()), t.status(), t.holderName(),
                            t.holderName() == null ? null : HolderNames.label(t.holderName()),
                            t.holderUserId() == null ? Delivery.PAPER : Delivery.WHATSAPP))
                    .sorted(Comparator.comparing(TicketDto::ticketNo)).toList();
            return new InvitationDto(o.id(), x.invitation().createdAt(), new StaffRef(x.invitation().invitedById(),
                    x.invitation().invitedByName()), o.places(), lines, tickets);
        }).toList());
    }

    @GetMapping("/org/invitations/{id}/tickets")
    ResponseEntity<PrintDto> print(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        org.adminOrOps();
        InvitationService.Print p = invitations.print(org.organizerId(), org.userId(), id);
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
}
