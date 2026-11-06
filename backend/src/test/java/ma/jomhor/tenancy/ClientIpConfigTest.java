package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

/**
 * Outside dev/test a missing TRUSTED_PROXIES_REGEX fails startup by name. Loads only the main application.yml
 * (the test application.properties sets the variable to empty, which is allowed: OtpSenderProfileTest starts so).
 */
class ClientIpConfigTest {

    @Test
    void missingTrustedProxiesRegexFailsStartupOutsideDevAndTest() {
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
                "--" + IntegrationTest.EVENT_KEY_MASTER).close())
                .hasStackTraceContaining("TRUSTED_PROXIES_REGEX must be set");
    }
}
