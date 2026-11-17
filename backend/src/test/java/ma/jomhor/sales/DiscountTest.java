package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import ma.jomhor.sales.promos.domain.Discount;
import ma.jomhor.sales.promos.domain.PromoCode;
import org.junit.jupiter.api.Test;

/**
 * BE-65: docs/04-api.md §Promo codes Discount and Refunds, property style over random orders (fixed seed, so a failure
 * replays): integer centimes only, 0 &lt; discount ≤ gross − 100 or NOT_APPLICABLE (0), PERCENT = ⌊e × p ÷ 100⌋,
 * AMOUNT = min(a, e); the refunds of one order's tickets never sum above its total.
 */
class DiscountTest {

    private static PromoCode percent(int p) {
        return new PromoCode(null, null, 1, null, "CODE", PromoCode.Kind.PERCENT, p, null, null, 1, true, null);
    }

    private static PromoCode amount(long a) {
        return new PromoCode(null, null, 1, null, "CODE", PromoCode.Kind.AMOUNT, null, a, null, 1, true, null);
    }

    @Test
    void examplesFromTheContract() {
        assertThat(Discount.of(percent(20), 30_000, 30_000)).isEqualTo(6_000);
        assertThat(Discount.of(amount(5_000), 3_000, 3_000)).isEqualTo(2_900); // 1 DH stays to pay
        assertThat(Discount.of(amount(5_000), 30_000, 30_000)).isEqualTo(5_000);
        assertThat(Discount.of(percent(99), 101, 101)).isEqualTo(1); // ⌊99.99⌋ = 99, capped at gross − 100
        assertThat(Discount.of(percent(1), 99, 10_000)).isZero(); // ⌊0.99⌋ = 0
        assertThat(Discount.of(percent(50), 0, 10_000)).isZero(); // nothing in scope
        assertThat(Discount.of(amount(500), 100, 100)).isZero(); // a 1 DH order can't be discounted
        assertThat(Discount.refund(15_000, 24_000, 6_000)).isEqualTo(12_000);
        assertThat(Discount.refund(5_000, 10_000, 0)).isEqualTo(5_000); // no code: the unit price
        assertThat(Discount.refund(0, 0, 0)).isZero(); // a FREE place
    }

    @Test
    void discountsAreIntegersInRangeOrNotApplicable() {
        Random random = new Random(20261117);
        for (int n = 0; n < 20_000; n++) {
            List<long[]> lines = lines(random);
            long gross = lines.stream().mapToLong(l -> l[0] * l[1]).sum();
            // In scope: a random subset of the lines.
            long eligible = lines.stream().filter(l -> random.nextBoolean()).mapToLong(l -> l[0] * l[1]).sum();
            int p = 1 + random.nextInt(99);
            long a = 1 + random.nextInt(200_000);
            long byPercent = Discount.of(percent(p), eligible, gross);
            long byAmount = Discount.of(amount(a), eligible, gross);
            for (long d : new long[] {byPercent, byAmount}) {
                assertThat(d).isBetween(0L, Math.max(0, gross - Discount.MIN_LEFT_MAD));
            }
            assertThat(byPercent).isEqualTo(Math.max(0, Math.min(eligible * p / 100, gross - 100)));
            assertThat(byAmount).isEqualTo(Math.max(0, Math.min(Math.min(a, eligible), gross - 100)));
        }
    }

    @Test
    void refundsOfAnOrderNeverSumAboveItsTotal() {
        Random random = new Random(1117);
        for (int n = 0; n < 20_000; n++) {
            List<long[]> lines = lines(random);
            long gross = lines.stream().mapToLong(l -> l[0] * l[1]).sum();
            long discount = Discount.of(percent(1 + random.nextInt(99)), gross, gross);
            long total = gross - discount;
            long refunded = 0;
            for (long[] l : lines) {
                long each = Discount.refund(l[0], total, discount);
                assertThat(each).isBetween(0L, l[0]);
                refunded += l[1] * each;
            }
            assertThat(refunded).isLessThanOrEqualTo(total);
        }
    }

    /** 1 to 3 lines of {unit price 0-100,000 centimes, qty 1-10}. */
    private static List<long[]> lines(Random random) {
        List<long[]> lines = new ArrayList<>();
        for (int i = 0, k = 1 + random.nextInt(3); i < k; i++) {
            lines.add(new long[] {random.nextInt(100_001), 1 + random.nextInt(10)});
        }
        return lines;
    }
}
