package com.trading.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标相关性分析
 * 用于检测指标之间的背离、确认和矛盾信号
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorCorrelation {
    
    // 背离检测
    private boolean priceMomentumDivergence;    // 价格与动量背离
    private boolean priceVolumeDivergence;      // 价格与成交量背离
    private boolean macdDivergence;             // MACD背离
    private boolean rsiDivergence;              // RSI背离
    private boolean stochasticDivergence;       // 随机指标背离
    
    // 确认信号
    private boolean bullishConfirmation;        // 多头确认（多个指标同时看涨）
    private boolean bearishConfirmation;        // 空头确认（多个指标同时看跌）
    private boolean trendConfirmation;          // 趋势确认（趋势指标一致）
    private boolean reversalConfirmation;       // 反转确认（多个反转信号）
    
    // 矛盾信号
    private boolean conflictingSignals;         // 指标信号冲突
    private boolean mixedTrend;                 // 混合趋势（部分看涨部分看跌）
    private boolean uncertainState;             // 不确定状态（信号模糊）
    
    // 趋势一致性
    private TrendAlignment trendAlignment;      // 趋势对齐程度
    private int bullishIndicatorCount;          // 看涨指标数量
    private int bearishIndicatorCount;          // 看跌指标数量
    private int neutralIndicatorCount;          // 中性指标数量
    
    // 相关性强度
    private double correlationScore;            // 相关性得分（0-100）
    private CorrelationLevel level;             // 相关性级别
    
    // 分析结果
    private List<String> findings;              // 发现的模式
    private List<String> recommendations;       // 建议操作
    
    /**
     * 趋势对齐程度枚举
     */
    public enum TrendAlignment {
        STRONG_BULLISH("强烈看涨"),      // 所有指标看涨
        MODERATE_BULLISH("温和看涨"),    // 多数指标看涨
        NEUTRAL("中性"),                  // 指标平衡
        MODERATE_BEARISH("温和看跌"),    // 多数指标看跌
        STRONG_BEARISH("强烈看跌"),      // 所有指标看跌
        CONFLICTING("冲突");              // 严重分歧
        
        private final String description;
        
        TrendAlignment(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 相关性级别枚举
     */
    public enum CorrelationLevel {
        VERY_HIGH("极高相关性", 80),     // 80-100分
        HIGH("高相关性", 60),            // 60-80分
        MODERATE("中等相关性", 40),      // 40-60分
        LOW("低相关性", 20),             // 20-40分
        VERY_LOW("极低相关性", 0);       // 0-20分
        
        private final String description;
        private final int threshold;
        
        CorrelationLevel(String description, int threshold) {
            this.description = description;
            this.threshold = threshold;
        }
        
        public String getDescription() {
            return description;
        }
        
        public int getThreshold() {
            return threshold;
        }
    }
    
    /**
     * 分析技术指标相关性
     */
    public static IndicatorCorrelation analyze(
            BigDecimal price,
            BigDecimal previousPrice,
            BigDecimal rsi,
            BigDecimal previousRsi,
            BigDecimal macdLine,
            BigDecimal macdSignal,
            BigDecimal previousMacdLine,
            BigDecimal stochK,
            BigDecimal stochD,
            BigDecimal previousStochK,
            BigDecimal williamsR,
            BigDecimal cci,
            BigDecimal mfi,
            BigDecimal adx,
            BigDecimal plusDI,
            BigDecimal minusDI,
            BigDecimal volume,
            BigDecimal volumeMA,
            BigDecimal upperBand,
            BigDecimal lowerBand,
            BigDecimal percentB) {
        
        IndicatorCorrelation correlation = new IndicatorCorrelation();
        List<String> findings = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        
        // 初始化计数器
        int bullishCount = 0;
        int bearishCount = 0;
        int neutralCount = 0;
        
        // 1. 分析价格趋势
        boolean priceUp = price != null && previousPrice != null && 
                         price.compareTo(previousPrice) > 0;
        boolean priceDown = price != null && previousPrice != null && 
                           price.compareTo(previousPrice) < 0;
        
        // 2. RSI分析
        if (rsi != null) {
            if (rsi.doubleValue() > 70) {
                bearishCount++; // RSI超买，可能回调
                
                // 检测RSI背离
                if (previousRsi != null && priceUp && 
                    rsi.compareTo(previousRsi) < 0) {
                    correlation.setRsiDivergence(true);
                    findings.add("RSI顶背离：价格创新高但RSI未创新高");
                    recommendations.add("警惕潜在回调风险");
                }
            } else if (rsi.doubleValue() < 30) {
                bullishCount++; // RSI超卖，可能反弹
                
                // 检测RSI背离
                if (previousRsi != null && priceDown && 
                    rsi.compareTo(previousRsi) > 0) {
                    correlation.setRsiDivergence(true);
                    findings.add("RSI底背离：价格创新低但RSI未创新低");
                    recommendations.add("关注潜在反弹机会");
                }
            } else if (rsi.doubleValue() > 50) {
                bullishCount++;
            } else if (rsi.doubleValue() < 50) {
                bearishCount++;
            } else {
                neutralCount++;
            }
        }
        
        // 3. MACD分析
        if (macdLine != null && macdSignal != null) {
            boolean macdBullish = macdLine.compareTo(macdSignal) > 0;
            boolean macdBearish = macdLine.compareTo(macdSignal) < 0;
            
            if (macdBullish) {
                bullishCount++;
                if (macdLine.compareTo(BigDecimal.ZERO) > 0) {
                    bullishCount++; // MACD在零轴上方，强势
                }
            } else if (macdBearish) {
                bearishCount++;
                if (macdLine.compareTo(BigDecimal.ZERO) < 0) {
                    bearishCount++; // MACD在零轴下方，弱势
                }
            }
            
            // 检测MACD背离
            if (previousMacdLine != null) {
                if (priceUp && macdLine.compareTo(previousMacdLine) < 0) {
                    correlation.setMacdDivergence(true);
                    findings.add("MACD顶背离：价格上涨但MACD下降");
                } else if (priceDown && macdLine.compareTo(previousMacdLine) > 0) {
                    correlation.setMacdDivergence(true);
                    findings.add("MACD底背离：价格下跌但MACD上升");
                }
            }
        }
        
        // 4. Stochastic分析
        if (stochK != null && stochD != null) {
            double kValue = stochK.doubleValue();
            double dValue = stochD.doubleValue();
            
            // 特殊处理：K值异常（接近0）
            if (kValue <= 0.01) {
                // K=0是计算异常，不是交易信号
                neutralCount++;
                findings.add("Stochastic K值异常（≈ 0），指标不可靠");
                recommendations.add("等待Stochastic指标恢复正常");
                // 不增加多头计数，避免误判
            } else if (kValue > 80) {
                bearishCount++; // 超买
            } else if (kValue < 20) {
                bullishCount++; // 超卖
            } else if (kValue > dValue) {
                bullishCount++; // K线上穿D线
            } else if (kValue < dValue) {
                bearishCount++; // K线下穿D线
            }
            
            // 检测Stochastic背离
            if (previousStochK != null) {
                if (priceUp && stochK.compareTo(previousStochK) < 0) {
                    correlation.setStochasticDivergence(true);
                    findings.add("Stochastic顶背离");
                } else if (priceDown && stochK.compareTo(previousStochK) > 0) {
                    correlation.setStochasticDivergence(true);
                    findings.add("Stochastic底背离");
                }
            }
            
            // K/D背离
            if (Math.abs(kValue - dValue) > 20) {
                correlation.setStochasticDivergence(true);
                findings.add("Stochastic K/D背离过大");
            }
        }
        
        // 5. Williams %R分析
        if (williamsR != null) {
            double wrValue = williamsR.doubleValue();
            if (wrValue > -20) {
                bearishCount++; // 超买
            } else if (wrValue < -80) {
                bullishCount++; // 超卖
            } else if (wrValue > -50) {
                bearishCount++;
            } else {
                bullishCount++;
            }
        }
        
        // 6. CCI分析
        if (cci != null) {
            double cciValue = cci.doubleValue();
            if (cciValue > 100) {
                bullishCount++; // 强势
            } else if (cciValue < -100) {
                bearishCount++; // 弱势
            } else {
                neutralCount++;
            }
        }
        
        // 7. MFI分析
        if (mfi != null) {
            double mfiValue = mfi.doubleValue();
            if (mfiValue > 80) {
                bearishCount++; // 资金流出压力
            } else if (mfiValue < 20) {
                bullishCount++; // 资金流入机会
            } else if (mfiValue > 50) {
                bullishCount++;
            } else {
                bearishCount++;
            }
        }
        
        // 8. ADX趋势分析
        if (adx != null && plusDI != null && minusDI != null) {
            double adxValue = adx.doubleValue();
            if (adxValue > 25) {
                // 强趋势
                if (plusDI.compareTo(minusDI) > 0) {
                    bullishCount += 2; // 强上升趋势
                    findings.add("ADX显示强上升趋势");
                } else {
                    bearishCount += 2; // 强下降趋势
                    findings.add("ADX显示强下降趋势");
                }
            } else {
                neutralCount++; // 无明显趋势
                findings.add("ADX显示趋势不明显");
            }
        }
        
        // 9. 成交量分析
        if (volume != null && volumeMA != null) {
            boolean volumeHigh = volume.compareTo(volumeMA.multiply(BigDecimal.valueOf(1.5))) > 0;
            boolean volumeLow = volume.compareTo(volumeMA.multiply(BigDecimal.valueOf(0.5))) < 0;
            
            if (priceUp && volumeHigh) {
                bullishCount++; // 价涨量增，健康上涨
                findings.add("价涨量增，上涨动能充足");
            } else if (priceUp && volumeLow) {
                correlation.setPriceVolumeDivergence(true);
                findings.add("价涨量缩，上涨动能不足");
                recommendations.add("警惕虚假突破");
            } else if (priceDown && volumeHigh) {
                bearishCount++; // 价跌量增，恐慌抛售
                findings.add("价跌量增，下跌动能强劲");
            } else if (priceDown && volumeLow) {
                correlation.setPriceVolumeDivergence(true);
                findings.add("价跌量缩，下跌动能减弱");
                recommendations.add("关注止跌信号");
            }
        }
        
        // 10. 布林带分析
        if (percentB != null) {
            double pbValue = percentB.doubleValue();
            if (pbValue > 1) {
                bearishCount++; // 价格在上轨之上，超买
            } else if (pbValue < 0) {
                bullishCount++; // 价格在下轨之下，超卖
            } else if (pbValue > 0.8) {
                bearishCount++;
            } else if (pbValue < 0.2) {
                bullishCount++;
            }
        }
        
        // 设置计数
        correlation.setBullishIndicatorCount(bullishCount);
        correlation.setBearishIndicatorCount(bearishCount);
        correlation.setNeutralIndicatorCount(neutralCount);
        
        // 判断趋势对齐
        int totalSignals = bullishCount + bearishCount;
        if (totalSignals > 0) {
            double bullishRatio = (double) bullishCount / totalSignals;
            
            if (bullishRatio > 0.8) {
                correlation.setTrendAlignment(TrendAlignment.STRONG_BULLISH);
                correlation.setBullishConfirmation(true);
                findings.add("强烈看涨共识");
                
                // 检查是否有异常指标
                boolean hasAbnormalIndicator = false;
                if (stochK != null && stochK.doubleValue() <= 0.01) {
                    hasAbnormalIndicator = true;
                }
                if (williamsR != null && (williamsR.doubleValue() <= -100 || williamsR.doubleValue() >= 0)) {
                    hasAbnormalIndicator = true;
                }
                
                if (!hasAbnormalIndicator) {
                    recommendations.add("考虑做多机会");
                } else {
                    recommendations.add("多头信号但存在异常指标，谨慎操作");
                }
            } else if (bullishRatio > 0.6) {
                correlation.setTrendAlignment(TrendAlignment.MODERATE_BULLISH);
                correlation.setBullishConfirmation(true);
                findings.add("温和看涨倾向");
            } else if (bullishRatio < 0.2) {
                correlation.setTrendAlignment(TrendAlignment.STRONG_BEARISH);
                correlation.setBearishConfirmation(true);
                findings.add("强烈看跌共识");
                
                // 同样检查异常
                boolean hasAbnormalIndicator = false;
                if (stochK != null && stochK.doubleValue() <= 0.01) {
                    hasAbnormalIndicator = true;
                }
                
                if (!hasAbnormalIndicator) {
                    recommendations.add("考虑做空或观望");
                } else {
                    recommendations.add("空头信号但指标异常，建议观望");
                }
            } else if (bullishRatio < 0.4) {
                correlation.setTrendAlignment(TrendAlignment.MODERATE_BEARISH);
                correlation.setBearishConfirmation(true);
                findings.add("温和看跌倾向");
            } else {
                correlation.setTrendAlignment(TrendAlignment.NEUTRAL);
                correlation.setMixedTrend(true);
                findings.add("多空分歧，趋势不明");
                recommendations.add("等待明确信号");
            }
        } else {
            correlation.setTrendAlignment(TrendAlignment.NEUTRAL);
            correlation.setUncertainState(true);
        }
        
        // 检测冲突信号
        boolean hasBackDivergence = correlation.isRsiDivergence() || 
                                   correlation.isMacdDivergence() || 
                                   correlation.isStochasticDivergence();
        boolean hasVolumeDivergence = correlation.isPriceVolumeDivergence();
        
        if (hasBackDivergence || hasVolumeDivergence) {
            correlation.setConflictingSignals(true);
            if (!recommendations.contains("谨慎操作，等待确认")) {
                recommendations.add("存在背离信号，谨慎操作");
            }
        }
        
        // 计算相关性得分
        double score = calculateCorrelationScore(
            bullishCount, bearishCount, neutralCount,
            hasBackDivergence, hasVolumeDivergence
        );
        
        // 如果有异常指标，降低得分
        if (stochK != null && stochK.doubleValue() <= 0.01) {
            score = Math.max(0, score - 25); // K=0大幅降低得分
            findings.add("⚠️ 相关性得分因Stochastic异常而降低");
        }
        if (williamsR != null && (williamsR.doubleValue() <= -100 || williamsR.doubleValue() >= 0)) {
            score = Math.max(0, score - 15); // Williams %R极端值降低得分
        }
        
        correlation.setCorrelationScore(score);
        
        // 设置相关性级别
        if (score >= 80) {
            correlation.setLevel(CorrelationLevel.VERY_HIGH);
        } else if (score >= 60) {
            correlation.setLevel(CorrelationLevel.HIGH);
        } else if (score >= 40) {
            correlation.setLevel(CorrelationLevel.MODERATE);
        } else if (score >= 20) {
            correlation.setLevel(CorrelationLevel.LOW);
        } else {
            correlation.setLevel(CorrelationLevel.VERY_LOW);
        }
        
        // 设置发现和建议
        correlation.setFindings(findings);
        correlation.setRecommendations(recommendations);
        
        return correlation;
    }
    
    /**
     * 计算相关性得分
     */
    private static double calculateCorrelationScore(
            int bullishCount, int bearishCount, int neutralCount,
            boolean hasDivergence, boolean hasVolumeDivergence) {
        
        double score = 100.0;
        int total = bullishCount + bearishCount + neutralCount;
        
        if (total == 0) {
            return 0;
        }
        
        // 一致性得分（越一致得分越高）
        double maxCount = Math.max(bullishCount, bearishCount);
        double consistencyRatio = maxCount / total;
        score = consistencyRatio * 100;
        
        // 背离扣分
        if (hasDivergence) {
            score -= 20;
        }
        if (hasVolumeDivergence) {
            score -= 10;
        }
        
        // 中性指标过多扣分
        double neutralRatio = (double) neutralCount / total;
        if (neutralRatio > 0.3) {
            score -= neutralRatio * 20;
        }
        
        return Math.max(0, Math.min(100, score));
    }
    
    /**
     * 生成相关性摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        
        if (trendAlignment != null) {
            sb.append("趋势: ").append(trendAlignment.getDescription());
        }
        
        if (level != null) {
            sb.append(" | 相关性: ").append(level.getDescription());
            sb.append(" (").append(String.format("%.1f", correlationScore)).append("分)");
        }
        
        sb.append(" | 多空比: ").append(bullishIndicatorCount)
          .append("/").append(bearishIndicatorCount);
        
        if (conflictingSignals) {
            sb.append(" | ⚠️冲突");
        }
        
        return sb.toString();
    }
    
    /**
     * 获取简短标记（用于CSV）
     */
    public String getFlags() {
        List<String> flags = new ArrayList<>();
        
        if (rsiDivergence) flags.add("RSI背离");
        if (macdDivergence) flags.add("MACD背离");
        if (stochasticDivergence) flags.add("Stoch背离");
        if (priceVolumeDivergence) flags.add("量价背离");
        if (bullishConfirmation) flags.add("多头确认");
        if (bearishConfirmation) flags.add("空头确认");
        if (conflictingSignals) flags.add("信号冲突");
        
        return String.join(",", flags);
    }
}