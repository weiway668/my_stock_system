package com.trading.validation;

import com.trading.config.IndicatorFlags;
import com.trading.config.IndicatorCorrelation;
import com.trading.config.IndicatorHealth;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 验证数据点
 * 包含某一日的K线数据和所有计算的技术指标
 */
@Data
@Builder
public class ValidationDataPoint {
    
    // 基础数据
    private LocalDate date;
    private LocalDateTime dateTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
    
    // 核心技术指标
    private BigDecimal rsi;                // RSI(14)
    private BigDecimal macdLine;           // MACD线
    private BigDecimal macdSignal;         // MACD信号线
    private BigDecimal macdHistogram;      // MACD柱状图
    
    // 布林带
    private BigDecimal bollingerUpper;     // 布林带上轨
    private BigDecimal bollingerMiddle;    // 布林带中轨
    private BigDecimal bollingerLower;     // 布林带下轨
    
    // 移动平均线
    private BigDecimal ma20;               // 20日移动平均
    private BigDecimal ma50;               // 50日移动平均
    private BigDecimal ema12;              // 12日指数移动平均
    private BigDecimal ema26;              // 26日指数移动平均
    
    // 布林带扩展
    private BigDecimal bollingerBandwidth; // 带宽
    private BigDecimal bollingerPercentB;  // %B指标
    
    // 振荡器指标
    private BigDecimal cci;                // CCI商品通道指数
    private BigDecimal mfi;                // MFI资金流量指数
    private BigDecimal stochK;             // 随机指标K
    private BigDecimal stochD;             // 随机指标D
    private BigDecimal williamsR;          // 威廉指标
    
    // 趋势强度指标
    private BigDecimal atr;                // 平均真实波幅
    private BigDecimal adx;                // 平均趋向指标
    private BigDecimal plusDI;             // +DI
    private BigDecimal minusDI;            // -DI
    
    // 趋势反转
    private BigDecimal parabolicSar;       // 抛物线SAR
    
    // Pivot Points轴心点系统
    private BigDecimal pivotPoint;         // 轴心点
    private BigDecimal resistance1;        // 阻力位1
    private BigDecimal resistance2;        // 阻力位2
    private BigDecimal resistance3;        // 阻力位3
    private BigDecimal support1;           // 支撑位1
    private BigDecimal support2;           // 支撑位2
    private BigDecimal support3;           // 支撑位3
    
    // 成交量指标
    private BigDecimal obv;                // 能量潮
    private BigDecimal volumeMA;           // 成交量移动平均
    private BigDecimal volumeRatio;        // 成交量比率
    private BigDecimal vwap;               // 成交量加权平均价
    
    // 极值警告标记
    private IndicatorFlags flags;          // 技术指标极值标记
    private String warningFlags;           // 简短的警告标记字符串
    private String warningLevel;           // 警告级别(INFO/WARN/ERROR)
    private String warningDetails;         // 警告详情
    
    // 相关性分析
    private IndicatorCorrelation correlation; // 指标相关性分析
    private String correlationSummary;     // 相关性摘要
    private String tradingSignal;          // 交易信号(多头/空头/中性/冲突)
    
    // 健康度评分
    private IndicatorHealth health;        // 指标健康度
    private String healthStatus;           // 健康状态
    private Double healthScore;            // 健康度分数
    
    // 综合建议
    private String recommendation;         // 综合建议
    
    /**
     * 生成易于阅读的字符串表示
     */
    public String toReadableString() {
        StringBuilder sb = new StringBuilder();
        sb.append("日期: ").append(dateTime).append("\n");
        sb.append("OHLC: ").append(format(open)).append(" / ")
                .append(format(high)).append(" / ")
                .append(format(low)).append(" / ")
                .append(format(close)).append("\n");
        sb.append("成交量: ").append(volume != null ? volume.longValue() : "N/A").append("\n");
        sb.append("RSI: ").append(format(rsi)).append("\n");
        sb.append("MACD: ").append(format(macdLine)).append(" | ")
                .append(format(macdSignal)).append(" | ")
                .append(format(macdHistogram)).append("\n");
        sb.append("布林带: ").append(format(bollingerUpper)).append(" | ")
                .append(format(bollingerMiddle)).append(" | ")
                .append(format(bollingerLower)).append("\n");
        sb.append("CCI: ").append(format(cci)).append("\n");
        sb.append("MFI: ").append(format(mfi)).append("\n");
        sb.append("SAR: ").append(format(parabolicSar)).append("\n");
        sb.append("轴心点: ").append(format(pivotPoint)).append("\n");
        sb.append("阻力位: ").append(format(resistance1)).append(" | ")
                .append(format(resistance2)).append(" | ")
                .append(format(resistance3)).append("\n");
        sb.append("支撑位: ").append(format(support1)).append(" | ")
                .append(format(support2)).append(" | ")
                .append(format(support3)).append("\n");
        
        return sb.toString();
    }
    
