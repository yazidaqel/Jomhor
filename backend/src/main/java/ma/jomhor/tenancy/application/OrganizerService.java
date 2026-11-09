package ma.jomhor.tenancy.application;

import java.util.List;
import java.util.UUID;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.domain.CashPoint;
import ma.jomhor.tenancy.domain.LoyaltyTier;
import ma.jomhor.tenancy.domain.Organizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Organizer settings for other modules, and the public club directory (docs/04-api.md §Club directory). */
@Service
public class OrganizerService {

    private static final Logger log = LoggerFactory.getLogger(OrganizerService.class);

    private final Accounts accounts;

    public OrganizerService(Accounts accounts) {
        this.accounts = accounts;
    }

    /** organizers.settings.cash_points in their order; empty when unset (set by ops via SQL until Lot 5, plan Q54). */
    public List<CashPoint> cashPoints(UUID org) {
        return accounts.cashPoints(org);
    }

    /**
     * organizers.settings.loyalty.tiers (docs/03-schema.sql); the defaults when absent, and when malformed with one
     * WARN (plan lot-4 Q99: never a 500). Unknown organizer (listed or not) → 404.
     */
    public List<LoyaltyTier> loyaltyTiers(UUID org) {
        Accounts.LoyaltySetting s = accounts.loyalty(org).orElseThrow(() -> ApiException.notFound("Organizer not found"));
        if (!s.present()) {
            return LoyaltyTier.DEFAULTS;
        }
        if (LoyaltyTier.valid(s.tiers())) {
            return s.tiers();
        }
        log.warn("Malformed settings.loyalty of organizer {}: default tiers used", org);
        return LoyaltyTier.DEFAULTS;
    }

    /** 404 unless the organizer exists (listed or not). */
    public void require(UUID org) {
        if (!accounts.organizerExists(org)) {
            throw ApiException.notFound("Organizer not found");
        }
    }

    /** GET /fan/organizers: listed organizers, by name then id. */
    public List<Organizer> listed() {
        return accounts.listedOrganizers();
    }

    /** GET /fan/organizers/{slug}: unknown, unlisted or malformed slug → 404 (a malformed one matches no row). */
    public Organizer listed(String slug) {
        return accounts.listedOrganizer(slug).orElseThrow(() -> ApiException.notFound("Organizer not found"));
    }
}
