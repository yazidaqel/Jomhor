package ma.jomhor.payments.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.payments.application.RefundService;
import ma.jomhor.payments.domain.Refund;
import ma.jomhor.tenancy.domain.OrgContext;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tickets.domain.TicketToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Organizer refunds: GET /org/refunds (ADMIN, OPS, FINANCE), POST /org/refunds/{id}/done (ADMIN,
 * FINANCE). Money in centimes; {@code reason} is the text typed on refund.
 */
@RestController
class OrgRefundController {

    record DoneBody(String reference) {}

    record StaffRef(UUID id, String name) {}

    record RefundDto(UUID id, int eventId, UUID ticketId, String ticketNo, UUID orderId, long amountMad, String method,
                     String reason, Refund.Status status, StaffRef requestedBy, Instant requestedAt, StaffRef doneBy,
                     Instant doneAt, String reference) {
        static RefundDto of(Refund r) {
            return new RefundDto(r.id(), r.eventId(), r.ticketId(), TicketToken.ticketNo(r.ticketId()), r.orderId(),
                    r.amountMad(), r.method(), r.reason(), r.status(), new StaffRef(r.requestedBy(), r.requestedByName()),
                    r.requestedAt(), r.doneBy() == null ? null : new StaffRef(r.doneBy(), r.doneByName()), r.doneAt(),
                    r.reference());
        }
    }

    private final RefundService refunds;

    OrgRefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @GetMapping("/org/refunds")
    List<RefundDto> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org,
                         @RequestParam(required = false) Refund.Status status,
                         @RequestParam(name = "event_id", required = false) Integer eventId) {
        org.only(StaffRole.ADMIN, StaffRole.OPS, StaffRole.FINANCE);
        return refunds.list(org.organizerId(), status, eventId).stream().map(RefundDto::of).toList();
    }

    @PostMapping("/org/refunds/{id}/done")
    RefundDto done(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                   @RequestBody DoneBody body) {
        org.only(StaffRole.ADMIN, StaffRole.FINANCE);
        return RefundDto.of(refunds.done(org.organizerId(), org.userId(), id, body.reference()));
    }
}
