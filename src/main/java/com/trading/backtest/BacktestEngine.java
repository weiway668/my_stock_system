package com.trading.backtest;

import com.trading.common.utils.BigDecimalUtils;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.service.MarketDataService;
import com.trading.strategy.TechnicalAnalysisService;
import com.trading.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 回测引擎 (已重构以支持限价单)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private final MarketDataService marketDataService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final HKStockCommissionCalculator commissionCalculator;

    public CompletableFuture<BacktestResult> runBacktest(BacktestRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("开始回测 (支持限价单): strategy={}, symbol={}, period={} to {}",
                    request.getStrategy().getName(), request.getSymbol(), request.getStartTime(), request.getEndTime());

            try {
                List<MarketData> historicalData = fetchHistoricalData(request);
                if (historicalData.isEmpty()) {
                    return createEmptyResult(request);
                }

                PortfolioManager portfolioManager = new PortfolioManager(request.getInitialCapital().doubleValue(), request.getSlippageRate().doubleValue(), commissionCalculator);
                List<Order> pendingOrders = new ArrayList<>();
                LocalDate lastDate = null;

                for (int i = 0; i < historicalData.size(); i++) {
                    MarketData currentData = historicalData.get(i);

                    // 1. 检查并执行待处理的限价单
                    checkForTriggeredOrders(pendingOrders, currentData, portfolioManager);

                    // 2. 更新持仓市值
                    portfolioManager.updatePositionsMarketValue(Map.of(currentData.getSymbol(), currentData.getClose()));

                    // 3. 计算技术指标
                    TechnicalIndicators indicators = (i > 20) ? calculateIndicators(historicalData.subList(0, i + 1)) : new TechnicalIndicators();

                    // 4. 策略生成交易信号
                    List<com.trading.domain.entity.Position> domainPositions = portfolioManager.getPositions().values().stream()
                            .map(this::convertToDomainPosition).collect(Collectors.toList());
                    TradingStrategy.TradingSignal signal = request.getStrategy().generateSignal(currentData, indicators, domainPositions);

                    // 5. 根据信号创建订单并处理
                    if (signal != null && signal.getType() != TradingStrategy.TradingSignal.SignalType.NO_ACTION) {
                        executeSignal(portfolioManager, pendingOrders, signal, currentData);
                    }

                    // 6. 在每天结束时记录权益
                    LocalDate currentDate = currentData.getTimestamp().toLocalDate();
                    if (lastDate != null && !currentDate.isEqual(lastDate)) {
                        portfolioManager.recordDailyEquity(lastDate);
                    }
                    lastDate = currentDate;
                }
                if(lastDate != null) {
                    portfolioManager.recordDailyEquity(lastDate);
                }

                return calculateResult(portfolioManager, request);

            } catch (Exception e) {
                log.error("回测执行失败", e);
                return createErrorResult(request, e.getMessage());
            }
        });
    }

    private void checkForTriggeredOrders(List<Order> pendingOrders, MarketData currentData, PortfolioManager portfolioManager) {
        Iterator<Order> iterator = pendingOrders.iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next();
            boolean filled = false;

            // 检查买入限价单是否触发
            if (order.getSide() == OrderSide.BUY && currentData.getLow().compareTo(order.getPrice()) <= 0) {
                // 价格触及或更低，以限价成交
                filled = true;
            }
            // 检查卖出限价单是否触发
            else if (order.getSide() == OrderSide.SELL && currentData.getHigh().compareTo(order.getPrice()) >= 0) {
                // 价格触及或更高，以限价成交
                filled = true;
            }

            if (filled) {
                log.info("限价单触发并成交: {}", order);
                order.setStatus(OrderStatus.FILLED);
                portfolioManager.processTransaction(order); // 使用PortfolioManager处理已成交的订单
                iterator.remove(); // 从待处理列表中移除
            }
        }
    }

    private void executeSignal(PortfolioManager portfolioManager, List<Order> pendingOrders, TradingStrategy.TradingSignal signal, MarketData currentData) {
        int positionSize = 100; // 简化仓位管理
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
        // 策略现在可以指定订单类型和价格
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
    
    // ... 其他辅助方法(fetchHistoricalData, calculateResult等)保持不变，为简化省略 ...
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

    private TechnicalIndicators calculateIndicators(List<MarketData> data) {
        if (data.isEmpty()) {
            return new TechnicalIndicators();
        }
        try {
            String symbol = data.get(0).getSymbol();
            // 调用正确的方法，直接传递数据列表
            return technicalAnalysisService.calculateIndicatorsFromData(symbol, data);
        } catch (Exception e) {
            log.error("技术指标计算失败，返回空指标", e);
            return new TechnicalIndicators();
        }
    }

    private BacktestResult calculateResult(PortfolioManager portfolioManager, BacktestRequest request) {
        BacktestResult result = new BacktestResult();
        result.setStrategy(request.getStrategy().getName());
        result.setSymbol(request.getSymbol());
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setInitialCapital(BigDecimalUtils.scale(portfolioManager.getInitialCash()));

        // 最终权益
        BigDecimal finalEquity = BigDecimalUtils.scale(portfolioManager.calculateTotalEquity());
        result.setFinalEquity(finalEquity);

        // 总收益与年化
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

        // 交易统计
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

        // 权益曲线与风险指标
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
