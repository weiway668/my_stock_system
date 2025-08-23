package com.trading.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 回测结果实体
 * <p>
 * 用于在数据库中持久化存储每一次回测的详细结果，
 * 包括策略配置、回测周期以及核心性能指标。
 * </p>
 */
@Entity
@Table(name = "backtest_results", indexes = {
        @Index(name = "idx_strategy_name", columnList = "strategyName"),
        @Index(name = "idx_backtest_run_time", columnList = "backtestRunTime")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class BacktestResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 50)
    private String id;

    // === 回测元数据 ===
    @Column(nullable = false)
    private String strategyName;

    @Column(columnDefinition = "TEXT")
    private String strategyParameters;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime backtestRunTime;

    // === 核心性能指标 ===
    @Column(precision = 18, scale = 8)
    private BigDecimal annualizedReturn; // 年化收益率

    @Column(precision = 18, scale = 8)
    private BigDecimal cumulativeReturn; // 累计收益率

    @Column(precision = 18, scale = 8)
    private BigDecimal sharpeRatio; // 夏普比率

    @Column(precision = 18, scale = 8)
    private BigDecimal sortinoRatio; // 索提诺比率

    @Column(precision = 18, scale = 8)
    private BigDecimal maxDrawdown; // 最大回撤

    @Column(precision = 18, scale = 8)
    private BigDecimal calmarRatio; // Calmar比率

    @Column(precision = 18, scale = 8)
    private BigDecimal winRate; // 胜率

    @Column(precision = 18, scale = 8)
    private BigDecimal profitLossRatio; // 盈亏比

    // === 交易统计 ===
    private int totalTrades;

    private int winningTrades;

    private int losingTrades;

    @Column(precision = 18, scale = 4)
    private BigDecimal averageProfit;

    @Column(precision = 18, scale = 4)
    private BigDecimal averageLoss;

    // === 资产信息 ===
    @Column(precision = 18, scale = 4)
    private BigDecimal initialCapital;

    @Column(precision = 18, scale = 4)
    private BigDecimal finalCapital;

    @Column(precision = 18, scale = 4)
    private BigDecimal totalReturn; // 绝对总收益

    // === 成本统计 ===
    @Column(precision = 18, scale = 4)
    private BigDecimal totalCost;

    @Column(precision = 18, scale = 4)
    private BigDecimal totalCommission;

    @Column(precision = 18, scale = 4)
    private BigDecimal totalStampDuty;

    @Column(precision = 18, scale = 4)
    private BigDecimal totalTradingFee;

    @Column(precision = 18, scale = 4)
    private BigDecimal totalSettlementFee;

    @Column(precision = 18, scale = 4)
    private BigDecimal totalPlatformFee; // 平台使用费

    @Lob
    @Column(columnDefinition = "TEXT")
    private String dailyEquityChartData; // 用于图表绘制的每日权益数据 (JSON格式)

    @Lob
    @Column(columnDefinition = "TEXT")
    private String tradeHistoryData; // 详细交易历史 (JSON格式)
}
