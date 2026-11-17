package ma.jomhor.tenancy.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * organizers.settings.finance (docs/03-schema.sql, plan lot-5 Q231): the fee contract and the organizer's legal and
 * bank details, set by Jomhor ops, never writable through /org. {@code rib} never leaves the server in full.
 */
public record FinanceSettings(String legalName, String address, String ice, String taxId, String rc, String rib,
                              int feeRateBp, LocalDate startOn) {

    /** The RIB masked to its last 4 digits: settings may reach a log through this. */
    @Override
    public String toString() {
        return "FinanceSettings[legalName=" + legalName + ", address=" + address + ", ice=" + ice + ", taxId=" + taxId
                + ", rc=" + rc + ", rib=****" + ribLast4() + ", feeRateBp=" + feeRateBp + ", startOn=" + startOn + "]";
    }

    public String ribLast4() {
        return rib.substring(rib.length() - 4);
    }

    /**
     * Valid = legal_name and address non-blank strings, ice 15 digits, rib 24 digits, fee_rate_bp an integer 0–10 000,
     * start_on a YYYY-MM-DD date; if and rc optional strings. Anything else → empty.
     */
    public static Optional<FinanceSettings> parse(JsonNode f) {
        if (f == null || !f.isObject()) {
            return Optional.empty();
        }
        String name = string(f, "legal_name");
        String address = string(f, "address");
        String ice = string(f, "ice");
        String rib = string(f, "rib");
        String start = string(f, "start_on");
        JsonNode rate = f.get("fee_rate_bp");
        if (name == null || name.isBlank() || address == null || address.isBlank() || ice == null
                || !ice.matches("\\d{15}") || rib == null || !rib.matches("\\d{24}") || start == null || rate == null
                || !rate.isIntegralNumber() || !rate.canConvertToInt() || rate.asInt() < 0 || rate.asInt() > 10_000
                || (f.has("if") && string(f, "if") == null) || (f.has("rc") && string(f, "rc") == null)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FinanceSettings(name, address, ice, string(f, "if"), string(f, "rc"), rib,
                    rate.asInt(), LocalDate.parse(start)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String string(JsonNode f, String key) {
        JsonNode v = f.get(key);
        return v != null && v.isString() ? v.asString() : null;
    }
}
