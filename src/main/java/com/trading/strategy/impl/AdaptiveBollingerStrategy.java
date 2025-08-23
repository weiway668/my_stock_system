package com.trading.strategy.impl;

import com.trading.config.BollingerBandConfig;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.strategy.TradingStrategy;
import com.trading.strategy.UsesBollingerBands;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 自适应布林带策略
 * <p>
 * 根据市场状态（趋势、盘整、高波动）动态选择不同的布林带参数集和交易逻辑。
 * </p>
 */
@Slf4j
@Component("BOLL_ADAPTIVE")
public class AdaptiveBollingerStrategy extends AbstractTradingStrategy implements UsesBollingerBands {

    private final BollingerBandConfig config;

    private enum MarketState {
        TRENDING, RANGING, VOLATILE
    }

    public AdaptiveBollingerStrategy(BollingerBandConfig config) {
        this.config = config;
        this.enabled = config.isEnabled();
        this.name = "自适应布林带策略";
        this.version = "1.0";
    }

    @Override
    protected void doInitialize() {
        log.info("初始化自适应布林带策略，配置: {}", config.getAdaptive());
    }

    @Override
    protected void doDestroy() {
        log.info("销毁自适应布林带策略");
    }

    @Override
    public TradingSignal generateSignal(MarketData marketData, List<TechnicalIndicators> indicatorHistory,
            List<Position> positions) {
        if (!this.isEnabled() || indicatorHistory.isEmpty()) {
            return createNoActionSignal("策略未启用或指标历史为空");
        }

        TechnicalIndicators indicators = indicatorHistory.get(indicatorHistory.size() - 1);
        MarketState marketState = identifyMarketState(marketData, indicators);

        return switch (marketState) {
            case RANGING -> handleRangingMarket(marketData, indicators, positions);
            case TRENDING -> handleTrendingMarket(marketData, indicators, positions);
            case VOLATILE -> handleVolatileMarket(marketData, indicators, positions);
        };
    }

    private MarketState identifyMarketState(MarketData marketData, TechnicalIndicators indicators) {
        BigDecimal adx = indicators.getAdx();
        BigDecimal atr = indicators.getAtr();
        BigDecimal close = marketData.getClose(); // Use actual close price

        if (adx == null || atr == null || close == null || close.compareTo(BigDecimal.ZERO) == 0) {
            return MarketState.VOLATILE; // Data incomplete, assume volatile
        }

        if (adx.doubleValue() > config.getAdaptive().getAdxThreshold()) {
            return MarketState.TRENDING;
        }

        BigDecimal atrRatio = atr.divide(close, 4, BigDecimal.ROUND_HALF_UP);
        if (atrRatio.doubleValue() < config.getAdaptive().getAtrRatioThreshold()) {
            return MarketState.RANGING;
        }

        return MarketState.VOLATILE;
    }

    private TradingSignal handleRangingMarket(MarketData marketData, TechnicalIndicators indicators,
            List<Position> positions) {
        String paramsKey = config.getAdaptive().getRangingParamsKey();
        TechnicalIndicators.BollingerBandSet bb = indicators.getBollingerBands().get(paramsKey);
        if (bb == null){
            return createNoActionSignal("缺少盘整市参数的布林带指标: " + paramsKey);
        }
        // 在盘整市，使用均值回归逻辑
        BigDecimal price = marketData.getClose();
        boolean hasPosition = positions.stream()
                .anyMatch(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0);

        if (!hasPosition && price.compareTo(bb.getLowerBand()) <= 0) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.BUY, price, 0.8, "盘整市触及布林带下轨");
        } else if (hasPosition && price.compareTo(bb.getUpperBand()) >= 0) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, price, 0.8, "盘整市触及布林带上轨");
        }
        return createNoActionSignal("盘整市，等待价格触及轨道");
    }

    private TradingSignal handleTrendingMarket(MarketData marketData, TechnicalIndicators indicators,
            List<Position> positions) {
        String paramsKey = config.getAdaptive().getTrendingParamsKey();
        TechnicalIndicators.BollingerBandSet bb = indicators.getBollingerBands().get(paramsKey);
        if (bb == null)
            return createNoActionSignal("缺少趋势市参数的布林带指标: " + paramsKey);

        // 在趋势市，使用突破逻辑
        BigDecimal price = marketData.getClose();
        boolean hasPosition = positions.stream()
                .anyMatch(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0);

        if (!hasPosition && price.compareTo(bb.getMiddleBand()) > 0) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.BUY, price, 0.6, "趋势市突破布林带中轨");
        }
        // 在趋势市，通常不轻易做空，仅在价格跌破中轨时平仓
        else if (hasPosition && price.compareTo(bb.getMiddleBand()) < 0) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, price, 0.9, "趋势市跌破中轨，止盈/止损");
        }
        return createNoActionSignal("趋势市，等待回调或突破信号");
    }

    private TradingSignal handleVolatileMarket(MarketData marketData, TechnicalIndicators indicators,
            List<Position> positions) {
        // 在高波动市场，策略保持谨慎，不进行任何操作
        return createNoActionSignal("市场波动剧烈，暂停交易");
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

    private TradingSignal createNoActionSignal(String reason) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.NO_ACTION)
                .reason(reason)
                .build();
    }

    // We can leave calculatePositionSize and applyRiskManagement with default
    // implementations from AbstractTradingStrategy for now
    @Override
    public int calculatePositionSize(TradingSignal signal, BigDecimal availableCash, BigDecimal currentPrice) {
        if (signal.getType() == TradingSignal.SignalType.BUY) {
            return 100; // Simplified for now
        }
        return Integer.MAX_VALUE; // Sell all
    }

    @Override
    public Order applyRiskManagement(Order order, MarketData marketData) {
        return order;
    }
}
