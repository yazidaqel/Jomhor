package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.payments.application.CardGateway;
import ma.jomhor.tenancy.application.OtpSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The log sender prints codes: only dev and staging get it. Anywhere else there is no sender yet, so no start.
 * Under prod and no profile a stub card gateway and a stub ticket messenger are registered, so the OtpSender is the
 * only bean missing.
 */
class OtpSenderProfileTest {

    @Test
    void prodRefusesToStart() {
        assertThatThrownBy(() -> start("prod", true).close())
                .hasStackTraceContaining("No qualifying bean of type '" + OtpSender.class.getName() + "'");
    }

    @Test
    void noProfileRefusesToStart() {
        assertThatThrownBy(() -> start(null, true).close())
                .hasStackTraceContaining("No qualifying bean of type '" + OtpSender.class.getName() + "'");
    }

    @Test
    void stagingStartsWithTheLogSender() {
        try (var context = start("staging", false)) {
            assertThat(context.getBean(OtpSender.class).getClass().getSimpleName()).isEqualTo("LogOtpSender");
        }
    }

    private static ConfigurableApplicationContext start(String profile, boolean stubCardGateway) {
        var pg = IntegrationTest.POSTGRES;
        var app = new SpringApplication(JomhorApplication.class);
        if (stubCardGateway) {
            app.addInitializers(c -> {
                c.getBeanFactory().registerSingleton("stubCardGateway", mock(CardGateway.class));
                c.getBeanFactory().registerSingleton("stubTicketMessenger", mock(TicketMessenger.class));
            });
        }
        return app.run(
                "--spring.profiles.active=" + (profile == null ? "" : profile),
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--jomhor.jwt-secret=test-only-jwt-secret-0123456789abcdef",
                "--jomhor.otp-secret=test-only-otp-secret-0123456789abcdef",
                "--" + IntegrationTest.EVENT_KEY_MASTER);
    }
}
