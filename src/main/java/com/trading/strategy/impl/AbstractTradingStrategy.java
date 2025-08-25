package com.trading.strategy.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.strategy.TradingStrategy;

import lombok.extern.slf4j.Slf4j;

/**
 * 抽象交易策略基类
 * 提供策略的通用实现
 */
@Slf4j
public abstract class AbstractTradingStrategy implements TradingStrategy {

    protected String name;
    protected String version;
    protected String description;
    protected boolean enabled;
    protected Map<String, Object> parameters;
    protected LocalDateTime lastUpdateTime;

    public AbstractTradingStrategy() {
        this.parameters = new ConcurrentHashMap<>();
        this.enabled = false;
        this.lastUpdateTime = LocalDateTime.now();
    }

    @Override
    public void initialize(Map<String, Object> config) {
        log.info("初始化策略: {}", getName());
        if (config != null) {
            this.parameters.putAll(config);
        }
        doInitialize();
        this.lastUpdateTime = LocalDateTime.now();
    }

    public void destroy() {
        log.info("销毁策略: {}", getName());
        doDestroy();
        this.lastUpdateTime = LocalDateTime.now();
    }

    /**
     * 子类实现的初始化逻辑
     */
    protected abstract void doInitialize();

    protected abstract void doDestroy();

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void enable() {
        log.info("启用策略: {}", getName());
        this.enabled = true;
    }

    @Override
    public void disable() {
        log.info("禁用策略: {}", getName());
        this.enabled = false;
    }

    @Override
    public void updateParameters(Map<String, Object> newParameters) {
        log.info("更新策略参数: {}", getName());
        if (newParameters != null) {
            this.parameters.putAll(newParameters);
            this.lastUpdateTime = LocalDateTime.now();
            onParametersUpdated();
        }
    }

    /**
     * 参数更新后的回调
     */
    protected void onParametersUpdated() {
        // 子类可以重写此方法以响应参数更新
    }


    /**
     * 判断当前趋势是否有利。这是一个专业实现，综合考虑了多个因素。
     * 子类可以重写此方法或其辅助方法以自定义趋势判断逻辑。
     * @param marketData 当前市场数据
     * @param indicatorHistory 技术指标历史
     * @return 如果趋势有利于建仓，则返回 true
     */
    protected boolean isTrendFavorable(MarketData marketData, List<TechnicalIndicators> indicatorHistory) {
        // 数据量不足时，不进行严格的趋势判断，避免过滤掉早期机会
        if (indicatorHistory.size() < 50) { // 减少数据量要求至50，以适应不同长度的均线
            log.debug("历史数据不足50条，跳过专业趋势分析");
            return true; 
        }
        
        TechnicalIndicators current = indicatorHistory.get(indicatorHistory.size() - 1);
        TechnicalIndicators previous = indicatorHistory.get(indicatorHistory.size() - 2);

        // 1. 多时间框架趋势确认 (均线多头排列)
        boolean multiTimeframeTrend = checkMultiTimeframeTrend(current);
        
        // 2. 移动平均线斜率确认 (短期均线向上)
        boolean maSlope = checkMASlope(current, previous);
        
        // 3. 趋势强度确认 (ADX > 20)
        boolean trendStrength = checkTrendStrength(current);
        
        // 4. 价格与长期趋势关系确认 (价格在50周期均线之上)
        boolean pricePosition = checkPricePosition(marketData, current);
        
        log.debug("专业趋势分析结果: 多时间框架={}, 斜率={}, 强度={}, 价格位置={}", 
            multiTimeframeTrend, maSlope, trendStrength, pricePosition);

        // 综合判断：趋势强度满足，并且其他三个条件中至少满足两个
        return trendStrength && (multiTimeframeTrend && maSlope && pricePosition);
    }

