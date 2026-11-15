package ma.jomhor.crm.application;

import java.util.UUID;

/** What crm needs from products (docs/04-api.md §Products Member rule). Implemented by the sales module's products. */
public interface MembershipProducts {

    /** Whether the organizer has a MEMBERSHIP product, any status: paid_required. */
    boolean any(UUID org);
}
