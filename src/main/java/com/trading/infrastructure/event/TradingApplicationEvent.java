package com.trading.infrastructure.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Base class for all trading system events
 * Provides common properties and functionality for Spring Events
 */
@Getter
public abstract class TradingApplicationEvent extends ApplicationEvent {
    
    private final String eventId;
    private final LocalDateTime eventTimestamp;
    private final String eventType;
    
    protected TradingApplicationEvent(Object source, String eventType) {
        super(source);
        this.eventId = UUID.randomUUID().toString();
        this.eventTimestamp = LocalDateTime.now();
        this.eventType = eventType;
    }
    
    protected TradingApplicationEvent(Object source, String eventType, String eventId) {
        super(source);
        this.eventId = eventId;
        this.eventTimestamp = LocalDateTime.now();
        this.eventType = eventType;
    }
    
    @Override
    public String toString() {
        return String.format("%s[eventId=%s, timestamp=%s, source=%s]", 
            eventType, eventId, eventTimestamp, source.getClass().getSimpleName());
    }
}