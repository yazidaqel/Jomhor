package ma.jomhor.payments.domain;

import java.util.UUID;
import ma.jomhor.sales.domain.Order.PaymentMethod;

/**
 * A payment attempt on an order. {@code id} is the reference the gateway sees; {@code payCode} is set on CASH_AGENCY
 * attempts only. Money in centimes.
 */
public record Payment(UUID id, UUID organizerId, UUID orderId, PaymentMethod method, String provider, long amountMad,
                      Status status, String payCode) {

    public enum Status { PENDING, SUCCEEDED, FAILED }
}
