package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.payments.application.CardGateway;
import ma.jomhor.payments.application.PaymentOptions;
import ma.jomhor.tenancy.application.OtpSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The simulated gateway exists only under dev, test and staging with payments.card.provider=fake. Without an
 * adapter there is no start (prod until BE-7, or no profile). Outside staging a stub OtpSender and a stub
 * TicketMessenger are registered so the card gateway is the only bean missing. The provider, secret and base URL come from src/test/resources unless overridden.
 * The same rule for the simulated cash network and wallet (BE-24): a stub gateway lets prod reach PaymentOptions.
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

    // ---------- cash network and wallet (plan Q44, Q47) ----------

    @Test
    void prodRefusesTheSimulatedCashNetwork() {
        assertThatThrownBy(() -> start("prod", true, "--payments.cash.networks=fake").close())
                .hasStackTraceContaining("The simulated cash network is refused under the prod profile");
    }

    @Test
    void prodRefusesTheSimulatedWallet() {
        assertThatThrownBy(() -> start("prod", true, "--payments.wallet.provider=fake").close())
                .hasStackTraceContaining("The simulated wallet is refused under the prod profile");
    }

    @Test
    void prodAlongsideStagingStillRefusesTheSimulatedNetwork() {
        assertThatThrownBy(() -> start("staging,prod", true, "--payments.card.provider=", "--payments.cash.networks=fake")
                .close()).hasStackTraceContaining("The simulated cash network is refused under the prod profile");
    }

    @Test
    void aNetworkWithoutAnAdapterRefusesToStart() {
        assertThatThrownBy(() -> start("staging", "--payments.cash.networks=fake, wafacash").close())
                .hasStackTraceContaining("No adapter for cash network wafacash");
    }

    @Test
    void aWalletProviderMustBeTheCardGateways() {
        assertThatThrownBy(() -> start("staging", "--payments.wallet.provider=orange").close())
                .hasStackTraceContaining("payments.wallet.provider must be the card gateway's provider");
    }

    @Test
    void stagingStartsWithTheSimulatedNetworkAndWallet() {
        try (var context = start("staging", "--payments.cash.networks=fake", "--payments.wallet.provider=fake")) {
            PaymentOptions options = context.getBean(PaymentOptions.class);
            assertThat(options.cashNetworks()).containsExactly("Réseau cash simulé");
            assertThat(options.walletConfigured()).isTrue();
            assertThat(options.network("fake")).isPresent();
        }
        try (var context = start("staging")) { // unset: neither
            PaymentOptions options = context.getBean(PaymentOptions.class);
            assertThat(options.cashNetworks()).isEmpty();
            assertThat(options.walletConfigured()).isFalse();
            assertThat(options.network("fake")).isEmpty();
        }
    }

    private static String stackTrace(Throwable e) {
        var w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    private static ConfigurableApplicationContext start(String profiles, String... extra) {
        return start(profiles, false, extra);
    }

    /** {@code stubGateway}: a mock CardGateway, so prod gets past the missing adapter to the cash and wallet checks. */
    private static ConfigurableApplicationContext start(String profiles, boolean stubGateway, String... extra) {
        var pg = IntegrationTest.POSTGRES;
        var app = new SpringApplication(JomhorApplication.class);
        if (!profiles.contains("staging")) { // staging has its own (log) OtpSender and TicketMessenger
            app.addInitializers(c -> {
                c.getBeanFactory().registerSingleton("stubOtpSender", mock(OtpSender.class));
                c.getBeanFactory().registerSingleton("stubTicketMessenger", mock(TicketMessenger.class));
            });
        }
        if (stubGateway) {
            app.addInitializers(c -> c.getBeanFactory().registerSingleton("stubCardGateway", mock(CardGateway.class)));
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
