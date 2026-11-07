package ma.jomhor.sales.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An order with its items. Money in centimes. */
public record Order(UUID id, UUID organizerId, int eventId, UUID userId, Channel channel, Status status,
                    long totalMad, Instant reservedUntil, Instant paidAt, List<Item> items) {

    /** docs/06-glossary.md §Order statuses. */
    public enum Status { RESERVED, AWAITING_CASH, PAID, EXPIRED, CANCELLED, REFUNDED }

    // ponytail: POS (sales_channel in the schema) is the back-office counter, added with its lot.
    public enum Channel { APP, WEB }

    /** payment_method: the method of an order's payment attempts (docs/06-glossary.md payment methods). */
    public enum PaymentMethod { CARD, CASH_AGENCY, WALLET, POS_CASH, FREE }

    /**
     * unit_price_mad is the snapshot taken at reservation; holders one per place (plan Q15), empty on Lot 0 orders
     * (their tickets go to the buyer).
     */
    public record Item(UUID priceCategoryId, int qty, long unitPriceMad, List<Holder> holders) {}

    /** Who holds one place: a normalized name (docs/04-api.md §Holder names) and the holding account. No phone. */
    public record Holder(String name, UUID userId) {}

    public Order withItems(List<Item> newItems) {
        return new Order(id, organizerId, eventId, userId, channel, status, totalMad, reservedUntil, paidAt, newItems);
    }

    public int places() {
        return items.stream().mapToInt(Item::qty).sum();
    }
}
