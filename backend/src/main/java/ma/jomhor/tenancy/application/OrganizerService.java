package ma.jomhor.tenancy.application;

import java.util.List;
import java.util.UUID;
import ma.jomhor.tenancy.domain.CashPoint;
import org.springframework.stereotype.Service;

/** Organizer settings for other modules. */
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
}
