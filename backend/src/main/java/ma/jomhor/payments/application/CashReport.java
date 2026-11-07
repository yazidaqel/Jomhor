package ma.jomhor.payments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.VenueService;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.payments.application.Payments.CashPayment;
import ma.jomhor.payments.domain.PosSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /org/finance/cash and .csv (docs/04-api.md §Organizer, plan Q50): cash collected per local day × collector, and
 * the till sessions opened in the range with their gap. Money in centimes.
 */
@Service
public class CashReport {

    /** The longest range, in local days of the report's zone, both ends included. */
    static final int MAX_DAYS = 92;
    /** collector_name of a club till (provider POS). */
    static final String TILL = "Guichet";

    /** One row of {@code days}: collectorName is "Guichet" for POS, the network's display name otherwise. */
    public record Day(LocalDate day, String collector, String collectorName, String method, long payments,
                      long amountMad) {}

    /** declaredMad and varianceMad cover closed sessions only; openSessions counts the open ones of the range. */
    public record Totals(long collectedMad, long declaredMad, long varianceMad, long openSessions) {}

    /** {@code from} and {@code to}: the local days asked for, both included. */
    public record Report(LocalDate from, LocalDate to, String timezone, List<Day> days, List<PosSession> sessions,
                         Totals totals) {}

    private final Payments payments;
    private final PosSessions sessions;
    /** provider → display name, from every cash network adapter present (enabled or not: past payments keep it). */
    private final Map<String, String> networkNames;

    CashReport(Payments payments, PosSessions sessions, List<CashNetwork> networks) {
        this.payments = payments;
        this.sessions = sessions;
        this.networkNames = networks.stream().collect(Collectors.toMap(CashNetwork::provider, CashNetwork::displayName,
                (a, b) -> a));
    }

    /**
     * {@code from} and {@code to}: local dates (YYYY-MM-DD, both included) in {@code timezone} (null → the default zone;
     * else an IANA region id), from ≤ to, at most 92 days. Else 422 VALIDATION_FAILED on the field. The bounds are
     * [start of from, start of to + 1) by this server's tzdata, so a client's stale zone rules can't shift the days.
     * One snapshot for both reads.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report of(UUID org, String from, String to, String timezone) {
        String tz = timezone == null || timezone.isBlank() ? Venue.DEFAULT_TIMEZONE : VenueService.checkTimezone(timezone);
        ZoneId zone = ZoneId.of(tz);
        LocalDate first = date("from", from);
        LocalDate last = date("to", to);
        if (last.isBefore(first)) {
            throw invalid("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS) {
            throw invalid("to", "at most " + MAX_DAYS + " days after from");
        }
        Instant start = first.atStartOfDay(zone).toInstant();
        Instant end = last.plusDays(1).atStartOfDay(zone).toInstant();
        // ponytail: grouped in memory, one row per cash payment of <= 92 days (a few 10,000s for a club); a SQL
        // group by on a precomputed local day if one organizer's range ever reaches millions of rows
        List<Day> days = payments.cashPayments(org, start, end).stream()
                .collect(Collectors.groupingBy(p -> new Key(p.completedAt().atZone(zone).toLocalDate(), p.collector(),
                        p.method().name()), TreeMap::new, Collectors.toList()))
                .entrySet().stream().map(e -> day(e.getKey(), e.getValue())).toList();
        List<PosSession> opened = sessions.openedIn(org, start, end);
        List<PosSession> closed = opened.stream().filter(s -> s.closedAt() != null).toList();
        Totals totals = new Totals(days.stream().mapToLong(Day::amountMad).sum(),
                closed.stream().mapToLong(PosSession::declaredMad).sum(),
                closed.stream().mapToLong(PosSession::varianceMad).sum(), opened.size() - closed.size());
        return new Report(first, last, tz, days, opened, totals);
    }

    /** Centimes as DH with a comma and two decimals, by integer maths: 125050 → 1250,50; -1000 → -10,00. */
    public static String dh(long centimes) {
        long abs = Math.abs(centimes);
        return (centimes < 0 ? "-" : "") + abs / 100 + "," + (abs % 100 < 10 ? "0" : "") + abs % 100;
    }

    /** A {@code days} row's identity, in the 04 order: day, then collector, then method. */
    private record Key(LocalDate day, String collector, String method) implements Comparable<Key> {
        private static final Comparator<Key> ORDER = Comparator.comparing(Key::day).thenComparing(Key::collector)
                .thenComparing(Key::method);

        @Override
        public int compareTo(Key o) {
            return ORDER.compare(this, o);
        }
    }

    private Day day(Key k, List<CashPayment> rows) {
        String name = k.collector().equals("POS") ? TILL : networkNames.getOrDefault(k.collector(), k.collector());
        return new Day(k.day(), k.collector(), name, k.method(), rows.size(),
                rows.stream().mapToLong(CashPayment::amountMad).sum());
    }

    private static LocalDate date(String field, String value) {
        if (value == null) {
            throw invalid(field, "required");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(field, "must be a date YYYY-MM-DD");
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request",
                Map.of("fields", Map.of(field, message)));
    }
}
