package ma.jomhor.gate.adapters.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import ma.jomhor.gate.application.ManifestSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ed25519 key from env GATE_MANIFEST_SIGNING_KEY: standard base64 of a PKCS#8 private key. application.yml has no
 * default, so any profile without it fails startup (dev has a dev-only key in application-dev.yml, tests their own).
 * Messages name the variable only; exceptions never carry key material.
 */
@Component
class Ed25519ManifestSigner implements ManifestSigner {

    static final String VARIABLE = "GATE_MANIFEST_SIGNING_KEY";

    private final PrivateKey privateKey;
    private final byte[] publicKey;

    Ed25519ManifestSigner(@Value("${jomhor.gate.manifest-signing-key}") String pkcs8Base64) {
        byte[] seed = null;
        try {
            byte[] der = Base64.getDecoder().decode(pkcs8Base64.strip());
            privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
            Arrays.fill(der, (byte) 0);
            seed = ((EdECPrivateKey) privateKey).getBytes().orElseThrow();
            publicKey = publicKeyOf(seed);
        } catch (GeneralSecurityException | RuntimeException e) {
            // not chained: the cause may quote the input
            throw new IllegalStateException(VARIABLE + " must be base64 of an Ed25519 PKCS#8 private key");
        } finally {
            if (seed != null) {
                Arrays.fill(seed, (byte) 0);
            }
        }
    }

    @Override
    public byte[] publicKey() {
        return publicKey.clone();
    }

    @Override
    public byte[] sign(byte[] payload) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privateKey);
            s.update(payload);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /**
     * The JDK derives no public key from a private one, but its Ed25519 generator turns the 32 bytes it draws into
     * the private key: feeding it the seed yields the matching public key (X.509: 12-byte prefix + 32 raw bytes).
     */
    private static byte[] publicKeyOf(byte[] seed) throws GeneralSecurityException {
        var gen = KeyPairGenerator.getInstance("Ed25519");
        gen.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                System.arraycopy(seed, 0, bytes, 0, bytes.length);
            }
        });
        byte[] x509 = gen.generateKeyPair().getPublic().getEncoded();
        return Arrays.copyOfRange(x509, x509.length - 32, x509.length);
    }
}
