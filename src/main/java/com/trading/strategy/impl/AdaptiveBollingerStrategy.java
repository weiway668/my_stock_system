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
import java.util.Optional;

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
        if (indicatorHistory.isEmpty()) {
            return createNoActionSignal(marketData.getSymbol(),"指标历史为空");
        }

        // 检查是否已持仓
        Optional<Position> positionOpt = positions.stream()
                .filter(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0)
                .findFirst();

        // 如果持仓，优先执行通用卖出信号框架
        if (positionOpt.isPresent()) {
            Optional<TradingSignal> sellSignal = checkForBaseSellSignal(positionOpt.get(), marketData, historicalKlines, indicatorHistory);
            if (sellSignal.isPresent()) {
                return sellSignal.get();
            }
        }

        // 如果通用卖出框架未触发，或当前未持仓，则执行策略原有的买卖逻辑
        TechnicalIndicators indicators = indicatorHistory.get(indicatorHistory.size() - 1);
        MarketState marketState = identifyMarketState(marketData, indicators);

        return switch (marketState) {
            case RANGING -> handleRangingMarket(marketData, historicalKlines, indicatorHistory, positions);
            case TRENDING -> handleTrendingMarket(marketData, historicalKlines, indicatorHistory, positions);
            case VOLATILE -> handleVolatileMarket(marketData, positions);
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

    private TradingSignal handleRangingMarket(MarketData marketData, List<com.trading.domain.entity.HistoricalKLineEntity> historicalKlines, List<TechnicalIndicators> indicatorHistory, List<Position> positions) {
        String paramsKey = config.getAdaptive().getRangingParamsKey();
        TechnicalIndicators.BollingerBandSet bb = indicatorHistory.get(indicatorHistory.size() - 1).getBollingerBands().get(paramsKey);
        if (bb == null){
            return createNoActionSignal(marketData.getSymbol(),"缺少盘整市参数的布林带指标: " + paramsKey);
        }
        
        BigDecimal price = marketData.getClose();
        boolean hasPosition = positions.stream()
                .anyMatch(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0);

        // Sell logic (unfiltered)
        BigDecimal upperBandWithBuffer = bb.getUpperBand().multiply(BigDecimal.valueOf(config.getAdaptive().getRangingUpperBufferFactor()));
        if (hasPosition && price.compareTo(upperBandWithBuffer) >= 0) {
            return createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, price, 0.8, "盘整市接近布林带上轨(缓冲)");
        }

        // Buy logic (filtered)
        BigDecimal lowerBandWithBuffer = bb.getLowerBand().multiply(BigDecimal.valueOf(config.getAdaptive().getRangingLowerBufferFactor()));
        if (!hasPosition && price.compareTo(lowerBandWithBuffer) <= 0) {
            if (isTrendFavorable(marketData, indicatorHistory) && isVolatilityAdequate(marketData, historicalKlines, indicatorHistory)) {
                 return createSignal(marketData.getSymbol(), TradingSignal.SignalType.BUY, price, 0.8, "盘整市接近布林带下轨(已过滤)");
            }
        }

        return createNoActionSignal(marketData.getSymbol(),"盘整市，等待价格触及轨道缓冲区域");
    }

    private TradingSignal handleTrendingMarket(MarketData marketData, List<com.trading.domain.entity.HistoricalKLineEntity> historicalKlines, List<TechnicalIndicators> indicatorHistory, List<Position> positions) {
        String paramsKey = config.getAdaptive().getTrendingParamsKey();
        TechnicalIndicators.BollingerBandSet bb = indicatorHistory.get(indicatorHistory.size() - 1).getBollingerBands().get(paramsKey);
        if (bb == null){
            return createNoActionSignal(marketData.getSymbol(),"缺少趋势市参数的布林带指标: " + paramsKey);
        }

        BigDecimal price = marketData.getClose();
        boolean hasPosition = positions.stream()
                .anyMatch(p -> p.getSymbol().equals(marketData.getSymbol()) && p.getQuantity() > 0);

        BigDecimal middleBandWithBuffer = bb.getMiddleBand().multiply(BigDecimal.valueOf(config.getAdaptive().getTrendingMiddleBufferFactor()));
        BigDecimal upperBandWithBuffer = bb.getUpperBand().multiply(BigDecimal.valueOf(config.getAdaptive().getTrendingUpperLimitFactor()));

        if (!hasPosition && price.compareTo(middleBandWithBuffer) > 0 && price.compareTo(upperBandWithBuffer) < 0) {
            if (isTrendFavorable(marketData, indicatorHistory) && isVolatilityAdequate(marketData, historicalKlines, indicatorHistory)) {
                return createSignal(marketData.getSymbol(), TradingSignal.SignalType.BUY, price, 0.6, "趋势市突破中轨(已过滤)");
            }
        }

        return createNoActionSignal(marketData.getSymbol(),"趋势市，等待谨慎突破信号");
    }

    private TradingSignal handleVolatileMarket(MarketData marketData, List<Position> positions) {
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