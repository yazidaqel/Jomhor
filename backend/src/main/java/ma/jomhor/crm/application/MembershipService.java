package ma.jomhor.crm.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.common.AuditLog;
import ma.jomhor.crm.domain.Loyalty;
import ma.jomhor.crm.domain.Member;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.application.UserService;
import ma.jomhor.tenancy.domain.LoyaltyTier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** GET/PUT/DELETE /fan/me/membership (docs/04-api.md §Fan; plan lot-4 Q57, Q58, Q89-Q91, Q99, Q100). */
@Service
public class MembershipService {

    /** 04: the 11 latest CLOSED events (the 12th dot is the next event, read elsewhere). */
    static final int LAST_EVENTS = 11;

    /** {@code paidRequired}: 04 §Products Member rule (Lot 5 step 3). */
    public record View(MemberView member, LoyaltyView loyalty, boolean paidRequired) {}

    /** {@code paidUntil}: null = never paid. */
    public record MemberView(int number, Instant joinedAt, boolean marketingPush, boolean marketingWhatsapp,
                             Instant paidUntil) {}

    public record LoyaltyView(long eventsAttended, Tier tier, NextTier nextTier, List<Memberships.PastEvent> lastEvents) {}

    public record Tier(String code, String name) {}

    public record NextTier(String code, String name, int minEvents) {}

    /** GET /org/members/summary: counts only, no name or phone. */
    public record Summary(long members, @JsonProperty("joined_7d") long joined7d, long marketingPush,
                          long marketingWhatsapp, long reachablePush, List<TierCount> tiers, long paid,
                          boolean paidRequired, long exportable) {}

    public record TierCount(String code, String name, long members) {}

    private final Memberships memberships;
    private final OrganizerService organizers;
    private final UserService users;
    private final AuditLog audit;
    private final MembershipProducts products;

    MembershipService(Memberships memberships, OrganizerService organizers, UserService users, AuditLog audit,
                      MembershipProducts products) {
        this.memberships = memberships;
        this.organizers = organizers;
        this.users = users;
        this.audit = audit;
        this.products = products;
    }

    /** The GET shape; loyalty for non-members too. Unknown organizer → 404. */
    public View view(UUID userId, UUID org) {
        List<LoyaltyTier> tiers = organizers.loyaltyTiers(org);
        String locale = users.get(userId).locale();
        MemberView member = memberships.find(org, userId).filter(Member::live)
                .map(m -> new MemberView(m.number(), m.joinedAt(), m.marketingPush(), m.marketingWhatsapp(),
                        m.paidUntil()))
                .orElse(null);
        long attended = memberships.eventsAttended(org, userId);
        LoyaltyTier tier = Loyalty.tier(tiers, attended);
        LoyaltyTier next = Loyalty.next(tiers, attended);
        return new View(member, new LoyaltyView(attended, new Tier(tier.code(), Loyalty.name(tier, locale)),
                next == null ? null : new NextTier(next.code(), Loyalty.name(next, locale), next.minEvents()),
                memberships.lastEvents(org, userId, LAST_EVENTS)), paidRequired(org));
    }

    /** 04 §Products Member rule: the organizer has a MEMBERSHIP product (any status), so members need a cotisation. */
    public boolean paidRequired(UUID org) {
        return products.any(org);
    }

    /**
     * 04 §Products Member rule, the caller passing {@link #paidRequired}: a live membership with joined_at before
     * {@code joinedBefore} (null = no cutoff) and, when {@code paidRequired}, paid_until after {@code at}.
     */
    public boolean memberAt(UUID org, UUID userId, Instant joinedBefore, Instant at, boolean paidRequired) {
        return memberships.find(org, userId).filter(Member::live)
                .filter(m -> joinedBefore == null || m.joinedAt().isBefore(joinedBefore))
                .filter(m -> !paidRequired || m.paidUntil() != null && m.paidUntil().isAfter(at))
                .isPresent();
    }

    /** Live members with paid_until after now (04 GET /org/products/{id} MEMBERSHIP {@code paid_members}). */
    public long paidMembers(UUID org) {
        return memberships.counts(org, Set.of()).paid();
    }

    /** The fan's paid_until in the organizer's club (left or not), or null (never paid, or no membership). */
    public Instant paidUntil(UUID org, UUID userId) {
        return memberships.find(org, userId).map(Member::paidUntil).orElse(null);
    }

