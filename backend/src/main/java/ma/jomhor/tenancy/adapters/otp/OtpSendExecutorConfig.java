package ma.jomhor.tenancy.adapters.otp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The bounded pool OTP codes are sent on. Queue full → the code is dropped (the fan asks again), never a 500: a WARN
 * and one more on the {@value #DROPPED} counter (plan lot-5 Q148; BE-45 sizes the pool from it).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class OtpSendExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(OtpSendExecutorConfig.class);
    static final String DROPPED = "jomhor.otp.dropped";

    /** Only for @Async("otpSendExecutor"): spring.task.execution.mode=force keeps Boot's own executor for the rest. */
    @Bean
    ThreadPoolTaskExecutor otpSendExecutor(@Value("${otp.send.threads}") int threads,
                                           @Value("${otp.send.queue-capacity}") int queueCapacity,
                                           MeterRegistry meters) {
        Counter dropped = Counter.builder(DROPPED).description("OTP codes dropped: send queue full").register(meters);
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("otp-send-");
        executor.setRejectedExecutionHandler((task, pool) -> {
            dropped.increment();
            log.warn("OTP send queue full, one code dropped");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
