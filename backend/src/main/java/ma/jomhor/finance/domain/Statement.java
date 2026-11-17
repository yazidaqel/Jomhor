package ma.jomhor.finance.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A daily settlement statement's figures (docs/04-api.md §Finance Statement), pure and integer-only: every amount in
 * centimes, rates in basis points, floor and half-up by integer division. The one place these formulas live.
 *
 * @param balanceMad x = collected − fee_ht − fee_vat + carried_in: the next statement's carried_in is least(0, x)
 */
public record Statement(long collectedMad, long tillMad, int feeRateBp, long feeHtMad, long feeVatMad,
                        long carriedInMad, long payoutMad, long balanceMad, List<Line> lines) {

    /** payments.provider of a club till: the only till test. */
    public static final String TILL = "POS";

    /** One SUCCEEDED payment of the day: its order's event, else its item's product (exactly one is set). */
    public record Payment(Integer eventId, UUID productId, String method, String collector, long amountMad) {}

    /** The day's payments of one subject × method × collector. */
    public record Line(Integer eventId, UUID productId, String method, String collector, int payments,
                       long amountMad) {}

    /** DUE when there is something to pay out, else NONE. */
    public String status() {
        return payoutMad > 0 ? "DUE" : "NONE";
    }

    /**
     * {@code carriedInMad}: least(0, x of the previous statement), 0 for the first; never positive.
     */
    // ponytail: a refund_required payment (a late success, money owed back) is in the fee base like any SUCCEEDED
    // payment (plan lot-5 §13.7); take it out here if that ever matters.
    public static Statement build(List<Payment> payments, int feeRateBp, int vatRateBp, long carriedInMad) {
        if (feeRateBp < 0 || feeRateBp > 10_000 || vatRateBp < 0 || vatRateBp > 10_000 || carriedInMad > 0) {
            throw new IllegalArgumentException("rate out of range or positive carried balance");
        }
        long collected = 0;
        long till = 0;
        Map<Key, long[]> grouped = new LinkedHashMap<>();
        for (Payment p : payments) {
            if (p.collector().equals(TILL)) {
                till = Math.addExact(till, p.amountMad());
            } else {
                collected = Math.addExact(collected, p.amountMad());
            }
            long[] sum = grouped.computeIfAbsent(new Key(p.eventId(), p.productId(), p.method(), p.collector()),
                    k -> new long[2]);
            sum[0]++;
            sum[1] = Math.addExact(sum[1], p.amountMad());
        }
        long feeHt = Math.multiplyExact(Math.addExact(collected, till), feeRateBp) / 10_000;
        long feeVat = (Math.multiplyExact(feeHt, vatRateBp) + 5_000) / 10_000;
        long balance = collected - feeHt - feeVat + carriedInMad;
        List<Line> lines = grouped.entrySet().stream().sorted(Map.Entry.comparingByKey(Key.ORDER))
                .map(e -> new Line(e.getKey().eventId(), e.getKey().productId(), e.getKey().method(),
                        e.getKey().collector(), Math.toIntExact(e.getValue()[0]), e.getValue()[1]))
                .toList();
        return new Statement(collected, till, feeRateBp, feeHt, feeVat, carriedInMad, Math.max(0, balance), balance,
                lines);
    }

    /** The next statement's carried_in: least(0, x) of a stored statement's figures. */
    public static long carry(long collectedMad, long feeHtMad, long feeVatMad, long carriedInMad) {
        return Math.min(0, collectedMad - feeHtMad - feeVatMad + carriedInMad);
    }

    private record Key(Integer eventId, UUID productId, String method, String collector) {
        static final Comparator<Key> ORDER = Comparator.comparing(Key::eventId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Key::productId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Key::method).thenComparing(Key::collector);
    }
}
