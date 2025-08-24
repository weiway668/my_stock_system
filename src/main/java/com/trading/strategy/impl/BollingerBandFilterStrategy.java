package com.trading.strategy.impl;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.trading.config.BollingerBandConfig;
import com.trading.config.BollingerBandFilterConfig;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.vo.TechnicalIndicators;

import lombok.extern.slf4j.Slf4j;

/**
 * 方案四：布林带组合过滤系统
 * <p>
 * 通过一系列独立的过滤器对当前的市场状态进行多维度打分，最终综合判断是否值得交易。
 * 旨在提高信号质量，过滤掉大量的“假信号”和低质量的交易机会。
 * </p>
 */
@Slf4j
@Component("BOLL_FILTER")
public class BollingerBandFilterStrategy extends AbstractTradingStrategy {

    private final BollingerBandFilterConfig config;

    // Hardcoded advanced rules for now
    private final double buyVolumeFactor = 1.2;
    private final double forbiddenUpperBandProximity = 0.95;
    private final double forbiddenSqueezeWidth = 0.015;
    private final double sellUpperBandProximity = 0.98;
    private final int maxPositionDays = 3;

    public BollingerBandFilterStrategy(BollingerBandFilterConfig config) {
        this.config = config;
        this.enabled = config.isEnabled();
        this.name = "布林带高级规则策略";
        this.version = "2.0";
    }

    @Override
    public List<BollingerBandConfig.ParameterSet> getRequiredBollingerBandSets() {
        BollingerBandConfig.ParameterSet defaultSet = new BollingerBandConfig.ParameterSet();
        defaultSet.setKey("default");
        defaultSet.setPeriod(20);
        defaultSet.setStdDev(2.0);
        return Collections.singletonList(defaultSet);
    }

    @Override
    protected void doInitialize() {
        log.info("Initializing Advanced Bollinger Band Strategy with config: {}", config);
    }

    @Override
    protected void doDestroy() {
        log.info("Destroying Advanced Bollinger Band Strategy.");
    }

    @Override
    public TradingSignal generateSignal(MarketData marketData, List<TechnicalIndicators> indicatorHistory,
            List<Position> positions) {
        if (indicatorHistory.size() < 2) {
            return createNoActionSignal(marketData.getSymbol(), "指标历史不足");
        }

        TechnicalIndicators currentIndicators = indicatorHistory.get(indicatorHistory.size() - 1);
        TechnicalIndicators prevIndicators = indicatorHistory.get(indicatorHistory.size() - 2);
        TechnicalIndicators.BollingerBandSet bb = currentIndicators.getBollingerBands().get("default");

        if (bb == null || bb.getUpperBand() == null || bb.getMiddleBand() == null || bb.getLowerBand() == null) {
            return createNoActionSignal(marketData.getSymbol(), "缺少默认的布林带指标");
        }

        Optional<Position> positionOpt = positions.stream()
                .filter(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0)
                .findFirst();

        if (positionOpt.isPresent()) {
            return checkForSellSignal(marketData, currentIndicators, prevIndicators, positionOpt.get());
        } else {
            return checkForBuySignal(marketData, currentIndicators, prevIndicators);
        }
    }

