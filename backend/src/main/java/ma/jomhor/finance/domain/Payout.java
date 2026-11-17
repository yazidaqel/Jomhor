package ma.jomhor.finance.domain;

import java.time.LocalDate;

/** When a statement's payout is due (docs/04-api.md §Finance Statement, plan lot-5 Q226). */
public final class Payout {

    private Payout() {}

    /** {@code day} + {@code payoutDays}; a Saturday or Sunday moves to the next Monday. */
    // ponytail: Moroccan bank holidays are not modelled; a holiday list in settings if one ever delays a payout.
    public static LocalDate dueOn(LocalDate day, int payoutDays) {
        LocalDate due = day.plusDays(payoutDays);
        return switch (due.getDayOfWeek()) {
            case SATURDAY -> due.plusDays(2);
            case SUNDAY -> due.plusDays(1);
            default -> due;
        };
    }

    /** DUE and due_on before {@code today} (local). */
    public static boolean overdue(String status, LocalDate dueOn, LocalDate today) {
        return status.equals("DUE") && dueOn.isBefore(today);
    }
}
