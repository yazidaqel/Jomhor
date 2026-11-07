package ma.jomhor.tickets.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import ma.jomhor.tenancy.domain.OrgContext;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tickets.application.TicketAdminService;
import ma.jomhor.tickets.application.Tickets;
import ma.jomhor.tickets.domain.Ticket;
import ma.jomhor.tickets.domain.TicketToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Organizer tickets: the lookup (ADMIN, OPS, FINANCE), block and unblock (ADMIN, OPS), refund
 * (ADMIN). CASHIER, GATE and SUPERVISOR get 403 from the org interceptor. A row carries the holder's full name (staff
 * only), never a phone, K_e or K_t.
 */
@RestController
class OrgTicketController {

    record SearchBody(String q) {}

    record ReasonBody(String reason) {}

    record ZoneDto(int id, String code, String name) {}

    record RefundDto(UUID id, String status, long amountMad) {}

    record RowDto(UUID id, String ticketNo, Ticket.Status status, ZoneDto zone, String holderName, String holderLabel,
                  long unitPriceMad, UUID orderId, String channel, String paymentMethod, Instant issuedAt,
                  RefundDto refund, String transferredTo, String transferredFrom) {
        static RowDto of(Tickets.Row r) {
            return new RowDto(r.id(), TicketToken.ticketNo(r.id()), r.status(),
                    new ZoneDto(r.zoneId(), r.zoneCode(), r.zoneName()), r.holderName(),
                    r.holderName() == null ? null : HolderNames.label(r.holderName()), r.unitPriceMad(), r.orderId(),
                    r.channel(), r.paymentMethod(), r.issuedAt(),
                    r.refundId() == null ? null : new RefundDto(r.refundId(), r.refundStatus(), r.refundAmountMad()),
                    r.transferredTo() == null ? null : TicketToken.ticketNo(r.transferredTo()),
                    r.transferredFrom() == null ? null : TicketToken.ticketNo(r.transferredFrom()));
        }

        @Override
        public String toString() {
            return "RowDto[" + id + "]";
        }
    }

    private final TicketAdminService tickets;

    OrgTicketController(TicketAdminService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/org/events/{id}/tickets/search")
    List<RowDto> search(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                        @RequestBody SearchBody body) {
        org.only(StaffRole.ADMIN, StaffRole.OPS, StaffRole.FINANCE);
        return tickets.search(org.organizerId(), id, body.q()).stream().map(RowDto::of).toList();
    }

    @PostMapping("/org/tickets/{id}/block")
    RowDto block(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                 @RequestBody ReasonBody body) {
        org.adminOrOps();
        return RowDto.of(tickets.block(org.organizerId(), org.userId(), id, body.reason()));
    }

    @PostMapping("/org/tickets/{id}/unblock")
    RowDto unblock(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                   @RequestBody ReasonBody body) {
        org.adminOrOps();
        return RowDto.of(tickets.unblock(org.organizerId(), org.userId(), id, body.reason()));
    }

    @PostMapping("/org/tickets/{id}/refund")
    RowDto refund(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                  @RequestBody ReasonBody body) {
        org.only(StaffRole.ADMIN);
        return RowDto.of(tickets.refund(org.organizerId(), org.userId(), id, body.reason()));
    }
}
