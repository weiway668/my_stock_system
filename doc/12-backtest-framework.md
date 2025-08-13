# 港股程序化交易系统 - 回测框架文档

## 12.1 回测框架概述

### 12.1.1 架构设计
回测框架采用分层设计，通过命令行接口驱动，专注于专业量化回测体验：

```
┌─────────────────────────────────────────┐
│     CLI Layer (BacktestCommand)         │
│  - 参数解析和验证                        │
│  - 中文界面和进度显示                    │
│  - 报告输出管理                          │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│   Backtest Engine (BacktestEngine)     │
│  - 异步回测执行                          │
│  - 持仓管理和交易执行                    │
│  - 风险指标计算                          │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│  Support Services                       │
│  - DataPreparationService              │
│  - HKStockCommissionCalculator          │
│  - BacktestReportGenerator              │
└─────────────────────────────────────────┘
```

### 12.1.2 核心特性
- **CLI优先**: 专业命令行界面，支持批处理和自动化
- **数据预热**: 100天预热期确保技术指标准确性
- **精确费用**: 基于2024年港股费用结构的准确计算
- **异步执行**: CompletableFuture支持非阻塞回测
- **多格式报告**: JSON/CSV/HTML/中文摘要等专业输出

## 12.2 核心组件实现

### 12.2.1 回测引擎 (BacktestEngine)

```java
@Service
@Slf4j
public class BacktestEngine {
    
    private final TradingSystemRepository repository;
    private final DataPreparationService dataPreparationService;
    private final HKStockCommissionCalculator commissionCalculator;
    
    /**
     * 异步执行回测
     * @param request 回测请求参数
     * @return CompletableFuture<BacktestResult> 异步回测结果
     */
    @Async
    public CompletableFuture<BacktestResult> runBacktest(BacktestRequest request) {
        log.info("开始回测: {} {} -> {}", request.getSymbol(), 
            request.getStartTime().toLocalDate(), request.getEndTime().toLocalDate());
        
        // 1. 数据准备阶段
        PreparedData preparedData = dataPreparationService.prepareBacktestData(
            request.getSymbol(), request.getStartTime(), request.getEndTime()
        );
        
        // 2. 初始化回测账户
        BacktestAccount account = new BacktestAccount(
            request.getInitialCapital(), 
            request.getSymbol()
        );
        
        // 3. 回测主循环
        executeBacktestLoop(request, preparedData, account);
        
        // 4. 计算最终结果
        BacktestResult result = calculateFinalResult(request, account, preparedData);
        
        return CompletableFuture.completedFuture(result);
    }
    
    /**
     * 执行回测主循环
     */
    private void executeBacktestLoop(BacktestRequest request, PreparedData data, BacktestAccount account) {
        TradingStrategy strategy = request.getStrategy();
        List<MarketData> marketData = data.getMarketData();
        
        for (int i = data.getWarmupEndIndex(); i < marketData.size(); i++) {
            MarketData current = marketData.get(i);
            
            // 生成交易信号
            Optional<TradingSignal> signal = strategy.generateSignal(current, marketData.subList(0, i + 1));
            
            if (signal.isPresent()) {
                executeTrade(account, signal.get(), current, request);
            }
            
            // 更新每日统计
            account.updateDailyStats(current);
        }
    }
    
    /**
     * 执行交易
     */
    private void executeTrade(BacktestAccount account, TradingSignal signal, 
                             MarketData marketData, BacktestRequest request) {
        
        if (signal.getAction() == TradingAction.BUY) {
            // 计算买入数量（港股100股为一手）
            int quantity = calculateBuyQuantity(account, marketData, request);
            if (quantity > 0) {
                // 计算费用
                HKStockCommissionCalculator.CommissionBreakdown breakdown = 
                    commissionCalculator.calculateCommission(marketData.getClose(), quantity, false);
                
                // 执行买入
                account.buy(marketData.getClose(), quantity, breakdown.getTotalFee(), marketData.getTimestamp());
                
                log.debug("买入: {} 股 @ {}, 费用: {}", quantity, marketData.getClose(), breakdown.getTotalFee());
            }
            
        } else if (signal.getAction() == TradingAction.SELL && account.hasPosition()) {
            Position position = account.getPosition();
            
            // 计算卖出费用（包含印花税）
            HKStockCommissionCalculator.CommissionBreakdown breakdown = 
                commissionCalculator.calculateCommission(marketData.getClose(), position.getQuantity(), true);
            
            // 执行卖出
            BigDecimal pnl = account.sell(marketData.getClose(), breakdown.getTotalFee(), marketData.getTimestamp());
            
            log.debug("卖出: {} 股 @ {}, 盈亏: {}, 费用: {}", 
                position.getQuantity(), marketData.getClose(), pnl, breakdown.getTotalFee());
        }
    }
}
```

