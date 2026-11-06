package ma.jomhor.tenancy.domain;

import java.util.UUID;

/**
 * Who calls an {@code /org/**} endpoint, for which organizer. Set per request by the
 * X-Organizer-Id check; controllers read it with {@code @RequestAttribute(OrgContext.ATTRIBUTE)}.
 * Tenant queries take {@link #organizerId()} from here, never from the body.
 */
public record OrgContext(UUID organizerId, UUID userId, StaffRole role) {

    public static final String ATTRIBUTE = "ma.jomhor.orgContext";
}
