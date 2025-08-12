package com.trading.infrastructure.event;

import com.trading.domain.entity.Order;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Event fired when an order is executed
 * Used for position updates, P&L calculations, and audit logging
 */
@Getter
public class OrderExecutedEvent extends TradingApplicationEvent {
    
    private final Order order;
    private final String symbol;
    private final BigDecimal executionPrice;
    private final int executedQuantity;
    
    public OrderExecutedEvent(Object source, Order order, BigDecimal executionPrice, int executedQuantity) {
        super(source, "ORDER_EXECUTED");
        this.order = order;
        this.symbol = order.getSymbol();
        this.executionPrice = executionPrice;
        this.executedQuantity = executedQuantity;
    }
}