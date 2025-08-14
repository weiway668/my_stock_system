package com.trading.config;

import com.trading.domain.vo.TechnicalIndicators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标健康度评分系统
 * 综合评估技术指标的质量和可靠性
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorHealth {
    
    // 健康度得分（0-100）
    private double overallScore;           // 总体健康度得分
    private double dataQualityScore;       // 数据质量得分
    private double consistencyScore;       // 一致性得分
    private double stabilityScore;         // 稳定性得分
    private double timeframeScore;         // 时间周期适配得分
    
    // 健康度级别
    private HealthLevel healthLevel;       // 健康度级别
    
    // 详细评估
    private int totalIndicators;           // 总指标数
    private int validIndicators;           // 有效指标数
    private int naIndicators;              // N/A指标数
    private int extremeValueCount;         // 极值数量
    private int divergenceCount;           // 背离数量
    
    // 问题列表
    private List<String> issues;           // 发现的问题
    private List<String> suggestions;      // 改进建议
    
    // 可靠性标记
    private boolean reliableForTrading;    // 是否可靠用于交易
    private boolean needsMoreData;         // 是否需要更多数据
    private boolean hasDataQualityIssues;  // 是否有数据质量问题
    
    /**
     * 健康度级别枚举
     */
    public enum HealthLevel {
        EXCELLENT("优秀", "✅", 90),      // 90-100分
        GOOD("良好", "👍", 75),           // 75-90分
        FAIR("一般", "👌", 60),           // 60-75分
        POOR("较差", "⚠️", 40),           // 40-60分
        CRITICAL("危险", "❌", 0);        // 0-40分
        
        private final String description;
        private final String icon;
        private final int threshold;
        
        HealthLevel(String description, String icon, int threshold) {
            this.description = description;
            this.icon = icon;
            this.threshold = threshold;
        }
        
        public String getDescription() {
            return description;
        }
        
        public String getIcon() {
            return icon;
        }
        
        public int getThreshold() {
            return threshold;
        }
    }
    
    /**
     * 评估技术指标健康度
     */
    public static IndicatorHealth evaluate(
            TechnicalIndicators indicators,
            IndicatorFlags flags,
            IndicatorCorrelation correlation,
            String timeframe,
            int dataSize) {
        
        IndicatorHealth health = new IndicatorHealth();
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        
        // 1. 评估数据质量
        DataQualityResult dataQuality = evaluateDataQuality(indicators);
        health.setTotalIndicators(dataQuality.total);
        health.setValidIndicators(dataQuality.valid);
        health.setNaIndicators(dataQuality.naCount);
        health.setDataQualityScore(dataQuality.score);
        
        if (dataQuality.score < 80) {
            issues.add("数据完整性不足：" + dataQuality.naCount + "个指标缺失");
            suggestions.add("增加历史数据量以提高指标计算准确性");
            health.setHasDataQualityIssues(true);
        }
        
        // 2. 评估一致性
        double consistencyScore = 100.0;
        if (correlation != null) {
            consistencyScore = correlation.getCorrelationScore();
            health.setConsistencyScore(consistencyScore);
            
            if (correlation.isConflictingSignals()) {
                issues.add("指标信号存在冲突");
                suggestions.add("等待更明确的信号再进行交易");
                consistencyScore -= 20;
            }
            
            if (correlation.isRsiDivergence() || correlation.isMacdDivergence()) {
                health.setDivergenceCount(health.getDivergenceCount() + 1);
                issues.add("检测到技术指标背离");
                suggestions.add("谨慎操作，可能出现趋势反转");
            }
        } else {
            health.setConsistencyScore(50); // 无相关性分析时给中等分数
        }
        
        // 3. 评估稳定性（基于极值数量）
        double stabilityScore = 100.0;
        if (flags != null) {
            int extremeCount = countExtremeValues(flags);
            health.setExtremeValueCount(extremeCount);
            
            if (extremeCount > 5) {
                stabilityScore = 40;
                issues.add("过多极值警告：" + extremeCount + "个");
                suggestions.add("市场可能处于极端状态，建议观望");
            } else if (extremeCount > 3) {
                stabilityScore = 60;
                issues.add("多个极值警告");
                suggestions.add("注意控制风险");
            } else if (extremeCount > 1) {
                stabilityScore = 80;
            }
        }
        health.setStabilityScore(stabilityScore);
        
        // 4. 评估时间周期适配度
        double timeframeScore = evaluateTimeframeAdaptation(timeframe, dataSize);
        health.setTimeframeScore(timeframeScore);
        
        if (timeframeScore < 70) {
            issues.add("数据量不足以支持当前时间周期分析");
            suggestions.add("增加数据点数量或使用更短的时间周期");
            health.setNeedsMoreData(true);
        }
        
        // 5. 计算总体健康度得分
        double overallScore = calculateOverallScore(
            dataQuality.score,
            consistencyScore,
            stabilityScore,
            timeframeScore
        );
        health.setOverallScore(overallScore);
        
        // 6. 设置健康度级别
        if (overallScore >= 90) {
            health.setHealthLevel(HealthLevel.EXCELLENT);
            health.setReliableForTrading(true);
        } else if (overallScore >= 75) {
            health.setHealthLevel(HealthLevel.GOOD);
            health.setReliableForTrading(true);
        } else if (overallScore >= 60) {
            health.setHealthLevel(HealthLevel.FAIR);
            health.setReliableForTrading(true);
            suggestions.add("建议谨慎交易，设置严格止损");
        } else if (overallScore >= 40) {
            health.setHealthLevel(HealthLevel.POOR);
            health.setReliableForTrading(false);
            suggestions.add("不建议基于当前指标进行交易");
        } else {
            health.setHealthLevel(HealthLevel.CRITICAL);
            health.setReliableForTrading(false);
            issues.add("指标健康度严重不足");
            suggestions.add("等待数据质量改善后再进行分析");
        }
        
        // 7. 添加综合建议
        if (health.isReliableForTrading()) {
            if (overallScore >= 85) {
                suggestions.add(0, "技术指标健康度良好，可正常参考交易");
            } else {
                suggestions.add(0, "技术指标基本可靠，建议结合其他分析");
            }
        }
        
        health.setIssues(issues);
        health.setSuggestions(suggestions);
        
        return health;
    }
    
    /**
     * 评估数据质量
     */
    private static DataQualityResult evaluateDataQuality(TechnicalIndicators indicators) {
        int total = 0;
        int valid = 0;
        int naCount = 0;
        
        // 检查核心指标
        List<BigDecimal> coreIndicators = List.of(
            indicators.getRsi(),
            indicators.getMacdLine(),
            indicators.getUpperBand(),
            indicators.getSma20(),
            indicators.getStochK(),
            indicators.getAtr(),
            indicators.getAdx(),
            indicators.getCci(),
            indicators.getMfi()
        );
        
        for (BigDecimal value : coreIndicators) {
            total++;
            if (value != null && value.compareTo(BigDecimal.ZERO) != 0) {
                valid++;
            } else {
                naCount++;
            }
        }
        
        // 检查次要指标
        List<BigDecimal> secondaryIndicators = List.of(
            indicators.getSma50(),
            indicators.getEma26(),
            indicators.getWilliamsR(),
            indicators.getPlusDI(),
            indicators.getMinusDI(),
            indicators.getVwap(),
            indicators.getPivotPoint()
        );
        
        for (BigDecimal value : secondaryIndicators) {
            total++;
            if (value != null && value.compareTo(BigDecimal.ZERO) != 0) {
                valid++;
            } else {
                naCount++;
            }
        }
        
        double score = (valid * 100.0) / total;
        
        return new DataQualityResult(total, valid, naCount, score);
    }
    
    /**
     * 计数极值警告
     */
    private static int countExtremeValues(IndicatorFlags flags) {
        int count = 0;
        
        if (flags.isRsiExtremeLow() || flags.isRsiExtremeHigh()) count++;
        if (flags.isStochExtremeLow() || flags.isStochExtremeHigh()) count++;
        if (flags.isStochKZero()) count++;
        if (flags.isWilliamsAtBottom() || flags.isWilliamsAtTop()) count++;
        if (flags.isCciExtremeLow() || flags.isCciExtremeHigh()) count++;
        if (flags.isMfiExtremeLow() || flags.isMfiExtremeHigh()) count++;
        if (flags.isPercentBExtremeLow() || flags.isPercentBExtremeHigh()) count++;
        if (flags.isVolumeExtremeLow() || flags.isVolumeExtremeHigh()) count++;
        
        return count;
    }
    
    /**
     * 评估时间周期适配度
     */
    private static double evaluateTimeframeAdaptation(String timeframe, int dataSize) {
        // 根据时间周期确定最小数据需求
        int minRequired;
        int optimal;
        
        if (timeframe == null) timeframe = "1d";
        
        switch (timeframe.toLowerCase()) {
            case "1m":
            case "5m":
                minRequired = 100;
                optimal = 200;
                break;
            case "15m":
            case "30m":
                minRequired = 60;
                optimal = 120;
                break;
            case "1h":
            case "60m":
                minRequired = 50;
                optimal = 100;
                break;
            case "4h":
                minRequired = 40;
                optimal = 80;
                break;
            case "1d":
            default:
                minRequired = 30;
                optimal = 60;
                break;
            case "1w":
                minRequired = 20;
                optimal = 40;
                break;
        }
        
        if (dataSize >= optimal) {
            return 100.0;
        } else if (dataSize >= minRequired) {
            return 70 + (30.0 * (dataSize - minRequired) / (optimal - minRequired));
        } else {
            return 70.0 * dataSize / minRequired;
        }
    }
    
    /**
     * 计算总体健康度得分
     */
    private static double calculateOverallScore(
            double dataQuality, double consistency, 
            double stability, double timeframe) {
        
        // 加权平均：数据质量30%，一致性30%，稳定性25%，时间适配15%
        return dataQuality * 0.30 + 
               consistency * 0.30 + 
               stability * 0.25 + 
               timeframe * 0.15;
    }
    
    /**
     * 生成健康度摘要
     */
    public String getSummary() {
        if (healthLevel == null) {
            return "未评估";
        }
        
        return String.format("%s %s (%.1f分) | 有效指标:%d/%d", 
            healthLevel.getIcon(),
            healthLevel.getDescription(),
            overallScore,
            validIndicators,
            totalIndicators
        );
    }
    
    /**
     * 获取简短状态（用于CSV）
     */
    public String getStatus() {
        if (healthLevel == null) {
            return "";
        }
        
        return String.format("%s%.0f", healthLevel.getIcon(), overallScore);
    }
    
    /**
     * 数据质量评估结果
     */
    private static class DataQualityResult {
        int total;
        int valid;
        int naCount;
        double score;
        
        DataQualityResult(int total, int valid, int naCount, double score) {
            this.total = total;
            this.valid = valid;
            this.naCount = naCount;
            this.score = score;
        }
    }
}