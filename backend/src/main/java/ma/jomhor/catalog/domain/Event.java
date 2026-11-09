package ma.jomhor.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An event (match, concert, conference day). Never carries organizer_id (it comes from OrgContext) nor
 * event_key_enc: K_e is written once at create and is not read back through this record.
 * {@code metadata} is the jsonb object as JSON text. {@code timezone} is read from the venue (read-only here).
 */
public record Event(int id, UUID venueId, String timezone, UUID seriesId, Type eventType, String title,
                    Instant startsAt, Instant doorsOpenAt, Instant saleStartsAt, Status status, Instant refundCutoffAt,
                    ReentryPolicy reentryPolicy, int perAccountLimit, String metadata, Instant createdAt) {

    public Event withStatus(Status newStatus, Instant newRefundCutoffAt) {
        return new Event(id, venueId, timezone, seriesId, eventType, title, startsAt, doorsOpenAt, saleStartsAt, newStatus,
                newRefundCutoffAt, reentryPolicy, perAccountLimit, metadata, createdAt);
    }

    public enum Type { FOOTBALL_MATCH, CONCERT, FESTIVAL_DAY, CONFERENCE, OTHER }

    public enum Status { DRAFT, ON_SALE, GATES_OPEN, CLOSED }

    public enum ReentryPolicy { NONE, ONE_EXIT }

    /** An event and its organizer, for jobs that run across organizers. */
    public record Ref(UUID organizerId, int id) {}

    /** Row of GET /org/events: quota and sold are sums over the event's price categories. */
    public record Summary(int id, String title, Type eventType, Instant startsAt, Status status, UUID venueId,
                          String venueName, String venueTimezone, long quota, long sold) {}

    public record PriceCategory(UUID id, int zoneId, String name, long priceMad, int quota, int sold, String zoneName,
                                String zoneCode) {}

    /**
     * A non-DRAFT event as the public sees it (GET /fan/events*): no key, no quota or sold. {@code onSale},
     * {@code soldOut} and {@code fromPriceMad} come from the list query (database clock); the detail recomputes them from its price rows ({@link #withStock}).
     */
    public record Listing(int id, UUID organizerId, Organizer organizer, Status status, Type eventType, String title,
                          Instant startsAt, Instant doorsOpenAt, String timezone, String venueName, String venueCity,
                          String metadata, Instant saleStartsAt, Instant salesCloseAt, boolean onSale, boolean soldOut,
                          Long fromPriceMad, int perAccountLimit) {

        public Listing withStock(boolean newSoldOut, Long newFromPriceMad) {
            return new Listing(id, organizerId, organizer, status, eventType, title, startsAt, doorsOpenAt, timezone,
                    venueName, venueCity, metadata, saleStartsAt, salesCloseAt, onSale, newSoldOut, newFromPriceMad,
                    perAccountLimit);
        }
    }

    /**
     * The event's organizer as fans see it (docs/04-api.md §Club directory): {@code slug} null = unlisted,
     * {@code crestUrl} already through {@link ma.jomhor.common.Crests}. Its id is {@link Listing#organizerId()}.
     */
    public record Organizer(String slug, String name, String crestUrl) {}

    /** A zone of a public event with the gates serving it (by code, byte-wise) and the event's prices on it. */
    public record ZoneListing(int id, String code, String name, boolean seated, List<GateListing> gates,
                              List<PriceListing> prices) {

        /** The gates' codes, ascending byte-wise. */
        public List<String> gateCodes() {
            return gates.stream().map(GateListing::code).toList();
        }
    }

    /** A gate as fans see it (04 /fan/tickets {@code gates[]}): coordinates both or neither, scale 6. */
    public record GateListing(String code, String name, String directions, BigDecimal latitude, BigDecimal longitude) {}

    /** remaining = quota - sold. */
    public record PriceListing(UUID priceCategoryId, int zoneId, String name, long priceMad, int remaining) {}
}
