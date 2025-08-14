package com.trading.validation;

import lombok.extern.slf4j.Slf4j;

/**
 * 技术指标预热配置
 * 根据不同的K线周期(timeframe)动态计算所需的预热数据量
 * 
 * 关键理解：
 * - MACD(12,26,9)中的26是指26个K线周期，不是26天
 * - 对于30分钟K线，只需要26条30分钟K线
 * - 对于日线，才需要26条日K线（26天）
 */
@Slf4j
public class IndicatorWarmupConfig {
    
    // 技术指标的基础周期需求
    private static final int MACD_LONG_PERIOD = 26;    // MACD长期EMA
    private static final int MACD_SIGNAL_PERIOD = 9;   // MACD信号线
    private static final int BB_PERIOD = 20;           // 布林带周期
    private static final int CCI_PERIOD = 20;          // CCI周期
    private static final int RSI_PERIOD = 14;          // RSI周期
    private static final int MFI_PERIOD = 14;          // MFI周期
    
    /**
     * 获取指定timeframe的最少预热K线数量
     * 
     * @param timeframe K线周期（1m, 5m, 15m, 30m, 60m, 1d等）
     * @return 最少需要的K线数量
     */
    public static int getMinimumBars(String timeframe) {
        // 基础需求：MACD需要26个周期 + 9个信号线周期 = 35
        // 为了确保所有指标都能准确计算，使用最大值
        int basePeriod = MACD_LONG_PERIOD + MACD_SIGNAL_PERIOD;
        
        // 根据不同timeframe添加额外缓冲
        int buffer = switch(timeframe.toLowerCase()) {
            case "1m", "5m", "15m", "30m", "60m", "1h" -> 15;  // 分钟级别：额外15条缓冲
            case "1d", "day" -> 65;  // 日线级别：需要更多历史数据以提高准确性
            case "1w", "week" -> 100; // 周线级别：需要更长历史
            case "1month", "month" -> 150; // 月线级别：需要最长历史
            default -> 15;
        };
        
        int minimumBars = basePeriod + buffer;
        log.debug("Timeframe {} 需要最少 {} 条K线作为预热数据", timeframe, minimumBars);
        return minimumBars;
    }
    
    /**
     * 获取建议的数据获取量
     * 为了有足够的验证数据，通常获取预热数据的2-3倍
     * 
     * @param timeframe K线周期
     * @return 建议获取的K线数量
     */
    public static int getDataFetchSize(String timeframe) {
        int minBars = getMinimumBars(timeframe);
        
        // 根据timeframe决定倍数
        int multiplier = switch(timeframe.toLowerCase()) {
            case "1m", "5m" -> 4;     // 分钟级数据量大，可以多获取
            case "15m", "30m" -> 3;   // 适中
            case "60m", "1h" -> 3;    // 适中
            case "1d", "day" -> 2;    // 日线数据珍贵，2倍即可
            default -> 3;
        };
        
        int fetchSize = minBars * multiplier;
        log.info("Timeframe {} 建议获取 {} 条K线（预热{}条，验证{}条）", 
                timeframe, fetchSize, minBars, fetchSize - minBars);
        return fetchSize;
    }
    
    /**
     * 获取特定指标所需的最少K线数
     * 
     * @param indicatorName 指标名称（RSI, MACD, BB等）
     * @return 该指标需要的最少K线数
     */
    public static int getIndicatorMinimumBars(String indicatorName) {
        return switch(indicatorName.toUpperCase()) {
            case "MACD" -> MACD_LONG_PERIOD + MACD_SIGNAL_PERIOD;
            case "BB", "BOLLINGER" -> BB_PERIOD;
            case "RSI" -> RSI_PERIOD;
            case "CCI" -> CCI_PERIOD;
            case "MFI" -> MFI_PERIOD;
            case "SAR" -> 2;  // SAR最少需要2条K线
            case "PIVOT" -> 1; // Pivot Points只需要1条K线
            default -> 20;  // 默认20条
        };
    }
    
    /**
     * 验证数据量是否充足
     * 
     * @param dataSize 实际数据量
     * @param timeframe K线周期
     * @return 是否充足
     */
    public static boolean isDataSufficient(int dataSize, String timeframe) {
        int minimum = getMinimumBars(timeframe);
        boolean sufficient = dataSize >= minimum;
        
        if (!sufficient) {
            log.warn("数据量不足：timeframe={}, 需要至少{}条，实际只有{}条", 
                    timeframe, minimum, dataSize);
        }
        
        return sufficient;
    }
    
    /**
     * 获取预热数据的时间跨度描述
     * 
     * @param timeframe K线周期
     * @param barCount K线数量
     * @return 时间跨度描述
     */
    public static String getTimeSpanDescription(String timeframe, int barCount) {
        return switch(timeframe.toLowerCase()) {
            case "1m" -> String.format("%.1f小时", barCount / 60.0);
            case "5m" -> String.format("%.1f小时", barCount * 5 / 60.0);
            case "15m" -> String.format("%.1f小时", barCount * 15 / 60.0);
            case "30m" -> String.format("%.1f天", barCount * 30 / 60.0 / 24);
            case "60m", "1h" -> String.format("%.1f天", barCount / 24.0);
            case "1d", "day" -> String.format("%d天", barCount);
            case "1w", "week" -> String.format("%d周", barCount);
            case "1month", "month" -> String.format("%d月", barCount);
            default -> String.format("%d个周期", barCount);
        };
    }
    
    /**
     * 获取完整的配置信息
     * 
     * @param timeframe K线周期
     * @return 配置信息字符串
     */
    public static String getConfigInfo(String timeframe) {
        int minBars = getMinimumBars(timeframe);
        int fetchSize = getDataFetchSize(timeframe);
        String timeSpan = getTimeSpanDescription(timeframe, minBars);
        
        return String.format(
            "Timeframe: %s\n" +
            "最少预热K线: %d条（覆盖%s）\n" +
            "建议获取K线: %d条\n" +
            "验证数据量: %d条",
            timeframe, minBars, timeSpan, fetchSize, fetchSize - minBars
        );
    }
}