package com.trading.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.domain.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 专业级回测报告生成器
 * 生成多种格式的详细回测报告
 */
@Slf4j
@Service
public class BacktestReportGenerator {
    
    private final ObjectMapper objectMapper;
    
    public BacktestReportGenerator() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * 生成完整的回测报告包
     * 类似Python版本的输出结构：output/hk_etf_v1_02800_20250812_082334/
     */
    public ReportGenerationResult generateReportPackage(BacktestRequest request, BacktestResult result) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 创建输出目录
            String outputDirName = createOutputDirectoryName(request, result);
            Path outputDir = createOutputDirectory(request.getOutputPath(), outputDirName);
            
            log.info("开始生成回测报告包: {}", outputDir);
            
            // 2. 生成各种格式的报告文件
            ReportFiles reportFiles = ReportFiles.builder()
                .outputDirectory(outputDir)
                .build();
            
            // 生成核心摘要文件
            generateSummaryJson(result, outputDir.resolve("summary.json"));
            reportFiles.setSummaryJson("summary.json");
            
            // 生成详细交易记录
            generateTradesCsv(result.getTrades(), outputDir.resolve("trades.csv"));
            reportFiles.setTradesCsv("trades.csv");
            
            // 生成权益曲线数据
            generateEquityCurveCsv(result, outputDir.resolve("equity_curve.csv"));
            reportFiles.setEquityCurveCsv("equity_curve.csv");
            
            // 生成每日收益数据
            generateDailyReturnsCsv(result, outputDir.resolve("daily_returns.csv"));
            reportFiles.setDailyReturnsCsv("daily_returns.csv");
            
            // 生成完整性能指标
            generatePerformanceMetricsJson(result, outputDir.resolve("performance_metrics.json"));
            reportFiles.setPerformanceMetricsJson("performance_metrics.json");
            
            // 生成策略参数配置
            generateStrategyParametersJson(request, outputDir.resolve("strategy_parameters.json"));
            reportFiles.setStrategyParametersJson("strategy_parameters.json");
            
            // 生成HTML可视化报告（如果启用）
            if (request.isGenerateHtmlReport()) {
                generateHtmlReport(request, result, outputDir.resolve("backtest_report.html"));
                reportFiles.setHtmlReport("backtest_report.html");
            }
            
            // 生成回测配置文件
            generateBacktestConfigJson(request, outputDir.resolve("backtest_config.json"));
            reportFiles.setBacktestConfigJson("backtest_config.json");
            
            // 生成中文摘要报告
            generateChineseSummaryTxt(result, outputDir.resolve("回测摘要.txt"));
            reportFiles.setChineseSummaryTxt("回测摘要.txt");
            
            long executionTime = System.currentTimeMillis() - startTime;
            
            log.info("回测报告生成完成: {} ({:.1f}秒)", outputDir, executionTime / 1000.0);
            
