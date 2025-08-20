package com.trading.backtest;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.strategy.TradingStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回测请求参数
 * 封装所有回测所需的配置信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRequest {
    
    /**
     * 交易策略
     */
    @JsonProperty("strategy")
    private TradingStrategy strategy;
    
    /**
     * 策略名称（用于显示）
     */
    @JsonProperty("strategy_name")
    private String strategyName;
    
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
     * K线时间框架
     */
    @JsonProperty("timeframe")
    private String timeframe;
    
    /**
     * 佣金费率
     */
    @JsonProperty("commission_rate")
    private BigDecimal commissionRate;
    
    /**
     * 滑点费率
     */
    @JsonProperty("slippage_rate")
    private BigDecimal slippageRate;
    
    /**
     * 印花税费率（港股卖出时收取）
     */
    @JsonProperty("stamp_duty_rate")
    private BigDecimal stampDutyRate;
    
    /**
     * 交易费费率
     */
    @JsonProperty("trading_fee_rate")
    private BigDecimal tradingFeeRate;
    
    /**
     * 结算费费率
     */
    @JsonProperty("settlement_fee_rate")  
    private BigDecimal settlementFeeRate;
    
    /**
     * 每笔交易最大仓位比例
     */
    @JsonProperty("max_position_ratio")
    private BigDecimal maxPositionRatio;
    
    /**
     * 风险管理：单笔最大损失比例
     */
    @JsonProperty("max_loss_per_trade")
    private BigDecimal maxLossPerTrade;
    
    /**
     * 输出目录路径
     */
    @JsonProperty("output_path")
    private String outputPath;
    
    /**
     * 是否生成详细报告
     */
    @JsonProperty("generate_detailed_report")
    @Builder.Default
    private boolean generateDetailedReport = true;
    
    /**
     * 是否生成HTML可视化报告
     */
    @JsonProperty("generate_html_report")
    @Builder.Default
    private boolean generateHtmlReport = true;

    /**
     * 策略回看指标的历史周期长度
     */
    @JsonProperty("indicator_history_lookback")
    @Builder.Default
    private int indicatorHistoryLookback = 20;
    
    /**
     * 创建默认的港股回测请求
     */
    public static BacktestRequest createHKStockRequest(String symbol, 
                                                      LocalDateTime startTime, 
                                                      LocalDateTime endTime,
                                                      BigDecimal initialCapital) {
        return BacktestRequest.builder()
            .symbol(symbol)
            .startTime(startTime)
            .endTime(endTime)
            .initialCapital(initialCapital)
            .timeframe("30m")
            // 港股费用结构
            .commissionRate(BigDecimal.valueOf(0.00025)) // 0.025%
            .slippageRate(BigDecimal.valueOf(0.0001))    // 0.01%
            .stampDutyRate(BigDecimal.valueOf(0.0013))   // 0.13%
            .tradingFeeRate(BigDecimal.valueOf(0.00005)) // 0.005%
            .settlementFeeRate(BigDecimal.valueOf(0.00002)) // 0.002%
            // 风险管理
            .maxPositionRatio(BigDecimal.valueOf(0.2))   // 20%
            .maxLossPerTrade(BigDecimal.valueOf(0.05))   // 5%
            .generateDetailedReport(true)
            .generateHtmlReport(true)
            .build();
    }
    
    /**
     * 验证请求参数的有效性
     */
    public void validate() {
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new IllegalArgumentException("交易标的不能为空");
        }
        if (startTime == null) {
            throw new IllegalArgumentException("开始时间不能为空");
        }
        if (endTime == null) {
            throw new IllegalArgumentException("结束时间不能为空");
        }
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
        if (initialCapital == null || initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("初始资金必须大于0");
        }
        if (commissionRate != null && commissionRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("佣金费率不能为负数");
        }
    }
    
    /**
     * 获取输出文件前缀
     * 格式：hk_etf_v1_02800_20250812_082334
     */
    public String getOutputFilePrefix() {
        String cleanSymbol = symbol.replace(".HK", "").replace(".", "");
        String timestamp = LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return String.format("hk_etf_v1_%s_%s", cleanSymbol, timestamp);
    }
}