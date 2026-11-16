package ma.jomhor.sales.products.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.sales.products.domain.Product;
import org.junit.jupiter.api.Test;

/**
 * Plan lot-5 BE-57, fixed clock: docs/04-api.md §Passes Installments offers the N-plan only when its last due date
 * (now + (N − 1) × 30 d) is on or before the series' ends_on, to the end of that day in the zone venue's zone.
 */
class InstallmentPlansTest {

    private static final String ZONE = "Africa/Casablanca";
    private static final LocalDate ENDS_ON = LocalDate.parse("2027-06-30");
    private static final Duration INTERVAL = Duration.ofDays(30);

    // plans reads only the interval: the other collaborators stay out.
    private final ProductService service = new ProductService(null, null, null, null, null, null, null, null, null,
            INTERVAL);

    private final Product pass = new Product(UUID.randomUUID(), UUID.randomUUID(), Product.Kind.PASS, "Abonnement",
            100_001, true, Instant.parse("2027-06-01T00:00:00Z"), 10, 0, UUID.randomUUID(), 1, null, 4, null);

    private final ProductService.PassScope scope = new ProductService.PassScope(
            new Event.Series(UUID.randomUUID(), "Saison", LocalDate.parse("2026-08-01"), ENDS_ON),
            new Event.ZoneAt(1, "EST", "Est", UUID.randomUUID(), "Stade", "Casablanca", ZONE));

    /** {@code now} such that the 4th due date falls at {@code lastDue}. */
    private int offered(Instant lastDue) {
        return service.plans(pass, scope, lastDue.minus(INTERVAL.multipliedBy(3))).getLast().installments();
    }

    @Test
    void theLastDueDateAt2359OnEndsOnIsOfferedAMinuteLaterIsNot() {
        Instant at2359 = ENDS_ON.atTime(LocalTime.of(23, 59)).atZone(ZoneId.of(ZONE)).toInstant();
        assertThat(offered(at2359)).isEqualTo(4);
        assertThat(offered(at2359.plus(Duration.ofMinutes(1)))).isEqualTo(1);
    }
}
