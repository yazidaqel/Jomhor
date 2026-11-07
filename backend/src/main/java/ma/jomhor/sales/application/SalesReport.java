package ma.jomhor.sales.application;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.EventService.PricedZone;
import ma.jomhor.catalog.domain.Event.PriceCategory;
import ma.jomhor.sales.application.Orders.RefundRequired;
import ma.jomhor.sales.application.Orders.SalesLine;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.tickets.application.TicketService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /org/events/{id}/sales (docs/04-api.md §Organizer): a read model over this module's orders, the event's
 * zones and quota from catalog's EventService, REFUNDED tickets from TicketService, and payments' refunds total
 * (PendingPayments) and refund flags (audit_log). Money in centimes. {@code paid} and {@code revenueMad} are gross:
 * they keep counting refunded tickets (net = revenueMad - refundedMad).
 */
@Service
public class SalesReport {

    /** Every sales_channel of the schema, reported with zeros when unused. */
    static final List<String> CHANNELS = List.of("APP", "WEB", "POS");

    /** Every payment_method of the schema, in its order, reported with zeros when unused. */
    static final List<String> METHODS = Arrays.stream(Order.PaymentMethod.values()).map(Enum::name).toList();

    public record EventSales(int eventId, long quota, long paid, long held, long awaitingCash, long refunded,
                             long revenueMad, long refundedMad, List<ZoneSales> zones, List<ChannelSales> channels,
                             List<MethodSales> methods, List<RefundRequired> refundsRequired) {}

    public record ZoneSales(int zoneId, String code, String name, long quota, long paid, long held, long awaitingCash,
                            long refunded, long revenueMad) {}

    public record ChannelSales(String channel, long paid, long revenueMad) {}

    public record MethodSales(String method, long paid, long revenueMad) {}

    private final EventService events;
    private final Orders orders;
    private final TicketService tickets;
    private final PendingPayments payments;

    SalesReport(EventService events, Orders orders, TicketService tickets, PendingPayments payments) {
        this.events = events;
        this.orders = orders;
        this.tickets = tickets;
        this.payments = payments;
    }

    /** One snapshot for every read (REPEATABLE READ), so quota, sales and refunds agree. Unknown event → 404. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventSales of(UUID org, int eventId) {
        List<PricedZone> priced = events.pricedZones(org, eventId);
        List<SalesLine> lines = orders.salesLines(org, eventId);
        Map<Integer, Long> refunded = tickets.refundedByZone(org, eventId);
        List<ZoneSales> zones = priced.stream().map(z -> {
            Set<UUID> ids = z.prices().stream().map(PriceCategory::id).collect(Collectors.toSet());
            List<SalesLine> l = lines.stream().filter(x -> ids.contains(x.priceCategoryId())).toList();
            return new ZoneSales(z.zoneId(), z.code(), z.name(), z.prices().stream().mapToLong(PriceCategory::quota).sum(),
                    sum(l, SalesLine::paid), sum(l, SalesLine::held), sum(l, SalesLine::awaitingCash),
                    refunded.getOrDefault(z.zoneId(), 0L), sum(l, SalesLine::revenueMad));
        }).toList();
        List<ChannelSales> channels = CHANNELS.stream().map(c -> {
            List<SalesLine> l = lines.stream().filter(x -> x.channel().equals(c)).toList();
            return new ChannelSales(c, sum(l, SalesLine::paid), sum(l, SalesLine::revenueMad));
        }).toList();
        List<MethodSales> methods = METHODS.stream().map(m -> {
            List<SalesLine> l = lines.stream().filter(x -> Objects.equals(x.paymentMethod(), m)).toList();
            return new MethodSales(m, sum(l, SalesLine::paid), sum(l, SalesLine::revenueMad));
        }).toList();
        return new EventSales(eventId, zones.stream().mapToLong(ZoneSales::quota).sum(), sum(lines, SalesLine::paid),
                sum(lines, SalesLine::held), sum(lines, SalesLine::awaitingCash),
                refunded.values().stream().mapToLong(Long::longValue).sum(), sum(lines, SalesLine::revenueMad),
                payments.refundedMad(org, eventId), zones, channels, methods, orders.refundsRequired(org, eventId));
    }

    private static <T> long sum(List<T> rows, ToLongFunction<T> f) {
        return rows.stream().mapToLong(f).sum();
    }
}
