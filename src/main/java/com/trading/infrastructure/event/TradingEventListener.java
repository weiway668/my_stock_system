package com.trading.infrastructure.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Event Listener for Trading System Events
 * Handles Spring Events for non-critical path processing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingEventListener {

    /**
     * Handle market data update events
     */
    @Async("eventTaskExecutor")
    @EventListener
    public void handleMarketDataUpdate(MarketDataUpdateEvent event) {
        try {
            log.debug("Processing market data update for symbol: {}", event.getSymbol());
            
            // TODO: Implement market data analytics
            // - Update technical indicators cache
            // - Trigger strategy recalculation (if needed)
            // - Update market statistics
            
            log.trace("Market data update processed: {}", event);
        } catch (Exception e) {
            log.error("Error processing market data update event: {}", event, e);
        }
    }

    /**
     * Handle order execution events
     */
    @Async("eventTaskExecutor")
    @EventListener
    public void handleOrderExecution(OrderExecutedEvent event) {
        try {
            log.info("Processing order execution for symbol: {} - Price: {}, Quantity: {}", 
                event.getSymbol(), event.getExecutionPrice(), event.getExecutedQuantity());
            
            // TODO: Implement post-execution processing
            // - Update position calculations
            // - Calculate P&L
            // - Update portfolio metrics
            // - Send execution confirmations
            
            log.debug("Order execution processed: {}", event);
        } catch (Exception e) {
            log.error("Error processing order execution event: {}", event, e);
        }
    }

    /**
     * Handle risk alert events with high priority
     */
    @Async("auditTaskExecutor")  // Use audit executor for important risk events
    @EventListener
    public void handleRiskAlert(RiskAlertEvent event) {
        try {
            log.warn("Processing risk alert - Level: {}, Type: {}, Message: {}", 
                event.getLevel(), event.getAlertType(), event.getMessage());
            
            // TODO: Implement risk alert handling
            // - Send notifications (email, SMS, Slack)
            // - Update risk metrics
            // - Trigger emergency stops if critical
            // - Log to audit trail
            
            if (event.getLevel() == RiskAlertEvent.AlertLevel.CRITICAL) {
                log.error("CRITICAL RISK ALERT: {}", event.getMessage());
                // TODO: Implement critical alert handling
                // - Immediate notification
                // - Emergency position closure
                // - System halt if necessary
            }
            
            log.info("Risk alert processed: {}", event);
        } catch (Exception e) {
            log.error("Error processing risk alert event: {}", event, e);
            // For risk events, we might want to escalate the error
            // TODO: Implement error escalation for failed risk processing
        }
    }

    /**
     * Handle general trading events for metrics and monitoring
     */
    @Async("metricsTaskExecutor")
    @EventListener
    public void handleTradingEvent(TradingApplicationEvent event) {
        try {
            // Only process events that aren't handled by specific listeners
            if (event instanceof MarketDataUpdateEvent || 
                event instanceof OrderExecutedEvent || 
                event instanceof RiskAlertEvent) {
                return; // Skip events handled by specific methods
            }
            
            log.debug("Processing general trading event: {}", event.getEventType());
            
            // TODO: Implement general event processing
            // - Update system metrics
            // - Collect performance statistics
            // - Update monitoring dashboards
            
            log.trace("General trading event processed: {}", event);
        } catch (Exception e) {
            log.debug("Error processing general trading event: {}", event, e);
            // For metrics events, we don't want to spam the logs with errors
        }
    }
}