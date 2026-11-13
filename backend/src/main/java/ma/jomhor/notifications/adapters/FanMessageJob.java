package ma.jomhor.notifications.adapters;

import ma.jomhor.notifications.application.FanMessageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs FanMessageService.deliverDue on {@code jomhor.notifications.message-cron} (every 5 s; "-" = off). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class FanMessageJob {

    private final FanMessageService messages;

    FanMessageJob(FanMessageService messages) {
        this.messages = messages;
    }

    @Scheduled(cron = "${jomhor.notifications.message-cron:*/5 * * * * *}")
    void deliver() {
        messages.deliverDue();
    }
}
