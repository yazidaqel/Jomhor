package ma.jomhor.crm.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.crm.domain.Campaign;
import ma.jomhor.notifications.domain.FanMessage.Channel;

/** Persistence port of campaigns, their audience and the fan inbox. Scoped by organizer_id, except the job's reads. */
public interface Campaigns {

    /** What a create or a PATCH writes. */
    record Draft(Channel channel, Campaign.Audience audience, Integer eventId, String title, String body) {}

    /** A DRAFT row created by the caller's staff row in this organizer; its id. */
    UUID insert(UUID org, UUID userId, Draft d);

    Optional<Campaign> get(UUID org, UUID id);

    /** The outbox's read, one row by primary key (no staff join, no counts): what a campaign row sends. */
    Optional<Draft> outbox(UUID org, UUID id);

    /** The organizer's {@code limit} newest campaigns. */
    List<Campaign> list(UUID org, int limit);

    /** The row's status, locked ({@code for update}) until the transaction ends; empty if unknown here. */
    Optional<Campaign.Status> lock(UUID org, UUID id);

    void update(UUID org, UUID id, Draft d);

    /** DRAFT → SCHEDULED at {@code at}, or at the database's now() when null. */
    void schedule(UUID org, UUID id, Instant at);

    void cancel(UUID org, UUID id);

    /** How many users the audience holds now. */
    long audienceSize(Campaign c);

    /** The audience now, one id per user (the same SQL as {@link #audienceSize}). */
    List<UUID> audience(Campaign c);

    /**
     * One due SCHEDULED campaign of any organizer but {@code skip}, locked {@code for update skip locked}; empty when
     * none is free.
     */
    Optional<Campaign> claimDue(Collection<UUID> skip);

    /** SCHEDULED → SENDING, started_at = now. */
    void start(UUID org, UUID id);

    /** SENDING campaigns of every organizer with no PENDING row left → SENT, finished_at = now; their ids. */
    List<UUID> finishDrained();

    /** The organizer block of the inbox: {@code crestUrl} already through Crests. */
    record InboxOrganizer(UUID id, String slug, String name, String crestUrl) {}

    record InboxMessage(UUID id, InboxOrganizer organizer, String title, String body, Integer eventId,
                        Instant sentAt) {}

    /** The user's SENT PUSH campaign rows of every organizer, newest first. */
    List<InboxMessage> inbox(UUID userId, int limit);
}
