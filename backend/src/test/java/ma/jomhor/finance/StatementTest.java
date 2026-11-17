package ma.jomhor.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import ma.jomhor.finance.domain.Payout;
import ma.jomhor.finance.domain.Statement;
import org.junit.jupiter.api.Test;

/**
 * BE-70: the statement figures (docs/04-api.md §Finance Statement, plan lot-5 Q223, Q226, Q232), pure. Property-style
 * over random payments with a fixed seed, plus the demo's exact figures and the rounding edges.
 */
class StatementTest {

    private static final int VAT = 2000;
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private static Statement.Payment pay(Integer event, UUID product, String method, String collector, long amount) {
        return new Statement.Payment(event, product, method, collector, amount);
    }

    @Test
    void theDemoThursdayThenATillOnlyFridayCarriesItsFees() {
        List<Statement.Payment> thursday = List.of(pay(1, null, "CARD", "FAKE", 200_000), pay(2, null, "CARD", "FAKE",
                100_000), pay(null, UUID.randomUUID(), "CARD", "FAKE", 40_000), pay(null, MEMBERSHIP, "WALLET", "FAKE",
                15_000), pay(1, null, "CASH_AGENCY", "FAKE_CASH", 45_000), pay(1, null, "POS_CASH", "POS", 100_000));
        Statement thu = Statement.build(thursday, 500, VAT, 0);
        assertThat(thu.collectedMad()).isEqualTo(400_000);
        assertThat(thu.tillMad()).isEqualTo(100_000);
        assertThat(thu.feeHtMad()).isEqualTo(25_000);
        assertThat(thu.feeVatMad()).isEqualTo(5_000);
        assertThat(thu.payoutMad()).isEqualTo(370_000);
        assertThat(thu.status()).isEqualTo("DUE");
        assertThat(Statement.carry(thu.collectedMad(), thu.feeHtMad(), thu.feeVatMad(), thu.carriedInMad())).isZero();

        Statement fri = Statement.build(List.of(pay(1, null, "POS_CASH", "POS", 80_000)), 500, VAT, 0);
        assertThat(fri.feeHtMad()).isEqualTo(4_000);
        assertThat(fri.feeVatMad()).isEqualTo(800);
        assertThat(fri.payoutMad()).isZero();
        assertThat(fri.status()).isEqualTo("NONE");
        long carried = Statement.carry(fri.collectedMad(), fri.feeHtMad(), fri.feeVatMad(), fri.carriedInMad());
        assertThat(carried).isEqualTo(-4_800);

        Statement next = Statement.build(List.of(pay(1, null, "CARD", "FAKE", 25_000)), 500, VAT, carried);
        assertThat(next.carriedInMad()).isEqualTo(-4_800);
        assertThat(next.payoutMad()).isEqualTo(25_000 - 1_250 - 250 - 4_800);
    }

    @Test
    void vatIsHalfUpOnTheFee() {
        // fee_ht = rate 10 000 bp of the base, so the base is the fee
        assertThat(vat(25_000)).isEqualTo(5_000);
        assertThat(vat(24_999)).isEqualTo(5_000); // 4 999,8 → 5 000
        assertThat(vat(24_997)).isEqualTo(4_999); // 4 999,4 → 4 999
        assertThat(vat(2)).isZero(); // 0,4 → 0
        assertThat(vat(3)).isEqualTo(1); // 0,6 → 1
        assertThat(vat(0)).isZero();
    }

    private static long vat(long feeHt) {
        return Statement.build(List.of(pay(1, null, "CARD", "FAKE", feeHt)), 10_000, VAT, 0).feeVatMad();
    }

    @Test
    void theFeeIsFlooredOnCollectedPlusTill() {
        Statement s = Statement.build(List.of(pay(1, null, "CARD", "FAKE", 199), pay(1, null, "POS_CASH", "POS", 0)),
                500, VAT, 0);
        assertThat(s.feeHtMad()).isEqualTo(9); // 9,95 → 9
    }

