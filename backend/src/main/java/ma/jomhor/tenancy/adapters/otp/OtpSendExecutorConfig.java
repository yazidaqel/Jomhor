package ma.jomhor.tenancy.adapters.otp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The bounded pool OTP codes are sent on. Queue full → the code is dropped (the fan asks again), never a 500. */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class OtpSendExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(OtpSendExecutorConfig.class);

    /** Only for @Async("otpSendExecutor"): spring.task.execution.mode=force keeps Boot's own executor for the rest. */
    @Bean
    ThreadPoolTaskExecutor otpSendExecutor(@Value("${otp.send.threads}") int threads,
                                           @Value("${otp.send.queue-capacity}") int queueCapacity) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("otp-send-");
        executor.setRejectedExecutionHandler((task, pool) -> log.warn("OTP send queue full, one code dropped"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
