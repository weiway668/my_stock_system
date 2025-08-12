package com.trading.domain.enums;

/**
 * Order Type Enumeration
 */
public enum OrderType {
    MARKET("Market order - execute immediately at market price"),
    LIMIT("Limit order - execute at specified price or better"),
    STOP("Stop order - convert to market when stop price is reached"),
    STOP_LIMIT("Stop limit order - convert to limit when stop price is reached"),
    TRAILING_STOP("Trailing stop order - stop price adjusts with market"),
    ICEBERG("Iceberg order - show only part of the total quantity"),
    AUCTION("Auction order - participate in opening/closing auction");
    
    private final String description;
    
    OrderType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    public boolean requiresPrice() {
        return this != MARKET;
    }
}