    /**
     * 04 §Products Paid membership: a MEMBERSHIP order is being paid (sales' settle, in its transaction). Under the
     * organizer's membership lock: already paid to {@code validUntil} → false, nothing written (NOT_PAYABLE). Else a
     * non-member joins (next number, opt-ins off) or a left member rejoins (number kept), audit membership.join, then
     * paid_until = greatest(paid_until, validUntil), audit membership.paid {product_id, paid_until}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean paid(UUID org, UUID userId, UUID productId, Instant validUntil) {
        memberships.lock(org);
        Optional<Member> existing = memberships.find(org, userId);
        if (existing.map(Member::paidUntil).filter(p -> !p.isBefore(validUntil)).isPresent()) {
            return false;
        }
        Member m = existing.orElse(null);
        if (m == null) {
            m = memberships.insertNext(org, userId, false, false);
            audit.user(org, userId, "membership.join", m.id().toString(), optIns(false, false));
        } else if (!m.live() && memberships.rejoin(org, m.id(), false, false)) {
            audit.user(org, userId, "membership.join", m.id().toString(), optIns(false, false));
        }
        Instant until = memberships.pay(org, m.id(), validUntil);
        audit.user(org, userId, "membership.paid", m.id().toString(),
                "{\"product_id\": \"%s\", \"paid_until\": \"%s\"}".formatted(productId, until));
        return true;
    }

    /** Events attended at this organizer, as /fan/me/membership counts them (on-sale eligibility). */
    public long eventsAttended(UUID org, UUID userId) {
        return memberships.eventsAttended(org, userId);
    }

    /**
     * PUT: join (or rejoin) → true (201); already a live member → opt-ins set, false (200). The organizer's membership
     * lock serializes PUT and DELETE per club: one number draw, no retry, and each audit row matches what its statement
     * changed (the statements are guarded too, so an audit never follows a write that did nothing).
     */
    @Transactional
    public boolean put(UUID userId, UUID org, boolean push, boolean whatsapp) {
        organizers.require(org);
        memberships.lock(org);
        Optional<Member> existing = memberships.find(org, userId);
        if (existing.isEmpty()) {
            Member joined = memberships.insertNext(org, userId, push, whatsapp);
            audit.user(org, userId, "membership.join", joined.id().toString(), optIns(push, whatsapp));
            return true;
        }
        Member m = existing.get();
        if (!m.live() && memberships.rejoin(org, m.id(), push, whatsapp)) {
            audit.user(org, userId, "membership.join", m.id().toString(), optIns(push, whatsapp));
            return true;
        }
        if (memberships.setOptIns(org, m.id(), push, whatsapp)) {
            audit.user(org, userId, "membership.consent", m.id().toString(), optIns(push, whatsapp));
        }
        return false;
    }

    /** DELETE: idempotent; membership.leave (no payload) only when a live membership is left. */
    @Transactional
    public void leave(UUID userId, UUID org) {
        organizers.require(org);
        memberships.lock(org);
        memberships.leave(org, userId)
                .ifPresent(id -> audit.user(org, userId, "membership.leave", id.toString(), null));
    }

    /**
     * 04 GET /org/members/summary: live members' counts, and every tier of the organizer (by min_events, fr names,
     * zeros included) with its members, each placed by events attended as /fan/me/membership does. {@code exportable}
     * counts the CRM export's rows for these current pass holders (CrmExportService.summary supplies them).
     */
    Summary summary(UUID org, Set<UUID> passHolders) {
        List<LoyaltyTier> tiers = organizers.loyaltyTiers(org);
        Memberships.Counts n = memberships.counts(org, passHolders);
        Map<Long, Long> byAttended = memberships.membersByEventsAttended(org);
        return new Summary(n.members(), n.joined7d(), n.marketingPush(), n.marketingWhatsapp(), n.reachablePush(),
                tiers.stream().map(t -> new TierCount(t.code(), Loyalty.name(t, "fr"), byAttended.entrySet().stream()
                        .filter(e -> Loyalty.tier(tiers, e.getKey()).equals(t)).mapToLong(Map.Entry::getValue).sum()))
                        .toList(), n.paid(), paidRequired(org), n.exportable());
    }

    /** The audit payload: the two booleans, nothing else. */
    private static String optIns(boolean push, boolean whatsapp) {
        return "{\"marketing_push\": %b, \"marketing_whatsapp\": %b}".formatted(push, whatsapp);
    }
}
