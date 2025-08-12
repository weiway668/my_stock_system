package com.trading.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring Events Configuration for Trading System
 * Configures async event handling for non-critical path operations
 */
@Slf4j
@Configuration
@EnableAsync
public class EventConfig {

    /**
     * Configure async task executor for Spring Events
     * Used for non-critical path processing to avoid blocking main trading flow
     */
    @Bean(name = "eventTaskExecutor")
    public TaskExecutor eventTaskExecutor() {
        log.info("Configuring async task executor for Spring Events");
        
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core pool configuration
        executor.setCorePoolSize(4);                    // Base threads for event processing
        executor.setMaxPoolSize(8);                     // Maximum threads under load
        executor.setQueueCapacity(1000);                // Queue for pending events
        executor.setKeepAliveSeconds(60);               // Thread idle timeout
        
        // Thread naming
        executor.setThreadNamePrefix("event-handler-");
        executor.setDaemon(true);                       // Daemon threads for clean shutdown
        
        // Rejection policy - Log and discard for non-critical events
        executor.setRejectedExecutionHandler(new LogAndDiscardPolicy());
        
        // Wait for tasks to complete on shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        
        executor.initialize();
        return executor;
    }

    /**
     * Configure application event multicaster for async processing
     */
    @Bean(name = "applicationEventMulticaster")
    public ApplicationEventMulticaster applicationEventMulticaster() {
        log.info("Configuring async application event multicaster");
        
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setTaskExecutor(eventTaskExecutor());
        
        // Set error handler to prevent uncaught exceptions from stopping event processing
        multicaster.setErrorHandler(throwable -> 
            log.error("Error occurred in async event processing", throwable)
        );
        
        return multicaster;
    }

    /**
     * Task executor specifically for audit and logging events
     * Separate from main event processing to ensure audit trail is maintained
     */
    @Bean(name = "auditTaskExecutor")
    public TaskExecutor auditTaskExecutor() {
        log.info("Configuring audit task executor for logging events");
        
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Smaller pool for audit operations
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(30);
        
        executor.setThreadNamePrefix("audit-handler-");
        executor.setDaemon(false);  // Important: audit threads should not be daemon
        
        // For audit, we want to retain events even if pool is full
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);  // More time for audit completion
        
        executor.initialize();
        return executor;
    }

    /**
     * Task executor for performance metrics and monitoring events
     * Dedicated executor to ensure metrics collection doesn't interfere with trading
     */
    @Bean(name = "metricsTaskExecutor")
    @ConditionalOnProperty(name = "trading.monitoring.enabled", havingValue = "true", matchIfMissing = true)
    public TaskExecutor metricsTaskExecutor() {
        log.info("Configuring metrics task executor for monitoring events");
        
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Single-threaded for sequential metrics processing
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        
        executor.setThreadNamePrefix("metrics-handler-");
        executor.setDaemon(true);
        
        // Drop metrics events if queue is full to prevent memory issues
        executor.setRejectedExecutionHandler(new LogAndDiscardPolicy());
        
        executor.setWaitForTasksToCompleteOnShutdown(false);  // Don't wait for metrics
        
        executor.initialize();
        return executor;
    }

    /**
     * Custom rejection handler that logs rejected tasks
     */
    public static class LogAndDiscardPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("Event task rejected and discarded. Pool: [active={}, pool={}, queue={}]",
                executor.getActiveCount(),
                executor.getPoolSize(),
                executor.getQueue().size());
        }
    }
}