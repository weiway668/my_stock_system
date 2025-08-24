package com.trading.strategy.impl;

import com.trading.config.BollingerBandConfig;
import com.trading.config.BollingerBandFilterConfig;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.vo.TechnicalIndicators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
    private final Map<String, Function<FilterContext, FilterResult>> filters = new LinkedHashMap<>();

    public BollingerBandFilterStrategy(BollingerBandFilterConfig config) {
        this.config = config;
        this.enabled = config.isEnabled();
        this.name = "布林带组合过滤策略";
        this.version = "1.0";

        // 初始化过滤器映射
        filters.put("position", this::checkBbPosition);
        filters.put("width", this::checkBbWidth);
        filters.put("trend", this::checkBbTrend);
        if (config.getDivergence().isEnabled()) {
            filters.put("divergence", this::checkDivergence);
        }
        if (config.getVolume().isEnabled()) {
            filters.put("volume", this::checkVolumeConfirmation);
        }
    }

    @Override
    public List<BollingerBandConfig.ParameterSet> getRequiredBollingerBandSets() {
        // 本策略仅需要一套标准的"default"布林带参数用于所有过滤计算
        BollingerBandConfig.ParameterSet defaultSet = new BollingerBandConfig.ParameterSet();
        defaultSet.setKey("default");
        defaultSet.setPeriod(20);
        defaultSet.setStdDev(2.0);
        return Collections.singletonList(defaultSet);
    }

    @Override
    protected void doInitialize() {
        log.info("Initializing Bollinger Band Filter Strategy with config: {}", config);
    }

    @Override
    protected void doDestroy() {
        log.info("Destroying Bollinger Band Filter Strategy.");
    }

    @Override
    public TradingSignal generateSignal(MarketData marketData, List<TechnicalIndicators> indicatorHistory,
                                        List<Position> positions) {
        if (!this.isEnabled() || indicatorHistory.size() < config.getDivergence().getLookbackPeriod()) {
            return createNoActionSignal("策略未启用或指标历史不足");
        }

        TechnicalIndicators currentIndicators = indicatorHistory.get(indicatorHistory.size() - 1);
        TechnicalIndicators prevIndicators = indicatorHistory.get(indicatorHistory.size() - 2);

        // 使用默认的布林带参数进行过滤
        TechnicalIndicators.BollingerBandSet bb = currentIndicators.getBollingerBands().get("default");
        if (bb == null) {
            return createNoActionSignal("缺少默认的布林带指标");
        }

        FilterContext context = new FilterContext(marketData, indicatorHistory, currentIndicators, prevIndicators, bb);
        return applyAllFilters(context);
    }

    private TradingSignal applyAllFilters(FilterContext context) {
        Map<String, FilterResult> details = new LinkedHashMap<>();
        double totalScore = 0;
        int passedCount = 0;
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, Function<FilterContext, FilterResult>> entry : filters.entrySet()) {
            FilterResult result = entry.getValue().apply(context);
            details.put(entry.getKey(), result);

            if (result.isPass()) {
                passedCount++;
            }
            totalScore += result.getScore();
            if (result.getWarning() != null) {
                warnings.add(result.getWarning());
            }
        }

        boolean shouldTrade = passedCount >= config.getMinPassFilters() && totalScore > config.getMinTotalScore();

        String reason = String.format("决策: %s, 总分: %.2f, 通过过滤器: %d/%d, 警告: %s",
                shouldTrade ? "BUY" : "NO_ACTION", totalScore, passedCount, filters.size(), warnings);
        log.debug("{} - {}", context.marketData().getSymbol(), reason);

        if (shouldTrade) {
            return createSignal(context.marketData().getSymbol(), TradingSignal.SignalType.BUY, context.marketData().getClose(), totalScore / 100.0, reason);
        }

        return createNoActionSignal(reason);
    }

    // --- 过滤器实现 ---

    private FilterResult checkBbPosition(FilterContext ctx) {
        BigDecimal price = ctx.marketData().getClose();
        BigDecimal upper = ctx.bb().getUpperBand();
        BigDecimal lower = ctx.bb().getLowerBand();
        BigDecimal range = upper.subtract(lower);

        if (range.compareTo(BigDecimal.ZERO) <= 0) {
            return FilterResult.fail(-100, "布林带通道宽度为0");
        }

        BigDecimal positionRatio = price.subtract(lower).divide(range, 4, RoundingMode.HALF_UP);

        if (positionRatio.doubleValue() < 0.5) {
            double score = (0.5 - positionRatio.doubleValue()) * 100;
            return FilterResult.pass(score);
        } else {
            double score = -positionRatio.doubleValue() * 50;
            return FilterResult.fail(score);
        }
    }

    private FilterResult checkBbWidth(FilterContext ctx) {
        BigDecimal upper = ctx.bb().getUpperBand();
        BigDecimal lower = ctx.bb().getLowerBand();
        BigDecimal middle = ctx.bb().getMiddleBand();

        if (middle == null || middle.compareTo(BigDecimal.ZERO) == 0) {
            return FilterResult.fail(-100, "中轨数据无效");
        }

        double width = upper.subtract(lower).divide(middle, 4, RoundingMode.HALF_UP).doubleValue();
        BollingerBandFilterConfig.WidthFilter widthConfig = config.getWidth();

        if (width < widthConfig.getTooNarrow()) {
            return FilterResult.fail(-100, "布林带过窄，易突变");
        }
        if (width > widthConfig.getTooWide()) {
            return FilterResult.fail(-50, "布林带过宽，波动大");
        }
        if (width >= widthConfig.getIdealLower() && width <= widthConfig.getIdealUpper()) {
            return FilterResult.pass(50);
        }
        return FilterResult.pass(20);
    }

    private FilterResult checkBbTrend(FilterContext ctx) {
        TechnicalIndicators.BollingerBandSet prevBb = ctx.prevIndicators().getBollingerBands().get("default");
        if (prevBb == null) {
            return FilterResult.fail(-100, "缺少上一周期布林带指标");
        }

        BigDecimal middle = ctx.bb().getMiddleBand();
        BigDecimal prevMiddle = prevBb.getMiddleBand();

        if (middle == null || prevMiddle == null || prevMiddle.compareTo(BigDecimal.ZERO) == 0) {
            return FilterResult.fail(-100, "中轨历史数据无效");
        }

        double slope = middle.subtract(prevMiddle).divide(prevMiddle, 4, RoundingMode.HALF_UP).doubleValue();
        double slopeThreshold = config.getTrend().getSlopeThreshold();

        if (slope > slopeThreshold) {
            return FilterResult.pass(30);
        } else if (slope < -slopeThreshold) {
            return FilterResult.fail(-30);
        } else {
            return FilterResult.pass(10);
        }
    }

    // --- 占位过滤器 ---

    private FilterResult checkDivergence(FilterContext ctx) {
        int lookbackPeriod = config.getDivergence().getLookbackPeriod();
        if (ctx.indicatorHistory().size() < lookbackPeriod) {
            return FilterResult.pass(0, "RSI背离检测历史数据不足");
        }

        // 寻找价格低点
        int priceLowIndex = -1;
        BigDecimal priceLow = ctx.currentIndicators().getLowPrice();

        for (int i = 1; i < lookbackPeriod; i++) {
            BigDecimal pastPrice = ctx.indicatorHistory().get(ctx.indicatorHistory().size() - 1 - i).getLowPrice();
            if (pastPrice != null && pastPrice.compareTo(priceLow) < 0) {
                priceLow = pastPrice;
                priceLowIndex = ctx.indicatorHistory().size() - 1 - i;
            }
        }

        if (priceLowIndex == -1) { // 当前就是最低点
            priceLowIndex = ctx.indicatorHistory().size() - 1;
        }

        // 寻找RSI低点
        int rsiLowIndex = -1;
        BigDecimal rsiLow = ctx.currentIndicators().getRsi();

        for (int i = 1; i < lookbackPeriod; i++) {
            BigDecimal pastRsi = ctx.indicatorHistory().get(ctx.indicatorHistory().size() - 1 - i).getRsi();
            if (pastRsi != null && pastRsi.compareTo(rsiLow) < 0) {
                rsiLow = pastRsi;
                rsiLowIndex = ctx.indicatorHistory().size() - 1 - i;
            }
        }
        
        if (rsiLowIndex == -1) { // 当前就是最低点
            rsiLowIndex = ctx.indicatorHistory().size() - 1;
        }

        // 检测看涨背离: 价格创出新低，RSI没有创出新低
        if (priceLowIndex == ctx.indicatorHistory().size() - 1 && rsiLowIndex != ctx.indicatorHistory().size() - 1) {
             return FilterResult.pass(50, "RSI看涨背离");
        }

        return FilterResult.fail(0);
    }

    private FilterResult checkVolumeConfirmation(FilterContext ctx) {
        int maPeriod = config.getVolume().getMaPeriod();
        if (ctx.indicatorHistory().size() < maPeriod) {
            return FilterResult.pass(0, "成交量检测历史数据不足");
        }

        BigDecimal currentVolume = BigDecimal.valueOf(ctx.currentIndicators().getVolume() != null ? ctx.currentIndicators().getVolume() : 0L);

        BigDecimal volumeSum = BigDecimal.ZERO;
        for (int i = 0; i < maPeriod; i++) {
            Long pastVolume = ctx.indicatorHistory().get(ctx.indicatorHistory().size() - 1 - i).getVolume();
            volumeSum = volumeSum.add(BigDecimal.valueOf(pastVolume != null ? pastVolume : 0L));
        }
        BigDecimal volumeMA = volumeSum.divide(BigDecimal.valueOf(maPeriod), RoundingMode.HALF_UP);

        if (currentVolume.compareTo(volumeMA.multiply(BigDecimal.valueOf(1.2))) > 0) {
            return FilterResult.pass(20, "成交量放大");
        }

        return FilterResult.fail(0);
    }

    // --- 辅助类和方法 ---

    private TradingSignal createSignal(String symbol, TradingSignal.SignalType type, BigDecimal price, double confidence, String reason) {
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
        if (config.getRiskManagement().isEnabled() && order.getSide() == OrderSide.BUY) {
            BigDecimal stopLossPrice = order.getPrice().multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(config.getRiskManagement().getStopLossPercentage())));
            order.setStopLoss(stopLossPrice.setScale(2, RoundingMode.DOWN));
            log.info("为订单 {} 设置止损价: {}", order.getOrderId(), order.getStopLoss());
        }
        return order;
    }

    private record FilterContext(MarketData marketData, List<TechnicalIndicators> indicatorHistory, TechnicalIndicators currentIndicators, TechnicalIndicators prevIndicators, TechnicalIndicators.BollingerBandSet bb) {}

    @Data
    @Builder
    @AllArgsConstructor
    private static class FilterResult {
        private boolean pass;
        private double score;
        private String warning;

        public static FilterResult pass(double score) {
            return new FilterResult(true, score, null);
        }

        public static FilterResult pass(double score, String warning) {
            return new FilterResult(true, score, warning);
        }

        public static FilterResult fail(double score) {
            return new FilterResult(false, score, null);
        }

        public static FilterResult fail(double score, String warning) {
            return new FilterResult(false, score, warning);
        }
    }
}