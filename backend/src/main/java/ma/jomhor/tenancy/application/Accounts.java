package ma.jomhor.tenancy.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.tenancy.domain.CashPoint;
import ma.jomhor.tenancy.domain.LoyaltyTier;
import ma.jomhor.tenancy.domain.Membership;
import ma.jomhor.tenancy.domain.Organizer;
import ma.jomhor.tenancy.domain.StaffMember;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tenancy.domain.User;

/** Persistence port for users (platform table), staff (tenant table) and organizer settings. */
public interface Accounts {

    /** The user with this phone, created on first login. */
    User upsertByPhone(String phone);

    /** A successful fan OTP login: {@link #upsertByPhone}, and users.verified_at set if it never was. */
    User fanLogin(String phone);

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

    /** A CASHIER row of this user, active; empty if the user is already staff of this organizer. */
    Optional<StaffMember> insertCashier(UUID organizerId, UUID userId, String name);

    /** GATE/SUPERVISOR/CASHIER rows only; null = unchanged. Empty if no such row of this organizer. */
    Optional<StaffMember> updateScannerStaff(UUID organizerId, UUID staffId, String name, Boolean active);

    /** GATE/SUPERVISOR rows only; false if no such row of this organizer. */
    boolean setScannerPin(UUID organizerId, UUID staffId, String pinVerifier);

    /** Active GATE/SUPERVISOR rows with a PIN, by name: what the organizer's scanners get. */
    List<StaffMember> activeScannerStaff(UUID organizerId);

    /** organizers.settings.cash_points: object entries with a name, in order; anything else is skipped. */
    List<CashPoint> cashPoints(UUID organizerId);

    /**
     * organizers.settings.loyalty: {@code present} = the key is set; {@code tiers} = the entries of its tiers array in
     * order (empty when not an array), unchecked. Empty if no such organizer.
     */
    Optional<LoyaltySetting> loyalty(UUID organizerId);

    record LoyaltySetting(boolean present, List<LoyaltyTier> tiers) {}

    boolean organizerExists(UUID organizerId);

    /** Organizers with a slug (the club directory), by name then id. */
    List<Organizer> listedOrganizers();

    /** The listed organizer with this slug; empty if none. */
    Optional<Organizer> listedOrganizer(String slug);
}
