package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import ma.jomhor.payments.application.CardGateway;
import ma.jomhor.tenancy.application.OtpSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The simulated gateway exists only under dev, test and staging with payments.card.provider=fake. Without an
 * adapter there is no start (prod until BE-7, or no profile). Outside staging a stub OtpSender is registered so
 * the card gateway is the only bean missing. The provider, secret and base URL come from src/test/resources unless overridden.
 */
class CardGatewayProfileTest {

    private static final String MISSING = "No qualifying bean of type '" + CardGateway.class.getName() + "'";

    @Test
    void prodWithFakeRefusesToStart() {
        assertThatThrownBy(() -> start("prod").close()).hasStackTraceContaining(MISSING);
    }

    @Test
    void noProfileRefusesToStart() {
        assertThatThrownBy(() -> start("").close()).hasStackTraceContaining(MISSING);
    }

    @Test
    void prodAlongsideStagingStillRefusesTheFake() {
        assertThatThrownBy(() -> start("staging,prod").close())
                .hasStackTraceContaining("The simulated card gateway is refused under the prod profile");
    }

    @Test
    void stagingWithAnotherProviderHasNoGateway() {
        assertThatThrownBy(() -> start("staging", "--payments.card.provider=payzone").close())
                .hasStackTraceContaining(MISSING);
    }

    @Test
    void stagingNeedsTheFakeSecret() {
        assertThatThrownBy(() -> start("staging", "--payments.fake.secret=short").close())
                .hasStackTraceContaining("PAYMENTS_FAKE_SECRET must be at least 32 bytes")
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain("short\""));
    }

    @Test
    void stagingStartsWithTheSimulatedGateway() {
        try (var context = start("staging")) {
            assertThat(context.getBean(CardGateway.class).provider()).isEqualTo("FAKE");
        }
    }

    private static String stackTrace(Throwable e) {
        var w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    private static ConfigurableApplicationContext start(String profiles, String... extra) {
        var pg = IntegrationTest.POSTGRES;
        var app = new SpringApplication(JomhorApplication.class);
        if (!profiles.contains("staging")) { // staging has its own (log) OtpSender
            app.addInitializers(c -> c.getBeanFactory().registerSingleton("stubOtpSender", mock(OtpSender.class)));
        }
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=" + profiles,
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--" + IntegrationTest.JWT_SECRET,
                "--" + IntegrationTest.OTP_SECRET,
                "--" + IntegrationTest.EVENT_KEY_MASTER));
        args.addAll(List.of(extra));
        return app.run(args.toArray(String[]::new));
    }
}
