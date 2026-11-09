package ma.jomhor.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ma.jomhor.crm.domain.Loyalty;
import ma.jomhor.tenancy.domain.LoyaltyTier;
import org.junit.jupiter.api.Test;

class LoyaltyTest {

    private static final List<LoyaltyTier> TIERS = LoyaltyTier.DEFAULTS; // 0 · 3 · 10

    @Test
    void tierBoundaries() {
        assertThat(Loyalty.tier(TIERS, 0).code()).isEqualTo("T1");
        assertThat(Loyalty.tier(TIERS, 2).code()).isEqualTo("T1");
        assertThat(Loyalty.tier(TIERS, 3).code()).isEqualTo("T2");
        assertThat(Loyalty.tier(TIERS, 9).code()).isEqualTo("T2");
        assertThat(Loyalty.tier(TIERS, 10).code()).isEqualTo("T3");
        assertThat(Loyalty.next(TIERS, 0).code()).isEqualTo("T2");
        assertThat(Loyalty.next(TIERS, 2).code()).isEqualTo("T2");
        assertThat(Loyalty.next(TIERS, 3).code()).isEqualTo("T3");
        assertThat(Loyalty.next(TIERS, 9).code()).isEqualTo("T3");
        assertThat(Loyalty.next(TIERS, 10)).isNull();
    }

    @Test
    void nameInLocaleElseFr() {
        LoyaltyTier t = new LoyaltyTier("A", Map.of("fr", "Membre", "ar", "منخرط"), 0);
        assertThat(Loyalty.name(t, "ar")).isEqualTo("منخرط");
        assertThat(Loyalty.name(t, "fr")).isEqualTo("Membre");
        assertThat(Loyalty.name(t, "en")).isEqualTo("Membre");
    }

    @Test
    void validTiers() {
        assertThat(LoyaltyTier.valid(TIERS)).isTrue();
        assertThat(LoyaltyTier.valid(List.of())).isFalse();
        assertThat(LoyaltyTier.valid(List.of(new LoyaltyTier("A", Map.of("fr", "A"), 1)))).isFalse(); // not from 0
        assertThat(LoyaltyTier.valid(List.of(new LoyaltyTier("A", Map.of("fr", "A"), 0),
                new LoyaltyTier("B", Map.of("fr", "B"), 0)))).isFalse(); // not strictly ascending
        assertThat(LoyaltyTier.valid(List.of(new LoyaltyTier("A", Map.of("en", "A"), 0)))).isFalse(); // no fr
        assertThat(LoyaltyTier.valid(List.of(new LoyaltyTier(null, Map.of("fr", "A"), 0)))).isFalse(); // no code
    }
}
