package com.trading.infrastructure.event;

import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Event Handler for Trading Events in Disruptor
 */
@Slf4j
@Component
public class TradingEventHandler implements EventHandler<TradingEvent> {
    
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    
    private long processedCount = 0;
    private long errorCount = 0;
    
    @Override
    public void onEvent(TradingEvent event, long sequence, boolean endOfBatch) {
        try {
            processEvent(event);
            processedCount++;
            
            // Log batch completion
            if (endOfBatch && processedCount % 1000 == 0) {
                log.debug("Processed {} events, errors: {}", processedCount, errorCount);
            }
        } catch (Exception e) {
            errorCount++;
            log.error("Error processing event: {} at sequence: {}", event, sequence, e);
            handleError(event, e);
        } finally {
            // Clear event for reuse
            event.clear();
        }
    }
    
    private void processEvent(TradingEvent event) {
        if (event == null || event.getEventType() == null) {
            return;
        }
        
        log.debug("Processing event: {} - {}", event.getEventType(), event.getEventId());
        
        switch (event.getEventType()) {
            case MARKET_DATA_UPDATE:
                handleMarketDataUpdate(event);
                break;
                
            case SIGNAL_GENERATED:
                handleSignalGenerated(event);
                break;
                
            case ORDER_CREATED:
            case ORDER_SUBMITTED:
            case ORDER_FILLED:
            case ORDER_CANCELLED:
                handleOrderEvent(event);
                break;
                
            case POSITION_OPENED:
            case POSITION_UPDATED:
            case POSITION_CLOSED:
                handlePositionEvent(event);
                break;
                
            case RISK_ALERT:
            case STOP_LOSS_TRIGGERED:
            case TAKE_PROFIT_TRIGGERED:
                handleRiskEvent(event);
                break;
                
            case SYSTEM_START:
            case SYSTEM_STOP:
                handleSystemEvent(event);
                break;
                
            case ERROR:
            case WARNING:
                handleErrorWarning(event);
                break;
                
            default:
                log.warn("Unhandled event type: {}", event.getEventType());
        }
        
        // Publish to Spring event bus for non-critical path processing
        if (eventPublisher != null && shouldPublishToSpring(event)) {
            eventPublisher.publishEvent(event);
        }
    }
    
    protected void handleMarketDataUpdate(TradingEvent event) {
        // Process market data update
        log.trace("Market data update: {}", event.getData());
        // TODO: Implement market data processing logic
    }
    
    protected void handleSignalGenerated(TradingEvent event) {
        // Process trading signal
        log.info("Trading signal generated: {}", event.getData());
        // TODO: Implement signal processing logic
    }
    
    protected void handleOrderEvent(TradingEvent event) {
        // Process order events
        log.info("Order event: {} - {}", event.getEventType(), event.getData());
        // TODO: Implement order processing logic
    }
    
    private void handlePositionEvent(TradingEvent event) {
        // Process position events
        log.info("Position event: {} - {}", event.getEventType(), event.getData());
        // TODO: Implement position processing logic
    }
    
    protected void handleRiskEvent(TradingEvent event) {
        // Process risk events with high priority
        log.warn("Risk event: {} - {}", event.getEventType(), event.getData());
        // TODO: Implement risk event processing logic
    }
    
    private void handleSystemEvent(TradingEvent event) {
        // Process system events
        log.info("System event: {} - {}", event.getEventType(), event.getData());
        // TODO: Implement system event processing logic
    }
    
    private void handleErrorWarning(TradingEvent event) {
        // Process errors and warnings
        if (event.getEventType() == TradingEvent.EventType.ERROR) {
            log.error("Error event: {}", event.getData());
        } else {
            log.warn("Warning event: {}", event.getData());
        }
    }
    
    private void handleError(TradingEvent event, Exception e) {
        // Error handling logic
        log.error("Failed to process event: {}", event, e);
        // TODO: Implement error recovery logic
    }
    
    private boolean shouldPublishToSpring(TradingEvent event) {
        // Determine if event should be published to Spring event bus
        // Only non-critical path events should be published to avoid latency
        switch (event.getEventType()) {
            case MARKET_DATA_UPDATE:
            case SIGNAL_GENERATED:
            case ORDER_SUBMITTED:
            case ORDER_FILLED:
                return false; // Critical path, don't publish to Spring
            default:
                return true; // Non-critical, can publish for additional processing
        }
    }
    
    /**
     * Get processing statistics
     */
    public ProcessingStats getStats() {
        return ProcessingStats.builder()
            .processedCount(processedCount)
            .errorCount(errorCount)
            .errorRate(processedCount > 0 ? (double) errorCount / processedCount : 0)
            .build();
    }
    
    @lombok.Builder
    @lombok.Data
    public static class ProcessingStats {
        private long processedCount;
        private long errorCount;
        private double errorRate;
    }
}