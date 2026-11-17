package ma.jomhor.finance.adapters;

import ma.jomhor.finance.application.FinanceProperties;
import ma.jomhor.finance.application.SettlementService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs SettlementService.run {@code finance.tick} (10 min) after the previous run ends, on every instance (the
 * organizer try-lock keeps each statement single); tests set a period that never fires.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(FinanceProperties.class)
class SettlementJob {

    private final SettlementService settlements;

    SettlementJob(SettlementService settlements) {
        this.settlements = settlements;
    }

    @Scheduled(fixedDelayString = "${finance.tick}", initialDelayString = "${finance.tick}")
    void run() {
        settlements.run();
    }
}
