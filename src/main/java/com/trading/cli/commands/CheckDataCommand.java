package com.trading.cli.commands;

import com.trading.cli.AbstractCommand;
import com.trading.cli.CommandException;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.service.HistoricalDataService;
import com.trading.service.HistoricalDataService.DataIntegrityReport;
import com.trading.service.HistoricalDataService.DataQualityReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据质量与完整性检查命令
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckDataCommand extends AbstractCommand {

    private final HistoricalDataService historicalDataService;

    @Override
    public String getName() {
        return "data:check";
    }

    @Override
    public String getDescription() {
        return "检查指定股票的数据质量和完整性";
    }

    @Override
    public List<String> getAliases() {
        return List.of("check-data");
    }

    @Override
    public List<String> getExamples() {
        return List.of(
            "data:check --symbol HK.00700",
            "data:check --symbol HK.9988 --start 2023-01-01"
        );
    }

    @Override
    public void execute(String[] args) throws CommandException {
        try {
            Options options = createOptions();
            CommandLine cmd = parseArgs(args, options);

            if (shouldShowHelp(cmd) || !cmd.hasOption("symbol")) {
                printUsage();
                return;
            }

            String symbol = cmd.getOptionValue("symbol");
            String startDateStr = cmd.getOptionValue("start", LocalDate.now().minusYears(1).toString());
            LocalDate startDate = LocalDate.parse(startDateStr);
            LocalDate endDate = LocalDate.now();
            KLineType kLineType = KLineType.K_DAY; // 默认检查日K线

            printInfo(String.format("开始对股票 %s 进行数据分析 (时间范围: %s to %s, K线类型: %s)...",
                    symbol, startDate, endDate, kLineType));
            printSeparator();

            // 1. 数据质量报告
            printTableHeader("数据质量报告 (Data Quality Report)");
            DataQualityReport qualityReport = historicalDataService.getDataQualityReport(symbol, kLineType);
            if (qualityReport.totalRecords() == 0) {
                printWarning("未找到任何本地数据，无法生成质量报告。");
            } else {
                System.out.println(qualityReport.getChineseSummary());
            }
            printSeparator();

            // 2. 数据完整性报告
            printTableHeader("数据完整性报告 (Data Integrity Report)");
            DataIntegrityReport integrityReport = historicalDataService.validateDataIntegrity(symbol, startDate, endDate, kLineType);
            System.out.println(integrityReport.getChineseSummary());

            printSeparator();
            printSuccess("数据分析完成。");

        } catch (Exception e) {
            log.error("数据分析命令执行失败", e);
            throw CommandException.executionFailed(getName(), args, e.getMessage(), e);
        }
    }

    @Override
    public void printUsage() {
        printUsageHeader("java -jar trading.jar data:check [选项]");
        System.out.println(ANSI_BOLD + "描述:" + ANSI_RESET);
        System.out.println("  对指定股票的本地历史数据进行全面的质量和完整性检查。");
        System.out.println();
        printOptions(createOptions());
        printExamples();
    }

    protected Options createOptions() {
        Options options = createBaseOptions();
        options.addOption(createOption("sym", "symbol", "要检查的股票代码 (必需)", true));
        options.addOption(createOption("s", "start", "完整性检查的开始日期 (格式: YYYY-MM-DD, 默认一年前)", true));
        return options;
    }
}
