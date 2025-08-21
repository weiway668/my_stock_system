package com.trading.backtest;

import com.trading.common.utils.BigDecimalUtils;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;
import com.trading.domain.vo.TechnicalIndicators;
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
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private final MarketDataService marketDataService;
    private final HKStockCommissionCalculator commissionCalculator;

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

                return calculateResult(portfolioManager, request, historicalDataWithWarmup);

            } catch (Exception e) {
                log.error("回测执行失败", e);
                return createErrorResult(request, e.getMessage());
            }
        });
    }
    
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
            return marketDataService.getOhlcvData(request.getSymbol(), timeframe, startTime, request.getEndTime(), 20000).get();
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

    private BacktestResult calculateResult(PortfolioManager portfolioManager, BacktestRequest request, List<MarketData> historicalData) {
        BacktestResult result = new BacktestResult();
        result.setStrategy(request.getStrategyName());
        result.setSymbol(request.getSymbol());
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setInitialCapital(BigDecimalUtils.scale(portfolioManager.getInitialCash()));

        // 1. 计算并记录“持仓到底”的市值(Mark-to-Market)
        BigDecimal markToMarketEquity = BigDecimalUtils.scale(portfolioManager.calculateTotalEquity());
        result.setFinalEquityMarkToMarket(markToMarketEquity);
        result.setUnrealizedPnl(markToMarketEquity.subtract(portfolioManager.getInitialCash()));

        // 2. 模拟期末强制平仓
        if (!portfolioManager.getPositions().isEmpty()) {
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

        // 3. 基于完全平仓后的最终现金计算核心指标
        BigDecimal finalCash = BigDecimalUtils.scale(portfolioManager.getCash());
        result.setFinalEquity(finalCash);

        BigDecimal totalReturn = finalCash.subtract(portfolioManager.getInitialCash());
        result.setTotalReturn(BigDecimalUtils.scale(totalReturn));

        if (portfolioManager.getInitialCash().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal returnRate = totalReturn.divide(portfolioManager.getInitialCash(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            result.setReturnRate(BigDecimalUtils.scale(returnRate));
            result.setAnnualizedReturn(calculateAnnualizedReturn(finalCash, portfolioManager.getInitialCash(), request.getStartTime(), request.getEndTime()));
        } else {
            result.setReturnRate(BigDecimal.ZERO);
            result.setAnnualizedReturn(BigDecimal.ZERO);
        }

        List<Order> trades = portfolioManager.getTradeHistory();
        result.setTotalTrades(trades.size());
        result.setTradeHistory(trades);
        calculateTradeStats(result, trades);

        List<BigDecimal> equityCurve = portfolioManager.getDailyEquitySnapshots().stream().map(PortfolioManager.EquitySnapshot::totalValue).collect(Collectors.toList());
        result.setEquityCurve(equityCurve);
        BigDecimal maxDrawdown = calculateMaxDrawdown(equityCurve);
        result.setMaxDrawdown(BigDecimalUtils.scale(maxDrawdown));

        List<BigDecimal> dailyReturns = calculateDailyReturns(portfolioManager.getDailyEquitySnapshots());
        result.setDailyReturns(dailyReturns);
        result.setSharpeRatio(calculateSharpeRatio(dailyReturns));
        result.setSortinoRatio(calculateSortinoRatio(dailyReturns));

        if (maxDrawdown.compareTo(BigDecimal.ZERO) > 0) {
            result.setCalmarRatio(result.getAnnualizedReturn().divide(maxDrawdown, 2, RoundingMode.HALF_UP));
        } else {
            result.setCalmarRatio(BigDecimal.ZERO);
        }

        BigDecimal totalCommissions = trades.stream().map(Order::getCommission).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        result.setTotalCommission(BigDecimalUtils.scale(totalCommissions));

        String formattedFinalEquity = String.format("%.2f", result.getFinalEquity());
        String formattedReturnRate = String.format("%.2f", result.getReturnRate());
        log.info("回测完成: 最终权益(平仓后) {}, 收益率 {}%", formattedFinalEquity, formattedReturnRate);
        return result;
    }

    private void calculateTradeStats(BacktestResult result, List<Order> trades) {
        List<Order> closingTrades = trades.stream().filter(o -> o.getSide() == OrderSide.SELL && o.getRealizedPnl() != null).collect(Collectors.toList());
        if (closingTrades.isEmpty()) {
            result.setWinRate(BigDecimal.ZERO);
            result.setAvgWin(BigDecimal.ZERO);
            result.setAvgLoss(BigDecimal.ZERO);
            result.setProfitFactor(BigDecimal.ZERO);
            return;
        }

        int winningTrades = (int) closingTrades.stream().filter(o -> o.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0).count();
        int losingTrades = closingTrades.size() - winningTrades;
        result.setWinningTrades(winningTrades);
        result.setLosingTrades(losingTrades);

        BigDecimal winRate = BigDecimal.valueOf(winningTrades).divide(BigDecimal.valueOf(closingTrades.size()), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        result.setWinRate(BigDecimalUtils.scale(winRate));

        BigDecimal grossProfit = closingTrades.stream().filter(o -> o.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0).map(Order::getRealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLoss = closingTrades.stream().filter(o -> o.getRealizedPnl().compareTo(BigDecimal.ZERO) <= 0).map(Order::getRealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);

        result.setAvgWin(winningTrades > 0 ? BigDecimalUtils.scale(grossProfit.divide(BigDecimal.valueOf(winningTrades), RoundingMode.HALF_UP)) : BigDecimal.ZERO);
        result.setAvgLoss(losingTrades > 0 ? BigDecimalUtils.scale(grossLoss.divide(BigDecimal.valueOf(losingTrades), RoundingMode.HALF_UP)) : BigDecimal.ZERO);

        if (grossLoss.abs().compareTo(BigDecimal.ZERO) > 0) {
            result.setProfitFactor(BigDecimalUtils.scale(grossProfit.divide(grossLoss.abs(), RoundingMode.HALF_UP)));
        } else {
            result.setProfitFactor(BigDecimal.valueOf(Double.POSITIVE_INFINITY));
        }
    }

    private List<BigDecimal> calculateDailyReturns(List<PortfolioManager.EquitySnapshot> equitySnapshots) {
        List<BigDecimal> dailyReturns = new ArrayList<>();
        for (int i = 1; i < equitySnapshots.size(); i++) {
            BigDecimal previousEquity = equitySnapshots.get(i - 1).totalValue();
            BigDecimal currentEquity = equitySnapshots.get(i).totalValue();
            if (previousEquity.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal dailyReturn = currentEquity.subtract(previousEquity).divide(previousEquity, 8, RoundingMode.HALF_UP);
                dailyReturns.add(dailyReturn);
            }
        }
        return dailyReturns;
    }

    private BigDecimal calculateAnnualizedReturn(BigDecimal finalEquity, BigDecimal initialCapital, LocalDateTime startTime, LocalDateTime endTime) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(startTime, endTime);
        if (days <= 0) return BigDecimal.ZERO;
        double annualizationFactor = 365.0 / days;
        double totalReturnRatio = finalEquity.divide(initialCapital, 8, RoundingMode.HALF_UP).doubleValue();
        double annualizedReturnRatio = Math.pow(totalReturnRatio, annualizationFactor) - 1;
        return BigDecimal.valueOf(annualizedReturnRatio * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSharpeRatio(List<BigDecimal> dailyReturns) {
        if (dailyReturns == null || dailyReturns.size() < 2) return BigDecimal.ZERO;
        double meanReturn = dailyReturns.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        double stdDev = Math.sqrt(dailyReturns.stream().mapToDouble(r -> Math.pow(r.doubleValue() - meanReturn, 2)).average().orElse(0.0));
        if (stdDev == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf((meanReturn / stdDev) * Math.sqrt(252)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSortinoRatio(List<BigDecimal> dailyReturns) {
        if (dailyReturns == null || dailyReturns.size() < 2) return BigDecimal.ZERO;
        double meanReturn = dailyReturns.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        double downsideDeviation = Math.sqrt(dailyReturns.stream().filter(r -> r.compareTo(BigDecimal.ZERO) < 0).mapToDouble(r -> Math.pow(r.doubleValue(), 2)).average().orElse(0.0));
        if (downsideDeviation == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf((meanReturn / downsideDeviation) * Math.sqrt(252)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMaxDrawdown(List<BigDecimal> equityCurve) {
        if (equityCurve == null || equityCurve.isEmpty()) return BigDecimal.ZERO;
        BigDecimal peak = equityCurve.get(0);
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (BigDecimal equity : equityCurve) {
            if (equity.compareTo(peak) > 0) peak = equity;
            BigDecimal drawdown = peak.subtract(equity).divide(peak, 4, RoundingMode.HALF_UP);
            if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;
        }
        return maxDrawdown.multiply(BigDecimal.valueOf(100));
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