package ma.jomhor.payments.domain;

import java.util.UUID;

/** A payment attempt on an order. {@code id} is the reference the gateway sees. Money in centimes. */
public record Payment(UUID id, UUID organizerId, UUID orderId, String provider, long amountMad, Status status) {

    public enum Status { PENDING, SUCCEEDED, FAILED }
}
