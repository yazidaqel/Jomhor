package ma.jomhor.payments.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.payments.domain.Refund;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The refunds ledger (plan Q52): a refunded ticket owes its unit price back. No gateway refund API: the money goes
 * back by hand (gateway back-office, club office cash), then FINANCE or an ADMIN marks the refund DONE.
 */
@Service
public class RefundService {

    private final Refunds refunds;
    private final AuditLog audit;
    private final JsonMapper json;

    public RefundService(Refunds refunds, AuditLog audit, JsonMapper json) {
        this.refunds = refunds;
        this.audit = audit;
        this.json = json;
    }

    /** A REQUIRED refund for a ticket refunded in the caller's transaction (tickets: POST /org/tickets/{id}/refund). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID request(UUID org, UUID user, int eventId, UUID ticketId, UUID orderId, long amountMad, String reason) {
        return refunds.insert(org, user, eventId, ticketId, orderId, amountMad, reason);
    }

    public List<Refund> list(UUID org, Refund.Status status, Integer eventId) {
        return refunds.list(org, status, eventId);
    }

    /**
     * 04 POST /org/refunds/{id}/done: {@code reference} 1 to 100 characters (trimmed). REQUIRED → DONE, audited
     * {@code refund.done}; already DONE → the row unchanged. Unknown or another organizer's → 404.
     */
    @Transactional
    public Refund done(UUID org, UUID user, UUID refundId, String reference) {
        String ref = reference == null ? "" : reference.strip();
        int length = ref.codePointCount(0, ref.length());
        if (length < 1 || length > 100) {
            throw new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                    Map.of("fields", Map.of("reference", "1 to 100 characters")));
        }
        Refund.Status status = refunds.lockStatus(org, refundId)
                .orElseThrow(() -> ApiException.notFound("Refund not found"));
        if (status == Refund.Status.REQUIRED) {
            refunds.done(org, user, refundId, ref);
            audit.staff(org, user, "refund.done", refundId.toString(), json.writeValueAsString(Map.of("reference", ref)));
        }
        return refunds.get(org, refundId).orElseThrow();
    }
}
