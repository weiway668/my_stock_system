package com.trading.cli.commands;

import com.trading.backtest.BacktestEngine;
import com.trading.backtest.BacktestRequest;
import com.trading.backtest.BacktestResult;
import com.trading.backtest.BacktestReportGenerator;
import com.trading.cli.AbstractCommand;
import com.trading.cli.CommandException;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.service.HistoricalDataService;
import com.trading.service.HistoricalDataService.DataIntegrityReport;
import com.trading.strategy.TradingStrategy;
import com.trading.strategy.impl.MACDTradingStrategy;
import com.trading.strategy.impl.BollingerBandMeanReversionStrategy;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestCommand extends AbstractCommand {

    private final BacktestEngine backtestEngine;
    private final BacktestReportGenerator reportGenerator;
    private final HistoricalDataService historicalDataService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String getName() {
        return "backtest";
    }

    @Override
    public String getDescription() {
        return "执行策略回测，如果缺少数据会自动下载";
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
                "bt --strategy MACD --symbol 02800.HK --from 2024-01-01 --verbose");
    }

    @Override
    public void execute(String[] args) throws CommandException {
        long startTime = System.currentTimeMillis();

        try {
            Options options = createOptions();
            CommandLine cmd = parseArgs(args, options);

            if (shouldShowHelp(cmd)) {
                printUsage();
                return;
            }

            BacktestRequest request = parseBacktestRequest(cmd);
            request.validate();

            printBacktestConfig(request, isVerbose(cmd));

            // 核心修改：在回测前检查并准备数据
            ensureDataIsAvailable(request);

            String outputPath = request.getOutputPath();
            if (outputPath != null) {
                Path outputDir = Paths.get(outputPath);
                if (!Files.exists(outputDir)) {
                    Files.createDirectories(outputDir);
                    printInfo("已创建输出目录: " + outputDir.toAbsolutePath());
                }
            }

            printInfo("开始执行回测...");
            printSeparator();

            CompletableFuture<BacktestResult> future = backtestEngine.runBacktest(request);
            BacktestResult result = future.get();

            long executionTime = System.currentTimeMillis() - startTime;
            result.setExecutionTimeMs(executionTime);
            result.setReportGeneratedAt(LocalDateTime.now());

            printBacktestResult(result, isVerbose(cmd), isQuiet(cmd));

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

    private void ensureDataIsAvailable(BacktestRequest request) throws CommandException {
        printInfo("正在检查本地历史数据完整性...");
        KLineType klineType = KLineType.fromString(request.getTimeframe());
        
        DataIntegrityReport integrityReport = historicalDataService.validateDataIntegrity(
            request.getSymbol(), 
            request.getStartTime().toLocalDate(), 
            request.getEndTime().toLocalDate(), 
            klineType
        );

        // 如果数据完整度高于95%，则认为无需下载
        boolean needsDownload = integrityReport.completenessRate() < 0.95;

        if (needsDownload) {
            printWarning(String.format("本地数据不完整 (完整度: %.2f%%)，开始自动下载...", integrityReport.completenessRate() * 100));
            try {
                CompletableFuture<HistoricalDataService.DownloadResult> downloadFuture = historicalDataService.downloadHistoricalData(
                    request.getSymbol(), 
                    request.getStartTime().toLocalDate(), 
                    request.getEndTime().toLocalDate(), 
                    klineType, 
                    request.getRehabType() // 使用请求中指定的复权类型
                );

                HistoricalDataService.DownloadResult downloadResult = downloadFuture.get(); // 等待下载完成
                if (downloadResult.success()) {
                    printSuccess(String.format("数据自动下载成功，共获取 %d 条记录。", downloadResult.downloadedCount()));
                } else {
                    throw new CommandException("数据自动下载失败: " + downloadResult.errorMessage());
                }
            } catch (Exception e) {
                throw new CommandException("数据自动下载过程中发生错误: " + e.getMessage(), e);
            }
        } else {
            printSuccess("本地数据完整，无需下载。");
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

    private Options createOptions() {
        Options options = createBaseOptions();

        options.addOption(createRequiredOption("s", "strategy", "策略名称 (MACD/BOLL/VOLUME)", true));
        options.addOption(createRequiredOption("sym", "symbol", "交易标的 (如: 02800.HK)", true));

        options.addOption(createOption("from", "from", "开始日期 (YYYY-MM-DD)", true));
        options.addOption(createOption("to", "to", "结束日期 (YYYY-MM-DD), 默认今天", true));

        options.addOption(createOption("c", "capital", "初始资金 (默认: 100000)", true));
        options.addOption(createOption("tf", "timeframe", "K线周期 (默认: 30m)", true));

        options.addOption(createOption("comm", "commission", "佣金费率 (默认: 0.00025)", true));
        options.addOption(createOption("slip", "slippage", "滑点费率 (默认: 0.0001)", true));

        options.addOption(createOption("o", "output", "输出目录路径", true));
        options.addOption(createOption("", "rehab", "复权类型 (FORWARD/BACKWARD/NONE)，默认FORWARD", true));
        options.addOption(createOption("", "no-html", "不生成HTML报告", false));
        options.addOption(createOption("", "no-files", "不生成文件报告", false));

        return options;
    }

    private BacktestRequest parseBacktestRequest(CommandLine cmd) throws CommandException {
        String strategyName = cmd.getOptionValue("strategy");
        TradingStrategy strategy = createStrategy(strategyName);

        String symbol = cmd.getOptionValue("symbol");

        LocalDateTime startTime = parseDateTime(cmd.getOptionValue("from"));
        LocalDateTime endTime = parseDateTime(getOptionValue(cmd, "to", LocalDateTime.now().toLocalDate().toString()));

        BigDecimal initialCapital = new BigDecimal(getOptionValue(cmd, "capital", "100000"));

        BacktestRequest.BacktestRequestBuilder requestBuilder = BacktestRequest.builder()
                .strategy(strategy)
                .strategyName(strategyName)
                .symbol(symbol)
                .startTime(startTime)
                .endTime(endTime)
                .initialCapital(initialCapital)
                .timeframe(getOptionValue(cmd, "timeframe", "30m"));

        if (cmd.hasOption("commission")) {
            requestBuilder.commissionRate(new BigDecimal(cmd.getOptionValue("commission")));
        }
        if (cmd.hasOption("slippage")) {
            requestBuilder.slippageRate(new BigDecimal(cmd.getOptionValue("slippage")));
        }

        if (cmd.hasOption("output")) {
            requestBuilder.outputPath(cmd.getOptionValue("output"));
        }

        requestBuilder.generateHtmlReport(!cmd.hasOption("no-html"));
        requestBuilder.generateDetailedReport(!cmd.hasOption("no-files"));

        BacktestRequest request = BacktestRequest.createHKStockRequest(symbol, startTime, endTime, initialCapital);

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
        if (cmd.hasOption("rehab")) {
            request.setRehabType(RehabType.valueOf(cmd.getOptionValue("rehab").toUpperCase()));
        }
        request.setStrategy(strategy);
        request.setStrategyName(strategyName);
        request.setTimeframe(userRequest.getTimeframe());
        request.setGenerateHtmlReport(userRequest.isGenerateHtmlReport());
        request.setGenerateDetailedReport(userRequest.isGenerateDetailedReport());

        return request;
    }

    private TradingStrategy createStrategy(String strategyName) throws CommandException {
        switch (strategyName.toUpperCase()) {
            case "MACD":
                return new MACDTradingStrategy();
            case "BOLL":
                return new BollingerBandMeanReversionStrategy();
            case "VOLUME":
                throw CommandException.invalidArgument(getName(), null, "VOLUME策略暂未实现");
            default:
                throw CommandException.invalidArgument(getName(), null,
                        "不支持的策略: " + strategyName + ", 支持的策略: MACD, BOLL, VOLUME");
        }
    }

    private LocalDateTime parseDateTime(String dateStr) throws CommandException {
        if (dateStr == null) {
            throw CommandException.invalidArgument(getName(), null, "日期不能为空");
        }

        try {
            if (dateStr.length() == 10) {
                return java.time.LocalDate.parse(dateStr, DATE_FORMATTER).atStartOfDay();
            } else {
                return LocalDateTime.parse(dateStr, DATETIME_FORMATTER);
            }
        } catch (DateTimeParseException e) {
            throw CommandException.invalidArgument(getName(), null,
                    "无效的日期格式: " + dateStr + ", 请使用 YYYY-MM-DD 或 YYYY-MM-DD HH:mm:ss 格式");
        }
    }

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

    private void printBacktestResult(BacktestResult result, boolean verbose, boolean quiet) {
        if (quiet) {
            System.out.printf("%.2f%%,%.2f%%,%.2f,%.1f%%,%d%n",
                    result.getReturnRate(), result.getMaxDrawdown(),
                    result.getSharpeRatio(), result.getWinRate(), result.getTotalTrades());
            return;
        }

        if (!result.isSuccessful()) {
            printError("回测失败: " + (result.getError() != null ? result.getError() : "未知错误"));
            return;
        }

        printTableHeader("回测结果");

        if (result.getStartTime() != null && result.getEndTime() != null) {
            System.out.printf("回测期间: %s 至 %s (%d天)%n",
                    result.getStartTime().toLocalDate(),
                    result.getEndTime().toLocalDate(),
                    result.getBacktestDays());
        }
        System.out.printf("执行耗时: %.1f秒%n", result.getExecutionTimeMs() / 1000.0);
        System.out.println();

        printSubHeader("📈 收益指标");
        System.out.printf("初始资金: %s¥%,.2f%s%n", ANSI_CYAN, result.getInitialCapital(), ANSI_RESET);
        System.out.printf("最终权益(平仓后): %s¥%,.2f%s%n", ANSI_CYAN, result.getFinalEquity(), ANSI_RESET);
        System.out.printf("绝对收益: %s¥%,.2f%s%n",
                result.getTotalReturn().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED,
                result.getTotalReturn(), ANSI_RESET);
        System.out.printf("总收益率: %s%.2f%%%s%n",
                result.getReturnRate().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED,
                result.getReturnRate().multiply(BigDecimal.valueOf(100)), ANSI_RESET);
        System.out.printf("年化收益: %s%.2f%%%s%n",
                result.getAnnualizedReturn().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED,
                result.getAnnualizedReturn().multiply(BigDecimal.valueOf(100)), ANSI_RESET);

        if (result.getFinalEquityMarkToMarket() != null && result.getUnrealizedPnl() != null) {
            System.out.println("---");
            System.out.printf("期末持仓权益(参考): %s¥%,.2f%s%n", ANSI_YELLOW, result.getFinalEquityMarkToMarket(), ANSI_RESET);
            System.out.printf("期末浮动盈亏(参考): %s¥%,.2f%s%n",
                    result.getUnrealizedPnl().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_YELLOW : ANSI_RED,
                    result.getUnrealizedPnl(), ANSI_RESET);
        }
        System.out.println();

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

        if (verbose && result.getTotalCosts() != null) {
            printSubHeader("💰 成本分析");
            System.out.printf("总佣金: ¥%.2f%n", result.getTotalCommission());
            System.out.printf("总滑点: ¥%.2f%n", result.getTotalSlippage());
            if (result.getTotalStampDuty() != null) {
                System.out.printf("印花税: ¥%.2f%n", result.getTotalStampDuty());
            }
            System.out.printf("总成本: ¥%.2f%n", result.getTotalCosts());
            System.out.printf("成本占收益比: %.2f%%%n",
                    result.getTotalReturn().compareTo(BigDecimal.ZERO) > 0
                            ? result.getTotalCosts().divide(result.getTotalReturn(), 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                            : BigDecimal.ZERO);
            System.out.println();
        }

        printSubHeader("🎯 目标分析");
        boolean annualReturnTarget = result.getAnnualizedReturn().compareTo(new BigDecimal("15")) >= 0 &&
                result.getAnnualizedReturn().compareTo(new BigDecimal("20")) <= 0;
        boolean maxDrawdownTarget = result.getMaxDrawdown().compareTo(new BigDecimal("15")) < 0;

        System.out.printf("年化收益目标(15-20%%): %s%n",
                annualReturnTarget ? "✅ 达成" : "❌ 未达成");
        System.out.printf("最大回撤目标(<15%%): %s%n",
                maxDrawdownTarget ? "✅ 达成" : "❌ 超出");
        System.out.printf("综合评价: %s%n",
                annualReturnTarget && maxDrawdownTarget ? ANSI_GREEN + "优秀" + ANSI_RESET
                        : annualReturnTarget || maxDrawdownTarget ? ANSI_YELLOW + "良好" + ANSI_RESET
                                : ANSI_RED + "需要改进" + ANSI_RESET);

        printSeparator();
        printSuccess("回测完成");
    }

    private void printSubHeader(String title) {
        System.out.println(ANSI_BOLD + title + ANSI_RESET);
    }

    private void generateReports(BacktestRequest request, BacktestResult result) {
        try {
            printInfo("正在生成详细报告...");

            BacktestReportGenerator.ReportGenerationResult reportResult = reportGenerator.generateReportPackage(request,
                    result);

            if (reportResult.isSuccessful()) {
                printSuccess("报告生成完成");
                printInfo("输出目录: " + reportResult.getOutputDirectory());
                printInfo("生成耗时: " + String.format("%.1f秒", reportResult.getGenerationTimeMs() / 1000.0));

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
                    System.out
                            .println("  ├── " + reportResult.getReportFiles().getPerformanceMetricsJson() + " (性能指标)");
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
