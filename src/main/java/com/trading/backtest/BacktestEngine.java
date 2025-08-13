package com.trading.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.service.MarketDataService;
import com.trading.strategy.TechnicalAnalysisService;
import com.trading.strategy.TradingStrategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 回测引擎
 * 用于策略的历史数据回测
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private final MarketDataService marketDataService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final DataPreparationService dataPreparationService;
    private final HKStockCommissionCalculator commissionCalculator;

    /**
     * 执行回测
     */
    public CompletableFuture<BacktestResult> runBacktest(BacktestRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("开始回测: strategy={}, symbol={}, period={} to {}",
                    request.getStrategy().getName(),
                    request.getSymbol(),
                    request.getStartTime(),
                    request.getEndTime());

            try {
                // 1. 数据准备阶段
                log.info("第1阶段: 准备回测数据...");
                DataPreparationService.DataPreparationResult dataPrep = dataPreparationService
                        .prepareBacktestData(request).get();

                if (!dataPrep.isSuccessful()) {
                    return BacktestResult.createFailureResult(request, "数据准备失败: " + dataPrep.getError());
                }

                log.info("数据准备完成: {}", dataPrep.getChineseSummary());

                // 2. 初始化回测上下文
                BacktestContext context = initializeContext(request);

                // 3. 获取历史数据（包括预热数据）
                List<MarketData> historicalData = fetchHistoricalData(request);

                if (historicalData.isEmpty()) {
                    log.warn("无历史数据可用于回测");
                    return createEmptyResult(request);
                }

                log.info("加载历史数据: {} 条记录", historicalData.size());

                // 执行回测
                for (int i = 0; i < historicalData.size(); i++) {
                    MarketData currentData = historicalData.get(i);

                    // 计算技术指标
                    TechnicalIndicators indicators = calculateIndicators(
                            historicalData.subList(0, i + 1));

                    // 更新持仓市值
                    updatePositions(context, currentData);

                    // 生成交易信号
                    TradingStrategy.TradingSignal signal = request.getStrategy()
                            .generateSignal(currentData, indicators, context.getPositions());

                    // 执行交易
                    if (signal != null && signal.getType() != TradingStrategy.TradingSignal.SignalType.NO_ACTION
                            && signal.getType() != TradingStrategy.TradingSignal.SignalType.HOLD) {
                        executeSignal(context, signal, currentData, request);
                    }

                    // 记录每日权益
                    recordDailyEquity(context, currentData);
                }

                // 计算回测结果
                return calculateResult(context, request);

            } catch (Exception e) {
                log.error("回测执行失败", e);
                return createErrorResult(request, e.getMessage());
            }
        });
    }

    /**
     * 初始化回测上下文
     */
    private BacktestContext initializeContext(BacktestRequest request) {
        BacktestContext context = new BacktestContext();
        context.setInitialCapital(request.getInitialCapital());
        context.setCash(request.getInitialCapital());
        context.setPositions(new ArrayList<>());
        context.setOrders(new ArrayList<>());
        context.setEquityCurve(new TreeMap<>());
        context.setTotalCommission(BigDecimal.ZERO);
        context.setTotalSlippage(BigDecimal.ZERO);
        return context;
    }

    /**
     * 获取历史数据
     */
    private List<MarketData> fetchHistoricalData(BacktestRequest request) {
        try {
            String timeframe = request.getTimeframe() != null ? request.getTimeframe() : "30m";
            return marketDataService.getOhlcvData(
                    request.getSymbol(),
                    timeframe,
                    request.getStartTime(),
                    request.getEndTime(),
                    10000).get();
        } catch (Exception e) {
            log.error("获取历史数据失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 计算技术指标
     */
    private TechnicalIndicators calculateIndicators(List<MarketData> data) {
        if (data.size() < 30) {
            // 数据不足，返回空指标
            return TechnicalIndicators.builder()
                    .calculatedAt(LocalDateTime.now())
                    .build();
        }

        // 简化版指标计算（实际应使用TechnicalAnalysisService）
        MarketData latest = data.get(data.size() - 1);

        return TechnicalIndicators.builder()
                .macdLine(calculateSimpleMACD(data))
                .rsi(calculateSimpleRSI(data))
                .sma20(calculateSMA(data, 20))
                .volumeRatio(calculateVolumeRatio(data))
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 更新持仓市值
     */
    private void updatePositions(BacktestContext context, MarketData currentData) {
        for (Position position : context.getPositions()) {
            if (position.getSymbol().equals(currentData.getSymbol())) {
                position.setCurrentPrice(currentData.getClose());
                position.setMarketValue(currentData.getClose()
                        .multiply(BigDecimal.valueOf(position.getQuantity())));
                position.setUnrealizedPnl(
                        position.getMarketValue().subtract(
                                position.getAvgCost().multiply(
                                        BigDecimal.valueOf(position.getQuantity()))));
                position.setLastUpdateTime(currentData.getTimestamp());
            }
        }
    }

    /**
     * 执行交易信号
     */
    private void executeSignal(BacktestContext context,
            TradingStrategy.TradingSignal signal,
            MarketData currentData,
            BacktestRequest request) {

        // 计算仓位大小
        int positionSize = request.getStrategy().calculatePositionSize(
                signal, context.getCash(), currentData.getClose());

        if (positionSize <= 0) {
            return;
        }

        // 创建订单
        Order order = createOrder(signal, currentData, positionSize);

        // 应用风险管理
        order = request.getStrategy().applyRiskManagement(order, currentData);

        // 计算交易成本（使用港股手续费计算器）
        boolean isSell = signal.getType() == TradingStrategy.TradingSignal.SignalType.SELL ||
                signal.getType() == TradingStrategy.TradingSignal.SignalType.CLOSE_LONG;

        HKStockCommissionCalculator.CommissionBreakdown commissionBreakdown = commissionCalculator
                .calculateCommission(order.getPrice(), order.getQuantity(), isSell);

        BigDecimal commission = commissionBreakdown.getTotalCost();
        BigDecimal slippage = calculateSlippage(commissionBreakdown.getTradeValue(), request);

        // 检查资金是否充足
        BigDecimal totalCost = commissionBreakdown.getTradeValue().add(commission).add(slippage);
        if (signal.getType() == TradingStrategy.TradingSignal.SignalType.BUY
                && totalCost.compareTo(context.getCash()) > 0) {
            log.debug("资金不足，跳过交易: required={}, available={}",
                    totalCost, context.getCash());
            return;
        }

        // 执行交易
        if (signal.getType() == TradingStrategy.TradingSignal.SignalType.BUY) {
            executeBuy(context, order, commission, slippage);
        } else if (signal.getType() == TradingStrategy.TradingSignal.SignalType.SELL
                || signal.getType() == TradingStrategy.TradingSignal.SignalType.CLOSE_LONG) {
            executeSell(context, order, commission, slippage);
        }

        // 记录订单
        context.getOrders().add(order);

        log.debug("执行交易: type={}, symbol={}, price={}, quantity={}",
                signal.getType(), order.getSymbol(), order.getPrice(), order.getQuantity());
    }

    /**
     * 执行买入
     */
    private void executeBuy(BacktestContext context, Order order,
            BigDecimal commission, BigDecimal slippage) {
        // 扣除资金
        BigDecimal totalCost = order.getPrice()
                .multiply(BigDecimal.valueOf(order.getQuantity()))
                .add(commission)
                .add(slippage);
        context.setCash(context.getCash().subtract(totalCost));

        // 更新或创建持仓
        Position position = context.getPositions().stream()
                .filter(p -> p.getSymbol().equals(order.getSymbol()))
                .findFirst()
                .orElse(null);

        if (position == null) {
            position = Position.builder()
                    .symbol(order.getSymbol())
                    .accountId("backtest")
                    .quantity(order.getQuantity())
                    .avgCost(order.getPrice())
                    .currentPrice(order.getPrice())
                    .openTime(order.getCreateTime())
                    .positionType("LONG")
                    .build();
            context.getPositions().add(position);
        } else {
            // 更新平均成本
            int totalQuantity = position.getQuantity() + order.getQuantity();
            BigDecimal totalCostBasis = position.getAvgCost()
                    .multiply(BigDecimal.valueOf(position.getQuantity()))
                    .add(order.getPrice().multiply(BigDecimal.valueOf(order.getQuantity())));
            position.setAvgCost(totalCostBasis.divide(
                    BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP));
            position.setQuantity(totalQuantity);
        }

        // 更新订单状态
        order.setStatus(OrderStatus.FILLED);
        order.setExecutedPrice(order.getPrice());
        order.setExecutedQuantity(order.getQuantity());
        order.setCommission(commission);
        order.setSlippage(slippage);

        // 累计成本
        context.setTotalCommission(context.getTotalCommission().add(commission));
        context.setTotalSlippage(context.getTotalSlippage().add(slippage));
    }

    /**
     * 执行卖出
     */
    private void executeSell(BacktestContext context, Order order,
            BigDecimal commission, BigDecimal slippage) {
        // 查找持仓
        Position position = context.getPositions().stream()
                .filter(p -> p.getSymbol().equals(order.getSymbol()))
                .findFirst()
                .orElse(null);

        if (position == null || position.getQuantity() <= 0) {
            log.debug("无持仓可卖出: symbol={}", order.getSymbol());
            return;
        }

        // 确定卖出数量
        int sellQuantity = Math.min(order.getQuantity(), position.getQuantity());

        // 计算收入
        BigDecimal revenue = order.getPrice()
                .multiply(BigDecimal.valueOf(sellQuantity))
                .subtract(commission)
                .subtract(slippage);
        context.setCash(context.getCash().add(revenue));

        // 计算已实现盈亏
        BigDecimal realizedPnl = order.getPrice().subtract(position.getAvgCost())
                .multiply(BigDecimal.valueOf(sellQuantity))
                .subtract(commission)
                .subtract(slippage);

        // 更新持仓
        position.setQuantity(position.getQuantity() - sellQuantity);
        if (position.getQuantity() <= 0) {
            context.getPositions().remove(position);
        }

        // 更新订单
        order.setStatus(OrderStatus.FILLED);
        order.setExecutedPrice(order.getPrice());
        order.setExecutedQuantity(sellQuantity);
        order.setCommission(commission);
        order.setSlippage(slippage);
        order.setRealizedPnl(realizedPnl);

        // 累计成本
        context.setTotalCommission(context.getTotalCommission().add(commission));
        context.setTotalSlippage(context.getTotalSlippage().add(slippage));
    }

    /**
     * 记录每日权益
     */
    private void recordDailyEquity(BacktestContext context, MarketData currentData) {
        BigDecimal positionValue = context.getPositions().stream()
                .map(p -> p.getCurrentPrice().multiply(BigDecimal.valueOf(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalEquity = context.getCash().add(positionValue);
        context.getEquityCurve().put(currentData.getTimestamp(), totalEquity);
    }

    /**
     * 计算回测结果
     */
    private BacktestResult calculateResult(BacktestContext context, BacktestRequest request) {
        BacktestResult result = new BacktestResult();
        result.setStrategy(request.getStrategy().getName());
        result.setSymbol(request.getSymbol());
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setInitialCapital(request.getInitialCapital());

        // 计算最终权益
        BigDecimal positionValue = context.getPositions().stream()
                .map(p -> p.getCurrentPrice().multiply(BigDecimal.valueOf(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal finalEquity = context.getCash().add(positionValue);
        result.setFinalEquity(finalEquity);

        // 计算收益
        BigDecimal totalReturn = finalEquity.subtract(request.getInitialCapital());
        BigDecimal returnRate = totalReturn.divide(
                request.getInitialCapital(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        result.setTotalReturn(totalReturn);
        result.setReturnRate(returnRate);

        // 交易统计
        result.setTotalTrades(context.getOrders().size());
        result.setTotalCommission(context.getTotalCommission());
        result.setTotalSlippage(context.getTotalSlippage());

        // 计算性能指标
        TradingStrategy.PerformanceMetrics metrics = request.getStrategy().evaluatePerformance(context.getOrders());
        result.setPerformanceMetrics(metrics);

        // 权益曲线
        result.setEquityCurve(new ArrayList<>(context.getEquityCurve().values()));

        // 计算最大回撤
        result.setMaxDrawdown(calculateMaxDrawdown(context.getEquityCurve()));

        // 计算夏普比率
        result.setSharpeRatio(calculateSharpeRatio(context.getEquityCurve()));

        log.info("回测完成: return={:.2f}%, maxDrawdown={:.2f}%, sharpe={:.2f}",
                returnRate, result.getMaxDrawdown(), result.getSharpeRatio());

        return result;
    }

    // 辅助方法

    private Order createOrder(TradingStrategy.TradingSignal signal,
            MarketData marketData, int quantity) {
        OrderSide side = signal.getType() == TradingStrategy.TradingSignal.SignalType.BUY ? OrderSide.BUY
                : OrderSide.SELL;

        return Order.builder()
                .orderId(UUID.randomUUID().toString())
                .symbol(signal.getSymbol())
                .accountId("backtest")
                .side(side)
                .type(OrderType.MARKET)
                .price(marketData.getClose())
                .quantity(quantity)
                .status(OrderStatus.PENDING)
                .createTime(marketData.getTimestamp())
                .build();
    }

    private BigDecimal calculateCommission(BigDecimal orderValue, BacktestRequest request) {
        return orderValue.multiply(request.getCommissionRate());
    }

    private BigDecimal calculateSlippage(BigDecimal orderValue, BacktestRequest request) {
        return orderValue.multiply(request.getSlippageRate());
    }

    private BigDecimal calculateSimpleMACD(List<MarketData> data) {
        // 简化的MACD计算
        return BigDecimal.ZERO;
    }

    private BigDecimal calculateSimpleRSI(List<MarketData> data) {
        // 简化的RSI计算
        return BigDecimal.valueOf(50);
    }

    private BigDecimal calculateSMA(List<MarketData> data, int period) {
        if (data.size() < period) {
            return BigDecimal.ZERO;
        }

        BigDecimal sum = data.subList(data.size() - period, data.size()).stream()
                .map(MarketData::getClose)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateVolumeRatio(List<MarketData> data) {
        if (data.size() < 2) {
            return BigDecimal.ONE;
        }

        MarketData current = data.get(data.size() - 1);
        MarketData previous = data.get(data.size() - 2);

        if (previous.getVolume() == 0) {
            return BigDecimal.ONE;
        }

        return BigDecimal.valueOf(current.getVolume())
                .divide(BigDecimal.valueOf(previous.getVolume()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMaxDrawdown(Map<LocalDateTime, BigDecimal> equityCurve) {
        if (equityCurve.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (BigDecimal equity : equityCurve.values()) {
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }

            BigDecimal drawdown = peak.subtract(equity)
                    .divide(peak.compareTo(BigDecimal.ZERO) > 0 ? peak : BigDecimal.ONE,
                            4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        return maxDrawdown;
    }

    private BigDecimal calculateSharpeRatio(Map<LocalDateTime, BigDecimal> equityCurve) {
        if (equityCurve.size() < 2) {
            return BigDecimal.ZERO;
        }

        List<BigDecimal> returns = new ArrayList<>();
        BigDecimal previousEquity = null;

        for (BigDecimal equity : equityCurve.values()) {
            if (previousEquity != null) {
                BigDecimal dailyReturn = equity.subtract(previousEquity)
                        .divide(previousEquity, 6, RoundingMode.HALF_UP);
                returns.add(dailyReturn);
            }
            previousEquity = equity;
        }

        if (returns.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 计算平均收益
        BigDecimal avgReturn = returns.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 6, RoundingMode.HALF_UP);

        // 计算标准差
        BigDecimal variance = returns.stream()
                .map(r -> r.subtract(avgReturn).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 6, RoundingMode.HALF_UP);

        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

        if (stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // 年化夏普比率（假设252个交易日）
        return avgReturn.divide(stdDev, 2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(Math.sqrt(252)));
    }

    private BacktestResult createEmptyResult(BacktestRequest request) {
        BacktestResult result = new BacktestResult();
        result.setStrategy(request.getStrategy().getName());
        result.setSymbol(request.getSymbol());
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setInitialCapital(request.getInitialCapital());
        result.setFinalEquity(request.getInitialCapital());
        result.setTotalReturn(BigDecimal.ZERO);
        result.setReturnRate(BigDecimal.ZERO);
        result.setTotalTrades(0);
        return result;
    }

    private BacktestResult createErrorResult(BacktestRequest request, String error) {
        BacktestResult result = createEmptyResult(request);
        result.setError(error);
        return result;
    }

    /**
     * 回测上下文
     */
    private static class BacktestContext {
        private BigDecimal initialCapital;
        private BigDecimal cash;
        private List<Position> positions;
        private List<Order> orders;
        private Map<LocalDateTime, BigDecimal> equityCurve;
        private BigDecimal totalCommission;
        private BigDecimal totalSlippage;

        // Getters and Setters
        public BigDecimal getInitialCapital() {
            return initialCapital;
        }

        public void setInitialCapital(BigDecimal capital) {
            this.initialCapital = capital;
        }

        public BigDecimal getCash() {
            return cash;
        }

        public void setCash(BigDecimal cash) {
            this.cash = cash;
        }

        public List<Position> getPositions() {
            return positions;
        }

        public void setPositions(List<Position> positions) {
            this.positions = positions;
        }

        public List<Order> getOrders() {
            return orders;
        }

        public void setOrders(List<Order> orders) {
            this.orders = orders;
        }

        public Map<LocalDateTime, BigDecimal> getEquityCurve() {
            return equityCurve;
        }

        public void setEquityCurve(Map<LocalDateTime, BigDecimal> curve) {
            this.equityCurve = curve;
        }

        public BigDecimal getTotalCommission() {
            return totalCommission;
        }

        public void setTotalCommission(BigDecimal commission) {
            this.totalCommission = commission;
        }

        public BigDecimal getTotalSlippage() {
            return totalSlippage;
        }

        public void setTotalSlippage(BigDecimal slippage) {
            this.totalSlippage = slippage;
        }
    }
}