package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import ma.jomhor.tenancy.application.OtpSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** The log sender prints codes: only dev and staging get it. Anywhere else there is no sender yet, so no start. */
class OtpSenderProfileTest {

    @Test
    void prodRefusesToStart() {
        assertThatThrownBy(() -> start("prod").close())
                .hasStackTraceContaining("No qualifying bean of type '" + OtpSender.class.getName() + "'");
    }

    @Test
    void noProfileRefusesToStart() {
        assertThatThrownBy(() -> start(null).close())
                .hasStackTraceContaining("No qualifying bean of type '" + OtpSender.class.getName() + "'");
    }

    @Test
    void stagingStartsWithTheLogSender() {
        try (var context = start("staging")) {
            assertThat(context.getBean(OtpSender.class).getClass().getSimpleName()).isEqualTo("LogOtpSender");
        }
    }

    private static ConfigurableApplicationContext start(String profile) {
        var pg = IntegrationTest.POSTGRES;
        return SpringApplication.run(JomhorApplication.class,
                "--spring.profiles.active=" + (profile == null ? "" : profile),
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--jomhor.jwt-secret=test-only-jwt-secret-0123456789abcdef",
                "--jomhor.otp-secret=test-only-otp-secret-0123456789abcdef");
    }
}
