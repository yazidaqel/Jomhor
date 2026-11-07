package ma.jomhor.sales.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.tenancy.domain.CashPoint;

/** The `cash` block of 04 GET /fan/orders/{id}. Implemented by the payments module. */
public interface CashInstructions {

    /** {@code payCode} = the 6 stored symbols; {@code networks} = display names of the enabled cash networks. */
    record Cash(String payCode, long amountMad, List<CashPoint> points, List<String> networks) {}

    /** The order's PENDING cash code and where to pay it; empty if it has none. */
    Optional<Cash> pending(UUID org, UUID orderId);
}