    @Test
    void randomDaysKeepEveryInvariant() {
        Random random = new Random(20261126);
        String[][] kinds = {{"CARD", "FAKE"}, {"CARD", "PAYZONE"}, {"WALLET", "FAKE"}, {"CASH_AGENCY", "FAKE_CASH"},
                {"CASH_AGENCY", "POS"}, {"POS_CASH", "POS"}};
        UUID[] products = {UUID.randomUUID(), UUID.randomUUID()};
        long carried = 0;
        for (int day = 0; day < 2_000; day++) {
            List<Statement.Payment> payments = new ArrayList<>();
            int n = 1 + random.nextInt(30);
            for (int i = 0; i < n; i++) {
                String[] k = kinds[random.nextInt(kinds.length)];
                boolean product = random.nextInt(4) == 0;
                payments.add(pay(product ? null : 1 + random.nextInt(3), product ? products[random.nextInt(2)] : null,
                        k[0], k[1], random.nextInt(4) == 0 ? 0 : random.nextLong(5_000_000)));
            }
            int rate = random.nextInt(10_001);
            Statement s = Statement.build(payments, rate, VAT, carried);

            long collected = payments.stream().filter(p -> !p.collector().equals("POS"))
                    .mapToLong(Statement.Payment::amountMad).sum();
            long till = payments.stream().filter(p -> p.collector().equals("POS"))
                    .mapToLong(Statement.Payment::amountMad).sum();
            assertThat(s.collectedMad()).isEqualTo(collected);
            assertThat(s.tillMad()).isEqualTo(till);
            assertThat(s.lines().stream().mapToLong(Statement.Line::amountMad).sum()).isEqualTo(collected + till);
            assertThat(s.lines().stream().mapToInt(Statement.Line::payments).sum()).isEqualTo(n);
            assertThat(s.lines()).allSatisfy(l -> assertThat(l.eventId() == null).isNotEqualTo(l.productId() == null));
            assertThat(s.lines().stream().map(l -> List.of(String.valueOf(l.eventId()), String.valueOf(l.productId()),
                    l.method(), l.collector())).distinct().count()).isEqualTo(s.lines().size());
            // floor: fee_ht × 10 000 ≤ base × rate < (fee_ht + 1) × 10 000
            long base = collected + till;
            assertThat(s.feeHtMad() * 10_000).isLessThanOrEqualTo(base * rate);
            assertThat((s.feeHtMad() + 1) * 10_000).isGreaterThan(base * rate);
            // half-up: |fee_vat × 10 000 − fee_ht × vat| ≤ 5 000, and exactly 5 000 only when rounding up
            long diff = s.feeVatMad() * 10_000 - s.feeHtMad() * VAT;
            assertThat(diff).isBetween(-4_999L, 5_000L);
            assertThat(s.carriedInMad()).isEqualTo(carried).isLessThanOrEqualTo(0);
            long x = collected - s.feeHtMad() - s.feeVatMad() + carried;
            assertThat(s.balanceMad()).isEqualTo(x);
            assertThat(s.payoutMad()).isEqualTo(Math.max(0, x)).isGreaterThanOrEqualTo(0);
            assertThat(s.status().equals("NONE")).isEqualTo(s.payoutMad() == 0);
            carried = Statement.carry(s.collectedMad(), s.feeHtMad(), s.feeVatMad(), s.carriedInMad());
            assertThat(carried).isEqualTo(Math.min(0, x));
        }
    }

    @Test
    void aPositiveCarryOrARateOutOfRangeIsRefused() {
        assertThatThrownBy(() -> Statement.build(List.of(), 500, VAT, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Statement.build(List.of(), 10_001, VAT, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Statement.build(List.of(), -1, VAT, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payoutIsDueTwoDaysLaterAndAWeekendMovesToMonday() {
        assertThat(Payout.dueOn(LocalDate.parse("2026-11-26"), 2)).isEqualTo("2026-11-30"); // Thu → Sat → Mon
        assertThat(Payout.dueOn(LocalDate.parse("2026-11-27"), 2)).isEqualTo("2026-11-30"); // Fri → Sun → Mon
        assertThat(Payout.dueOn(LocalDate.parse("2026-11-30"), 2)).isEqualTo("2026-12-02"); // Mon → Wed
        assertThat(Payout.dueOn(LocalDate.parse("2026-11-25"), 2)).isEqualTo("2026-11-27"); // Wed → Fri
        assertThat(Payout.dueOn(LocalDate.parse("2026-11-28"), 2)).isEqualTo("2026-11-30"); // Sat → Mon
    }

    @Test
    void overdueIsDueAndPastItsDate() {
        LocalDate today = LocalDate.parse("2026-12-01");
        assertThat(Payout.overdue("DUE", LocalDate.parse("2026-11-30"), today)).isTrue();
        assertThat(Payout.overdue("DUE", today, today)).isFalse();
        assertThat(Payout.overdue("PAID", LocalDate.parse("2026-11-30"), today)).isFalse();
        assertThat(Payout.overdue("NONE", LocalDate.parse("2026-11-30"), today)).isFalse();
    }
}
