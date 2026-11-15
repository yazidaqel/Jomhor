package ma.jomhor.catalog.application;

import java.util.UUID;

/** What catalog needs from season passes (docs/04-api.md §Passes). Implemented by the sales module's products. */
public interface EventPasses {

    /** Whether a pass ticket order (pass_id and event_id set) exists for the event: its series_id is then fixed. */
    boolean hasPassTickets(UUID org, int eventId);
}