    /**
     * 检查多时间框架趋势，确认均线是否形成多头排列。
     * @param current 当前技术指标
     * @return 如果SMA20 > SMA50，则为true
     */
    protected boolean checkMultiTimeframeTrend(TechnicalIndicators current) {
        BigDecimal maShort = current.getSma20();
        BigDecimal maMedium = current.getSma50();
        
        if (maShort == null || maMedium == null) {
            log.debug("短期或中期移动平均线数据不全，跳过多时间框架趋势检查");
            return true; // 数据不全时默认通过
        }
        
        // 多头排列: 短周期 > 中周期
        return maShort.compareTo(maMedium) > 0;
    }

    /**
     * 检查短期移动平均线的斜率，确认趋势方向。
     * @param current 当前技术指标
     * @param previous 前一期技术指标
     * @return 如果SMA20正在上升，则为true
     */
    protected boolean checkMASlope(TechnicalIndicators current, TechnicalIndicators previous) {
        BigDecimal currentSMA20 = current.getSma20();
        BigDecimal previousSMA20 = previous.getSma20();

        if (currentSMA20 == null || previousSMA20 == null) {
            log.debug("SMA20数据不全，无法计算斜率，跳过检查");
            return true; // 数据不全时默认通过
        }

        // 简单通过比较当前值和前期值来判断斜率方向
        return currentSMA20.compareTo(previousSMA20) > 0;
    }

    /**
     * 检查趋势强度，要求ADX指标显示有明确的趋势。
     * @param current 当前技术指标
     * @return 如果ADX > 20，则为true
     */
    protected boolean checkTrendStrength(TechnicalIndicators current) {
        BigDecimal adx = current.getAdx();
        
        if (adx == null) {
            log.debug("ADX数据不可用，跳过趋势强度检查");
            return true; // 数据不全时默认通过
        }
        
        // ADX > 20 表示趋势存在
        BigDecimal adxThreshold = getParameter("adxThreshold", new BigDecimal("20"));
        return adx.compareTo(adxThreshold) > 0;
    }

    /**
     * 检查当前价格与中期均线的关系，确认处于上升趋势中。
     * @param marketData 当前市场数据
     * @param current 当前技术指标
     * @return 如果价格高于SMA50，则为true
     */
    protected boolean checkPricePosition(MarketData marketData, TechnicalIndicators current) {
        BigDecimal price = marketData.getClose();
        BigDecimal maMedium = current.getSma50();
        
        if (maMedium == null) {
            log.debug("50周期均线数据不可用，跳过价格位置检查");
            return true; // 数据不全时默认通过
        }
        
        // 价格位于50日均线之上，确认处于中期上升趋势中
        return price.compareTo(maMedium) > 0;
    }

    /**
     * 判断当前波动性是否足够且不过高。这是一个专业实现，综合使用ATR和布林带宽度。
     * @param marketData 当前市场数据
     * @param indicatorHistory 技术指标历史
     * @return 如果波动性在适宜范围内，则返回 true
     */
    protected boolean isVolatilityAdequate(MarketData marketData, List<TechnicalIndicators> indicatorHistory) {
        if (indicatorHistory.isEmpty()) {
            return false;
        }

        // 1. ATR绝对波动率过滤
        boolean atrFilter = checkATRVolatility(marketData, indicatorHistory);

        // 2. 布林带宽度过滤
        boolean bbWidthFilter = checkBollingerBandWidth(indicatorHistory);

        log.debug("专业波动率分析结果: ATR过滤={}, 布林带宽度过滤={}", atrFilter, bbWidthFilter);

        // 综合判断：两者都必须满足
        return atrFilter && bbWidthFilter;
    }

