package ma.jomhor.sales.products.adapters;

import ma.jomhor.sales.products.application.PassTicketService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs PassTicketService.run {@code passes.tick} (30 s) after the previous run ends; tests set a period that never fires. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class PassTicketJob {

    private final PassTicketService passTickets;

    PassTicketJob(PassTicketService passTickets) {
        this.passTickets = passTickets;
    }

    @Scheduled(fixedDelayString = "${passes.tick}", initialDelayString = "${passes.tick}")
    void run() {
        passTickets.run();
    }
}
