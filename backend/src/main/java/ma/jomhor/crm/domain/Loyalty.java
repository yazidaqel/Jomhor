package ma.jomhor.crm.domain;

import java.util.List;
import ma.jomhor.tenancy.domain.LoyaltyTier;

/** Loyalty on read (plan lot-4 Q58): the unit is events attended; tiers are valid (ascending from 0). */
public final class Loyalty {

    private Loyalty() {}

    /** The highest tier reached with {@code attended} events. */
    public static LoyaltyTier tier(List<LoyaltyTier> tiers, long attended) {
        return tiers.reversed().stream().filter(t -> t.minEvents() <= attended).findFirst().orElse(tiers.getFirst());
    }

    /** The first tier not reached yet; null at the top. */
    public static LoyaltyTier next(List<LoyaltyTier> tiers, long attended) {
        return tiers.stream().filter(t -> t.minEvents() > attended).findFirst().orElse(null);
    }

    /** The name in {@code locale}, falling back to fr. */
    public static String name(LoyaltyTier tier, String locale) {
        return tier.name().getOrDefault(locale, tier.name().get("fr"));
    }
}
