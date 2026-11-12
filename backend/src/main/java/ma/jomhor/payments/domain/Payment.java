package ma.jomhor.payments.domain;

import java.util.UUID;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.sales.domain.Order.PaymentMethod;

/**
 * A payment attempt on an order. {@code id} is the reference the gateway sees; {@code payCode} is set on CASH_AGENCY
 * attempts only; {@code posSessionId} on cash taken at a club till; {@code returnChannel} on a CARD/WALLET attempt
 * started with /pay {@code channel} (null = the order's channel). Money in centimes.
 */
public record Payment(UUID id, UUID organizerId, UUID orderId, PaymentMethod method, String provider, long amountMad,
                      Status status, String payCode, UUID posSessionId, Order.Channel returnChannel) {

    public enum Status { PENDING, SUCCEEDED, FAILED }
}
