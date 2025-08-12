package com.trading.domain.enums;

/**
 * Order Side Enumeration
 */
public enum OrderSide {
    BUY("Buy"),
    SELL("Sell");
    
    private final String description;
    
    OrderSide(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}