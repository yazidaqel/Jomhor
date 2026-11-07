package ma.jomhor.tenancy.application;

import java.time.Instant;
import java.util.UUID;

/** Persistence port for {@code fan_sessions} (platform table, per user, no organizer_id; plan P12). */
public interface FanSessions {

    UUID create(UUID userId, Instant expiresAt);

    /** One primary-key lookup: the session exists for this user, is not revoked and not expired. */
    boolean isActive(UUID sessionId, UUID userId);

    void revoke(UUID sessionId, UUID userId);

    void revokeAll(UUID userId);

    /** Every session of the user but {@code keep} (lost-phone re-issue). */
    void revokeOthers(UUID keep, UUID userId);
}
