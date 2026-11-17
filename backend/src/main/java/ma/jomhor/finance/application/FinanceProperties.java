package ma.jomhor.finance.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
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
}
