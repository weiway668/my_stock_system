package com.trading.service;

import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.RingBuffer;
import com.trading.infrastructure.event.TradingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高性能事件处理服务
 * 使用Disruptor实现超低延迟的事件处理
 * 集成市场数据、交易信号、订单执行等各种事件处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HighPerformanceEventProcessor {

    private final RingBuffer<TradingEvent> ringBuffer;
    private final EventTranslatorOneArg<TradingEvent, Object> eventTranslator;
    private final ApplicationEventPublisher springEventPublisher;
    
    // 各种服务集成
    private final MarketDataService marketDataService;
    private final TradingSignalProcessor signalProcessor;
    private final OrderExecutionService orderExecutionService;
    private final TradingService tradingService;

    // 事件处理统计
    private final AtomicBoolean processingEnabled = new AtomicBoolean(false);
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong eventsPerSecond = new AtomicLong(0);
    
    // 辅助执行器
    private final ExecutorService auxiliaryExecutor = Executors.newCachedThreadPool();

    @PostConstruct
    public void initialize() {
        log.info("初始化高性能事件处理器...");
        
        // 启动事件处理
        processingEnabled.set(true);
        
        // 启动性能监控
        startPerformanceMonitoring();
        
        log.info("高性能事件处理器初始化完成 - RingBuffer大小: {}", ringBuffer.getBufferSize());
    }

    /**
     * 发布市场数据事件
     */
    public void publishMarketDataEvent(Object marketData) {
        if (processingEnabled.get()) {
            MarketDataEvent event = MarketDataEvent.builder()
                .type(EventType.MARKET_DATA)
                .data(marketData)
                .timestamp(System.currentTimeMillis())
                .build();
                
            publishEvent(event);
        }
    }

    /**
     * 发布交易信号事件
     */
    public void publishTradingSignalEvent(Object signal) {
        if (processingEnabled.get()) {
            TradingSignalEvent event = TradingSignalEvent.builder()
                .type(EventType.TRADING_SIGNAL)
                .data(signal)
                .timestamp(System.currentTimeMillis())
                .priority(EventPriority.HIGH)
                .build();
                
            publishEvent(event);
        }
    }

    /**
     * 发布订单事件
     */
    public void publishOrderEvent(Object order) {
        if (processingEnabled.get()) {
            OrderEvent event = OrderEvent.builder()
                .type(EventType.ORDER_EXECUTION)
                .data(order)
                .timestamp(System.currentTimeMillis())
                .priority(EventPriority.CRITICAL)
                .build();
                
            publishEvent(event);
        }
    }

    /**
     * 发布风险控制事件
     */
    public void publishRiskControlEvent(Object riskData) {
        if (processingEnabled.get()) {
            RiskControlEvent event = RiskControlEvent.builder()
                .type(EventType.RISK_CONTROL)
                .data(riskData)
                .timestamp(System.currentTimeMillis())
                .priority(EventPriority.CRITICAL)
                .build();
                
            publishEvent(event);
        }
    }

    /**
     * 批量发布事件
     */
    public void publishBatchEvents(java.util.List<Object> eventData, EventType eventType) {
        if (processingEnabled.get() && eventData != null && !eventData.isEmpty()) {
            
            BatchEvent batchEvent = BatchEvent.builder()
                .type(eventType)
                .batchData(eventData)
                .batchSize(eventData.size())
                .timestamp(System.currentTimeMillis())
                .build();
                
            publishEvent(batchEvent);
            
            log.debug("批量发布事件: type={}, count={}", eventType, eventData.size());
        }
    }

    /**
     * 异步处理市场数据流
     */
    public CompletableFuture<Void> processMarketDataStream(java.util.stream.Stream<Object> marketDataStream) {
        return CompletableFuture.runAsync(() -> {
            try {
                marketDataStream
                    .parallel()
                    .forEach(this::publishMarketDataEvent);
                    
                log.debug("市场数据流处理完成");
                
            } catch (Exception e) {
                log.error("处理市场数据流异常", e);
            }
        }, auxiliaryExecutor);
    }

    /**
     * 处理实时信号生成和执行流水线
     */
    public CompletableFuture<Void> processSignalExecutionPipeline(Object marketData) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 1. 发布市场数据事件
                publishMarketDataEvent(marketData);
                
                // 2. 等待信号生成 (通过事件处理器异步处理)
                // 事件处理器会自动调用信号处理器生成信号
                
                // 3. 信号执行事件会自动发布
                // 整个流水线通过Disruptor实现超低延迟处理
                
                log.trace("信号执行流水线启动");
                
            } catch (Exception e) {
                log.error("信号执行流水线异常", e);
            }
        }, auxiliaryExecutor);
    }

    /**
     * 获取处理性能统计
     */
    public ProcessingStatistics getProcessingStatistics() {
        try {
            return ProcessingStatistics.builder()
                .totalEventsProcessed(eventsProcessed.get())
                .eventsPerSecond(eventsPerSecond.get())
                .ringBufferSize(ringBuffer.getBufferSize())
                .remainingCapacity(ringBuffer.remainingCapacity())
                .processingEnabled(processingEnabled.get())
                .build();
                
        } catch (Exception e) {
            log.error("获取处理统计异常", e);
            return ProcessingStatistics.builder().build();
        }
    }

    /**
     * 暂停事件处理
     */
    public void pauseProcessing() {
        processingEnabled.set(false);
        log.info("事件处理已暂停");
    }

    /**
     * 恢复事件处理
     */
    public void resumeProcessing() {
        processingEnabled.set(true);
        log.info("事件处理已恢复");
    }

    /**
     * 清空RingBuffer（谨慎使用）
     */
    public void clearRingBuffer() {
        try {
            long cursor = ringBuffer.getCursor();
            log.warn("清空RingBuffer - 当前cursor: {}", cursor);
            // RingBuffer没有直接的clear方法，这里只是记录状态
            
        } catch (Exception e) {
            log.error("清空RingBuffer异常", e);
        }
    }

    // 私有方法实现

    /**
     * 发布事件到RingBuffer
     */
    private void publishEvent(Object event) {
        try {
            boolean published = ringBuffer.tryPublishEvent(eventTranslator, event);
            if (published) {
                eventsProcessed.incrementAndGet();
                log.trace("事件发布成功: {}", event.getClass().getSimpleName());
            } else {
                log.warn("RingBuffer已满，事件发布失败: {}", event.getClass().getSimpleName());
                
                // 如果RingBuffer满了，可以考虑使用阻塞发布
                // ringBuffer.publishEvent(eventTranslator, event);
                
                // 或者异步重试
                CompletableFuture.runAsync(() -> {
                    try {
                        ringBuffer.publishEvent(eventTranslator, event);
                        eventsProcessed.incrementAndGet();
                        log.debug("事件延迟发布成功: {}", event.getClass().getSimpleName());
                    } catch (Exception e) {
                        log.error("延迟发布事件异常", e);
                    }
                }, auxiliaryExecutor);
            }
            
        } catch (Exception e) {
            log.error("发布事件异常", e);
        }
    }

    /**
     * 启动性能监控
     */
    private void startPerformanceMonitoring() {
        auxiliaryExecutor.execute(() -> {
            long lastCount = 0;
            
            while (processingEnabled.get()) {
                try {
                    Thread.sleep(1000); // 每秒统计一次
                    
                    long currentCount = eventsProcessed.get();
                    long currentEPS = currentCount - lastCount;
                    eventsPerSecond.set(currentEPS);
                    lastCount = currentCount;
                    
                    if (currentEPS > 0) {
                        log.trace("事件处理性能: {}/秒, 总计: {}, RingBuffer剩余: {}", 
                            currentEPS, currentCount, ringBuffer.remainingCapacity());
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("性能监控异常", e);
                }
            }
        });
    }

    /**
     * 检查服务健康状态
     */
    public boolean isHealthy() {
        try {
            return processingEnabled.get() && 
                   ringBuffer != null && 
                   auxiliaryExecutor != null && 
                   !auxiliaryExecutor.isShutdown() &&
                   ringBuffer.remainingCapacity() > 0;
                   
        } catch (Exception e) {
            log.error("健康检查异常", e);
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭高性能事件处理器...");
        
        processingEnabled.set(false);
        
        try {
            // 等待当前事件处理完成
            Thread.sleep(100);
            
            // 关闭辅助执行器
            auxiliaryExecutor.shutdown();
            if (!auxiliaryExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                auxiliaryExecutor.shutdownNow();
            }
            
            log.info("高性能事件处理器关闭完成 - 总处理事件数: {}", eventsProcessed.get());
            
        } catch (Exception e) {
            log.error("关闭高性能事件处理器异常", e);
        }
    }

    // 事件类型和数据类定义

    public enum EventType {
        MARKET_DATA,        // 市场数据事件
        TRADING_SIGNAL,     // 交易信号事件
        ORDER_EXECUTION,    // 订单执行事件
        RISK_CONTROL,       // 风险控制事件
        SYSTEM_STATUS,      // 系统状态事件
        BATCH_PROCESSING    // 批量处理事件
    }

    public enum EventPriority {
        LOW,        // 低优先级
        NORMAL,     // 普通优先级
        HIGH,       // 高优先级
        CRITICAL    // 关键优先级
    }

    @lombok.Builder
    @lombok.Data
    public static class MarketDataEvent {
        private EventType type;
        private Object data;
        private long timestamp;
        private EventPriority priority = EventPriority.NORMAL;
    }

    @lombok.Builder
    @lombok.Data
    public static class TradingSignalEvent {
        private EventType type;
        private Object data;
        private long timestamp;
        private EventPriority priority = EventPriority.HIGH;
    }

    @lombok.Builder
    @lombok.Data
    public static class OrderEvent {
        private EventType type;
        private Object data;
        private long timestamp;
        private EventPriority priority = EventPriority.CRITICAL;
    }

    @lombok.Builder
    @lombok.Data
    public static class RiskControlEvent {
        private EventType type;
        private Object data;
        private long timestamp;
        private EventPriority priority = EventPriority.CRITICAL;
    }

    @lombok.Builder
    @lombok.Data
    public static class BatchEvent {
        private EventType type;
        private java.util.List<Object> batchData;
        private int batchSize;
        private long timestamp;
        private EventPriority priority = EventPriority.NORMAL;
    }

    @lombok.Builder
    @lombok.Data
    public static class ProcessingStatistics {
        private long totalEventsProcessed;
        private long eventsPerSecond;
        private long ringBufferSize;
        private long remainingCapacity;
        private boolean processingEnabled;
        
        public double getUtilizationRate() {
            if (ringBufferSize > 0) {
                return (double)(ringBufferSize - remainingCapacity) / ringBufferSize * 100;
            }
            return 0.0;
        }
    }
}