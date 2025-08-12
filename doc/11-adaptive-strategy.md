# 策略自适应切换系统

## 11.1 市场状态识别与策略选择

```java
@Service
@Slf4j
public class AdaptiveStrategySelector {
    
    private final MarketStateAnalyzer marketAnalyzer;
    private final Map<MarketState, TradingStrategy> strategyMap;
    private final StrategyPerformanceTracker performanceTracker;
    
    @PostConstruct
    public void init() {
        // 注册三大策略
        strategyMap.put(MarketState.TRENDING, new MACDTrendStrategy());
        strategyMap.put(MarketState.RANGING, new BOLLReversionStrategy());
        strategyMap.put(MarketState.BREAKOUT, new VolumeBreakoutStrategy());
    }
    
    // 自适应策略选择
    public TradingStrategy selectOptimalStrategy(
            MarketData data30m, 
            MarketData data120m) {
        
        // 1. 识别市场状态
        MarketState currentState = marketAnalyzer.analyze(data30m, data120m);
        
        // 2. 获取对应策略
        TradingStrategy strategy = strategyMap.get(currentState);
        
        // 3. 检查策略近期表现
        StrategyPerformance performance = performanceTracker
            .getRecentPerformance(strategy.getName());
        
        // 4. 如果策略表现不佳，切换到保守模式
        if (performance.getWinRate() < 0.3 || 
            performance.getConsecutiveLosses() > 2) {
            log.info("策略{}表现不佳，切换到观望模式", strategy.getName());
            return new NeutralStrategy(); // 观望策略
        }
        
        log.info("选择策略: {} for market state: {}", 
            strategy.getName(), currentState);
        
        return strategy;
    }
}

// 市场状态分析器
@Component
public class MarketStateAnalyzer {
    
    public MarketState analyze(MarketData data30m, MarketData data120m) {
        TechnicalIndicators ind30m = data30m.getIndicators();
        TechnicalIndicators ind120m = data120m.getIndicators();
        
        // 计算关键指标
        BigDecimal atr = ind30m.getAtr();
        BigDecimal price = data30m.getClose();
        BigDecimal atrRatio = atr.divide(price, 4, RoundingMode.HALF_UP);
        
        // BOLL带宽
        BigDecimal bandwidth = calculateBandwidth(ind30m);
        
        // ADX趋势强度
        BigDecimal adx = ind120m.getAdx();
        
        // 成交量比率
        BigDecimal volumeRatio = ind30m.getVolumeRatio();
        
        // 判断市场状态
        if (adx.compareTo(BigDecimal.valueOf(25)) > 0 && 
            bandwidth.compareTo(BigDecimal.valueOf(0.1)) > 0) {
            return MarketState.TRENDING; // 趋势市
            
        } else if (adx.compareTo(BigDecimal.valueOf(20)) < 0 && 
                   bandwidth.compareTo(BigDecimal.valueOf(0.05)) < 0) {
            return MarketState.RANGING; // 震荡市
            
        } else if (volumeRatio.compareTo(BigDecimal.valueOf(2.0)) > 0 && 
                   isPriceBreakout(data30m)) {
            return MarketState.BREAKOUT; // 突破市
            
        } else {
            return MarketState.NEUTRAL; // 中性/观望
        }
    }
    
    private BigDecimal calculateBandwidth(TechnicalIndicators indicators) {
        BigDecimal upper = indicators.getUpperBand();
        BigDecimal lower = indicators.getLowerBand();
        BigDecimal middle = indicators.getMiddleBand();
        
        if (middle.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        
        return upper.subtract(lower).divide(middle, 4, RoundingMode.HALF_UP);
    }
    
    private boolean isPriceBreakout(MarketData data) {
        BigDecimal price = data.getClose();
        BigDecimal upper = data.getIndicators().getUpperBand();
        BigDecimal lower = data.getIndicators().getLowerBand();
        
        // 价格突破上轨或下轨
        return price.compareTo(upper) > 0 || price.compareTo(lower) < 0;
    }
}
```

## 11.2 三大策略具体实现

### 11.2.1 策略A：MACD趋势跟踪策略