            return ReportGenerationResult.builder()
                .successful(true)
                .outputDirectory(outputDir.toString())
                .reportFiles(reportFiles)
                .generationTimeMs(executionTime)
                .build();
                
        } catch (Exception e) {
            log.error("回测报告生成失败", e);
            return ReportGenerationResult.builder()
                .successful(false)
                .error("报告生成失败: " + e.getMessage())
                .generationTimeMs(System.currentTimeMillis() - startTime)
                .build();
        }
    }
    
    /**
     * 创建输出目录名称
     * 格式：hk_etf_v1_02800_20250812_082334
     */
    private String createOutputDirectoryName(BacktestRequest request, BacktestResult result) {
        String cleanSymbol = request.getSymbol().replace(".HK", "").replace(".", "");
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String strategy = request.getStrategyName().toLowerCase();
        
        return String.format("hk_%s_v1_%s_%s", strategy, cleanSymbol, timestamp);
    }
    
    /**
     * 创建输出目录
     */
    private Path createOutputDirectory(String basePath, String dirName) throws IOException {
        Path baseDir = basePath != null ? Paths.get(basePath) : Paths.get("./output");
        Path outputDir = baseDir.resolve(dirName);
        
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        
        return outputDir;
    }
    
    /**
     * 生成核心指标摘要JSON
     */
    private void generateSummaryJson(BacktestResult result, Path outputPath) throws IOException {
        SummaryReport summary = SummaryReport.builder()
            .strategyName(result.getStrategy())
            .symbol(result.getSymbol())
            .backtestPeriod(String.format("%s 至 %s", 
                result.getStartTime().toLocalDate(), 
                result.getEndTime().toLocalDate()))
            .backtestDays((int) result.getBacktestDays())
            .initialCapital(result.getInitialCapital())
            .finalEquity(result.getFinalEquity())
            .totalReturn(result.getTotalReturn())
            .returnRate(String.format("%.2f%%", result.getReturnRate()))
            .annualizedReturn(String.format("%.2f%%", result.getAnnualizedReturn()))
            .maxDrawdown(String.format("%.2f%%", result.getMaxDrawdown()))
            .sharpeRatio(result.getSharpeRatio())
            .sortinoRatio(result.getSortinoRatio())
            .calmarRatio(result.getCalmarRatio())
            .totalTrades(result.getTotalTrades())
            .winningTrades(result.getWinningTrades())
            .losingTrades(result.getLosingTrades())
            .winRate(String.format("%.1f%%", result.getWinRate()))
            .profitFactor(result.getProfitFactor())
            .totalCosts(result.getTotalCosts())
            .executionTimeMs(result.getExecutionTimeMs())
            .reportGeneratedAt(result.getReportGeneratedAt())
            .build();
        
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(outputPath.toFile(), summary);
        
        log.debug("已生成摘要文件: {}", outputPath);
    }
    
    /**
     * 生成交易记录CSV
     */
    private void generateTradesCsv(List<Order> trades, Path outputPath) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
            // CSV标题
            writer.println("交易时间,订单ID,股票代码,交易类型,数量,价格,总金额,手续费,滑点,已实现盈亏,状态");
            
            // 交易记录
            for (Order trade : trades) {
                writer.printf("%s,%s,%s,%s,%d,%.4f,%.2f,%.2f,%.2f,%.2f,%s%n",
                    trade.getCreateTime(),
                    trade.getOrderId(),
                    trade.getSymbol(),
                    trade.getSide().toString(),
                    trade.getQuantity(),
                    trade.getPrice(),
                    trade.getPrice().multiply(BigDecimal.valueOf(trade.getQuantity())),
                    trade.getCommission() != null ? trade.getCommission() : BigDecimal.ZERO,
                    trade.getSlippage() != null ? trade.getSlippage() : BigDecimal.ZERO,
                    trade.getRealizedPnl() != null ? trade.getRealizedPnl() : BigDecimal.ZERO,
                    trade.getStatus().toString()
                );
            }
        }
        
        log.debug("已生成交易记录文件: {} ({} 条记录)", outputPath, trades.size());
    }
    
    /**
     * 生成权益曲线CSV
     */
    private void generateEquityCurveCsv(BacktestResult result, Path outputPath) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
            writer.println("时间,权益,收益率,累计收益率,回撤");
            
            List<BigDecimal> equityCurve = result.getEquityCurve();
            List<BigDecimal> drawdownSeries = result.getDrawdownSeries();
            BigDecimal initialCapital = result.getInitialCapital();
            
            for (int i = 0; i < equityCurve.size(); i++) {
                BigDecimal equity = equityCurve.get(i);
                BigDecimal dailyReturn = i > 0 ? 
                    equity.subtract(equityCurve.get(i-1)).divide(equityCurve.get(i-1), 6, java.math.RoundingMode.HALF_UP) :
                    BigDecimal.ZERO;
                BigDecimal cumulativeReturn = equity.subtract(initialCapital).divide(initialCapital, 6, java.math.RoundingMode.HALF_UP);
                BigDecimal drawdown = drawdownSeries != null && i < drawdownSeries.size() ? 
                    drawdownSeries.get(i) : BigDecimal.ZERO;
                
                // 这里时间是模拟的，实际应该从回测结果中获取对应的时间点
                LocalDateTime timestamp = result.getStartTime().plusDays(i);
                
                writer.printf("%s,%.2f,%.6f,%.6f,%.4f%n",
                    timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    equity,
                    dailyReturn,
                    cumulativeReturn,
                    drawdown
                );
            }
        }
        
        log.debug("已生成权益曲线文件: {} ({} 个数据点)", outputPath, result.getEquityCurve().size());
    }
    
    /**
     * 生成每日收益CSV
     */
    private void generateDailyReturnsCsv(BacktestResult result, Path outputPath) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
            writer.println("日期,日收益率,累计收益率");
            
            List<BigDecimal> dailyReturns = result.getDailyReturns();
            if (dailyReturns != null && !dailyReturns.isEmpty()) {
                BigDecimal cumulativeReturn = BigDecimal.ZERO;
                
                for (int i = 0; i < dailyReturns.size(); i++) {
                    BigDecimal dailyReturn = dailyReturns.get(i);
                    cumulativeReturn = cumulativeReturn.add(dailyReturn);
                    
                    LocalDateTime date = result.getStartTime().plusDays(i);
                    writer.printf("%s,%.6f,%.6f%n",
                        date.toLocalDate(),
                        dailyReturn,
                        cumulativeReturn
                    );
                }
            }
        }
        
        log.debug("已生成每日收益文件: {}", outputPath);
    }
    
    /**
     * 生成完整性能指标JSON
     */
    private void generatePerformanceMetricsJson(BacktestResult result, Path outputPath) throws IOException {
        PerformanceMetricsReport metrics = PerformanceMetricsReport.builder()
            .totalReturn(result.getTotalReturn())
            .returnRate(result.getReturnRate())
            .annualizedReturn(result.getAnnualizedReturn())
            .maxDrawdown(result.getMaxDrawdown())
            .sharpeRatio(result.getSharpeRatio())
            .sortinoRatio(result.getSortinoRatio())
            .calmarRatio(result.getCalmarRatio())
            .volatility(result.getVolatility())
            .downsideRisk(result.getDownsideRisk())
            .totalTrades(result.getTotalTrades())
            .winningTrades(result.getWinningTrades())
            .losingTrades(result.getLosingTrades())
            .winRate(result.getWinRate())
            .avgWin(result.getAvgWin())
            .avgLoss(result.getAvgLoss())
            .profitFactor(result.getProfitFactor())
            .avgHoldingDays(result.getAvgHoldingDays())
            .maxConsecutiveWins(result.getMaxConsecutiveWins())
            .maxConsecutiveLosses(result.getMaxConsecutiveLosses())
            .totalCommission(result.getTotalCommission())
            .totalSlippage(result.getTotalSlippage())
            .totalCosts(result.getTotalCosts())
            .monthlyReturns(result.getMonthlyReturns())
            .yearlyReturns(result.getYearlyReturns())
            .build();
        
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(outputPath.toFile(), metrics);
        
        log.debug("已生成性能指标文件: {}", outputPath);
    }
    
    /**
     * 生成策略参数JSON
     */
    private void generateStrategyParametersJson(BacktestRequest request, Path outputPath) throws IOException {
        StrategyParametersReport params = StrategyParametersReport.builder()
            .strategyName(request.getStrategyName())
            .symbol(request.getSymbol())
            .timeframe(request.getTimeframe())
            .initialCapital(request.getInitialCapital())
            .commissionRate(request.getCommissionRate())
            .slippageRate(request.getSlippageRate())
            .stampDutyRate(request.getStampDutyRate())
            .tradingFeeRate(request.getTradingFeeRate())
            .settlementFeeRate(request.getSettlementFeeRate())
            .maxPositionRatio(request.getMaxPositionRatio())
            .maxLossPerTrade(request.getMaxLossPerTrade())
            .build();
        
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(outputPath.toFile(), params);
        
        log.debug("已生成策略参数文件: {}", outputPath);
    }
    
    /**
     * 生成回测配置JSON
     */
    private void generateBacktestConfigJson(BacktestRequest request, Path outputPath) throws IOException {
        BacktestConfigReport config = BacktestConfigReport.builder()
            .backtestStartTime(request.getStartTime())
            .backtestEndTime(request.getEndTime())
            .symbol(request.getSymbol())
            .strategyName(request.getStrategyName())
            .timeframe(request.getTimeframe())
            .initialCapital(request.getInitialCapital())
            .generateDetailedReport(request.isGenerateDetailedReport())
            .generateHtmlReport(request.isGenerateHtmlReport())
            .outputPath(request.getOutputPath())
            .build();
        
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(outputPath.toFile(), config);
        
        log.debug("已生成回测配置文件: {}", outputPath);
    }
    
    /**
     * 生成HTML可视化报告
     */
    private void generateHtmlReport(BacktestRequest request, BacktestResult result, Path outputPath) throws IOException {
        String htmlContent = generateHtmlReportContent(request, result);
        Files.write(outputPath, htmlContent.getBytes("UTF-8"));
        
        log.debug("已生成HTML报告: {}", outputPath);
    }
    
    /**
     * 生成中文摘要文本
     */
    private void generateChineseSummaryTxt(BacktestResult result, Path outputPath) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
            writer.println("===============================");
            writer.println("港股ETF程序化交易回测报告");
            writer.println("===============================");
            writer.println();
            
            writer.println(result.getChineseSummary());
            
            // 目标达成分析
            writer.println("=== 目标达成情况 ===");
            boolean annualReturnTarget = result.getAnnualizedReturn().compareTo(new BigDecimal("15")) >= 0 &&
                                       result.getAnnualizedReturn().compareTo(new BigDecimal("20")) <= 0;
            boolean maxDrawdownTarget = result.getMaxDrawdown().compareTo(new BigDecimal("15")) < 0;
            
            writer.printf("年化收益目标(15-20%%): %s%n", annualReturnTarget ? "✅ 达成" : "❌ 未达成");
            writer.printf("最大回撤目标(<15%%): %s%n", maxDrawdownTarget ? "✅ 达成" : "❌ 超出");
            writer.printf("综合评价: %s%n", 
                annualReturnTarget && maxDrawdownTarget ? "优秀" :
                annualReturnTarget || maxDrawdownTarget ? "良好" : "需要改进");
                
            writer.println();
            writer.println("===============================");
            writer.printf("报告生成时间: %s%n", 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        
        log.debug("已生成中文摘要: {}", outputPath);
    }
    
    /**
     * 生成HTML报告内容
     */
    private String generateHtmlReportContent(BacktestRequest request, BacktestResult result) {
        StringBuilder html = new StringBuilder();
        
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang='zh-CN'>\n");
        html.append("<head>\n");
        html.append("    <meta charset='UTF-8'>\n");
        html.append("    <meta name='viewport' content='width=device-width, initial-scale=1.0'>\n");
        html.append("    <title>港股ETF回测报告 - ").append(result.getSymbol()).append("</title>\n");
        html.append("    <style>\n");
        html.append(getHtmlStyles());
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div class='container'>\n");
        html.append("        <h1>港股ETF程序化交易回测报告</h1>\n");
        html.append("        <div class='header-info'>\n");
        html.append("            <span>策略: ").append(result.getStrategy()).append("</span>\n");
        html.append("            <span>标的: ").append(result.getSymbol()).append("</span>\n");
        html.append("            <span>期间: ").append(result.getStartTime().toLocalDate())
                   .append(" ~ ").append(result.getEndTime().toLocalDate()).append("</span>\n");
        html.append("        </div>\n");
        
        // 核心指标卡片
        html.append("        <div class='metrics-grid'>\n");
        html.append(generateMetricCard("总收益率", String.format("%.2f%%", result.getReturnRate()), "return"));
        html.append(generateMetricCard("年化收益", String.format("%.2f%%", result.getAnnualizedReturn()), "annual"));
        html.append(generateMetricCard("最大回撤", String.format("%.2f%%", result.getMaxDrawdown()), "drawdown"));
        html.append(generateMetricCard("夏普比率", String.format("%.2f", result.getSharpeRatio()), "sharpe"));
        html.append(generateMetricCard("胜率", String.format("%.1f%%", result.getWinRate()), "winrate"));
        html.append(generateMetricCard("总交易", String.valueOf(result.getTotalTrades()), "trades"));
        html.append("        </div>\n");
        
        // TODO: 添加图表（权益曲线、收益分布等）
        html.append("        <div class='chart-placeholder'>\n");
        html.append("            <p>📊 图表功能开发中... 请查看生成的CSV文件获取详细数据</p>\n");
        html.append("        </div>\n");
        
        html.append("        <div class='footer'>\n");
        html.append("            <p>报告生成时间: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>\n");
        html.append("            <p>🤖 由港股程序化交易系统生成</p>\n");
        html.append("        </div>\n");
        html.append("    </div>\n");
        html.append("</body>\n");
        html.append("</html>");
        
        return html.toString();
    }
    
    private String generateMetricCard(String title, String value, String type) {
        return String.format(
            "            <div class='metric-card %s'>\n" +
            "                <h3>%s</h3>\n" +
            "                <p class='metric-value'>%s</p>\n" +
            "            </div>\n", 
            type, title, value);
    }
    
    private String getHtmlStyles() {
        return """
            body { font-family: 'Microsoft YaHei', Arial, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }
            .container { max-width: 1200px; margin: 0 auto; background: white; border-radius: 10px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }
            h1 { text-align: center; color: #2c3e50; padding: 30px 0; margin: 0; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; border-radius: 10px 10px 0 0; }
            .header-info { display: flex; justify-content: space-around; padding: 20px; background: #ecf0f1; }
            .metrics-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; padding: 30px; }
            .metric-card { background: white; border-radius: 8px; padding: 20px; text-align: center; box-shadow: 0 2px 4px rgba(0,0,0,0.1); border-left: 4px solid #3498db; }
            .metric-card.return { border-left-color: #27ae60; }
            .metric-card.annual { border-left-color: #f39c12; }
            .metric-card.drawdown { border-left-color: #e74c3c; }
            .metric-card.sharpe { border-left-color: #9b59b6; }
            .metric-card.winrate { border-left-color: #1abc9c; }
            .metric-card.trades { border-left-color: #34495e; }
            .metric-card h3 { margin: 0 0 10px 0; color: #7f8c8d; font-size: 14px; }
            .metric-value { font-size: 24px; font-weight: bold; margin: 0; color: #2c3e50; }
            .chart-placeholder { padding: 40px; text-align: center; color: #7f8c8d; }
            .footer { text-align: center; padding: 20px; color: #95a5a6; border-top: 1px solid #ecf0f1; }
            """;
    }
    
    // 数据类定义
    
    @lombok.Builder
    @lombok.Data
    public static class ReportGenerationResult {
        private boolean successful;
        private String outputDirectory;
        private ReportFiles reportFiles;
        private String error;
        private long generationTimeMs;
    }
    
    @lombok.Builder
    @lombok.Data
    public static class ReportFiles {
        private Path outputDirectory;
        private String summaryJson;
        private String tradesCsv;
        private String equityCurveCsv;
        private String dailyReturnsCsv;
        private String performanceMetricsJson;
        private String strategyParametersJson;
        private String backtestConfigJson;
        private String htmlReport;
        private String chineseSummaryTxt;
    }
    
    @lombok.Builder
    @lombok.Data
    private static class SummaryReport {
        private String strategyName;
        private String symbol;
        private String backtestPeriod;
        private int backtestDays;
        private BigDecimal initialCapital;
        private BigDecimal finalEquity;
        private BigDecimal totalReturn;
        private String returnRate;
        private String annualizedReturn;
        private String maxDrawdown;
        private BigDecimal sharpeRatio;
        private BigDecimal sortinoRatio;
        private BigDecimal calmarRatio;
        private int totalTrades;
        private int winningTrades;
        private int losingTrades;
        private String winRate;
        private BigDecimal profitFactor;
        private BigDecimal totalCosts;
        private Long executionTimeMs;
        private LocalDateTime reportGeneratedAt;
    }
    
    @lombok.Builder
    @lombok.Data
    private static class PerformanceMetricsReport {
        private BigDecimal totalReturn;
        private BigDecimal returnRate;
        private BigDecimal annualizedReturn;
        private BigDecimal maxDrawdown;
        private BigDecimal sharpeRatio;
        private BigDecimal sortinoRatio;
        private BigDecimal calmarRatio;
        private BigDecimal volatility;
        private BigDecimal downsideRisk;
        private int totalTrades;
        private int winningTrades;
        private int losingTrades;
        private BigDecimal winRate;
        private BigDecimal avgWin;
        private BigDecimal avgLoss;
        private BigDecimal profitFactor;
        private BigDecimal avgHoldingDays;
        private int maxConsecutiveWins;
        private int maxConsecutiveLosses;
        private BigDecimal totalCommission;
        private BigDecimal totalSlippage;
        private BigDecimal totalCosts;
        private java.util.Map<String, BigDecimal> monthlyReturns;
        private java.util.Map<String, BigDecimal> yearlyReturns;
    }
    
    @lombok.Builder
    @lombok.Data
    private static class StrategyParametersReport {
        private String strategyName;
        private String symbol;
        private String timeframe;
        private BigDecimal initialCapital;
        private BigDecimal commissionRate;
        private BigDecimal slippageRate;
        private BigDecimal stampDutyRate;
        private BigDecimal tradingFeeRate;
        private BigDecimal settlementFeeRate;
        private BigDecimal maxPositionRatio;
        private BigDecimal maxLossPerTrade;
    }
    
    @lombok.Builder
    @lombok.Data
    private static class BacktestConfigReport {
        private LocalDateTime backtestStartTime;
        private LocalDateTime backtestEndTime;
        private String symbol;
        private String strategyName;
        private String timeframe;
        private BigDecimal initialCapital;
        private boolean generateDetailedReport;
        private boolean generateHtmlReport;
        private String outputPath;
    }
}