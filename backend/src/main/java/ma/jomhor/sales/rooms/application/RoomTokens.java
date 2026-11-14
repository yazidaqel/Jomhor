package ma.jomhor.sales.rooms.application;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.common.Secrets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Room tokens (docs/04-api.md §Waiting room Queue token, Admission): JWT HS256 under ROOMS_TOKEN_SECRET, never the fan
 * JWT secret, so neither kind is ever accepted for the other. Claims are ids, a rank and an expiry: no phone, name or
 * device id. The same secret keys the device and IP hashes. Nimbus compares tags in constant time.
 */
@Component
public class RoomTokens {

    static final String ISSUER = "jomhor";
    static final String QUEUE = "queue";
    static final String ADMISSION = "admission";

    /** A verified queue token: {@code rank} null until ranked; {@code expiresAt} = the event's refund_cutoff_at. */
    public record Queue(UUID entryId, int eventId, Integer rank, Instant expiresAt) {}

    /** A signed admission token's claims; its expiry is the caller's to check (EXPIRED vs INVALID). */
    public record Admission(UUID userId, int eventId, Instant expiresAt) {}

    private final byte[] secret;
    private final MACSigner signer;
    private final MACVerifier verifier;

    RoomTokens(@Value("${rooms.token-secret}") String secret) throws JOSEException {
        this.secret = Secrets.atLeast32Bytes("ROOMS_TOKEN_SECRET", secret);
        this.signer = new MACSigner(this.secret);
        this.verifier = new MACVerifier(this.secret);
    }

    /** {@code iss, aud: queue, sub} = entry id, {@code evt}, {@code rnk} (only once ranked), {@code exp}. */
    public String queue(UUID entryId, int eventId, Integer rank, Instant expiresAt) {
        JWTClaimsSet.Builder claims = base(QUEUE, entryId.toString(), eventId, expiresAt).issuer(ISSUER);
        if (rank != null) {
            claims.claim("rnk", rank);
        }
        return sign(claims.build());
    }

    /** {@code aud: admission, sub} = user id, {@code evt}, {@code ent} = entry id, {@code exp} (04 lists no iss). */
    public String admission(UUID userId, int eventId, UUID entryId, Instant expiresAt) {
        return sign(base(ADMISSION, userId.toString(), eventId, expiresAt).claim("ent", entryId.toString()).build());
    }

    /** Empty unless HS256 under this secret, {@code iss}, {@code aud} exactly queue, and {@code exp} after {@code now}. */
    public Optional<Queue> readQueue(String token, Instant now) {
        try {
            JWTClaimsSet c = verified(token, QUEUE);
            if (c == null || !ISSUER.equals(c.getIssuer()) || !now.isBefore(c.getExpirationTime().toInstant())) {
                return Optional.empty();
            }
            return Optional.of(new Queue(UUID.fromString(c.getSubject()), c.getIntegerClaim("evt"),
                    c.getIntegerClaim("rnk"), c.getExpirationTime().toInstant()));
        } catch (ParseException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Empty unless HS256 under this secret with {@code aud} exactly admission; an expired one is returned. */
    public Optional<Admission> readAdmission(String token) {
        try {
            JWTClaimsSet c = verified(token, ADMISSION);
            return c == null ? Optional.empty() : Optional.of(new Admission(UUID.fromString(c.getSubject()),
                    c.getIntegerClaim("evt"), c.getExpirationTime().toInstant()));
        } catch (ParseException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The claims if HS256 under this secret, {@code aud} exactly {@code audience}, with exp, evt and sub; else null. */
    private JWTClaimsSet verified(String token, String audience) throws ParseException {
        SignedJWT jwt = SignedJWT.parse(token);
        try {
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                return null;
            }
        } catch (JOSEException e) {
            return null;
        }
        JWTClaimsSet c = jwt.getJWTClaimsSet();
        return List.of(audience).equals(c.getAudience()) && c.getExpirationTime() != null
                && c.getIntegerClaim("evt") != null && c.getSubject() != null ? c : null;
    }

    /** HMAC-SHA256(ROOMS_TOKEN_SECRET, {@code value}): 32 bytes, for "device|…" and "ip|…". */
    public byte[] hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet.Builder base(String audience, String subject, int eventId, Instant expiresAt) {
        return new JWTClaimsSet.Builder().audience(audience).subject(subject).claim("evt", eventId)
                .expirationTime(Date.from(expiresAt.truncatedTo(ChronoUnit.SECONDS)));
    }

    private String sign(JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }
}