### 12.2.2 回测请求对象 (BacktestRequest)

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BacktestRequest {
    
    private TradingStrategy strategy;
    private String strategyName;
    private String symbol;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal initialCapital;
    private String timeframe;
    
    // 港股费用结构 (2024年标准)
    private BigDecimal commissionRate = new BigDecimal("0.00025");     // 0.025% 佣金
    private BigDecimal stampDutyRate = new BigDecimal("0.0013");       // 0.13% 印花税 (仅卖出)
    private BigDecimal tradingFeeRate = new BigDecimal("0.00005");     // 0.005% 交易费
    private BigDecimal settlementFeeRate = new BigDecimal("0.00002");  // 0.002% 结算费
    private BigDecimal slippageRate = new BigDecimal("0.0001");        // 0.01% 滑点
    
    // 输出配置
    private String outputPath;
    private boolean generateDetailedReport = true;
    private boolean generateHtmlReport = true;
    
    /**
     * 创建港股标准回测请求
     */
    public static BacktestRequest createHKStockRequest(String symbol, 
                                                      LocalDateTime startTime, 
                                                      LocalDateTime endTime,
                                                      BigDecimal initialCapital) {
        return BacktestRequest.builder()
            .symbol(symbol)
            .startTime(startTime)
            .endTime(endTime)
            .initialCapital(initialCapital)
            .timeframe("30m")
            .build();
    }
    
    /**
     * 验证请求参数
     */
    public void validate() {
        if (strategy == null) {
            throw new IllegalArgumentException("策略不能为空");
        }
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("交易标的不能为空");
        }
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("时间范围不能为空");
        }
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
        if (initialCapital == null || initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("初始资金必须大于0");
        }
    }
}
```

### 12.2.3 回测结果对象 (BacktestResult)

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BacktestResult {
    
    // 基本信息
    private boolean successful = true;
    private String error;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long backtestDays;
    private long executionTimeMs;
    private LocalDateTime reportGeneratedAt;
    
    // 资金情况
    private BigDecimal initialCapital;
    private BigDecimal finalEquity;
    private BigDecimal totalReturn;
    private BigDecimal returnRate;
    private BigDecimal annualizedReturn;
    
    // 风险指标
    private BigDecimal maxDrawdown;
    private BigDecimal sharpeRatio;
    private BigDecimal sortinoRatio;
    private BigDecimal calmarRatio;
    
    // 交易统计
    private int totalTrades;
    private int winningTrades;
    private int losingTrades;
    private BigDecimal winRate;
    private BigDecimal avgWin;
    private BigDecimal avgLoss;
    private BigDecimal profitFactor;
    
    // 成本分析
    private BigDecimal totalCommission;
    private BigDecimal totalStampDuty;
    private BigDecimal totalTradingFee;
    private BigDecimal totalSettlementFee;
    private BigDecimal totalSlippage;
    private BigDecimal totalCosts;
    
    // 历史数据
    private List<Trade> trades = new ArrayList<>();
    private List<DailyStats> dailyStats = new ArrayList<>();
    
    /**
     * 生成中文摘要
     */
    public String getChineseSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== 港股程序化交易回测报告 ===\n\n");
        
        summary.append("【回测概况】\n");
        summary.append(String.format("回测期间：%s 至 %s (%d天)\n", 
            startTime.toLocalDate(), endTime.toLocalDate(), backtestDays));
        summary.append(String.format("初始资金：¥%,.2f\n", initialCapital));
        summary.append(String.format("最终权益：¥%,.2f\n", finalEquity));
        summary.append(String.format("执行时间：%.1f秒\n\n", executionTimeMs / 1000.0));
        
        summary.append("【收益分析】\n");
        summary.append(String.format("绝对收益：¥%,.2f\n", totalReturn));
        summary.append(String.format("总收益率：%.2f%%\n", returnRate));
        summary.append(String.format("年化收益率：%.2f%%\n", annualizedReturn));
        summary.append(String.format("最大回撤：%.2f%%\n\n", maxDrawdown));
        
        summary.append("【风险指标】\n");
        summary.append(String.format("夏普比率：%.2f\n", sharpeRatio));
        if (sortinoRatio != null) {
            summary.append(String.format("索提诺比率：%.2f\n", sortinoRatio));
        }
        if (calmarRatio != null) {
            summary.append(String.format("卡尔马比率：%.2f\n", calmarRatio));
        }
        summary.append("\n");
        
        summary.append("【交易统计】\n");
        summary.append(String.format("总交易次数：%d\n", totalTrades));
        summary.append(String.format("盈利交易：%d (%.1f%%)\n", winningTrades, winRate));
        summary.append(String.format("亏损交易：%d (%.1f%%)\n", losingTrades, 100 - winRate));
        summary.append(String.format("平均盈利：¥%.2f\n", avgWin));
        summary.append(String.format("平均亏损：¥%.2f\n", avgLoss));
        summary.append(String.format("盈亏比：%.2f\n\n", profitFactor));
        
        summary.append("【成本分析】\n");
        summary.append(String.format("佣金费用：¥%.2f\n", totalCommission));
        summary.append(String.format("印花税：¥%.2f\n", totalStampDuty));
        summary.append(String.format("交易费：¥%.2f\n", totalTradingFee));
        summary.append(String.format("结算费：¥%.2f\n", totalSettlementFee));
        summary.append(String.format("滑点成本：¥%.2f\n", totalSlippage));
        summary.append(String.format("总成本：¥%.2f\n\n", totalCosts));
        
        summary.append("【目标达成分析】\n");
        boolean annualReturnTarget = annualizedReturn.compareTo(new BigDecimal("15")) >= 0 &&
                                   annualizedReturn.compareTo(new BigDecimal("20")) <= 0;
        boolean maxDrawdownTarget = maxDrawdown.compareTo(new BigDecimal("15")) < 0;
        
        summary.append(String.format("年化收益目标(15-20%%)：%s\n", 
            annualReturnTarget ? "✅ 达成" : "❌ 未达成"));
        summary.append(String.format("最大回撤目标(<15%%)：%s\n", 
            maxDrawdownTarget ? "✅ 达成" : "❌ 超出"));
        
        String overallRating = annualReturnTarget && maxDrawdownTarget ? "优秀" :
                              annualReturnTarget || maxDrawdownTarget ? "良好" : "需要改进";
        summary.append(String.format("综合评价：%s\n", overallRating));
        
        return summary.toString();
    }
}
```

