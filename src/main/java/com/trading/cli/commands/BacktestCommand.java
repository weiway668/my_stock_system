package com.trading.cli.commands;

import com.trading.backtest.BacktestEngine;
import com.trading.backtest.BacktestRequest;
import com.trading.backtest.BacktestResult;
import com.trading.backtest.BacktestReportGenerator;
import com.trading.cli.AbstractCommand;
import com.trading.cli.CommandException;
import com.trading.strategy.TradingStrategy;
import com.trading.strategy.impl.MACDTradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 回测命令
 * 执行策略回测并生成详细报告
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestCommand extends AbstractCommand {
    
    private final BacktestEngine backtestEngine;
    private final BacktestReportGenerator reportGenerator;
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    public String getName() {
        return "backtest";
    }
    
    @Override
    public String getDescription() {
        return "执行策略回测";
    }
    
    @Override
    public List<String> getAliases() {
        return List.of("bt", "test");
    }
    
    @Override
    public List<String> getExamples() {
        return List.of(
            "backtest --strategy MACD --symbol 02800.HK --from 2024-01-01 --to 2024-12-31",
            "backtest -s MACD -sym 02800.HK --capital 100000 --output ./output",
            "bt --strategy MACD --symbol 02800.HK --from 2024-01-01 --verbose"
        );
    }
    
    @Override
    public void execute(String[] args) throws CommandException {
        long startTime = System.currentTimeMillis();
        
        try {
            // 创建选项
            Options options = createOptions();
            CommandLine cmd = parseArgs(args, options);
            
            // 检查是否显示帮助
            if (shouldShowHelp(cmd)) {
                printUsage();
                return;
            }
            
            // 解析并验证参数
            BacktestRequest request = parseBacktestRequest(cmd);
            request.validate();
            
            // 显示回测配置
            printBacktestConfig(request, isVerbose(cmd));
            
            // 确认输出目录
            String outputPath = request.getOutputPath();
            if (outputPath != null) {
                Path outputDir = Paths.get(outputPath);
                if (!Files.exists(outputDir)) {
                    Files.createDirectories(outputDir);
                    printInfo("已创建输出目录: " + outputDir.toAbsolutePath());
                }
            }
            
            // 执行回测
            printInfo("开始执行回测...");
            printSeparator();
            
            CompletableFuture<BacktestResult> future = backtestEngine.runBacktest(request);
            BacktestResult result = future.get();
            
            long executionTime = System.currentTimeMillis() - startTime;
            result.setExecutionTimeMs(executionTime);
            result.setReportGeneratedAt(LocalDateTime.now());
            
            // 显示结果
            printBacktestResult(result, isVerbose(cmd), isQuiet(cmd));
            
            // 生成报告文件（如果需要）
            if (request.isGenerateDetailedReport() && outputPath != null) {
                generateReports(request, result);
            }
            
        } catch (CommandException e) {
            throw e;
        } catch (Exception e) {
            log.error("回测执行失败", e);
            throw CommandException.executionFailed(getName(), args, e.getMessage(), e);
        }
    }
    
    @Override
    public void printUsage() {
        printUsageHeader("java -jar trading.jar backtest [选项]");
        
        System.out.println(ANSI_BOLD + "描述:" + ANSI_RESET);
        System.out.println("  执行交易策略的历史数据回测，生成详细的性能分析报告。");
        System.out.println("  支持多种策略和交易标的，输出专业级回测结果。");
        System.out.println();
        
        printOptions(createOptions());
        printExamples();
        
        System.out.println(ANSI_BOLD + "支持的策略:" + ANSI_RESET);
        System.out.println("  MACD        MACD趋势跟踪策略 (12/26/9配置)");
        System.out.println("  BOLL        布林带均值回归策略");
        System.out.println("  VOLUME      成交量突破策略");
        System.out.println();
        
        System.out.println(ANSI_BOLD + "输出文件:" + ANSI_RESET);
        System.out.println("  当指定 --output 选项时，将生成以下文件:");
        System.out.println("  ├── summary.json              核心指标摘要");
        System.out.println("  ├── trades.csv                详细交易记录");
        System.out.println("  ├── equity_curve.csv          权益曲线数据");
        System.out.println("  ├── performance_metrics.json  完整性能分析");
        System.out.println("  └── backtest_report.html      可视化HTML报告");
    }
    
    /**
     * 创建命令选项
     */
    private Options createOptions() {
        Options options = createBaseOptions();
        
        // 必需参数
        options.addOption(createRequiredOption("s", "strategy", "策略名称 (MACD/BOLL/VOLUME)", true));
        options.addOption(createRequiredOption("sym", "symbol", "交易标的 (如: 02800.HK)", true));
        
        // 时间参数
        options.addOption(createOption("from", "from", "开始日期 (YYYY-MM-DD)", true));
        options.addOption(createOption("to", "to", "结束日期 (YYYY-MM-DD), 默认今天", true));
        
        // 回测配置
        options.addOption(createOption("c", "capital", "初始资金 (默认: 100000)", true));
        options.addOption(createOption("tf", "timeframe", "K线周期 (默认: 30m)", true));
        
        // 费用配置
        options.addOption(createOption("comm", "commission", "佣金费率 (默认: 0.00025)", true));
        options.addOption(createOption("slip", "slippage", "滑点费率 (默认: 0.0001)", true));
        
        // 输出配置
        options.addOption(createOption("o", "output", "输出目录路径", true));
        options.addOption(createOption("", "no-html", "不生成HTML报告", false));
        options.addOption(createOption("", "no-files", "不生成文件报告", false));
        
        return options;
    }
    
    /**
     * 解析回测请求参数
     */
    private BacktestRequest parseBacktestRequest(CommandLine cmd) throws CommandException {
        // 策略
        String strategyName = cmd.getOptionValue("strategy");
        TradingStrategy strategy = createStrategy(strategyName);
        
        // 交易标的
        String symbol = cmd.getOptionValue("symbol");
        
        // 时间范围
        LocalDateTime startTime = parseDateTime(cmd.getOptionValue("from"));
        LocalDateTime endTime = parseDateTime(getOptionValue(cmd, "to", LocalDateTime.now().toLocalDate().toString()));
        
        // 初始资金
        BigDecimal initialCapital = new BigDecimal(getOptionValue(cmd, "capital", "100000"));
        
        // 创建请求
        BacktestRequest.BacktestRequestBuilder requestBuilder = BacktestRequest.builder()
            .strategy(strategy)
            .strategyName(strategyName)
            .symbol(symbol)
            .startTime(startTime)
            .endTime(endTime)
            .initialCapital(initialCapital)
            .timeframe(getOptionValue(cmd, "timeframe", "30m"));
        
        // 费用设置
        if (cmd.hasOption("commission")) {
            requestBuilder.commissionRate(new BigDecimal(cmd.getOptionValue("commission")));
        }
        if (cmd.hasOption("slippage")) {
            requestBuilder.slippageRate(new BigDecimal(cmd.getOptionValue("slippage")));
        }
        
        // 输出设置
        if (cmd.hasOption("output")) {
            String outputPath = cmd.getOptionValue("output");
            requestBuilder.outputPath(outputPath);
        }
        
        requestBuilder.generateHtmlReport(!cmd.hasOption("no-html"));
        requestBuilder.generateDetailedReport(!cmd.hasOption("no-files"));
        
        // 使用港股默认费用结构
        BacktestRequest request = BacktestRequest.createHKStockRequest(symbol, startTime, endTime, initialCapital);
        
        // 应用用户自定义配置
        BacktestRequest userRequest = requestBuilder.build();
        if (userRequest.getCommissionRate() != null) {
            request.setCommissionRate(userRequest.getCommissionRate());
        }
        if (userRequest.getSlippageRate() != null) {
            request.setSlippageRate(userRequest.getSlippageRate());
        }
        if (userRequest.getOutputPath() != null) {
            request.setOutputPath(userRequest.getOutputPath());
        }
        request.setStrategy(strategy);
        request.setStrategyName(strategyName);
        request.setTimeframe(userRequest.getTimeframe());
        request.setGenerateHtmlReport(userRequest.isGenerateHtmlReport());
        request.setGenerateDetailedReport(userRequest.isGenerateDetailedReport());
        
        return request;
    }
    
    /**
     * 创建策略实例
     */
    private TradingStrategy createStrategy(String strategyName) throws CommandException {
        switch (strategyName.toUpperCase()) {
            case "MACD":
                return new MACDTradingStrategy();
            case "BOLL":
                // TODO: 实现BOLL策略
                throw CommandException.invalidArgument(getName(), null, "BOLL策略暂未实现");
            case "VOLUME":
                // TODO: 实现VOLUME策略
                throw CommandException.invalidArgument(getName(), null, "VOLUME策略暂未实现");
            default:
                throw CommandException.invalidArgument(getName(), null, 
                    "不支持的策略: " + strategyName + "，支持的策略: MACD, BOLL, VOLUME");
        }
    }
    
    /**
     * 解析日期时间
     */
    private LocalDateTime parseDateTime(String dateStr) throws CommandException {
        if (dateStr == null) {
            throw CommandException.invalidArgument(getName(), null, "日期不能为空");
        }
        
        try {
            // 尝试解析日期格式 (YYYY-MM-DD)
            if (dateStr.length() == 10) {
                return java.time.LocalDate.parse(dateStr, DATE_FORMATTER).atStartOfDay();
            }
            // 尝试解析日期时间格式 (YYYY-MM-DD HH:mm:ss)
            else {
                return LocalDateTime.parse(dateStr, DATETIME_FORMATTER);
            }
        } catch (DateTimeParseException e) {
            throw CommandException.invalidArgument(getName(), null, 
                "无效的日期格式: " + dateStr + "，请使用 YYYY-MM-DD 或 YYYY-MM-DD HH:mm:ss 格式");
        }
    }
    
    /**
     * 打印回测配置
     */
    private void printBacktestConfig(BacktestRequest request, boolean verbose) {
        printTableHeader("回测配置");
        
        System.out.printf("策略名称: %s%n", request.getStrategyName());
        System.out.printf("交易标的: %s%n", request.getSymbol());
        System.out.printf("时间范围: %s 至 %s%n", 
            request.getStartTime().toLocalDate(),
            request.getEndTime().toLocalDate());
        System.out.printf("初始资金: ¥%,.2f%n", request.getInitialCapital());
        System.out.printf("K线周期: %s%n", request.getTimeframe());
        
        if (verbose) {
            System.out.printf("佣金费率: %.4f%%%n", request.getCommissionRate().multiply(BigDecimal.valueOf(100)));
            System.out.printf("滑点费率: %.4f%%%n", request.getSlippageRate().multiply(BigDecimal.valueOf(100)));
            System.out.printf("印花税率: %.4f%%%n", request.getStampDutyRate().multiply(BigDecimal.valueOf(100)));
            
            if (request.getOutputPath() != null) {
                System.out.printf("输出目录: %s%n", request.getOutputPath());
            }
        }
        
        printSeparator();
    }
    
    /**
     * 打印回测结果
     */
    private void printBacktestResult(BacktestResult result, boolean verbose, boolean quiet) {
        if (quiet) {
            // 静默模式只输出关键指标
            System.out.printf("%.2f%%,%.2f%%,%.2f,%.1f%%,%d%n",
                result.getReturnRate(), result.getMaxDrawdown(), 
                result.getSharpeRatio(), result.getWinRate(), result.getTotalTrades());
            return;
        }
        
        if (!result.isSuccessful()) {
            printError("回测失败: " + result.getError());
            return;
        }
        
        printTableHeader("回测结果");
        
        // 基本信息
        System.out.printf("回测期间: %s 至 %s (%d天)%n", 
            result.getStartTime().toLocalDate(),
            result.getEndTime().toLocalDate(), 
            result.getBacktestDays());
        System.out.printf("执行耗时: %.1f秒%n", result.getExecutionTimeMs() / 1000.0);
        System.out.println();
        
        // 收益指标
        printSubHeader("📈 收益指标");
        System.out.printf("初始资金: %s¥%,.2f%s%n", ANSI_CYAN, result.getInitialCapital(), ANSI_RESET);
        System.out.printf("最终权益: %s¥%,.2f%s%n", ANSI_CYAN, result.getFinalEquity(), ANSI_RESET);
        System.out.printf("绝对收益: %s¥%,.2f%s%n", 
            result.getTotalReturn().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED,
            result.getTotalReturn(), ANSI_RESET);
        System.out.printf("总收益率: %s%.2f%%%s%n",
            result.getReturnRate().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED,
            result.getReturnRate(), ANSI_RESET);
        System.out.printf("年化收益: %s%.2f%%%s%n",
            result.getAnnualizedReturn().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED,
            result.getAnnualizedReturn(), ANSI_RESET);
        System.out.println();
        
        // 风险指标
        printSubHeader("⚠️ 风险指标");
        System.out.printf("最大回撤: %s%.2f%%%s%n", ANSI_RED, result.getMaxDrawdown(), ANSI_RESET);
        System.out.printf("夏普比率: %.2f%n", result.getSharpeRatio());
        if (result.getSortinoRatio() != null) {
            System.out.printf("索提诺比率: %.2f%n", result.getSortinoRatio());
        }
        if (result.getCalmarRatio() != null) {
            System.out.printf("卡尔马比率: %.2f%n", result.getCalmarRatio());
        }
        System.out.println();
        
        // 交易统计
        printSubHeader("📊 交易统计");
        System.out.printf("总交易次数: %d%n", result.getTotalTrades());
        if (result.getTotalTrades() > 0) {
            System.out.printf("盈利交易: %s%d%s%n", ANSI_GREEN, result.getWinningTrades(), ANSI_RESET);
            System.out.printf("亏损交易: %s%d%s%n", ANSI_RED, result.getLosingTrades(), ANSI_RESET);
            System.out.printf("胜率: %.1f%%%n", result.getWinRate());
            System.out.printf("平均盈利: %s¥%.2f%s%n", ANSI_GREEN, result.getAvgWin(), ANSI_RESET);
            System.out.printf("平均亏损: %s¥%.2f%s%n", ANSI_RED, result.getAvgLoss(), ANSI_RESET);
            System.out.printf("盈亏比: %.2f%n", result.getProfitFactor());
        }
        System.out.println();
        
        // 成本分析
        if (verbose && result.getTotalCosts() != null) {
            printSubHeader("💰 成本分析");
            System.out.printf("总佣金: ¥%.2f%n", result.getTotalCommission());
            System.out.printf("总滑点: ¥%.2f%n", result.getTotalSlippage());
            if (result.getTotalStampDuty() != null) {
                System.out.printf("印花税: ¥%.2f%n", result.getTotalStampDuty());
            }
            System.out.printf("总成本: ¥%.2f%n", result.getTotalCosts());
            System.out.printf("成本占收益比: %.2f%%%n", 
                result.getTotalReturn().compareTo(BigDecimal.ZERO) > 0 ?
                result.getTotalCosts().divide(result.getTotalReturn(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
            System.out.println();
        }
        
        // 目标达成情况
        printSubHeader("🎯 目标分析");
        boolean annualReturnTarget = result.getAnnualizedReturn().compareTo(new BigDecimal("15")) >= 0 &&
                                   result.getAnnualizedReturn().compareTo(new BigDecimal("20")) <= 0;
        boolean maxDrawdownTarget = result.getMaxDrawdown().compareTo(new BigDecimal("15")) < 0;
        
        System.out.printf("年化收益目标(15-20%%): %s%n", 
            annualReturnTarget ? "✅ 达成" : "❌ 未达成");
        System.out.printf("最大回撤目标(<15%%): %s%n", 
            maxDrawdownTarget ? "✅ 达成" : "❌ 超出");
        System.out.printf("综合评价: %s%n", 
            annualReturnTarget && maxDrawdownTarget ? 
            ANSI_GREEN + "优秀" + ANSI_RESET : 
            annualReturnTarget || maxDrawdownTarget ? 
            ANSI_YELLOW + "良好" + ANSI_RESET :
            ANSI_RED + "需要改进" + ANSI_RESET);
        
        printSeparator();
        printSuccess("回测完成");
    }
    
    private void printSubHeader(String title) {
        System.out.println(ANSI_BOLD + title + ANSI_RESET);
    }
    
    private void generateReports(BacktestRequest request, BacktestResult result) {
        try {
            printInfo("正在生成详细报告...");
            
            BacktestReportGenerator.ReportGenerationResult reportResult = 
                reportGenerator.generateReportPackage(request, result);
            
            if (reportResult.isSuccessful()) {
                printSuccess("报告生成完成");
                printInfo("输出目录: " + reportResult.getOutputDirectory());
                printInfo("生成耗时: " + String.format("%.1f秒", reportResult.getGenerationTimeMs() / 1000.0));
                
                // 显示生成的文件列表
                System.out.println("\n📄 生成的文件:");
                if (reportResult.getReportFiles().getSummaryJson() != null) {
                    System.out.println("  ├── " + reportResult.getReportFiles().getSummaryJson() + " (核心指标摘要)");
                }
                if (reportResult.getReportFiles().getTradesCsv() != null) {
                    System.out.println("  ├── " + reportResult.getReportFiles().getTradesCsv() + " (详细交易记录)");
                }
                if (reportResult.getReportFiles().getEquityCurveCsv() != null) {
                    System.out.println("  ├── " + reportResult.getReportFiles().getEquityCurveCsv() + " (权益曲线)");
                }
                if (reportResult.getReportFiles().getPerformanceMetricsJson() != null) {
                    System.out.println("  ├── " + reportResult.getReportFiles().getPerformanceMetricsJson() + " (性能指标)");
                }
                if (reportResult.getReportFiles().getHtmlReport() != null) {
                    System.out.println("  ├── " + reportResult.getReportFiles().getHtmlReport() + " (HTML可视化报告)");
                }
                if (reportResult.getReportFiles().getChineseSummaryTxt() != null) {
                    System.out.println("  └── " + reportResult.getReportFiles().getChineseSummaryTxt() + " (中文摘要)");
                }
                
            } else {
                printError("报告生成失败: " + reportResult.getError());
            }
            
        } catch (Exception e) {
            printError("报告生成过程中出现异常: " + e.getMessage());
            log.error("报告生成异常", e);
        }
    }
}