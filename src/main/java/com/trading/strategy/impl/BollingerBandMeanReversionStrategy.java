package com.trading.strategy.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.vo.TechnicalIndicators;

import lombok.extern.slf4j.Slf4j;

/**
 * 布林带均值回归策略
 * 核心理念：价格围绕均值上下波动，在下轨买入，在上轨卖出
 */
@Slf4j
@Component("BOLL")
public class BollingerBandMeanReversionStrategy extends AbstractTradingStrategy {
    
    @Override
    protected void doInitialize() {
        log.info("初始化布林带均值回归策略");
    }
    
    protected void doDestroy() {
        log.info("销毁布林带均值回归策略");
    }
    
    // 布林带参数
    private static final int BB_PERIOD = 20;  // 20日均线
    private static final double BB_STD = 2.0;  // 2倍标准差
    
    // 位置评分系统
    private static final Map<String, Integer> POSITION_SCORES = new HashMap<>();
    static {
        POSITION_SCORES.put("below_lower", 100);      // 下轨以下：强烈买入
        POSITION_SCORES.put("near_lower", 80);        // 接近下轨：买入
        POSITION_SCORES.put("lower_to_middle", 60);   // 下轨到中轨：谨慎买入
        POSITION_SCORES.put("middle", 40);            // 中轨附近：观望
        POSITION_SCORES.put("middle_to_upper", 20);   // 中轨到上轨：准备卖出
        POSITION_SCORES.put("near_upper", -50);       // 接近上轨：禁止买入
        POSITION_SCORES.put("above_upper", -100);     // 上轨以上：强烈禁止
    }
    
    // 策略参数
    private BigDecimal positionSizeRatio = BigDecimal.valueOf(0.95); // 使用95%的资金
    private BigDecimal minBuyScore = BigDecimal.valueOf(60);  // 最低买入分数
    private BigDecimal maxSellScore = BigDecimal.valueOf(20); // 最高卖出分数
    
    // 趋势判断参数
    private int trendLookback = 5;  // 趋势回看天数
    private BigDecimal trendThreshold = BigDecimal.valueOf(-0.05); // 下跌趋势阈值
    
    @Override
    public String getName() {
        return "布林带均值回归策略";
    }
    
    @Override
    public TradingSignal generateSignal(MarketData marketData, TechnicalIndicators indicators, List<Position> positions) {
        try {
            // 检查是否有布林带指标
            if (indicators.getBollingerUpper() == null || 
                indicators.getBollingerMiddle() == null || 
                indicators.getBollingerLower() == null) {
                log.warn("布林带指标未计算，跳过信号生成");
                return createNoActionSignal(marketData.getSymbol());
            }
            
            BigDecimal price = marketData.getClose();
            BigDecimal upper = indicators.getBollingerUpper();
            BigDecimal middle = indicators.getBollingerMiddle();
            BigDecimal lower = indicators.getBollingerLower();
            
            // 计算价格在布林带中的位置得分
            int positionScore = calculateBBPositionScore(price, upper, middle, lower);
            
            // 检查是否已有持仓
            boolean hasPosition = positions.stream()
                .anyMatch(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0);
            
            // 生成交易信号
            if (!hasPosition && positionScore >= minBuyScore.intValue()) {
                // 买入条件：价格在下半区且得分足够高
                if (confirmNotDowntrend(marketData, indicators)) {
                    log.info("【{}】生成买入信号: symbol={}, price={}, score={}, lower={}, middle={}", getName(),
                        marketData.getSymbol(), price, positionScore, lower, middle);
                    
                    return TradingSignal.builder()
                        .symbol(marketData.getSymbol())
                        .type(TradingSignal.SignalType.BUY)
                        .price(price)
                        .confidence(calculateSignalStrength(positionScore, true))
                        .reason(String.format("布林带均值回归买入（位置分:%d，价格:%.2f，下轨:%.2f）", 
                            positionScore, price, lower))
                        .timestamp(LocalDateTime.now())
                        .build();
                }
            } else if (hasPosition && positionScore <= maxSellScore.intValue()) {
                // 卖出条件：价格在上半区
                log.info("【{}】生成卖出信号: symbol={}, price={}, score={}, upper={}, middle={}", getName(),
                    marketData.getSymbol(), price, positionScore, upper, middle);
                
                return TradingSignal.builder()
                    .symbol(marketData.getSymbol())
                    .type(TradingSignal.SignalType.SELL)
                    .price(price)
                    .confidence(calculateSignalStrength(positionScore, false))
                    .reason(String.format("布林带高位止盈（位置分:%d，价格:%.2f，上轨:%.2f）", 
                        positionScore, price, upper))
                    .timestamp(LocalDateTime.now())
                    .build();
            }
            
            // 持有信号
            if (hasPosition && positionScore > maxSellScore.intValue() && positionScore < 50) {
                return TradingSignal.builder()
                    .symbol(marketData.getSymbol())
                    .type(TradingSignal.SignalType.HOLD)
                    .price(price)
                    .confidence(BigDecimal.valueOf(0.5))
                    .reason(String.format("持有（位置分:%d）", positionScore))
                    .timestamp(LocalDateTime.now())
                    .build();
            }
            
            return createNoActionSignal(marketData.getSymbol());
            
        } catch (Exception e) {
            log.error("生成交易信号失败", e);
            return createNoActionSignal(marketData.getSymbol());
        }
    }
    
