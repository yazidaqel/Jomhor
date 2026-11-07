package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.payments.application.CardGateway;
import ma.jomhor.tenancy.application.OtpSender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    /**
     * The dev fixed OTP: a profile set other than exactly dev refuses to start (DevSeederTest: dev accepts it). Prod
     * gets stubs for the beans it lacks (an OtpSender too), so the fixed OTP is the only reason left. No dev profile
     * here: it would apply the dev seed to the shared container.
     */
    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void fixedOtpRefusedOutsideDev(String profile) {
        assertThatThrownBy(() -> start(profile, profile.equals("prod"), "--jomhor.dev.fixed-otp=123456").close())
                .hasStackTraceContaining("jomhor.dev.fixed-otp is allowed under the dev profile only");
    }

    private static ConfigurableApplicationContext start(String profile, boolean stubs, String... extra) {
        var pg = IntegrationTest.POSTGRES;
        var app = new SpringApplication(JomhorApplication.class);
        if (stubs) {
            app.addInitializers(c -> {
                c.getBeanFactory().registerSingleton("stubCardGateway", mock(CardGateway.class));
                c.getBeanFactory().registerSingleton("stubTicketMessenger", mock(TicketMessenger.class));
                if (extra.length > 0) { // only the fixed OTP may stop it
                    c.getBeanFactory().registerSingleton("stubOtpSender", mock(OtpSender.class));
                }
            });
        }
        var args = new ArrayList<>(List.of(
                "--spring.profiles.active=" + (profile == null ? "" : profile),
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--jomhor.jwt-secret=test-only-jwt-secret-0123456789abcdef",
                "--jomhor.otp-secret=test-only-otp-secret-0123456789abcdef",
                "--" + IntegrationTest.EVENT_KEY_MASTER));
        args.addAll(List.of(extra));
        return app.run(args.toArray(String[]::new));
    }
}
