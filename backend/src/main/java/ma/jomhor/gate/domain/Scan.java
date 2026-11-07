package ma.jomhor.gate.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One checked scan row as the device sent it (scans table; docs/04-api.md §Gate sync rules). Append-only: stored once,
 * never updated or deleted. DUPLICATE_LATE is not a result: reports derive it (plan lot-2 Q34).
 */
public record Scan(UUID scanId, UUID ticketId, UUID gateId, UUID staffId, Direction direction, Result result,
                   Integer tokenVersion, Instant deviceTime, OverrideReason overrideReason, UUID supervisorId) {

    public enum Direction { IN, OUT }

    /** The device codes (06 glossary). {@code authenticated}: the token verified, so the row names its ticket. */
    public enum Result {
        OK(true), DUPLICATE(true), BAD_SIG(false), BAD_FORMAT(false), UNKNOWN(true), CANCELLED(true), BLOCKED(true),
        WRONG_ZONE(true), WRONG_EVENT(false), EXPIRED_SLOT(false), OVERRIDE(true);

        public final boolean authenticated;

        Result(boolean authenticated) {
            this.authenticated = authenticated;
        }
    }

    public enum OverrideReason { ID_CHECKED, DEVICE_FAULT, DAMAGED_TICKET, OTHER }

    /** An admitted (OK/OVERRIDE) scan of another device, for used_since: {@code firstAt} is its device_time. */
    public record Used(long seq, UUID ticketId, Direction direction, Instant firstAt, String gateCode) {}
}
