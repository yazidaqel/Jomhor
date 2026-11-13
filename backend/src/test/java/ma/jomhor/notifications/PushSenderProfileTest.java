package ma.jomhor.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import ma.jomhor.JomhorApplication;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.crm.application.CampaignService;
import ma.jomhor.crm.domain.Campaign;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.notifications.domain.FanMessage.Channel;
import ma.jomhor.payments.application.CardGateway;
import ma.jomhor.tenancy.application.OtpSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The log push sender is for dev and staging (notifications.push.provider=log) and refuses prod. Prod without a
 * provider starts with no PushSender, and its PUSH messages fail NOT_CONFIGURED (docs/04-api.md §Notifications).
 * Under prod, stubs stand in for the OtpSender, card gateway and TicketMessenger, which prod has no adapter for yet.
 */
class PushSenderProfileTest {

    @Test
    void prodRefusesTheLogSender() {
        assertThatThrownBy(() -> start("prod", "log").close())
                .hasStackTraceContaining("The log push sender is refused under the prod profile");
    }

    @Test
    void prodWithoutAProviderStartsAndFailsPushNotConfigured() {
        try (var context = start("prod", "")) {
            assertThat(context.getBeanProvider(PushSender.class).getIfAvailable()).isNull();
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            UUID org = jdbc.sql("insert into organizers (name) values ('Push') returning id").query(UUID.class).single();
            UUID user = jdbc.sql("insert into users (phone_e164) values (?) returning id")
                    .param("+2125" + "%08d".formatted((int) (Math.random() * 100_000_000))).query(UUID.class).single();
            // NOT_CONFIGURED is decided before the kind is rendered.
            UUID staff = jdbc.sql("insert into staff (organizer_id, name, role) values (?, 'Agent', 'OPS') returning id")
                    .param(org).query(UUID.class).single();
            UUID campaign = jdbc.sql("""
                            insert into campaigns (organizer_id, channel, audience, title, body, status, created_by)
                            values (?, 'PUSH', 'MEMBERS', 'Titre', 'Corps', 'SENDING', ?) returning id""")
                    .params(org, staff).query(UUID.class).single();
            UUID id = jdbc.sql("""
                            insert into fan_messages (organizer_id, user_id, channel, kind, campaign_id, created_at,
                                                      next_attempt_at)
                            values (?, ?, 'PUSH', 'CAMPAIGN', ?, now() - interval '1 day', now() - interval '1 day')
                            returning id""").params(org, user, campaign).query(UUID.class).single();
            context.getBean(FanMessageService.class).deliverDue();
            Map<String, Object> row = jdbc.sql("select status::text as status, last_error from fan_messages where id = ?")
                    .param(id).query().singleRow();
            assertThat(row).containsEntry("status", "FAILED").containsEntry("last_error", "NOT_CONFIGURED");

            // 04 POST /org/campaigns: a PUSH campaign → 422 on channel, NOT_CONFIGURED; WhatsApp isn't checked.
            jdbc.sql("insert into staff (organizer_id, user_id, name, role) values (?, ?, 'Admin', 'ADMIN')")
                    .params(org, user).update();
            CampaignService campaigns = context.getBean(CampaignService.class);
            assertThatThrownBy(() -> campaigns.create(org, user, new CampaignService.Input(Channel.PUSH,
                    Campaign.Audience.MEMBERS, null, "Titre", "Corps")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(422);
                        assertThat(e.details()).containsEntry("reason", "NOT_CONFIGURED")
                                .containsEntry("fields", Map.of("channel", "no push provider configured"));
                    });
            assertThat(campaigns.create(org, user, new CampaignService.Input(Channel.WHATSAPP,
                    Campaign.Audience.MEMBERS, null, "Titre", "Corps")).campaign().status())
                    .isEqualTo(Campaign.Status.DRAFT);
        }
    }

    @Test
    void stagingStartsWithTheLogSender() {
        try (var context = start("staging", "log")) {
            assertThat(context.getBean(PushSender.class).getClass().getSimpleName()).isEqualTo("LogPushSender");
        }
    }

    private static ConfigurableApplicationContext start(String profile, String provider) {
        var pg = IntegrationTest.POSTGRES;
        var app = new SpringApplication(JomhorApplication.class);
        if (profile.equals("prod")) {
            app.addInitializers(c -> {
                c.getBeanFactory().registerSingleton("stubOtpSender", mock(OtpSender.class));
                c.getBeanFactory().registerSingleton("stubCardGateway", mock(CardGateway.class));
                c.getBeanFactory().registerSingleton("stubTicketMessenger", mock(TicketMessenger.class));
            });
        }
        return app.run(
                "--spring.profiles.active=" + profile,
                "--server.port=0",
                "--notifications.push.provider=" + provider,
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--" + IntegrationTest.JWT_SECRET,
                "--" + IntegrationTest.OTP_SECRET,
                "--" + IntegrationTest.EVENT_KEY_MASTER);
    }
}
