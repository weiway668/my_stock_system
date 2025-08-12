# 服务层设计

## 四、服务层设计

### 4.1 市场数据服务

```java
@Service
@Slf4j
public class MarketDataService {
    
    private final MarketDataRepository repository;
    private final RedisTemplate<String, MarketData> redisTemplate;
    private final FutuMarketDataClient futuClient;
    private final IndicatorCalculator indicatorCalculator;
    
    // 获取实时行情
    public MarketData getRealTimeData(String symbol) {
        // 1. 先从Redis获取
        String key = "MARKET:" + symbol;
        MarketData cached = redisTemplate.opsForValue().get(key);
        if (cached != null && isValid(cached)) {
            return cached;
        }
        
        // 2. 从数据源获取
        MarketData data = futuClient.getQuote(symbol);
        
        // 3. 计算技术指标
        TechnicalIndicators indicators = indicatorCalculator
            .calculate(symbol, data);
        data.setIndicators(indicators);
        
        // 4. 更新缓存
        redisTemplate.opsForValue().set(key, data, 
            Duration.ofSeconds(5));
        
        // 5. 异步保存到数据库
        CompletableFuture.runAsync(() -> 
            repository.save(data)
        );
        
        return data;
    }
    
    // 批量获取历史数据
    public List<MarketData> getHistoricalData(
            String symbol, 
            LocalDateTime from, 
            LocalDateTime to) {
        return repository.findBySymbolAndTimestampBetween(
            symbol, from, to
        );
    }
    
    // 订阅实时数据流
    public void subscribeRealTimeData(String symbol, 
            Consumer<MarketData> consumer) {
        futuClient.subscribe(symbol, data -> {
            // 计算指标
            TechnicalIndicators indicators = 
                indicatorCalculator.calculate(symbol, data);
            data.setIndicators(indicators);
            
            // 处理数据
            consumer.accept(data);
            
            // 保存
            saveMarketData(data);
        });
    }
}
```

### 4.2 策略服务（针对港股的四层过滤系统）

