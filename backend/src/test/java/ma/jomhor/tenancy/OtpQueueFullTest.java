package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import ma.jomhor.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;

/**
 * One send thread, no queue: while it is busy every other code is dropped (a WARN and the jomhor.otp.dropped counter),
 * and the API still answers 202.
 */
@SpringBootTest(properties = {IntegrationTest.JWT_SECRET, IntegrationTest.OTP_SECRET, IntegrationTest.EVENT_KEY_MASTER,
        "otp.send.threads=1", "otp.send.queue-capacity=0"})
@Import(OtpQueueFullTest.AsyncProbe.class)
@ExtendWith(OutputCaptureExtension.class)
class OtpQueueFullTest extends IntegrationTest {

    @Autowired
    AsyncProbe probe;

    @Autowired
    MeterRegistry meters;

    private double dropped() {
        return meters.counter("jomhor.otp.dropped").count();
    }

    @Test
    void aFullSendQueueStillAnswers202OnFanAndOrg(CapturedOutput output) throws Exception {
        double droppedBefore = dropped();
        String busy = randomPhone();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            release.await();
            return null;
        }).when(otpSender).send(eq(busy), anyString());
        requestOtp("/fan/auth/otp", busy).andExpect(status().isAccepted());
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue(); // the only send thread is now held

        String fan = randomPhone();
        String staffPhone = randomPhone();
        staff(organizer("Queue FC"), staffPhone, "ADMIN", true);
        String nonStaff = randomPhone();
        try {
            requestOtp("/fan/auth/otp", fan).andExpect(status().isAccepted()).andExpect(content().string(""));
            requestOtp("/org/auth/otp", staffPhone).andExpect(status().isAccepted()).andExpect(content().string(""));
            requestOtp("/org/auth/otp", nonStaff).andExpect(status().isAccepted()).andExpect(content().string(""));
        } finally {
            release.countDown();
        }
        awaitSends();
        verify(otpSender, never()).send(eq(fan), anyString());
        verify(otpSender, never()).send(eq(staffPhone), anyString());
        assertThat(dropped() - droppedBefore).isEqualTo(2);
        assertThat(output.getOut()).contains("OTP send queue full, one code dropped");
    }

    /** spring.task.execution.mode=force: an unqualified @Async runs on Boot's executor, never on the OTP pool. */
    @Test
    void unqualifiedAsyncRunsOnBootsExecutorNotTheOtpPool() throws Exception {
        assertThat(probe.threadName().get(10, TimeUnit.SECONDS)).startsWith("task-");
    }

    static class AsyncProbe {
        @Async
        public CompletableFuture<String> threadName() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }
}
