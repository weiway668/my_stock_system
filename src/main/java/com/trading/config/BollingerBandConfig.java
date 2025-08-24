package com.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 布林带策略相关配置
 * <p>
 * 包含所有基于布林带的子策略的配置项，并支持多套布林带计算参数。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "trading.strategies.bollinger-band")
public class BollingerBandConfig {

    /**
     * 是否启用布林带策略组
     */
    private boolean enabled = true;

    /**
     * 多套布林带计算参数配置
     * 允许根据不同市场状态使用不同的参数集
     */
    private List<ParameterSet> parameterSets = new ArrayList<>();

    /**
     * 均值回归子策略配置
     */
    private MeanReversion meanReversion = new MeanReversion();

    /**
     * 挤压突破子策略配置
     */
    private SqueezeBreakout squeezeBreakout = new SqueezeBreakout();

    /**
     * 自适应策略配置
     */
    private Adaptive adaptive = new Adaptive();

    /**
     * 定义一套完整的布林带计算参数
     */
    @Data
    public static class ParameterSet {
        /**
         * 参数集的唯一标识名称, e.g., "trending", "ranging"
         */
        private String key;

        /**
         * 布林带计算周期
         */
        private int period = 20;

        /**
         * 布林带标准差倍数
         */
        private double stdDev = 2.0;
    }

    @Data
    public static class MeanReversion {
        private boolean enabled = true;
        private int minBuyScore = 60;
        private BigDecimal positionSizeRatio = new BigDecimal("0.95");
        private BigDecimal expansionThreshold = new BigDecimal("1.5");
    }

    @Data
    public static class SqueezeBreakout {
        private boolean enabled = false;
        private BigDecimal squeezeThreshold = new BigDecimal("0.015");
        private int minSqueezePeriods = 5;
        private int breakoutLookback = 10;
        private BigDecimal expansionFactor = new BigDecimal("0.2");
        private BigDecimal volumeMultiplier = new BigDecimal("1.5");
    }

    /**
     * 自适应策略特定配置
     */
    @Data
    public static class Adaptive {
        private boolean enabled = true;

        // ADX > adxThreshold -> 趋势行情
        private double adxThreshold;

        // ATR / Close < atrRatioThreshold -> 盘整行情
        private double atrRatioThreshold;

        // === 缓冲因子配置 (经2024年数据分析后优化) ===
        /**
         * 盘整市，买入时下轨的缓冲系数 (e.g., 1.002 表示在下轨的 0.2% 之上即可触发)
         */
        private double rangingLowerBufferFactor = 1.002;
        /**
         * 盘整市，卖出时上轨的缓冲系数 (e.g., 0.998 表示在上轨的 0.2% 之下即可触发)
         */
        private double rangingUpperBufferFactor = 0.998;
        /**
         * 趋势市，买入时突破中轨的缓冲系数 (e.g., 1.005 表示突破中轨0.5%触发)
         */
        private double trendingMiddleBufferFactor = 1.005;
        /**
         * 趋势市，买入时上轨的限制系数 (e.g., 1.02 表示价格低于上轨的102%即可， фактически放开限制)
         */
        private double trendingUpperLimitFactor = 1.02;

        // 趋势市使用的参数集key
        private String trendingParamsKey = "trending";

        // 盘整市使用的参数集key
        private String rangingParamsKey = "ranging";

        // 高波动市使用的参数集key
        private String volatileParamsKey = "volatile";
    }
}
