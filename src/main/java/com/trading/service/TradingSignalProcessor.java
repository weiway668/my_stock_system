package com.trading.service;

import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderType;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.infrastructure.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 交易信号处理器
 * 根据技术指标和市场数据生成交易信号
 * 实现多种信号策略：MACD、布林带、RSI、成交量分析等
 * 支持多层信号过滤和风险评估
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingSignalProcessor {

    private final MarketDataService marketDataService;
    private final TradingService tradingService;
    private final OrderExecutionService orderExecutionService;
    private final CacheService cacheService;
    private final ApplicationEventPublisher eventPublisher;

    // 信号处理执行器
    private final ScheduledExecutorService signalProcessor = Executors.newScheduledThreadPool(4);
    private final ExecutorService signalWorker = Executors.newCachedThreadPool();
    private final AtomicBoolean processingEnabled = new AtomicBoolean(false);
    private final AtomicLong signalCounter = new AtomicLong(0);

    // 信号监控
    private ScheduledFuture<?> signalGenerationTask;
    private ScheduledFuture<?> signalValidationTask;

    // 配置参数
    private static final BigDecimal SIGNAL_THRESHOLD = BigDecimal.valueOf(0.7); // 70%信号强度阈值
    private static final int MAX_POSITIONS_PER_SYMBOL = 3;
    private static final BigDecimal MAX_POSITION_SIZE_RATIO = BigDecimal.valueOf(0.2); // 20%

    @PostConstruct
    public void initialize() {
        log.info("初始化交易信号处理器...");
        
        // 启动信号生成
        startSignalGeneration();
        
        // 启动信号验证
        startSignalValidation();
        
        processingEnabled.set(true);
        log.info("交易信号处理器初始化完成");
    }

    /**
     * 处理实时市场数据并生成信号
     */
    public CompletableFuture<List<TradingSignal>> processMarketData(MarketData marketData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("处理市场数据生成信号: symbol={}, price={}", 
                    marketData.getSymbol(), marketData.getClose());

                if (!processingEnabled.get()) {
                    return List.of();
                }

                // 获取历史数据用于分析
                List<MarketData> historicalData = getHistoricalDataForAnalysis(
                    marketData.getSymbol(), 50);

                if (historicalData.isEmpty()) {
                    log.warn("无法获取历史数据进行信号分析: symbol={}", marketData.getSymbol());
                    return List.of();
                }

                // 添加当前数据
                historicalData.add(marketData);

                // 生成多种信号
                List<TradingSignal> signals = List.of();
                
                // MACD信号
                TradingSignal macdSignal = generateMacdSignal(marketData, historicalData);
                if (macdSignal != null) {
                    signals = new java.util.ArrayList<>(signals);
                    signals.add(macdSignal);
                }

                // 布林带信号  
                TradingSignal bollingerSignal = generateBollingerSignal(marketData, historicalData);
                if (bollingerSignal != null) {
                    signals = new java.util.ArrayList<>(signals);
                    signals.add(bollingerSignal);
                }

                // RSI信号
                TradingSignal rsiSignal = generateRsiSignal(marketData, historicalData);
                if (rsiSignal != null) {
                    signals = new java.util.ArrayList<>(signals);
                    signals.add(rsiSignal);
                }

                // 成交量信号
                TradingSignal volumeSignal = generateVolumeSignal(marketData, historicalData);
                if (volumeSignal != null) {
                    signals = new java.util.ArrayList<>(signals);
                    signals.add(volumeSignal);
                }

                // 信号过滤和验证
                List<TradingSignal> filteredSignals = filterAndValidateSignals(signals);

                // 缓存信号
                cacheSignals(marketData.getSymbol(), filteredSignals);

                signalCounter.addAndGet(filteredSignals.size());
                
                log.debug("信号生成完成: symbol={}, signals={}", 
                    marketData.getSymbol(), filteredSignals.size());

                return filteredSignals;

            } catch (Exception e) {
                log.error("处理市场数据生成信号异常: symbol={}", 
                    marketData != null ? marketData.getSymbol() : "null", e);
                return List.of();
            }
        }, signalWorker);
    }

    /**
     * 批量处理多个股票的信号
     */
    public CompletableFuture<BatchSignalResult> processBatchSymbols(List<String> symbols) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("批量处理信号: symbols={}", symbols.size());

                int totalSignals = 0;
                int processedSymbols = 0;
                StringBuilder errors = new StringBuilder();

                // 并行处理每个股票
                List<CompletableFuture<List<TradingSignal>>> futures = symbols.stream()
                    .map(this::processSymbolSignals)
                    .toList();

                // 等待所有处理完成
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));

                allFutures.get(30, TimeUnit.SECONDS);

                // 统计结果
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        List<TradingSignal> signals = futures.get(i).get();
                        totalSignals += signals.size();
                        processedSymbols++;
                        
                        log.debug("股票信号处理完成: symbol={}, signals={}", 
                            symbols.get(i), signals.size());
                            
                    } catch (Exception e) {
                        errors.append(String.format("处理%s失败: %s; ", 
                            symbols.get(i), e.getMessage()));
                        log.error("处理股票信号异常: symbol={}", symbols.get(i), e);
                    }
                }

                return BatchSignalResult.builder()
                    .totalSymbols(symbols.size())
                    .processedSymbols(processedSymbols)
                    .totalSignals(totalSignals)
                    .errors(errors.toString())
                    .success(processedSymbols > 0)
                    .build();

            } catch (Exception e) {
                log.error("批量处理信号异常", e);
                return BatchSignalResult.builder()
                    .totalSymbols(symbols.size())
                    .errors("批量处理异常: " + e.getMessage())
                    .success(false)
                    .build();
            }
        }, signalWorker);
    }

    /**
     * 执行交易信号 - 将信号转换为实际订单
     */
    public CompletableFuture<SignalExecutionResult> executeSignal(TradingSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("执行交易信号: symbol={}, type={}, strength={}", 
                    signal.getSymbol(), signal.getSignalType(), signal.getStrength());

                // 信号强度检查
                if (signal.getStrength().compareTo(SIGNAL_THRESHOLD) < 0) {
                    return SignalExecutionResult.skipped("信号强度不足: " + signal.getStrength());
                }

                // 风险检查
                RiskCheckResult riskCheck = performSignalRiskCheck(signal);
                if (!riskCheck.isPassed()) {
                    return SignalExecutionResult.rejected("风险检查失败: " + riskCheck.getReason());
                }

                // 创建订单请求
                TradingService.OrderRequest orderRequest = createOrderFromSignal(signal);
                if (orderRequest == null) {
                    return SignalExecutionResult.failed("无法创建订单请求");
                }

                // 提交订单
                CompletableFuture<TradingService.OrderResult> orderFuture = 
                    tradingService.submitOrder(orderRequest);
                
                TradingService.OrderResult orderResult = orderFuture.get(10, TimeUnit.SECONDS);

                if (orderResult.isSuccess()) {
                    // 记录信号执行
                    recordSignalExecution(signal, orderResult.getOrder());
                    
                    return SignalExecutionResult.builder()
                        .success(true)
                        .signalId(signal.getSignalId())
                        .orderId(orderResult.getOrder() != null ? orderResult.getOrder().getOrderId() : null)
                        .message("信号执行成功")
                        .build();
                } else {
                    return SignalExecutionResult.failed("订单提交失败: " + orderResult.getMessage());
                }

            } catch (Exception e) {
                log.error("执行交易信号异常: signalId={}", signal.getSignalId(), e);
                return SignalExecutionResult.failed("信号执行异常: " + e.getMessage());
            }
        }, signalWorker);
    }

    /**
     * 获取信号统计
     */
    public SignalStatistics getSignalStatistics() {
        try {
            long totalSignalsGenerated = signalCounter.get();
            
            // 从缓存获取最近信号数据
            int recentBuySignals = getRecentSignalCount("BUY", 1);
            int recentSellSignals = getRecentSignalCount("SELL", 1);
            int recentHoldSignals = getRecentSignalCount("HOLD", 1);

            return SignalStatistics.builder()
                .totalSignalsGenerated(totalSignalsGenerated)
                .recentBuySignals(recentBuySignals)
                .recentSellSignals(recentSellSignals)
                .recentHoldSignals(recentHoldSignals)
                .processingEnabled(processingEnabled.get())
                .averageSignalStrength(calculateAverageSignalStrength())
                .build();

        } catch (Exception e) {
            log.error("获取信号统计异常", e);
            return SignalStatistics.builder().build();
        }
    }

    // 私有方法实现

    /**
     * 处理单个股票信号
     */
    private CompletableFuture<List<TradingSignal>> processSymbolSignals(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 获取最新市场数据
                java.util.Optional<MarketData> latestData = marketDataService.getLatestMarketData(symbol);
                
                if (latestData.isPresent()) {
                    return processMarketData(latestData.get()).get();
                } else {
                    log.warn("无法获取最新市场数据: symbol={}", symbol);
                    return List.of();
                }

            } catch (Exception e) {
                log.error("处理股票信号异常: symbol={}", symbol, e);
                return List.of();
            }
        }, signalWorker);
    }

    /**
     * 生成MACD信号
     */
    private TradingSignal generateMacdSignal(MarketData currentData, List<MarketData> historicalData) {
        try {
            TechnicalIndicators indicators = currentData.getIndicators();
            if (indicators == null || !hasValidMacd(indicators)) {
                return null;
            }

            SignalType signalType = SignalType.HOLD;
            BigDecimal strength = BigDecimal.ZERO;
            String reason = "";

            // MACD金叉 - 买入信号
            if (indicators.isMacdBullish()) {
                signalType = SignalType.BUY;
                strength = calculateMacdSignalStrength(indicators, historicalData);
                reason = "MACD金叉，买入信号";
            }
            // MACD死叉 - 卖出信号  
            else if (!indicators.isMacdBullish() && indicators.getMacdLine() != null) {
                signalType = SignalType.SELL;
                strength = calculateMacdSignalStrength(indicators, historicalData);
                reason = "MACD死叉，卖出信号";
            }

            if (signalType != SignalType.HOLD) {
                return TradingSignal.builder()
                    .signalId(generateSignalId())
                    .symbol(currentData.getSymbol())
                    .signalType(signalType)
                    .strength(strength)
                    .price(currentData.getClose())
                    .timestamp(LocalDateTime.now())
                    .strategy("MACD")
                    .reason(reason)
                    .confidence(strength.multiply(BigDecimal.valueOf(0.8)))
                    .build();
            }

            return null;

        } catch (Exception e) {
            log.error("生成MACD信号异常: symbol={}", currentData.getSymbol(), e);
            return null;
        }
    }

    /**
     * 生成布林带信号
     */
    private TradingSignal generateBollingerSignal(MarketData currentData, List<MarketData> historicalData) {
        try {
            TechnicalIndicators indicators = currentData.getIndicators();
            if (indicators == null || indicators.getUpperBand() == null || indicators.getLowerBand() == null) {
                return null;
            }

            BigDecimal currentPrice = currentData.getClose();
            SignalType signalType = SignalType.HOLD;
            BigDecimal strength = BigDecimal.ZERO;
            String reason = "";

            // 价格突破上轨 - 可能的卖出信号（超买）
            if (indicators.isAboveUpperBand(currentPrice)) {
                signalType = SignalType.SELL;
                strength = calculateBollingerSignalStrength(currentPrice, indicators);
                reason = "价格突破布林带上轨，超买信号";
            }
            // 价格跌破下轨 - 可能的买入信号（超卖）
            else if (indicators.isBelowLowerBand(currentPrice)) {
                signalType = SignalType.BUY;
                strength = calculateBollingerSignalStrength(currentPrice, indicators);
                reason = "价格跌破布林带下轨，超卖信号";
            }

            if (signalType != SignalType.HOLD) {
                return TradingSignal.builder()
                    .signalId(generateSignalId())
                    .symbol(currentData.getSymbol())
                    .signalType(signalType)
                    .strength(strength)
                    .price(currentPrice)
                    .timestamp(LocalDateTime.now())
                    .strategy("BOLLINGER")
                    .reason(reason)
                    .confidence(strength.multiply(BigDecimal.valueOf(0.75)))
                    .build();
            }

            return null;

        } catch (Exception e) {
            log.error("生成布林带信号异常: symbol={}", currentData.getSymbol(), e);
            return null;
        }
    }

    /**
     * 生成RSI信号
     */
    private TradingSignal generateRsiSignal(MarketData currentData, List<MarketData> historicalData) {
        try {
            TechnicalIndicators indicators = currentData.getIndicators();
            if (indicators == null || indicators.getRsi() == null) {
                return null;
            }

            SignalType signalType = SignalType.HOLD;
            BigDecimal strength = BigDecimal.ZERO;
            String reason = "";

            // RSI超买 - 卖出信号
            if (indicators.isOverbought()) {
                signalType = SignalType.SELL;
                strength = calculateRsiSignalStrength(indicators.getRsi());
                reason = "RSI超买（>70），卖出信号";
            }
            // RSI超卖 - 买入信号
            else if (indicators.isOversold()) {
                signalType = SignalType.BUY;
                strength = calculateRsiSignalStrength(indicators.getRsi());
                reason = "RSI超卖（<30），买入信号";
            }

            if (signalType != SignalType.HOLD) {
                return TradingSignal.builder()
                    .signalId(generateSignalId())
                    .symbol(currentData.getSymbol())
                    .signalType(signalType)
                    .strength(strength)
                    .price(currentData.getClose())
                    .timestamp(LocalDateTime.now())
                    .strategy("RSI")
                    .reason(reason)
                    .confidence(strength.multiply(BigDecimal.valueOf(0.7)))
                    .build();
            }

            return null;

        } catch (Exception e) {
            log.error("生成RSI信号异常: symbol={}", currentData.getSymbol(), e);
            return null;
        }
    }

    /**
     * 生成成交量信号
     */
    private TradingSignal generateVolumeSignal(MarketData currentData, List<MarketData> historicalData) {
        try {
            TechnicalIndicators indicators = currentData.getIndicators();
            if (indicators == null || indicators.getVolumeRatio() == null) {
                return null;
            }

            BigDecimal volumeRatio = indicators.getVolumeRatio();
            SignalType signalType = SignalType.HOLD;
            BigDecimal strength = BigDecimal.ZERO;
            String reason = "";

            // 成交量异常放大 - 可能有重要信号
            if (volumeRatio.compareTo(BigDecimal.valueOf(2.0)) > 0) {
                // 结合价格变化判断方向
                BigDecimal priceChange = calculatePriceChangeRatio(currentData, historicalData);
                
                if (priceChange.compareTo(BigDecimal.valueOf(0.02)) > 0) { // 价格上涨>2%
                    signalType = SignalType.BUY;
                    reason = "成交量放大且价格上涨，买入信号";
                } else if (priceChange.compareTo(BigDecimal.valueOf(-0.02)) < 0) { // 价格下跌>2%
                    signalType = SignalType.SELL;  
                    reason = "成交量放大且价格下跌，卖出信号";
                }

                strength = calculateVolumeSignalStrength(volumeRatio, priceChange);
            }

            if (signalType != SignalType.HOLD) {
                return TradingSignal.builder()
                    .signalId(generateSignalId())
                    .symbol(currentData.getSymbol())
                    .signalType(signalType)
                    .strength(strength)
                    .price(currentData.getClose())
                    .timestamp(LocalDateTime.now())
                    .strategy("VOLUME")
                    .reason(reason)
                    .confidence(strength.multiply(BigDecimal.valueOf(0.6)))
                    .build();
            }

            return null;

        } catch (Exception e) {
            log.error("生成成交量信号异常: symbol={}", currentData.getSymbol(), e);
            return null;
        }
    }

    /**
     * 信号过滤和验证
     */
    private List<TradingSignal> filterAndValidateSignals(List<TradingSignal> signals) {
        return signals.stream()
            .filter(signal -> signal.getStrength().compareTo(BigDecimal.valueOf(0.5)) >= 0)
            .filter(this::validateSignalTiming)
            .filter(this::validateSignalConsistency)
            .sorted((s1, s2) -> s2.getStrength().compareTo(s1.getStrength())) // 按强度降序
            .limit(3) // 最多保留3个最强信号
            .toList();
    }

    /**
     * 验证信号时机
     */
    private boolean validateSignalTiming(TradingSignal signal) {
        // TODO: 实现信号时机验证逻辑
        // 1. 检查市场开盘时间
        // 2. 检查是否在交易时间内
        // 3. 检查是否有重要事件影响
        return true;
    }

    /**
     * 验证信号一致性
     */
    private boolean validateSignalConsistency(TradingSignal signal) {
        try {
            // 检查同一股票是否有冲突信号
            String cacheKey = "recent_signals:" + signal.getSymbol();
            @SuppressWarnings("unchecked")
            List<TradingSignal> recentSignals = cacheService.get(cacheKey, List.class);
            
            if (recentSignals != null) {
                // 检查最近5分钟内是否有相反信号
                LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
                boolean hasConflicting = recentSignals.stream()
                    .filter(s -> s.getTimestamp().isAfter(fiveMinutesAgo))
                    .anyMatch(s -> isConflictingSignal(signal, s));
                    
                if (hasConflicting) {
                    log.warn("发现冲突信号，过滤掉: symbol={}, type={}", 
                        signal.getSymbol(), signal.getSignalType());
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            log.error("验证信号一致性异常", e);
            return true; // 验证失败时默认通过
        }
    }

    /**
     * 检查信号冲突
     */
    private boolean isConflictingSignal(TradingSignal signal1, TradingSignal signal2) {
        return (signal1.getSignalType() == SignalType.BUY && signal2.getSignalType() == SignalType.SELL) ||
               (signal1.getSignalType() == SignalType.SELL && signal2.getSignalType() == SignalType.BUY);
    }

    /**
     * 缓存信号
     */
    private void cacheSignals(String symbol, List<TradingSignal> signals) {
        try {
            if (!signals.isEmpty()) {
                String cacheKey = "recent_signals:" + symbol;
                cacheService.cache(cacheKey, signals, 1800); // 缓存30分钟
                
                // 缓存最新信号
                TradingSignal latestSignal = signals.get(0);
                cacheService.cache("latest_signal:" + symbol, latestSignal, 600); // 缓存10分钟
            }

        } catch (Exception e) {
            log.warn("缓存信号异常: symbol={}", symbol, e);
        }
    }

    /**
     * 信号风险检查
     */
    private RiskCheckResult performSignalRiskCheck(TradingSignal signal) {
        try {
            // TODO: 实现详细的风险检查逻辑
            // 1. 检查持仓限制
            // 2. 检查资金使用率
            // 3. 检查市场波动率
            // 4. 检查流动性
            
            return RiskCheckResult.builder()
                .passed(true)
                .build();

        } catch (Exception e) {
            log.error("信号风险检查异常", e);
            return RiskCheckResult.builder()
                .passed(false)
                .reason("风险检查系统异常")
                .build();
        }
    }

    /**
     * 从信号创建订单请求
     */
    private TradingService.OrderRequest createOrderFromSignal(TradingSignal signal) {
        try {
            OrderSide orderSide = signal.getSignalType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL;
            
            // 根据信号强度计算订单数量
            Long quantity = calculateOrderQuantity(signal);
            
            return TradingService.OrderRequest.builder()
                .symbol(signal.getSymbol())
                .orderType(OrderType.LIMIT)
                .side(orderSide)
                .quantity(quantity)
                .price(signal.getPrice())
                .accountId("default_account")
                .clientOrderId(signal.getSignalId())
                .build();

        } catch (Exception e) {
            log.error("从信号创建订单请求异常", e);
            return null;
        }
    }

    /**
     * 计算订单数量
     */
    private Long calculateOrderQuantity(TradingSignal signal) {
        // 基础数量
        long baseQuantity = 1000;
        
        // 根据信号强度调整
        double strengthMultiplier = signal.getStrength().doubleValue();
        
        return Math.round(baseQuantity * strengthMultiplier);
    }

    /**
     * 记录信号执行
     */
    private void recordSignalExecution(TradingSignal signal, Order order) {
        try {
            // 记录到缓存
            String executionKey = "signal_execution:" + signal.getSignalId();
            SignalExecution execution = SignalExecution.builder()
                .signalId(signal.getSignalId())
                .orderId(order != null ? order.getOrderId() : null)
                .executedAt(LocalDateTime.now())
                .executionPrice(order != null ? order.getPrice() : null)
                .build();
                
            cacheService.cache(executionKey, execution, 86400); // 缓存24小时
            
            log.info("信号执行记录已保存: signalId={}, orderId={}", 
                signal.getSignalId(), order != null ? order.getOrderId() : "null");

        } catch (Exception e) {
            log.warn("记录信号执行异常", e);
        }
    }

    // 辅助计算方法
    
    private List<MarketData> getHistoricalDataForAnalysis(String symbol, int limit) {
        try {
            // TODO: 从MarketDataService获取历史数据
            return List.of(); // 临时返回空列表
        } catch (Exception e) {
            log.error("获取历史数据异常: symbol={}", symbol, e);
            return List.of();
        }
    }

    private boolean hasValidMacd(TechnicalIndicators indicators) {
        return indicators.getMacdLine() != null && indicators.getSignalLine() != null;
    }

    private BigDecimal calculateMacdSignalStrength(TechnicalIndicators indicators, List<MarketData> historicalData) {
        // 简化的MACD强度计算
        if (indicators.getHistogram() != null) {
            return indicators.getHistogram().abs().divide(BigDecimal.valueOf(10), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(0.5);
    }

    private BigDecimal calculateBollingerSignalStrength(BigDecimal price, TechnicalIndicators indicators) {
        // 计算价格偏离中轨的程度
        BigDecimal deviation = price.subtract(indicators.getMiddleBand())
            .abs()
            .divide(indicators.getMiddleBand(), 4, RoundingMode.HALF_UP);
        return deviation.min(BigDecimal.ONE);
    }

    private BigDecimal calculateRsiSignalStrength(BigDecimal rsi) {
        if (rsi.compareTo(BigDecimal.valueOf(70)) > 0) {
            // 超买强度
            return rsi.subtract(BigDecimal.valueOf(70))
                .divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
        } else if (rsi.compareTo(BigDecimal.valueOf(30)) < 0) {
            // 超卖强度
            return BigDecimal.valueOf(30).subtract(rsi)
                .divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal calculatePriceChangeRatio(MarketData currentData, List<MarketData> historicalData) {
        if (historicalData.size() < 2) {
            return BigDecimal.ZERO;
        }
        
        MarketData previousData = historicalData.get(historicalData.size() - 2);
        return currentData.getClose()
            .subtract(previousData.getClose())
            .divide(previousData.getClose(), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateVolumeSignalStrength(BigDecimal volumeRatio, BigDecimal priceChange) {
        BigDecimal volumeStrength = volumeRatio.divide(BigDecimal.valueOf(5), 4, RoundingMode.HALF_UP);
        BigDecimal priceStrength = priceChange.abs().multiply(BigDecimal.valueOf(10));
        return volumeStrength.add(priceStrength).min(BigDecimal.ONE);
    }

    private String generateSignalId() {
        return "SIG_" + System.currentTimeMillis() + "_" + 
               String.format("%04d", (int)(Math.random() * 10000));
    }

    private int getRecentSignalCount(String signalType, int hours) {
        // TODO: 从缓存或数据库查询最近信号数量
        return (int)(Math.random() * 10); // 模拟值
    }

    private double calculateAverageSignalStrength() {
        // TODO: 计算平均信号强度
        return 0.65; // 模拟值
    }

    private void startSignalGeneration() {
        signalGenerationTask = signalProcessor.scheduleWithFixedDelay(() -> {
            try {
                if (processingEnabled.get()) {
                    // TODO: 定期生成信号的逻辑
                    log.trace("信号生成任务执行");
                }
            } catch (Exception e) {
                log.error("信号生成任务异常", e);
            }
        }, 10, 30, TimeUnit.SECONDS);

        log.debug("信号生成任务已启动");
    }

    private void startSignalValidation() {
        signalValidationTask = signalProcessor.scheduleWithFixedDelay(() -> {
            try {
                // TODO: 验证历史信号准确性
                log.trace("信号验证任务执行");
            } catch (Exception e) {
                log.error("信号验证任务异常", e);
            }
        }, 60, 300, TimeUnit.SECONDS);

        log.debug("信号验证任务已启动");
    }

    public boolean isHealthy() {
        return processingEnabled.get() && 
               signalProcessor != null && !signalProcessor.isShutdown() &&
               signalWorker != null && !signalWorker.isShutdown();
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭交易信号处理器...");
        
        processingEnabled.set(false);
        
        try {
            // 取消任务
            if (signalGenerationTask != null) {
                signalGenerationTask.cancel(false);
            }
            if (signalValidationTask != null) {
                signalValidationTask.cancel(false);
            }
            
            // 关闭执行器
            signalProcessor.shutdown();
            signalWorker.shutdown();
            
            if (!signalProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                signalProcessor.shutdownNow();
            }
            if (!signalWorker.awaitTermination(5, TimeUnit.SECONDS)) {
                signalWorker.shutdownNow();
            }
            
            log.info("交易信号处理器关闭完成");
            
        } catch (Exception e) {
            log.error("关闭交易信号处理器异常", e);
        }
    }

    // 数据类和枚举定义

    public enum SignalType {
        BUY,    // 买入信号
        SELL,   // 卖出信号
        HOLD    // 持有信号
    }

    @lombok.Builder
    @lombok.Data
    public static class TradingSignal {
        private String signalId;
        private String symbol;
        private SignalType signalType;
        private BigDecimal strength;      // 信号强度 0-1
        private BigDecimal confidence;    // 信号置信度 0-1
        private BigDecimal price;
        private LocalDateTime timestamp;
        private String strategy;          // 策略名称
        private String reason;            // 信号原因
        private java.util.Map<String, Object> metadata; // 额外元数据
    }

    @lombok.Builder
    @lombok.Data
    public static class BatchSignalResult {
        private int totalSymbols;
        private int processedSymbols;
        private int totalSignals;
        private String errors;
        private boolean success;
    }

    @lombok.Builder
    @lombok.Data
    public static class SignalExecutionResult {
        private boolean success;
        private String signalId;
        private String orderId;
        private String message;
        
        public static SignalExecutionResult skipped(String message) {
            return SignalExecutionResult.builder()
                .success(false)
                .message(message)
                .build();
        }
        
        public static SignalExecutionResult rejected(String message) {
            return SignalExecutionResult.builder()
                .success(false)
                .message(message)
                .build();
        }
        
        public static SignalExecutionResult failed(String message) {
            return SignalExecutionResult.builder()
                .success(false)
                .message(message)
                .build();
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class RiskCheckResult {
        private boolean passed;
        private String reason;
    }

    @lombok.Builder
    @lombok.Data
    public static class SignalExecution {
        private String signalId;
        private String orderId;
        private LocalDateTime executedAt;
        private BigDecimal executionPrice;
    }

    @lombok.Builder
    @lombok.Data
    public static class SignalStatistics {
        private long totalSignalsGenerated;
        private int recentBuySignals;
        private int recentSellSignals;
        private int recentHoldSignals;
        private boolean processingEnabled;
        private double averageSignalStrength;
    }
}