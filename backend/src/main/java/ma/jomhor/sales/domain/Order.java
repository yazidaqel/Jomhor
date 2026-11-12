package ma.jomhor.sales.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order with its items. Money in centimes. {@code groupOrderId}: set on a split-pay member order, the buyer's
 * order (docs/04-api.md §Split pay); null otherwise.
 */
public record Order(UUID id, UUID organizerId, int eventId, UUID userId, Channel channel, Status status,
                    long totalMad, Instant reservedUntil, Instant paidAt, UUID groupOrderId, List<Item> items) {

    /** docs/06-glossary.md §Order statuses. */
    public enum Status { RESERVED, AWAITING_CASH, PAID, EXPIRED, CANCELLED, REFUNDED }

    /** POS = a club till (Lot 3): created PAID, no buyer (user_id null). Never accepted from /fan/orders. */
    public enum Channel { APP, WEB, POS }

    /** A split-pay order's side (04 `group.role`, `group_role`): the buyer's order, or a member order. */
    public enum GroupRole { INITIATOR, MEMBER }

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
        return new Order(id, organizerId, eventId, userId, channel, status, totalMad, reservedUntil, paidAt, groupOrderId,
                newItems);
    }

    public int places() {
        return items.stream().mapToInt(Item::qty).sum();
    }
}
