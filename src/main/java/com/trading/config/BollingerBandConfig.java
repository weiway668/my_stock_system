package com.trading.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * 布林带策略相关配置
 * <p>
 * 包含所有基于布林带的子策略的配置项。
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
     * 布林带计算周期
     */
    private int period = 20;

    /**
     * 布林带标准差倍数
     */
    private double stdDev = 2.0;

    /**
     * 均值回归子策略配置
     */
    private MeanReversion meanReversion = new MeanReversion();

    /**
     * 挤压突破子策略配置
     */
    private SqueezeBreakout squeezeBreakout = new SqueezeBreakout();

    @Data
    public static class MeanReversion {
        /**
         * 是否启用均值回归子策略
         */
        private boolean enabled = true;

        /**
         * 最低买入分数
         * 只有当价格在布林带中的位置得分高于此值时，才会考虑买入
         */
        private int minBuyScore = 60;

        /**
         * 仓位大小比例
         * 用于计算每次交易投入的资金占总可用现金的比例
         */
        private BigDecimal positionSizeRatio = new BigDecimal("0.95");
        
        /**
         * 带宽扩张过滤的阈值倍数
         * 如果当前带宽超过历史平均带宽的此倍数，则认为进入趋势行情，暂停均值回归交易
         */
        private BigDecimal expansionThreshold = new BigDecimal("1.5");
    }

    @Data
    public static class SqueezeBreakout {
        /**
         * 是否启用挤压突破子策略
         */
        private boolean enabled = false; // 默认关闭，待稳定后开启

        /**
         * 布林带挤压的带宽阈值
         * 当 (上轨 - 下轨) / 中轨 < 此阈值时，视为进入挤压状态
         */
        private BigDecimal squeezeThreshold = new BigDecimal("0.015");

        /**
         * 最小挤压周期
         * 必须连续维持至少N个周期处于挤压状态
         */
        private int minSqueezePeriods = 5;

        /**
         * 突破确认的回看周期
         * 用于检测挤压状态和突破
         */
        private int breakoutLookback = 10;

        /**
         * 带宽扩张确认因子
         * 当前带宽 > 前一周期带宽 * (1 + 因子) 时，视为有效扩张
         */
        private BigDecimal expansionFactor = new BigDecimal("0.2");

        /**
         * 突破时成交量放大倍数
         * 交易量需要大于N日平均交易量的倍数，才认为是有效突破
         */
        private BigDecimal volumeMultiplier = new BigDecimal("1.5");
    }
}