package com.trading.strategy.impl;

import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderStatus;
import com.trading.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    
    /**
     * 子类实现的初始化逻辑
     */
    protected abstract void doInitialize();
    
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
            metrics.setProfitFactor(totalProfit.compareTo(BigDecimal.ZERO) > 0 ? 
                BigDecimal.valueOf(999) : BigDecimal.ZERO);
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