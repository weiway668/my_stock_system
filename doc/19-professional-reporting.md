# 港股程序化交易系统 - 专业报告系统

## 19.1 报告系统概述

### 19.1.1 设计目标
专业报告系统 (BacktestReportGenerator) 为回测结果生成多格式、多维度的分析报告，匹配Python版本的输出标准，为量化交易决策提供全面的数据支持。

### 19.1.2 报告体系架构
```
报告生成流程：
┌─────────────────────────────────────────┐
│     BacktestReportGenerator             │
│  - 报告生成协调器                        │
│  - 多格式输出管理                        │
│  - 模板渲染引擎                          │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│     多格式报告生成器                     │
│  - JSONReportGenerator                  │
│  - CSVReportGenerator                   │
│  - HTMLReportGenerator                  │
│  - ChineseSummaryGenerator              │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│     输出目录结构                         │
│  output/hk_strategy_v1_symbol_timestamp/│
│  ├── summary.json                       │
│  ├── trades.csv                         │
│  ├── equity_curve.csv                   │
│  ├── performance_metrics.json           │
│  ├── backtest_report.html               │
│  └── chinese_summary.txt                │
└─────────────────────────────────────────┘
```

### 19.1.3 核心特性
- **多格式支持**: JSON/CSV/HTML/TXT等专业格式
- **Python兼容**: 输出目录结构完全兼容Python版本
- **中文本地化**: 专门的中文分析报告
- **可视化图表**: HTML报告包含交互式图表
- **模板化设计**: 支持自定义报告模板
- **异步生成**: 大报告支持异步生成，不阻塞主流程

## 19.2 核心实现

### 19.2.1 报告生成器主体

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class BacktestReportGenerator {
    
    private final JSONReportGenerator jsonGenerator;
    private final CSVReportGenerator csvGenerator;
    private final HTMLReportGenerator htmlGenerator;
    private final ChineseSummaryGenerator chineseSummaryGenerator;
    private final ReportTemplateEngine templateEngine;
    private final MetricsCalculatorService metricsCalculator;
    
    /**
     * 生成完整报告包
     * 
     * @param request 回测请求
     * @param result 回测结果
     * @return 报告生成结果
     */
    public ReportGenerationResult generateReportPackage(BacktestRequest request, BacktestResult result) {
        long startTime = System.currentTimeMillis();
        log.info("🔧 开始生成回测报告: {} {}", request.getSymbol(), request.getStrategyName());
        
        try {
            // 1. 创建输出目录
            String outputDirectoryName = createOutputDirectoryName(request, result);
            Path outputPath = createOutputDirectory(request.getOutputPath(), outputDirectoryName);
            
            // 2. 准备报告数据
            ReportData reportData = prepareReportData(request, result);
            
            // 3. 并行生成各种格式报告
            CompletableFuture<ReportFile> summaryJsonFuture = CompletableFuture.supplyAsync(() ->
                jsonGenerator.generateSummaryJson(reportData, outputPath));
            
            CompletableFuture<ReportFile> tradesCsvFuture = CompletableFuture.supplyAsync(() ->
                csvGenerator.generateTradesCsv(reportData, outputPath));
            
            CompletableFuture<ReportFile> equityCurveFuture = CompletableFuture.supplyAsync(() ->
                csvGenerator.generateEquityCurveCsv(reportData, outputPath));
            
            CompletableFuture<ReportFile> performanceJsonFuture = CompletableFuture.supplyAsync(() ->
                jsonGenerator.generatePerformanceMetricsJson(reportData, outputPath));
            
            CompletableFuture<ReportFile> chineseSummaryFuture = CompletableFuture.supplyAsync(() ->
                chineseSummaryGenerator.generateChineseSummary(reportData, outputPath));
            
            // HTML报告（可选）
            CompletableFuture<ReportFile> htmlReportFuture = null;
            if (request.isGenerateHtmlReport()) {
                htmlReportFuture = CompletableFuture.supplyAsync(() ->
                    htmlGenerator.generateHtmlReport(reportData, outputPath));
            }
            
            // 4. 等待所有报告生成完成
            List<CompletableFuture<ReportFile>> allFutures = new ArrayList<>();
            allFutures.add(summaryJsonFuture);
            allFutures.add(tradesCsvFuture);
            allFutures.add(equityCurveFuture);
            allFutures.add(performanceJsonFuture);
            allFutures.add(chineseSummaryFuture);
            if (htmlReportFuture != null) {
                allFutures.add(htmlReportFuture);
            }
            
            CompletableFuture<Void> allOfFuture = CompletableFuture.allOf(
                allFutures.toArray(new CompletableFuture[0]));
            
            List<ReportFile> reportFiles = allOfFuture.thenApply(v ->
                allFutures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
            ).get(30, TimeUnit.SECONDS); // 最多等待30秒
            
            long executionTime = System.currentTimeMillis() - startTime;
            
            // 5. 构建生成结果
            ReportGenerationResult generationResult = ReportGenerationResult.builder()
                .successful(true)
                .outputDirectory(outputPath.toAbsolutePath().toString())
                .outputDirectoryName(outputDirectoryName)
                .reportFiles(reportFiles)
                .generationTimeMs(executionTime)
                .totalFilesGenerated(reportFiles.size())
                .totalSizeBytes(calculateTotalSize(reportFiles))
                .build();
            
            log.info("✅ 报告生成完成: {} 个文件, 耗时: {}ms, 总大小: {} KB", 
                reportFiles.size(), executionTime, generationResult.getTotalSizeBytes() / 1024);
            
            return generationResult;
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("❌ 报告生成失败: {}", e.getMessage(), e);
            
            return ReportGenerationResult.builder()
                .successful(false)
                .error(e.getMessage())
                .generationTimeMs(executionTime)
                .build();
        }
    }
    
    /**
     * 创建输出目录名称
     * 格式: hk_{strategy}_v1_{symbol}_{timestamp}
     */
    private String createOutputDirectoryName(BacktestRequest request, BacktestResult result) {
        String strategy = request.getStrategyName().toLowerCase();
        String cleanSymbol = request.getSymbol().replace(".HK", "").replace(".", "");
        String timestamp = result.getReportGeneratedAt().format(
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        
        return String.format("hk_%s_v1_%s_%s", strategy, cleanSymbol, timestamp);
    }
    
    /**
     * 创建输出目录
     */
    private Path createOutputDirectory(String basePath, String directoryName) throws IOException {
        Path outputPath = Paths.get(basePath != null ? basePath : "./output", directoryName);
        Files.createDirectories(outputPath);
        
        log.debug("创建输出目录: {}", outputPath.toAbsolutePath());
        return outputPath;
    }
    
    /**
     * 准备报告数据
     */
    private ReportData prepareReportData(BacktestRequest request, BacktestResult result) {
        log.debug("准备报告数据...");
        
        // 计算扩展指标
        ExtendedMetrics extendedMetrics = metricsCalculator.calculateExtendedMetrics(result);
        
        // 分析交易模式
        TradingPatternAnalysis patternAnalysis = analyzeTradingPatterns(result.getTrades());
        
        // 风险分析
        RiskAnalysis riskAnalysis = analyzeRiskMetrics(result);
        
        // 市场对比分析
        MarketComparisonAnalysis marketComparison = compareWithMarketBenchmark(request, result);
        
        return ReportData.builder()
            .request(request)
            .result(result)
            .extendedMetrics(extendedMetrics)
            .tradingPatternAnalysis(patternAnalysis)
            .riskAnalysis(riskAnalysis)
            .marketComparisonAnalysis(marketComparison)
            .reportGenerationTime(LocalDateTime.now())
            .build();
    }
    
    /**
     * 分析交易模式
     */
    private TradingPatternAnalysis analyzeTradingPatterns(List<Trade> trades) {
        if (trades.isEmpty()) {
            return TradingPatternAnalysis.empty();
        }
        
        // 按小时统计交易分布
        Map<Integer, Long> hourlyDistribution = trades.stream()
            .collect(Collectors.groupingBy(
                trade -> trade.getEntryTime().getHour(),
                Collectors.counting()));
        
        // 按星期统计交易分布  
        Map<DayOfWeek, Long> weeklyDistribution = trades.stream()
            .collect(Collectors.groupingBy(
                trade -> trade.getEntryTime().getDayOfWeek(),
                Collectors.counting()));
        
        // 持仓时间分析
        List<Long> holdingPeriods = trades.stream()
            .filter(trade -> trade.getExitTime() != null)
            .map(trade -> ChronoUnit.MINUTES.between(trade.getEntryTime(), trade.getExitTime()))
            .collect(Collectors.toList());
        
        double avgHoldingMinutes = holdingPeriods.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);
        
        // 连续盈亏分析
        ConsecutiveAnalysis consecutiveAnalysis = analyzeConsecutiveWinsLosses(trades);
        
        return TradingPatternAnalysis.builder()
            .hourlyDistribution(hourlyDistribution)
            .weeklyDistribution(weeklyDistribution)
            .averageHoldingMinutes(avgHoldingMinutes)
            .consecutiveAnalysis(consecutiveAnalysis)
            .totalTrades(trades.size())
            .build();
    }
    
    /**
     * 分析连续盈亏情况
     */
    private ConsecutiveAnalysis analyzeConsecutiveWinsLosses(List<Trade> trades) {
        if (trades.isEmpty()) {
            return ConsecutiveAnalysis.empty();
        }
        
        int maxConsecutiveWins = 0;
        int maxConsecutiveLosses = 0;
        int currentWinStreak = 0;
        int currentLossStreak = 0;
        
        for (Trade trade : trades) {
            if (trade.getProfitLoss().compareTo(BigDecimal.ZERO) > 0) {
                // 盈利交易
                currentWinStreak++;
                currentLossStreak = 0;
                maxConsecutiveWins = Math.max(maxConsecutiveWins, currentWinStreak);
            } else {
                // 亏损交易
                currentLossStreak++;
                currentWinStreak = 0;
                maxConsecutiveLosses = Math.max(maxConsecutiveLosses, currentLossStreak);
            }
        }
        
        return ConsecutiveAnalysis.builder()
            .maxConsecutiveWins(maxConsecutiveWins)
            .maxConsecutiveLosses(maxConsecutiveLosses)
            .currentWinStreak(currentWinStreak)
            .currentLossStreak(currentLossStreak)
            .build();
    }
}
```

### 19.2.2 JSON报告生成器

```java
@Component
@Slf4j
public class JSONReportGenerator {
    
