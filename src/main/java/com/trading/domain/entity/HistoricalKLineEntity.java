package com.trading.domain.entity;

import com.trading.common.enums.MarketType;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 历史K线数据实体
 * 支持多市场、多周期的K线数据存储
 * 继承MarketData的基础字段，增加历史数据特有的字段
 */
@Entity
@Table(name = "historical_kline", indexes = {
        @Index(name = "idx_symbol_ktype_timestamp", columnList = "symbol,kline_type,timestamp"),
        @Index(name = "idx_market_ktype", columnList = "market,kline_type"),
        @Index(name = "idx_timestamp_ktype", columnList = "timestamp,kline_type"),
        @Index(name = "idx_download_time", columnList = "download_time"),
        @Index(name = "idx_data_status", columnList = "data_status")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class HistoricalKLineEntity {
    
    /**
     * 主键：symbol_ktype_timestamp 的哈希值
     */
    @Id
    @Column(length = 100)
    private String id;
    
    /**
     * 股票代码（包含市场后缀）
     */
    @Column(nullable = false, length = 20)
    private String symbol;
    
    /**
     * 市场类型
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MarketType market;
    
    /**
     * K线类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kline_type", nullable = false, length = 10)
    private KLineType klineType;
    
    /**
     * 复权类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rehab_type", nullable = false, length = 10)
    private RehabType rehabType;
    
    /**
     * K线时间戳
     */
    @Column(nullable = false)
    private LocalDateTime timestamp;
    
    // ==================== OHLCV数据 ====================
    
    /**
     * 开盘价
     */
    @Column(nullable = false, precision = 15, scale = 6)
    private BigDecimal open;
    
    /**
     * 最高价
     */
    @Column(nullable = false, precision = 15, scale = 6)
    private BigDecimal high;
    
    /**
     * 最低价
     */
    @Column(nullable = false, precision = 15, scale = 6)
    private BigDecimal low;
    
    /**
     * 收盘价
     */
    @Column(nullable = false, precision = 15, scale = 6)
    private BigDecimal close;
    
    /**
     * 成交量
     */
    @Column(nullable = false)
    private Long volume;
    
    /**
     * 成交额
     */
    @Column(precision = 20, scale = 2)
    private BigDecimal turnover;
    
    // ==================== 计算字段 ====================
    
    /**
     * 昨收价
     */
    @Column(name = "pre_close", precision = 15, scale = 6)
    private BigDecimal preClose;
    
    /**
     * 涨跌额
     */
    @Column(name = "change_value", precision = 15, scale = 6)
    private BigDecimal changeValue;
    
    /**
     * 涨跌幅（百分比）
     */
    @Column(name = "change_rate", precision = 10, scale = 4)
    private BigDecimal changeRate;
    
    /**
     * 换手率（百分比）
     */
    @Column(name = "turnover_rate", precision = 10, scale = 4)
    private BigDecimal turnoverRate;
    
    // ==================== 数据管理字段 ====================
    
    /**
     * 数据来源
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", length = 20)
    private DataSource dataSource;
    
    /**
     * 数据状态
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_status", nullable = false, length = 20)
    private DataStatus dataStatus;
    
    /**
     * 数据下载时间
     */
    @Column(name = "download_time")
    private LocalDateTime downloadTime;
    
    /**
     * 数据校验时间
     */
    @Column(name = "validation_time")
    private LocalDateTime validationTime;
    
    /**
     * 数据质量分数（0-100）
     */
    @Column(name = "quality_score")
    private Integer qualityScore;
    
    /**
     * 数据版本号（用于更新检测）
     */
    @Column(name = "data_version")
    private Integer dataVersion;
    
    // ==================== 审计字段 ====================
    
    /**
     * 创建时间
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    // ==================== 业务方法 ====================
    
    /**
     * 计算价格变动
     */
    public BigDecimal calculatePriceChange() {
        if (close == null || preClose == null) {
            return BigDecimal.ZERO;
        }
        return close.subtract(preClose);
    }
    
    /**
     * 计算涨跌幅
     */
    public BigDecimal calculateChangePercent() {
        if (preClose == null || preClose.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return calculatePriceChange()
                .divide(preClose, 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
    
    /**
     * 判断是否为涨停
     * 根据不同市场的涨停限制判断
     */
    public boolean isUpperLimit() {
        if (changeRate == null) return false;
        
        BigDecimal limitRate = switch (market) {
            case HK -> BigDecimal.valueOf(1000); // 港股无涨跌停限制
            case CN_SH, CN_SZ -> BigDecimal.valueOf(10); // A股10%涨停
            case US -> BigDecimal.valueOf(1000); // 美股无涨跌停限制
            default -> BigDecimal.valueOf(10);
        };
        
        return changeRate.compareTo(limitRate.multiply(BigDecimal.valueOf(0.99))) >= 0;
    }
    
    /**
     * 判断是否为跌停
     */
    public boolean isLowerLimit() {
        if (changeRate == null) return false;
        
        BigDecimal limitRate = switch (market) {
            case HK -> BigDecimal.valueOf(-1000); // 港股无涨跌停限制
            case CN_SH, CN_SZ -> BigDecimal.valueOf(-10); // A股10%跌停
            case US -> BigDecimal.valueOf(-1000); // 美股无涨跌停限制
            default -> BigDecimal.valueOf(-10);
        };
        
        return changeRate.compareTo(limitRate.multiply(BigDecimal.valueOf(0.99))) <= 0;
    }
    
    /**
     * 判断是否为有效K线数据
     */
    public boolean isValidKLine() {
        return open != null && high != null && low != null && close != null &&
               volume != null && volume >= 0 &&
               high.compareTo(low) >= 0 &&
               high.compareTo(open) >= 0 &&
               high.compareTo(close) >= 0 &&
               low.compareTo(open) <= 0 &&
               low.compareTo(close) <= 0;
    }
    
    /**
     * 获取K线描述
     */
    public String getDescription() {
        return String.format("%s_%s_%s_%s", 
                symbol, 
                market.getDescription(), 
                klineType.name(), 
                timestamp);
    }
    
    @PrePersist
    @PreUpdate
    public void generateId() {
        if (this.id == null) {
            this.id = String.format("%s_%s_%s_%s", 
                    symbol,
                    klineType.name(),
                    rehabType.name(),
                    timestamp.toString().replaceAll("[:\\-\\s]", ""));
        }
    }
    
    // ==================== 枚举定义 ====================
    
    /**
     * 数据来源枚举
     */
    public enum DataSource {
        FUTU("富途API"),
        TUSHARE("Tushare数据源"),
        YAHOO("Yahoo Finance"),
        MANUAL("手工录入"),
        CALCULATED("计算生成");
        
        private final String description;
        
        DataSource(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 数据状态枚举
     */
    public enum DataStatus {
        PENDING("待处理"),
        DOWNLOADING("下载中"),
        DOWNLOADED("已下载"),
        VALIDATED("已校验"),
        QUALITY_CHECK_PASSED("质量检查通过"),
        QUALITY_CHECK_FAILED("质量检查失败"),
        ERROR("错误"),
        EXPIRED("已过期");
        
        private final String description;
        
        DataStatus(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
        
        /**
         * 判断是否为有效状态
         */
        public boolean isValid() {
            return this == DOWNLOADED || this == VALIDATED || this == QUALITY_CHECK_PASSED;
        }
        
        /**
         * 判断是否需要重新下载
         */
        public boolean needsRedownload() {
            return this == ERROR || this == EXPIRED || this == QUALITY_CHECK_FAILED;
        }
    }
}