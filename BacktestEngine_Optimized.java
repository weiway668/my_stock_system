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
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.CCIIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 回测引擎 (已重构以支持限价单和高性能指标计算)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private final MarketDataService marketDataService;
    private final HKStockCommissionCalculator commissionCalculator;

    // 用于持有预先计算好的TA4J指标对象，保证后续策略使用时不会为空
    private record PrecalculatedIndicators(
            BollingerBandsUpperIndicator bbUpper,
            BollingerBandsMiddleIndicator bbMiddle,
            BollingerBandsLowerIndicator bbLower,
            MACDIndicator macd,
            EMAIndicator macdSignal,
            RSIIndicator rsi,
            SMAIndicator sma20,
            SMAIndicator sma50,
            EMAIndicator ema12,
            EMAIndicator ema20,
            EMAIndicator ema26,
            ATRIndicator atr,
            ADXIndicator adx,
            PlusDIIndicator plusDI,
            MinusDIIndicator minusDI,
            StochasticOscillatorKIndicator stochK,
            StochasticOscillatorDIndicator stochD,
            CCIIndicator cci
    ) {}

    public CompletableFuture<BacktestResult> runBacktest(BacktestRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("开始回测 (高性能模式): strategy={}, symbol={}, period={} to {}",
                    request.getStrategyName(), request.getSymbol(), request.getStartTime(), request.getEndTime());

            try {
                List<MarketData> historicalData = fetchHistoricalData(request);
                if (historicalData.isEmpty() || historicalData.size() < 50) { // 50 for SMA50
                    log.warn("历史数据不足 (少于50条)，无法进行有效回测");
                    return createEmptyResult(request);
                }

                BarSeries series = buildBarSeries(request.getSymbol(), historicalData);
                PrecalculatedIndicators precalculatedIndicators = precalculateIndicators(series);

                PortfolioManager portfolioManager = new PortfolioManager(request.getInitialCapital().doubleValue(), request.getSlippageRate().doubleValue(), commissionCalculator);
                List<Order> pendingOrders = new ArrayList<>();
                LocalDate lastDate = null;

                for (int i = 0; i < series.getBarCount(); i++) {
                    MarketData currentData = historicalData.get(i);

                    checkForTriggeredOrders(pendingOrders, currentData, portfolioManager);
                    portfolioManager.updatePositionsMarketValue(Map.of(currentData.getSymbol(), currentData.getClose()));

                    TechnicalIndicators indicators = getIndicatorsForIndex(precalculatedIndicators, i);

                    List<com.trading.domain.entity.Position> domainPositions = portfolioManager.getPositions().values().stream()
                            .map(this::convertToDomainPosition).collect(Collectors.toList());
                    TradingStrategy.TradingSignal signal = request.getStrategy().generateSignal(currentData, indicators, domainPositions);

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

                return calculateResult(portfolioManager, request);

            } catch (Exception e) {
                log.error("回测执行失败", e);
                return createErrorResult(request, e.getMessage());
            }
        });
    }

    private BarSeries buildBarSeries(String symbol, List<MarketData> dataList) {
        BarSeries series = new BaseBarSeries(symbol);
        for (MarketData data : dataList) {
            ZonedDateTime zdt = ZonedDateTime.of(data.getTimestamp(), ZoneId.systemDefault());
            series.addBar(
                    zdt,
                    DecimalNum.valueOf(data.getOpen()),
                    DecimalNum.valueOf(data.getHigh()),
                    DecimalNum.valueOf(data.getLow()),
                    DecimalNum.valueOf(data.getClose()),
                    DecimalNum.valueOf(data.getVolume())
            );
        }
        return series;
    }

    private PrecalculatedIndicators precalculateIndicators(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma20ForBB = new SMAIndicator(closePrice, 20);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20ForBB);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev);
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        SMAIndicator sma50 = new SMAIndicator(closePrice, 50);
        EMAIndicator ema12 = new EMAIndicator(closePrice, 12);
        EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
        EMAIndicator ema26 = new EMAIndicator(closePrice, 26);
        ATRIndicator atr = new ATRIndicator(series, 14);
        ADXIndicator adx = new ADXIndicator(series, 14);
        PlusDIIndicator plusDI = new PlusDIIndicator(series, 14);
        MinusDIIndicator minusDI = new MinusDIIndicator(series, 14);
        StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(series, 14);
        StochasticOscillatorDIndicator stochD = new StochasticOscillatorDIndicator(stochK);
        CCIIndicator cci = new CCIIndicator(series, 20);

        return new PrecalculatedIndicators(bbUpper, bbMiddle, bbLower, macd, macdSignal, rsi, sma20, sma50, ema12, ema20, ema26, atr, adx, plusDI, minusDI, stochK, stochD, cci);
    }

    private TechnicalIndicators getIndicatorsForIndex(PrecalculatedIndicators indicators, int index) {
        BigDecimal macdLine = toBigDecimal(indicators.macd.getValue(index));
        BigDecimal signalLine = toBigDecimal(indicators.macdSignal.getValue(index));
        BigDecimal histogram = (macdLine != null && signalLine != null) ? macdLine.subtract(signalLine) : null;

        return TechnicalIndicators.builder()
                .upperBand(toBigDecimal(indicators.bbUpper.getValue(index)))
                .middleBand(toBigDecimal(indicators.bbMiddle.getValue(index)))
                .lowerBand(toBigDecimal(indicators.bbLower.getValue(index)))
                .macdLine(macdLine)
                .signalLine(signalLine)
                .histogram(histogram)
                .rsi(toBigDecimal(indicators.rsi.getValue(index)))
                .sma20(toBigDecimal(indicators.sma20.getValue(index)))
                .sma50(toBigDecimal(indicators.sma50.getValue(index)))
                .ema12(toBigDecimal(indicators.ema12.getValue(index)))
                .ema20(toBigDecimal(indicators.ema20.getValue(index)))
                .ema26(toBigDecimal(indicators.ema26.getValue(index)))
                .atr(toBigDecimal(indicators.atr.getValue(index)))
                .adx(toBigDecimal(indicators.adx.getValue(index)))
                .plusDI(toBigDecimal(indicators.plusDI.getValue(index)))
                .minusDI(toBigDecimal(indicators.minusDI.getValue(index)))
                .stochK(toBigDecimal(indicators.stochK.getValue(index)))
                .stochD(toBigDecimal(indicators.stochD.getValue(index)))
                .cci(toBigDecimal(indicators.cci.getValue(index)))
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

    private Order createOrder(TradingStrategy.TradingSignal signal, MarketData marketData, int quantity) {
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

    private List<MarketData> fetchHistoricalData(BacktestRequest request) {
        try {
            String timeframe = request.getTimeframe() != null ? request.getTimeframe() : "1d";
            return marketDataService.getOhlcvData(request.getSymbol(), timeframe, request.getStartTime(), request.getEndTime(), 10000).get();
        } catch (Exception e) {
            log.error("获取历史数据失败", e);
            return new ArrayList<>();
        }
    }

    private BacktestResult calculateResult(PortfolioManager portfolioManager, BacktestRequest request) {
        BacktestResult result = new BacktestResult();
        result.setStrategy(request.getStrategyName());
        result.setSymbol(request.getSymbol());
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setInitialCapital(BigDecimalUtils.scale(portfolioManager.getInitialCash()));
        BigDecimal finalEquity = BigDecimalUtils.scale(portfolioManager.calculateTotalEquity());
        result.setFinalEquity(finalEquity);
        BigDecimal totalReturn = finalEquity.subtract(portfolioManager.getInitialCash());
        result.setTotalReturn(BigDecimalUtils.scale(totalReturn));
        if (portfolioManager.getInitialCash().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal returnRate = totalReturn.divide(portfolioManager.getInitialCash(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            result.setReturnRate(BigDecimalUtils.scale(returnRate));
            long days = java.time.temporal.ChronoUnit.DAYS.between(request.getStartTime(), request.getEndTime());
            if (days > 0) {
                double annualizationFactor = 365.0 / days;
                double totalReturnRatio = finalEquity.divide(portfolioManager.getInitialCash(), 8, RoundingMode.HALF_UP).doubleValue();
                double annualizedReturnRatio = Math.pow(totalReturnRatio, annualizationFactor) - 1;
                result.setAnnualizedReturn(BigDecimalUtils.scale(BigDecimal.valueOf(annualizedReturnRatio * 100)));
            } else {
                result.setAnnualizedReturn(BigDecimal.ZERO);
            }
        } else {
            result.setReturnRate(BigDecimal.ZERO);
            result.setAnnualizedReturn(BigDecimal.ZERO);
        }
        List<Order> trades = portfolioManager.getTradeHistory();
        result.setTotalTrades(trades.size());
        result.setTradeHistory(trades);
        List<Order> closingTrades = trades.stream().filter(o -> o.getSide() == OrderSide.SELL && o.getRealizedPnl() != null).collect(Collectors.toList());
        int winningTrades = (int) closingTrades.stream().filter(o -> o.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0).count();
        int losingTrades = closingTrades.size() - winningTrades;
        result.setWinningTrades(winningTrades);
        result.setLosingTrades(losingTrades);
        if (!closingTrades.isEmpty()) {
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
        } else {
            result.setWinRate(BigDecimal.ZERO);
            result.setAvgWin(BigDecimal.ZERO);
            result.setAvgLoss(BigDecimal.ZERO);
            result.setProfitFactor(BigDecimal.ZERO);
        }
        List<BigDecimal> equityCurve = portfolioManager.getDailyEquitySnapshots().stream().map(PortfolioManager.EquitySnapshot::totalValue).collect(Collectors.toList());
        result.setEquityCurve(equityCurve);
        result.setMaxDrawdown(BigDecimalUtils.scale(calculateMaxDrawdown(equityCurve)));
        result.setSharpeRatio(BigDecimalUtils.scale(calculateSharpeRatio(equityCurve)));
        result.setSortinoRatio(BigDecimal.ZERO);
        result.setCalmarRatio(BigDecimal.ZERO);
        String formattedFinalEquity = String.format("%.2f", result.getFinalEquity());
        String formattedReturnRate = String.format("%.2f", result.getReturnRate());
        log.info("回测完成: 最终权益 {}, 收益率 {}%", formattedFinalEquity, formattedReturnRate);
        return result;
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

    private BigDecimal calculateSharpeRatio(List<BigDecimal> equityCurve) {
        return BigDecimal.ZERO;
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