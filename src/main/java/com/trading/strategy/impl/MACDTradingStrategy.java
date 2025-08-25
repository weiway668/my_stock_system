package com.trading.strategy.impl;

import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.vo.TechnicalIndicators;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MACD交易策略实现
 * 基于MACD指标的金叉死叉信号进行交易
 */
@Slf4j
@Component("macdStrategy")
public class MACDTradingStrategy extends AbstractTradingStrategy {
    
    // 策略参数
    private static final String PARAM_POSITION_RATIO = "positionRatio";
    private static final String PARAM_STOP_LOSS_RATIO = "stopLossRatio";
    private static final String PARAM_TAKE_PROFIT_RATIO = "takeProfitRatio";
    private static final String PARAM_MIN_CONFIDENCE = "minConfidence";
    private static final String PARAM_MAX_POSITIONS = "maxPositions";
    
    // 默认参数值
    private static final BigDecimal DEFAULT_POSITION_RATIO = BigDecimal.valueOf(0.1);
    private static final BigDecimal DEFAULT_STOP_LOSS_RATIO = BigDecimal.valueOf(0.05);
    private static final BigDecimal DEFAULT_TAKE_PROFIT_RATIO = BigDecimal.valueOf(0.1);
    private static final BigDecimal DEFAULT_MIN_CONFIDENCE = BigDecimal.valueOf(0.6);
    private static final int DEFAULT_MAX_POSITIONS = 3;
    
    // 历史指标缓存
    private TechnicalIndicators previousIndicators;
    
    public MACDTradingStrategy() {
        super();
        this.name = "MACD Trading Strategy";
        this.version = "1.0.0";
        this.description = "基于MACD金叉死叉信号的交易策略";
    }
    
    @Override
    protected void doInitialize() {
        // 设置默认参数
        setParameter(PARAM_POSITION_RATIO, DEFAULT_POSITION_RATIO);
        setParameter(PARAM_STOP_LOSS_RATIO, DEFAULT_STOP_LOSS_RATIO);
        setParameter(PARAM_TAKE_PROFIT_RATIO, DEFAULT_TAKE_PROFIT_RATIO);
        setParameter(PARAM_MIN_CONFIDENCE, DEFAULT_MIN_CONFIDENCE);
        setParameter(PARAM_MAX_POSITIONS, DEFAULT_MAX_POSITIONS);
        
        log.info("MACD策略初始化完成，参数: {}", parameters);
    }
    
    @Override
    protected TradingSignal generateStrategySignal(MarketData marketData, List<com.trading.domain.entity.HistoricalKLineEntity> historicalKlines, List<TechnicalIndicators> indicatorHistory, List<Position> positions) {
        
        if (indicatorHistory == null || indicatorHistory.size() < 2) {
            return createNoActionSignal(marketData.getSymbol(), "指标历史不足");
        }

        // 检查是否已持仓
        Optional<Position> positionOpt = positions.stream()
                .filter(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0)
                .findFirst();

        // 1. 如果持仓，则检查卖出信号
        if (positionOpt.isPresent()) {
            Optional<TradingSignal> sellSignal = checkForBaseSellSignal(positionOpt.get(), marketData, historicalKlines, indicatorHistory);
            // 如果卖出框架有信号，则返回该信号；否则，持有不动
            return sellSignal.orElse(createNoActionSignal(marketData.getSymbol(), "持仓中，未触发卖出条件"));
        }

        // 2. 如果未持仓，则在满足过滤条件的前提下，检查买入信号
        TechnicalIndicators indicators = indicatorHistory.get(indicatorHistory.size() - 1);
        TechnicalIndicators previousIndicators = indicatorHistory.get(indicatorHistory.size() - 2);

        if (isTrendFavorable(marketData, indicatorHistory) && isVolatilityAdequate(marketData, historicalKlines, indicatorHistory)) {
            if (previousIndicators.hasBullishMacdCrossover(indicators)) {
                BigDecimal confidence = calculateBullishConfidence(indicators, marketData);
                int maxPositions = getParameter(PARAM_MAX_POSITIONS, DEFAULT_MAX_POSITIONS);

                if (confidence.compareTo(getParameter(PARAM_MIN_CONFIDENCE, DEFAULT_MIN_CONFIDENCE)) >= 0 && positions.size() < maxPositions) {
                    log.info("MACD金叉买入信号: symbol={}, confidence={}", marketData.getSymbol(), confidence);
                    return createSignal(marketData.getSymbol(), TradingSignal.SignalType.BUY, marketData.getClose(), confidence.doubleValue(), "MACD金叉，趋势与波动性确认");
                }
            }
        }
        
        // 3. 无任何操作
        return createNoActionSignal(marketData.getSymbol(), "等待交易机会");
    }
    
    @Override
    public int calculatePositionSize(
            TradingSignal signal, 
            BigDecimal accountBalance,
            BigDecimal currentPrice) {
        
        if (signal.getType() == TradingSignal.SignalType.NO_ACTION 
            || signal.getType() == TradingSignal.SignalType.HOLD) {
            return 0;
        }
        
        // 获取仓位比例参数
        BigDecimal positionRatio = getParameter(PARAM_POSITION_RATIO, DEFAULT_POSITION_RATIO);
        
        // 计算可用资金
        BigDecimal availableFunds = accountBalance.multiply(positionRatio);
        
        // 根据信号置信度调整仓位
        BigDecimal adjustedFunds = availableFunds.multiply(signal.getConfidence());
        
        // 计算股数（向下取整到100的整数倍）
        int shares = adjustedFunds.divide(currentPrice, 0, RoundingMode.DOWN).intValue();
        shares = (shares / 100) * 100; // 香港市场一手100股
        
        log.debug("计算仓位大小: balance={}, price={}, shares={}", 
            accountBalance, currentPrice, shares);
        
        return shares;
    }
    
