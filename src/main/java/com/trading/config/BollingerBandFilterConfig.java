package com.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 方案四：布林带组合过滤系统 的配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "trading.strategies.bollinger-filter")
public class BollingerBandFilterConfig {

    private boolean enabled = false;

    /**
     * 信号被采纳所需的最少通过的过滤器数量
     */
    private int minPassFilters = 3;

    /**
     * 信号被采纳所需的最低总分
     */
    private double minTotalScore = 50.0;

    /**
     * 布林带宽度过滤器的阈值
     */
    private WidthFilter width = new WidthFilter();

    /**
     * 布林带趋势过滤器的阈值
     */
    private TrendFilter trend = new TrendFilter();

    @Data
    public static class WidthFilter {
        /**
         * 过窄的宽度阈值，低于此值将被拒绝
         */
        private double tooNarrow = 0.015;

        /**
         * 理想宽度的下限
         */
        private double idealLower = 0.02;

        /**
         * 理想宽度的上限
         */
        private double idealUpper = 0.04;

        /**
         * 过宽的宽度阈值，高于此值将被拒绝
         */
        private double tooWide = 0.06;
    }

    @Data
    public static class TrendFilter {
        /**
         * 判断中轨趋势的斜率阈值
         */
        private double slopeThreshold = 0.001;
    }

    /**
     * RSI背离检测配置
     */
    private DivergenceFilter divergence = new DivergenceFilter();

    /**
     * 成交量确认配置
     */
    private VolumeFilter volume = new VolumeFilter();

    /**
     * 风险管理配置
     */
    private RiskManagement riskManagement = new RiskManagement();

    @Data
    public static class DivergenceFilter {
        private boolean enabled = true;
        /**
         * RSI背离检测的回看周期
         */
        private int lookbackPeriod = 14;
        /**
         * RSI指标的计算周期
         */
        private int rsiPeriod = 14;
    }

    @Data
    public static class VolumeFilter {
        private boolean enabled = true;
        /**
         * 成交量移动平均的计算周期
         */
        private int maPeriod = 20;
    }

    @Data
    public static class RiskManagement {
        private boolean enabled = true;
        /**
         * 止损位设置在入场价下方一定百分比
         */
        private double stopLossPercentage = 0.02; // 默认2%
    }
}