    /**
     * 计算价格在布林带中的位置得分
     */
    private int calculateBBPositionScore(BigDecimal price, BigDecimal upper, 
                                         BigDecimal middle, BigDecimal lower) {
        BigDecimal bbWidth = upper.subtract(lower);
        if (bbWidth.compareTo(BigDecimal.ZERO) <= 0) {
            return 40; // 布林带宽度异常，返回中性分数
        }
        
        BigDecimal positionRatio = price.subtract(lower).divide(bbWidth, 4, RoundingMode.HALF_UP);
        
        // 根据价格位置返回相应分数
        if (price.compareTo(lower) < 0) {
            // 价格在下轨以下
            return POSITION_SCORES.get("below_lower");
        } else if (positionRatio.compareTo(BigDecimal.valueOf(0.2)) < 0) {
            // 价格接近下轨（下轨到20%位置）
            return POSITION_SCORES.get("near_lower");
        } else if (price.compareTo(middle) < 0) {
            // 价格在下轨到中轨之间
            return POSITION_SCORES.get("lower_to_middle");
        } else if (positionRatio.compareTo(BigDecimal.valueOf(0.55)) < 0) {
            // 价格在中轨附近
            return POSITION_SCORES.get("middle");
        } else if (positionRatio.compareTo(BigDecimal.valueOf(0.8)) < 0) {
            // 价格在中轨到上轨之间
            return POSITION_SCORES.get("middle_to_upper");
        } else if (price.compareTo(upper) < 0) {
            // 价格接近上轨
            return POSITION_SCORES.get("near_upper");
        } else {
            // 价格在上轨以上
            return POSITION_SCORES.get("above_upper");
        }
    }
    
    /**
     * 确认不是下跌趋势
     */
    private boolean confirmNotDowntrend(MarketData marketData, TechnicalIndicators indicators) {
        // 使用SMA20作为趋势判断
        if (indicators.getSma20() != null) {
            BigDecimal price = marketData.getClose();
            BigDecimal sma20 = indicators.getSma20();
            
            // 如果价格低于SMA20超过5%，认为是下跌趋势
            BigDecimal priceRatio = price.subtract(sma20).divide(sma20, 4, RoundingMode.HALF_UP);

            if (priceRatio.compareTo(trendThreshold) < 0) {
                log.debug("检测到下跌趋势，价格低于SMA20 {}%", priceRatio.multiply(BigDecimal.valueOf(100)));
                return false;
            }
        }
        
        // 检查RSI是否过低（超卖但可能继续下跌）
        if (indicators.getRsi() != null && indicators.getRsi().compareTo(BigDecimal.valueOf(30)) < 0) {
            log.debug("RSI过低: {}，可能继续下跌", indicators.getRsi());
            // RSI过低时仍然可以买入，因为可能是超卖反弹机会
            // return false;
        }
        
        return true;
    }
    
