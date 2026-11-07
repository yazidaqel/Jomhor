package ma.jomhor.sales.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.sales.application.SalesReport;
import ma.jomhor.sales.application.SalesReport.EventSales;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /org/events/{id}/sales (docs/04-api.md §Organizer). ADMIN, OPS, FINANCE (a GET); GATE, SUPERVISOR and CASHIER
 * get 403 from the org interceptor. Responses are the DTO records below, built field by field. Money in centimes.
 */
@RestController
class OrgSalesController {

    record ZoneDto(int zoneId, String code, String name, long quota, long paid, long held, long awaitingCash,
                   long refunded, long revenueMad) {}

    record ChannelDto(String channel, long paid, long revenueMad) {}

    record MethodDto(String method, long paid, long revenueMad) {}

    record RefundDto(UUID paymentId, UUID orderId, long amountMad, String reason, Instant at) {}

    record SalesDto(int eventId, long quota, long paid, long held, long awaitingCash, long refunded, long revenueMad,
                    long refundedMad, List<ZoneDto> zones, List<ChannelDto> channels, List<MethodDto> methods,
                    List<RefundDto> refundsRequired) {
        static SalesDto of(EventSales s) {
            return new SalesDto(s.eventId(), s.quota(), s.paid(), s.held(), s.awaitingCash(), s.refunded(),
                    s.revenueMad(), s.refundedMad(),
                    s.zones().stream().map(z -> new ZoneDto(z.zoneId(), z.code(), z.name(), z.quota(), z.paid(),
                            z.held(), z.awaitingCash(), z.refunded(), z.revenueMad())).toList(),
                    s.channels().stream().map(c -> new ChannelDto(c.channel(), c.paid(), c.revenueMad())).toList(),
                    s.methods().stream().map(m -> new MethodDto(m.method(), m.paid(), m.revenueMad())).toList(),
                    s.refundsRequired().stream().map(r -> new RefundDto(r.paymentId(), r.orderId(), r.amountMad(),
                            r.reason(), r.at())).toList());
        }
    }

    private final SalesReport report;

    OrgSalesController(SalesReport report) {
        this.report = report;
    }

    @GetMapping("/org/events/{id}/sales")
    SalesDto sales(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return SalesDto.of(report.of(org.organizerId(), id));
    }
}
