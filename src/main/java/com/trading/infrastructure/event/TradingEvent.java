package com.trading.infrastructure.event;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Trading Event for Disruptor
 */
@Data
@NoArgsConstructor
public class TradingEvent {
    
    private String eventId;
    private EventType eventType;
    private Object data;
    private LocalDateTime timestamp;
    private String source;
    private String correlationId;
    
    public enum EventType {
        // Market data events
        MARKET_DATA_UPDATE,
        MARKET_DATA_SNAPSHOT,
        
        // Trading signal events
        SIGNAL_GENERATED,
        SIGNAL_VALIDATED,
        SIGNAL_EXECUTED,
        
        // Order events
        ORDER_CREATED,
        ORDER_SUBMITTED,
        ORDER_FILLED,
        ORDER_PARTIAL_FILLED,
        ORDER_CANCELLED,
        ORDER_REJECTED,
        ORDER_EXPIRED,
        
        // Position events
        POSITION_OPENED,
        POSITION_UPDATED,
        POSITION_CLOSED,
        
        // Risk events
        RISK_ALERT,
        STOP_LOSS_TRIGGERED,
        TAKE_PROFIT_TRIGGERED,
        MARGIN_CALL,
        
        // System events
        SYSTEM_START,
        SYSTEM_STOP,
        STRATEGY_START,
        STRATEGY_STOP,
        ERROR,
        WARNING
    }
    
    /**
     * Factory method to create new event
     */
    public static TradingEvent create(EventType type, Object data) {
        TradingEvent event = new TradingEvent();
        event.eventType = type;
        event.data = data;
        event.timestamp = LocalDateTime.now();
        event.eventId = java.util.UUID.randomUUID().toString();
        return event;
    }
    
    /**
     * Clear event data for reuse
     */
    public void clear() {
        this.eventId = null;
        this.eventType = null;
        this.data = null;
        this.timestamp = null;
        this.source = null;
        this.correlationId = null;
    }
}