## 12.3 数据预热机制

### 12.3.1 数据预备服务 (DataPreparationService)

```java
@Service
@Slf4j
public class DataPreparationService {
    
    private static final int WARMUP_DAYS = 100; // 预热天数
    private static final int MIN_REQUIRED_DATA_POINTS = 60; // 最少数据点
    
    private final TradingSystemRepository repository;
    
    /**
     * 为回测准备数据，包括预热数据
     */
    public PreparedData prepareBacktestData(String symbol, LocalDateTime startTime, LocalDateTime endTime) {
        log.info("准备回测数据: {} {} -> {}", symbol, startTime.toLocalDate(), endTime.toLocalDate());
        
        // 计算预热开始时间（向前推100个交易日）
        LocalDateTime warmupStartTime = calculateWarmupStartTime(startTime);
        
        // 加载包含预热期的历史数据
        List<MarketData> allData = loadHistoricalData(symbol, warmupStartTime, endTime);
        
        // 验证数据质量
        validateDataQuality(allData, symbol, startTime, endTime);
        
        // 计算预热结束位置
        int warmupEndIndex = findWarmupEndIndex(allData, startTime);
        
        // 预计算技术指标（在预热期）
        precomputeIndicators(allData, warmupEndIndex);
        
        return PreparedData.builder()
            .symbol(symbol)
            .marketData(allData)
            .warmupStartTime(warmupStartTime)
            .backtestStartTime(startTime)
            .backtestEndTime(endTime)
            .warmupEndIndex(warmupEndIndex)
            .build();
    }
    
    /**
     * 验证数据质量
     */
    private void validateDataQuality(List<MarketData> data, String symbol, 
                                   LocalDateTime startTime, LocalDateTime endTime) {
        if (data.isEmpty()) {
            throw new IllegalStateException("无法获取" + symbol + "的历史数据");
        }
        
        // 检查数据量是否足够
        if (data.size() < MIN_REQUIRED_DATA_POINTS) {
            throw new IllegalStateException(
                String.format("数据量不足，需要至少%d个数据点，实际只有%d个", 
                    MIN_REQUIRED_DATA_POINTS, data.size()));
        }
        
        // 检查数据完整性
        long missingDataPoints = 0;
        for (int i = 1; i < data.size(); i++) {
            MarketData prev = data.get(i - 1);
            MarketData curr = data.get(i);
            
            // 检查价格异常
            if (curr.getClose().compareTo(BigDecimal.ZERO) <= 0 ||
                curr.getVolume().compareTo(BigDecimal.ZERO) < 0) {
                log.warn("发现异常数据点: {} 价格={} 成交量={}", 
                    curr.getTimestamp(), curr.getClose(), curr.getVolume());
            }
        }
        
        log.info("数据质量检查完成: {} 共{}个数据点", symbol, data.size());
    }
    
    /**
     * 预计算技术指标
     */
    private void precomputeIndicators(List<MarketData> data, int warmupEndIndex) {
        log.info("开始预计算技术指标，预热数据点: {}", warmupEndIndex);
        
        // 在预热期计算MACD指标
        for (int i = 26; i <= warmupEndIndex; i++) { // MACD需要26个数据点
            List<MarketData> window = data.subList(0, i + 1);
            // 计算并缓存MACD值...
        }
        
        log.info("技术指标预计算完成");
    }
}
```

