package ma.jomhor.tenancy.domain;

/** Where fans pay a cash code in person: one entry of organizers.settings.cash_points (docs/03-schema.sql). */
public record CashPoint(String name, String address, String hours) {}
