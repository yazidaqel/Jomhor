package ma.jomhor.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import ma.jomhor.finance.application.FinanceProperties;
import ma.jomhor.finance.application.SettlementService;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.payments.application.CardGateway;
import ma.jomhor.tenancy.application.OtpSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * BE-71: under prod, startup fails unless every finance.issuer key is set and its ICE is 15 digits (plan lot-5 Q230,
 * §13.4.4). A stub card gateway, OTP sender and ticket messenger let prod reach the check (as CardGatewayProfileTest);
 * the issuer otherwise comes from src/test/resources. The dev profile's demo issuer passes the same rule.
 */
class FinanceConfigTest {

    @Test
    void prodWithABlankIssuerIceRefusesToStart() {
        assertThatThrownBy(() -> prod("--finance.issuer.ice=").close())
                .hasStackTraceContaining("Required under the prod profile: finance.issuer.ice");
    }

    @Test
    void prodNamesEveryBlankKey() {
        assertThatThrownBy(() -> prod("--finance.issuer.legal-name= ", "--finance.issuer.if=", "--finance.issuer.tp=")
                .close()).hasStackTraceContaining(
                        "Required under the prod profile: finance.issuer.legal-name, finance.issuer.if, finance.issuer.tp");
    }

    @Test
    void prodRefusesAnIceThatIsNotFifteenDigits() {
        assertThatThrownBy(() -> prod("--finance.issuer.ice=44444444444444").close())
                .hasStackTraceContaining("finance.issuer.ice must be 15 digits under the prod profile");
        assertThatThrownBy(() -> prod("--finance.issuer.ice=44444444444444A").close())
                .hasStackTraceContaining("finance.issuer.ice must be 15 digits under the prod profile");
    }

    @Test
    void prodWithTheFullIssuerStarts() {
        try (var context = prod()) {
            assertThat(context.getBean(SettlementService.class)).isNotNull();
        }
    }

    @Test
    void theDevDemoIssuerPassesTheProdRule() throws Exception {
        var env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("dev", new ClassPathResource("application-dev.yml"))
                .forEach(env.getPropertySources()::addFirst);
        FinanceProperties.Issuer issuer = new Binder(ConfigurationPropertySources.get(env),
                new PropertySourcesPlaceholdersResolver(env))
                .bind("finance.issuer", FinanceProperties.Issuer.class).get();
        assertThat(issuer.ice()).hasSize(15);
        assertThatCode(() -> new FinanceProperties("Africa/Casablanca", null, null, 2, 2000, issuer).checkIssuer())
                .doesNotThrowAnyException();
    }

    private static ConfigurableApplicationContext prod(String... extra) {
        var pg = IntegrationTest.POSTGRES;
        var app = new SpringApplication(JomhorApplication.class);
        app.addInitializers(c -> {
            c.getBeanFactory().registerSingleton("stubOtpSender", mock(OtpSender.class));
            c.getBeanFactory().registerSingleton("stubTicketMessenger", mock(TicketMessenger.class));
            c.getBeanFactory().registerSingleton("stubCardGateway", mock(CardGateway.class));
        });
        List<String> args = new ArrayList<>(List.of("--spring.profiles.active=prod", "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(), "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(), "--" + IntegrationTest.JWT_SECRET,
                "--" + IntegrationTest.OTP_SECRET, "--" + IntegrationTest.EVENT_KEY_MASTER));
        args.addAll(List.of(extra));
        return app.run(args.toArray(String[]::new));
    }
}
