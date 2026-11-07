package ma.jomhor.payments.adapters.fakecash;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.common.Secrets;
import ma.jomhor.payments.adapters.web.Pages;
import ma.jomhor.payments.application.CardGateway.HttpCallback;
import ma.jomhor.payments.application.CashNetwork;
import ma.jomhor.payments.application.PaymentOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The simulated cash network (provider FAKE_CASH, plan Q44): a real adapter, not a mock. Its agent terminal is
 * /payments/cash/fake; its callback is form fields {@code pay_code, amount_mad, network_ref, signature}, the signature
 * hex HMAC-SHA256 over {@code pay_code|amount_mad|network_ref} with PAYMENTS_FAKE_SECRET (at least 32 bytes). Exists
 * only under dev, test and staging with payments.cash.networks containing fake; PaymentOptions refuses fake under prod.
 */
@Component
@Profile({"dev", "test", "staging"})
@Conditional(FakeCashNetwork.Listed.class)
public class FakeCashNetwork implements CashNetwork {

    /** payments.cash.networks contains fake. */
    static class Listed implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return PaymentOptions.names(context.getEnvironment().getProperty("payments.cash.networks")).contains("fake");
        }
    }

    private final byte[] secret;

    FakeCashNetwork(@Value("${payments.fake.secret:}") String secret) {
        this.secret = Secrets.atLeast32Bytes("PAYMENTS_FAKE_SECRET", secret);
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public String provider() {
        return "FAKE_CASH";
    }

    @Override
    public String displayName() {
        return "Réseau cash simulé";
    }

    @Override
    public CashVerdict verify(HttpCallback request) {
        Map<String, String> p = request.params();
        String code = p.get("pay_code");
        String amount = p.get("amount_mad");
        String ref = p.get("network_ref");
        String signature = p.get("signature");
        if (code == null || amount == null || ref == null || signature == null
                || (code + amount + ref).indexOf('|') >= 0) { // '|' joins the signed fields: never inside one
            return CashVerdict.rejected();
        }
        byte[] expected = sign(code, amount, ref).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8))) {
            return CashVerdict.rejected();
        }
        try {
            return new CashVerdict(true, code, Long.parseLong(amount), ref);
        } catch (NumberFormatException e) { // signed with our key but malformed: not something we sent
            return CashVerdict.rejected();
        }
    }

    /** The terminal's browser posted the callback: an FR page for the agent. */
    @Override
    public ResponseEntity<String> acknowledge(CashVerdict verdict, Outcome outcome) {
        String again = "<p><a href=\"../fake\">Nouveau code</a></p>";
        return switch (outcome) {
            case ACCEPTED -> Pages.html(HttpStatus.OK, "Encaissement enregistré",
                    "<h1>Encaissement enregistré</h1>" + again);
            case ALREADY -> Pages.html(HttpStatus.OK, "Encaissement enregistré",
                    "<h1>Encaissement déjà enregistré</h1>" + again);
            case UNKNOWN -> Pages.html(HttpStatus.NOT_FOUND, "Code inconnu",
                    "<h1>Code inconnu ou non payable</h1>" + again);
            case REFUSED -> Pages.html(HttpStatus.CONFLICT, "Encaissement refusé",
                    "<h1>Encaissement refusé</h1>" + again);
        };
    }

    /** Hex HMAC-SHA256 of the callback fields. */
    String sign(String payCode, String amountMad, String networkRef) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] tag = mac.doFinal(String.join("|", payCode, amountMad, networkRef).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(tag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
