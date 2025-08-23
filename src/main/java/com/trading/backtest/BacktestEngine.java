package com.trading.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.CCIIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.ParabolicSarIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.WilliamsRIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.MoneyFlowIndexIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.Num;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.common.utils.BigDecimalUtils;
import com.trading.config.BollingerBandConfig;
import com.trading.domain.entity.BacktestResultEntity;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.repository.BacktestResultRepository;
import com.trading.service.MarketDataService;
import com.trading.strategy.TradingStrategy;
import com.trading.strategy.UsesBollingerBands;
import com.trading.strategy.impl.BollingerBandMultiStrategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private final MarketDataService marketDataService;
    private final HKStockCommissionCalculator commissionCalculator;
    private final PerformanceAnalyticsService performanceAnalyticsService;
    private final BacktestResultRepository backtestResultRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    // Record to hold a set of Bollinger Band indicators for a specific parameter set.
    private record BollingerIndicatorSet(
            BollingerBandsUpperIndicator upper, BollingerBandsMiddleIndicator middle, BollingerBandsLowerIndicator lower
    ) {}

    // Updated record to hold multiple sets of Bollinger Bands.
    private record PrecalculatedIndicators(
            BarSeries series,
            Map<String, BollingerIndicatorSet> bollingerBands,
            MACDIndicator macd, EMAIndicator macdSignal,
            RSIIndicator rsi, SMAIndicator sma20, SMAIndicator sma50, EMAIndicator ema12, EMAIndicator ema20, EMAIndicator ema26, EMAIndicator ema50,
            ATRIndicator atr, ADXIndicator adx, PlusDIIndicator plusDI, MinusDIIndicator minusDI,
            StochasticOscillatorKIndicator stochK, StochasticOscillatorDIndicator stochD, CCIIndicator cci,
            WilliamsRIndicator williamsR, ParabolicSarIndicator parabolicSar, OnBalanceVolumeIndicator obv,
            MoneyFlowIndexIndicator mfi, VWAPIndicator vwap, SMAIndicator volumeSma
    ) {}

    public CompletableFuture<BacktestResult> runBacktest(BacktestRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("开始回测: strategy={}, symbol={}, period={} to {}",
                    request.getStrategyName(), request.getSymbol(), request.getStartTime(), request.getEndTime());

            try {
                final int WARMUP_DAYS = 100;
                LocalDateTime fetchStartTime = request.getStartTime().minusDays(WARMUP_DAYS);
                List<MarketData> historicalDataWithWarmup = fetchHistoricalData(request, fetchStartTime);

                if (historicalDataWithWarmup.isEmpty() || historicalDataWithWarmup.size() < 50) {
                    log.warn("历史数据不足(含预热数据)，无法进行有效回测");
                    return createEmptyResult(request);
                }

                BarSeries series = buildBarSeries(request.getSymbol(), historicalDataWithWarmup);
                // Pass the strategy from the request to pre-calculate indicators based on its config.
                PrecalculatedIndicators precalculatedIndicators = precalculateIndicators(series, request.getStrategy());

                request.getStrategy().initialize(request.getStrategyParameters());

                PortfolioManager portfolioManager = new PortfolioManager(request.getInitialCapital().doubleValue(), request.getSlippageRate().doubleValue(), commissionCalculator);
                List<Order> pendingOrders = new ArrayList<>();
                LocalDate lastDate = null;

                int startIndex = findStartIndex(historicalDataWithWarmup, request.getStartTime());
                if (startIndex == -1) {
                    log.error("无法在数据序列中找到指定的开始时间: {}", request.getStartTime());
                    return createErrorResult(request, "无法找到开始时间");
                }

                log.info("数据预热完成，将从第 {} 个数据点开始回测 (共 {} 条数据)", startIndex, historicalDataWithWarmup.size());

                for (int i = startIndex; i < series.getBarCount(); i++) {
                    MarketData currentData = historicalDataWithWarmup.get(i);
                    checkForTriggeredOrders(pendingOrders, currentData, portfolioManager);
                    portfolioManager.updatePositionsMarketValue(Map.of(currentData.getSymbol(), currentData.getClose()));

                    int lookback = request.getIndicatorHistoryLookback();
                    List<TechnicalIndicators> indicatorHistory = new ArrayList<>();
                    for (int j = Math.max(0, i - lookback + 1); j <= i; j++) {
                        indicatorHistory.add(getIndicatorsForIndex(precalculatedIndicators, j));
                    }

                    List<com.trading.domain.entity.Position> domainPositions = portfolioManager.getPositions().values().stream()
                            .map(this::convertToDomainPosition).collect(Collectors.toList());
                    TradingStrategy.TradingSignal signal = request.getStrategy().generateSignal(currentData, indicatorHistory, domainPositions);

                    if (signal != null && signal.getType() != TradingStrategy.TradingSignal.SignalType.NO_ACTION) {
                        executeSignal(portfolioManager, pendingOrders, signal, currentData);
                    }

                    LocalDate currentDate = currentData.getTimestamp().toLocalDate();
                    if (lastDate != null && !currentDate.isEqual(lastDate)) {
                        portfolioManager.recordDailyEquity(lastDate);
                    }
                    lastDate = currentDate;
                }
                if (lastDate != null) {
                    portfolioManager.recordDailyEquity(lastDate);
                }

                return processAndSaveResults(portfolioManager, request, historicalDataWithWarmup);

            } catch (Exception e) {
                log.error("回测执行失败", e);
                return createErrorResult(request, e.getMessage());
            } finally {
                log.info("回测结束，调用策略销毁方法: {}", request.getStrategyName());
                request.getStrategy().destroy();
            }
        });
    }

    private PrecalculatedIndicators precalculateIndicators(BarSeries series, TradingStrategy strategy) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        Map<String, BollingerIndicatorSet> bollingerBandsMap = new HashMap<>();
        // Check if the strategy is a Bollinger-based strategy to get its config.
        if (strategy instanceof UsesBollingerBands) {
            BollingerBandConfig config = applicationContext.getBean(BollingerBandConfig.class);
            log.info("检测到布林带策略，将为其计算 {} 套参数", config.getParameterSets().size());
            for (BollingerBandConfig.ParameterSet paramSet : config.getParameterSets()) {
                SMAIndicator sma = new SMAIndicator(closePrice, paramSet.getPeriod());
                BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);
                StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, paramSet.getPeriod());
                BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev, series.function().apply(BigDecimal.valueOf(paramSet.getStdDev())));
                BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev, series.function().apply(BigDecimal.valueOf(paramSet.getStdDev())));
                bollingerBandsMap.put(paramSet.getKey(), new BollingerIndicatorSet(bbUpper, bbMiddle, bbLower));
            }
        }

        // Standard indicators that are always calculated
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        SMAIndicator sma50 = new SMAIndicator(closePrice, 50);
        EMAIndicator ema12 = new EMAIndicator(closePrice, 12);
        EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
        EMAIndicator ema26 = new EMAIndicator(closePrice, 26);
        EMAIndicator ema50 = new EMAIndicator(closePrice, 50);
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        ATRIndicator atr = new ATRIndicator(series, 14);
        ADXIndicator adx = new ADXIndicator(series, 14);
        PlusDIIndicator plusDI = new PlusDIIndicator(series, 14);
        MinusDIIndicator minusDI = new MinusDIIndicator(series, 14);
        StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(series, 14);
        StochasticOscillatorDIndicator stochD = new StochasticOscillatorDIndicator(stochK);
        CCIIndicator cci = new CCIIndicator(series, 20);
        WilliamsRIndicator williamsR = new WilliamsRIndicator(series, 14);
        ParabolicSarIndicator parabolicSar = new ParabolicSarIndicator(series);
        OnBalanceVolumeIndicator obv = new OnBalanceVolumeIndicator(series);
        MoneyFlowIndexIndicator mfi = new MoneyFlowIndexIndicator(series, 14);
        VWAPIndicator vwap = new VWAPIndicator(series, 14);
        SMAIndicator volumeSma = new SMAIndicator(volume, 20);

        return new PrecalculatedIndicators(
                series, bollingerBandsMap, macd, macdSignal, rsi, sma20, sma50, ema12, ema20, ema26, ema50,
                atr, adx, plusDI, minusDI, stochK, stochD, cci, williamsR, parabolicSar, obv, mfi, vwap, volumeSma
        );
    }

    private TechnicalIndicators getIndicatorsForIndex(PrecalculatedIndicators indicators, int index) {
        Map<String, TechnicalIndicators.BollingerBandSet> bbSets = new HashMap<>();
        for (Map.Entry<String, BollingerIndicatorSet> entry : indicators.bollingerBands.entrySet()) {
            String key = entry.getKey();
            BollingerIndicatorSet set = entry.getValue();
            BigDecimal upper = toBigDecimal(set.upper.getValue(index));
            BigDecimal middle = toBigDecimal(set.middle.getValue(index));
            BigDecimal lower = toBigDecimal(set.lower.getValue(index));
            BigDecimal bandwidth = null;
            BigDecimal percentB = null;
            if (upper != null && lower != null && middle != null && middle.compareTo(BigDecimal.ZERO) != 0) {
                bandwidth = upper.subtract(lower).divide(middle, 4, RoundingMode.HALF_UP);
                BigDecimal range = upper.subtract(lower);
                if (range.compareTo(BigDecimal.ZERO) != 0) {
                    percentB = toBigDecimal(indicators.series.getBar(index).getClosePrice()).subtract(lower).divide(range, 4, RoundingMode.HALF_UP);
                }
            }
            bbSets.put(key, new TechnicalIndicators.BollingerBandSet(upper, middle, lower, bandwidth, percentB));
        }

        // For backward compatibility, populate legacy fields with the 'default' or first available set.
        TechnicalIndicators.BollingerBandSet defaultBb = bbSets.getOrDefault("ranging", bbSets.values().stream().findFirst().orElse(new TechnicalIndicators.BollingerBandSet()));

        return TechnicalIndicators.builder()
                .bollingerBands(bbSets)
                .upperBand(defaultBb.getUpperBand()).middleBand(defaultBb.getMiddleBand()).lowerBand(defaultBb.getLowerBand()).bandwidth(defaultBb.getBandwidth()).percentB(defaultBb.getPercentB())
                .macdLine(toBigDecimal(indicators.macd.getValue(index))).signalLine(toBigDecimal(indicators.macdSignal.getValue(index))).histogram(toBigDecimal(indicators.macd.getValue(index)).subtract(toBigDecimal(indicators.macdSignal.getValue(index))))
                .rsi(toBigDecimal(indicators.rsi.getValue(index)))
                .sma20(toBigDecimal(indicators.sma20.getValue(index)))
                .sma50(toBigDecimal(indicators.sma50.getValue(index)))
                .ema12(toBigDecimal(indicators.ema12.getValue(index)))
                .ema20(toBigDecimal(indicators.ema20.getValue(index)))
                .ema26(toBigDecimal(indicators.ema26.getValue(index)))
                .ema50(toBigDecimal(indicators.ema50.getValue(index)))
                .atr(toBigDecimal(indicators.atr.getValue(index)))
                .adx(toBigDecimal(indicators.adx.getValue(index)))
                .plusDI(toBigDecimal(indicators.plusDI.getValue(index)))
                .minusDI(toBigDecimal(indicators.minusDI.getValue(index)))
                .stochK(toBigDecimal(indicators.stochK.getValue(index)))
                .stochD(toBigDecimal(indicators.stochD.getValue(index)))
                .cci(toBigDecimal(indicators.cci.getValue(index)))
                .williamsR(toBigDecimal(indicators.williamsR.getValue(index)))
                .parabolicSar(toBigDecimal(indicators.parabolicSar.getValue(index)))
                .obv(toBigDecimal(indicators.obv.getValue(index)))
                .vwap(toBigDecimal(indicators.vwap.getValue(index)))
                .mfi(toBigDecimal(indicators.mfi.getValue(index)))
                .volumeSma(toBigDecimal(indicators.volumeSma.getValue(index)))
                .build();
    }

    // All other methods (processAndSaveResults, liquidateRemainingPositions, etc.) remain the same.

    private BacktestResult processAndSaveResults(PortfolioManager portfolioManager, BacktestRequest request, List<MarketData> historicalData) {
        liquidateRemainingPositions(portfolioManager, historicalData);
        List<PortfolioManager.EquitySnapshot> snapshots = portfolioManager.getDailyEquitySnapshots();
        if (!snapshots.isEmpty()) {
            BigDecimal finalEquity = portfolioManager.calculateTotalEquity();
            PortfolioManager.EquitySnapshot lastSnapshot = snapshots.get(snapshots.size() - 1);
            snapshots.set(snapshots.size() - 1, new PortfolioManager.EquitySnapshot(lastSnapshot.date(), finalEquity));
        }
        PerformanceAnalyticsService.PerformanceMetrics metrics = performanceAnalyticsService.calculatePerformance(
                portfolioManager.getTradeHistory(), snapshots, 0.02);
        saveResultEntity(request, portfolioManager, metrics);
        return createBacktestResultResponse(request, portfolioManager, metrics);
    }

    private void liquidateRemainingPositions(PortfolioManager portfolioManager, List<MarketData> historicalData) {
        if (!portfolioManager.getPositions().isEmpty() && !historicalData.isEmpty()) {
            MarketData lastData = historicalData.get(historicalData.size() - 1);
            Map<String, PortfolioManager.Position> positionsToLiquidate = new HashMap<>(portfolioManager.getPositions());
            for (PortfolioManager.Position position : positionsToLiquidate.values()) {
                Order liquidationOrder = Order.builder().symbol(position.symbol()).side(OrderSide.SELL).type(OrderType.MARKET).quantity(position.quantity()).price(lastData.getClose()).createTime(lastData.getTimestamp()).build();
                portfolioManager.processTransaction(liquidationOrder);
            }
        }
    }

    @Transactional
    protected void saveResultEntity(BacktestRequest request, PortfolioManager portfolioManager, PerformanceAnalyticsService.PerformanceMetrics metrics) {
        try {
            String dailyEquityJson = objectMapper.writeValueAsString(portfolioManager.getDailyEquitySnapshots());
            String tradeHistoryJson = objectMapper.writeValueAsString(portfolioManager.getTradeHistory());
            BacktestResultEntity entity = BacktestResultEntity.builder()
                    .strategyName(request.getStrategyName()).strategyParameters("TODO")
                    .symbol(request.getSymbol()).startDate(request.getStartTime().toLocalDate()).endDate(request.getEndTime().toLocalDate()).backtestRunTime(LocalDateTime.now())
                    .annualizedReturn(metrics.annualizedReturn()).cumulativeReturn(metrics.cumulativeReturn()).sharpeRatio(metrics.sharpeRatio()).sortinoRatio(metrics.sortinoRatio()).maxDrawdown(metrics.maxDrawdown()).calmarRatio(metrics.calmarRatio()).winRate(metrics.winRate()).profitLossRatio(metrics.profitLossRatio()).totalTrades(metrics.totalTrades()).winningTrades(metrics.winningTrades()).losingTrades(metrics.losingTrades()).averageProfit(metrics.averageProfit()).averageLoss(metrics.averageLoss())
                    .initialCapital(portfolioManager.getInitialCash()).finalCapital(portfolioManager.getCash()).totalReturn(portfolioManager.getCash().subtract(portfolioManager.getInitialCash()))
                    .totalCost(metrics.totalCost()).totalCommission(metrics.totalCommission()).totalStampDuty(metrics.totalStampDuty()).totalTradingFee(metrics.totalTradingFee()).totalSettlementFee(metrics.totalSettlementFee()).totalPlatformFee(metrics.totalPlatformFee())
                    .dailyEquityChartData(dailyEquityJson).tradeHistoryData(tradeHistoryJson).build();
            backtestResultRepository.save(entity);
        } catch (Exception e) {
            log.error("保存回测结果失败", e);
        }
    }

    private BacktestResult createBacktestResultResponse(BacktestRequest request, PortfolioManager portfolioManager, PerformanceAnalyticsService.PerformanceMetrics metrics) {
        BacktestResult result = new BacktestResult();
        result.setStrategy(request.getStrategyName());
        result.setSymbol(request.getSymbol());
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setInitialCapital(BigDecimalUtils.scale(portfolioManager.getInitialCash()));
        result.setFinalEquity(BigDecimalUtils.scale(portfolioManager.getCash()));
        BigDecimal totalReturn = result.getFinalEquity().subtract(result.getInitialCapital());
        result.setTotalReturn(totalReturn);
        if (result.getInitialCapital().compareTo(BigDecimal.ZERO) > 0) {
            result.setReturnRate(totalReturn.divide(result.getInitialCapital(), 4, RoundingMode.HALF_UP));
        } else {
            result.setReturnRate(BigDecimal.ZERO);
        }
        result.setAnnualizedReturn(metrics.annualizedReturn());
        result.setSharpeRatio(metrics.sharpeRatio());
        result.setSortinoRatio(metrics.sortinoRatio());
        result.setMaxDrawdown(metrics.maxDrawdown());
        result.setCalmarRatio(metrics.calmarRatio());
        result.setWinRate(metrics.winRate());
        result.setProfitFactor(metrics.profitLossRatio());
        result.setTotalTrades(metrics.totalTrades());
        result.setWinningTrades(metrics.winningTrades());
        result.setLosingTrades(metrics.losingTrades());
        result.setAvgWin(metrics.averageProfit());
        result.setAvgLoss(metrics.averageLoss());
        result.setTotalCosts(metrics.totalCost());
        result.setTotalCommission(metrics.totalCommission());
        result.setTotalStampDuty(metrics.totalStampDuty());
        result.setTotalTradingFee(metrics.totalTradingFee());
        result.setTotalSettlementFee(metrics.totalSettlementFee());
        result.setTradeHistory(portfolioManager.getTradeHistory());
        result.setEquityCurve(portfolioManager.getDailyEquitySnapshots().stream().map(PortfolioManager.EquitySnapshot::totalValue).collect(Collectors.toList()));
        return result;
    }

    private int findStartIndex(List<MarketData> data, LocalDateTime targetStartTime) {
        for (int i = 0; i < data.size(); i++) {
            if (!data.get(i).getTimestamp().isBefore(targetStartTime)) return i;
        }
        return -1;
    }

    private List<MarketData> fetchHistoricalData(BacktestRequest request, LocalDateTime startTime) {
        try {
            String timeframe = request.getTimeframe() != null ? request.getTimeframe() : "1d";
            return marketDataService.getOhlcvData(request.getSymbol(), timeframe, startTime, request.getEndTime(), 20000, request.getRehabType()).get();
        } catch (Exception e) {
            log.error("获取历史数据失败", e);
            return new ArrayList<>();
        }
    }

    private BarSeries buildBarSeries(String symbol, List<MarketData> dataList) {
        BarSeries series = new BaseBarSeries(symbol);
        for (MarketData data : dataList) {
            series.addBar(ZonedDateTime.of(data.getTimestamp(), ZoneId.systemDefault()), data.getOpen(), data.getHigh(), data.getLow(), data.getClose(), data.getVolume());
        }
        return series;
    }

    private BigDecimal toBigDecimal(Num num) {
        if (num == null || num.isNaN()) return null;
        return BigDecimal.valueOf(num.doubleValue());
    }

    private void checkForTriggeredOrders(List<Order> pendingOrders, MarketData currentData, PortfolioManager portfolioManager) {
        Iterator<Order> iterator = pendingOrders.iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next();
            boolean filled = false;
            if (order.getSide() == OrderSide.BUY && currentData.getLow().compareTo(order.getPrice()) <= 0) filled = true;
            else if (order.getSide() == OrderSide.SELL && currentData.getHigh().compareTo(order.getPrice()) >= 0) filled = true;
            if (filled) {
                order.setStatus(OrderStatus.FILLED);
                portfolioManager.processTransaction(order);
                iterator.remove();
            }
        }
    }

    private void executeSignal(PortfolioManager portfolioManager, List<Order> pendingOrders, TradingStrategy.TradingSignal signal, MarketData currentData) {
        int positionSize = 100;
        Order order = createOrder(signal, currentData, positionSize);
        if (order.getType() == OrderType.MARKET) {
            order.setStatus(OrderStatus.FILLED);
            portfolioManager.processTransaction(order);
        } else if (order.getType() == OrderType.LIMIT) {
            pendingOrders.add(order);
        }
    }

    private Order createOrder(TradingStrategy.TradingSignal signal, MarketData marketData, long quantity) {
        OrderType type = signal.getOrderType() != null ? signal.getOrderType() : OrderType.MARKET;
        BigDecimal price = (type == OrderType.LIMIT && signal.getPrice() != null) ? signal.getPrice() : marketData.getClose();
        OrderSide side = (signal.getType() == TradingStrategy.TradingSignal.SignalType.BUY) ? OrderSide.BUY : OrderSide.SELL;
        return Order.builder().orderId(UUID.randomUUID().toString()).symbol(signal.getSymbol()).side(side).type(type).price(price).quantity(quantity).status(OrderStatus.PENDING).createTime(marketData.getTimestamp()).rationale(signal.getReason()).build();
    }

    private com.trading.domain.entity.Position convertToDomainPosition(PortfolioManager.Position backtestPosition) {
        if (backtestPosition == null) return null;
        com.trading.domain.entity.Position domainPosition = new com.trading.domain.entity.Position();
        domainPosition.setSymbol(backtestPosition.symbol());
        domainPosition.setQuantity((int) backtestPosition.quantity());
        domainPosition.setAvgCost(backtestPosition.averageCost());
        domainPosition.setMarketValue(backtestPosition.marketValue());
        return domainPosition;
    }

    private BacktestResult createEmptyResult(BacktestRequest request) {
        BacktestResult result = new BacktestResult();
        result.setInitialCapital(request.getInitialCapital());
        result.setFinalEquity(request.getInitialCapital());
        return result;
    }

    private BacktestResult createErrorResult(BacktestRequest request, String error) {
        BacktestResult result = createEmptyResult(request);
        result.setError(error);
        return result;
    }
}