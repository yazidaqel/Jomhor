package ma.jomhor.sales.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An order with its items. Money in centimes. */
public record Order(UUID id, UUID organizerId, int eventId, Status status, long totalMad, Instant reservedUntil,
                    Instant paidAt, List<Item> items) {

    /** docs/06-glossary.md §Order statuses. */
    public enum Status { RESERVED, AWAITING_CASH, PAID, EXPIRED, CANCELLED, REFUNDED }

    // ponytail: POS (sales_channel in the schema) is the back-office counter, added with its lot.
    public enum Channel { APP, WEB }

    /** unit_price_mad is the snapshot taken at reservation. */
    public record Item(UUID priceCategoryId, int qty, long unitPriceMad) {}
}