    private final ObjectMapper objectMapper;
    
    public JSONReportGenerator() {
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
    
    /**
     * 生成核心指标摘要JSON
     */
    public ReportFile generateSummaryJson(ReportData reportData, Path outputPath) {
        log.debug("生成 summary.json...");
        
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            BacktestResult result = reportData.getResult();
            BacktestRequest request = reportData.getRequest();
            
            // 基本信息
            summary.put("symbol", request.getSymbol());
            summary.put("strategy", request.getStrategyName());
            summary.put("start_date", result.getStartTime().toLocalDate());
            summary.put("end_date", result.getEndTime().toLocalDate());
            summary.put("backtest_days", result.getBacktestDays());
            summary.put("initial_capital", result.getInitialCapital());
            
            // 核心指标
            Map<String, Object> performance = new LinkedHashMap<>();
            performance.put("total_return", result.getTotalReturn());
            performance.put("return_rate", result.getReturnRate());
            performance.put("annualized_return", result.getAnnualizedReturn());
            performance.put("max_drawdown", result.getMaxDrawdown());
            performance.put("sharpe_ratio", result.getSharpeRatio());
            performance.put("sortino_ratio", result.getSortinoRatio());
            performance.put("calmar_ratio", result.getCalmarRatio());
            summary.put("performance", performance);
            
            // 交易统计
            Map<String, Object> trading = new LinkedHashMap<>();
            trading.put("total_trades", result.getTotalTrades());
            trading.put("winning_trades", result.getWinningTrades());
            trading.put("losing_trades", result.getLosingTrades());
            trading.put("win_rate", result.getWinRate());
            trading.put("avg_win", result.getAvgWin());
            trading.put("avg_loss", result.getAvgLoss());
            trading.put("profit_factor", result.getProfitFactor());
            summary.put("trading", trading);
            
            // 成本分析
            Map<String, Object> costs = new LinkedHashMap<>();
            costs.put("total_commission", result.getTotalCommission());
            costs.put("total_stamp_duty", result.getTotalStampDuty());
            costs.put("total_trading_fee", result.getTotalTradingFee());
            costs.put("total_settlement_fee", result.getTotalSettlementFee());
            costs.put("total_costs", result.getTotalCosts());
            summary.put("costs", costs);
            
            // 目标达成情况
            Map<String, Object> targets = new LinkedHashMap<>();
            boolean annualReturnTarget = result.getAnnualizedReturn().compareTo(new BigDecimal("15")) >= 0 &&
                                       result.getAnnualizedReturn().compareTo(new BigDecimal("20")) <= 0;
            boolean maxDrawdownTarget = result.getMaxDrawdown().compareTo(new BigDecimal("15")) < 0;
            
            targets.put("annual_return_target_15_20_pct", annualReturnTarget);
            targets.put("max_drawdown_target_below_15_pct", maxDrawdownTarget);
            targets.put("overall_rating", annualReturnTarget && maxDrawdownTarget ? "优秀" :
                                        annualReturnTarget || maxDrawdownTarget ? "良好" : "需要改进");
            summary.put("targets", targets);
            
            // 生成时间
            summary.put("report_generated_at", reportData.getReportGenerationTime());
            
            // 写入文件
            Path filePath = outputPath.resolve("summary.json");
            objectMapper.writeValue(filePath.toFile(), summary);
            
            long fileSize = Files.size(filePath);
            log.debug("summary.json 生成完成: {} bytes", fileSize);
            
            return ReportFile.builder()
                .fileName("summary.json")
                .filePath(filePath.toString())
                .fileType(ReportFileType.JSON)
                .description("核心指标摘要")
                .sizeBytes(fileSize)
                .build();
                
        } catch (Exception e) {
            log.error("生成 summary.json 失败", e);
            throw new ReportGenerationException("生成 summary.json 失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成完整性能指标JSON
     */
    public ReportFile generatePerformanceMetricsJson(ReportData reportData, Path outputPath) {
        log.debug("生成 performance_metrics.json...");
        
        try {
            Map<String, Object> metrics = new LinkedHashMap<>();
            BacktestResult result = reportData.getResult();
            ExtendedMetrics extended = reportData.getExtendedMetrics();
            
            // 收益指标
            Map<String, Object> returns = new LinkedHashMap<>();
            returns.put("total_return", result.getTotalReturn());
            returns.put("return_rate", result.getReturnRate());
            returns.put("annualized_return", result.getAnnualizedReturn());
            returns.put("monthly_returns", extended.getMonthlyReturns());
            returns.put("cumulative_returns", extended.getCumulativeReturns());
            metrics.put("returns", returns);
            
            // 风险指标
            Map<String, Object> risk = new LinkedHashMap<>();
            risk.put("max_drawdown", result.getMaxDrawdown());
            risk.put("volatility", extended.getVolatility());
            risk.put("downside_deviation", extended.getDownsideDeviation());
            risk.put("var_95", extended.getVar95());
            risk.put("cvar_95", extended.getCvar95());
            risk.put("beta", extended.getBeta());
            metrics.put("risk", risk);
            
            // 风险调整收益指标
            Map<String, Object> riskAdjusted = new LinkedHashMap<>();
            riskAdjusted.put("sharpe_ratio", result.getSharpeRatio());
            riskAdjusted.put("sortino_ratio", result.getSortinoRatio());
            riskAdjusted.put("calmar_ratio", result.getCalmarRatio());
            riskAdjusted.put("information_ratio", extended.getInformationRatio());
            riskAdjusted.put("treynor_ratio", extended.getTreynorRatio());
            metrics.put("risk_adjusted", riskAdjusted);
            
            // 交易效率指标
            Map<String, Object> efficiency = new LinkedHashMap<>();
            efficiency.put("profit_factor", result.getProfitFactor());
            efficiency.put("recovery_factor", extended.getRecoveryFactor());
            efficiency.put("payoff_ratio", extended.getPayoffRatio());
            efficiency.put("kelly_criterion", extended.getKellyCriterion());
            metrics.put("efficiency", efficiency);
            
            // 写入文件
            Path filePath = outputPath.resolve("performance_metrics.json");
            objectMapper.writeValue(filePath.toFile(), metrics);
            
            long fileSize = Files.size(filePath);
            log.debug("performance_metrics.json 生成完成: {} bytes", fileSize);
            
            return ReportFile.builder()
                .fileName("performance_metrics.json")
                .filePath(filePath.toString())
                .fileType(ReportFileType.JSON)
                .description("完整性能分析")
                .sizeBytes(fileSize)
                .build();
                
        } catch (Exception e) {
            log.error("生成 performance_metrics.json 失败", e);
            throw new ReportGenerationException("生成 performance_metrics.json 失败: " + e.getMessage(), e);
        }
    }
}
```

### 19.2.3 CSV报告生成器

```java
@Component
@Slf4j
public class CSVReportGenerator {
    
    private static final String CSV_HEADER_TRADES = "trade_id,symbol,entry_time,exit_time,side,quantity,entry_price,exit_price,profit_loss,profit_loss_pct,holding_minutes,commission,total_cost";
    private static final String CSV_HEADER_EQUITY = "timestamp,equity,drawdown,drawdown_pct,daily_return,cumulative_return";
    
    /**
     * 生成详细交易记录CSV
     */
    public ReportFile generateTradesCsv(ReportData reportData, Path outputPath) {
        log.debug("生成 trades.csv...");
        
        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(outputPath.resolve("trades.csv"), StandardCharsets.UTF_8))) {
            
            // 写入表头
            writer.println(CSV_HEADER_TRADES);
            
            // 写入交易数据
            List<Trade> trades = reportData.getResult().getTrades();
            for (int i = 0; i < trades.size(); i++) {
                Trade trade = trades.get(i);
                
                String line = String.join(",",
                    String.valueOf(i + 1), // trade_id
                    trade.getSymbol(),
                    trade.getEntryTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    trade.getExitTime() != null ? 
                        trade.getExitTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "",
                    trade.getSide().name(),
                    String.valueOf(trade.getQuantity()),
                    formatDecimal(trade.getEntryPrice()),
                    trade.getExitPrice() != null ? formatDecimal(trade.getExitPrice()) : "",
                    formatDecimal(trade.getProfitLoss()),
                    formatDecimal(trade.getProfitLossPercentage()),
                    String.valueOf(trade.getHoldingMinutes()),
                    formatDecimal(trade.getCommission()),
                    formatDecimal(trade.getTotalCost())
                );
                
                writer.println(line);
            }
            
            Path filePath = outputPath.resolve("trades.csv");
            long fileSize = Files.size(filePath);
            
            log.debug("trades.csv 生成完成: {} 条交易记录, {} bytes", trades.size(), fileSize);
            
            return ReportFile.builder()
                .fileName("trades.csv")
                .filePath(filePath.toString())
                .fileType(ReportFileType.CSV)
                .description("详细交易记录")
                .sizeBytes(fileSize)
                .recordCount(trades.size())
                .build();
                
        } catch (Exception e) {
            log.error("生成 trades.csv 失败", e);
            throw new ReportGenerationException("生成 trades.csv 失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成权益曲线CSV
     */
    public ReportFile generateEquityCurveCsv(ReportData reportData, Path outputPath) {
        log.debug("生成 equity_curve.csv...");
        
        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(outputPath.resolve("equity_curve.csv"), StandardCharsets.UTF_8))) {
            
            // 写入表头
            writer.println(CSV_HEADER_EQUITY);
            
            // 写入权益曲线数据
            List<DailyStats> dailyStats = reportData.getResult().getDailyStats();
            BigDecimal initialEquity = reportData.getResult().getInitialCapital();
            BigDecimal maxEquity = initialEquity;
            
            for (DailyStats stats : dailyStats) {
                BigDecimal currentEquity = stats.getEndEquity();
                
                // 计算回撤
                if (currentEquity.compareTo(maxEquity) > 0) {
                    maxEquity = currentEquity;
                }
                BigDecimal drawdown = maxEquity.subtract(currentEquity);
                BigDecimal drawdownPct = drawdown.divide(maxEquity, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
                
                // 计算收益率
                BigDecimal dailyReturn = stats.getDailyReturn();
                BigDecimal cumulativeReturn = currentEquity.subtract(initialEquity)
                    .divide(initialEquity, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
                
                String line = String.join(",",
                    stats.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    formatDecimal(currentEquity),
                    formatDecimal(drawdown),
                    formatDecimal(drawdownPct),
                    formatDecimal(dailyReturn),
                    formatDecimal(cumulativeReturn)
                );
                
                writer.println(line);
            }
            
            Path filePath = outputPath.resolve("equity_curve.csv");
            long fileSize = Files.size(filePath);
            
            log.debug("equity_curve.csv 生成完成: {} 个数据点, {} bytes", dailyStats.size(), fileSize);
            
            return ReportFile.builder()
                .fileName("equity_curve.csv")
                .filePath(filePath.toString())
                .fileType(ReportFileType.CSV)
                .description("权益曲线数据")
                .sizeBytes(fileSize)
                .recordCount(dailyStats.size())
                .build();
                
        } catch (Exception e) {
            log.error("生成 equity_curve.csv 失败", e);
            throw new ReportGenerationException("生成 equity_curve.csv 失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 格式化小数，保留合适的精度
     */
    private String formatDecimal(BigDecimal value) {
        if (value == null) {
            return "";
        }
        // 去除末尾零并返回纯数字字符串
        return value.stripTrailingZeros().toPlainString();
    }
}
```

### 19.2.4 中文摘要生成器

```java
@Component
@Slf4j
public class ChineseSummaryGenerator {
    
    /**
     * 生成中文分析摘要
     */
    public ReportFile generateChineseSummary(ReportData reportData, Path outputPath) {
        log.debug("生成 chinese_summary.txt...");
        
        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(outputPath.resolve("chinese_summary.txt"), StandardCharsets.UTF_8))) {
            
            BacktestResult result = reportData.getResult();
            BacktestRequest request = reportData.getRequest();
            TradingPatternAnalysis pattern = reportData.getTradingPatternAnalysis();
            
            // 生成详细的中文分析报告
            generateDetailedChineseSummary(writer, request, result, pattern);
            
            Path filePath = outputPath.resolve("chinese_summary.txt");
            long fileSize = Files.size(filePath);
            
            log.debug("chinese_summary.txt 生成完成: {} bytes", fileSize);
            
            return ReportFile.builder()
                .fileName("chinese_summary.txt")
                .filePath(filePath.toString())
                .fileType(ReportFileType.TEXT)
                .description("中文分析摘要")
                .sizeBytes(fileSize)
                .build();
                
        } catch (Exception e) {
            log.error("生成 chinese_summary.txt 失败", e);
            throw new ReportGenerationException("生成 chinese_summary.txt 失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成详细的中文分析摘要
     */
    private void generateDetailedChineseSummary(PrintWriter writer, BacktestRequest request, 
                                              BacktestResult result, TradingPatternAnalysis pattern) {
        
        // 标题
        writer.println("═══════════════════════════════════════════════");
        writer.println("           港股程序化交易回测报告");
        writer.println("═══════════════════════════════════════════════");
        writer.println();
        
        // 基本信息
        writer.println("【回测基本信息】");
        writer.println("交易标的：" + request.getSymbol());
        writer.println("策略名称：" + request.getStrategyName());
        writer.println("回测期间：" + result.getStartTime().toLocalDate() + " 至 " + result.getEndTime().toLocalDate());
        writer.println("回测天数：" + result.getBacktestDays() + " 天");
        writer.println("初始资金：¥" + formatCurrency(result.getInitialCapital()));
        writer.println("最终权益：¥" + formatCurrency(result.getFinalEquity()));
        writer.println("执行耗时：" + String.format("%.1f", result.getExecutionTimeMs() / 1000.0) + " 秒");
        writer.println();
        
        // 收益分析
        writer.println("【收益分析】");
        writer.println("绝对收益：¥" + formatCurrency(result.getTotalReturn()));
        writer.println("总收益率：" + formatPercentage(result.getReturnRate()) + "%");
        writer.println("年化收益率：" + formatPercentage(result.getAnnualizedReturn()) + "%");
        writer.println("基准收益率：" + formatPercentage(result.getBenchmarkReturn()) + "% (恒生指数)");
        writer.println("超额收益：" + formatPercentage(result.getExcessReturn()) + "%");
        writer.println();
        
        // 风险分析
        writer.println("【风险分析】");
        writer.println("最大回撤：" + formatPercentage(result.getMaxDrawdown()) + "%");
        writer.println("夏普比率：" + formatDecimal(result.getSharpeRatio()));
        if (result.getSortinoRatio() != null) {
            writer.println("索提诺比率：" + formatDecimal(result.getSortinoRatio()));
        }
        if (result.getCalmarRatio() != null) {
            writer.println("卡尔马比率：" + formatDecimal(result.getCalmarRatio()));
        }
        writer.println("波动率：" + formatPercentage(reportData.getExtendedMetrics().getVolatility()) + "%");
        writer.println("下行风险：" + formatPercentage(reportData.getExtendedMetrics().getDownsideDeviation()) + "%");
        writer.println();
        
        // 交易统计
        writer.println("【交易统计】");
        writer.println("总交易次数：" + result.getTotalTrades() + " 笔");
        if (result.getTotalTrades() > 0) {
            writer.println("盈利交易：" + result.getWinningTrades() + " 笔");
            writer.println("亏损交易：" + result.getLosingTrades() + " 笔");
            writer.println("胜率：" + formatPercentage(result.getWinRate()) + "%");
            writer.println("平均盈利：¥" + formatCurrency(result.getAvgWin()));
            writer.println("平均亏损：¥" + formatCurrency(result.getAvgLoss()));
            writer.println("盈亏比：" + formatDecimal(result.getProfitFactor()));
            writer.println("平均持仓时间：" + formatHoldingTime(pattern.getAverageHoldingMinutes()));
        }
        writer.println();
        
        // 交易模式分析
        writer.println("【交易模式分析】");
        writer.println("最大连续盈利：" + pattern.getConsecutiveAnalysis().getMaxConsecutiveWins() + " 笔");
        writer.println("最大连续亏损：" + pattern.getConsecutiveAnalysis().getMaxConsecutiveLosses() + " 笔");
        writer.println("当前连胜连亏：" + getCurrentStreakDescription(pattern.getConsecutiveAnalysis()));
        
        // 交易时间分布
        writer.println("交易时间偏好：" + analyzeTradingTimePreference(pattern.getHourlyDistribution()));
        writer.println("交易日偏好：" + analyzeTradingDayPreference(pattern.getWeeklyDistribution()));
        writer.println();
        
        // 成本分析
        writer.println("【成本分析】");
        writer.println("佣金费用：¥" + formatCurrency(result.getTotalCommission()));
        writer.println("印花税：¥" + formatCurrency(result.getTotalStampDuty()));
        writer.println("交易费：¥" + formatCurrency(result.getTotalTradingFee()));
        writer.println("结算费：¥" + formatCurrency(result.getTotalSettlementFee()));
        writer.println("滑点成本：¥" + formatCurrency(result.getTotalSlippage()));
        writer.println("总成本：¥" + formatCurrency(result.getTotalCosts()));
        
        BigDecimal costRatio = result.getTotalCosts().divide(result.getInitialCapital(), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        writer.println("成本占比：" + formatPercentage(costRatio) + "%");
        writer.println();
        
        // 目标达成分析
        writer.println("【目标达成分析】");
        boolean annualReturnTarget = result.getAnnualizedReturn().compareTo(new BigDecimal("15")) >= 0 &&
                                   result.getAnnualizedReturn().compareTo(new BigDecimal("20")) <= 0;
        boolean maxDrawdownTarget = result.getMaxDrawdown().compareTo(new BigDecimal("15")) < 0;
        
        writer.println("年化收益目标 (15%-20%)：" + (annualReturnTarget ? "✓ 达成" : "✗ 未达成"));
        writer.println("最大回撤目标 (<15%)：" + (maxDrawdownTarget ? "✓ 达成" : "✗ 超出"));
        
        String overallRating;
        if (annualReturnTarget && maxDrawdownTarget) {
            overallRating = "优秀 - 收益和风险控制都表现良好";
        } else if (annualReturnTarget) {
            overallRating = "良好 - 收益达标但风险控制需要改进";
        } else if (maxDrawdownTarget) {
            overallRating = "一般 - 风险控制良好但收益不足";
        } else {
            overallRating = "需要改进 - 收益和风险控制都未达标";
        }
        writer.println("综合评价：" + overallRating);
        writer.println();
        
        // 策略评估和建议
        writer.println("【策略评估与建议】");
        generateStrategyRecommendations(writer, result, pattern);
        
        // 报告生成信息
        writer.println();
        writer.println("───────────────────────────────────────────────");
        writer.println("报告生成时间：" + LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")));
        writer.println("报告版本：港股程序化交易系统 v1.0");
        writer.println("───────────────────────────────────────────────");
    }
    
    /**
     * 生成策略评估和建议
     */
    private void generateStrategyRecommendations(PrintWriter writer, BacktestResult result, 
                                               TradingPatternAnalysis pattern) {
        
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        
        // 分析优势
        if (result.getSharpeRatio().compareTo(new BigDecimal("1.5")) >= 0) {
            strengths.add("夏普比率表现优秀，风险调整后收益良好");
        }
        if (result.getWinRate().compareTo(new BigDecimal("55")) >= 0) {
            strengths.add("胜率较高，策略选股能力较强");
        }
        if (result.getProfitFactor().compareTo(new BigDecimal("1.5")) >= 0) {
            strengths.add("盈亏比表现良好，能够有效控制亏损");
        }
        
        // 分析劣势
        if (result.getMaxDrawdown().compareTo(new BigDecimal("10")) >= 0) {
            weaknesses.add("最大回撤较大，需要加强风险控制");
        }
        if (result.getTotalTrades() < 10) {
            weaknesses.add("交易频率较低，样本数量不足以验证策略稳定性");
        }
        if (pattern.getConsecutiveAnalysis().getMaxConsecutiveLosses() >= 5) {
            weaknesses.add("存在连续亏损风险，需要优化止损机制");
        }
        
        // 生成建议
        if (result.getMaxDrawdown().compareTo(new BigDecimal("15")) >= 0) {
            recommendations.add("建议加强仓位管理，考虑动态调整持仓规模");
        }
        if (result.getWinRate().compareTo(new BigDecimal("50")) < 0) {
            recommendations.add("建议优化进场信号，提高交易成功率");
        }
        if (result.getTotalTrades() > 0) {
            double avgHoldingHours = pattern.getAverageHoldingMinutes() / 60.0;
            if (avgHoldingHours < 2) {
                recommendations.add("持仓时间较短，建议评估是否过于频繁交易");
            } else if (avgHoldingHours > 48) {
                recommendations.add("持仓时间较长，建议优化出场时机");
            }
        }
        
        // 输出评估结果
        if (!strengths.isEmpty()) {
            writer.println("策略优势：");
            strengths.forEach(strength -> writer.println("+ " + strength));
        }
        
        if (!weaknesses.isEmpty()) {
            writer.println("策略劣势：");
            weaknesses.forEach(weakness -> writer.println("- " + weakness));
        }
        
        if (!recommendations.isEmpty()) {
            writer.println("改进建议：");
            recommendations.forEach(recommendation -> writer.println("→ " + recommendation));
        }
        
        if (strengths.isEmpty() && weaknesses.isEmpty()) {
            writer.println("策略表现平平，建议进一步优化参数或考虑其他策略。");
        }
    }
    
    // 格式化辅助方法
    private String formatCurrency(BigDecimal amount) {
        return String.format("%,.2f", amount);
    }
    
    private String formatPercentage(BigDecimal percentage) {
        return String.format("%.2f", percentage);
    }
    
    private String formatDecimal(BigDecimal value) {
        return String.format("%.2f", value);
    }
    
    private String formatHoldingTime(double minutes) {
        if (minutes < 60) {
            return String.format("%.0f分钟", minutes);
        } else if (minutes < 1440) {
            return String.format("%.1f小时", minutes / 60);
        } else {
            return String.format("%.1f天", minutes / 1440);
        }
    }
}
```

### 19.2.5 HTML可视化报告生成器

```java
@Component
@Slf4j
public class HTMLReportGenerator {
    
    private final TemplateEngine templateEngine;
    
    public HTMLReportGenerator() {
        // 初始化Thymeleaf模板引擎
        this.templateEngine = new TemplateEngine();
        FileTemplateResolver templateResolver = new FileTemplateResolver();
        templateResolver.setPrefix("classpath:/templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("UTF-8");
        this.templateEngine.setTemplateResolver(templateResolver);
    }
    
    /**
     * 生成HTML可视化报告
     */
    public ReportFile generateHtmlReport(ReportData reportData, Path outputPath) {
        log.debug("生成 backtest_report.html...");
        
        try {
            // 准备模板变量
            Context context = new Context();
            context.setVariable("reportData", reportData);
            context.setVariable("result", reportData.getResult());
            context.setVariable("request", reportData.getRequest());
            context.setVariable("extendedMetrics", reportData.getExtendedMetrics());
            context.setVariable("tradingPattern", reportData.getTradingPatternAnalysis());
            context.setVariable("riskAnalysis", reportData.getRiskAnalysis());
            context.setVariable("generationTime", LocalDateTime.now());
            
            // 准备图表数据
            prepareChartData(context, reportData);
            
            // 渲染HTML模板
            String html = templateEngine.process("backtest_report", context);
            
            // 写入文件
            Path filePath = outputPath.resolve("backtest_report.html");
            Files.write(filePath, html.getBytes(StandardCharsets.UTF_8));
            
            long fileSize = Files.size(filePath);
            
            log.debug("backtest_report.html 生成完成: {} bytes", fileSize);
            
            return ReportFile.builder()
                .fileName("backtest_report.html")
                .filePath(filePath.toString())
                .fileType(ReportFileType.HTML)
                .description("HTML可视化报告")
                .sizeBytes(fileSize)
                .build();
                
        } catch (Exception e) {
            log.error("生成 backtest_report.html 失败", e);
            throw new ReportGenerationException("生成 backtest_report.html 失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 准备图表数据
     */
    private void prepareChartData(Context context, ReportData reportData) {
        BacktestResult result = reportData.getResult();
        
        // 权益曲线数据
        List<Map<String, Object>> equityData = result.getDailyStats().stream()
            .map(stats -> {
                Map<String, Object> point = new HashMap<>();
                point.put("date", stats.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                point.put("equity", stats.getEndEquity());
                return point;
            })
            .collect(Collectors.toList());
        context.setVariable("equityChartData", equityData);
        
        // 回撤曲线数据
        List<Map<String, Object>> drawdownData = calculateDrawdownData(result.getDailyStats());
        context.setVariable("drawdownChartData", drawdownData);
        
        // 月度收益分布
        Map<String, BigDecimal> monthlyReturns = calculateMonthlyReturns(result.getDailyStats());
        context.setVariable("monthlyReturnsData", monthlyReturns);
        
        // 交易分布数据
        TradingPatternAnalysis pattern = reportData.getTradingPatternAnalysis();
        context.setVariable("hourlyDistribution", pattern.getHourlyDistribution());
        context.setVariable("weeklyDistribution", pattern.getWeeklyDistribution());
        
        // 盈亏分布数据
        Map<String, Object> profitDistribution = calculateProfitDistribution(result.getTrades());
        context.setVariable("profitDistribution", profitDistribution);
    }
    
    private List<Map<String, Object>> calculateDrawdownData(List<DailyStats> dailyStats) {
        // 计算每日回撤数据
        BigDecimal maxEquity = dailyStats.get(0).getEndEquity();
        
        return dailyStats.stream()
            .map(stats -> {
                BigDecimal currentEquity = stats.getEndEquity();
                if (currentEquity.compareTo(maxEquity) > 0) {
                    maxEquity = currentEquity;
                }
                
                BigDecimal drawdown = maxEquity.subtract(currentEquity)
                    .divide(maxEquity, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
                
                Map<String, Object> point = new HashMap<>();
                point.put("date", stats.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                point.put("drawdown", drawdown.negate()); // 负值显示
                return point;
            })
            .collect(Collectors.toList());
    }
}
```

## 19.3 报告数据结构

### 19.3.1 报告生成结果

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReportGenerationResult {
    
    private boolean successful;
    private String error;
    private String outputDirectory;
    private String outputDirectoryName;
    private List<ReportFile> reportFiles;
    private long generationTimeMs;
    private int totalFilesGenerated;
    private long totalSizeBytes;
    
    /**
     * 获取生成报告摘要
     */
    public String getSummary() {
        if (!successful) {
            return String.format("报告生成失败: %s (耗时: %dms)", error, generationTimeMs);
        }
        
        return String.format(
            "报告生成成功: %d个文件, 总大小: %.1fKB, 耗时: %dms\n输出目录: %s",
            totalFilesGenerated,
            totalSizeBytes / 1024.0,
            generationTimeMs,
            outputDirectory
        );
    }
    
    /**
     * 获取文件清单
     */
    public List<String> getFileList() {
        if (reportFiles == null) {
            return Collections.emptyList();
        }
        
        return reportFiles.stream()
            .map(file -> String.format("├── %s (%s)", file.getFileName(), file.getDescription()))
            .collect(Collectors.toList());
    }
    
    /**
     * 验证所有文件是否生成成功
     */
    public boolean validateAllFiles() {
        if (!successful || reportFiles == null) {
            return false;
        }
        
        return reportFiles.stream()
            .allMatch(file -> {
                Path filePath = Paths.get(file.getFilePath());
                return Files.exists(filePath) && file.getSizeBytes() > 0;
            });
    }
}

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReportFile {
    private String fileName;
    private String filePath;
    private ReportFileType fileType;
    private String description;
    private long sizeBytes;
    private Integer recordCount; // 对于数据文件，记录行数
    
    /**
     * 获取文件大小的友好显示
     */
    public String getFormattedSize() {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        } else if (sizeBytes < 1024 * 1024) {
            return String.format("%.1f KB", sizeBytes / 1024.0);
        } else {
            return String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
        }
    }
}

public enum ReportFileType {
    JSON("JSON数据文件", ".json"),
    CSV("CSV表格文件", ".csv"),
    HTML("HTML网页文件", ".html"),
    TEXT("文本文件", ".txt"),
    PDF("PDF文档", ".pdf");
    
    private final String description;
    private final String extension;
    
    ReportFileType(String description, String extension) {
        this.description = description;
        this.extension = extension;
    }
    
    public String getDescription() { return description; }
    public String getExtension() { return extension; }
}
```

## 19.4 报告模板系统

### 19.4.1 HTML报告模板 (Thymeleaf)

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>港股程序化交易回测报告</title>
    <script src="https://cdn.plot.ly/plotly-latest.min.js"></script>
    <style>
        body { font-family: 'Microsoft YaHei', Arial, sans-serif; margin: 0; padding: 20px; background-color: #f5f5f5; }
        .container { max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px; box-shadow: 0 0 20px rgba(0,0,0,0.1); }
        .header { text-align: center; margin-bottom: 30px; padding-bottom: 20px; border-bottom: 2px solid #e0e0e0; }
        .title { color: #2c3e50; font-size: 28px; font-weight: bold; margin-bottom: 10px; }
        .subtitle { color: #7f8c8d; font-size: 16px; }
        .metrics-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 20px; margin: 30px 0; }
        .metric-card { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px; border-radius: 10px; text-align: center; }
        .metric-card.positive { background: linear-gradient(135deg, #4CAF50 0%, #45a049 100%); }
        .metric-card.negative { background: linear-gradient(135deg, #f44336 0%, #d32f2f 100%); }
        .metric-value { font-size: 24px; font-weight: bold; margin-bottom: 5px; }
        .metric-label { font-size: 14px; opacity: 0.9; }
        .chart-container { margin: 30px 0; padding: 20px; background: #fafafa; border-radius: 10px; }
        .chart-title { color: #2c3e50; font-size: 18px; font-weight: bold; margin-bottom: 15px; text-align: center; }
        .summary-section { margin: 30px 0; padding: 20px; background: #f8f9fa; border-radius: 10px; border-left: 4px solid #007bff; }
        .section-title { color: #2c3e50; font-size: 20px; font-weight: bold; margin-bottom: 15px; }
        .trade-table { width: 100%; border-collapse: collapse; margin-top: 20px; }
        .trade-table th, .trade-table td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }
        .trade-table th { background-color: #f2f2f2; font-weight: bold; }
        .profit { color: #4CAF50; font-weight: bold; }
        .loss { color: #f44336; font-weight: bold; }
        .footer { margin-top: 50px; padding-top: 20px; border-top: 1px solid #e0e0e0; text-align: center; color: #7f8c8d; font-size: 12px; }
    </style>
</head>
<body>
    <div class="container">
        <!-- 报告头部 -->
        <div class="header">
            <div class="title">港股程序化交易回测报告</div>
            <div class="subtitle" th:text="|${result.symbol} • ${request.strategyName} • ${#temporals.format(result.startTime, 'yyyy-MM-dd')} 至 ${#temporals.format(result.endTime, 'yyyy-MM-dd')}|"></div>
        </div>

        <!-- 核心指标卡片 -->
        <div class="metrics-grid">
            <div class="metric-card" th:classappend="${result.returnRate > 0} ? 'positive' : 'negative'">
                <div class="metric-value" th:text="|${#numbers.formatDecimal(result.returnRate, 1, 2)}%|"></div>
                <div class="metric-label">总收益率</div>
            </div>
            <div class="metric-card" th:classappend="${result.annualizedReturn > 15} ? 'positive' : 'negative'">
                <div class="metric-value" th:text="|${#numbers.formatDecimal(result.annualizedReturn, 1, 2)}%|"></div>
                <div class="metric-label">年化收益率</div>
            </div>
            <div class="metric-card" th:classappend="${result.maxDrawdown < 15} ? 'positive' : 'negative'">
                <div class="metric-value" th:text="|${#numbers.formatDecimal(result.maxDrawdown, 1, 2)}%|"></div>
                <div class="metric-label">最大回撤</div>
            </div>
            <div class="metric-card">
                <div class="metric-value" th:text="${#numbers.formatDecimal(result.sharpeRatio, 1, 2)}"></div>
                <div class="metric-label">夏普比率</div>
            </div>
            <div class="metric-card" th:classappend="${result.winRate > 50} ? 'positive' : 'negative'">
                <div class="metric-value" th:text="|${#numbers.formatDecimal(result.winRate, 1, 1)}%|"></div>
                <div class="metric-label">胜率</div>
            </div>
            <div class="metric-card">
                <div class="metric-value" th:text="${result.totalTrades}"></div>
                <div class="metric-label">总交易次数</div>
            </div>
        </div>

        <!-- 权益曲线图 -->
        <div class="chart-container">
            <div class="chart-title">权益曲线</div>
            <div id="equityChart" style="height: 400px;"></div>
        </div>

        <!-- 回撤曲线图 -->
        <div class="chart-container">
            <div class="chart-title">回撤曲线</div>
            <div id="drawdownChart" style="height: 300px;"></div>
        </div>

        <!-- 交易分布图 -->
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 20px;">
            <div class="chart-container">
                <div class="chart-title">小时交易分布</div>
                <div id="hourlyChart" style="height: 300px;"></div>
            </div>
            <div class="chart-container">
                <div class="chart-title">星期交易分布</div>
                <div id="weeklyChart" style="height: 300px;"></div>
            </div>
        </div>

        <!-- 详细交易记录 -->
        <div class="summary-section">
            <div class="section-title">交易记录明细 (前10笔)</div>
            <table class="trade-table">
                <thead>
                    <tr>
                        <th>交易时间</th>
                        <th>方向</th>
                        <th>数量</th>
                        <th>进场价格</th>
                        <th>出场价格</th>
                        <th>盈亏</th>
                        <th>盈亏率</th>
                        <th>持仓时间</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="trade, iterStat : ${result.trades}" th:if="${iterStat.index < 10}">
                        <td th:text="${#temporals.format(trade.entryTime, 'MM-dd HH:mm')}"></td>
                        <td th:text="${trade.side == 'BUY' ? '买入' : '卖出'}" 
                            th:class="${trade.side == 'BUY' ? 'profit' : 'loss'}"></td>
                        <td th:text="${trade.quantity}"></td>
                        <td th:text="|¥${#numbers.formatDecimal(trade.entryPrice, 1, 2)}|"></td>
                        <td th:text="|¥${#numbers.formatDecimal(trade.exitPrice, 1, 2)}|"></td>
                        <td th:text="|¥${#numbers.formatDecimal(trade.profitLoss, 1, 2)}|" 
                            th:class="${trade.profitLoss > 0 ? 'profit' : 'loss'}"></td>
                        <td th:text="|${#numbers.formatDecimal(trade.profitLossPercentage, 1, 2)}%|"
                            th:class="${trade.profitLossPercentage > 0 ? 'profit' : 'loss'}"></td>
                        <td th:text="${trade.holdingMinutes < 60 ? trade.holdingMinutes + '分钟' : (trade.holdingMinutes/60) + '小时'}"></td>
                    </tr>
                </tbody>
            </table>
        </div>

        <!-- 报告脚注 -->
        <div class="footer">
            <div>报告生成时间：<span th:text="${#temporals.format(generationTime, 'yyyy年MM月dd日 HH:mm:ss')}"></span></div>
            <div>港股程序化交易系统 v1.0 • Powered by Claude Code</div>
        </div>
    </div>

    <!-- JavaScript图表代码 -->
    <script th:inline="javascript">
        // 权益曲线图
        var equityData = /*[[${equityChartData}]]*/ [];
        var equityTrace = {
            x: equityData.map(d => d.date),
            y: equityData.map(d => d.equity),
            type: 'scatter',
            mode: 'lines',
            name: '权益',
            line: { color: '#007bff', width: 2 }
        };
        
        Plotly.newPlot('equityChart', [equityTrace], {
            title: false,
            xaxis: { title: '日期' },
            yaxis: { title: '权益 (¥)' },
            margin: { l: 60, r: 20, t: 20, b: 40 }
        });

        // 回撤曲线图
        var drawdownData = /*[[${drawdownChartData}]]*/ [];
        var drawdownTrace = {
            x: drawdownData.map(d => d.date),
            y: drawdownData.map(d => d.drawdown),
            type: 'scatter',
            mode: 'lines',
            name: '回撤',
            line: { color: '#dc3545', width: 2 },
            fill: 'tozeroy',
            fillcolor: 'rgba(220, 53, 69, 0.1)'
        };
        
        Plotly.newPlot('drawdownChart', [drawdownTrace], {
            title: false,
            xaxis: { title: '日期' },
            yaxis: { title: '回撤 (%)' },
            margin: { l: 60, r: 20, t: 20, b: 40 }
        });

        // 其他图表...
    </script>
</body>
</html>
```

## 19.5 配置和监控

### 19.5.1 报告生成配置

```yaml
# application.yml
trading:
  reporting:
    # 基础配置
    default-output-path: "./output"
    enable-async-generation: true
    generation-timeout-seconds: 300
    
    # 文件格式配置
    formats:
      json:
        enabled: true
        pretty-print: true
      csv:
        enabled: true
        charset: UTF-8
        delimiter: ","
      html:
        enabled: true
        template-engine: thymeleaf
        include-charts: true
      chinese-summary:
        enabled: true
        detailed-analysis: true
        
    # 图表配置
    charts:
      plotly-version: "latest"
      default-theme: "light"
      colors:
        profit: "#4CAF50"
        loss: "#f44336"
        neutral: "#007bff"
        
    # 模板配置
    templates:
      base-path: "classpath:/templates/"
      cache-enabled: true
      cache-ttl: 3600
      
    # 性能配置
    performance:
      max-concurrent-generations: 4
      max-report-size-mb: 50
      cleanup-old-reports-days: 30
      
    # 监控配置
    monitoring:
      enabled: true
      metrics-enabled: true
      alert-long-generation-threshold-ms: 30000
```

### 19.5.2 报告生成监控

```java
@Component
public class ReportGenerationMonitor {
    
    private final MeterRegistry meterRegistry;
    private final Counter reportGenerationCounter;
    private final Timer reportGenerationTimer;
    private final Gauge reportSizeGauge;
    
    public ReportGenerationMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.reportGenerationCounter = Counter.builder("report_generation_total")
            .description("报告生成总数")
            .register(meterRegistry);
        this.reportGenerationTimer = Timer.builder("report_generation_duration")
            .description("报告生成耗时")
            .register(meterRegistry);
        this.reportSizeGauge = Gauge.builder("report_package_size_bytes")
            .description("报告包大小")
            .register(meterRegistry, this, ReportGenerationMonitor::getCurrentReportSize);
    }
    
    public void recordReportGeneration(String strategy, String symbol, boolean success, 
                                     long durationMs, long sizeBytes) {
        reportGenerationCounter.increment(
            Tags.of("strategy", strategy, "symbol", symbol, "status", success ? "success" : "error"));
        
        reportGenerationTimer.record(durationMs, TimeUnit.MILLISECONDS);
        
        if (success) {
            meterRegistry.gauge("report_size_bytes", 
                Tags.of("strategy", strategy, "symbol", symbol), sizeBytes);
        }
        
        // 长时间生成告警
        if (durationMs > 30000) { // 30秒
            log.warn("报告生成时间过长: {}ms, 策略: {}, 标的: {}", durationMs, strategy, symbol);
        }
    }
    
    private double getCurrentReportSize() {
        return meterRegistry.find("report_size_bytes")
            .gauge() != null ? meterRegistry.find("report_size_bytes").gauge().value() : 0.0;
    }
}
```

通过这个完善的专业报告系统，能够为回测结果生成多格式、多维度的分析报告，完全兼容Python版本的输出标准，为量化交易决策提供全面的数据支持和可视化分析。