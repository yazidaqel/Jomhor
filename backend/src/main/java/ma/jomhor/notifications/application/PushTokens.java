package ma.jomhor.notifications.application;

import java.util.List;
import java.util.UUID;

/** Persistence port for push_tokens (platform identity like fan_sessions: no organizer_id, plan lot-4 Q73). */
public interface PushTokens {

    /**
     * In the caller's transaction: the session's other tokens are deleted, then {@code token} is upserted to this user
     * and session.
     */
    void register(UUID userId, UUID sessionId, String token, String platform);

    /** The user's tokens whose session is live (not revoked, not expired). */
    List<String> live(UUID userId);

    void delete(String token);
}
