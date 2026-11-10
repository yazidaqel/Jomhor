package ma.jomhor.tenancy.domain;

import java.util.List;
import java.util.Map;

/**
 * One tier of organizers.settings.loyalty.tiers (docs/03-schema.sql): {@code name} by locale (fr, ar, en; fr always
 * there once valid).
 */
public record LoyaltyTier(String code, Map<String, String> name, int minEvents) {

    /** 03: absent or malformed settings mean these. */
    public static final List<LoyaltyTier> DEFAULTS = List.of(
            new LoyaltyTier("T1", Map.of("fr", "Membre", "ar", "منخرط", "en", "Member"), 0),
            new LoyaltyTier("T2", Map.of("fr", "Fidèle", "ar", "وفي", "en", "Regular"), 3),
            new LoyaltyTier("T3", Map.of("fr", "Pilier", "ar", "ركيزة", "en", "Stalwart"), 10));

    /**
     * A non-empty list of tiers with a code, a fr name and {@code min_events}, strictly ascending from 0. A
     * {@code minEvents} below 0 stands for a missing or non-integer value.
     */
    public static boolean valid(List<LoyaltyTier> tiers) {
        if (tiers.isEmpty() || tiers.getFirst().minEvents() != 0) {
            return false;
        }
        for (int i = 0; i < tiers.size(); i++) {
            LoyaltyTier t = tiers.get(i);
            if (t.code() == null || t.code().isEmpty() || t.name().getOrDefault("fr", "").isEmpty()
                    || (i > 0 && t.minEvents() <= tiers.get(i - 1).minEvents())) {
                return false;
            }
        }
        return true;
    }
}
