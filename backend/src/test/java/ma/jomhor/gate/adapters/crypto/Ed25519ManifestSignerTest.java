package ma.jomhor.gate.adapters.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

/** GATE_MANIFEST_SIGNING_KEY: PKCS#8 Ed25519, public key derived as OpenSSL does, startup fails without a valid one. */
class Ed25519ManifestSignerTest {

    /** The test key of src/test/resources/application.properties and its public key, both from OpenSSL. */
    static final String TEST_KEY = "MC4CAQAwBQYDK2VwBCIEIL+e1GK5oIgWytc6DivLU7RK4h6MpYvqcLDohcW8gl2Y";
    static final String TEST_PUBLIC = "9657JB-9nYlMlNMM2AYY8UzKMLcbfTkI3UKd6SDuEDc";

    @Test
    void publicKeyMatchesOpenSslAndSignaturesVerify() throws Exception {
        var signer = new Ed25519ManifestSigner(TEST_KEY);
        byte[] pub = signer.publicKey();
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(pub)).isEqualTo(TEST_PUBLIC);

        byte[] payload = "JMH-MANIFEST\t1\n".getBytes(StandardCharsets.UTF_8);
        byte[] sig = signer.sign(payload);
        assertThat(sig).hasSize(64).isEqualTo(signer.sign(payload)); // Ed25519 is deterministic
        var verify = Signature.getInstance("Ed25519");
        verify.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                HexFormat.of().parseHex("302a300506032b6570032100" + HexFormat.of().formatHex(pub)))));
        verify.update(payload);
        assertThat(verify.verify(sig)).isTrue();
    }

    @Test
    void badKeysFailByNameWithoutEchoingTheValue() {
        String p256 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg"; // truncated EC PKCS#8
        for (String bad : new String[] {"", "not base64!", p256, Base64.getEncoder().encodeToString(new byte[48])}) {
            assertThatThrownBy(() -> new Ed25519ManifestSigner(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("GATE_MANIFEST_SIGNING_KEY must be base64 of an Ed25519 PKCS#8 private key")
                    .hasNoCause();
        }
    }

    @Test
    void stagingStartupFailsWithoutTheKey() {
        var pg = IntegrationTest.POSTGRES;
        assertThatThrownBy(() -> SpringApplication.run(JomhorApplication.class,
                "--spring.profiles.active=staging",
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--" + IntegrationTest.JWT_SECRET,
                "--" + IntegrationTest.OTP_SECRET,
                "--" + IntegrationTest.EVENT_KEY_MASTER,
                "--jomhor.gate.manifest-signing-key=").close())
                .hasStackTraceContaining("GATE_MANIFEST_SIGNING_KEY must be base64 of an Ed25519 PKCS#8 private key");
    }

    /**
     * Absent, not empty: only the main application.yml is loaded (the test application.properties sets the key), every
     * other required value is given, so the unresolved ${GATE_MANIFEST_SIGNING_KEY} is what fails.
     */
    @Test
    void startupFailsWhenTheKeyIsAbsent() {
        var pg = IntegrationTest.POSTGRES;
        assertThatThrownBy(() -> SpringApplication.run(JomhorApplication.class,
                "--spring.config.location=classpath:/application.yml",
                "--spring.profiles.active=staging",
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--" + IntegrationTest.JWT_SECRET,
                "--" + IntegrationTest.OTP_SECRET,
                "--" + IntegrationTest.EVENT_KEY_MASTER,
                "--TRUSTED_PROXIES_REGEX=",
                "--SHOP_ORIGIN=https://shop.test",
                "--payments.card.provider=fake",
                "--payments.public-base-url=http://localhost/v1",
                "--payments.fake.secret=test-only-payments-fake-secret-0123456789",
                "--payments.return-url.web=http://shop.test/orders/{order_id}",
                "--payments.return-url.app=jomhor-fan://orders/{order_id}").close())
                .hasStackTraceContaining("Could not resolve placeholder 'GATE_MANIFEST_SIGNING_KEY'");
    }
}