    /**
     * 生成CSV格式的行数据（按照指定的分组格式）
     */
    public String toCsvRow() {
        return String.join(",",
            // 基础数据
            dateTime.toString(),
            format(open),
            format(high),
            format(low),
            format(close),
            volume != null ? volume.toString() : "",
            
            // 移动平均
            format(ma20),
            format(ma50),
            format(ema12),
            format(ema26),
            
            // 动量指标
            format(rsi),
            format(macdLine),
            format(macdSignal),
            format(macdHistogram),
            
            // 布林带
            format(bollingerUpper),
            format(bollingerMiddle),
            format(bollingerLower),
            format(bollingerBandwidth),
            format(bollingerPercentB),
            
            // 振荡器
            format(cci),
            format(mfi),
            format(stochK),
            format(stochD),
            format(williamsR),
            
            // 趋势强度
            format(atr),
            format(adx),
            format(plusDI),
            format(minusDI),
            
            // 趋势反转
            format(parabolicSar),
            
            // 支撑阻力
            format(pivotPoint),
            format(resistance1),
            format(resistance2),
            format(resistance3),
            format(support1),
            format(support2),
            format(support3),
            
            // 成交量指标
            format(obv),
            format(volumeMA),
            format(volumeRatio),
            format(vwap)
        );
    }
    
    /**
     * 获取CSV格式的标题行
     */
    public static String getCsvHeader() {
        return String.join(",",
            // 基础数据
            "日期", "开盘", "最高", "最低", "收盘", "成交量",
            
            // 移动平均
            "MA20", "MA50", "EMA12", "EMA26",
            
            // 动量
            "RSI", "MACD线", "MACD信号", "MACD柱",
            
            // 布林带
            "布林上", "布林中", "布林下", "带宽", "%B",
            
            // 振荡器
            "CCI", "MFI", "Stoch_K", "Stoch_D", "Williams_R",
            
            // 趋势强度
            "ATR", "ADX", "+DI", "-DI",
            
            // 趋势反转
            "SAR",
            
            // 支撑阻力
            "轴心点", "R1", "R2", "R3", "S1", "S2", "S3",
            
            // 成交量
            "OBV", "成交量MA", "成交量比率", "VWAP"
        );
    }
    
