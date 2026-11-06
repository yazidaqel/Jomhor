package ma.jomhor.sales.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** The expiry job alone (no application.properties here): every 30 s by default, off with "-". */
class OrderExpiryJobTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OrderExpiryJob.class)
            .withBean(OrderService.class, () -> mock(OrderService.class));

    @Test
    void runsEvery30SecondsByDefault() {
        runner.run(context -> assertThat(crons(context.getBean(ScheduledTaskHolder.class)))
                .containsExactly("*/30 * * * * *"));
    }

    @Test
    void dashTurnsItOff() {
        runner.withPropertyValues("jomhor.sales.expiry-cron=-")
                .run(context -> assertThat(crons(context.getBean(ScheduledTaskHolder.class))).isEmpty());
    }

    private static List<String> crons(ScheduledTaskHolder holder) {
        return holder.getScheduledTasks().stream()
                .filter(t -> t.getTask() instanceof CronTask)
                .map(t -> ((CronTask) t.getTask()).getExpression())
                .toList();
    }
}
