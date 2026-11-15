package ma.jomhor.notifications.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.crm.application.CampaignService;
import ma.jomhor.crm.domain.Campaign;
import ma.jomhor.notifications.domain.Delivery;
import ma.jomhor.notifications.domain.FanMessage;
import ma.jomhor.notifications.domain.FanMessage.Channel;
import ma.jomhor.notifications.domain.MessageCopy;
import ma.jomhor.payments.domain.CashCode;
import ma.jomhor.sales.application.CashInstructions;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.tenancy.application.UserService;
import ma.jomhor.tenancy.domain.User;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tickets.domain.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the fan_messages outbox (docs/04-api.md §Notifications) after commit, at least once, with the
 * ticket_deliveries retry policy ({@link Delivery#backoff}). Everything is rendered at send time, read in the row's
 * organizer: the recipient's phone and locale, the subject (ticket, member order, cash code) and the event. A subject
 * that no longer applies → FAILED STALE; no push provider → FAILED NOT_CONFIGURED; no live token → FAILED
 * NO_DEVICE; a MEMBERS campaign recipient who left or opted out → FAILED OPTED_OUT.
 * Those four are final; any other error backs off. Also registers push tokens (PUT /fan/me/push-token).
 */
@Service
public class FanMessageService {

    private static final Logger log = LoggerFactory.getLogger(FanMessageService.class);
    /** The default job period ({@code *}/5 s), also used when the cron is off ("-": tests call deliverDue). */
    static final Duration DEFAULT_PERIOD = Duration.ofSeconds(5);
    /** WhatsApp template names (plan lot-4 Q63); constants until BE-15 moves template ids to config. */
    static final String SPLIT_INVITE = "split_invite";
    static final String PAY_CODE = "pay_code";
    static final String CLUB_NEWS = "club_news";
    static final String EVENT_INFO = "event_info";

    private final FanMessages messages;
    private final PushTokens tokens;
    private final UserService users;
    private final OrderService orders;
    private final TicketService tickets;
    private final Inventory inventory;
    private final CashInstructions cash;
    private final CampaignService campaigns;
    private final TicketMessenger messenger;
    private final ObjectProvider<PushSender> pushSender;
    private final TransactionTemplate tx;
    /** One savepoint per row: a database error while sending one row can't abort the batch's transaction. */
    private final TransactionTemplate perRow;
    private final int maxPerRun;
    private final String payUrl;

    public FanMessageService(FanMessages messages, PushTokens tokens, UserService users, OrderService orders,
                             TicketService tickets, Inventory inventory, CashInstructions cash,
                             CampaignService campaigns, TicketMessenger messenger,
                             ObjectProvider<PushSender> pushSender,
                             PlatformTransactionManager transactions,
                             @Value("${notifications.send-rate-per-second}") int ratePerSecond,
                             @Value("${jomhor.notifications.message-cron:*/5 * * * * *}") String cron,
                             @Value("${payments.return-url.web}") String payUrl) {
        this.messages = messages;
        this.tokens = tokens;
        this.users = users;
        this.orders = orders;
        this.tickets = tickets;
        this.inventory = inventory;
        this.cash = cash;
        this.campaigns = campaigns;
        this.messenger = messenger;
        this.pushSender = pushSender;
        this.tx = new TransactionTemplate(transactions);
        this.perRow = new TransactionTemplate(transactions);
        this.perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.maxPerRun = (int) Math.max(1, ratePerSecond * period(cron).toSeconds());
        this.payUrl = payUrl;
    }

    /**
     * The gap between two runs of {@code cron}. ponytail: measured once from a fixed instant, so it assumes a regular
     * cron (every N s or min); compute it per run if the job ever gets an irregular one.
     */
    static Duration period(String cron) {
        if (!CronExpression.isValidExpression(cron)) {
            return DEFAULT_PERIOD;
        }
        CronExpression c = CronExpression.parse(cron);
        LocalDateTime first = c.next(LocalDateTime.of(2000, 1, 1, 0, 0));
        return Duration.between(first, c.next(first));
    }

    /** 04 PUT /fan/me/push-token: the session's other tokens go, then the token moves to this user and session. */
    @Transactional
    public void registerPushToken(UUID userId, UUID sessionId, String token, String platform) {
        tokens.register(userId, sessionId, token, platform);
    }

    /**
     * One job run: at most notifications.send-rate-per-second × the job period (plan lot-4 Q127), across every
     * instance (plan lot-5 P105).
     */
    public int deliverDue() {
        return deliverDue(maxPerRun);
    }

    /**
     * One claim of up to {@code max} due rows, sent and committed in one transaction under the claim try-lock: an
     * instance whose run starts meanwhile sends nothing, so N instances on the same tick still send at most {@code max}.
     * ponytail: the lock lasts only as long as the run; an instance whose tick starts after another's run committed
     * (clock skew longer than the run) sends its own {@code max} in the same period. Store the last claimed period in
     * the database if a provider's rate limit ever trips on that.
     */
    public int deliverDue(int max) {
        return tx.execute(s -> messages.tryLockClaim() ? deliverBatch(max) : 0);
    }

    /** What became of one row: sent (error null), or failed with a final or a retried error. */
    private record Outcome(String error, boolean retry) {
        static final Outcome SENT = new Outcome(null, false);

        static Outcome finalError(String code) {
            return new Outcome(code, false);
        }

        static Outcome retry(String code) {
            return new Outcome(code, true);
        }
    }

    /**
     * As TicketDeliveryService: the claimed rows stay locked until the batch commits, so a parallel run skips them.
     * ponytail: provider calls run inside the transaction (a whole run on one connection); claim-then-lease if that
     * hurts.
     */
    private int deliverBatch(int limit) {
        List<FanMessage> due = messages.claimDue(limit);
        for (FanMessage m : due) {
            Outcome out;
            try {
                out = perRow.execute(s -> send(m));
            } catch (TicketMessenger.Failed e) {
                out = Outcome.retry(e.code());
            } catch (RuntimeException e) {
                // The class only: an exception message could carry the phone or a provider response body.
                out = Outcome.retry(e.getClass().getSimpleName());
            }
            if (out == Outcome.SENT) {
                messages.markSent(m.organizerId(), m.id());
            } else {
                log.warn("Fan message {} ({} {}) failed: {}", m.id(), m.kind(), m.channel(), out.error());
                messages.markFailed(m.organizerId(), m.id(), out.error(),
                        out.retry() ? Delivery.backoff(m.attempts() + 1) : null);
            }
        }
        return due.size();
    }

    /** What to send: a push (title, body, path) and/or a WhatsApp template with its parameters. */
    private record Content(String title, String body, String path, String template, List<String> params) {
        static Content push(String title, String body, String path) {
            return new Content(title, body, path, null, null);
        }
    }

    private Outcome send(FanMessage m) {
        PushSender pusher = null;
        if (m.channel() == Channel.PUSH) {
            pusher = pushSender.getIfAvailable();
            if (pusher == null) {
                return Outcome.finalError(FanMessage.NOT_CONFIGURED);
            }
        }
        CampaignService.Message campaign = null;
        if (m.kind() == FanMessage.Kind.CAMPAIGN) {
            campaign = campaigns.message(m.organizerId(), m.campaignId(), m.userId());
            if (campaign.optedOut()) {
                return Outcome.finalError(FanMessage.OPTED_OUT);
            }
        }
        User user = users.get(m.userId());
        String locale = MessageCopy.locale(user.locale());
        Content c = campaign == null ? render(m, locale) : campaign(campaign);
        if (c == null) {
            return Outcome.finalError(FanMessage.STALE);
        }
        if (m.channel() == Channel.WHATSAPP) {
            messenger.send(new TicketMessenger.Template(user.id(), user.phone(), locale, c.template(), c.params()));
            return Outcome.SENT;
        }
        return push(pusher, m.userId(), c);
    }

    /**
     * PUSH to every live token of the user: SENT when one accepts. Each unregistered token is deleted; none accepted
     * and none left → NO_DEVICE (also with no live token); else the last provider error, retried.
     */
    private Outcome push(PushSender pusher, UUID userId, Content c) {
        List<String> live = tokens.live(userId);
        List<String> dead = new ArrayList<>();
        boolean accepted = false;
        String error = null;
        for (String token : live) {
            try {
                pusher.send(new PushSender.Push(token, c.title(), c.body(), c.path()));
                accepted = true;
            } catch (PushSender.Unregistered e) {
                dead.add(token);
            } catch (PushSender.Failed e) {
                error = e.code();
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName();
            }
        }
        dead.forEach(tokens::delete);
        if (accepted) {
            return Outcome.SENT;
        }
        return dead.size() == live.size() ? Outcome.finalError(FanMessage.NO_DEVICE) : Outcome.retry(error);
    }

    /** The message in {@code locale}, read in the row's organizer; null when its subject no longer applies (STALE). */
    private Content render(FanMessage m, String locale) {
        UUID org = m.organizerId();
        return switch (m.kind()) {
            case TICKET_RECEIVED -> {
                Ticket t;
                try {
                    t = tickets.heldValid(m.userId(), m.ticketId()); // not held (404) or not VALID (409): stale
                } catch (ApiException e) {
                    yield null;
                }
                if (!t.organizerId().equals(org)) {
                    yield null;
                }
                yield Content.push(MessageCopy.text(locale, "push.ticket_received.title", Map.of()),
                        MessageCopy.text(locale, "push.ticket_received.body",
                                Map.of("event", event(t.eventId()).title())),
                        "/tickets/" + t.id());
            }
            case SPLIT_INVITE -> splitInvite(org, m.orderId(), locale);
            case PAY_CODE -> payCode(org, m.orderId(), locale);
            case ORDER_PAID -> Content.push(MessageCopy.text(locale, "push.order_paid.title", Map.of()),
                    MessageCopy.text(locale, "push.order_paid.body", Map.of()), "/orders/" + m.orderId());
            case CAMPAIGN -> throw new IllegalStateException("rendered by campaign()");
        };
    }

    /**
     * 04 §Campaigns Content: PUSH = the typed title and body, opening the event or the home screen; WHATSAPP =
     * club_news (MEMBERS) or event_info (EVENT_HOLDERS) with [title, body]. Nothing about the recipient.
     */
    private static Content campaign(CampaignService.Message c) {
        return new Content(c.title(), c.body(), c.eventId() == null ? "/" : "/events/" + c.eventId(),
                c.audience() == Campaign.Audience.MEMBERS ? CLUB_NEWS : EVENT_INFO, List.of(c.title(), c.body()));
    }

    /** Stale unless the member order is RESERVED and still held (database clock). */
    private Content splitInvite(UUID org, UUID memberOrderId, String locale) {
        Order member = orders.inOrg(org, memberOrderId).orElseThrow().order();
        if (member.status() != Order.Status.RESERVED || !orders.holding(org, member.id())) {
            return null;
        }
        Order buyer = orders.inOrg(org, member.groupOrderId()).orElseThrow().order();
        String label = orders.initiatorLabel(buyer);
        Inventory.EventLabel e = event(member.eventId());
        String amount = MessageCopy.amount(member.totalMad());
        String deadline = MessageCopy.deadline(member.reservedUntil(), e.timezone(), locale);
        String title = label == null ? MessageCopy.text(locale, "push.split_invite.title_friend", Map.of())
                : MessageCopy.text(locale, "push.split_invite.title", Map.of("label", label));
        String body = MessageCopy.text(locale, "push.split_invite.body",
                Map.of("event", e.title(), "amount", amount, "deadline", deadline));
        List<String> params = List.of(label == null ? MessageCopy.text(locale, "friend", Map.of()) : label, e.title(),
                amount, deadline, payUrl.replace("{order_id}", member.id().toString()));
        return new Content(title, body, "/orders/" + member.id(), SPLIT_INVITE, params);
    }

    /** Stale unless the order has a PENDING code: params [JMH XXXX XX, exact amount, deadline in the venue zone]. */
    private Content payCode(UUID org, UUID orderId, String locale) {
        Optional<CashInstructions.Cash> code = cash.pending(org, orderId);
        if (code.isEmpty()) {
            return null;
        }
        Order o = orders.inOrg(org, orderId).orElseThrow().order();
        Inventory.EventLabel e = event(o.eventId());
        return new Content(null, null, null, PAY_CODE, List.of(CashCode.display(code.get().payCode()),
                MessageCopy.amount(code.get().amountMad()),
                MessageCopy.deadline(o.reservedUntil(), e.timezone(), locale)));
    }

    private Inventory.EventLabel event(int eventId) {
        return inventory.label(eventId).orElseThrow();
    }
}
