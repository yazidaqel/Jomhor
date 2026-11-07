package ma.jomhor.tenancy.domain;

import java.util.Arrays;
import java.util.UUID;
import ma.jomhor.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Who calls an {@code /org/**} endpoint, for which organizer. Set per request by the
 * X-Organizer-Id check; controllers read it with {@code @RequestAttribute(OrgContext.ATTRIBUTE)}.
 * Tenant queries take {@link #organizerId()} from here, never from the body.
 */
public record OrgContext(UUID organizerId, UUID userId, StaffRole role) {

    public static final String ATTRIBUTE = "ma.jomhor.orgContext";

    /**
     * Lot 2 devices, staff, assignments and close are ADMIN/OPS only (04 §Organizer): FINANCE gets 403 there, GETs
     * included, although the interceptor lets it read every other /org GET.
     */
    public OrgContext adminOrOps() {
        if (role != StaffRole.ADMIN && role != StaffRole.OPS) {
            throw new ApiException("FORBIDDEN", HttpStatus.FORBIDDEN, "Forbidden");
        }
        return this;
    }

    /** 403 unless the caller has one of {@code roles} (e.g. refund: ADMIN only, 04 §Organizer Lot 3). */
    public OrgContext only(StaffRole... roles) {
        if (!Arrays.asList(roles).contains(role)) {
            throw new ApiException("FORBIDDEN", HttpStatus.FORBIDDEN, "Forbidden");
        }
        return this;
    }
}
