package ma.jomhor.sales.rooms.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.crm.application.MembershipService;
import ma.jomhor.sales.rooms.domain.QueueEntry;
import ma.jomhor.sales.rooms.domain.WaitingRoom;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Rule;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Status;
import ma.jomhor.tenancy.application.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * POST and GET /fan/queue (docs/04-api.md §Fan, §Waiting room). A join writes one entry per account per room; a poll
 * reads the room from this instance's memo and the rank from its signed token, so a ranked fan still waiting costs no
 * SQL. The entry is read only while the token has no rank, and written once at the turn (admitted_at).
 */
// ponytail: the queue lives in Postgres (plan Q139). If the BE-45 smoke shows joins under target, move entries to a
// sorted set per room (Redis/Valkey) behind QueueEntries; the token contract stays.
@Service
public class QueueService {

    public record Admission(String token, Instant expiresAt) {}

    /** The GET /fan/queue shape. */
    public record View(int eventId, String status, Instant saleStartsAt, Integer position, int inRoom, int ratePerMin,
                       Long estimatedWaitS, boolean soldOut, Admission admission, String queueToken, int pollAfterS,
                       Instant serverTime) {}

    /** A join's result: {@code created} (a new entry, or one moved back) → 201. */
    public record Joined(boolean created, View view) {}

    private static final Duration IP_WINDOW = Duration.ofMinutes(5);

    private final WaitingRooms rooms;
    private final QueueEntries entries;
    private final RoomMemo memo;
    private final RoomTokens tokens;
    private final PollLimiter polls;
    private final Inventory inventory;
    private final UserService users;
    private final MembershipService memberships;
    private final Duration admissionWindow;
    private final int deviceLimit;
    private final int ipLimit5m;
    private final SecureRandom random = new SecureRandom();

    QueueService(WaitingRooms rooms, QueueEntries entries, RoomMemo memo, RoomTokens tokens, PollLimiter polls,
                 Inventory inventory, UserService users, MembershipService memberships,
                 @Value("${rooms.admission-window}") Duration admissionWindow,
                 @Value("${rooms.device-limit}") int deviceLimit, @Value("${rooms.ip-limit-5m}") int ipLimit5m) {
        this.rooms = rooms;
        this.entries = entries;
        this.memo = memo;
        this.tokens = tokens;
        this.polls = polls;
        this.inventory = inventory;
        this.users = users;
        this.memberships = memberships;
        this.admissionWindow = admissionWindow;
        this.deviceLimit = deviceLimit;
        this.ipLimit5m = ipLimit5m;
    }

    /**
     * Checks in 04 order: event visible (404), room live and sales open (QUEUE_NOT_OPEN), eligibility (NOT_ELIGIBLE),
     * device limit, then the per-IP limit only when an entry is created or moved back. {@code deviceId} is validated
     * by the caller (16-64 of [A-Za-z0-9_-]); {@code clientIp} comes from the trusted-proxy chain. Soft limits, no
     * lock: racing joins can overshoot by those in flight.
     */
    public Joined join(UUID userId, int eventId, String deviceId, String clientIp) {
        Inventory.SaleEvent sale = inventory.saleEvent(eventId).filter(e -> !e.draft())
                .orElseThrow(() -> ApiException.notFound("Event not found"));
        UUID org = sale.organizerId();
        WaitingRoom room = rooms.find(org, eventId).orElseThrow(() -> notOpen("NO_ROOM", null));
        Instant now = Instant.now();
        Status status = room.statusAt(now); // a tick running late never keeps fans out once joins are open
        if (status == Status.SCHEDULED) {
            throw notOpen("NOT_STARTED", room.opensAt());
        }
        if (status == Status.ENDED) {
            throw notOpen("ENDED", null);
        }
        if (sale.window() != Inventory.SaleWindow.OPEN && sale.window() != Inventory.SaleWindow.NOT_STARTED) {
            throw notOpen("CLOSED", null);
        }
        checkEligible(org, userId, room, null);
        byte[] device = tokens.hash("device|" + deviceId);
        byte[] ip = tokens.hash("ip|" + clientIp);
        Instant cutoff = sale.refundCutoffAt();
        if (entries.devicePeers(org, room.id(), device, userId) >= deviceLimit) {
            throw new ApiException("QUEUE_DEVICE_LIMIT", HttpStatus.CONFLICT,
                    "Too many accounts on this device for this sale", Map.of("limit", deviceLimit));
        }
        Optional<QueueEntry> existing = entries.findByUser(org, room.id(), userId);
        if (existing.isPresent() && !expired(existing.get(), cutoff, now)) {
            return new Joined(false, view(room, due(room, existing.get(), now), existing.get().id(), null, cutoff, now));
        }
        if (ipLimit5m > 0 && entries.recentFromIp(org, room.id(), ip, IP_WINDOW) >= ipLimit5m) {
            throw new ApiException("QUEUE_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
                    "Too many joins from this network", Map.of("reason", "IP", "retry_after_s", 60));
        }
        if (existing.isPresent()) {
            QueueEntry moved = entries.moveBack(org, room.id(), existing.get().id(), device, ip);
            return new Joined(true, view(room, moved, moved.id(), null, cutoff, now));
        }
        Optional<QueueEntry> created = entries.insert(org, room.id(), userId, device, ip, random.nextLong());
        if (created.isPresent()) {
            return new Joined(true, view(room, created.get(), created.get().id(), null, cutoff, now));
        }
        QueueEntry raced = entries.findByUser(org, room.id(), userId).orElseThrow(); // a concurrent join of the same fan
        return new Joined(false, view(room, raced, raced.id(), null, cutoff, now));
    }

