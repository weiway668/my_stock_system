package com.trading.domain.entity;

import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Order Entity
 * Represents a trading order in the system
 */
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_order_status", columnList = "status"),
    @Index(name = "idx_order_symbol", columnList = "symbol"),
    @Index(name = "idx_order_create_time", columnList = "createTime"),
    @Index(name = "idx_order_signal_id", columnList = "signalId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Order {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 50)
    private String orderId;
    
    @Column(nullable = false, length = 20)
    private String symbol;
    
    @Column(nullable = false, length = 50)
    private String accountId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderSide side; // BUY, SELL
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderType type; // MARKET, LIMIT, STOP, STOP_LIMIT
    
    @Column(precision = 10, scale = 2)
    private BigDecimal price;
    
    @Column(nullable = false)
    private Integer quantity;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;
    
    @Column(nullable = false)
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
    // Association with signal
    @Column(length = 50)
    private String signalId;
    
    @Column(length = 50)
    private String strategyName;
    
    // Execution information
    @Column(precision = 10, scale = 2)
    private BigDecimal executedPrice;
    
    private Integer executedQuantity;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal commission;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal slippage;
    
    // Risk management
    @Column(precision = 10, scale = 2)
    private BigDecimal stopLoss;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal takeProfit;
    
    // Broker information
    @Column(length = 100)
    private String brokerOrderId;
    
    @Column(length = 50)
    private String brokerName;
    
    // P&L tracking
    @Column(precision = 10, scale = 2)
    private BigDecimal realizedPnl;
    
    @Column(columnDefinition = "TEXT")
    private String notes;
    
    // Audit fields
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    private LocalDateTime updatedAt;
    
    // Business methods
    
    /**
     * Calculate total value of the order
     */
    public BigDecimal getTotalValue() {
        BigDecimal orderPrice = executedPrice != null ? executedPrice : price;
        if (orderPrice == null) {
            return BigDecimal.ZERO;
        }
        Integer orderQuantity = executedQuantity != null ? executedQuantity : quantity;
        return orderPrice.multiply(BigDecimal.valueOf(orderQuantity));
    }
    
    /**
     * Calculate total cost including commission
     */
    public BigDecimal getTotalCost() {
        BigDecimal totalValue = getTotalValue();
        if (commission != null) {
            if (side == OrderSide.BUY) {
                totalValue = totalValue.add(commission);
            } else {
                totalValue = totalValue.subtract(commission);
            }
        }
        return totalValue;
    }
    
    /**
     * Check if order is filled
     */
    public boolean isFilled() {
        return status == OrderStatus.FILLED;
    }
    
    /**
     * Check if order is active
     */
    public boolean isActive() {
        return status == OrderStatus.PENDING || status == OrderStatus.PARTIAL_FILLED;
    }
    
    /**
     * Check if order is terminated
     */
    public boolean isTerminated() {
        return status == OrderStatus.FILLED || 
               status == OrderStatus.CANCELLED || 
               status == OrderStatus.REJECTED;
    }
    
    /**
     * Calculate fill ratio
     */
    public BigDecimal getFillRatio() {
        if (quantity == null || quantity == 0) {
            return BigDecimal.ZERO;
        }
        if (executedQuantity == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(executedQuantity)
            .divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
    }
    
    /**
     * Update order status based on execution
     */
    public void updateExecutionStatus() {
        if (executedQuantity == null || executedQuantity == 0) {
            this.status = OrderStatus.PENDING;
        } else if (executedQuantity < quantity) {
            this.status = OrderStatus.PARTIAL_FILLED;
        } else {
            this.status = OrderStatus.FILLED;
        }
        this.updateTime = LocalDateTime.now();
    }
    
    @PrePersist
    public void prePersist() {
        if (this.createTime == null) {
            this.createTime = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = OrderStatus.PENDING;
        }
        if (this.executedQuantity == null) {
            this.executedQuantity = 0;
        }
    }
    
    @PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}