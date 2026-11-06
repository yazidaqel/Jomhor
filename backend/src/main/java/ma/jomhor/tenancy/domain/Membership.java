package ma.jomhor.tenancy.domain;

import java.util.UUID;

public record Membership(UUID organizerId, String organizerName, StaffRole role) {}
