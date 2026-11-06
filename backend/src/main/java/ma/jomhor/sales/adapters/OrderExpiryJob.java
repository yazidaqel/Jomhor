package ma.jomhor.sales.adapters;

import ma.jomhor.sales.application.OrderService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs OrderService.expireDue on {@code jomhor.sales.expiry-cron} (every 30 s; "-" turns it off, as in tests). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OrderExpiryJob {

    private final OrderService orders;

    OrderExpiryJob(OrderService orders) {
        this.orders = orders;
    }

    @Scheduled(cron = "${jomhor.sales.expiry-cron:*/30 * * * * *}")
    void expire() {
        orders.expireDue();
    }
}
