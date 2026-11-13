package ma.jomhor.notifications.application;

import java.util.List;
import java.util.UUID;

/** Persistence port for push_tokens (platform identity like fan_sessions: no organizer_id, plan lot-4 Q73). */
public interface PushTokens {

    /**
     * The one live-token SQL (docs/04-api.md §Notifications): token {@code p} joined to its session {@code f}, not
     * revoked and not expired. Sending and every audience count (campaign estimate and snapshot, the members summary's
     * reachable_push) use it, so they cannot drift apart.
     */
    String LIVE_JOIN = "push_tokens p join fan_sessions f on f.id = p.session_id and f.user_id = p.user_id"
            + " and f.revoked_at is null and f.expires_at > now()";

    /** True when the user in {@code userColumn} has a live token ({@link #LIVE_JOIN}). */
    static String liveTokenOf(String userColumn) {
        return "exists (select 1 from " + LIVE_JOIN + " where p.user_id = " + userColumn + ")";
    }

    /**
     * In the caller's transaction: the session's other tokens are deleted, then {@code token} is upserted to this user
     * and session.
     */
    void register(UUID userId, UUID sessionId, String token, String platform);

    /** The user's tokens whose session is live (not revoked, not expired). */
    List<String> live(UUID userId);

    void delete(String token);
}
