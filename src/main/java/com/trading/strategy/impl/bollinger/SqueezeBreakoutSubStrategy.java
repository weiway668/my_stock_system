package com.trading.strategy.impl.bollinger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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
 * 布林带挤压突破子策略
 * 核心理念：在布林带收窄（挤压）后，当价格放量突破时进行交易。
 */
@Slf4j
@RequiredArgsConstructor
public class SqueezeBreakoutSubStrategy implements BollingerBandSubStrategy {

    private final BollingerBandConfig.SqueezeBreakout config;

    private enum SqueezeState {
        SQUEEZING, BREAKOUT, NORMAL
    }

    @Override
    public String getName() {
        return "布林带挤压突破策略";
    }

    @Override
    public Optional<TradingStrategy.TradingSignal> generateSignal(MarketData marketData, List<TechnicalIndicators> indicatorHistory, List<Position> positions) {
        if (!config.isEnabled() || indicatorHistory.size() < config.getBreakoutLookback()) {
            return Optional.empty();
        }

        SqueezeState state = detectSqueezeState(indicatorHistory);

        if (state == SqueezeState.BREAKOUT) {
            boolean hasPosition = positions.stream()
                    .anyMatch(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0);

            if (hasPosition) {
                return Optional.empty();
            }

            TechnicalIndicators currentIndicators = indicatorHistory.get(indicatorHistory.size() - 1);
            BigDecimal price = marketData.getClose();
            BigDecimal middle = currentIndicators.getMiddleBand();
            BigDecimal avgVolume = currentIndicators.getVolumeSma();
            BigDecimal currentVolume = marketData.getVolume() != null ? BigDecimal.valueOf(marketData.getVolume()) : BigDecimal.ZERO;

            boolean isVolumeConfirmed = avgVolume != null && currentVolume.compareTo(avgVolume.multiply(config.getVolumeMultiplier())) > 0;

            if (price.compareTo(middle) > 0 && isVolumeConfirmed) {
                TradingStrategy.TradingSignal signal = TradingStrategy.TradingSignal.builder()
                        .symbol(marketData.getSymbol())
                        .type(TradingStrategy.TradingSignal.SignalType.BUY)
                        .price(price)
                        .confidence(BigDecimal.valueOf(0.9))
                        .reason(String.format("布林带挤压后放量突破（价格:%.2f，成交量:%.0f）",
                                price.doubleValue(), currentVolume.doubleValue()))
                        .build();
                return Optional.of(signal);
            }
        }

        return Optional.empty();
    }

    private SqueezeState detectSqueezeState(List<TechnicalIndicators> indicatorHistory) {
        List<TechnicalIndicators> recentData = indicatorHistory.stream()
                .skip(indicatorHistory.size() - config.getBreakoutLookback())
                .collect(Collectors.toList());

        long squeezePeriods = recentData.stream()
                .map(this::calculateBandwidthPercentage)
                .filter(Objects::nonNull)
                .filter(bwp -> bwp.compareTo(config.getSqueezeThreshold()) < 0)
                .count();

        boolean isSqueezing = squeezePeriods >= config.getMinSqueezePeriods();

        if (isSqueezing && recentData.size() >= 2) {
            TechnicalIndicators current = recentData.get(recentData.size() - 1);
            TechnicalIndicators previous = recentData.get(recentData.size() - 2);

            if (current.getBandwidth() != null && previous.getBandwidth() != null) {
                BigDecimal expansionThreshold = previous.getBandwidth().multiply(BigDecimal.ONE.add(config.getExpansionFactor()));
                if (current.getBandwidth().compareTo(expansionThreshold) > 0) {
                    return SqueezeState.BREAKOUT;
                }
            }
            return SqueezeState.SQUEEZING;
        }

        return SqueezeState.NORMAL;
    }

    private BigDecimal calculateBandwidthPercentage(TechnicalIndicators indicators) {
        if (indicators.getUpperBand() == null || indicators.getLowerBand() == null || indicators.getMiddleBand() == null ||
                indicators.getMiddleBand().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal bandwidth = indicators.getUpperBand().subtract(indicators.getLowerBand());
        return bandwidth.divide(indicators.getMiddleBand(), 4, RoundingMode.HALF_UP);
    }
}