```java
@Component
public class MACDTrendStrategy implements TradingStrategy {
    
    @Override
    public String getName() {
        return "MACD_TREND";
    }
    
    @Override
    public Optional<TradingSignal> generateSignal(
            MarketData data30m, 
            MarketData data120m) {
        
        TechnicalIndicators ind30m = data30m.getIndicators();
        TechnicalIndicators ind120m = data120m.getIndicators();
        
        // 入场条件检查
        boolean condition1 = checkMACDGoldenCross(ind30m); // MACD金叉
        boolean condition2 = ind30m.getMacdLine().compareTo(BigDecimal.ZERO) > 0; // DIF>0
        boolean condition3 = checkMACDAlignment(ind30m, ind120m); // 大小周期同向
        boolean condition4 = checkVolumeConfirmation(data30m); // 成交量放大
        boolean condition5 = checkBOLLPosition(data30m); // 价格突破中轨
        
        if (condition1 && condition2 && condition3 && condition4 && condition5) {
            return Optional.of(createBuySignal(data30m, SignalStrength.STRONG));
        }
        
        // 出场条件检查
        if (hasOpenPosition() && checkExitConditions(data30m)) {
            return Optional.of(createSellSignal(data30m, SignalStrength.MEDIUM));
        }
        
        return Optional.empty();
    }
    
    private boolean checkMACDGoldenCross(TechnicalIndicators indicators) {
        BigDecimal macdLine = indicators.getMacdLine();
        BigDecimal signalLine = indicators.getSignalLine();
        BigDecimal prevMacdLine = indicators.getPrevMacdLine();
        BigDecimal prevSignalLine = indicators.getPrevSignalLine();
        
        // 当前MACD线在信号线上方，之前在下方 = 金叉
        return macdLine.compareTo(signalLine) > 0 && 
               prevMacdLine.compareTo(prevSignalLine) <= 0;
    }
    
    private boolean checkMACDAlignment(TechnicalIndicators ind30m, 
                                      TechnicalIndicators ind120m) {
        // 30分钟和120分钟MACD方向一致
        boolean direction30m = ind30m.getMacdLine()
            .compareTo(ind30m.getSignalLine()) > 0;
        boolean direction120m = ind120m.getMacdLine()
            .compareTo(ind120m.getSignalLine()) > 0;
        
        return direction30m == direction120m;
    }
    
    private boolean checkVolumeConfirmation(MarketData data) {
        BigDecimal volumeRatio = data.getIndicators().getVolumeRatio();
        return volumeRatio.compareTo(BigDecimal.valueOf(1.5)) >= 0;
    }
    
    private boolean checkBOLLPosition(MarketData data) {
        BigDecimal price = data.getClose();
        BigDecimal middle = data.getIndicators().getMiddleBand();
        
        // 价格在中轨上方
        return price.compareTo(middle) > 0;
    }
    
    @Override
    public StopLossStrategy getStopLossStrategy() {
        // 使用ATR追踪止损
        return new ATRTrailingStopLoss(1.5, 0.03); // 1.5倍ATR或3%追踪
    }
    
    @Override
    public TakeProfitStrategy getTakeProfitStrategy() {
        // 分批止盈：5%/8%/10%
        return new TieredTakeProfit(
            Arrays.asList(0.05, 0.08, 0.10),
            Arrays.asList(0.3, 0.4, 0.3) // 仓位比例
        );
    }
}
```

### 11.2.2 策略B：BOLL均值回归策略

```java
@Component
public class BOLLReversionStrategy implements TradingStrategy {
    
    @Override
    public String getName() {
        return "BOLL_REVERSION";
    }
    
    @Override
    public Optional<TradingSignal> generateSignal(
            MarketData data30m, 
            MarketData data120m) {
        
        TechnicalIndicators ind30m = data30m.getIndicators();
        BigDecimal price = data30m.getClose();
        
        // 买入条件：价格触及下轨反弹
        if (checkBuyConditions(data30m, data120m)) {
            // 检查是否有反转K线形态
            if (hasReversalPattern(data30m)) {
                return Optional.of(createBuySignal(data30m, SignalStrength.MEDIUM));
            }
        }
        
        // 卖出条件：价格触及上轨或达到止盈目标
        if (hasOpenPosition() && checkSellConditions(data30m)) {
            return Optional.of(createSellSignal(data30m, SignalStrength.MEDIUM));
        }
        
        return Optional.empty();
    }
    
    private boolean checkBuyConditions(MarketData data30m, MarketData data120m) {
        TechnicalIndicators ind30m = data30m.getIndicators();
        BigDecimal price = data30m.getClose();
        BigDecimal lower = ind30m.getLowerBand();
        
        // 条件1：价格接近或触及下轨
        boolean nearLowerBand = price.subtract(lower)
            .divide(lower, 4, RoundingMode.HALF_UP)
            .compareTo(BigDecimal.valueOf(0.01)) < 0;
        
        // 条件2：RSI超卖
        boolean oversold = ind30m.getRsi().compareTo(BigDecimal.valueOf(30)) < 0;
        
        // 条件3：MACD柱状图缩短（动能减弱）
        boolean macdWeakening = ind30m.getHistogram().abs()
            .compareTo(ind30m.getPrevHistogram().abs()) < 0;
        
        // 条件4：120分钟未破前低（大级别支撑）
        boolean support120m = !isBreakingSupport(data120m);
        
        return nearLowerBand && oversold && macdWeakening && support120m;
    }
    
    private boolean hasReversalPattern(MarketData data) {
        // 检查K线反转形态：锤子线、十字星等
        BigDecimal open = data.getOpen();
        BigDecimal high = data.getHigh();
        BigDecimal low = data.getLow();
        BigDecimal close = data.getClose();
        
        BigDecimal body = close.subtract(open).abs();
        BigDecimal upperShadow = high.subtract(close.max(open));
        BigDecimal lowerShadow = open.min(close).subtract(low);
        BigDecimal range = high.subtract(low);
        
        // 锤子线：下影线长，实体小
        boolean isHammer = lowerShadow.compareTo(body.multiply(BigDecimal.valueOf(2))) > 0 &&
                          upperShadow.compareTo(body.multiply(BigDecimal.valueOf(0.5))) < 0;
        
        // 十字星：实体极小
        boolean isDoji = body.compareTo(range.multiply(BigDecimal.valueOf(0.1))) < 0;
        
        return isHammer || isDoji;
    }
    
    private boolean checkSellConditions(MarketData data) {
        BigDecimal price = data.getClose();
        BigDecimal upper = data.getIndicators().getUpperBand();
        
        // 价格触及上轨
        boolean touchUpperBand = price.compareTo(upper) >= 0;
        
        // RSI超买
        boolean overbought = data.getIndicators().getRsi()
            .compareTo(BigDecimal.valueOf(70)) > 0;
        
        return touchUpperBand || overbought;
    }
    
    @Override
    public StopLossStrategy getStopLossStrategy() {
        // 固定止损2%
        return new FixedStopLoss(0.02);
    }
    
    @Override
    public TakeProfitStrategy getTakeProfitStrategy() {
        // 触及BOLL上轨止盈
        return new BOLLBandTakeProfit(BOLLBand.UPPER);
    }
}
```

