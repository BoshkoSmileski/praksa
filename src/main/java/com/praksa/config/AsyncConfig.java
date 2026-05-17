package com.praksa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync  // This is what activates @Async throughout the application
public class AsyncConfig {

    /**
     * Named executor bean for email sending.
     *
     * Why these numbers:
     *   corePoolSize=2   — 2 threads always alive, ready for emails
     *   maxPoolSize=5    — can burst to 5 under heavy load
     *   queueCapacity=50 — up to 50 tasks can wait before rejecting new ones
     *
     * For a university system this is more than enough.
     * For high-volume production, you'd use a message broker (RabbitMQ/Kafka) instead.
     */
    @Bean(name = "emailTaskExecutor")
    public Executor emailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("email-async-");  // visible in logs and thread dumps
        executor.setWaitForTasksToCompleteOnShutdown(true);  // don't drop emails on app shutdown
        executor.setAwaitTerminationSeconds(30);             // wait up to 30s for in-flight emails
        executor.initialize();
        return executor;
    }
}