    /**
     * 计算信号强度
     */
    private BigDecimal calculateSignalStrength(int positionScore, boolean isBuy) {
        if (isBuy) {
            // 买入信号强度：分数越高，强度越大
            if (positionScore >= 100) {
                return BigDecimal.valueOf(1.0);
            } else if (positionScore >= 80) {
                return BigDecimal.valueOf(0.8);
            } else if (positionScore >= 60) {
                return BigDecimal.valueOf(0.6);
            } else {
                return BigDecimal.valueOf(0.4);
            }
        } else {
            // 卖出信号强度：分数越低，强度越大
            if (positionScore <= -50) {
                return BigDecimal.valueOf(1.0);
            } else if (positionScore <= 0) {
                return BigDecimal.valueOf(0.8);
            } else if (positionScore <= 20) {
                return BigDecimal.valueOf(0.6);
            } else {
                return BigDecimal.valueOf(0.4);
            }
        }
    }
    
    @Override
    public int calculatePositionSize(TradingSignal signal, BigDecimal availableCash, BigDecimal currentPrice) {
        if (signal.getType() == TradingSignal.SignalType.BUY) {
            // 使用95%的可用资金
            BigDecimal maxInvestment = availableCash.multiply(positionSizeRatio);
            
            // 计算可买入的股数（港股最小交易单位通常是100股）
            int shares = maxInvestment.divide(currentPrice, 0, RoundingMode.DOWN).intValue();
            
            // 调整到100的整数倍
            shares = (shares / 100) * 100;
            
            log.debug("计算仓位: 可用资金={}, 当前价格={}, 计算股数={}", 
                availableCash, currentPrice, shares);
            
            return Math.max(shares, 100); // 至少买100股
        } else if (signal.getType() == TradingSignal.SignalType.SELL) {
            // 卖出全部持仓
            return Integer.MAX_VALUE;
        }
        
        return 0;
    }
    
    @Override
    public Order applyRiskManagement(Order order, MarketData marketData) {
        // 布林带策略的风险管理
        // 可以在这里添加止损逻辑，例如：
        // - 如果买入后价格跌破下轨X%，则止损
        // - 如果持仓时间过长（如超过20天），则强制平仓
        
        // 暂时不设置止损，依靠布林带自身的均值回归特性
        return order;
    }
    
    @Override
    public PerformanceMetrics evaluatePerformance(List<Order> orders) {
        PerformanceMetrics metrics = new PerformanceMetrics();
        
        if (orders.isEmpty()) {
            return metrics;
        }
        
        // 计算基本统计
        int totalTrades = orders.size();
        int winningTrades = 0;
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        
        for (Order order : orders) {
            if (order.getRealizedPnl() != null) {
                if (order.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0) {
                    winningTrades++;
                    totalProfit = totalProfit.add(order.getRealizedPnl());
                } else {
                    totalLoss = totalLoss.add(order.getRealizedPnl().abs());
                }
            }
        }
        
        // 计算指标
        metrics.setTotalTrades(totalTrades);
        metrics.setWinningTrades(winningTrades);
        metrics.setLosingTrades(totalTrades - winningTrades);
        
        if (totalTrades > 0) {
            metrics.setWinRate(BigDecimal.valueOf(winningTrades)
                .divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)));
        }
        
        if (winningTrades > 0) {
            metrics.setAverageWin(totalProfit.divide(
                BigDecimal.valueOf(winningTrades), 2, RoundingMode.HALF_UP));
        }
        
        if (totalTrades - winningTrades > 0) {
            metrics.setAverageLoss(totalLoss.divide(
                BigDecimal.valueOf(totalTrades - winningTrades), 2, RoundingMode.HALF_UP));
        }
        
        // 盈亏比
        if (metrics.getAverageLoss() != null && metrics.getAverageLoss().compareTo(BigDecimal.ZERO) > 0) {
            metrics.setProfitFactor(metrics.getAverageWin()
                .divide(metrics.getAverageLoss(), 2, RoundingMode.HALF_UP));
        }
        
        log.info("策略性能评估: 总交易={}, 胜率={}%, 盈亏比={}", 
            totalTrades, metrics.getWinRate(), metrics.getProfitFactor());
        
        return metrics;
    }
    
    /**
     * 创建无操作信号
     */
    private TradingSignal createNoActionSignal(String symbol) {
        return TradingSignal.builder()
            .symbol(symbol)
            .type(TradingSignal.SignalType.NO_ACTION)
            .confidence(BigDecimal.ZERO)
            .reason("等待交易机会")
            .timestamp(LocalDateTime.now())
            .build();
    }
}