```java
@Service
@Slf4j
public class HKStockStrategyService {
    
    private final SignalRepository signalRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MarketDataService marketDataService;
    
    // 策略评估 - 专门针对港股的四层过滤系统
    public Optional<TradingSignal> evaluateStrategy(
            String strategyName, 
            MarketData data30m,
            MarketData data120m) {
        
        // 1. 市场状态识别层 (15%)
        MarketState marketState = identifyMarketState(data30m, data120m);
        BigDecimal stateScore = evaluateMarketState(marketState, data30m);
        if (stateScore.compareTo(BigDecimal.valueOf(50)) < 0) {
            log.debug("市场状态不适合交易: {}", marketState);
            return Optional.empty();
        }
        
        // 2. MACD信号层 (35%) - 主要信号
        BigDecimal macdScore = evaluateMACDSignal(data30m, data120m);
        if (macdScore.compareTo(BigDecimal.valueOf(60)) < 0) {
            log.debug("MACD信号未通过: {}", macdScore);
            return Optional.empty();
        }
        
        // 3. BOLL通道层 (25%)
        BigDecimal bollScore = evaluateBOLLChannel(data30m, data120m);
        if (bollScore.compareTo(BigDecimal.valueOf(50)) < 0) {
            log.debug("BOLL通道检查未通过: {}", bollScore);
            return Optional.empty();
        }
        
        // 4. 量价确认层 (25%)
        BigDecimal volumeScore = evaluateVolumePrice(data30m, data120m);
        if (volumeScore.compareTo(BigDecimal.valueOf(60)) < 0) {
            log.debug("量价确认未通过: {}", volumeScore);
            return Optional.empty();
        }
        
        // 计算综合得分（加权）
        BigDecimal totalScore = calculateWeightedScore(
            stateScore, 0.15,
            macdScore, 0.35,
            bollScore, 0.25,
            volumeScore, 0.25
        );
        
        if (totalScore.compareTo(BigDecimal.valueOf(70)) >= 0) {
            // 根据市场状态选择具体策略
            TradingSignal signal = createStrategySignal(
                marketState, strategyName, data30m, totalScore,
                macdScore, bollScore, volumeScore
            );
            
            // 多周期共振验证
            if (!validateMultiTimeframe(signal, data30m, data120m)) {
                log.info("多周期共振验证未通过");
                return Optional.empty();
            }
            
            // 保存并发送信号
            signalRepository.save(signal);
            eventPublisher.publishEvent(new TradingSignalEvent(signal));
            
            return Optional.of(signal);
        }
        
        return Optional.empty();
    }
    
    // 市场状态识别
    private MarketState identifyMarketState(MarketData data30m, MarketData data120m) {
        TechnicalIndicators ind30m = data30m.getIndicators();
        TechnicalIndicators ind120m = data120m.getIndicators();
        
        // ATR分析 - 判断波动性
        BigDecimal atr30m = ind30m.getAtr();
        BigDecimal atr120m = ind120m.getAtr();
        BigDecimal price = data30m.getClose();
        
        BigDecimal atrRatio = atr30m.divide(price, 4, RoundingMode.HALF_UP);
        
        // BOLL带宽分析
        BigDecimal bandwidth = calculateBandwidth(ind30m);
        
        // ADX趋势强度
        BigDecimal adx = ind120m.getAdx();
        
        if (adx.compareTo(BigDecimal.valueOf(25)) > 0 && 
            bandwidth.compareTo(BigDecimal.valueOf(0.1)) > 0) {
            return MarketState.TRENDING; // 趋势市
        } else if (adx.compareTo(BigDecimal.valueOf(20)) < 0 && 
                   bandwidth.compareTo(BigDecimal.valueOf(0.05)) < 0) {
            return MarketState.RANGING; // 震荡市
        } else {
            return MarketState.BREAKOUT; // 突破/转折市
        }
    }
    
    // MACD信号评估（核心）
    private BigDecimal evaluateMACDSignal(MarketData data30m, MarketData data120m) {
        TechnicalIndicators ind30m = data30m.getIndicators();
        TechnicalIndicators ind120m = data120m.getIndicators();
        
        BigDecimal score = BigDecimal.ZERO;
        
        // 30分钟MACD状态（40分）
        BigDecimal macdLine30m = ind30m.getMacdLine();
        BigDecimal signalLine30m = ind30m.getSignalLine();
        BigDecimal histogram30m = ind30m.getHistogram();
        
        // 金叉/死叉判断
        if (macdLine30m.compareTo(signalLine30m) > 0) { // 金叉状态
            score = score.add(BigDecimal.valueOf(20));
            
            if (histogram30m.compareTo(BigDecimal.ZERO) > 0) {
                score = score.add(BigDecimal.valueOf(20)); // 柱状图为正
            }
        }
        
        // 120分钟MACD确认（30分）
        BigDecimal macdLine120m = ind120m.getMacdLine();
        BigDecimal signalLine120m = ind120m.getSignalLine();
        
        if (macdLine120m.compareTo(signalLine120m) > 0) { // 大周期同向
            score = score.add(BigDecimal.valueOf(30));
        }
        
        // MACD背离检测（30分）
        if (!detectMACDDivergence(data30m)) {
            score = score.add(BigDecimal.valueOf(30));
        }
        
        return score;
    }
    
    // BOLL通道评估
    private BigDecimal evaluateBOLLChannel(MarketData data30m, MarketData data120m) {
        TechnicalIndicators ind30m = data30m.getIndicators();
        BigDecimal price = data30m.getClose();
        
        BigDecimal upperBand = ind30m.getUpperBand();
        BigDecimal middleBand = ind30m.getMiddleBand();
        BigDecimal lowerBand = ind30m.getLowerBand();
        
        BigDecimal score = BigDecimal.ZERO;
        
        // 价格位置评分（50分）
        if (price.compareTo(middleBand) > 0 && 
            price.compareTo(upperBand) < 0) {
            // 价格在中轨上方，上轨下方 - 上升趋势
            score = score.add(BigDecimal.valueOf(50));
        } else if (price.compareTo(lowerBand) > 0 && 
                   price.compareTo(middleBand) < 0) {
            // 价格在下轨上方，中轨下方 - 可能反弹
            score = score.add(BigDecimal.valueOf(30));
        }
        
        // 带宽分析（50分）
        BigDecimal bandwidth = calculateBandwidth(ind30m);
        if (bandwidth.compareTo(BigDecimal.valueOf(0.03)) > 0 && 
            bandwidth.compareTo(BigDecimal.valueOf(0.15)) < 0) {
            // 带宽适中
            score = score.add(BigDecimal.valueOf(50));
        }
        
        return score;
    }
    
    // 量价确认评估
    private BigDecimal evaluateVolumePrice(MarketData data30m, MarketData data120m) {
        BigDecimal volumeRatio = data30m.getIndicators().getVolumeRatio();
        BigDecimal priceChange = data30m.getChangePercent();
        
        BigDecimal score = BigDecimal.ZERO;
        
        // 成交量评分（50分）
        if (volumeRatio.compareTo(BigDecimal.valueOf(1.5)) >= 0) {
            score = score.add(BigDecimal.valueOf(30)); // 成交量放大
            
            if (volumeRatio.compareTo(BigDecimal.valueOf(2.0)) >= 0) {
                score = score.add(BigDecimal.valueOf(20)); // 显著放量
            }
        }
        
        // 量价配合度（50分）
        if (priceChange.compareTo(BigDecimal.ZERO) > 0 && 
            volumeRatio.compareTo(BigDecimal.valueOf(1.2)) > 0) {
            // 价涨量增
            score = score.add(BigDecimal.valueOf(50));
        } else if (priceChange.compareTo(BigDecimal.ZERO) < 0 && 
                   volumeRatio.compareTo(BigDecimal.valueOf(0.8)) < 0) {
            // 价跌量缩（可能见底）
            score = score.add(BigDecimal.valueOf(30));
        }
        
        return score;
    }
    
    // 多周期共振验证
    private boolean validateMultiTimeframe(TradingSignal signal, 
                                          MarketData data30m, 
                                          MarketData data120m) {
        // MACD方向一致性
        boolean macdAligned = data30m.getIndicators().getMacdLine()
            .compareTo(data30m.getIndicators().getSignalLine()) ==
            data120m.getIndicators().getMacdLine()
            .compareTo(data120m.getIndicators().getSignalLine());
        
        // BOLL位置一致性
        boolean bollAligned = !isBollConflicting(data30m, data120m);
        
        // 趋势方向一致性
        boolean trendAligned = data30m.getIndicators().getEma20()
            .compareTo(data30m.getIndicators().getEma50()) ==
            data120m.getIndicators().getEma20()
            .compareTo(data120m.getIndicators().getEma50());
        
        return macdAligned && bollAligned && trendAligned;
    }
}
```