    @Override
    public Order applyRiskManagement(Order order, MarketData marketData) {
        if (order == null) {
            return null;
        }
        
        BigDecimal entryPrice = order.getPrice() != null ? 
            order.getPrice() : marketData.getClose();
        
        // 获取止损止盈参数
        BigDecimal stopLossRatio = getParameter(PARAM_STOP_LOSS_RATIO, DEFAULT_STOP_LOSS_RATIO);
        BigDecimal takeProfitRatio = getParameter(PARAM_TAKE_PROFIT_RATIO, DEFAULT_TAKE_PROFIT_RATIO);
        
        // 根据订单方向设置止损止盈
        if (order.getSide() == OrderSide.BUY) {
            // 买单：止损在下方，止盈在上方
            order.setStopLoss(entryPrice.multiply(BigDecimal.ONE.subtract(stopLossRatio)));
            order.setTakeProfit(entryPrice.multiply(BigDecimal.ONE.add(takeProfitRatio)));
        } else {
            // 卖单：止损在上方，止盈在下方
            order.setStopLoss(entryPrice.multiply(BigDecimal.ONE.add(stopLossRatio)));
            order.setTakeProfit(entryPrice.multiply(BigDecimal.ONE.subtract(takeProfitRatio)));
        }
        
        log.info("应用风险管理: orderId={}, stopLoss={}, takeProfit={}", 
            order.getOrderId(), order.getStopLoss(), order.getTakeProfit());
        
        return order;
    }
    
    /**
     * 计算看涨信号置信度
     */
    private BigDecimal calculateBullishConfidence(TechnicalIndicators indicators, MarketData marketData) {
        BigDecimal confidence = BigDecimal.valueOf(0.5); // 基础置信度
        
        // MACD柱状图为正且增强
        if (indicators.getHistogram() != null && indicators.getHistogram().compareTo(BigDecimal.ZERO) > 0) {
            confidence = confidence.add(BigDecimal.valueOf(0.2));
        }
        
        // RSI未超买
        if (indicators.getRsi() != null) {
            if (indicators.getRsi().compareTo(BigDecimal.valueOf(30)) > 0 
                && indicators.getRsi().compareTo(BigDecimal.valueOf(70)) < 0) {
                confidence = confidence.add(BigDecimal.valueOf(0.2));
            } else if (indicators.getRsi().compareTo(BigDecimal.valueOf(70)) >= 0) {
                confidence = confidence.subtract(BigDecimal.valueOf(0.1)); // RSI超买，降低置信度
            }
        }
        
        // 价格在布林带中轨以上
        if (indicators.getMiddleBand() != null 
            && marketData.getClose().compareTo(indicators.getMiddleBand()) > 0) {
            confidence = confidence.add(BigDecimal.valueOf(0.1));
        }
        
        // 成交量支持
        if (indicators.getVolumeRatio() != null 
            && indicators.getVolumeRatio().compareTo(BigDecimal.valueOf(1.2)) > 0) {
            confidence = confidence.add(BigDecimal.valueOf(0.1));
        }
        
        // 限制置信度在0-1之间
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            confidence = BigDecimal.ONE;
        } else if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            confidence = BigDecimal.ZERO;
        }
        
        return confidence;
    }
    
    /**
     * 计算看跌信号置信度
     */
    private BigDecimal calculateBearishConfidence(TechnicalIndicators indicators, MarketData marketData) {
        BigDecimal confidence = BigDecimal.valueOf(0.5); // 基础置信度
        
        // MACD柱状图为负且减弱
        if (indicators.getHistogram() != null && indicators.getHistogram().compareTo(BigDecimal.ZERO) < 0) {
            confidence = confidence.add(BigDecimal.valueOf(0.2));
        }
        
        // RSI未超卖
        if (indicators.getRsi() != null) {
            if (indicators.getRsi().compareTo(BigDecimal.valueOf(30)) > 0 
                && indicators.getRsi().compareTo(BigDecimal.valueOf(70)) < 0) {
                confidence = confidence.add(BigDecimal.valueOf(0.2));
            } else if (indicators.getRsi().compareTo(BigDecimal.valueOf(30)) <= 0) {
                confidence = confidence.subtract(BigDecimal.valueOf(0.1)); // RSI超卖，降低置信度
            }
        }
        
        // 价格在布林带中轨以下
        if (indicators.getMiddleBand() != null 
            && marketData.getClose().compareTo(indicators.getMiddleBand()) < 0) {
            confidence = confidence.add(BigDecimal.valueOf(0.1));
        }
        
        // 成交量支持
        if (indicators.getVolumeRatio() != null 
            && indicators.getVolumeRatio().compareTo(BigDecimal.valueOf(1.2)) > 0) {
            confidence = confidence.add(BigDecimal.valueOf(0.1));
        }
        
        // 限制置信度在0-1之间
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            confidence = BigDecimal.ONE;
        } else if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            confidence = BigDecimal.ZERO;
        }
        
        return confidence;
    }

    @Override
    protected void doDestroy() {
        // TODO Auto-generated method stub
    }
}