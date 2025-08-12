package com.trading.infrastructure.event;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * Event fired when a risk alert is triggered
 * Used for notifications, emergency stops, and risk monitoring
 */
@Getter
public class RiskAlertEvent extends TradingApplicationEvent {
    
    public enum AlertLevel {
        INFO, WARNING, CRITICAL
    }
    
    public enum AlertType {
        DAILY_LOSS_LIMIT,
        MAX_DRAWDOWN,
        POSITION_LIMIT,
        MARGIN_CALL,
        SYSTEM_ERROR
    }
    
    private final AlertLevel level;
    private final AlertType alertType;
    private final String message;
    private final String symbol;
    private final BigDecimal currentValue;
    private final BigDecimal thresholdValue;
    
    public RiskAlertEvent(Object source, AlertLevel level, AlertType alertType, 
                         String message, String symbol, BigDecimal currentValue, BigDecimal thresholdValue) {
        super(source, "RISK_ALERT");
        this.level = level;
        this.alertType = alertType;
        this.message = message;
        this.symbol = symbol;
        this.currentValue = currentValue;
        this.thresholdValue = thresholdValue;
    }
    
    public RiskAlertEvent(Object source, AlertLevel level, AlertType alertType, String message) {
        this(source, level, alertType, message, null, null, null);
    }
}