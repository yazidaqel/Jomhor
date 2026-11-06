package ma.jomhor.notifications;

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
 * The log messenger is for dev and staging only. Under prod there is no TicketMessenger until BE-15, so no start.
 * Under prod a stub OtpSender and card gateway are registered, so the TicketMessenger is the only bean missing.
 */
class TicketMessengerProfileTest {

    @Test
    void prodWithoutAMessengerRefusesToStart() {
        assertThatThrownBy(() -> start("prod", true).close())
                .hasStackTraceContaining("No qualifying bean of type '" + TicketMessenger.class.getName() + "'");
    }

    @Test
    void stagingStartsWithTheLogMessenger() {
        try (var context = start("staging", false)) {
            assertThat(context.getBean(TicketMessenger.class).getClass().getSimpleName())
                    .isEqualTo("LogTicketMessenger");
        }
    }

    private static ConfigurableApplicationContext start(String profile, boolean stubs) {
        var pg = IntegrationTest.POSTGRES;
        var app = new SpringApplication(JomhorApplication.class);
        if (stubs) {
            app.addInitializers(c -> {
                c.getBeanFactory().registerSingleton("stubOtpSender", mock(OtpSender.class));
                c.getBeanFactory().registerSingleton("stubCardGateway", mock(CardGateway.class));
            });
        }
        return app.run(
                "--spring.profiles.active=" + profile,
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--" + IntegrationTest.JWT_SECRET,
                "--" + IntegrationTest.OTP_SECRET,
                "--" + IntegrationTest.EVENT_KEY_MASTER);
    }
}
