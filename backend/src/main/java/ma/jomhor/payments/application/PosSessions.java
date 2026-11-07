package ma.jomhor.payments.application;

import java.util.Optional;
import java.util.UUID;
import ma.jomhor.payments.domain.PosSession;

/**
 * Persistence port for pos_sessions, scoped by organizer. The cashier is the caller's active staff row of the
 * organizer (staff.user_id = the token subject).
 */
public interface PosSessions {

    /** The caller's open session, with its figures. */
    Optional<PosSession> openOf(UUID org, UUID userId);

    /**
     * The caller's open session id, its row locked ({@code for update}) until the end of the transaction: sales and
     * collects of one till run one at a time, and a close waits for them (so expected_mad misses none).
     */
    Optional<UUID> lockOpenOf(UUID org, UUID userId);

    /** Opens a session for the caller; false if the caller already has one open (unchanged). */
    boolean insert(UUID org, UUID userId, String label);

    /** A session of the organizer and who runs it, locked {@code for update}. */
    Optional<Owned> lock(UUID org, UUID sessionId);

    record Owned(UUID id, UUID staffUserId, boolean closed) {}

    /** Closes a locked open session: expected_mad = sum of its SUCCEEDED payments now, declared as counted. */
    void close(UUID org, UUID sessionId, long declaredMad);

    Optional<PosSession> get(UUID org, UUID sessionId);
}
