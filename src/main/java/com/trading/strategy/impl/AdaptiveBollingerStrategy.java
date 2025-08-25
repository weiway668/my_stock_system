package com.trading.strategy.impl;

import com.trading.config.BollingerBandConfig;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.vo.TechnicalIndicators;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 自适应布林带策略
 * <p>
 * 根据市场状态（趋势、盘整、高波动）动态选择不同的布林带参数集和交易逻辑。
 * </p>
 */
@Slf4j
@Component("BOLL_ADAPTIVE")
public class AdaptiveBollingerStrategy extends AbstractTradingStrategy {

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
    public List<BollingerBandConfig.ParameterSet> getRequiredBollingerBandSets() {
        // 该策略需要使用配置文件中定义的所有布林带参数集
        return config.getParameterSets();
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
    protected TradingSignal generateStrategySignal(MarketData marketData, List<com.trading.domain.entity.HistoricalKLineEntity> historicalKlines, List<TechnicalIndicators> indicatorHistory, List<Position> positions) {
        if (!this.isEnabled() || indicatorHistory.isEmpty()) {
            return createNoActionSignal(marketData.getSymbol(),"策略未启用或指标历史为空");
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
        
        BigDecimal atrRatio = atr.divide(close, 4, RoundingMode.HALF_UP);
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
            return createNoActionSignal(marketData.getSymbol(),"缺少盘整市参数的布林带指标: " + paramsKey);
        }
        // 在盘整市，使用均值回归逻辑
        BigDecimal price = marketData.getClose();
        boolean hasPosition = positions.stream()
                .anyMatch(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0);

        BigDecimal lowerBandWithBuffer = bb.getLowerBand().multiply(BigDecimal.valueOf(config.getAdaptive().getRangingLowerBufferFactor()));
        BigDecimal upperBandWithBuffer = bb.getUpperBand().multiply(BigDecimal.valueOf(config.getAdaptive().getRangingUpperBufferFactor()));

        if (!hasPosition && price.compareTo(lowerBandWithBuffer) <= 0) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.BUY, price, 0.8, "盘整市接近布林带下轨(缓冲)");
        } else if (hasPosition && price.compareTo(upperBandWithBuffer) >= 0) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, price, 0.8, "盘整市接近布林带上轨(缓冲)");
        }
        return createNoActionSignal(marketData.getSymbol(),"盘整市，等待价格触及轨道缓冲区域");
    }

    private TradingSignal handleTrendingMarket(MarketData marketData, TechnicalIndicators indicators,
            List<Position> positions) {
        String paramsKey = config.getAdaptive().getTrendingParamsKey();
        TechnicalIndicators.BollingerBandSet bb = indicators.getBollingerBands().get(paramsKey);
        if (bb == null){
            return createNoActionSignal(marketData.getSymbol(),"缺少趋势市参数的布林带指标: " + paramsKey);
        }
        // 在趋势市，使用谨慎突破逻辑
        BigDecimal price = marketData.getClose();
        boolean hasPosition = positions.stream()
                .anyMatch(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0);

        BigDecimal middleBandWithBuffer = bb.getMiddleBand().multiply(BigDecimal.valueOf(config.getAdaptive().getTrendingMiddleBufferFactor()));
        BigDecimal upperBandWithBuffer = bb.getUpperBand().multiply(BigDecimal.valueOf(config.getAdaptive().getTrendingUpperLimitFactor()));

        if (!hasPosition && price.compareTo(middleBandWithBuffer) > 0 && price.compareTo(upperBandWithBuffer) < 0) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.BUY, price, 0.6, "趋势市突破中轨且未接近上轨");
        }
        // 在趋势市，通常不轻易做空，仅在价格跌破中轨时平仓 (此为原有逻辑，予以保留作为风控)
        // else if (hasPosition && price.compareTo(bb.getMiddleBand()) < 0) {
        //     return createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, price, 0.9, "趋势市跌破中轨，止盈/止损");
        // }
        return createNoActionSignal(marketData.getSymbol(),"趋势市，等待谨慎突破信号");
    }

    private TradingSignal handleVolatileMarket(MarketData marketData, TechnicalIndicators indicators,
            List<Position> positions) {
        // 在高波动市场，策略保持谨慎，不进行任何操作
        return createNoActionSignal(marketData.getSymbol(),"市场波动剧烈，暂停交易");
    }



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