package ma.jomhor.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Event.PriceCategory;

/**
 * Persistence port for events and price_categories. Every method is scoped by organizer_id; a row of
 * another organizer (or a price of another event than the one given) is treated as absent.
 */
public interface Events {

    /** Null filters are ignored. Ordered by starts_at. */
    List<Event.Summary> list(UUID org, Event.Status status, Instant from);

    /** Next events.id, taken before the insert so K_e can be bound to it. */
    int nextId();

    /** Inserts DRAFT {@code eventId} (from nextId) with its encrypted K_e. Empty if the venue is not the organizer's. */
    Optional<Event> insert(UUID org, int eventId, UUID venueId, Event.Type type, String title, Instant startsAt,
                           Instant doorsOpenAt, Event.ReentryPolicy reentryPolicy, int perAccountLimit,
                           String metadata, byte[] eventKeyEnc);

    Optional<Event> find(UUID org, int eventId);

    /** events.event_key_enc: the only read of it, for EventService.withEventKey. */
    Optional<byte[]> eventKeyEnc(UUID org, int eventId);

    /** Like find, and locks the event row until the end of the transaction. */
    Optional<Event> lock(UUID org, int eventId);

    /** Writes the mutable columns of {@code event} (title, times, status, cutoff, rules, metadata). */
    Event update(UUID org, Event event);

    List<PriceCategory> prices(UUID org, int eventId);

    Optional<PriceCategory> findPrice(UUID org, int eventId, UUID priceId);

    /** Capacity of the zone if it belongs to this venue. */
    Optional<Integer> zoneCapacity(UUID org, UUID venueId, int zoneId);

    /** Σ quota of the event's categories on the zone, the category {@code except} left out (may be null). */
    long quotaOnZone(UUID org, int eventId, int zoneId, UUID except);

    boolean hasQuota(UUID org, int eventId);

    PriceCategory insertPrice(UUID org, int eventId, int zoneId, String name, long priceMad, int quota);

    /** Null arguments are unchanged. Empty if the new quota is below sold (checked in the same statement). */
    Optional<PriceCategory> updatePrice(UUID org, int eventId, UUID priceId, String name, Long priceMad, Integer quota);

    /** False if sold > 0. A category referenced by order items fails on its FK. */
    boolean deletePriceIfUnsold(UUID org, int eventId, UUID priceId);
}
