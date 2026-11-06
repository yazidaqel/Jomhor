package ma.jomhor.sales.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.EventService.PricedZone;
import ma.jomhor.catalog.domain.Event.PriceCategory;
import ma.jomhor.sales.application.Orders.RefundRequired;
import ma.jomhor.sales.application.Orders.SalesLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /org/events/{id}/sales (docs/04-api.md §Organizer): a read model over this module's orders, the event's
 * zones and quota from catalog's EventService, and payments' refund flags in audit_log. Money in centimes.
 */
@Service
public class SalesReport {

    /** Every sales_channel of the schema, reported with zeros when unused. */
    static final List<String> CHANNELS = List.of("APP", "WEB", "POS");

    public record EventSales(int eventId, long quota, long paid, long held, long revenueMad, List<ZoneSales> zones,
                             List<ChannelSales> channels, List<RefundRequired> refundsRequired) {}

    public record ZoneSales(int zoneId, String code, String name, long quota, long paid, long held, long revenueMad) {}

    public record ChannelSales(String channel, long paid, long revenueMad) {}

    private final EventService events;
    private final Orders orders;

    SalesReport(EventService events, Orders orders) {
        this.events = events;
        this.orders = orders;
    }

    /** One snapshot for every read (REPEATABLE READ), so quota, sales and refunds agree. Unknown event → 404. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventSales of(UUID org, int eventId) {
        List<PricedZone> priced = events.pricedZones(org, eventId);
        List<SalesLine> lines = orders.salesLines(org, eventId);
        List<ZoneSales> zones = priced.stream().map(z -> {
            Set<UUID> ids = z.prices().stream().map(PriceCategory::id).collect(Collectors.toSet());
            List<SalesLine> l = lines.stream().filter(x -> ids.contains(x.priceCategoryId())).toList();
            return new ZoneSales(z.zoneId(), z.code(), z.name(), z.prices().stream().mapToLong(PriceCategory::quota).sum(),
                    sum(l, SalesLine::paid), sum(l, SalesLine::held), sum(l, SalesLine::revenueMad));
        }).toList();
        List<ChannelSales> channels = CHANNELS.stream().map(c -> {
            List<SalesLine> l = lines.stream().filter(x -> x.channel().equals(c)).toList();
            return new ChannelSales(c, sum(l, SalesLine::paid), sum(l, SalesLine::revenueMad));
        }).toList();
        return new EventSales(eventId, zones.stream().mapToLong(ZoneSales::quota).sum(), sum(lines, SalesLine::paid),
                sum(lines, SalesLine::held), sum(lines, SalesLine::revenueMad), zones, channels,
                orders.refundsRequired(org, eventId));
    }

    private static <T> long sum(List<T> rows, ToLongFunction<T> f) {
        return rows.stream().mapToLong(f).sum();
    }
}
