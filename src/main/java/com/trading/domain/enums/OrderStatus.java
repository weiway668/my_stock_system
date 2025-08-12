package com.trading.domain.enums;

/**
 * Order Status Enumeration
 */
public enum OrderStatus {
    PENDING("Pending submission"),
    SUBMITTED("Submitted to broker"),
    PARTIAL_FILLED("Partially filled"),
    FILLED("Completely filled"),
    CANCELLED("Cancelled"),
    REJECTED("Rejected by broker"),
    EXPIRED("Expired"),
    FAILED("Failed to submit");
    
    private final String description;
    
    OrderStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    public boolean isActive() {
        return this == PENDING || this == SUBMITTED || this == PARTIAL_FILLED;
    }
    
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED || this == EXPIRED || this == FAILED;
    }
}