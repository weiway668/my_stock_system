package com.trading.cli.commands;

import java.io.File;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.springframework.stereotype.Component;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import com.trading.cli.AbstractCommand;
import com.trading.cli.CommandException;
import com.trading.common.utils.BigDecimalUtils;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.service.HistoricalDataService;
import com.trading.strategy.TechnicalAnalysisService;

import cn.hutool.core.util.NumberUtil;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class CalculateIndicatorCommand extends AbstractCommand {

    private final HistoricalDataService historicalDataService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int PREHEAT_PERIOD = 100; // 预热期，多获取100个单位的数据

    @Override
    public String getName() {
        return "indicator:calc";
    }

    @Override
    public String getDescription() {
        return "计算并显示指定股票的核心技术指标(自动包含预热数据)";
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

        KLineType klineType = KLineType.valueOf(getOptionValue(cmd, "kline", "K_DAY").toUpperCase());
        RehabType rehabType = RehabType.valueOf(getOptionValue(cmd, "rehab", "FORWARD").toUpperCase());
        int tail = Integer.parseInt(getOptionValue(cmd, "tail", "10"));

        // 计算预热期开始日期
        LocalDate preheatFromDate = fromDate.minusDays(PREHEAT_PERIOD);

        printInfo(String.format("正在为 %s 从 %s 到 %s 计算 %s K线的技术指标 (%s)...",
                symbol, fromDate, toDate, klineType, rehabType));
        printInfo(String.format("自动包含从 %s 开始的 %d 天预热数据。", preheatFromDate, PREHEAT_PERIOD));

        try {
            // 1. 获取包含预热期的历史数据
            List<HistoricalKLineEntity> klinesWithPreheat = historicalDataService.getHistoricalKLine(
                    symbol, preheatFromDate, toDate, klineType, rehabType);
            if (klinesWithPreheat.isEmpty()) {
                printWarning("未找到任何历史数据，无法计算指标。");
                return;
            }
            printInfo(String.format("获取到 %d 条K线数据 (包含预热数据)", klinesWithPreheat.size()));

            // 2. 转换为TA4J的BarSeries
            BaseBarSeries series = new BaseBarSeries(symbol);
            Duration barDuration = getBarDuration(klineType);
            for (HistoricalKLineEntity kline : klinesWithPreheat) {
                series.addBar(new BaseBar(
                        barDuration,
                        kline.getTimestamp().atZone(ZoneId.systemDefault()),
                        kline.getOpen().doubleValue(),
                        kline.getHigh().doubleValue(),
                        kline.getLow().doubleValue(),
                        kline.getClose().doubleValue(),
                        kline.getVolume().doubleValue()
                ));
            }

            // 3. 计算技术指标
            List<TechnicalIndicators> indicators = technicalAnalysisService.calculateIndicators(series);

            // 4. 打印结果到控制台
            printIndicators(klinesWithPreheat, indicators, tail, fromDate);

            // 5. 如果指定了输出路径，则将全量结果写入文件
            if (cmd.hasOption("output")) {
                String outputPath = cmd.getOptionValue("output");
                if (outputPath == null || outputPath.trim().isEmpty()) {
                    // 用户提供了--output开关但没有提供值，生成默认路径
                    outputPath = generateDefaultOutputPath(symbol, klineType, toDate);
                }
                // 确保输出目录存在
                File outputFile = new File(outputPath);
                File parentDir = outputFile.getParentFile();
                if (parentDir != null) {
                    parentDir.mkdirs();
                }
                writeIndicatorsToCsv(outputPath, klinesWithPreheat, indicators, fromDate);
            }

        } catch (Exception e) {
            throw new CommandException("计算指标过程中发生错误: " + e.getMessage(), e);
        }
    }

    private String generateDefaultOutputPath(String symbol, KLineType klineType, LocalDate toDate) {
        String fileName = String.format("%s_%s_%s_indicators.csv",
                symbol.replace(".", "_"),
                klineType.name(),
                toDate.format(DATE_FORMATTER));
        return "output/" + fileName;
    }

    private void writeIndicatorsToCsv(String filePath, List<HistoricalKLineEntity> klines, List<TechnicalIndicators> indicators, LocalDate originalFromDate) {
        printInfo("正在将全量指标结果导出到CSV文件: " + filePath);
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(filePath))) {
            // 写入CSV表头
            writer.write("Timestamp,Open,High,Low,Close,Volume,MA5,MA20,MACD,Signal,RSI,UpperBand,LowerBand,ADX,ATR\n");

            // 写入数据行
            for (int i = 0; i < klines.size(); i++) {
                HistoricalKLineEntity kline = klines.get(i);
                // 仅写入在指定日期之后的数据
                if (!kline.getTimestamp().toLocalDate().isBefore(originalFromDate)) {
                    TechnicalIndicators ind = indicators.get(i);
                    StringBuilder sb = new StringBuilder();
                    sb.append(kline.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(",");
                    sb.append(BigDecimalUtils.scaleForMoney(kline.getOpen())).append(",");
                    sb.append(BigDecimalUtils.scaleForMoney(kline.getHigh())).append(",");
                    sb.append(BigDecimalUtils.scaleForMoney(kline.getLow())).append(",");
                    sb.append(BigDecimalUtils.scaleForMoney(kline.getClose())).append(",");
                    sb.append(kline.getVolume()).append(",");
                    sb.append(BigDecimalUtils.scaleForMoney(ind.getSma5())).append(",");
                    sb.append(BigDecimalUtils.scaleForMoney(ind.getSma20())).append(",");
                    sb.append(BigDecimalUtils.scaleForIndicator(ind.getMacdLine())).append(",");
                    sb.append(BigDecimalUtils.scaleForIndicator(ind.getSignalLine())).append(",");
                    sb.append(BigDecimalUtils.scaleForIndicator(ind.getRsi())).append(",");
                    sb.append(BigDecimalUtils.scaleForMoney(ind.getUpperBand())).append(",");
                    sb.append(BigDecimalUtils.scaleForMoney(ind.getLowerBand())).append(",");
                    sb.append(BigDecimalUtils.scaleForIndicator(ind.getAdx())).append(",");
                    sb.append(BigDecimalUtils.scaleForIndicator(ind.getAtr())).append("\n");
                    writer.write(sb.toString());
                }
            }
            printSuccess("全量指标结果已成功导出到: " + filePath);
        } catch (java.io.IOException e) {
            printError("导出指标到CSV文件时出错: " + e.getMessage());
        }
    }

    private void printIndicators(List<HistoricalKLineEntity> klines, List<TechnicalIndicators> indicators, int tail, LocalDate originalFromDate) {
        printSeparator();
        printTableHeader("技术指标计算结果 (最近 " + tail + " 条, 从 " + originalFromDate + " 开始)");
        System.out.printf("%-21s | %-10s | %-10s | %-10s | %-10s | %-10s | %-10s | %-18s | %-10s | %-10s%n",
                "时间戳", "收盘价", "MA5", "MA20", "MACD", "Signal", "RSI", "上轨/下轨", "ADX", "ATR");
        printSeparator();

        // 过滤掉预热数据
        List<Integer> displayIndexes = new ArrayList<>();
        for (int i = 0; i < klines.size(); i++) {
            if (!klines.get(i).getTimestamp().toLocalDate().isBefore(originalFromDate)) {
                displayIndexes.add(i);
            }
        }

        // 获取最后N条记录的起始索引
        int startIndex = Math.max(0, displayIndexes.size() - tail);
        
        for (int i = startIndex; i < displayIndexes.size(); i++) {
            int dataIndex = displayIndexes.get(i);
            HistoricalKLineEntity kline = klines.get(dataIndex);
            TechnicalIndicators ind = indicators.get(dataIndex);
            System.out.printf("%-21s | %-10.2f | %-10.2f | %-10.2f | %-10.2f | %-10.2f | %-10.2f | %-18s | %-10.2f | %-10.2f%n",
                    kline.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    kline.getClose(),
                    ind.getSma5(),
                    ind.getSma20(),
                    ind.getMacdLine(),
                    ind.getSignalLine(),
                    ind.getRsi(),
                    String.format("%.2f / %.2f", ind.getUpperBand(), ind.getLowerBand()),
                    ind.getAdx(),
                    ind.getAtr()
            );
        }
        printSeparator();
    }

    private Duration getBarDuration(KLineType klineType) {
        return switch (klineType) {
            case K_1MIN -> Duration.ofMinutes(1);
            case K_5MIN -> Duration.ofMinutes(5);
            case K_15MIN -> Duration.ofMinutes(15);
            case K_30MIN -> Duration.ofMinutes(30);
            case K_60MIN -> Duration.ofHours(1);
            case K_DAY -> Duration.ofDays(1);
            case K_WEEK -> Duration.ofDays(7);
            case K_MONTH -> Duration.ofDays(30); // Approximate
        };
    }

    @Override
    public void printUsage() {
        printUsageHeader("java -jar trading.jar indicator:calc [选项]");
        System.out.println("\n" + getDescription() + ".\n");
        printOptions(createOptions());
        printExamples();
    }

    @Override
    public List<String> getExamples() {
        return List.of(
                "indicator:calc --symbol HK.00700 --from 2024-01-01",
                "indicator:calc -s HK.00700 -f 2024-01-01 -t 2024-01-31 -k K_30MIN --tail 20",
                "indicator:calc -s HK.00700 -f 2024-01-01 --output",
                "indicator:calc -s HK.00700 -f 2024-01-01 --output /path/to/custom.csv"
        );
    }

    private Options createOptions() {
        Options options = createBaseOptions();
        options.addOption(createRequiredOption("s", "symbol", "股票代码 (e.g., HK.00700)", true));
        options.addOption(createRequiredOption("f", "from", "开始日期 (YYYY-MM-DD)", true));
        options.addOption(createOption("t", "to", "结束日期 (YYYY-MM-DD), 默认为今天", true));
        options.addOption(createOption("k", "kline", "K线类型 (默认: K_DAY)", true));
        options.addOption(createOption("r", "rehab", "复权类型 (FORWARD, BACKWARD, NONE), 默认为 FORWARD", true));
        options.addOption(createOption("i", "indicator", "要显示的特定指标 (e.g., MACD, BOLL)", true));
        
        org.apache.commons.cli.Option outputOption = new org.apache.commons.cli.Option("o", "output", true, "将完整指标结果输出到文件。可选择提供路径，否则使用默认路径和文件名。");
        outputOption.setOptionalArg(true);
        options.addOption(outputOption);

        options.addOption(createOption("", "tail", "显示最近N条记录 (默认: 10)", true));
        return options;
    }
}
