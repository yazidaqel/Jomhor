package ma.jomhor.tenancy.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.HolderNames;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.domain.Pins;
import ma.jomhor.tenancy.domain.StaffMember;
import ma.jomhor.tenancy.domain.StaffRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gate staff and their PINs (docs/04-api.md §Organizer /org/staff, §Gate PINs). {@code org} is always
 * OrgContext.organizerId() (or the device's organizer); another organizer's row is 404. The plain PIN exists only in
 * the {@link Created} / {@link #resetPin} return value: never stored, logged or audited.
 */
@Service
public class StaffService {

    /** POST /org/staff: the row and its PIN, shown once (null for a CASHIER). */
    public record Created(StaffMember staff, String pin) {
        @Override
        public String toString() {
            return "Created[staff=" + staff + ", pin=****]";
        }
    }

    /** Same as the auth endpoints. */
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");

    private final Accounts accounts;
    private final AuditLog audit;

    public StaffService(Accounts accounts, AuditLog audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    public List<StaffMember> list(UUID org) {
        return accounts.staff(org);
    }

    /**
     * GATE or SUPERVISOR (a PIN, {@code phone} ignored) or CASHIER (no PIN: {@code phone} E.164 required, its user
     * created or linked, so it logs in by OTP; a phone already staff of this organizer → 422 TAKEN). {@code name}
     * per §Holder names.
     */
    @Transactional
    public Created create(UUID org, String name, StaffRole role, String phone) {
        String n = name(name);
        if (role == StaffRole.CASHIER) {
            if (phone == null || !E164.matcher(phone).matches()) {
                throw invalid("phone", "an E.164 phone number");
            }
            UUID user = accounts.upsertByPhone(phone).id();
            return new Created(accounts.insertCashier(org, user, n).orElseThrow(() -> new ApiException(
                    "VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                    Map.of("fields", Map.of("phone", "already staff of this organizer"), "reason", "TAKEN"))), null);
        }
        if (role != StaffRole.GATE && role != StaffRole.SUPERVISOR) {
            throw invalid("role", "GATE, SUPERVISOR or CASHIER");
        }
        String pin = Pins.newPin();
        return new Created(accounts.insertScannerStaff(org, n, role, Pins.verifier(pin)), pin);
    }

    /** GATE/SUPERVISOR/CASHIER rows only (others → 404); null = unchanged. */
    @Transactional
    public StaffMember update(UUID org, UUID staffId, String name, Boolean active) {
        return accounts.updateScannerStaff(org, staffId, name == null ? null : name(name), active)
                .orElseThrow(StaffService::notFound);
    }

    /** A new random PIN for a GATE/SUPERVISOR row (others → 404); audited without the PIN. */
    @Transactional
    public String resetPin(UUID org, UUID userId, UUID staffId) {
        String pin = Pins.newPin();
        if (!accounts.setScannerPin(org, staffId, Pins.verifier(pin))) {
            throw notFound();
        }
        audit.staff(org, userId, "staff.pin_reset", staffId.toString());
        return pin;
    }

    /** What the organizer's scanners get with GET /gate/events: active GATE/SUPERVISOR staff with a PIN. */
    public List<StaffMember> scannerStaff(UUID org) {
        return accounts.activeScannerStaff(org);
    }

    private static String name(String raw) {
        return HolderNames.normalize(raw).orElseThrow(() -> invalid("name", HolderNames.INVALID));
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }

    private static ApiException notFound() {
        return ApiException.notFound("Staff not found");
    }
}