    /**
     * The poll. Missing, bad or expired token, or a purged entry → 401. Then the per-entry limit (QUEUE_RATE_LIMITED
     * POLL, no SQL). A room no longer in the memo has ENDED. A ranked token still waiting reads nothing; an unranked
     * one reads its entry; the turn writes admitted_at once.
     */
    public View poll(String token) {
        Instant now = Instant.now();
        RoomTokens.Queue q = (token == null ? Optional.<RoomTokens.Queue>empty() : tokens.readQueue(token, now))
                .orElseThrow(QueueService::unauthorized);
        if (!polls.allow(q.entryId())) {
            throw new ApiException("QUEUE_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Polling too fast",
                    Map.of("reason", "POLL", "retry_after_s", polls.retryAfterS()));
        }
        Optional<WaitingRoom> live = memo.get(q.eventId());
        if (live.isEmpty()) {
            Instant saleStartsAt = inventory.saleEvent(q.eventId()).map(Inventory.SaleEvent::saleStartsAt).orElse(null);
            return new View(q.eventId(), "ENDED", saleStartsAt, null, 0, 0, null, false, null,
                    tokens.queue(q.entryId(), q.eventId(), q.rank(), q.expiresAt()), 0, now);
        }
        WaitingRoom room = live.get();
        QueueEntry entry = null;
        if (q.rank() == null || q.rank() <= room.admittedThrough()) {
            entry = load(room, q.entryId(), q.rank(), now);
        }
        return view(room, entry, q.entryId(), q.rank(), q.expiresAt(), now);
    }

    /** The entry as of now, admitted if its rank has come up. */
    private QueueEntry load(WaitingRoom room, UUID entryId, Integer tokenRank, Instant now) {
        UUID org = room.organizerId();
        if (tokenRank != null) { // the token's rank is admitted: write the turn (or find the entry moved back since)
            return entries.admit(org, room.id(), entryId, room.admittedThrough(), now)
                    .or(() -> entries.find(org, room.id(), entryId)).orElseThrow(QueueService::unauthorized);
        }
        return due(room, entries.find(org, room.id(), entryId).orElseThrow(QueueService::unauthorized), now);
    }

    /** The entry, admitted now if its rank has come up and its turn hasn't started. */
    private QueueEntry due(WaitingRoom room, QueueEntry e, Instant now) {
        return e.admittedAt() == null && e.rank() != null && e.rank() <= room.admittedThrough()
                ? entries.admit(room.organizerId(), room.id(), e.id(), room.admittedThrough(), now).orElse(e) : e;
    }

    /**
     * The 04 shape from the room and the entry ({@code entry} null = a ranked token still waiting: {@code tokenRank}).
     * {@code cutoff} = refund_cutoff_at: the queue token's expiry and the admission's cap.
     */
    private View view(WaitingRoom room, QueueEntry entry, UUID entryId, Integer tokenRank, Instant cutoff,
                      Instant now) {
        Integer rank = entry == null ? tokenRank : entry.rank();
        String queueToken = tokens.queue(entryId, room.eventId(), rank, cutoff);
        Status status = room.statusAt(now);
        int rate = status == Status.PAUSED ? 0 : room.measuredRatePerMin();
        int inRoom = status == Status.PRE_QUEUE ? room.joinedCount()
                : Math.max(0, room.rankedCount() - room.admittedThrough());
        if (entry != null && entry.admittedAt() != null) {
            Instant expiresAt = turnEnd(entry.admittedAt(), cutoff);
            if (now.isBefore(expiresAt)) {
                Admission admission = new Admission(
                        tokens.admission(entry.userId(), room.eventId(), entryId, expiresAt), expiresAt);
                return new View(room.eventId(), "ADMITTED", room.saleStartsAt(), null, inRoom, rate, null,
                        room.soldOut(), admission, queueToken, 0, now);
            }
            return new View(room.eventId(), "EXPIRED", room.saleStartsAt(), null, inRoom, rate, null, room.soldOut(),
                    null, queueToken, 0, now);
        }
        if (status == Status.PRE_QUEUE) {
            long toSale = Math.max(0, Duration.between(now, room.saleStartsAt()).toSeconds());
            int after = (int) Math.max(1, Math.min(30, toSale + ThreadLocalRandom.current().nextInt(21)));
            return new View(room.eventId(), "PRE_QUEUE", room.saleStartsAt(), null, inRoom, rate, null,
                    room.soldOut(), null, queueToken, after, now);
        }
        Integer position = rank == null ? null : Math.max(1, rank - room.admittedThrough());
        Long wait = position == null || rate == 0 ? null : (position * 60L + rate - 1) / rate;
        int after = position == null ? 2 : jitter(wait != null && wait < 120 ? 5 : 15);
        return new View(room.eventId(), status == Status.PAUSED ? "PAUSED" : "WAITING", room.saleStartsAt(),
                position, inRoom, rate, wait, room.soldOut(), null, queueToken, after, now);
    }

