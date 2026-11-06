package ma.jomhor.tenancy.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.tenancy.domain.Membership;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tenancy.domain.User;

/** Persistence port for users (platform table) and staff (tenant table). */
public interface Accounts {

    /** The user with this phone, created on first login. */
    User upsertByPhone(String phone);

    Optional<User> findUser(UUID userId);

    Optional<UUID> findUserIdByPhone(String phone);

    boolean hasActiveStaff(String phone);

    /** Active staff rows of one user across organizers (the organizer switcher). */
    List<Membership> activeMemberships(UUID userId);

    Optional<StaffRole> activeRole(UUID organizerId, UUID userId);
}