## 12.4 专业报告生成

### 12.4.1 目录结构设计

回测报告按照Python版本的约定，生成标准化目录结构：

```
output/
└── hk_macd_v1_02800_20250812_143022/
    ├── summary.json                 # 核心指标摘要
    ├── trades.csv                   # 详细交易记录
    ├── equity_curve.csv             # 权益曲线数据
    ├── performance_metrics.json     # 完整性能分析
    ├── backtest_report.html         # 可视化HTML报告
    └── chinese_summary.txt          # 中文分析摘要
```

### 12.4.2 报告生成器 (BacktestReportGenerator)

```java
@Service
@Slf4j
public class BacktestReportGenerator {
    
    /**
     * 生成完整报告包
     */
    public ReportGenerationResult generateReportPackage(BacktestRequest request, BacktestResult result) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 创建输出目录
            String outputDirectory = createOutputDirectoryName(request, result);
            Path outputPath = Paths.get(request.getOutputPath(), outputDirectory);
            Files.createDirectories(outputPath);
            
            ReportFiles reportFiles = new ReportFiles();
            
            // 生成各种格式报告
            reportFiles.setSummaryJson(generateSummaryJson(result, outputPath));
            reportFiles.setTradesCsv(generateTradesCsv(result, outputPath));
            reportFiles.setEquityCurveCsv(generateEquityCurveCsv(result, outputPath));
            reportFiles.setPerformanceMetricsJson(generatePerformanceMetricsJson(result, outputPath));
            reportFiles.setChineseSummaryTxt(generateChineseSummaryTxt(result, outputPath));
            
            if (request.isGenerateHtmlReport()) {
                reportFiles.setHtmlReport(generateHtmlReport(request, result, outputPath));
            }
            
            long executionTime = System.currentTimeMillis() - startTime;
            
            return ReportGenerationResult.builder()
                .successful(true)
                .outputDirectory(outputPath.toAbsolutePath().toString())
                .reportFiles(reportFiles)
                .generationTimeMs(executionTime)
                .build();
                
        } catch (Exception e) {
            log.error("报告生成失败", e);
            return ReportGenerationResult.builder()
                .successful(false)
                .error(e.getMessage())
                .build();
        }
    }
    
    /**
     * 创建输出目录名称
     */
    private String createOutputDirectoryName(BacktestRequest request, BacktestResult result) {
        String strategy = request.getStrategyName().toLowerCase();
        String cleanSymbol = request.getSymbol().replace(".HK", "").replace(".", "");
        String timestamp = result.getReportGeneratedAt().format(
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        
        return String.format("hk_%s_v1_%s_%s", strategy, cleanSymbol, timestamp);
    }
}
```