### 11.2.3 策略C：量价突破策略

```java
@Component
public class VolumeBreakoutStrategy implements TradingStrategy {
    
    @Override
    public String getName() {
        return "VOLUME_BREAKOUT";
    }
    
    @Override
    public Optional<TradingSignal> generateSignal(
            MarketData data30m, 
            MarketData data120m) {
        
        // 检查量价突破条件
        if (checkBreakoutConditions(data30m, data120m)) {
            BigDecimal signalStrength = calculateBreakoutStrength(data30m);
            
            if (signalStrength.compareTo(BigDecimal.valueOf(70)) > 0) {
                return Optional.of(createBuySignal(data30m, 
                    SignalStrength.fromValue(signalStrength)));
            }
        }
        
        // 检查出场条件
        if (hasOpenPosition() && checkExitConditions(data30m)) {
            return Optional.of(createSellSignal(data30m, SignalStrength.MEDIUM));
        }
        
        return Optional.empty();
    }
    
    private boolean checkBreakoutConditions(MarketData data30m, MarketData data120m) {
        TechnicalIndicators ind30m = data30m.getIndicators();
        BigDecimal price = data30m.getClose();
        
        // 条件1：成交量突破20日均量2倍
        boolean volumeBreakout = ind30m.getVolumeRatio()
            .compareTo(BigDecimal.valueOf(2.0)) > 0;
        
        // 条件2：MACD柱状图由负转正
        boolean macdTurning = ind30m.getHistogram().compareTo(BigDecimal.ZERO) > 0 &&
                             ind30m.getPrevHistogram().compareTo(BigDecimal.ZERO) <= 0;
        
        // 条件3：价格突破关键阻力位
        boolean priceBreakout = isPriceBreakingResistance(data30m);
        
        // 条件4：120分钟确认突破有效
        boolean confirmedBreakout = isBreakoutConfirmed(data120m);
        
        return volumeBreakout && macdTurning && priceBreakout && confirmedBreakout;
    }
    
    private boolean isPriceBreakingResistance(MarketData data) {
        BigDecimal price = data.getClose();
        BigDecimal upper = data.getIndicators().getUpperBand();
        
        // 突破BOLL上轨
        boolean breakUpperBand = price.compareTo(upper) > 0;
        
        // 创20日新高
        boolean new20DayHigh = price.compareTo(data.getHigh20Day()) > 0;
        
        return breakUpperBand || new20DayHigh;
    }
    
    private boolean isBreakoutConfirmed(MarketData data120m) {
        // 120分钟级别形成新高
        BigDecimal price = data120m.getClose();
        BigDecimal prevHigh = data120m.getPrevHigh();
        
        return price.compareTo(prevHigh) > 0;
    }
    
    private BigDecimal calculateBreakoutStrength(MarketData data) {
        BigDecimal score = BigDecimal.ZERO;
        
        // 成交量强度（40分）
        BigDecimal volumeRatio = data.getIndicators().getVolumeRatio();
        if (volumeRatio.compareTo(BigDecimal.valueOf(3.0)) > 0) {
            score = score.add(BigDecimal.valueOf(40));
        } else if (volumeRatio.compareTo(BigDecimal.valueOf(2.0)) > 0) {
            score = score.add(BigDecimal.valueOf(30));
        } else {
            score = score.add(BigDecimal.valueOf(20));
        }
        
        // 价格强度（30分）
        BigDecimal priceChange = data.getChangePercent();
        if (priceChange.compareTo(BigDecimal.valueOf(0.03)) > 0) {
            score = score.add(BigDecimal.valueOf(30));
        } else if (priceChange.compareTo(BigDecimal.valueOf(0.02)) > 0) {
            score = score.add(BigDecimal.valueOf(20));
        }
        
        // MACD强度（30分）
        BigDecimal histogram = data.getIndicators().getHistogram();
        if (histogram.compareTo(BigDecimal.ZERO) > 0) {
            score = score.add(BigDecimal.valueOf(30));
        }
        
        return score;
    }
    
    private boolean checkExitConditions(MarketData data) {
        // 量能萎缩
        boolean volumeShrinking = data.getIndicators().getVolumeRatio()
            .compareTo(BigDecimal.valueOf(0.8)) < 0;
        
        // MACD死叉
        boolean macdDeadCross = checkMACDDeadCross(data.getIndicators());
        
        // 跌破突破位
        boolean breakdownSupport = isPriceBreakingSupport(data);
        
        return volumeShrinking || macdDeadCross || breakdownSupport;
    }
    
    @Override
    public StopLossStrategy getStopLossStrategy() {
        // 突破位下方4%止损
        return new BreakoutStopLoss(0.04);
    }
    
    @Override
    public TakeProfitStrategy getTakeProfitStrategy() {
        // 目标收益6%
        return new FixedTakeProfit(0.06);
    }
}
```

