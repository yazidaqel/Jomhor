package ma.jomhor.sales.application;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** What the expiry needs from payments (plan Q8). Implemented by the payments module: sales never reads its tables. */
public interface PendingPayments {

    /** Orders with a PENDING payment created less than {@code age} ago (database clock). */
    Set<UUID> ordersWithPendingYoungerThan(Duration age);
}