### 4.3 交易执行服务

```java
@Service
@Transactional
@Slf4j
public class TradingService {
    
    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final RiskService riskService;
    private final FutuTradingClient futuClient;
    private final ApplicationEventPublisher eventPublisher;
    
    // 执行交易信号
    public Order executeSignal(TradingSignal signal) {
        // 1. 风险检查
        RiskValidation validation = riskService.validate(signal);
        if (!validation.isPassed()) {
            log.warn("风险检查未通过: {}", validation.getReason());
            return null;
        }
        
        // 2. 创建订单
        Order order = createOrder(signal);
        
        // 3. 提交到券商
        try {
            String brokerOrderId = futuClient.placeOrder(
                order.getSymbol(),
                order.getSide().toString(),
                order.getQuantity(),
                order.getPrice()
            );
            
            order.setBrokerOrderId(brokerOrderId);
            order.setStatus(OrderStatus.SUBMITTED);
            
        } catch (Exception e) {
            log.error("订单提交失败", e);
            order.setStatus(OrderStatus.REJECTED);
            order.setRemark(e.getMessage());
        }
        
        // 4. 保存订单
        orderRepository.save(order);
        
        // 5. 发送订单事件
        eventPublisher.publishEvent(
            new OrderEvent(order)
        );
        
        return order;
    }
    
    // 更新持仓
    @Transactional
    public void updatePosition(Order order) {
        if (order.getStatus() != OrderStatus.FILLED) {
            return;
        }
        
        Position position = positionRepository
            .findById(order.getSymbol())
            .orElse(new Position(order.getSymbol()));
        
        if (order.getSide() == OrderSide.BUY) {
            position.addPosition(
                order.getExecutedQuantity(), 
                order.getExecutedPrice()
            );
        } else {
            position.reducePosition(
                order.getExecutedQuantity(), 
                order.getExecutedPrice()
            );
        }
        
        positionRepository.save(position);
    }
    
    // 处理订单回报
    public void handleOrderUpdate(OrderUpdate update) {
        Order order = orderRepository
            .findById(update.getOrderId())
            .orElseThrow();
        
        order.setStatus(update.getStatus());
        order.setExecutedQuantity(update.getExecutedQty());
        order.setExecutedPrice(update.getExecutedPrice());
        order.setUpdateTime(LocalDateTime.now());
        
        orderRepository.save(order);
        
        // 更新持仓
        if (update.getStatus() == OrderStatus.FILLED) {
            updatePosition(order);
        }
    }
}
```

