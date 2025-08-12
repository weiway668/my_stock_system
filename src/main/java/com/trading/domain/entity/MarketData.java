package com.trading.domain.entity;

import com.trading.domain.vo.TechnicalIndicators;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Market Data Entity
 * Represents real-time and historical market data for Hong Kong stocks
 */
@Entity
@Table(name = "market_data", indexes = {
    @Index(name = "idx_symbol_timestamp", columnList = "symbol,timestamp"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class MarketData {
    
    @Id
    @Column(length = 100)
    private String id; // Format: symbol_timestamp
    
    @Column(nullable = false, length = 20)
    private String symbol;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal open;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal high;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal low;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal close;
    
    @Column(nullable = false)
    private Long volume;
    
    @Column(precision = 15, scale = 2)
    private BigDecimal turnover; // 成交额
    
    @Column(nullable = false)
    private LocalDateTime timestamp;
    
    @Column(length = 10)
    private String timeframe; // 30m, 120m, 1d
    
    // Technical Indicators (Embedded)
    @Embedded
    private TechnicalIndicators indicators;
    
    // Audit fields
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    private LocalDateTime updatedAt;
    
    // Business methods
    
    /**
     * Calculate price change
     */
    public BigDecimal getPriceChange() {
        if (close == null || open == null) {
            return BigDecimal.ZERO;
        }
        return close.subtract(open);
    }
    
    /**
     * Calculate price change percentage
     */
    public BigDecimal getChangePercent() {
        if (open == null || open.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return getPriceChange()
            .divide(open, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    }
    
    /**
     * Calculate volume ratio compared to average
     */
    public BigDecimal getVolumeRatio(Long avgVolume) {
        if (avgVolume == null || avgVolume == 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(volume)
            .divide(BigDecimal.valueOf(avgVolume), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * Check if this is a bullish candle
     */
    public boolean isBullish() {
        return close.compareTo(open) > 0;
    }
    
    /**
     * Check if this is a bearish candle
     */
    public boolean isBearish() {
        return close.compareTo(open) < 0;
    }
    
    /**
     * Calculate the candle body size
     */
    public BigDecimal getBodySize() {
        return close.subtract(open).abs();
    }
    
    /**
     * Calculate the upper shadow
     */
    public BigDecimal getUpperShadow() {
        BigDecimal bodyTop = close.max(open);
        return high.subtract(bodyTop);
    }
    
    /**
     * Calculate the lower shadow
     */
    public BigDecimal getLowerShadow() {
        BigDecimal bodyBottom = close.min(open);
        return bodyBottom.subtract(low);
    }
    
    @PrePersist
    @PreUpdate
    public void generateId() {
        if (this.id == null) {
            this.id = symbol + "_" + timestamp.toString().replace(":", "").replace("-", "");
        }
    }
}