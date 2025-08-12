package com.trading.infrastructure.config;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.trading.infrastructure.event.TradingEvent;
import com.trading.infrastructure.event.TradingEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Disruptor Configuration for High-Performance Event Processing
 */
@Slf4j
@Configuration
public class DisruptorConfig {
    
    @Value("${disruptor.ring-buffer-size:65536}")
    private int ringBufferSize;
    
    @Value("${disruptor.wait-strategy:BUSY_SPIN}")
    private String waitStrategyName;
    
    @Value("${disruptor.producer-type:MULTI}")
    private String producerTypeName;
    
    @Bean
    public Disruptor<TradingEvent> tradingDisruptor(TradingEventHandler eventHandler) {
        log.info("Initializing Disruptor with buffer size: {}", ringBufferSize);
        
        // Event factory
        EventFactory<TradingEvent> eventFactory = TradingEvent::new;
        
        // Thread factory
        ThreadFactory threadFactory = new DisruptorThreadFactory();
        
        // Wait strategy
        WaitStrategy waitStrategy = createWaitStrategy();
        
        // Producer type
        ProducerType producerType = ProducerType.valueOf(producerTypeName);
        
        // Create disruptor
        Disruptor<TradingEvent> disruptor = new Disruptor<>(
            eventFactory,
            ringBufferSize,
            threadFactory,
            producerType,
            waitStrategy
        );
        
        // Set event handler
        disruptor.handleEventsWith(eventHandler);
        
        // Exception handler
        disruptor.setDefaultExceptionHandler(new DisruptorExceptionHandler());
        
        // Start disruptor
        disruptor.start();
        
        log.info("Disruptor started successfully with {} wait strategy", waitStrategyName);
        
        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Disruptor...");
            disruptor.shutdown();
        }));
        
        return disruptor;
    }
    
    @Bean
    public RingBuffer<TradingEvent> tradingRingBuffer(Disruptor<TradingEvent> disruptor) {
        return disruptor.getRingBuffer();
    }
    
    @Bean
    public EventTranslatorOneArg<TradingEvent, Object> eventTranslator() {
        return (event, sequence, data) -> event.setData(data);
    }
    
    private WaitStrategy createWaitStrategy() {
        switch (waitStrategyName) {
            case "BUSY_SPIN":
                return new BusySpinWaitStrategy();
            case "YIELD":
                return new YieldingWaitStrategy();
            case "BLOCKING":
                return new BlockingWaitStrategy();
            case "LITE_BLOCKING":
                return new LiteBlockingWaitStrategy();
            case "TIMEOUT_BLOCKING":
                return new TimeoutBlockingWaitStrategy(1000, java.util.concurrent.TimeUnit.MICROSECONDS);
            case "LITE_TIMEOUT_BLOCKING":
                return new LiteTimeoutBlockingWaitStrategy(1000, java.util.concurrent.TimeUnit.MICROSECONDS);
            case "SLEEPING":
                return new SleepingWaitStrategy();
            case "PHASE_BACKOFF":
                return PhasedBackoffWaitStrategy.withLock(1000, 1000, java.util.concurrent.TimeUnit.NANOSECONDS);
            default:
                log.warn("Unknown wait strategy: {}, using BusySpinWaitStrategy", waitStrategyName);
                return new BusySpinWaitStrategy();
        }
    }
    
    /**
     * Custom thread factory for Disruptor
     */
    private static class DisruptorThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix = "disruptor-processor-";
        
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            thread.setDaemon(false);
            thread.setPriority(Thread.MAX_PRIORITY);
            return thread;
        }
    }
    
    /**
     * Exception handler for Disruptor
     */
    private static class DisruptorExceptionHandler implements ExceptionHandler<TradingEvent> {
        @Override
        public void handleEventException(Throwable ex, long sequence, TradingEvent event) {
            log.error("Exception processing event at sequence {}: {}", sequence, event, ex);
        }
        
        @Override
        public void handleOnStartException(Throwable ex) {
            log.error("Exception during Disruptor start", ex);
        }
        
        @Override
        public void handleOnShutdownException(Throwable ex) {
            log.error("Exception during Disruptor shutdown", ex);
        }
    }
}