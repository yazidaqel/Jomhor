package ma.jomhor.sales.products.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.products.domain.Pass;

/** Persistence port for passes. Every organizer method is scoped by organizer_id. */
public interface Passes {

    /**
     * Serialises what can make a pass of the series live for the account (a purchase, a late success's re-take) until
     * the end of the transaction.
     */
    void lockAccount(UUID userId, UUID seriesId);

    /** The account's pass of the series that isn't CANCELLED (at most one, the unique index). */
    Optional<Pass> live(UUID org, UUID seriesId, UUID userId);

    Optional<Pass> find(UUID org, UUID passId);

    /** A PENDING pass of the PASS product's series, held by {@code userId}. */
    Pass insert(UUID org, UUID productId, UUID seriesId, UUID userId, String holderName);

    /** {@code from} → ACTIVE; false if the pass wasn't {@code from} (nothing written). */
    boolean activate(UUID org, UUID passId, Pass.Status from);

    /**
     * The PENDING passes bought by these just-expired product orders → CANCELLED, and their products' sold − 1 each, in
     * the caller's transaction. The products are locked first, in id order, then the passes.
     */
    void cancelExpired(Collection<UUID> orderIds);

    /** A fan's pass with its purchase order while PENDING (else null). */
    record Owned(Pass pass, UUID orderId) {}

    /** The account's passes that aren't CANCELLED, newest first. */
    List<Owned> owned(UUID userId);

    /** Event id → the holder's ticket from the pass's ticket orders (CANCELLED ones left out). */
    Map<Integer, UUID> tickets(UUID org, UUID passId, UUID holderId);
}
