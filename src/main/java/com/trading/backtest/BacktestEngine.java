package com.trading.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

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
                    request.getStrategy().getName(), request.getSymbol(), request.getStartTime(), request.getEndTime());

            try {
                // 1. 数据准备
                log.info("阶段1: 准备数据...");
                List<MarketData> historicalData = fetchHistoricalData(request);
                if (historicalData.isEmpty()) {
                    log.warn("无历史数据可用于回测");
                    return createEmptyResult(request);
                }
                log.info("数据加载完成: {} 条记录", historicalData.size());

                // 2. 初始化投资组合管理器
                PortfolioManager portfolioManager = new PortfolioManager(request.getInitialCapital().doubleValue());
                LocalDate lastDate = null;

                // 3. 循环执行回测
                for (int i = 0; i < historicalData.size(); i++) {
                    MarketData currentData = historicalData.get(i);

                    // a. 更新持仓市值
                    portfolioManager.updatePositionsMarketValue(Map.of(currentData.getSymbol(), currentData.getClose()));

                    // b. 计算技术指标 (需要足够的历史数据)
                    TechnicalIndicators indicators = (i > 20) ? calculateIndicators(historicalData.subList(0, i + 1)) : new TechnicalIndicators();

                    // c. 策略生成交易信号
                    List<com.trading.domain.entity.Position> domainPositions = portfolioManager.getPositions().values().stream()
                            .map(this::convertToDomainPosition).collect(Collectors.toList());
                    TradingStrategy.TradingSignal signal = request.getStrategy()
                            .generateSignal(currentData, indicators, domainPositions);

                    // d. 根据信号执行交易
                    if (signal != null && signal.getType() != TradingStrategy.TradingSignal.SignalType.NO_ACTION) {
                        executeSignal(portfolioManager, signal, currentData, request);
                    }

                    // e. 在每天结束时记录权益
                    LocalDate currentDate = currentData.getTimestamp().toLocalDate();
                    if (lastDate != null && !currentDate.isEqual(lastDate)) {
                        portfolioManager.recordDailyEquity(lastDate);
                    }
                    lastDate = currentDate;
                }
                // 记录最后一日的权益
                if(lastDate != null) {
                    portfolioManager.recordDailyEquity(lastDate);
                }

                // 4. 计算最终回测结果
                return calculateResult(portfolioManager, request);

            } catch (Exception e) {
                log.error("回测执行失败", e);
                return createErrorResult(request, e.getMessage());
            }
        });
    }

    private com.trading.domain.entity.Position convertToDomainPosition(PortfolioManager.Position backtestPosition) {
        if (backtestPosition == null) {
            return null;
        }
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
            return marketDataService.getOhlcvData(
                    request.getSymbol(), timeframe, request.getStartTime(), request.getEndTime(), 10000).get();
        } catch (Exception e) {
            log.error("获取历史数据失败", e);
            return new ArrayList<>();
        }
    }

    private TechnicalIndicators calculateIndicators(List<MarketData> data) {
        // ... (此部分保持不变，为简化省略)
        return new TechnicalIndicators();
    }

    private void executeSignal(PortfolioManager portfolioManager, TradingStrategy.TradingSignal signal, MarketData currentData, BacktestRequest request) {
        // 仓位大小计算等逻辑可以进一步抽象
        long positionSize = 100; // 简化为每次交易100股

        Order order = createOrder(signal, currentData, positionSize);
        
        // 交易成本计算 (此处可以简化或在PortfolioManager内部处理)
        // BigDecimal commission = commissionCalculator.calculate(...);

        portfolioManager.processTransaction(order);
    }

    private Order createOrder(TradingStrategy.TradingSignal signal, MarketData marketData, long quantity) {
        OrderSide side = (signal.getType() == TradingStrategy.TradingSignal.SignalType.BUY) ? OrderSide.BUY : OrderSide.SELL;
        return Order.builder()
                .orderId(UUID.randomUUID().toString())
                .symbol(signal.getSymbol())
                .accountId("backtest")
                .side(side)
                .type(OrderType.MARKET)
                .price(marketData.getClose()) // 以当前收盘价作为执行价
                .quantity((int)quantity)
                .status(OrderStatus.FILLED) // 回测中简化为立即成交
                .createTime(marketData.getTimestamp())
                .build();
    }

    private BacktestResult calculateResult(PortfolioManager portfolioManager, BacktestRequest request) {
        BacktestResult result = new BacktestResult();
        result.setStrategy(request.getStrategy().getName());
        result.setSymbol(request.getSymbol());
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setInitialCapital(portfolioManager.getInitialCash());

        BigDecimal finalEquity = portfolioManager.calculateTotalEquity();
        result.setFinalEquity(finalEquity);

        BigDecimal totalReturn = finalEquity.subtract(portfolioManager.getInitialCash());
        result.setTotalReturn(totalReturn);

        if (portfolioManager.getInitialCash().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal returnRate = totalReturn.divide(portfolioManager.getInitialCash(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            result.setReturnRate(returnRate);
        }

        result.setTotalTrades(portfolioManager.getTradeHistory().size());
        // result.setTotalCommission(...); // 可从PortfolioManager获取

        List<BigDecimal> equityCurve = portfolioManager.getDailyEquitySnapshots().stream()
                .map(PortfolioManager.EquitySnapshot::totalValue)
                .collect(Collectors.toList());
        result.setEquityCurve(equityCurve);

        result.setMaxDrawdown(calculateMaxDrawdown(equityCurve));
        result.setSharpeRatio(calculateSharpeRatio(equityCurve));

        log.info("回测完成: 最终权益 {}, 收益率 {:.2f}%", finalEquity, result.getReturnRate());
        return result;
    }

    private BigDecimal calculateMaxDrawdown(List<BigDecimal> equityCurve) {
        if (equityCurve == null || equityCurve.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal peak = equityCurve.get(0);
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (BigDecimal equity : equityCurve) {
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            BigDecimal drawdown = peak.subtract(equity).divide(peak, 4, RoundingMode.HALF_UP);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }
        return maxDrawdown.multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateSharpeRatio(List<BigDecimal> equityCurve) {
        // ... (夏普比率计算简化)
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
