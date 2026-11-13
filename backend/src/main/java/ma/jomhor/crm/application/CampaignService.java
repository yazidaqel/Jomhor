package ma.jomhor.crm.application;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.crm.domain.Campaign;
import ma.jomhor.crm.domain.Campaign.Audience;
import ma.jomhor.crm.domain.Member;
import ma.jomhor.notifications.application.FanMessageQueue;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.notifications.domain.FanMessage.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * /org/campaigns (docs/04-api.md §Organizer, §Campaigns; plan lot-4 Q62, Q101, Q131-Q136), the campaign job and what
 * the outbox sends for a campaign row. {@code org} is always OrgContext.organizerId(); another organizer's campaign
 * is 404. Every write locks the campaign row first, so a write racing the job's snapshot sees its status after it.
 * Depends on FanMessageQueue only, never on FanMessageService (which reads this): no bean cycle.
 */
@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);
    static final int LIST_LIMIT = 100;
    static final int INBOX_LIMIT = 30;
    static final int TITLE_MAX = 60;
    static final int BODY_MAX = 500;
    static final Duration MAX_AHEAD = Duration.ofDays(30);

    /** GET /org/campaigns/{id}: the list row, its body and the recipients if it were sent now. */
    public record Detail(Campaign campaign, long audienceEstimate) {}

    /** POST and PATCH fields; null = absent (POST: required ones are checked, PATCH: unchanged). */
    public record Input(Channel channel, Audience audience, Integer eventId, String title, String body) {}

    /** What a campaign row sends; {@code optedOut}: a MEMBERS recipient who left or turned the opt-in off. */
    public record Message(Audience audience, Integer eventId, String title, String body, boolean optedOut) {}

    private final Campaigns campaigns;
    private final Memberships memberships;
    private final EventService events;
    private final FanMessageQueue queue;
    private final ObjectProvider<PushSender> pushSender;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    CampaignService(Campaigns campaigns, Memberships memberships, EventService events, FanMessageQueue queue,
                    ObjectProvider<PushSender> pushSender, AuditLog audit, PlatformTransactionManager transactions) {
        this.campaigns = campaigns;
        this.memberships = memberships;
        this.events = events;
        this.queue = queue;
        this.pushSender = pushSender;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    public List<Campaign> list(UUID org) {
        return campaigns.list(org, LIST_LIMIT);
    }

    public Detail get(UUID org, UUID id) {
        Campaign c = campaigns.get(org, id).orElseThrow(CampaignService::notFound);
        return new Detail(c, campaigns.audienceSize(c));
    }

    /** POST: every field checked, a DRAFT row; audit campaign.create {channel, audience} (Q132). */
    @Transactional
    public Detail create(UUID org, UUID userId, Input in) {
        Campaigns.Draft d = validate(org, in.channel(), in.audience(), in.eventId(), in.title(), in.body());
        UUID id = campaigns.insert(org, userId, d);
        audit.staff(org, userId, "campaign.create", id.toString(),
                "{\"channel\": \"%s\", \"audience\": \"%s\"}".formatted(d.channel(), d.audience()));
        return get(org, id);
    }

    /** PATCH: null = unchanged (event_id can't be cleared, Q101); the POST rules apply to the result. DRAFT only. */
    @Transactional
    public Detail update(UUID org, UUID id, Input in) {
        lockIn(org, id, Campaign.Status.DRAFT);
        Campaign c = campaigns.get(org, id).orElseThrow();
        Campaigns.Draft d = validate(org, or(in.channel(), c.channel()), or(in.audience(), c.audience()),
                or(in.eventId(), c.eventId()), or(in.title(), c.title()), or(in.body(), c.body()));
        campaigns.update(org, id, d);
        return get(org, id);
    }

    /**
     * DRAFT → SCHEDULED. Past and too far ahead are judged on this JVM's clock: absent or past = now, stored as the
     * database's now(); over 30 days ahead → 422.
     */
    @Transactional
    public Detail send(UUID org, UUID userId, UUID id, Instant scheduledAt) {
        lockIn(org, id, Campaign.Status.DRAFT);
        Instant now = Instant.now();
        if (scheduledAt != null && scheduledAt.isAfter(now.plus(MAX_AHEAD))) {
            throw invalid(Map.of("scheduled_at", "at most 30 days ahead"), null);
        }
        campaigns.schedule(org, id, scheduledAt == null || !scheduledAt.isAfter(now) ? null : scheduledAt);
        Detail d = get(org, id);
        audit.staff(org, userId, "campaign.schedule", id.toString(),
                "{\"scheduled_at\": \"%s\"}".formatted(d.campaign().scheduledAt()));
        return d;
    }

    /** SCHEDULED → CANCELLED; audit campaign.cancel without a payload. */
    @Transactional
    public Detail cancel(UUID org, UUID userId, UUID id) {
        lockIn(org, id, Campaign.Status.SCHEDULED);
        campaigns.cancel(org, id);
        audit.staff(org, userId, "campaign.cancel", id.toString());
        return get(org, id);
    }

    /**
     * One job run over every organizer: each due campaign in its own transaction (SENDING, then the audience snapshot
     * into the outbox), then every SENDING campaign with no PENDING row left → SENT (an empty snapshot included).
     * A campaign whose snapshot fails rolls back to SCHEDULED, is skipped for the rest of the run (so it cannot block
     * the others) and is retried next run. Ids, counts and exception classes only in the log.
     */
    public int runDue() {
        int started = 0;
        Set<UUID> failed = new HashSet<>();
        while (true) {
            AtomicReference<UUID> claimed = new AtomicReference<>();
            try {
                if (!Boolean.TRUE.equals(tx.execute(s -> snapshotNext(failed, claimed)))) {
                    break;
                }
                started++;
            } catch (RuntimeException e) {
                log.warn("Campaign {} snapshot failed: {}", claimed.get(), e.getClass().getSimpleName());
                if (claimed.get() == null) {
                    break; // the claim itself failed: nothing to skip, next run tries again
                }
                failed.add(claimed.get());
            }
        }
        List<UUID> finished = tx.execute(s -> campaigns.finishDrained());
        finished.forEach(id -> log.info("Campaign {} sent", id));
        return started;
    }

    private boolean snapshotNext(Set<UUID> skip, AtomicReference<UUID> claimed) {
        Optional<Campaign> due = campaigns.claimDue(skip);
        if (due.isEmpty()) {
            return false;
        }
        Campaign c = due.get();
        claimed.set(c.id());
        campaigns.start(c.organizerId(), c.id());
        int rows = queue.campaign(c.organizerId(), c.id(), c.channel(), campaigns.audience(c));
        log.info("Campaign {} of organizer {}: {} recipients queued", c.id(), c.organizerId(), rows);
        return true;
    }

    /** For the outbox, read in the row's organizer: MEMBERS recipients are re-checked (left, opt-in off). */
    public Message message(UUID org, UUID campaignId, UUID userId) {
        Campaigns.Draft c = campaigns.outbox(org, campaignId).orElseThrow();
        boolean optedOut = c.audience() == Audience.MEMBERS && memberships.find(org, userId)
                .filter(Member::live)
                .map(m -> c.channel() == Channel.PUSH ? m.marketingPush() : m.marketingWhatsapp())
                .map(optIn -> !optIn).orElse(true);
        return new Message(c.audience(), c.eventId(), c.title(), c.body(), optedOut);
    }

    /** GET /fan/me/inbox: the caller's SENT PUSH campaign rows of every organizer, newest first, at most 30. */
    public List<Campaigns.InboxMessage> inbox(UUID userId) {
        return campaigns.inbox(userId, INBOX_LIMIT);
    }

    // ---------- rules ----------

    private void lockIn(UUID org, UUID id, Campaign.Status expected) {
        Campaign.Status status = campaigns.lock(org, id).orElseThrow(CampaignService::notFound);
        if (status != expected) {
            throw new ApiException("CAMPAIGN_STATUS", HttpStatus.CONFLICT, "Campaign is " + status,
                    Map.of("status", status.name()));
        }
    }

    /** The POST rules (04): every field error at once; a PUSH campaign with no provider → NOT_CONFIGURED. */
    private Campaigns.Draft validate(UUID org, Channel channel, Audience audience, Integer eventId, String title,
                                     String body) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (channel == null) {
            fields.put("channel", "PUSH or WHATSAPP");
        }
        if (audience == null) {
            fields.put("audience", "MEMBERS or EVENT_HOLDERS");
        }
        if (eventId == null ? audience == Audience.EVENT_HOLDERS : !eventUsable(org, eventId)) {
            fields.put("event_id", eventId == null ? "required for EVENT_HOLDERS" : "an event of this organizer, not DRAFT");
        }
        String t = text(title, TITLE_MAX);
        if (t == null) {
            fields.put("title", "1 to " + TITLE_MAX + " characters");
        }
        String b = text(body, BODY_MAX);
        if (b == null) {
            fields.put("body", "1 to " + BODY_MAX + " characters");
        }
        boolean notConfigured = channel == Channel.PUSH && pushSender.getIfAvailable() == null;
        if (notConfigured) {
            fields.put("channel", "no push provider configured");
        }
        if (!fields.isEmpty()) {
            throw invalid(fields, notConfigured ? "NOT_CONFIGURED" : null);
        }
        return new Campaigns.Draft(channel, audience, eventId, t, b);
    }

    private boolean eventUsable(UUID org, int eventId) {
        try {
            return events.get(org, eventId).status() != Event.Status.DRAFT;
        } catch (ApiException e) { // another organizer's or unknown: 404 there, 422 here
            return false;
        }
    }

    /** Trimmed, 1 to {@code max} code points (as §Holder names), any script; null if not. */
    private static String text(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        int length = s.codePointCount(0, s.length());
        return length >= 1 && length <= max ? s : null;
    }

    private static <T> T or(T value, T current) {
        return value == null ? current : value;
    }

    private static ApiException invalid(Map<String, Object> fields, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", fields);
        if (reason != null) {
            details.put("reason", reason);
        }
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body", details);
    }

    private static ApiException notFound() {
        return ApiException.notFound("Campaign not found");
    }
}
