package ma.jomhor.notifications.domain;

import java.util.UUID;

/**
 * One PENDING fan_messages row claimed for sending (docs/04-api.md §Notifications). {@code orderId} is set for
 * SPLIT_INVITE, PAY_CODE and ORDER_PAID, {@code ticketId} for TICKET_RECEIVED, {@code campaignId} for CAMPAIGN. {@code attempts} = sends tried so far;
 * the retry policy is {@link Delivery#backoff}.
 */
public record FanMessage(UUID id, UUID organizerId, UUID userId, Channel channel, Kind kind, UUID campaignId,
                         UUID orderId, UUID ticketId, int attempts) {

    public enum Channel { PUSH, WHATSAPP }

    public enum Kind { TICKET_RECEIVED, SPLIT_INVITE, PAY_CODE, ORDER_PAID, CAMPAIGN }

    /** last_error of a message that can't be sent and is not retried. */
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String NO_DEVICE = "NO_DEVICE";
    public static final String STALE = "STALE";
    /** A MEMBERS campaign row whose recipient left the club or turned the channel's opt-in off (04 §Campaigns). */
    public static final String OPTED_OUT = "OPTED_OUT";
}
