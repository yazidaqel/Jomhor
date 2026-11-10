package ma.jomhor.crm.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.common.AuditLog;
import ma.jomhor.crm.domain.Loyalty;
import ma.jomhor.crm.domain.Member;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.application.UserService;
import ma.jomhor.tenancy.domain.LoyaltyTier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GET/PUT/DELETE /fan/me/membership (docs/04-api.md §Fan; plan lot-4 Q57, Q58, Q89-Q91, Q99, Q100). */
@Service
public class MembershipService {

    /** 04: the 11 latest CLOSED events (the 12th dot is the next event, read elsewhere). */
    static final int LAST_EVENTS = 11;

    public record View(MemberView member, LoyaltyView loyalty) {}

    public record MemberView(int number, Instant joinedAt, boolean marketingPush, boolean marketingWhatsapp) {}

    public record LoyaltyView(long eventsAttended, Tier tier, NextTier nextTier, List<Memberships.PastEvent> lastEvents) {}

    public record Tier(String code, String name) {}

    public record NextTier(String code, String name, int minEvents) {}

    private final Memberships memberships;
    private final OrganizerService organizers;
    private final UserService users;
    private final AuditLog audit;

    MembershipService(Memberships memberships, OrganizerService organizers, UserService users, AuditLog audit) {
        this.memberships = memberships;
        this.organizers = organizers;
        this.users = users;
        this.audit = audit;
    }

    /** The GET shape; loyalty for non-members too. Unknown organizer → 404. */
    public View view(UUID userId, UUID org) {
        List<LoyaltyTier> tiers = organizers.loyaltyTiers(org);
        String locale = users.get(userId).locale();
        MemberView member = memberships.find(org, userId).filter(Member::live)
                .map(m -> new MemberView(m.number(), m.joinedAt(), m.marketingPush(), m.marketingWhatsapp()))
                .orElse(null);
        long attended = memberships.eventsAttended(org, userId);
        LoyaltyTier tier = Loyalty.tier(tiers, attended);
        LoyaltyTier next = Loyalty.next(tiers, attended);
        return new View(member, new LoyaltyView(attended, new Tier(tier.code(), Loyalty.name(tier, locale)),
                next == null ? null : new NextTier(next.code(), Loyalty.name(next, locale), next.minEvents()),
                memberships.lastEvents(org, userId, LAST_EVENTS)));
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

    /** The audit payload: the two booleans, nothing else. */
    private static String optIns(boolean push, boolean whatsapp) {
        return "{\"marketing_push\": %b, \"marketing_whatsapp\": %b}".formatted(push, whatsapp);
    }
}
