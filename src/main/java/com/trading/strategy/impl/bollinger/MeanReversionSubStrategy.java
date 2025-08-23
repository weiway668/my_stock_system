package com.trading.strategy.impl.bollinger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.trading.config.BollingerBandConfig;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Position;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.strategy.TradingStrategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 布林带均值回归子策略
 * 核心理念：价格围绕均值上下波动，在下轨买入，在上轨或中轨卖出。
 */
@Slf4j
@RequiredArgsConstructor
public class MeanReversionSubStrategy implements BollingerBandSubStrategy {

    private final BollingerBandConfig.MeanReversion config;

    private static final Map<String, Integer> POSITION_SCORES = new HashMap<>();
    static {
        POSITION_SCORES.put("below_lower", 100);
        POSITION_SCORES.put("near_lower", 80);
        POSITION_SCORES.put("lower_to_middle", 60);
        POSITION_SCORES.put("middle", 40);
        POSITION_SCORES.put("middle_to_upper", 20);
        POSITION_SCORES.put("near_upper", -50);
        POSITION_SCORES.put("above_upper", -100);
    }

    @Override
    public String getName() {
        return "布林带均值回归策略";
    }

    @Override
    public Optional<TradingStrategy.TradingSignal> generateSignal(MarketData marketData, List<TechnicalIndicators> indicatorHistory, List<Position> positions) {
        if (!config.isEnabled()) {
            return Optional.empty();
        }

        TechnicalIndicators indicators = indicatorHistory.get(indicatorHistory.size() - 1);
        if (indicators.getUpperBand() == null || indicators.getMiddleBand() == null || indicators.getLowerBand() == null) {
            return Optional.empty();
        }

        BigDecimal price = marketData.getClose();
        BigDecimal upper = indicators.getUpperBand();
        BigDecimal middle = indicators.getMiddleBand();
        BigDecimal lower = indicators.getLowerBand();

        int positionScore = calculateBBPositionScore(price, upper, middle, lower);

        boolean hasPosition = positions.stream()
                .anyMatch(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0);

        if (!hasPosition && positionScore >= config.getMinBuyScore()) {
            if (confirmNotTrend(indicatorHistory)) {
                TradingStrategy.TradingSignal signal = TradingStrategy.TradingSignal.builder()
                        .symbol(marketData.getSymbol())
                        .type(TradingStrategy.TradingSignal.SignalType.BUY)
                        .price(price)
                        .confidence(calculateSignalStrength(positionScore, true))
                        .reason(String.format("布林带均值回归买入（位置分:%d，价格:%.2f，下轨:%.2f）",
                                positionScore, price.doubleValue(), lower.doubleValue()))
                        .build();
                return Optional.of(signal);
            }
        } else if (hasPosition && price.compareTo(upper) >= 0) {
            TradingStrategy.TradingSignal signal = TradingStrategy.TradingSignal.builder()
                    .symbol(marketData.getSymbol())
                    .type(TradingStrategy.TradingSignal.SignalType.SELL)
                    .price(price)
                    .confidence(BigDecimal.ONE)
                    .reason(String.format("布林带上轨止盈（价格:%.2f，上轨:%.2f）",
                            price.doubleValue(), upper.doubleValue()))
                    .build();
            return Optional.of(signal);
        }

        return Optional.empty();
    }

    private boolean confirmNotTrend(List<TechnicalIndicators> indicatorHistory) {
        int lookbackPeriod = 10;
        if (indicatorHistory.size() < lookbackPeriod) {
            return true;
        }

        TechnicalIndicators currentIndicators = indicatorHistory.get(indicatorHistory.size() - 1);
        BigDecimal currentBandwidth = currentIndicators.getBandwidth();

        if (currentBandwidth == null) {
            return false;
        }

        List<BigDecimal> recentBandwidths = indicatorHistory.stream()
                .skip(indicatorHistory.size() - lookbackPeriod)
                .map(TechnicalIndicators::getBandwidth)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (recentBandwidths.size() < lookbackPeriod / 2) {
            return true;
        }

        BigDecimal avgBandwidth = recentBandwidths.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(recentBandwidths.size()), 4, RoundingMode.HALF_UP);

        if (currentBandwidth.compareTo(avgBandwidth.multiply(config.getExpansionThreshold())) > 0) {
            return false;
        }

        return true;
    }

    private int calculateBBPositionScore(BigDecimal price, BigDecimal upper, BigDecimal middle, BigDecimal lower) {
        BigDecimal bbWidth = upper.subtract(lower);
        if (bbWidth.compareTo(BigDecimal.ZERO) <= 0) {
            return 40;
        }

        BigDecimal positionRatio = price.subtract(lower).divide(bbWidth, 4, RoundingMode.HALF_UP);

        if (price.compareTo(lower) < 0) return POSITION_SCORES.get("below_lower");
        if (positionRatio.compareTo(BigDecimal.valueOf(0.2)) < 0) return POSITION_SCORES.get("near_lower");
        if (price.compareTo(middle) < 0) return POSITION_SCORES.get("lower_to_middle");
        if (positionRatio.compareTo(BigDecimal.valueOf(0.55)) < 0) return POSITION_SCORES.get("middle");
        if (positionRatio.compareTo(BigDecimal.valueOf(0.8)) < 0) return POSITION_SCORES.get("middle_to_upper");
        if (price.compareTo(upper) < 0) return POSITION_SCORES.get("near_upper");
        return POSITION_SCORES.get("above_upper");
    }

    private BigDecimal calculateSignalStrength(int positionScore, boolean isBuy) {
        if (isBuy) {
            if (positionScore >= 100) return BigDecimal.valueOf(1.0);
            if (positionScore >= 80) return BigDecimal.valueOf(0.8);
            if (positionScore >= 60) return BigDecimal.valueOf(0.6);
            return BigDecimal.valueOf(0.4);
        } else {
            if (positionScore <= -50) return BigDecimal.valueOf(1.0);
            if (positionScore <= 0) return BigDecimal.valueOf(0.8);
            if (positionScore <= 20) return BigDecimal.valueOf(0.6);
            return BigDecimal.valueOf(0.4);
        }
    }
}