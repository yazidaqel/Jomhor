package ma.jomhor.payments.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.payments.application.CashDeposits.Locked;
import ma.jomhor.payments.domain.CashDeposit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bank deposits of till cash (docs/04-api.md §Finance Deposits, plan lot-5 Q233): FINANCE types in the slip and the
 * closed sessions it covers; an ADMIN voids a wrong one. A control on the club's own cash: it never touches payments.
 */
@Service
public class DepositService {

    /** Local days of deposited_on and of a session's close (04: "today in Africa/Casablanca"). */
    static final ZoneId ZONE = ZoneId.of(Venue.DEFAULT_TIMEZONE);
    static final int MAX_SESSIONS = 100;
    static final int MAX_UNDEPOSITED = 200;

    public record Listing(List<CashDeposit> deposits, List<CashDeposit.Session> undeposited) {}

    private final CashDeposits deposits;
    private final AuditLog audit;
    private final JsonMapper json;

    DepositService(CashDeposits deposits, AuditLog audit, JsonMapper json) {
        this.deposits = deposits;
        this.audit = audit;
        this.json = json;
    }

    /** 04 GET /org/finance/deposits: the range as /finance/cash (both days included, ≤ 92, else 422). One snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Listing list(UUID org, String from, String to) {
        CashReport.Days days = CashReport.days(from, to);
        return new Listing(deposits.between(org, days.from(), days.to()), deposits.undeposited(org, MAX_UNDEPOSITED));
    }

    /**
     * 04 POST /org/finance/deposits, in 04's bullet order: body checks, then the sessions under their row locks (id
     * order): UNKNOWN, OPEN, DEPOSITED on the first failing {@code session_ids[i]}; then the slip date (FUTURE,
     * BEFORE_CLOSE).
     */
    @Transactional
    public CashDeposit create(UUID org, UUID user, LocalDate depositedOn, Long amountMad, String reference,
                              List<UUID> sessionIds) {
        if (amountMad == null || amountMad < 0) {
            throw invalid("amount_mad", "an integer >= 0", null);
        }
        String ref = text("reference", reference, 100);
        if (sessionIds == null || sessionIds.isEmpty() || sessionIds.size() > MAX_SESSIONS || sessionIds.contains(null)
                || new HashSet<>(sessionIds).size() != sessionIds.size()) {
            throw invalid("session_ids", "1 to " + MAX_SESSIONS + " distinct session ids", null);
        }
        Map<UUID, Locked> locked = deposits.lockSessions(org, sessionIds).stream()
                .collect(Collectors.toMap(Locked::id, Function.identity()));
        for (int i = 0; i < sessionIds.size(); i++) {
            Locked s = locked.get(sessionIds.get(i));
            String reason = s == null ? "UNKNOWN" : s.closedAt() == null ? "OPEN" : s.deposited() ? "DEPOSITED" : null;
            if (reason != null) {
                throw invalid("session_ids[" + i + "]", reason.toLowerCase(), reason);
            }
        }
        if (depositedOn == null) {
            throw invalid("deposited_on", "required", null);
        }
        if (depositedOn.isAfter(LocalDate.now(ZONE))) {
            throw invalid("deposited_on", "must not be after today", "FUTURE");
        }
        if (locked.values().stream().anyMatch(s -> depositedOn.isBefore(s.closedAt().atZone(ZONE).toLocalDate()))) {
            throw invalid("deposited_on", "must not be before a session's close day", "BEFORE_CLOSE");
        }
        UUID id = deposits.insert(org, user, depositedOn, amountMad, ref, sessionIds);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount_mad", amountMad);
        payload.put("reference", ref);
        payload.put("session_ids", sessionIds);
        audit.staff(org, user, "deposit.create", id.toString(), json.writeValueAsString(payload));
        return deposits.get(org, id).orElseThrow();
    }

    /**
     * 04 POST /org/finance/deposits/{id}/void: unknown → 404; voided → unchanged, whatever the reason (a retry); live →
     * the reason checked, voided, sessions freed, audited.
     */
    @Transactional
    public CashDeposit voidDeposit(UUID org, UUID user, UUID depositId, String reason) {
        boolean voided = deposits.lockVoided(org, depositId)
                .orElseThrow(() -> ApiException.notFound("Deposit not found"));
        if (!voided) {
            String why = text("reason", reason, 200);
            List<UUID> freed = deposits.voidDeposit(org, user, depositId, why);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", why);
            payload.put("session_ids", freed);
            audit.staff(org, user, "deposit.void", depositId.toString(), json.writeValueAsString(payload));
        }
        return deposits.get(org, depositId).orElseThrow();
    }

    /** Trimmed, 1 to {@code max} characters, else 422 on {@code field}. */
    private static String text(String field, String value, int max) {
        String v = value == null ? "" : value.strip();
        int length = v.codePointCount(0, v.length());
        if (length < 1 || length > max) {
            throw invalid(field, "1 to " + max + " characters", null);
        }
        return v;
    }

    private static ApiException invalid(String field, String message, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (reason != null) {
            details.put("reason", reason);
        }
        details.put("fields", Map.of(field, message));
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body", details);
    }
}
