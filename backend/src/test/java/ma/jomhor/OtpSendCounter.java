package ma.jomhor;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionHandler;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Exact count of OTP sends handed to {@code otpSendExecutor} and not yet finished. A task decorator counts up on the
 * submitting thread (inside {@code execute}, before the pool queues or rejects it) and down once the send has run;
 * the pool's rejection handler is wrapped to count a dropped send down too. Unlike
 * {@code ThreadPoolExecutor.getTaskCount()}, which is approximate, this never misses a task between queue and worker.
 */
class OtpSendCounter implements BeanPostProcessor {

    private final Object lock = new Object();
    private long pending;
    private volatile boolean installed;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String name) {
        if ("otpSendExecutor".equals(name) && bean instanceof ThreadPoolTaskExecutor pool) {
            pool.setTaskDecorator(task -> {
                change(1);
                return new Counted(task);
            });
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if ("otpSendExecutor".equals(name) && bean instanceof ThreadPoolTaskExecutor pool) {
            var executor = pool.getThreadPoolExecutor();
            RejectedExecutionHandler drop = executor.getRejectedExecutionHandler();
            executor.setRejectedExecutionHandler((task, ex) -> {
                try {
                    drop.rejectedExecution(task, ex);
                } finally {
                    change(-1);
                }
            });
            installed = true;
        }
        return bean;
    }

    /** True once no send is pending, false if some are still pending after {@code timeout}. */
    boolean awaitIdle(Duration timeout) {
        if (!installed) { // e.g. the bean was renamed: fail loudly rather than wait for nothing
            throw new IllegalStateException("otpSendExecutor not instrumented");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (pending > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    lock.wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void change(int delta) {
        synchronized (lock) {
            pending += delta;
            lock.notifyAll();
        }
    }

    private final class Counted implements Runnable {
        private final Runnable task;

        Counted(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                change(-1);
            }
        }
    }
}