    private TradingSignal checkForSellSignal(MarketData marketData, TechnicalIndicators currentIndicators,
            TechnicalIndicators prevIndicators, Position position) {
        TechnicalIndicators.BollingerBandSet bb = currentIndicators.getBollingerBands().get("default");
        BigDecimal price = marketData.getClose();

        // 规则1: price > upper * 0.98
        if (price.compareTo(bb.getUpperBand().multiply(BigDecimal.valueOf(sellUpperBandProximity))) > 0) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, price, 1.0, "价格触及上轨卖出");
        }

        // 规则2: price > middle AND position_days > 3
        long positionDays = ChronoUnit.DAYS.between(position.getOpenTime(), marketData.getTimestamp());
        if (price.compareTo(bb.getMiddleBand()) > 0 && positionDays > maxPositionDays) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, price, 1.0, "中轨上方持仓超3天卖出");
        }

        // 规则3: bb_width expanding AND price falling
        BigDecimal currentWidth = bb.getUpperBand().subtract(bb.getLowerBand());
        TechnicalIndicators.BollingerBandSet prevBb = prevIndicators.getBollingerBands().get("default");
        BigDecimal prevWidth = prevBb.getUpperBand().subtract(prevBb.getLowerBand());
        boolean isWidthExpanding = currentWidth.compareTo(prevWidth) > 0;
        boolean isPriceFalling = price.compareTo(marketData.getOpen()) < 0; // Simple check for falling price in current
                                                                            // bar

        if (isWidthExpanding && isPriceFalling) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, price, 1.0, "带宽扩张且价格下跌卖出");
        }

        return createNoActionSignal(marketData.getSymbol(), "持有仓位，未触发卖出条件");
    }

    private TradingSignal checkForBuySignal(MarketData marketData, TechnicalIndicators currentIndicators,
            TechnicalIndicators prevIndicators) {
        TechnicalIndicators.BollingerBandSet bb = currentIndicators.getBollingerBands().get("default");
        BigDecimal price = marketData.getClose();
        BigDecimal width = bb.getUpperBand().subtract(bb.getLowerBand()).divide(bb.getMiddleBand(), 4, java.math.RoundingMode.HALF_UP);

        // --- 详细日志记录 ---
        log.debug("--- Buy Signal Check for {} at {} ---", marketData.getSymbol(), marketData.getTimestamp());
        log.debug("Price: {}, Upper: {}, Middle: {}, Lower: {}, Width: {}", 
            price, bb.getUpperBand(), bb.getMiddleBand(), bb.getLowerBand(), width);

        // --- 检查禁止买入区域 ---
        boolean isForbiddenPrice = price.compareTo(bb.getUpperBand().multiply(BigDecimal.valueOf(forbiddenUpperBandProximity))) > 0;
        log.debug("[Forbidden Rule 1] Price > Upper * {}: {} (Price={}, Threshold={})", 
            forbiddenUpperBandProximity, isForbiddenPrice, price, bb.getUpperBand().multiply(BigDecimal.valueOf(forbiddenUpperBandProximity)));
        if (isForbiddenPrice) {
            return createNoActionSignal(marketData.getSymbol(), "禁止买入: 价格过高，接近上轨");
        }

        boolean isForbiddenWidth = width.compareTo(BigDecimal.valueOf(forbiddenSqueezeWidth)) < 0;
        log.debug("[Forbidden Rule 2] Width < {}: {} (Width={})", forbiddenSqueezeWidth, isForbiddenWidth, width);
        if (isForbiddenWidth) {
            return createNoActionSignal(marketData.getSymbol(), "禁止买入: 布林带挤压");
        }

        BigDecimal middleBandSlope = bb.getMiddleBand()
                .subtract(prevIndicators.getBollingerBands().get("default").getMiddleBand());
        boolean isForbiddenMomentum = price.compareTo(bb.getMiddleBand()) > 0 && middleBandSlope.compareTo(BigDecimal.ZERO) < 0;
        log.debug("[Forbidden Rule 3] Price > Middle AND Momentum < 0: {} (Price={}, Middle={}, Slope={})", 
            isForbiddenMomentum, price, bb.getMiddleBand(), middleBandSlope);
        if (isForbiddenMomentum) {
            return createNoActionSignal(marketData.getSymbol(), "禁止买入: 中轨上方但动能减弱");
        }

        // --- 检查买入条件 ---
        boolean positionCondition = price.compareTo(bb.getMiddleBand()) < 0;
        log.debug("[Buy Condition 1] Price < Middle: {} (Price={}, Middle={})", positionCondition, price, bb.getMiddleBand());

        boolean widthCondition = width.compareTo(BigDecimal.valueOf(0.015)) > 0
                && width.compareTo(BigDecimal.valueOf(0.08)) < 0;
        log.debug("[Buy Condition 2] 0.015 < Width < 0.05: {} (Width={})", widthCondition, width);

        Long currentVolume = currentIndicators.getVolume();
        BigDecimal avgVolume = currentIndicators.getVolumeSma();
        boolean volumeCondition = false;
        if (currentVolume != null && avgVolume != null && avgVolume.compareTo(BigDecimal.ZERO) != 0) {
            volumeCondition = BigDecimal.valueOf(currentVolume)
                    .compareTo(avgVolume.multiply(BigDecimal.valueOf(buyVolumeFactor))) > 0;
            log.debug("[Buy Condition 3] Volume > AvgVolume * {}: {} (Volume={}, AvgVolumeThreshold={})", 
                buyVolumeFactor, volumeCondition, currentVolume, avgVolume.multiply(BigDecimal.valueOf(buyVolumeFactor)));
        } else {
            log.debug("[Buy Condition 3] Volume > AvgVolume * {}: false (Volume data insufficient)", buyVolumeFactor);
        }

        if (positionCondition && widthCondition && volumeCondition) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.BUY, price, 0.75, "满足所有买入条件");
        }

        return createNoActionSignal(marketData.getSymbol(), "未满足买入条件");
    }

    private TradingSignal createSignal(String symbol, TradingSignal.SignalType type, BigDecimal price,
            double confidence, String reason) {
        return TradingSignal.builder()
                .symbol(symbol)
                .type(type)
                .price(price)
                .confidence(BigDecimal.valueOf(confidence))
                .reason(reason)
                .build();
    }

    private TradingSignal createNoActionSignal(String symbol, String reason) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.NO_ACTION)
                .reason(reason)
                .build();
    }

    @Override
    public int calculatePositionSize(TradingSignal signal, BigDecimal availableCash, BigDecimal currentPrice) {
        if (signal.getType() == TradingSignal.SignalType.BUY) {
            // 默认返回100股作为示例
            return 100;
        }
        // 卖出时，返回最大值以清仓
        return Integer.MAX_VALUE;
    }

    @Override
    public Order applyRiskManagement(Order order, MarketData marketData) {
        if (config.getRiskManagement().isEnabled() && order.getSide() == com.trading.domain.enums.OrderSide.BUY) {
            BigDecimal stopLossPrice = order.getPrice().multiply(
                    BigDecimal.ONE.subtract(BigDecimal.valueOf(config.getRiskManagement().getStopLossPercentage())));
            order.setStopLoss(stopLossPrice.setScale(2, java.math.RoundingMode.DOWN));
            log.info("为订单 {} 设置止损价: {}", order.getOrderId(), order.getStopLoss());
        }
        return order;
    }
}