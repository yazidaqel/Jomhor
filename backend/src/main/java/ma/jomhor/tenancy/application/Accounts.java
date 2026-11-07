package ma.jomhor.tenancy.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.tenancy.domain.Membership;
import ma.jomhor.tenancy.domain.StaffMember;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tenancy.domain.User;

/** Persistence port for users (platform table) and staff (tenant table). */
public interface Accounts {

    /** The user with this phone, created on first login. */
    User upsertByPhone(String phone);

    Optional<User> findUser(UUID userId);

    /** Sets the fields given (null = unchanged; an empty display name clears it); the user after the change. */
    Optional<User> updateProfile(UUID userId, String displayName, String locale);

    Optional<UUID> findUserIdByPhone(String phone);

    boolean hasActiveStaff(String phone);

    /** Active staff rows of one user across organizers (the organizer switcher). */
    List<Membership> activeMemberships(UUID userId);

    Optional<StaffRole> activeRole(UUID organizerId, UUID userId);

    // ---------- staff of one organizer (/org/staff, scanner staff list) ----------

    /** Every staff row, by name. */
    List<StaffMember> staff(UUID organizerId);

    /** A PIN-only scanner staff row (no user), active. */
    StaffMember insertScannerStaff(UUID organizerId, String name, StaffRole role, String pinVerifier);

    /** GATE/SUPERVISOR rows only; null = unchanged. Empty if no such row of this organizer. */
    Optional<StaffMember> updateScannerStaff(UUID organizerId, UUID staffId, String name, Boolean active);

    /** GATE/SUPERVISOR rows only; false if no such row of this organizer. */
    boolean setScannerPin(UUID organizerId, UUID staffId, String pinVerifier);

    /** Active GATE/SUPERVISOR rows with a PIN, by name: what the organizer's scanners get. */
    List<StaffMember> activeScannerStaff(UUID organizerId);
}
