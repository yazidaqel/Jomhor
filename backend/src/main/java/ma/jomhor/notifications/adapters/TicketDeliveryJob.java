package ma.jomhor.notifications.adapters;

import ma.jomhor.notifications.application.TicketDeliveryService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs TicketDeliveryService.deliverDue on {@code jomhor.notifications.delivery-cron} (every 10 s; "-" = off). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class TicketDeliveryJob {

    private final TicketDeliveryService deliveries;

    TicketDeliveryJob(TicketDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @Scheduled(cron = "${jomhor.notifications.delivery-cron:*/10 * * * * *}")
    void deliver() {
        deliveries.deliverDue();
    }
}
