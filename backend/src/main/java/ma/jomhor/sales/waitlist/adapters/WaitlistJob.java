package ma.jomhor.sales.waitlist.adapters;

import ma.jomhor.sales.waitlist.application.WaitlistService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs WaitlistService.run {@code waitlist.tick} (30 s) after the previous run ends; tests set a period that never fires. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class WaitlistJob {

    private final WaitlistService waitlist;

    WaitlistJob(WaitlistService waitlist) {
        this.waitlist = waitlist;
    }

    @Scheduled(fixedDelayString = "${waitlist.tick}", initialDelayString = "${waitlist.tick}")
    void run() {
        waitlist.run();
    }
}
