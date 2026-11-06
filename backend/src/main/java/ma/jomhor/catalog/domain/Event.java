package ma.jomhor.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An event (match, concert, conference day). Never carries organizer_id (it comes from OrgContext) nor
 * event_key_enc: K_e is written once at create and is not read back through this record.
 * {@code metadata} is the jsonb object as JSON text.
 */
public record Event(int id, UUID venueId, UUID seriesId, Type eventType, String title, Instant startsAt,
                    Instant doorsOpenAt, Status status, Instant refundCutoffAt, ReentryPolicy reentryPolicy,
                    int perAccountLimit, String metadata, Instant createdAt) {

    public Event withStatus(Status newStatus, Instant newRefundCutoffAt) {
        return new Event(id, venueId, seriesId, eventType, title, startsAt, doorsOpenAt, newStatus, newRefundCutoffAt,
                reentryPolicy, perAccountLimit, metadata, createdAt);
    }

    public enum Type { FOOTBALL_MATCH, CONCERT, FESTIVAL_DAY, CONFERENCE, OTHER }

    public enum Status { DRAFT, ON_SALE, GATES_OPEN, CLOSED }

    public enum ReentryPolicy { NONE, ONE_EXIT }

    /** Row of GET /org/events: quota and sold are sums over the event's price categories. */
    public record Summary(int id, String title, Type eventType, Instant startsAt, Status status, UUID venueId,
                          String venueName, long quota, long sold) {}

    public record PriceCategory(UUID id, int zoneId, String name, long priceMad, int quota, int sold) {}
}
