package com.trading.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标极值标记系统
 * 用于标记技术指标的极端值情况，帮助识别异常信号和潜在风险
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorFlags {
    
    // RSI极值标记
    private boolean rsiExtremeLow;      // RSI < 20 极度超卖
    private boolean rsiOversold;         // RSI < 30 超卖
    private boolean rsiOverbought;       // RSI > 70 超买
    private boolean rsiExtremeHigh;      // RSI > 80 极度超买
    
    // Stochastic极值标记
    private boolean stochExtremeLow;     // K < 5 极度超卖
    private boolean stochOversold;       // K < 20 超卖
    private boolean stochOverbought;     // K > 80 超买
    private boolean stochExtremeHigh;    // K > 95 极度超买
    private boolean stochKZero;          // K = 0 触底（需要特别关注）
    private boolean stochDivergence;     // K和D背离
    
    // Williams %R极值标记
    private boolean williamsExtremeLow;  // WR < -95 极度超卖
    private boolean williamsOversold;    // WR < -80 超卖
    private boolean williamsOverbought;  // WR > -20 超买
    private boolean williamsExtremeHigh; // WR > -5 极度超买
    private boolean williamsAtBottom;    // WR = -100 触底
    private boolean williamsAtTop;       // WR = 0 触顶
    
    // CCI极值标记
    private boolean cciExtremeLow;       // CCI < -200 极度超卖
    private boolean cciOversold;         // CCI < -100 超卖
    private boolean cciOverbought;       // CCI > 100 超买
    private boolean cciExtremeHigh;      // CCI > 200 极度超买
    
    // MFI极值标记
    private boolean mfiExtremeLow;       // MFI < 10 极度超卖
    private boolean mfiOversold;         // MFI < 20 超卖
    private boolean mfiOverbought;       // MFI > 80 超买
    private boolean mfiExtremeHigh;      // MFI > 90 极度超买
    
    // 布林带极值标记
    private boolean priceAboveUpperBand; // 价格突破上轨
    private boolean priceBelowLowerBand; // 价格突破下轨
    private boolean bandwidthExtremeLow; // 带宽极窄（可能突破）
    private boolean bandwidthExtremeHigh;// 带宽极宽（高波动）
    private boolean percentBExtremeLow;  // %B < 0 价格在下轨之下
    private boolean percentBExtremeHigh; // %B > 1 价格在上轨之上
    
    // ADX趋势强度标记
    private boolean trendWeak;           // ADX < 20 趋势弱
    private boolean trendModerate;       // 20 <= ADX < 40 趋势中等
    private boolean trendStrong;         // 40 <= ADX < 60 趋势强
    private boolean trendVeryStrong;     // ADX >= 60 趋势极强
    
    // 成交量异常标记
    private boolean volumeExtremeLow;    // 成交量极低（< 50%平均）
    private boolean volumeLow;           // 成交量偏低（< 80%平均）
    private boolean volumeHigh;          // 成交量偏高（> 150%平均）
    private boolean volumeExtremeHigh;   // 成交量极高（> 200%平均）
    
    // 综合警告级别
    private WarningLevel overallWarning; // 综合警告级别
    private List<String> warnings;       // 具体警告信息列表
    
    // 分级警告列表
    private List<String> infoMessages;   // 信息级别消息
    private List<String> warnMessages;   // 警告级别消息
    private List<String> errorMessages;  // 错误级别消息
    
    /**
     * 警告级别枚举
     */
    public enum WarningLevel {
        NONE("无警告", ""),
        INFO("信息提示", "ℹ️"),
        LOW("低级警告", "🟢"),
        MEDIUM("中级警告", "🟡"),
        HIGH("高级警告", "🟠"),
        CRITICAL("严重警告", "🔴"),
        ERROR("错误警告", "❌");
        
        private final String description;
        private final String icon;
        
        WarningLevel(String description, String icon) {
            this.description = description;
            this.icon = icon;
        }
        
        public String getDescription() {
            return description;
        }
        
        public String getIcon() {
            return icon;
        }
    }
    
    /**
     * 根据技术指标值检测极值标记
     */
    public static IndicatorFlags detectFlags(
            BigDecimal rsi,
            BigDecimal stochK,
            BigDecimal stochD,
            BigDecimal williamsR,
            BigDecimal cci,
            BigDecimal mfi,
            BigDecimal close,
            BigDecimal upperBand,
            BigDecimal lowerBand,
            BigDecimal bandwidth,
            BigDecimal percentB,
            BigDecimal adx,
            BigDecimal volumeRatio,
            String timeframe) {
        
        IndicatorFlags flags = new IndicatorFlags();
        List<String> warnings = new ArrayList<>();
        List<String> infoMessages = new ArrayList<>();
        List<String> warnMessages = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();
        int warningCount = 0;
        int errorCount = 0;
        
        // 根据时间周期获取参数
        IndicatorParameters params = IndicatorParameters.forTimeframe(timeframe);
        
        // RSI极值检测
        if (rsi != null) {
            double rsiValue = rsi.doubleValue();
            if (rsiValue < 20) {
                flags.setRsiExtremeLow(true);
                errorMessages.add("RSI极度超卖(<20): " + String.format("%.2f", rsiValue));
                warningCount += 2;
                errorCount++;
            } else if (rsiValue < params.getRsiOversoldThreshold()) {
                flags.setRsiOversold(true);
                warnMessages.add("RSI超卖: " + String.format("%.2f", rsiValue));
                warningCount++;
            } else if (rsiValue > 80) {
                flags.setRsiExtremeHigh(true);
                errorMessages.add("RSI极度超买(>80): " + String.format("%.2f", rsiValue));
                warningCount += 2;
                errorCount++;
            } else if (rsiValue > params.getRsiOverboughtThreshold()) {
                flags.setRsiOverbought(true);
                warnMessages.add("RSI超买: " + String.format("%.2f", rsiValue));
                warningCount++;
            } else if (rsiValue > 45 && rsiValue < 55) {
                infoMessages.add("RSI中性区间: " + String.format("%.2f", rsiValue));
            }
        }
        
        // Stochastic极值检测
        if (stochK != null && stochD != null) {
            double kValue = stochK.doubleValue();
            double dValue = stochD.doubleValue();
            
            // K值极值检测
            if (kValue <= 0.01) {
                flags.setStochKZero(true);
                errorMessages.add("Stoch_K触底(≈0): " + String.format("%.2f", kValue));
                warningCount += 2;
                errorCount++;
            } else if (kValue < 5) {
                flags.setStochExtremeLow(true);
                errorMessages.add("Stoch_K极度超卖(<5): " + String.format("%.2f", kValue));
                warningCount += 2;
                errorCount++;
            } else if (kValue < 20) {
                flags.setStochOversold(true);
                warnMessages.add("Stoch_K超卖: " + String.format("%.2f", kValue));
                warningCount++;
            } else if (kValue > 95) {
                flags.setStochExtremeHigh(true);
                errorMessages.add("Stoch_K极度超买(>95): " + String.format("%.2f", kValue));
                warningCount += 2;
                errorCount++;
            } else if (kValue > 80) {
                flags.setStochOverbought(true);
                warnMessages.add("Stoch_K超买: " + String.format("%.2f", kValue));
                warningCount++;
            }
            
            // K和D背离检测
            if (Math.abs(kValue - dValue) > 20) {
                flags.setStochDivergence(true);
                warnings.add("Stoch K/D背离: K=" + String.format("%.2f", kValue) + 
                           ", D=" + String.format("%.2f", dValue));
                warningCount++;
            }
        }
        
        // Williams %R极值检测
        if (williamsR != null) {
            double wrValue = williamsR.doubleValue();
            if (wrValue <= -100) {
                flags.setWilliamsAtBottom(true);
                warnings.add("Williams %R触底(-100)");
                warningCount += 2;
            } else if (wrValue < -95) {
                flags.setWilliamsExtremeLow(true);
                warnings.add("Williams %R极度超卖(<-95): " + String.format("%.2f", wrValue));
                warningCount += 2;
            } else if (wrValue < -80) {
                flags.setWilliamsOversold(true);
                warnings.add("Williams %R超卖: " + String.format("%.2f", wrValue));
                warningCount++;
            } else if (wrValue >= 0) {
                flags.setWilliamsAtTop(true);
                warnings.add("Williams %R触顶(0)");
                warningCount += 2;
            } else if (wrValue > -5) {
                flags.setWilliamsExtremeHigh(true);
                warnings.add("Williams %R极度超买(>-5): " + String.format("%.2f", wrValue));
                warningCount += 2;
            } else if (wrValue > -20) {
                flags.setWilliamsOverbought(true);
                warnings.add("Williams %R超买: " + String.format("%.2f", wrValue));
                warningCount++;
            }
        }
        
        // CCI极值检测
        if (cci != null) {
            double cciValue = cci.doubleValue();
            if (cciValue < -200) {
                flags.setCciExtremeLow(true);
                warnings.add("CCI极度超卖(<-200): " + String.format("%.2f", cciValue));
                warningCount += 2;
            } else if (cciValue < params.getCciOversoldThreshold()) {
                flags.setCciOversold(true);
                warnings.add("CCI超卖: " + String.format("%.2f", cciValue));
                warningCount++;
            } else if (cciValue > 200) {
                flags.setCciExtremeHigh(true);
                warnings.add("CCI极度超买(>200): " + String.format("%.2f", cciValue));
                warningCount += 2;
            } else if (cciValue > params.getCciOverboughtThreshold()) {
                flags.setCciOverbought(true);
                warnings.add("CCI超买: " + String.format("%.2f", cciValue));
                warningCount++;
            }
        }
        
        // MFI极值检测
        if (mfi != null) {
            double mfiValue = mfi.doubleValue();
            if (mfiValue < 10) {
                flags.setMfiExtremeLow(true);
                warnings.add("MFI极度超卖(<10): " + String.format("%.2f", mfiValue));
                warningCount += 2;
            } else if (mfiValue < params.getMfiOversoldThreshold()) {
                flags.setMfiOversold(true);
                warnings.add("MFI超卖: " + String.format("%.2f", mfiValue));
                warningCount++;
            } else if (mfiValue > 90) {
                flags.setMfiExtremeHigh(true);
                warnings.add("MFI极度超买(>90): " + String.format("%.2f", mfiValue));
                warningCount += 2;
            } else if (mfiValue > params.getMfiOverboughtThreshold()) {
                flags.setMfiOverbought(true);
                warnings.add("MFI超买: " + String.format("%.2f", mfiValue));
                warningCount++;
            }
        }
        
        // 布林带极值检测
        if (close != null && upperBand != null && lowerBand != null) {
            if (close.compareTo(upperBand) > 0) {
                flags.setPriceAboveUpperBand(true);
                warnings.add("价格突破布林上轨");
                warningCount++;
            }
            if (close.compareTo(lowerBand) < 0) {
                flags.setPriceBelowLowerBand(true);
                warnings.add("价格突破布林下轨");
                warningCount++;
            }
        }
        
        if (percentB != null) {
            double pbValue = percentB.doubleValue();
            if (pbValue < 0) {
                flags.setPercentBExtremeLow(true);
                warnings.add("%B < 0 (价格在下轨之下)");
                warningCount++;
            } else if (pbValue > 1) {
                flags.setPercentBExtremeHigh(true);
                warnings.add("%B > 1 (价格在上轨之上)");
                warningCount++;
            }
        }
        
        if (bandwidth != null) {
            double bwValue = bandwidth.doubleValue();
            if (bwValue < 0.02) {  // 带宽小于2%
                flags.setBandwidthExtremeLow(true);
                warnings.add("布林带宽极窄(<2%): " + String.format("%.2f%%", bwValue * 100));
                warningCount++;
            } else if (bwValue > 0.10) {  // 带宽大于10%
                flags.setBandwidthExtremeHigh(true);
                warnings.add("布林带宽极宽(>10%): " + String.format("%.2f%%", bwValue * 100));
                warningCount++;
            }
        }
        
        // ADX趋势强度检测
        if (adx != null) {
            double adxValue = adx.doubleValue();
            if (adxValue < 20) {
                flags.setTrendWeak(true);
            } else if (adxValue < 40) {
                flags.setTrendModerate(true);
            } else if (adxValue < 60) {
                flags.setTrendStrong(true);
                warnings.add("趋势强劲(ADX=" + String.format("%.2f", adxValue) + ")");
            } else {
                flags.setTrendVeryStrong(true);
                warnings.add("趋势极强(ADX=" + String.format("%.2f", adxValue) + ")");
                warningCount++;
            }
        }
        
        // 成交量异常检测
        if (volumeRatio != null) {
            double vrValue = volumeRatio.doubleValue();
            if (vrValue < 0.5) {
                flags.setVolumeExtremeLow(true);
                warnings.add("成交量极低(<50%): " + String.format("%.2f", vrValue));
                warningCount++;
            } else if (vrValue < 0.8) {
                flags.setVolumeLow(true);
            } else if (vrValue > 2.0) {
                flags.setVolumeExtremeHigh(true);
                warnings.add("成交量极高(>200%): " + String.format("%.2f", vrValue));
                warningCount++;
            } else if (vrValue > 1.5) {
                flags.setVolumeHigh(true);
            }
        }
        
        // 合并所有警告消息
        warnings.addAll(errorMessages);
        warnings.addAll(warnMessages);
        warnings.addAll(infoMessages);
        
        // 设置综合警告级别
        WarningLevel level;
        if (errorCount > 3) {
            level = WarningLevel.ERROR;
        } else if (errorCount > 0) {
            level = WarningLevel.CRITICAL;
        } else if (warningCount > 6) {
            level = WarningLevel.HIGH;
        } else if (warningCount > 3) {
            level = WarningLevel.MEDIUM;
        } else if (warningCount > 0) {
            level = WarningLevel.LOW;
        } else if (!infoMessages.isEmpty()) {
            level = WarningLevel.INFO;
        } else {
            level = WarningLevel.NONE;
        }
        
        flags.setOverallWarning(level);
        flags.setWarnings(warnings);
        flags.setInfoMessages(infoMessages);
        flags.setWarnMessages(warnMessages);
        flags.setErrorMessages(errorMessages);
        
        return flags;
    }
    
    /**
     * 生成警告摘要字符串
     */
    public String getWarningSummary() {
        if (warnings == null || warnings.isEmpty()) {
            return "无警告";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(overallWarning.getDescription()).append("] ");
        sb.append(String.join("; ", warnings));
        return sb.toString();
    }
    
    /**
     * 获取简短的警告标记（用于CSV输出）
     */
    public String getWarningFlags() {
        List<String> flags = new ArrayList<>();
        
        if (rsiExtremeLow || rsiExtremeHigh) flags.add("RSI!");
        if (stochKZero) flags.add("K=0!");
        if (stochExtremeLow || stochExtremeHigh) flags.add("Stoch!");
        if (williamsAtBottom || williamsAtTop) flags.add("WR!");
        if (cciExtremeLow || cciExtremeHigh) flags.add("CCI!");
        if (mfiExtremeLow || mfiExtremeHigh) flags.add("MFI!");
        if (percentBExtremeLow || percentBExtremeHigh) flags.add("BB!");
        if (volumeExtremeLow || volumeExtremeHigh) flags.add("Vol!");
        
        if (flags.isEmpty()) {
            return "";
        }
        
        return String.join(",", flags);
    }
    
    /**
     * 获取分级警告标记（带图标）
     */
    public String getLeveledWarnings() {
        if (overallWarning == null || overallWarning == WarningLevel.NONE) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(overallWarning.getIcon());
        
        // 添加最重要的警告
        if (!errorMessages.isEmpty() && errorMessages.size() <= 2) {
            sb.append(" ").append(errorMessages.get(0));
        } else if (!warnMessages.isEmpty() && warnMessages.size() <= 2) {
            sb.append(" ").append(warnMessages.get(0));
        } else {
            sb.append(" ").append(overallWarning.getDescription());
        }
        
        // 添加计数
        if (errorMessages.size() + warnMessages.size() > 1) {
            sb.append(" (+").append(errorMessages.size() + warnMessages.size() - 1).append(")");
        }
        
        return sb.toString();
    }
    
    /**
     * 获取警告级别代码（INFO/WARN/ERROR）
     */
    public String getWarningLevelCode() {
        if (overallWarning == null) {
            return "";
        }
        
        switch (overallWarning) {
            case ERROR:
            case CRITICAL:
                return "ERROR";
            case HIGH:
            case MEDIUM:
            case LOW:
                return "WARN";
            case INFO:
                return "INFO";
            default:
                return "";
        }
    }
}