## 12.5 港股手续费精确计算

### 12.5.1 费用结构 (基于2024年标准)

```java
@Component
public class HKStockCommissionCalculator {
    
    // 港股费用标准 (2024年)
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.00025");      // 0.025%
    private static final BigDecimal MIN_COMMISSION = new BigDecimal("5.00");          // 最低¥5
    private static final BigDecimal STAMP_DUTY_RATE = new BigDecimal("0.0013");       // 0.13%
    private static final BigDecimal TRADING_FEE_RATE = new BigDecimal("0.00005");     // 0.005%
    private static final BigDecimal SETTLEMENT_FEE_RATE = new BigDecimal("0.00002");  // 0.002%
    private static final BigDecimal MIN_SETTLEMENT_FEE = new BigDecimal("2.00");      // 最低¥2
    private static final BigDecimal MAX_SETTLEMENT_FEE = new BigDecimal("100.00");    // 最高¥100
    
    /**
     * 计算交易费用详细分解
     */
    public CommissionBreakdown calculateCommission(BigDecimal price, Integer quantity, boolean isSell) {
        BigDecimal tradeValue = price.multiply(BigDecimal.valueOf(quantity));
        
        // 1. 佣金计算 (买卖都收)
        BigDecimal commission = tradeValue.multiply(COMMISSION_RATE);
        if (commission.compareTo(MIN_COMMISSION) < 0) {
            commission = MIN_COMMISSION;
        }
        
        // 2. 印花税 (只在卖出时收取)
        BigDecimal stampDuty = isSell ? 
            tradeValue.multiply(STAMP_DUTY_RATE) : BigDecimal.ZERO;
        
        // 3. 交易费 (买卖都收)
        BigDecimal tradingFee = tradeValue.multiply(TRADING_FEE_RATE);
        
        // 4. 结算费 (买卖都收)
        BigDecimal settlementFee = tradeValue.multiply(SETTLEMENT_FEE_RATE);
        if (settlementFee.compareTo(MIN_SETTLEMENT_FEE) < 0) {
            settlementFee = MIN_SETTLEMENT_FEE;
        } else if (settlementFee.compareTo(MAX_SETTLEMENT_FEE) > 0) {
            settlementFee = MAX_SETTLEMENT_FEE;
        }
        
        BigDecimal totalFee = commission.add(stampDuty).add(tradingFee).add(settlementFee);
        
        return CommissionBreakdown.builder()
            .tradeValue(tradeValue)
            .commission(commission)
            .stampDuty(stampDuty)
            .tradingFee(tradingFee)
            .settlementFee(settlementFee)
            .totalFee(totalFee)
            .build();
    }
    
    @Data
    @Builder
    public static class CommissionBreakdown {
        private BigDecimal tradeValue;      // 交易金额
        private BigDecimal commission;      // 佣金
        private BigDecimal stampDuty;       // 印花税
        private BigDecimal tradingFee;      // 交易费
        private BigDecimal settlementFee;   // 结算费
        private BigDecimal totalFee;        // 总费用
        
        /**
         * 获取费用占交易金额的百分比
         */
        public BigDecimal getFeePercentage() {
            if (tradeValue.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            return totalFee.divide(tradeValue, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        }
    }
}
```