    /**
     * 使用ATR（平均真实波幅）占价格的百分比来检查波动性。
     * @param marketData 当前市场数据
     * @param indicatorHistory 技术指标历史
     * @return 如果ATR百分比在0.8%到5%之间，则为true
     */
    protected boolean checkATRVolatility(MarketData marketData, List<TechnicalIndicators> indicatorHistory) {
        TechnicalIndicators current = indicatorHistory.get(indicatorHistory.size() - 1);
        BigDecimal atr = current.getAtr();
        BigDecimal price = marketData.getClose();

        if (atr == null || price == null || price.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("ATR或价格数据不可用，跳过ATR波动率检查");
            return true; // 数据不足时默认通过
        }

        // 计算ATR百分比 (ATR/Price)
        BigDecimal atrPercentage = atr.divide(price, 4, RoundingMode.HALF_UP);

        BigDecimal minAtrPercentage = getParameter("minAtrPercentage", new BigDecimal("0.008"));
        BigDecimal maxAtrPercentage = getParameter("maxAtrPercentage", new BigDecimal("0.05"));

        boolean adequateVolatility = atrPercentage.compareTo(minAtrPercentage) > 0 &&
                                   atrPercentage.compareTo(maxAtrPercentage) < 0;

        log.debug("ATR百分比: {}, 适宜波动率: {}", atrPercentage, adequateVolatility);
        return adequateVolatility;
    }

    /**
     * 使用布林带宽度来检查波动性。
     * @param indicatorHistory 技术指标历史
     * @return 如果布林带宽度在1.5%到15%之间，则为true
     */
    protected boolean checkBollingerBandWidth(List<TechnicalIndicators> indicatorHistory) {
        TechnicalIndicators current = indicatorHistory.get(indicatorHistory.size() - 1);
        BigDecimal bandwidth = current.getBandwidth();

        if (bandwidth == null) {
            log.debug("布林带数据不可用，跳过带宽检查");
            return true; // 数据不足时默认通过
        }

        // 带宽在1.5%到15%之间视为有效
        BigDecimal minBandwidth = getParameter("minBandwidth", new BigDecimal("0.015"));
        BigDecimal maxBandwidth = getParameter("maxBandwidth", new BigDecimal("0.15"));

        boolean adequateBandwidth = bandwidth.compareTo(minBandwidth) > 0 &&
                                  bandwidth.compareTo(maxBandwidth) < 0;

        log.debug("布林带宽度: {}, 适宜带宽: {}", bandwidth, adequateBandwidth);
        return adequateBandwidth;
    }

    TradingSignal createSignal(String symbol, TradingSignal.SignalType type, BigDecimal price,
            double confidence, String reason) {
        return TradingSignal.builder()
                .symbol(symbol)
                .type(type)
                .price(price)
                .confidence(BigDecimal.valueOf(confidence))
                .reason(reason)
                .build();
    }

