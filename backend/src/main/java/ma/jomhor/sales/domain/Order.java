package ma.jomhor.sales.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order with its items. Money in centimes. {@code groupOrderId}: set on a split-pay member order, the buyer's
 * order (docs/04-api.md §Split pay); null otherwise. {@code eventId} null: a product order (04 §Products), one item
 * with a {@code productId}; {@code passId}: its pass (a purchase, or with an event a pass ticket order), else null.
 * {@code promoCode}: the code the buyer's order used (docs/04-api.md §Promo codes), else null; {@code totalMad} is after
 * its {@code discountMad} (0 without a code), the items keep their list prices.
 */
public record Order(UUID id, UUID organizerId, Integer eventId, UUID userId, Channel channel, Status status,
                    long totalMad, Instant reservedUntil, Instant paidAt, UUID groupOrderId, UUID passId,
                    String promoCode, long discountMad, List<Item> items) {

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
     * (their tickets go to the buyer). An event place has {@code priceCategoryId}, a product item {@code productId}
     * (qty 1, one holder), never both.
     */
    public record Item(UUID priceCategoryId, UUID productId, int qty, long unitPriceMad, List<Holder> holders) {}

    /** Who holds one place: a normalized name (docs/04-api.md §Holder names) and the holding account. No phone. */
    public record Holder(String name, UUID userId) {}

    public Order withItems(List<Item> newItems) {
        return new Order(id, organizerId, eventId, userId, channel, status, totalMad, reservedUntil, paidAt, groupOrderId,
                passId, promoCode, discountMad, newItems);
    }

    /** A product order (event_id null, 04 §Products): its one item's product. */
    public UUID productId() {
        return eventId == null ? items.getFirst().productId() : null;
    }

    public int places() {
        return items.stream().mapToInt(Item::qty).sum();
    }
}