## 12.6 CLI命令使用指南

### 12.6.1 基本使用

```bash
# 执行MACD策略回测 (02800.HK 2024年全年)
java -jar trading.jar backtest --strategy MACD --symbol 02800.HK --from 2024-01-01 --to 2024-12-31

# 指定初始资金和输出目录
java -jar trading.jar backtest -s MACD -sym 02800.HK --capital 100000 --output ./reports

# 简化命令 (使用别名)
java -jar trading.jar bt --strategy MACD --symbol 02800.HK --from 2024-01-01 --verbose
```

### 12.6.2 高级参数

```bash
# 自定义费用参数
java -jar trading.jar backtest \
  --strategy MACD \
  --symbol 02800.HK \
  --from 2024-01-01 \
  --commission 0.0002 \
  --slippage 0.0001

# 静默模式输出 (仅关键指标)
java -jar trading.jar backtest --strategy MACD --symbol 02800.HK --from 2024-01-01 --quiet

# 不生成HTML报告
java -jar trading.jar backtest --strategy MACD --symbol 02800.HK --from 2024-01-01 --no-html
```

### 12.6.3 输出示例

CLI执行后的输出格式：

```
港股程序化交易系统 CLI v1.0

=== 回测配置 ===
策略名称: MACD
交易标的: 02800.HK
时间范围: 2024-01-01 至 2024-12-31
初始资金: ¥100,000.00
K线周期: 30m

开始执行回测...
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

=== 回测结果 ===
回测期间: 2024-01-01 至 2024-12-31 (365天)
执行耗时: 2.3秒

📈 收益指标
初始资金: ¥100,000.00
最终权益: ¥118,650.00
绝对收益: ¥18,650.00
总收益率: 18.65%
年化收益: 18.65%

⚠️ 风险指标
最大回撤: 8.32%
夏普比率: 1.85
索提诺比率: 2.34
卡尔马比率: 2.24

📊 交易统计
总交易次数: 24
盈利交易: 15
亏损交易: 9
胜率: 62.5%
平均盈利: ¥2,150.00
平均亏损: ¥980.00
盈亏比: 2.19

🎯 目标分析
年化收益目标(15-20%): ✅ 达成
最大回撤目标(<15%): ✅ 达成
综合评价: 优秀

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ 回测完成

正在生成详细报告...
✅ 报告生成完成
输出目录: /path/to/output/hk_macd_v1_02800_20250812_143022
生成耗时: 0.8秒

📄 生成的文件:
  ├── summary.json (核心指标摘要)
  ├── trades.csv (详细交易记录)
  ├── equity_curve.csv (权益曲线)
  ├── performance_metrics.json (性能指标)
  ├── backtest_report.html (HTML可视化报告)
  └── chinese_summary.txt (中文摘要)
```

## 12.7 性能优化和扩展

### 12.7.1 并行化支持
- 多策略并行回测
- 参数优化网格搜索
- 异步报告生成

### 12.7.2 缓存机制
- 历史数据缓存
- 技术指标结果缓存  
- 报告模板缓存

### 12.7.3 扩展接口
- 自定义策略插件
- 多资产组合回测
- 实时回测监控

通过这个专业的回测框架，用户可以方便地通过命令行执行各种策略回测，获得详细的分析报告，为实盘交易提供数据支持。