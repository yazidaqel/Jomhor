package ma.jomhor.notifications.domain;

import java.util.UUID;

/**
 * One PENDING fan_messages row claimed for sending (docs/04-api.md §Notifications). {@code orderId} is set for
 * SPLIT_INVITE, PAY_CODE and ORDER_PAID, {@code ticketId} for TICKET_RECEIVED. {@code attempts} = sends tried so far;
 * the retry policy is {@link Delivery#backoff}.
 */
public record FanMessage(UUID id, UUID organizerId, UUID userId, Channel channel, Kind kind, UUID orderId,
                         UUID ticketId, int attempts) {

    public enum Channel { PUSH, WHATSAPP }

    public enum Kind { TICKET_RECEIVED, SPLIT_INVITE, PAY_CODE, ORDER_PAID, CAMPAIGN }

    /** last_error of a message that can't be sent and is not retried. */
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String NO_DEVICE = "NO_DEVICE";
    public static final String STALE = "STALE";
}