    /**
     * 生成带分组注释的CSV标题（用于更好的可读性）
     */
    public static String getCsvHeaderWithComments() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 技术指标验证数据CSV格式\n");
        sb.append("# =============== 基础数据 ===============\n");
        sb.append("# 日期,开盘,最高,最低,收盘,成交量,\n");
        sb.append("# =============== 移动平均 ===============\n");
        sb.append("# MA20,MA50,EMA12,EMA26,\n");
        sb.append("# =============== 动量指标 ===============\n");
        sb.append("# RSI,MACD线,MACD信号,MACD柱,\n");
        sb.append("# =============== 布林带 ===============\n");
        sb.append("# 布林上,布林中,布林下,带宽,%B,\n");
        sb.append("# =============== 振荡器 ===============\n");
        sb.append("# CCI,MFI,Stoch_K,Stoch_D,Williams_R,\n");
        sb.append("# =============== 趋势强度 ===============\n");
        sb.append("# ATR,ADX,+DI,-DI,\n");
        sb.append("# =============== 趋势反转 ===============\n");
        sb.append("# SAR,\n");
        sb.append("# =============== 支撑阻力 ===============\n");
        sb.append("# 轴心点,R1,R2,R3,S1,S2,S3,\n");
        sb.append("# =============== 成交量 ===============\n");
        sb.append("# OBV,成交量MA,成交量比率,VWAP\n");
        sb.append("# =========================================\n");
        sb.append(getCsvHeader());
        return sb.toString();
    }
    
    /**
     * 生成增强版CSV标题（包含所有新功能）
     */
    public static String getCsvHeaderEnhanced() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 技术指标验证数据CSV格式（增强版）\n");
        sb.append("# =============== 基础数据 ===============\n");
        sb.append("# 日期,开盘,最高,最低,收盘,成交量,\n");
        sb.append("# =============== 移动平均 ===============\n");
        sb.append("# MA20,MA50,EMA12,EMA26,\n");
        sb.append("# =============== 动量指标 ===============\n");
        sb.append("# RSI,MACD线,MACD信号,MACD柱,\n");
        sb.append("# =============== 布林带 ===============\n");
        sb.append("# 布林上,布林中,布林下,带宽,%B,\n");
        sb.append("# =============== 振荡器 ===============\n");
        sb.append("# CCI,MFI,Stoch_K,Stoch_D,Williams_R,\n");
        sb.append("# =============== 趋势强度 ===============\n");
        sb.append("# ATR,ADX,+DI,-DI,\n");
        sb.append("# =============== 趋势反转 ===============\n");
        sb.append("# SAR,\n");
        sb.append("# =============== 支撑阻力 ===============\n");
        sb.append("# 轴心点,R1,R2,R3,S1,S2,S3,\n");
        sb.append("# =============== 成交量 ===============\n");
        sb.append("# OBV,成交量MA,成交量比率,VWAP,\n");
        sb.append("# =============== 分析评估 ===============\n");
        sb.append("# 警告级别,警告标记,健康度,相关性,交易信号,建议\n");
        sb.append("# =========================================\n");
        sb.append(getCsvHeader());
        sb.append(",警告级别,警告标记,健康度,相关性,交易信号,建议");
        return sb.toString();
    }
    
    /**
     * 生成带警告标记的CSV标题（兼容旧版）
     */
    public static String getCsvHeaderWithWarnings() {
        return getCsvHeaderEnhanced();
    }
    
    /**
     * 生成增强版CSV行（包含所有分析结果）
     */
    public String toCsvRowEnhanced() {
        String basicRow = toCsvRow();
        
        // 警告级别和标记
        String level = (flags != null) ? flags.getWarningLevelCode() : "";
        String warnings = (flags != null) ? flags.getWarningFlags() : "";
        
        // 健康度
        String healthStr = "";
        if (health != null) {
            healthStr = health.getStatus();
        } else if (healthScore != null) {
            healthStr = String.format("%.0f", healthScore);
        }
        
        // 相关性
        String correlStr = "";
        if (correlation != null) {
            correlStr = String.format("%.0f", correlation.getCorrelationScore());
        } else if (correlationSummary != null) {
            correlStr = correlationSummary;
        }
        
        // 交易信号
        String signal = "";
        if (correlation != null) {
            if (correlation.isBullishConfirmation()) {
                signal = "多头";
            } else if (correlation.isBearishConfirmation()) {
                signal = "空头";
            } else if (correlation.isConflictingSignals()) {
                signal = "冲突";
            } else {
                signal = "中性";
            }
        } else if (tradingSignal != null) {
            signal = tradingSignal;
        }
        
        // 建议
        String suggest = "";
        if (recommendation != null) {
            suggest = recommendation;
        } else if (correlation != null && correlation.getRecommendations() != null 
                   && !correlation.getRecommendations().isEmpty()) {
            suggest = correlation.getRecommendations().get(0);
        }
        
        // 组合所有字段
        return String.format("%s,%s,%s,%s,%s,%s,%s",
            basicRow, level, warnings, healthStr, correlStr, signal, suggest);
    }
    
    /**
     * 生成包含警告标记的CSV行（兼容旧版）
     */
    public String toCsvRowWithWarnings() {
        return toCsvRowEnhanced();
    }
    
    /**
     * 格式化数值显示
     */
    private String format(BigDecimal value) {
        if (value == null) {
            return "N/A";
        }
        return String.format("%.4f", value.doubleValue());
    }
    
    /**
     * 执行完整的技术分析（包括所有新功能）
     */
    public void performFullAnalysis(String timeframe, BigDecimal previousPrice, 
                                   BigDecimal previousRsi, BigDecimal previousMacd,
                                   BigDecimal previousStochK, int dataSize) {
        // 1. 检测极值标记
        if (close != null) {
            this.flags = IndicatorFlags.detectFlags(
                rsi, stochK, stochD, williamsR, cci, mfi,
                close, bollingerUpper, bollingerLower,
                bollingerBandwidth, bollingerPercentB,
                adx, volumeRatio, timeframe
            );
            this.warningFlags = (flags != null) ? flags.getWarningFlags() : "";
            this.warningLevel = (flags != null) ? flags.getWarningLevelCode() : "";
            this.warningDetails = (flags != null) ? flags.getLeveledWarnings() : "";
        }
        
        // 2. 相关性分析
        if (close != null && previousPrice != null) {
            // 转换Long类型的volume为BigDecimal
            BigDecimal volumeBD = (volume != null) ? BigDecimal.valueOf(volume) : null;
            
            this.correlation = IndicatorCorrelation.analyze(
                close, previousPrice, rsi, previousRsi,
                macdLine, macdSignal, previousMacd,
                stochK, stochD, previousStochK,
                williamsR, cci, mfi, adx, plusDI, minusDI,
                volumeBD, volumeMA, bollingerUpper, bollingerLower, bollingerPercentB
            );
            this.correlationSummary = (correlation != null) ? correlation.getSummary() : "";
        }
        
        // 3. 健康度评估（需要构建临时的TechnicalIndicators对象）
        if (flags != null && correlation != null) {
            // 简化版健康度评估
            this.healthScore = calculateSimpleHealthScore(dataSize);
            this.healthStatus = getHealthStatusFromScore(healthScore);
        }
        
        // 4. 生成综合建议
        generateRecommendation();
    }
    
    /**
     * 设置时间周期并检测极值标记（兼容旧版）
     */
    public void detectAndSetFlags(String timeframe) {
        performFullAnalysis(timeframe, null, null, null, null, 100);
    }
    
    /**
     * 计算简化的健康度分数
     */
    private Double calculateSimpleHealthScore(int dataSize) {
        double score = 100.0;
        
        // 基于警告扣分
        if (flags != null) {
            if (flags.getOverallWarning() != null) {
                switch (flags.getOverallWarning()) {
                    case ERROR:
                        score -= 40;
                        break;
                    case CRITICAL:
                        score -= 30;
                        break;
                    case HIGH:
                        score -= 20;
                        break;
                    case MEDIUM:
                        score -= 10;
                        break;
                    case LOW:
                        score -= 5;
                        break;
                }
            }
        }
        
        // 基于相关性调整
        if (correlation != null) {
            if (correlation.isConflictingSignals()) {
                score -= 15;
            }
            if (correlation.getCorrelationScore() < 50) {
                score -= 10;
            }
        }
        
        // 基于数据量调整
        if (dataSize < 50) {
            score -= 10;
        }
        
        return Math.max(0, Math.min(100, score));
    }
    
    /**
     * 根据分数获取健康状态
     */
    private String getHealthStatusFromScore(Double score) {
        if (score == null) return "";
        
        if (score >= 90) return "✅优秀";
        if (score >= 75) return "👍良好";
        if (score >= 60) return "👌一般";
        if (score >= 40) return "⚠️较差";
        return "❌危险";
    }
    
    /**
     * 生成综合建议
     */
    private void generateRecommendation() {
        // 优先级：ERROR警告 > CRITICAL警告 > 健康度低 > 相关性分析 > 普通警告
        
        // 1. ERROR级别强制谨慎建议
        if (flags != null && flags.getOverallWarning() != null) {
            if (flags.getOverallWarning() == IndicatorFlags.WarningLevel.ERROR) {
                this.recommendation = "❌ 存在严重警告，不建议交易";
                return;
            }
            if (flags.getOverallWarning() == IndicatorFlags.WarningLevel.CRITICAL) {
                this.recommendation = "🔴 存在多个极值警告，建议观望";
                return;
            }
        }
        
        // 2. 检查特定的危险标记
        if (flags != null) {
            // Stochastic K触底是异常，不是交易机会
            if (flags.isStochKZero()) {
                this.recommendation = "⚠️ Stochastic K异常，等待恢复正常";
                return;
            }
            // Williams %R极端值
            if (flags.isWilliamsAtBottom() || flags.isWilliamsAtTop()) {
                this.recommendation = "⚠️ Williams %R极端值，谨慎操作";
                return;
            }
        }
        
        // 3. 健康度低时不给积极建议
        if (healthScore != null && healthScore < 60) {
            this.recommendation = "🟡 指标健康度较低，建议观望";
            return;
        }
        
        // 4. 相关性分析建议（只在没有严重问题时采用）
        if (correlation != null) {
            // 检查是否有冲突
            if (correlation.isConflictingSignals()) {
                this.recommendation = "🔄 指标信号冲突，等待明确信号";
                return;
            }
            
            // 使用相关性分析的建议
            if (correlation.getRecommendations() != null && !correlation.getRecommendations().isEmpty()) {
                String corrRecommendation = correlation.getRecommendations().get(0);
                
                // 但如果有HIGH级别警告，降级建议
                if (flags != null && flags.getOverallWarning() == IndicatorFlags.WarningLevel.HIGH) {
                    if (corrRecommendation.contains("做多") || corrRecommendation.contains("做空")) {
                        this.recommendation = "🟠 " + corrRecommendation + "（但需注意风险）";
                        return;
                    }
                }
                
                this.recommendation = corrRecommendation;
                return;
            }
        }
        
        // 5. 默认建议（基于警告级别）
        if (flags != null && flags.getOverallWarning() != null) {
            switch (flags.getOverallWarning()) {
                case HIGH:
                    this.recommendation = "🟠 注意风险控制";
                    break;
                case MEDIUM:
                    this.recommendation = "🟡 谨慎参考";
                    break;
                case LOW:
                    this.recommendation = "🟢 正常参考";
                    break;
                case INFO:
                    this.recommendation = "ℹ️ 仅供参考";
                    break;
                default:
                    this.recommendation = "✅ 指标正常";
            }
        } else {
            this.recommendation = "指标正常";
        }
    }
}