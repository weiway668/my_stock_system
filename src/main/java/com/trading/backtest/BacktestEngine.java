package com.trading.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.common.utils.BigDecimalUtils;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.*;
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

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private final MarketDataService marketDataService;
    private final HKStockCommissionCalculator commissionCalculator;
    private final PerformanceAnalyticsService performanceAnalyticsService;
    private final BacktestResultRepository backtestResultRepository;
    private final ObjectMapper objectMapper;

    private record PrecalculatedIndicators(
            BarSeries series,
            BollingerBandsUpperIndicator bbUpper, BollingerBandsMiddleIndicator bbMiddle, BollingerBandsLowerIndicator bbLower,
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
                PrecalculatedIndicators precalculatedIndicators = precalculateIndicators(series);

                // 初始化策略，这是关键步骤！
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

    private BacktestResult processAndSaveResults(PortfolioManager portfolioManager, BacktestRequest request, List<MarketData> historicalData) {
        // 模拟期末强制平仓
        liquidateRemainingPositions(portfolioManager, historicalData);

        // 修正：在计算指标前，用平仓后的真实权益更新最后一条权益记录
        List<PortfolioManager.EquitySnapshot> snapshots = portfolioManager.getDailyEquitySnapshots();
        if (!snapshots.isEmpty()) {
            BigDecimal finalEquity = portfolioManager.calculateTotalEquity();
            PortfolioManager.EquitySnapshot lastSnapshot = snapshots.get(snapshots.size() - 1);
            snapshots.set(snapshots.size() - 1, new PortfolioManager.EquitySnapshot(lastSnapshot.date(), finalEquity));
            log.debug("用平仓后的最终权益 {} 更新了最后一条权益记录", finalEquity);
        }

        // 使用服务计算性能指标
        final double RISK_FREE_RATE = 0.02; // 假设无风险利率为2%
        PerformanceAnalyticsService.PerformanceMetrics metrics = performanceAnalyticsService.calculatePerformance(
                portfolioManager.getTradeHistory(),
                snapshots, // 使用修正后的快照列表
                RISK_FREE_RATE
        );

        // 保存结果到数据库
        saveResultEntity(request, portfolioManager, metrics);

        // 创建并返回用于API响应的BacktestResult对象
        return createBacktestResultResponse(request, portfolioManager, metrics);
    }

    private void liquidateRemainingPositions(PortfolioManager portfolioManager, List<MarketData> historicalData) {
        if (!portfolioManager.getPositions().isEmpty() && !historicalData.isEmpty()) {
            MarketData lastData = historicalData.get(historicalData.size() - 1);
            Map<String, PortfolioManager.Position> positionsToLiquidate = new HashMap<>(portfolioManager.getPositions());
            log.info("回测期末，对 {} 个剩余持仓进行模拟平仓...", positionsToLiquidate.size());

            for (PortfolioManager.Position position : positionsToLiquidate.values()) {
                Order liquidationOrder = Order.builder()
                        .symbol(position.symbol())
                        .side(OrderSide.SELL)
                        .type(OrderType.MARKET)
                        .quantity(position.quantity())
                        .price(lastData.getClose())
                        .createTime(lastData.getTimestamp())
                        .build();
                portfolioManager.processTransaction(liquidationOrder);
            }
        }
    }

    @Transactional
    private void saveResultEntity(BacktestRequest request, PortfolioManager portfolioManager, PerformanceAnalyticsService.PerformanceMetrics metrics) {
        try {
            String dailyEquityJson = objectMapper.writeValueAsString(portfolioManager.getDailyEquitySnapshots());
            String tradeHistoryJson = objectMapper.writeValueAsString(portfolioManager.getTradeHistory());

            BacktestResultEntity entity = BacktestResultEntity.builder()
                    .strategyName(request.getStrategyName())
                    .strategyParameters("TODO: Serialize strategy params") // TODO
                    .symbol(request.getSymbol())
                    .startDate(request.getStartTime().toLocalDate())
                    .endDate(request.getEndTime().toLocalDate())
                    .backtestRunTime(LocalDateTime.now())
                    .annualizedReturn(metrics.annualizedReturn())
                    .cumulativeReturn(metrics.cumulativeReturn())
                    .sharpeRatio(metrics.sharpeRatio())
                    .sortinoRatio(metrics.sortinoRatio())
                    .maxDrawdown(metrics.maxDrawdown())
                    .calmarRatio(metrics.calmarRatio())
                    .winRate(metrics.winRate())
                    .profitLossRatio(metrics.profitLossRatio())
                    .totalTrades(metrics.totalTrades())
                    .winningTrades(metrics.winningTrades())
                    .losingTrades(metrics.losingTrades())
                    .averageProfit(metrics.averageProfit())
                    .averageLoss(metrics.averageLoss())
                    .initialCapital(portfolioManager.getInitialCash())
                    .finalCapital(portfolioManager.getCash())
                    .totalReturn(portfolioManager.getCash().subtract(portfolioManager.getInitialCash()))
                    .totalCost(metrics.totalCost())
                    .totalCommission(metrics.totalCommission())
                    .totalStampDuty(metrics.totalStampDuty())
                    .totalTradingFee(metrics.totalTradingFee())
                    .totalSettlementFee(metrics.totalSettlementFee())
                    .totalPlatformFee(metrics.totalPlatformFee())
                    .dailyEquityChartData(dailyEquityJson)
                    .tradeHistoryData(tradeHistoryJson)
                    .build();

            backtestResultRepository.save(entity);
            log.info("回测结果已成功保存到数据库, ID: {}", entity.getId());
        } catch (JsonProcessingException e) {
            log.error("序列化回测结果失败", e);
        } catch (Exception e) {
            log.error("保存回测结果到数据库时发生未知错误", e);
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

        // 从 'metrics' 对象统一设置所有性能指标
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

        // 从 'metrics' 对象统一设置所有成本指标
        result.setTotalCosts(metrics.totalCost()); // 修正: 使用正确的复数形式方法 setTotalCosts
        result.setTotalCommission(metrics.totalCommission());
        result.setTotalStampDuty(metrics.totalStampDuty());
        result.setTotalTradingFee(metrics.totalTradingFee());
        result.setTotalSettlementFee(metrics.totalSettlementFee());

        // 设置详细数据
        result.setTradeHistory(portfolioManager.getTradeHistory());
        result.setEquityCurve(portfolioManager.getDailyEquitySnapshots().stream()
                .map(PortfolioManager.EquitySnapshot::totalValue)
                .collect(Collectors.toList()));

        log.info("回测完成: 最终权益(平仓后) {}, 收益率 {}%",
                String.format("%.2f", result.getFinalEquity()),
                String.format("%.2f", result.getReturnRate().multiply(BigDecimal.valueOf(100))));

        return result;
    }

    // ... (其它辅助方法保持不变) ...
    
    private int findStartIndex(List<MarketData> data, LocalDateTime targetStartTime) {
        for (int i = 0; i < data.size(); i++) {
            if (!data.get(i).getTimestamp().isBefore(targetStartTime)) {
                return i;
            }
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
            ZonedDateTime zdt = ZonedDateTime.of(data.getTimestamp(), ZoneId.systemDefault());
            series.addBar(zdt, data.getOpen(), data.getHigh(), data.getLow(), data.getClose(), data.getVolume());
        }
        return series;
    }

    private PrecalculatedIndicators precalculateIndicators(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        SMAIndicator sma50 = new SMAIndicator(closePrice, 50);
        EMAIndicator ema12 = new EMAIndicator(closePrice, 12);
        EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
        EMAIndicator ema26 = new EMAIndicator(closePrice, 26);
        EMAIndicator ema50 = new EMAIndicator(closePrice, 50);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, 20);
        return new PrecalculatedIndicators(
                series,
                new BollingerBandsUpperIndicator(bbMiddle, stdDev), bbMiddle, new BollingerBandsLowerIndicator(bbMiddle, stdDev),
                new MACDIndicator(closePrice, 12, 26), new EMAIndicator(new MACDIndicator(closePrice, 12, 26), 9),
                new RSIIndicator(closePrice, 14), sma20, sma50, ema12, ema20, ema26, ema50,
                new ATRIndicator(series, 14), new ADXIndicator(series, 14), new PlusDIIndicator(series, 14), new MinusDIIndicator(series, 14),
                new StochasticOscillatorKIndicator(series, 14), new StochasticOscillatorDIndicator(new StochasticOscillatorKIndicator(series, 14)),
                new CCIIndicator(series, 20), new WilliamsRIndicator(series, 14), new ParabolicSarIndicator(series),
                new OnBalanceVolumeIndicator(series), new MoneyFlowIndexIndicator(series, 14), new VWAPIndicator(series, 14),
                new SMAIndicator(volume, 20)
        );
    }

    private TechnicalIndicators getIndicatorsForIndex(PrecalculatedIndicators indicators, int index) {
        BigDecimal macdLine = toBigDecimal(indicators.macd.getValue(index));
        BigDecimal signalLine = toBigDecimal(indicators.macdSignal.getValue(index));
        BigDecimal histogram = (macdLine != null && signalLine != null) ? macdLine.subtract(signalLine) : null;

        BigDecimal upperBand = toBigDecimal(indicators.bbUpper.getValue(index));
        BigDecimal lowerBand = toBigDecimal(indicators.bbLower.getValue(index));
        BigDecimal middleBand = toBigDecimal(indicators.bbMiddle.getValue(index));
        BigDecimal bandwidth = null;
        BigDecimal percentB = null;
        if (upperBand != null && lowerBand != null && middleBand != null && middleBand.compareTo(BigDecimal.ZERO) != 0) {
            bandwidth = upperBand.subtract(lowerBand).divide(middleBand, 4, RoundingMode.HALF_UP);
            BigDecimal range = upperBand.subtract(lowerBand);
            if (range.compareTo(BigDecimal.ZERO) != 0) {
                Bar bar = indicators.series.getBar(index);
                percentB = toBigDecimal(bar.getClosePrice()).subtract(lowerBand).divide(range, 4, RoundingMode.HALF_UP);
            }
        }

        return TechnicalIndicators.builder()
                .upperBand(upperBand).middleBand(middleBand).lowerBand(lowerBand).bandwidth(bandwidth).percentB(percentB)
                .macdLine(macdLine).signalLine(signalLine).histogram(histogram)
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

    private BigDecimal toBigDecimal(Num num) {
        if (num == null || num.isNaN()) {
            return null;
        }
        return BigDecimal.valueOf(num.doubleValue());
    }

    private void checkForTriggeredOrders(List<Order> pendingOrders, MarketData currentData, PortfolioManager portfolioManager) {
        Iterator<Order> iterator = pendingOrders.iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next();
            boolean filled = false;
            if (order.getSide() == OrderSide.BUY && currentData.getLow().compareTo(order.getPrice()) <= 0) {
                filled = true;
            } else if (order.getSide() == OrderSide.SELL && currentData.getHigh().compareTo(order.getPrice()) >= 0) {
                filled = true;
            }
            if (filled) {
                log.info("限价单触发并成交: {}", order);
                order.setStatus(OrderStatus.FILLED);
                portfolioManager.processTransaction(order);
                iterator.remove();
            }
        }
    }

    private void executeSignal(PortfolioManager portfolioManager, List<Order> pendingOrders, TradingStrategy.TradingSignal signal, MarketData currentData) {
        int positionSize = 100; // Simplified
        Order order = createOrder(signal, currentData, positionSize);
        if (order.getType() == OrderType.MARKET) {
            log.info("市价单立即执行: {}", order);
            order.setStatus(OrderStatus.FILLED);
            portfolioManager.processTransaction(order);
        } else if (order.getType() == OrderType.LIMIT) {
            log.info("限价单已提交，待触发: {}", order);
            pendingOrders.add(order);
        }
    }

    private Order createOrder(TradingStrategy.TradingSignal signal, MarketData marketData, long quantity) {
        OrderType type = signal.getOrderType() != null ? signal.getOrderType() : OrderType.MARKET;
        BigDecimal price = (type == OrderType.LIMIT && signal.getPrice() != null) ? signal.getPrice() : marketData.getClose();
        OrderSide side = (signal.getType() == TradingStrategy.TradingSignal.SignalType.BUY) ? OrderSide.BUY : OrderSide.SELL;
        return Order.builder()
                .orderId(UUID.randomUUID().toString())
                .symbol(signal.getSymbol())
                .side(side)
                .type(type)
                .price(price)
                .quantity(quantity)
                .status(OrderStatus.PENDING)
                .createTime(marketData.getTimestamp())
                .rationale(signal.getReason()) // 保存交易理由
                .build();
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
