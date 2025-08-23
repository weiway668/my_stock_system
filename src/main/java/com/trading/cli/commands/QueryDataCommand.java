package com.trading.cli.commands;

import com.trading.cli.AbstractCommand;
import com.trading.cli.CommandException;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.service.HistoricalDataService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class QueryDataCommand extends AbstractCommand {

    private final HistoricalDataService historicalDataService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getName() {
        return "data:query";
    }

    @Override
    public String getDescription() {
        return "查询数据库中特定时间范围的原始K线数据";
    }

    @Override
    public void execute(String[] args) throws CommandException {
        Options options = createOptions();
        CommandLine cmd = parseArgs(args, options);

        String symbol = cmd.getOptionValue("symbol");
        LocalDate fromDate = LocalDate.parse(cmd.getOptionValue("from"), DATE_FORMATTER);
        
        LocalDate toDate = cmd.hasOption("to")
                ? LocalDate.parse(cmd.getOptionValue("to"), DATE_FORMATTER)
                : fromDate;

        printInfo(String.format("正在查询 %s 从 %s 到 %s 的数据...", symbol, fromDate, toDate));
        printSeparator();

        List<HistoricalKLineEntity> records = historicalDataService.getRecordsForDateRange(symbol, fromDate, toDate);

        if (records.isEmpty()) {
            printWarning("在指定时间范围内未找到任何记录。");
            return;
        }

        printTableHeader(String.format("查询结果 (%d 条记录)", records.size()));
        System.out.printf("%-25s | %-10s | %-10s | %-10s | %-10s | %-15s%n",
                "时间戳", "开盘", "收盘", "最高", "最低", "成交量");
        printSeparator();

        for (HistoricalKLineEntity record : records) {
            System.out.printf("%-25s | %-10.2f | %-10.2f | %-10.2f | %-10.2f | %-15d%n",
                    record.getTimestamp(),
                    record.getOpen(),
                    record.getClose(),
                    record.getHigh(),
                    record.getLow(),
                    record.getVolume());
        }
        printSeparator();
    }

    @Override
    public List<String> getExamples() {
        return List.of(
            "data:query --symbol HK.00700 --from 2024-01-03 --to 2024-01-05",
            "data:query --symbol HK.00700 --from 2024-01-04"
        );
    }

    private Options createOptions() {
        Options options = createBaseOptions();
        options.addOption(createRequiredOption("s", "symbol", "股票代码 (e.g., HK.00700)", true));
        options.addOption(createRequiredOption("f", "from", "开始日期 (YYYY-MM-DD)", true));
        options.addOption(createOption("t", "to", "结束日期 (YYYY-MM-DD), 默认为--from日期", true));
        return options;
    }

    @Override
    public void printUsage() {
        printUsageHeader("java -jar trading.jar data:query [选项]");
        System.out.println("\n" + getDescription() + ".\n");
        printOptions(createOptions());
        printExamples();
    }
}