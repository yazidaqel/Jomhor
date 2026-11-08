package ma.jomhor.tenancy.application;

import java.util.List;
import java.util.UUID;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.domain.CashPoint;
import ma.jomhor.tenancy.domain.Organizer;
import org.springframework.stereotype.Service;

/** Organizer settings for other modules, and the public club directory (docs/04-api.md §Club directory). */
@Service
public class OrganizerService {

    private final Accounts accounts;

    public OrganizerService(Accounts accounts) {
        this.accounts = accounts;
    }

    /** organizers.settings.cash_points in their order; empty when unset (set by ops via SQL until Lot 5, plan Q54). */
    public List<CashPoint> cashPoints(UUID org) {
        return accounts.cashPoints(org);
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
