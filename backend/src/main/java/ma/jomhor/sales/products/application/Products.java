package ma.jomhor.sales.products.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.products.domain.Product;

/**
 * Persistence port for products. Every organizer method is scoped by organizer_id; another organizer's row is absent.
 */
public interface Products {

    /** {@code kind} null = every kind. By kind (PASS, MEMBERSHIP), then name. */
    List<Product> list(UUID org, Product.Kind kind);

    Optional<Product> find(UUID org, UUID productId);

    /** Like find, and locks the row until the end of the transaction. */
    Optional<Product> lock(UUID org, UUID productId);

    /** Inserts {@code p} (its id, organizer, sold and created_at are ignored). */
    Product insert(UUID org, Product p);

    /**
     * Writes name, price, quota, on_sale, sales_close_at, valid_until and max_installments of {@code p}. Empty if the
     * quota is below sold (checked in the same statement).
     */
    Optional<Product> update(UUID org, Product p);

    /** Public (GET /fan/products): the organizer's products on sale, by kind then name. */
    List<Product> onSale(UUID org, Product.Kind kind);

    /** Public (GET /fan/products/{id}): the product by id alone, if on sale. */
    Optional<Product> onSale(UUID productId);

    /**
     * A PASS product's sold + 1 if it stays within its quota, in one row update (the row stays locked until the end of
     * the transaction). False: the quota is reached, nothing written.
     */
    boolean take(UUID org, UUID productId);

    /** Undoes {@link #take} in the same transaction: a PASS product's sold − 1. */
    void giveBack(UUID org, UUID productId);

    /** Whether a pass ticket order (pass_id and event_id set) exists for the event. */
    boolean hasPassTickets(UUID org, int eventId);

    /** Σ total_mad of the product's PAID product orders (04 GET /org/products/{id} {@code revenue_mad}). */
    long revenue(UUID org, UUID productId);
}