### 4.4 港股风险管理与动态仓位系统

```java
@Service
@Slf4j
public class HKStockRiskService {
    
    // 风险参数配置
    @Value("${risk.total-capital:100000}")
    private BigDecimal totalCapital; // 总资金10万港币
    
    @Value("${risk.max-single-position:50000}")
    private BigDecimal maxSinglePosition; // 单笔上限5万港币
    
    @Value("${risk.max-loss-per-trade:0.03}")
    private BigDecimal maxLossPerTrade; // 单笔最大亏损3%
    
    @Value("${risk.max-daily-loss:0.05}")
    private BigDecimal maxDailyLoss; // 日内最大亏损5%
    
    @Value("${risk.max-drawdown:0.15}")
    private BigDecimal maxDrawdown; // 最大回撤15%
    
    @Value("${risk.consecutive-loss-limit:3}")
    private Integer consecutiveLossLimit; // 连续亏损限制
    
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final TradeStatisticsService statisticsService;
    
    // 综合风险验证
    public RiskValidation validate(TradingSignal signal) {
        List<String> violations = new ArrayList<>();
        
        // 1. 检查资金使用率
        if (!checkCapitalUsage(signal)) {
            violations.add("资金使用超限");
        }
        
        // 2. 检查单笔风险
        if (!checkSingleTradeRisk(signal)) {
            violations.add("单笔风险过高");
        }
        
        // 3. 检查日内损失
        BigDecimal todayLoss = calculateTodayLoss();
        if (todayLoss.abs().compareTo(totalCapital.multiply(maxDailyLoss)) > 0) {
            violations.add(String.format("日内亏损已达%.2f%%", 
                todayLoss.abs().divide(totalCapital, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))));
        }
        
        // 4. 检查连续亏损
        Integer consecutiveLosses = getConsecutiveLosses();
        if (consecutiveLosses >= consecutiveLossLimit) {
            violations.add(String.format("连续亏损%d笔，暂停交易", consecutiveLosses));
        }
        
        // 5. 检查最大回撤
        BigDecimal currentDrawdown = calculateCurrentDrawdown();
        if (currentDrawdown.compareTo(maxDrawdown) > 0) {
            violations.add(String.format("当前回撤%.2f%%，超过限制", 
                currentDrawdown.multiply(BigDecimal.valueOf(100))));
        }
        
        return new RiskValidation(
            violations.isEmpty(), 
            String.join("; ", violations)
        );
    }
    
    // 动态仓位计算系统
    public PositionSize calculateDynamicPosition(
            TradingSignal signal, 
            MarketData marketData) {
        
        // 1. 基础仓位（单笔上限5万）
        BigDecimal basePosition = maxSinglePosition;
        
        // 2. 计算动态调整因子
        BigDecimal atrFactor = calculateATRFactor(marketData);
        BigDecimal signalFactor = calculateSignalStrengthFactor(signal);
        BigDecimal winRateFactor = calculateWinRateFactor();
        
        // 3. 综合仓位公式
        // position = base * (2 - ATR_ratio) * signal_strength * (0.5 + win_rate * 0.5)
        BigDecimal adjustedPosition = basePosition
            .multiply(BigDecimal.valueOf(2).subtract(atrFactor))
            .multiply(signalFactor)
            .multiply(BigDecimal.valueOf(0.5).add(winRateFactor.multiply(BigDecimal.valueOf(0.5))));
        
        // 4. 应用仓位限制（2万-5万）
        BigDecimal finalPosition = adjustedPosition;
        if (finalPosition.compareTo(BigDecimal.valueOf(20000)) < 0) {
            finalPosition = BigDecimal.valueOf(20000);
        } else if (finalPosition.compareTo(maxSinglePosition) > 0) {
            finalPosition = maxSinglePosition;
        }
        
        // 5. 计算股数（港股100股为一手）
        BigDecimal price = signal.getSuggestedPrice();
        Integer shares = finalPosition.divide(price, 0, RoundingMode.DOWN)
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
            .multiply(BigDecimal.valueOf(100))
            .intValue();
        
        // 6. 计算止损止盈价格
        StopLossPrice stopLoss = calculateStopLoss(signal, marketData);
        TakeProfitPrice takeProfit = calculateTakeProfit(signal, marketData);
        
        return PositionSize.builder()
            .shares(shares)
            .positionValue(price.multiply(BigDecimal.valueOf(shares)))
            .entryPrice(price)
            .stopLossPrice(stopLoss.getPrice())
            .stopLossPercent(stopLoss.getPercent())
            .takeProfitPrice(takeProfit.getPrice())
            .takeProfitPercent(takeProfit.getPercent())
            .riskAmount(calculateRiskAmount(shares, price, stopLoss.getPrice()))
            .build();
    }
    
    // ATR因子计算（波动率调整）
    private BigDecimal calculateATRFactor(MarketData data) {
        BigDecimal currentATR = data.getIndicators().getAtr();
        BigDecimal avgATR20 = data.getIndicators().getAtr20day();
        
        if (avgATR20.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ONE;
        }
        
        // ATR_ratio = current_ATR / 20day_avg_ATR
        return currentATR.divide(avgATR20, 4, RoundingMode.HALF_UP);
    }
    
    // 信号强度因子（0-1）
    private BigDecimal calculateSignalStrengthFactor(TradingSignal signal) {
        BigDecimal strength = signal.getStrength();
        // 将0-100的强度转换为0-1的因子
        return strength.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
    
    // 胜率因子（基于最近30天）
    private BigDecimal calculateWinRateFactor() {
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        List<Trade> recentTrades = orderRepository.findCompletedTradesSince(thirtyDaysAgo);
        
        if (recentTrades.isEmpty()) {
            return BigDecimal.valueOf(0.5); // 默认50%胜率
        }
        
        long wins = recentTrades.stream()
            .filter(t -> t.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
            .count();
        
        return BigDecimal.valueOf(wins)
            .divide(BigDecimal.valueOf(recentTrades.size()), 2, RoundingMode.HALF_UP);
    }
    
    // 止损价格计算（支持固定止损和ATR止损）
    public StopLossPrice calculateStopLoss(TradingSignal signal, MarketData data) {
        BigDecimal entryPrice = signal.getSuggestedPrice();
        
        // 1. 固定止损：3%
        BigDecimal fixedStopLoss = entryPrice.multiply(BigDecimal.valueOf(0.97));
        
        // 2. ATR止损：1.5倍ATR
        BigDecimal atr = data.getIndicators().getAtr();
        BigDecimal atrStopLoss = entryPrice.subtract(atr.multiply(BigDecimal.valueOf(1.5)));
        
        // 3. 选择更保守的止损
        BigDecimal stopLossPrice = fixedStopLoss.max(atrStopLoss);
        BigDecimal stopLossPercent = entryPrice.subtract(stopLossPrice)
            .divide(entryPrice, 4, RoundingMode.HALF_UP);
        
        return new StopLossPrice(stopLossPrice, stopLossPercent);
    }
    
    // 止盈价格计算（根据策略类型）
    public TakeProfitPrice calculateTakeProfit(TradingSignal signal, MarketData data) {
        BigDecimal entryPrice = signal.getSuggestedPrice();
        String strategyType = signal.getStrategyName();
        
        BigDecimal takeProfitPrice;
        BigDecimal takeProfitPercent;
        
        switch (strategyType) {
            case "MACD_TREND":
                // 策略A：分批止盈 5%/8%/10%
                takeProfitPrice = entryPrice.multiply(BigDecimal.valueOf(1.08));
                takeProfitPercent = BigDecimal.valueOf(0.08);
                break;
                
            case "BOLL_REVERSION":
                // 策略B：触及BOLL上轨
                takeProfitPrice = data.getIndicators().getUpperBand();
                takeProfitPercent = takeProfitPrice.subtract(entryPrice)
                    .divide(entryPrice, 4, RoundingMode.HALF_UP);
                break;
                
            case "VOLUME_BREAKOUT":
                // 策略C：目标收益6%
                takeProfitPrice = entryPrice.multiply(BigDecimal.valueOf(1.06));
                takeProfitPercent = BigDecimal.valueOf(0.06);
                break;
                
            default:
                // 默认5%止盈
                takeProfitPrice = entryPrice.multiply(BigDecimal.valueOf(1.05));
                takeProfitPercent = BigDecimal.valueOf(0.05);
        }
        
        return new TakeProfitPrice(takeProfitPrice, takeProfitPercent);
    }
    
    // 追踪止损管理
    @Component
    public class TrailingStopManager {
        
        private final Map<String, TrailingStopInfo> trailingStops = new ConcurrentHashMap<>();
        
        public void updateTrailingStop(Position position, MarketData currentData) {
            String positionId = position.getId();
            BigDecimal currentPrice = currentData.getClose();
            BigDecimal entryPrice = position.getAvgCost();
            
            // 计算当前盈利
            BigDecimal profit = currentPrice.subtract(entryPrice)
                .divide(entryPrice, 4, RoundingMode.HALF_UP);
            
            // 盈利超过5%才启动追踪止损
            if (profit.compareTo(BigDecimal.valueOf(0.05)) > 0) {
                TrailingStopInfo info = trailingStops.computeIfAbsent(positionId,
                    k -> new TrailingStopInfo(currentPrice));
                
                // 更新最高价
                if (currentPrice.compareTo(info.getHighWaterMark()) > 0) {
                    info.setHighWaterMark(currentPrice);
                }
                
                // 计算追踪止损价（高点回撤3%）
                BigDecimal trailingStopPrice = info.getHighWaterMark()
                    .multiply(BigDecimal.valueOf(0.97));
                
                // 检查是否触发止损
                if (currentPrice.compareTo(trailingStopPrice) <= 0) {
                    log.info("追踪止损触发: {} 当前价{} 止损价{}", 
                        position.getSymbol(), currentPrice, trailingStopPrice);
                    // 触发卖出信号
                    triggerStopLoss(position, trailingStopPrice);
                }
            }
        }
    }
}
```