package ma.jomhor.crm.domain;

import java.time.Instant;
import java.util.UUID;
import ma.jomhor.notifications.domain.FanMessage.Channel;

/**
 * A campaigns row (docs/03-schema.sql) as GET /org/campaigns lists it: {@code createdBy} is the staff row, and
 * {@code recipients} / {@code sent} / {@code failed} count its fan_messages rows (all, SENT, FAILED).
 */
public record Campaign(UUID id, UUID organizerId, Channel channel, Audience audience, Integer eventId, String title,
                       String body, Status status, Instant scheduledAt, Instant startedAt, Instant finishedAt,
                       UUID createdBy, String createdByName, long recipients, long sent, long failed) {

    public enum Audience { MEMBERS, EVENT_HOLDERS }

    public enum Status { DRAFT, SCHEDULED, SENDING, SENT, CANCELLED }
}
