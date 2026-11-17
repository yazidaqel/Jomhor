package ma.jomhor.tenancy.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.domain.CashPoint;
import ma.jomhor.tenancy.domain.FinanceSettings;
import ma.jomhor.tenancy.domain.LoyaltyTier;
import ma.jomhor.tenancy.domain.Organizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Organizer settings for other modules, and the public club directory (docs/04-api.md §Club directory). */
@Service
public class OrganizerService {

    private static final Logger log = LoggerFactory.getLogger(OrganizerService.class);

    private final Accounts accounts;
    private final JsonMapper json;

    public OrganizerService(Accounts accounts, JsonMapper json) {
        this.accounts = accounts;
        this.json = json;
    }

    /**
     * organizers.settings.finance (plan lot-5 Q231); empty when absent, and when malformed with one WARN (no
     * statements then, never a 500).
     */
    public Optional<FinanceSettings> finance(UUID org) {
        return accounts.finance(org).flatMap(raw -> parsed(org, raw));
    }

    /** The organizers with valid finance settings (the statement job's), by id; one WARN per malformed one. */
    public Map<UUID, FinanceSettings> withFinance() {
        Map<UUID, FinanceSettings> valid = new LinkedHashMap<>();
        accounts.financeAll().forEach((org, raw) -> parsed(org, raw).ifPresent(f -> valid.put(org, f)));
        return valid;
    }

    private Optional<FinanceSettings> parsed(UUID org, String raw) {
        Optional<FinanceSettings> f = FinanceSettings.parse(json.readTree(raw));
        if (f.isEmpty()) {
            log.warn("Malformed settings.finance of organizer {}: no settlement statements", org);
        }
        return f;
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

    /** The organizer as fans see it (listed or not, slug null when unlisted); unknown → 404. */
    public Organizer get(UUID org) {
        return accounts.organizer(org).orElseThrow(() -> ApiException.notFound("Organizer not found"));
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