## 11.3 持仓周期管理系统

```java
@Service
public class PositionPeriodManager {
    
    @Value("${trading.position-period.intraday.max-hours:6}")
    private Integer intradayMaxHours;
    
    @Value("${trading.position-period.short-term.max-days:5}")
    private Integer shortTermMaxDays;
    
    @Value("${trading.position-period.medium-term.max-days:15}")
    private Integer mediumTermMaxDays;
    
    // 根据信号强度决定持仓周期
    public PositionPeriod determinePositionPeriod(TradingSignal signal) {
        BigDecimal strength = signal.getStrength();
        
        if (strength.compareTo(BigDecimal.valueOf(80)) >= 0) {
            // 强信号：中线持仓（5-15天）
            return PositionPeriod.MEDIUM_TERM;
        } else if (strength.compareTo(BigDecimal.valueOf(60)) >= 0) {
            // 中等信号：短线持仓（1-5天）
            return PositionPeriod.SHORT_TERM;
        } else {
            // 弱信号：日内交易
            return PositionPeriod.INTRADAY;
        }
    }
    
    // 强制平仓检查
    @Scheduled(cron = "0 */5 * * * *") // 每5分钟检查一次
    public void checkForceClose() {
        LocalTime now = LocalTime.now();
        
        // 检查所有持仓
        List<Position> positions = positionRepository.findAllActive();
        
        for (Position position : positions) {
            // 日内仓位检查
            if (position.getPeriod() == PositionPeriod.INTRADAY) {
                if (shouldForceCloseIntraday(position, now)) {
                    forceClosePosition(position, "日内强制平仓");
                }
            }
            
            // 短线仓位检查
            else if (position.getPeriod() == PositionPeriod.SHORT_TERM) {
                if (position.getDaysHeld() > shortTermMaxDays) {
                    evaluateAndClose(position, "短线期限到达");
                }
            }
            
            // 中线仓位检查
            else if (position.getPeriod() == PositionPeriod.MEDIUM_TERM) {
                if (position.getDaysHeld() >= mediumTermMaxDays) {
                    forceClosePosition(position, "中线期限到达");
                }
            }
        }
    }
    
    private boolean shouldForceCloseIntraday(Position position, LocalTime now) {
        // 15:55强制平仓
        if (now.isAfter(LocalTime.of(15, 55))) {
            return true;
        }
        
        // 持仓超过6小时
        long hoursHeld = Duration.between(position.getOpenTime(), 
            LocalDateTime.now()).toHours();
        
        return hoursHeld >= intradayMaxHours;
    }
    
    private void evaluateAndClose(Position position, String reason) {
        // 评估是否应该继续持有
        MarketData currentData = marketDataService.getRealTimeData(position.getSymbol());
        TechnicalIndicators indicators = currentData.getIndicators();
        
        // 如果趋势仍然有利，可以继续持有
        if (isTrendFavorable(position, indicators)) {
            log.info("持仓{}趋势有利，继续持有", position.getSymbol());
            return;
        }
        
        // 否则平仓
        forceClosePosition(position, reason);
    }
}
```