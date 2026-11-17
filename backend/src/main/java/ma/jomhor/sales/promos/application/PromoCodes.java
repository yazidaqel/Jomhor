package ma.jomhor.sales.promos.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.promos.domain.PromoCode;

/** Persistence port for promo codes. Every method is scoped by organizer_id and event; another's row is absent. */
public interface PromoCodes {

    /**
     * A code with its figures, read from orders: {@code held} = its RESERVED/AWAITING_CASH orders still held,
     * {@code paid} = its PAID orders, {@code discountMad} = Σ discount_mad of those PAID orders.
     */
    record Listed(PromoCode promo, long held, long paid, long discountMad) {}

    /** The event's codes by code. */
    List<Listed> list(UUID org, int eventId);

    Optional<Listed> find(UUID org, int eventId, UUID promoId);

    /** Inserts {@code p} (id and created_at ignored). Empty: the code is already used in this event, nothing written. */
    Optional<PromoCode> insert(UUID org, PromoCode p);

    /** Writes active, max_uses and per_account_max; a null argument keeps that column as it is. */
    void update(UUID org, int eventId, UUID promoId, Boolean active, Integer maxUses, Integer perAccountMax);

    /** The event's active code with this text, its row locked until the end of the transaction. */
    Optional<PromoCode> lockActive(UUID org, int eventId, String code);

    /** Its uses: orders RESERVED, AWAITING_CASH or PAID with it; {@code buyer} not null = that buyer's orders only. */
    long uses(UUID org, UUID promoId, UUID buyer);
}
