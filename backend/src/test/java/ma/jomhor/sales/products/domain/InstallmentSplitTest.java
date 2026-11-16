package ma.jomhor.sales.products.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan lot-5 BE-57: docs/04-api.md §Passes Installments amounts, a pure function. */
class InstallmentSplitTest {

    /** Every price 1-100,000 centimes and n 1-10 it can split into amounts above 0 (price ≥ n). */
    @Test
    void theAmountsAddUpToThePriceAndTheFirstCarriesTheRest() {
        for (long price = 1; price <= 100_000; price++) {
            for (int n = 1; n <= Math.min(10, price); n++) {
                List<Long> amounts = Installments.split(price, n);
                long base = price / n;
                long sum = 0;
                boolean ok = amounts.size() == n && amounts.getFirst() == price - base * (n - 1);
                for (int k = 0; k < amounts.size(); k++) {
                    long a = amounts.get(k);
                    sum += a;
                    ok &= a > 0 && a <= amounts.getFirst() && (k == 0 || a == base);
                }
                if (!ok || sum != price) {
                    fail("split(%d, %d) = %s".formatted(price, n, amounts));
                }
            }
        }
        assertThat(Installments.split(100_001, 4)).containsExactly(25_001L, 25_000L, 25_000L, 25_000L);
    }

    @Test
    void aPriceBelowNHasNoSplit() {
        assertThatThrownBy(() -> Installments.split(3, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Installments.split(100, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
