package ma.jomhor.sales.products.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What an order sells besides event places (docs/04-api.md §Products). PASS: {@code quota}, {@code seriesId} and
 * {@code zoneId} set, {@code validUntil} null; MEMBERSHIP: the other way round ({@code sold} stays 0). Money in
 * centimes. {@code maxInstallments}: PASS, the most payments a fan may choose (1 = in full only; 1 for MEMBERSHIP).
 */
public record Product(UUID id, UUID organizerId, Kind kind, String name, long priceMad, boolean onSale,
                      Instant salesCloseAt, Integer quota, int sold, UUID seriesId, Integer zoneId, Instant validUntil,
                      int maxInstallments, Instant createdAt) {

    public enum Kind { PASS, MEMBERSHIP }

    /** PASS: quota − sold; null for MEMBERSHIP (no cap). */
    public Integer remaining() {
        return quota == null ? null : Math.max(0, quota - sold);
    }
}
