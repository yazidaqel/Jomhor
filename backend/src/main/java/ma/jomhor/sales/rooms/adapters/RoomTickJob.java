package ma.jomhor.sales.rooms.adapters;

import ma.jomhor.sales.rooms.application.RoomTicks;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs RoomTicks.run {@code rooms.tick} (1 s) after the previous run ends; tests set a period that never fires. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RoomTickJob {

    private final RoomTicks ticks;

    RoomTickJob(RoomTicks ticks) {
        this.ticks = ticks;
    }

    @Scheduled(fixedDelayString = "${rooms.tick}", initialDelayString = "${rooms.tick}")
    void tick() {
        ticks.run();
    }
}
