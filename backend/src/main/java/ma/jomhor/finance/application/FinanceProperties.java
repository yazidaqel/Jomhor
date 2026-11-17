package ma.jomhor.finance.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * {@code finance.*} (plan lot-5 §13.4.4): the statement day's zone, the job period, the wait after midnight, the payout
 * delay, the VAT rate in basis points and Jomhor's legal mentions as invoice issuer.
 */
@ConfigurationProperties("finance")
public record FinanceProperties(String timezone, Duration tick, Duration issueDelay, int payoutDays, int vatRateBp,
                                Issuer issuer) {

    /** Copied into each invoice at issue (invoices.issuer, snake_case keys; {@code if} is the tax id). */
    public record Issuer(String legalName, String legalForm, String capital, String address, String rc,
                         @Name("if") @JsonProperty("if") String taxId, String tp, String ice) {}

    /**
     * Under prod (plan lot-5 Q230): every finance.issuer key set and ice 15 digits, else startup fails naming the keys
     * (the message names keys, never values).
     */
    public void checkIssuer() {
        Issuer i = issuer == null ? new Issuer(null, null, null, null, null, null, null, null) : issuer;
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("legal-name", i.legalName());
        keys.put("legal-form", i.legalForm());
        keys.put("capital", i.capital());
        keys.put("address", i.address());
        keys.put("rc", i.rc());
        keys.put("if", i.taxId());
        keys.put("tp", i.tp());
        keys.put("ice", i.ice());
        List<String> blank = keys.entrySet().stream().filter(e -> e.getValue() == null || e.getValue().isBlank())
                .map(e -> "finance.issuer." + e.getKey()).toList();
        if (!blank.isEmpty()) {
            throw new IllegalStateException("Required under the prod profile: " + String.join(", ", blank));
        }
        if (!i.ice().matches("[0-9]{15}")) {
            throw new IllegalStateException("finance.issuer.ice must be 15 digits under the prod profile");
        }
    }
}
