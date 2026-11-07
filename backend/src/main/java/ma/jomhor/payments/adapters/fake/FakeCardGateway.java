package ma.jomhor.payments.adapters.fake;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.common.Secrets;
import ma.jomhor.payments.application.CardGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The simulated gateway (provider FAKE, plan §4.5): a real adapter, not a mock. Its hosted page is
 * /payments/fake/{id}; its callback is form fields signed with HMAC-SHA256 over
 * {@code payment_id|outcome|amount_mad|provider_ref} with PAYMENTS_FAKE_SECRET (≥ 32 bytes). Exists only under
 * dev, test and staging with payments.card.provider=fake, and refuses to start under prod.
 */
@Component
@Profile({"dev", "test", "staging"})
@ConditionalOnProperty(name = "payments.card.provider", havingValue = "fake")
public class FakeCardGateway implements CardGateway {

    static final String APPROVED = "APPROVED";
    static final String DECLINED = "DECLINED";

    private final byte[] secret;
    private final String baseUrl;

    FakeCardGateway(@Value("${payments.fake.secret:}") String secret,
                    @Value("${payments.public-base-url}") String baseUrl, Environment env) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("The simulated card gateway is refused under the prod profile");
        }
        this.secret = Secrets.atLeast32Bytes("PAYMENTS_FAKE_SECRET", secret);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String provider() {
        return "FAKE";
    }

    @Override
    public Checkout checkout(PaymentRef payment) {
        return new Checkout(URI.create(baseUrl + "/payments/fake/" + payment.paymentId()), Map.of());
    }

    @Override
    public CallbackVerdict verify(HttpCallback request) {
        Map<String, String> p = request.params();
        String id = p.get("payment_id");
        String outcome = p.get("outcome");
        String amount = p.get("amount_mad");
        String ref = p.get("provider_ref");
        String signature = p.get("signature");
        if (id == null || outcome == null || amount == null || ref == null || signature == null
                || (id + outcome + amount + ref).indexOf('|') >= 0) { // '|' joins the signed fields: never inside one
            return CallbackVerdict.rejected();
        }
        byte[] expected = sign(id, outcome, amount, ref).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8))) {
            return CallbackVerdict.rejected();
        }
        try {
            boolean approved = switch (outcome) {
                case APPROVED -> true;
                case DECLINED -> false;
                default -> throw new IllegalArgumentException("outcome");
            };
            return new CallbackVerdict(true, UUID.fromString(id), approved, ref, Long.parseLong(amount));
        } catch (IllegalArgumentException e) { // signed with our key but malformed: not something we sent
            return CallbackVerdict.rejected();
        }
    }

    /** The callback came through the buyer's browser: send it on to our /return page. */
    @Override
    public ResponseEntity<String> acknowledge(CallbackVerdict verdict) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(baseUrl + "/payments/card/" + verdict.paymentId() + "/return")).build();
    }

    /** Hex HMAC-SHA256 of the callback fields. */
    String sign(String paymentId, String outcome, String amountMad, String providerRef) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] tag = mac.doFinal(String.join("|", paymentId, outcome, amountMad, providerRef)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(tag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A callback as the hosted page posts it. */
    HttpCallback callback(UUID paymentId, String outcome, long amountMad, String providerRef) {
        String id = paymentId.toString();
        String amount = Long.toString(amountMad);
        return new HttpCallback(Map.of("payment_id", id, "outcome", outcome, "amount_mad", amount,
                "provider_ref", providerRef, "signature", sign(id, outcome, amount, providerRef)));
    }
}
