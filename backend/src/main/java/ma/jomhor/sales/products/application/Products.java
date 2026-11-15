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
     * Writes name, price, quota, on_sale, sales_close_at and valid_until of {@code p}. Empty if the quota is below sold
     * (checked in the same statement).
     */
    Optional<Product> update(UUID org, Product p);

    /** Public (GET /fan/products): the organizer's products on sale, by kind then name. */
    List<Product> onSale(UUID org, Product.Kind kind);

    /** Public (GET /fan/products/{id}): the product by id alone, if on sale. */
    Optional<Product> onSale(UUID productId);

    /** Whether a pass ticket order (pass_id and event_id set) exists for the event. */
    boolean hasPassTickets(UUID org, int eventId);
}
