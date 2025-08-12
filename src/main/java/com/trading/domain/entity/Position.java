package com.trading.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Position Entity
 * Represents a current holding position
 */
@Entity
@Table(name = "positions", indexes = {
    @Index(name = "idx_position_symbol", columnList = "symbol"),
    @Index(name = "idx_position_open_time", columnList = "openTime")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Position {
    
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "uuid2")
    @Column(length = 36)
    private String id;
    
    @Column(length = 20, nullable = false)
    private String symbol;
    
    @Column(length = 50, nullable = false)
    private String accountId;
    
    @Column(nullable = false)
    private Integer quantity;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal avgCost;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal marketValue;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal currentPrice;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal unrealizedPnl;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal realizedPnl;
    
    @Column(nullable = false)
    private LocalDateTime openTime;
    
    private LocalDateTime lastUpdateTime;
    
    // Risk metrics
    @Column(precision = 10, scale = 2)
    private BigDecimal stopLoss;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal takeProfit;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal maxDrawdown;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal highWaterMark;
    
    // Strategy information
    @Column(length = 50)
    private String strategyName;
    
    @Column(length = 10)
    private String positionType; // LONG, SHORT
    
    // Statistics
    private Integer totalTrades;
    private Integer winningTrades;
    private Integer losingTrades;
    
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
     * Calculate current P&L
     */
    public BigDecimal calculatePnl(BigDecimal currentPrice) {
        if (currentPrice == null || avgCost == null) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(avgCost)
            .multiply(BigDecimal.valueOf(quantity));
    }
    
    /**
     * Calculate P&L percentage
     */
    public BigDecimal calculatePnlPercent(BigDecimal currentPrice) {
        if (avgCost == null || avgCost.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal pnl = calculatePnl(currentPrice);
        BigDecimal totalCost = avgCost.multiply(BigDecimal.valueOf(Math.abs(quantity)));
        return pnl.divide(totalCost, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    }
    
    /**
     * Add to position (averaging)
     */
    public void addPosition(Integer qty, BigDecimal price) {
        if (qty == 0 || price == null) {
            return;
        }
        
        BigDecimal totalCost = avgCost.multiply(BigDecimal.valueOf(Math.abs(quantity)))
            .add(price.multiply(BigDecimal.valueOf(Math.abs(qty))));
        quantity += qty;
        
        if (quantity != 0) {
            avgCost = totalCost.divide(BigDecimal.valueOf(Math.abs(quantity)), 
                2, RoundingMode.HALF_UP);
        }
        
        lastUpdateTime = LocalDateTime.now();
    }
    
    /**
     * Reduce position
     */
    public BigDecimal reducePosition(Integer qty, BigDecimal price) {
        if (qty == 0 || qty > Math.abs(quantity)) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal realizedProfit = price.subtract(avgCost)
            .multiply(BigDecimal.valueOf(qty));
        
        quantity -= qty;
        
        if (realizedPnl == null) {
            realizedPnl = BigDecimal.ZERO;
        }
        realizedPnl = realizedPnl.add(realizedProfit);
        
        lastUpdateTime = LocalDateTime.now();
        
        return realizedProfit;
    }
    
    /**
     * Update market value and unrealized P&L
     */
    public void updateMarketValue(BigDecimal price) {
        this.currentPrice = price;
        this.marketValue = price.multiply(BigDecimal.valueOf(Math.abs(quantity)));
        this.unrealizedPnl = calculatePnl(price);
        this.lastUpdateTime = LocalDateTime.now();
        
        // Update high water mark
        if (highWaterMark == null || marketValue.compareTo(highWaterMark) > 0) {
            highWaterMark = marketValue;
        }
        
        // Update max drawdown
        if (highWaterMark != null && marketValue != null) {
            BigDecimal drawdown = highWaterMark.subtract(marketValue)
                .divide(highWaterMark, 4, RoundingMode.HALF_UP);
            if (maxDrawdown == null || drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }
    }
    
    /**
     * Check if position is profitable
     */
    public boolean isProfitable() {
        return unrealizedPnl != null && unrealizedPnl.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * Check if stop loss is hit
     */
    public boolean isStopLossHit(BigDecimal price) {
        if (stopLoss == null || price == null) {
            return false;
        }
        if ("LONG".equals(positionType)) {
            return price.compareTo(stopLoss) <= 0;
        } else {
            return price.compareTo(stopLoss) >= 0;
        }
    }
    
    /**
     * Check if take profit is hit
     */
    public boolean isTakeProfitHit(BigDecimal price) {
        if (takeProfit == null || price == null) {
            return false;
        }
        if ("LONG".equals(positionType)) {
            return price.compareTo(takeProfit) >= 0;
        } else {
            return price.compareTo(takeProfit) <= 0;
        }
    }
    
    /**
     * Get position size in value
     */
    public BigDecimal getPositionValue() {
        return avgCost.multiply(BigDecimal.valueOf(Math.abs(quantity)));
    }
    
    @PrePersist
    public void prePersist() {
        if (this.openTime == null) {
            this.openTime = LocalDateTime.now();
        }
        if (this.positionType == null) {
            this.positionType = quantity > 0 ? "LONG" : "SHORT";
        }
        if (this.realizedPnl == null) {
            this.realizedPnl = BigDecimal.ZERO;
        }
    }
    
    @PreUpdate
    public void preUpdate() {
        this.lastUpdateTime = LocalDateTime.now();
    }
}