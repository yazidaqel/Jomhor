package ma.jomhor.sales.products.domain;

import java.util.ArrayList;
import java.util.List;

/** docs/04-api.md §Passes Installments: how a price splits into payments. */
public final class Installments {

    public static final int MAX = 10;

    private Installments() {}

    /**
     * {@code n} integer amounts in centimes, Σ = {@code price} exactly: {@code base = price ÷ n} (floor), the first
     * {@code price − base × (n − 1)}, the others {@code base}. {@code price ≥ n} keeps every amount above 0.
     */
    public static List<Long> split(long price, int n) {
        if (n < 1 || price < n) {
            throw new IllegalArgumentException("price " + price + " can't be split in " + n);
        }
        long base = price / n;
        List<Long> amounts = new ArrayList<>(n);
        amounts.add(price - base * (n - 1));
        for (int k = 1; k < n; k++) {
            amounts.add(base);
        }
        return amounts;
    }
}
