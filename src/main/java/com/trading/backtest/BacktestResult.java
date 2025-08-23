package com.trading.backtest;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.domain.entity.Order;
import com.trading.strategy.TradingStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回测结果
 * 包含所有回测性能指标和详细数据
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class BacktestResult {
    
    // === 基本信息 ===
    
    /**
     * 策略名称
     */
    @JsonProperty("strategy")
    private String strategy;
    
    /**
     * 交易标的
     */
    @JsonProperty("symbol")
    private String symbol;
    
    /**
     * 回测开始时间
     */
    @JsonProperty("start_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    
    /**
     * 回测结束时间
     */
    @JsonProperty("end_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    
    /**
     * 初始资金
     */
    @JsonProperty("initial_capital")
    private BigDecimal initialCapital;
    
    /**
     * 最终权益
     */
    @JsonProperty("final_equity")
    private BigDecimal finalEquity;

    /**
     * 期末持仓市值（未平仓情况下的总权益）
     */
    @JsonProperty("final_equity_mark_to_market")
    private BigDecimal finalEquityMarkToMarket;

    /**
     * 期末未实现盈亏
     */
    @JsonProperty("unrealized_pnl")
    private BigDecimal unrealizedPnl;
    
    // === 收益指标 ===
    
    /**
     * 总收益（绝对金额）
     */
    @JsonProperty("total_return")
    private BigDecimal totalReturn;
    
    /**
     * 总收益率（百分比）
     */
    @JsonProperty("return_rate")
    private BigDecimal returnRate;
    
    /**
     * 年化收益率
     */
    @JsonProperty("annualized_return")
    private BigDecimal annualizedReturn;
    
    /**
     * 最大回撤
     */
    @JsonProperty("max_drawdown")
    private BigDecimal maxDrawdown;
    
    /**
     * 最大回撤期间
     */
    @JsonProperty("max_drawdown_duration")
    private Integer maxDrawdownDuration;
    
    // === 风险指标 ===
    
    /**
     * 夏普比率
     */
    @JsonProperty("sharpe_ratio")
    private BigDecimal sharpeRatio;
    
    /**
     * 索提诺比率
     */
    @JsonProperty("sortino_ratio")
    private BigDecimal sortinoRatio;
    
    /**
     * 卡尔马比率
     */
    @JsonProperty("calmar_ratio")
    private BigDecimal calmarRatio;
    
    /**
     * 波动率（年化）
     */
    @JsonProperty("volatility")
    private BigDecimal volatility;
    
    /**
     * 下行风险
     */
    @JsonProperty("downside_risk")
    private BigDecimal downsideRisk;
    
    // === 交易统计 ===
    
    /**
     * 总交易次数
     */
    @JsonProperty("total_trades")
    private Integer totalTrades;
    
    /**
     * 盈利交易次数
     */
    @JsonProperty("winning_trades")
    private Integer winningTrades;
    
    /**
     * 亏损交易次数
     */
    @JsonProperty("losing_trades")
    private Integer losingTrades;
    
    /**
     * 胜率
     */
    @JsonProperty("win_rate")
    private BigDecimal winRate;
    
    /**
     * 平均盈利
     */
    @JsonProperty("avg_win")
    private BigDecimal avgWin;
    
    /**
     * 平均亏损
     */
    @JsonProperty("avg_loss")
    private BigDecimal avgLoss;
    
    /**
     * 盈亏比
     */
    @JsonProperty("profit_factor")
    private BigDecimal profitFactor;
    
    /**
     * 平均持仓天数
     */
    @JsonProperty("avg_holding_days")
    private BigDecimal avgHoldingDays;
    
    /**
     * 最大连续盈利次数
     */
    @JsonProperty("max_consecutive_wins")
    private Integer maxConsecutiveWins;
    
    /**
     * 最大连续亏损次数
     */
    @JsonProperty("max_consecutive_losses")
    private Integer maxConsecutiveLosses;
    
    // === 成本统计 ===
    
    /**
     * 总佣金费用
     */
    @JsonProperty("total_commission")
    private BigDecimal totalCommission;
    
    /**
     * 总滑点费用
     */
    @JsonProperty("total_slippage")
    private BigDecimal totalSlippage;
    
    /**
     * 总印花税
     */
    @JsonProperty("total_stamp_duty")
    private BigDecimal totalStampDuty;
    
    /**
     * 总交易费
     */
    @JsonProperty("total_trading_fee")
    private BigDecimal totalTradingFee;
    
    /**
     * 总结算费
     */
    @JsonProperty("total_settlement_fee")
    private BigDecimal totalSettlementFee;
    
    /**
     * 总交易成本
     */
    @JsonProperty("total_costs")
    private BigDecimal totalCosts;
    
    // === 详细数据 ===
    
    /**
     * 权益曲线（时间序列）
     */
    @JsonProperty("equity_curve")
    @Builder.Default
    private List<BigDecimal> equityCurve = new ArrayList<>();
    
    /**
     * 每日收益率
     */
    @JsonProperty("daily_returns")
    @Builder.Default
    private List<BigDecimal> dailyReturns = new ArrayList<>();
    
    /**
     * 回撤序列
     */
    @JsonProperty("drawdown_series")
    @Builder.Default
    private List<BigDecimal> drawdownSeries = new ArrayList<>();
    
    /**
     * 所有交易记录
     */
    @JsonProperty("trades")
    @Builder.Default
    private List<Order> trades = new ArrayList<>();

    /**
     * 交易历史（用于测试和详细分析）
     */
    private List<Order> tradeHistory = new ArrayList<>();
    
    /**
     * 性能指标详细数据
     */
    @JsonProperty("performance_metrics")
    private TradingStrategy.PerformanceMetrics performanceMetrics;
    
    /**
     * 月度收益统计
     */
    @JsonProperty("monthly_returns")
    private Map<String, BigDecimal> monthlyReturns;
    
    /**
     * 年度收益统计
     */
    @JsonProperty("yearly_returns")
    private Map<String, BigDecimal> yearlyReturns;
    
    // === 报告信息 ===
    
    /**
     * 错误信息（如果回测失败）
     */
    @JsonProperty("error")
    private String error;
    
    /**
     * 回测耗时（毫秒）
     */
    @JsonProperty("execution_time_ms")
    private Long executionTimeMs;
    
    /**
     * 报告生成时间
     */
    @JsonProperty("report_generated_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reportGeneratedAt;
    
    // === 便捷方法 ===
    
    /**
     * 判断回测是否成功
     */
    public boolean isSuccessful() {
        return error == null || error.trim().isEmpty();
    }
    
    /**
     * 获取回测期间（天数）
     */
    public long getBacktestDays() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(
            startTime.toLocalDate(), endTime.toLocalDate());
    }
    
    /**
     * 计算年化系数
     */
    public double getAnnualizationFactor() {
        long days = getBacktestDays();
        return days > 0 ? 365.0 / days : 1.0;
    }
    
    /**
     * 获取中文摘要信息
     */
    public String getChineseSummary() {
        if (!isSuccessful()) {
            return "回测失败: " + error;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("=== 回测摘要 ===\n");
        summary.append(String.format("策略: %s\n", strategy));
        summary.append(String.format("标的: %s\n", symbol));
        summary.append(String.format("期间: %s 至 %s (%d天)\n", 
            startTime.toLocalDate(), endTime.toLocalDate(), getBacktestDays()));
        summary.append(String.format("初始资金: ¥%,.2f\n", initialCapital));
        summary.append(String.format("最终权益: ¥%,.2f\n", finalEquity));
        summary.append(String.format("绝对收益: ¥%,.2f\n", totalReturn));
        summary.append(String.format("总收益率: %.2f%%\n", returnRate.movePointRight(2)));
        summary.append(String.format("年化收益率: %.2f%%\n", annualizedReturn.movePointRight(2)));
        summary.append(String.format("最大回撤: %.2f%%\n", maxDrawdown.movePointRight(2)));
        summary.append(String.format("夏普比率: %.2f\n", sharpeRatio));
        summary.append(String.format("总交易次数: %d\n", totalTrades));
        summary.append(String.format("胜率: %.1f%%\n", winRate.movePointRight(2)));
        summary.append(String.format("盈亏比: %.2f\n", profitFactor));
        summary.append(String.format("总交易费用: ¥%,.2f\n", totalCosts));

        if (totalReturn != null && totalReturn.compareTo(BigDecimal.ZERO) > 0 && totalCosts != null) {
            BigDecimal costToReturnRatio = totalCosts.divide(totalReturn, 4, RoundingMode.HALF_UP);
            summary.append(String.format("费用/收益比: %.2f%%\n", costToReturnRatio.movePointRight(2)));
        }

        return summary.toString();
    }
    
    /**
     * 创建空的失败结果
     */
    public static BacktestResult createFailureResult(BacktestRequest request, String error) {
        return BacktestResult.builder()
            .strategy(request.getStrategyName())
            .symbol(request.getSymbol())
            .startTime(request.getStartTime())
            .endTime(request.getEndTime())
            .initialCapital(request.getInitialCapital())
            .finalEquity(request.getInitialCapital())
            .totalReturn(BigDecimal.ZERO)
            .returnRate(BigDecimal.ZERO)
            .totalTrades(0)
            .error(error)
            .reportGeneratedAt(LocalDateTime.now())
            .build();
    }
}