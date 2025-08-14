package com.trading.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 技术指标动态参数配置
 * 根据不同的时间周期自动调整技术指标参数
 * 
 * 参考：
 * - 30分钟线适合短线交易，需要更敏感的参数
 * - 日线适合中长线分析，使用标准参数
 * - 周线/月线适合长期趋势分析，需要更长的周期
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorParameters {
    
    // RSI参数
    private int rsiPeriod;
    private double rsiOversoldThreshold;
    private double rsiOverboughtThreshold;
    
    // MACD参数
    private int macdShortPeriod;
    private int macdLongPeriod;
    private int macdSignalPeriod;
    
    // 布林带参数
    private int bollingerPeriod;
    private double bollingerDeviation;
    
    // 随机指标参数
    private int stochasticKPeriod;
    private int stochasticDPeriod;
    private int stochasticSmooth;
    private double stochasticMinValue;  // 防止K值为0的最小值
    
    // Williams %R参数
    private int williamsRPeriod;
    private boolean williamsRSmoothing;  // 是否启用平滑
    private int williamsRSmoothPeriod;   // 平滑周期
    
    // ATR参数
    private int atrPeriod;
    
    // ADX参数
    private int adxPeriod;
    
    // CCI参数
    private int cciPeriod;
    private double cciOversoldThreshold;
    private double cciOverboughtThreshold;
    
    // MFI参数
    private int mfiPeriod;
    private double mfiOversoldThreshold;
    private double mfiOverboughtThreshold;
    
    // 移动平均线参数
    private int smaShortPeriod;  // 短期SMA (如20)
    private int smaLongPeriod;   // 长期SMA (如50)
    private int emaShortPeriod;  // 短期EMA (如12)
    private int emaLongPeriod;   // 长期EMA (如26)
    
    // VWAP参数
    private int vwapPeriod;
    
    // 成交量分析参数
    private int volumeMAperiod;
    
    /**
     * 获取30分钟K线的优化参数
     * 短线交易优化，更敏感的参数设置
     */
    public static IndicatorParameters for30Minutes() {
        return IndicatorParameters.builder()
            // RSI: 使用9周期更敏感，阈值调整为35/65
            .rsiPeriod(9)
            .rsiOversoldThreshold(35)
            .rsiOverboughtThreshold(65)
            
            // MACD: 使用8,17,9更快速反应
            .macdShortPeriod(8)
            .macdLongPeriod(17)
            .macdSignalPeriod(9)
            
            // 布林带: 使用18周期，1.8倍标准差
            .bollingerPeriod(18)
            .bollingerDeviation(1.8)
            
            // 随机指标: 9,3,3更快速，设置最小值0.01防止纯0
            .stochasticKPeriod(9)
            .stochasticDPeriod(3)
            .stochasticSmooth(3)
            .stochasticMinValue(0.01)
            
            // Williams %R: 使用10周期，启用3期平滑
            .williamsRPeriod(10)
            .williamsRSmoothing(true)
            .williamsRSmoothPeriod(3)
            
            // ATR: 10周期
            .atrPeriod(10)
            
            // ADX: 12周期
            .adxPeriod(12)
            
            // CCI: 14周期，阈值±150
            .cciPeriod(14)
            .cciOversoldThreshold(-150)
            .cciOverboughtThreshold(150)
            
            // MFI: 12周期，阈值25/75
            .mfiPeriod(12)
            .mfiOversoldThreshold(25)
            .mfiOverboughtThreshold(75)
            
            // 移动平均线
            .smaShortPeriod(10)
            .smaLongPeriod(30)
            .emaShortPeriod(9)
            .emaLongPeriod(21)
            
            // VWAP: 20周期
            .vwapPeriod(20)
            
            // 成交量MA: 10周期
            .volumeMAperiod(10)
            
            .build();
    }
    
    /**
     * 获取日线的标准参数
     * 中期分析标准配置
     */
    public static IndicatorParameters forDaily() {
        return IndicatorParameters.builder()
            // RSI: 标准14周期，30/70阈值
            .rsiPeriod(14)
            .rsiOversoldThreshold(30)
            .rsiOverboughtThreshold(70)
            
            // MACD: 标准12,26,9
            .macdShortPeriod(12)
            .macdLongPeriod(26)
            .macdSignalPeriod(9)
            
            // 布林带: 标准20周期，2倍标准差
            .bollingerPeriod(20)
            .bollingerDeviation(2.0)
            
            // 随机指标: 标准14,3,3，最小值0.01
            .stochasticKPeriod(14)
            .stochasticDPeriod(3)
            .stochasticSmooth(3)
            .stochasticMinValue(0.01)
            
            // Williams %R: 标准14周期，不平滑
            .williamsRPeriod(14)
            .williamsRSmoothing(false)
            .williamsRSmoothPeriod(1)
            
            // ATR: 标准14周期
            .atrPeriod(14)
            
            // ADX: 标准14周期
            .adxPeriod(14)
            
            // CCI: 标准20周期，阈值±100
            .cciPeriod(20)
            .cciOversoldThreshold(-100)
            .cciOverboughtThreshold(100)
            
            // MFI: 标准14周期，阈值20/80
            .mfiPeriod(14)
            .mfiOversoldThreshold(20)
            .mfiOverboughtThreshold(80)
            
            // 移动平均线: 标准配置
            .smaShortPeriod(20)
            .smaLongPeriod(50)
            .emaShortPeriod(12)
            .emaLongPeriod(26)
            
            // VWAP: 14周期
            .vwapPeriod(14)
            
            // 成交量MA: 20周期
            .volumeMAperiod(20)
            
            .build();
    }
    
    /**
     * 获取周线的长期参数
     * 长期趋势分析配置
     */
    public static IndicatorParameters forWeekly() {
        return IndicatorParameters.builder()
            // RSI: 21周期，25/75阈值
            .rsiPeriod(21)
            .rsiOversoldThreshold(25)
            .rsiOverboughtThreshold(75)
            
            // MACD: 长期参数
            .macdShortPeriod(12)
            .macdLongPeriod(26)
            .macdSignalPeriod(9)
            
            // 布林带: 26周期，2.5倍标准差
            .bollingerPeriod(26)
            .bollingerDeviation(2.5)
            
            // 随机指标: 21,5,5
            .stochasticKPeriod(21)
            .stochasticDPeriod(5)
            .stochasticSmooth(5)
            .stochasticMinValue(0.01)
            
            // Williams %R: 21周期
            .williamsRPeriod(21)
            .williamsRSmoothing(false)
            .williamsRSmoothPeriod(1)
            
            // 其他指标保持标准
            .atrPeriod(14)
            .adxPeriod(14)
            .cciPeriod(20)
            .cciOversoldThreshold(-100)
            .cciOverboughtThreshold(100)
            .mfiPeriod(14)
            .mfiOversoldThreshold(20)
            .mfiOverboughtThreshold(80)
            .smaShortPeriod(20)
            .smaLongPeriod(50)
            .emaShortPeriod(12)
            .emaLongPeriod(26)
            .vwapPeriod(14)
            .volumeMAperiod(20)
            
            .build();
    }
    
    /**
     * 根据时间周期字符串获取相应的参数配置
     * @param timeframe 时间周期 (如: "30m", "1d", "1w")
     * @return 对应的参数配置
     */
    public static IndicatorParameters forTimeframe(String timeframe) {
        if (timeframe == null) {
            return forDaily();  // 默认使用日线参数
        }
        
        String tf = timeframe.toLowerCase();
        
        // 分钟级别
        if (tf.contains("m") || tf.contains("min")) {
            int minutes = extractMinutes(tf);
            if (minutes <= 30) {
                return for30Minutes();  // 30分钟及以下使用短线参数
            } else if (minutes <= 60) {
                return for60Minutes();  // 60分钟使用小时线参数
            }
        }
        
        // 小时级别
        if (tf.contains("h") || tf.contains("hour")) {
            int hours = extractHours(tf);
            if (hours <= 4) {
                return for4Hours();  // 4小时线参数
            }
        }
        
        // 日线
        if (tf.contains("d") || tf.contains("day")) {
            return forDaily();
        }
        
        // 周线
        if (tf.contains("w") || tf.contains("week")) {
            return forWeekly();
        }
        
        // 月线
        if (tf.contains("mo") || tf.contains("month")) {
            return forMonthly();
        }
        
        // 默认返回日线参数
        return forDaily();
    }
    
    /**
     * 60分钟K线参数
     */
    private static IndicatorParameters for60Minutes() {
        return IndicatorParameters.builder()
            .rsiPeriod(12)
            .rsiOversoldThreshold(30)
            .rsiOverboughtThreshold(70)
            .macdShortPeriod(10)
            .macdLongPeriod(22)
            .macdSignalPeriod(9)
            .bollingerPeriod(20)
            .bollingerDeviation(2.0)
            .stochasticKPeriod(12)
            .stochasticDPeriod(3)
            .stochasticSmooth(3)
            .stochasticMinValue(0.01)
            .williamsRPeriod(12)
            .williamsRSmoothing(false)
            .williamsRSmoothPeriod(1)
            .atrPeriod(12)
            .adxPeriod(14)
            .cciPeriod(18)
            .cciOversoldThreshold(-100)
            .cciOverboughtThreshold(100)
            .mfiPeriod(14)
            .mfiOversoldThreshold(20)
            .mfiOverboughtThreshold(80)
            .smaShortPeriod(15)
            .smaLongPeriod(40)
            .emaShortPeriod(10)
            .emaLongPeriod(22)
            .vwapPeriod(14)
            .volumeMAperiod(15)
            .build();
    }
    
    /**
     * 4小时K线参数
     */
    private static IndicatorParameters for4Hours() {
        return forDaily();  // 4小时线使用日线参数
    }
    
    /**
     * 月线参数
     */
    private static IndicatorParameters forMonthly() {
        return forWeekly();  // 月线使用周线参数
    }
    
    /**
     * 从时间周期字符串中提取分钟数
     */
    private static int extractMinutes(String timeframe) {
        try {
            String numStr = timeframe.replaceAll("[^0-9]", "");
            if (!numStr.isEmpty()) {
                return Integer.parseInt(numStr);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return 30;  // 默认30分钟
    }
    
    /**
     * 从时间周期字符串中提取小时数
     */
    private static int extractHours(String timeframe) {
        try {
            String numStr = timeframe.replaceAll("[^0-9]", "");
            if (!numStr.isEmpty()) {
                return Integer.parseInt(numStr);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return 1;  // 默认1小时
    }
}