    TradingSignal createNoActionSignal(String symbol, String reason) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.NO_ACTION)
                .reason(reason)
                .build();
    }

    @Override
    public PerformanceMetrics evaluatePerformance(List<Order> orders) {
        PerformanceMetrics metrics = new PerformanceMetrics();
        metrics.setCalculatedAt(LocalDateTime.now());

        if (orders == null || orders.isEmpty()) {
            metrics.setTotalTrades(0);
            metrics.setWinRate(BigDecimal.ZERO);
            metrics.setTotalReturn(BigDecimal.ZERO);
            return metrics;
        }

        // 计算基本统计
        int totalTrades = 0;
        int winningTrades = 0;
        int losingTrades = 0;
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        BigDecimal totalReturn = BigDecimal.ZERO;

        for (Order order : orders) {
            if (order.getStatus() == OrderStatus.FILLED && order.getRealizedPnl() != null) {
                totalTrades++;
                BigDecimal pnl = order.getRealizedPnl();
                totalReturn = totalReturn.add(pnl);

                if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                    winningTrades++;
                    totalProfit = totalProfit.add(pnl);
                } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                    losingTrades++;
                    totalLoss = totalLoss.add(pnl.abs());
                }
            }
        }

        metrics.setTotalTrades(totalTrades);
        metrics.setWinningTrades(winningTrades);
        metrics.setLosingTrades(losingTrades);
        metrics.setTotalReturn(totalReturn);

        // 计算胜率
        if (totalTrades > 0) {
            BigDecimal winRate = BigDecimal.valueOf(winningTrades)
                    .divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            metrics.setWinRate(winRate);
        } else {
            metrics.setWinRate(BigDecimal.ZERO);
        }

        // 计算平均盈亏
        if (winningTrades > 0) {
            metrics.setAverageWin(totalProfit.divide(
                    BigDecimal.valueOf(winningTrades), 2, RoundingMode.HALF_UP));
        } else {
            metrics.setAverageWin(BigDecimal.ZERO);
        }

        if (losingTrades > 0) {
            metrics.setAverageLoss(totalLoss.divide(
                    BigDecimal.valueOf(losingTrades), 2, RoundingMode.HALF_UP));
        } else {
            metrics.setAverageLoss(BigDecimal.ZERO);
        }

        // 计算盈亏因子
        if (totalLoss.compareTo(BigDecimal.ZERO) > 0) {
            metrics.setProfitFactor(totalProfit.divide(totalLoss, 2, RoundingMode.HALF_UP));
        } else {
            metrics.setProfitFactor(
                    totalProfit.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(999) : BigDecimal.ZERO);
        }

        // 计算最大回撤
        metrics.setMaxDrawdown(calculateMaxDrawdown(orders));

        // 计算夏普比率
        metrics.setSharpeRatio(calculateSharpeRatio(orders));

        return metrics;
    }

    /**
     * 计算最大回撤
     */
    protected BigDecimal calculateMaxDrawdown(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal runningTotal = BigDecimal.ZERO;

        for (Order order : orders) {
            if (order.getRealizedPnl() != null) {
                runningTotal = runningTotal.add(order.getRealizedPnl());

                if (runningTotal.compareTo(peak) > 0) {
                    peak = runningTotal;
                }

                BigDecimal drawdown = peak.subtract(runningTotal);
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
        }

        // 返回百分比形式的最大回撤
        if (peak.compareTo(BigDecimal.ZERO) > 0) {
            return maxDrawdown.divide(peak, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        return BigDecimal.ZERO;
    }

    /**
     * 计算夏普比率
     */
    protected BigDecimal calculateSharpeRatio(List<Order> orders) {
        if (orders == null || orders.size() < 2) {
            return BigDecimal.ZERO;
        }

        // 计算收益率序列
        BigDecimal sumReturn = BigDecimal.ZERO;
        BigDecimal sumSquaredReturn = BigDecimal.ZERO;
        int count = 0;

        for (Order order : orders) {
            if (order.getRealizedPnl() != null && order.getTotalCost().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal returnRate = order.getRealizedPnl()
                        .divide(order.getTotalCost(), 6, RoundingMode.HALF_UP);
                sumReturn = sumReturn.add(returnRate);
                sumSquaredReturn = sumSquaredReturn.add(returnRate.multiply(returnRate));
                count++;
            }
        }

        if (count < 2) {
            return BigDecimal.ZERO;
        }

        // 计算平均收益率
        BigDecimal avgReturn = sumReturn.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);

        // 计算标准差
        BigDecimal variance = sumSquaredReturn.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP)
                .subtract(avgReturn.multiply(avgReturn));

        if (variance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

        // 假设无风险利率为2%年化
        BigDecimal riskFreeRate = BigDecimal.valueOf(0.02 / 252); // 日化无风险利率

        // 计算夏普比率
        BigDecimal excessReturn = avgReturn.subtract(riskFreeRate);

        if (stdDev.compareTo(BigDecimal.ZERO) > 0) {
            return excessReturn.divide(stdDev, 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(Math.sqrt(252))); // 年化
        }

        return BigDecimal.ZERO;
    }

    /**
     * 获取参数值
     */
    @SuppressWarnings("unchecked")
    protected <T> T getParameter(String key, T defaultValue) {
        Object value = parameters.get(key);
        if (value != null) {
            try {
                return (T) value;
            } catch (ClassCastException e) {
                log.warn("参数类型转换失败: key={}, value={}", key, value);
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 设置参数值
     */
    protected void setParameter(String key, Object value) {
        parameters.put(key, value);
    }
}