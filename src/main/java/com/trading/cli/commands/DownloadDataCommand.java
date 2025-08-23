package com.trading.cli.commands;

import com.trading.cli.AbstractCommand;
import com.trading.cli.CommandException;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.service.HistoricalDataService;
import com.trading.service.HistoricalDataService.DownloadResult;
import lombok.RequiredArgsConstructor;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class DownloadDataCommand extends AbstractCommand {

    private final HistoricalDataService historicalDataService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getName() {
        return "data:download";
    }

    @Override
    public String getDescription() {
        return "从Futu服务器下载指定时间范围的历史K线数据";
    }

    @Override
    public void execute(String[] args) throws CommandException {
        Options options = createOptions();
        CommandLine cmd = parseArgs(args, options);

        String symbol = cmd.getOptionValue("symbol");
        LocalDate fromDate = LocalDate.parse(cmd.getOptionValue("from"), DATE_FORMATTER);
        LocalDate toDate = cmd.hasOption("to")
                ? LocalDate.parse(cmd.getOptionValue("to"), DATE_FORMATTER)
                : LocalDate.now();

        KLineType klineType = KLineType.valueOf(getOptionValue(cmd, "kline", "K_30MIN").toUpperCase());
        RehabType rehabType = RehabType.valueOf(getOptionValue(cmd, "rehab", "FORWARD").toUpperCase());

        if (cmd.hasOption("clean")) {
            printInfo(String.format("正在清理 %s 从 %s 到 %s 的历史数据...", symbol, fromDate, toDate));
            historicalDataService.deleteHistoricalData(symbol, fromDate, toDate);
            printSuccess("历史数据清理完成");
        }

        printInfo(String.format("准备下载 %s 从 %s 到 %s 的 %s K线数据 (%s)...", 
            symbol, fromDate, toDate, klineType, rehabType));

        try {
            CompletableFuture<DownloadResult> future = historicalDataService.downloadHistoricalData(
                    symbol, fromDate, toDate, klineType, rehabType);

            // 等待下载完成
            DownloadResult result = future.get();

            printSeparator();
            if (result.success()) {
                printSuccess("数据下载成功");
                System.out.printf("  股票代码: %s%n", result.symbol());
                System.out.printf("  下载时段: %s to %s%n", result.startTime(), result.endTime());
                System.out.printf("  获取记录: %d 条%n", result.downloadedCount());
                System.out.printf("  存入数据库: %d 条%n", result.savedCount());
                System.out.printf("  耗时: %.2f 秒%n", result.timeMs() / 1000.0);
            } else {
                printError("数据下载失败");
                System.out.printf("  原因: %s%n", result.errorMessage());
            }
            printSeparator();

        } catch (Exception e) {
            throw new CommandException("下载过程中发生意外错误: " + e.getMessage(), e);
        }
    }

    @Override
    public void printUsage() {
        printUsageHeader("java -jar trading.jar data:download [选项]");
        System.out.println("\n" + getDescription() + ".\n");
        printOptions(createOptions());
        printExamples();
    }

    @Override
    public List<String> getExamples() {
        return List.of(
            "data:download --symbol HK.00700 --from 2024-01-01 --to 2024-01-31",
            "data:download -s HK.00700 -f 2024-01-01 -t 2024-01-31 -k K_DAY -r BACKWARD"
        );
    }

    private Options createOptions() {
        Options options = createBaseOptions();
        options.addOption(createRequiredOption("s", "symbol", "股票代码 (e.g., HK.00700)", true));
        options.addOption(createRequiredOption("f", "from", "开始日期 (YYYY-MM-DD)", true));
        options.addOption(createOption("t", "to", "结束日期 (YYYY-MM-DD), 默认为今天", true));
        options.addOption(createOption("k", "kline", "K线类型 (默认: K_30MIN)", true));
        options.addOption(createOption("r", "rehab", "复权类型 (FORWARD, BACKWARD, NONE), 默认为 FORWARD", true));
        options.addOption(createOption("c", "clean", "下载前清空该时间段的历史数据", false));
        return options;
    }
}
