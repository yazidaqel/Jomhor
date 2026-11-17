package ma.jomhor.sales.promos.domain;

/** docs/04-api.md §Promo codes money rules, integer centimes only. */
public final class Discount {

    /** At least 1 DH stays to pay on an order with a code. */
    public static final long MIN_LEFT_MAD = 100;

    private Discount() {}

    /**
     * Centimes off an order: PERCENT ⌊eligible × percent ÷ 100⌋, AMOUNT min(amount, eligible), then at most
     * {@code gross} − 100. 0 = the code can't apply (NOT_APPLICABLE). {@code eligible}: Σ unit_price_mad of the buyer's
     * own places in the code's scope; {@code gross}: Σ qty × unit_price_mad of the buyer's order.
     */
    public static long of(PromoCode code, long eligible, long gross) {
        long off = code.kind() == PromoCode.Kind.PERCENT ? eligible * code.percent() / 100
                : Math.min(code.amountMad(), eligible);
        return Math.max(0, Math.min(off, gross - MIN_LEFT_MAD));
    }

    /**
     * What refunding one place of an order pays back: ⌊unit × total ÷ (total + discount)⌋, the unit price without a
     * discount, 0 on a FREE order. The refunds of one order never sum above its total.
     */
    // ponytail: u × t must fit a long (about 9.2e18 centimes², e.g. a 1,000,000 DH place on a 1e9 DH order); past it
    // multiplyExact throws (a 500, never a wrong amount). BigInteger or Math.multiplyHigh if orders ever get that big.
    public static long refund(long unitPriceMad, long totalMad, long discountMad) {
        long gross = totalMad + discountMad;
        return gross == 0 ? 0 : Math.multiplyExact(unitPriceMad, totalMad) / gross;
    }
}
