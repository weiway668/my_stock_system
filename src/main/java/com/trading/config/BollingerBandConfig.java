package com.trading.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * 布林带策略相关配置
 */
@Data
@ConfigurationProperties(prefix = "trading.strategies.bollinger-band")
public class BollingerBandConfig {

    /**
     * 是否启用该策略
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
     * 最低买入分数
     * 只有当价格在布林带中的位置得分高于此值时，才会考虑买入
     */
    private int minBuyScore = 60;

    /**
     * 趋势判断的回看周期
     */
    private int trendLookback = 5;

    /**
     * 下跌趋势的阈值
     */
    private BigDecimal trendThreshold = new BigDecimal("-0.05");

    /**
     * 仓位大小比例
     * 用于计算每次交易投入的资金占总可用现金的比例
     */
    private BigDecimal positionSizeRatio = new BigDecimal("0.95");
}
