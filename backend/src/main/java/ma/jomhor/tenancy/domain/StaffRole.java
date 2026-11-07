package ma.jomhor.tenancy.domain;

public enum StaffRole {
    ADMIN, OPS, GATE, SUPERVISOR, FINANCE,
    /** Point of sale only (docs/04-api.md §Point of sale): back-office OTP login, no PIN. */
    CASHIER
}