    /** ± 20 %. */
    private static int jitter(int seconds) {
        return (int) Math.round(seconds * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
    }

    /** least(admitted_at + rooms.admission-window, refund_cutoff_at), to the second (a JWT exp). */
    private Instant turnEnd(Instant admittedAt, Instant cutoff) {
        Instant end = admittedAt.plus(admissionWindow);
        return (cutoff != null && cutoff.isBefore(end) ? cutoff : end).truncatedTo(ChronoUnit.SECONDS);
    }

    private boolean expired(QueueEntry e, Instant cutoff, Instant now) {
        return e.admittedAt() != null && !now.isBefore(turnEnd(e.admittedAt(), cutoff));
    }

    /**
     * 04 POST /fan/orders admission step, decided from this instance's memo and the token's signature only (no SQL of
     * the request; the memo reloads by itself once per TTL). No room, one ENDED, or one SCHEDULED before its
     * opens_at: no token needed, empty. A live room (PRE_QUEUE, OPEN, PAUSED, or SCHEDULED past opens_at while the
     * tick catches up: fail closed, by the memo row alone) needs the caller's admission token for this event, else 409
     * ADMISSION_REQUIRED {@code MISSING}, {@code INVALID} (bad signature, another user or another event) or
     * {@code EXPIRED}; it is returned for the split members' eligibility.
     */
    public Optional<WaitingRoom> requireAdmission(UUID userId, int eventId, String token) {
        Instant now = Instant.now();
        Optional<WaitingRoom> room = memo.get(eventId).filter(r -> r.statusAt(now).live());
        if (room.isEmpty()) {
            return room;
        }
        if (token == null || token.isBlank()) {
            throw admissionRequired("MISSING");
        }
        RoomTokens.Admission a = tokens.readAdmission(token)
                .filter(t -> t.eventId() == eventId && t.userId().equals(userId))
                .orElseThrow(() -> admissionRequired("INVALID"));
        if (!now.isBefore(a.expiresAt())) {
            throw admissionRequired("EXPIRED");
        }
        return room;
    }

    /**
     * 04 §Waiting room Eligibility, against the event's organizer; the first rule failed is the reason. {@code field}:
     * null for the caller's own join (details: the reason and the rule's figures), else the holder path of a split
     * {@code pays} member (04 POST /fan/orders, NOT_ELIGIBLE): the details are that path alone, no reason and no rule
     * figure, so a buyer can't probe another phone's account age, membership or attendance.
     */
    public void checkEligible(UUID org, UUID userId, WaitingRoom room, String field) {
        Rule rule = room.config().rule();
        Map<String, Object> details = new LinkedHashMap<>();
        if (rule.minAccountAgeDays() != null) {
            Instant verified = users.verifiedAt(userId);
            if (verified == null || verified.isAfter(room.verifiedBefore())) {
                details.put("reason", "ACCOUNT_TOO_NEW");
                details.put("verified_before", room.verifiedBefore().toString());
            }
        }
        if (details.isEmpty() && rule.memberJoinedBefore() != null) {
            Instant since = memberships.liveMemberSince(org, userId);
            if (since == null || !since.isBefore(rule.memberJoinedBefore())) {
                details.put("reason", "NOT_MEMBER");
                details.put("member_joined_before", rule.memberJoinedBefore().toString());
            }
        }
        if (details.isEmpty() && rule.minEventsAttended() != null) {
            long attended = memberships.eventsAttended(org, userId);
            if (attended < rule.minEventsAttended()) {
                details.put("reason", "NOT_ENOUGH_EVENTS");
                details.put("min_events_attended", rule.minEventsAttended());
                details.put("attended", attended);
            }
        }
        if (!details.isEmpty()) {
            throw new ApiException("NOT_ELIGIBLE", HttpStatus.CONFLICT, "Not eligible for this sale",
                    field == null ? details : Map.of("field", field));
        }
    }

    private static ApiException notOpen(String reason, Instant opensAt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        if (opensAt != null) {
            details.put("opens_at", opensAt.toString());
        }
        return new ApiException("QUEUE_NOT_OPEN", HttpStatus.CONFLICT, "The queue is not open", details);
    }

    private static ApiException admissionRequired(String reason) {
        return new ApiException("ADMISSION_REQUIRED", HttpStatus.CONFLICT, "Your turn in the waiting room is needed",
                Map.of("reason", reason));
    }

    private static ApiException unauthorized() {
        return new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Missing, invalid or wrong-audience token");
    }
}
