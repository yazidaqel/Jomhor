package ma.jomhor.tenancy.domain;

import java.util.UUID;

/**
 * A staff row of one organizer. {@code pinVerifier} is the PBKDF2 verifier ({@link Pins}) of GATE/SUPERVISOR rows,
 * null for back-office staff; it goes to the organizer's scanners only, never to /org responses.
 */
public record StaffMember(UUID id, String name, StaffRole role, boolean active, String pinVerifier) {

    public boolean hasPin() {
        return pinVerifier != null;
    }

    /** Without the verifier: a 4-digit PIN falls to it in milliseconds, so it stays out of logs like the PIN. */
    @Override
    public String toString() {
        return "StaffMember[id=" + id + ", role=" + role + ", active=" + active + ", hasPin=" + hasPin() + "]";
    }
}
