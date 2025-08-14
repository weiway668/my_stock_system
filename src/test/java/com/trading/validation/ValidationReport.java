package com.trading.validation;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标验证报告
 * 包含验证结果和分析数据
 */
@Data
@Builder
public class ValidationReport {
    
    private String symbol;                          // 股票代码
    private LocalDateTime validationTime;          // 验证时间
    private int totalDays;                         // 总天数
    @Builder.Default
    private List<ValidationDataPoint> dataPoints = new ArrayList<>();  // 验证数据点
    private String summary;                        // 报告摘要
    private String errorMessage;                   // 错误信息（如果有）
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * 生成完整的验证报告
     */
    public String generateFullReport() {
        StringBuilder report = new StringBuilder();
        
        // 报告头部
        report.append("=" .repeat(80)).append("\n");
        report.append("技术指标验证报告\n");
        report.append("=" .repeat(80)).append("\n");
        report.append("股票代码: ").append(symbol).append("\n");
        report.append("验证时间: ").append(validationTime.format(FORMATTER)).append("\n");
        report.append("数据天数: ").append(totalDays).append("\n");
        
        if (errorMessage != null) {
            report.append("错误信息: ").append(errorMessage).append("\n");
            return report.toString();
        }
        
        report.append("\n");
        
        // 报告摘要
        if (summary != null) {
            report.append("报告摘要:\n");
            report.append("-".repeat(40)).append("\n");
            report.append(summary).append("\n");
        }
        
        // 详细数据
        if (!dataPoints.isEmpty()) {
            report.append("\n详细技术指标数据:\n");
            report.append("=".repeat(80)).append("\n");
            
            for (int i = 0; i < dataPoints.size(); i++) {
                ValidationDataPoint point = dataPoints.get(i);
                report.append("\n第").append(i + 1).append("天 ");
                report.append(point.toReadableString());
                report.append("-".repeat(80)).append("\n");
            }
        }
        
        // 使用说明
        report.append("\n使用说明:\n");
        report.append("-".repeat(40)).append("\n");
        report.append("1. 请在富途APP中查看相同日期的技术指标\n");
        report.append("2. 对比RSI、MACD、布林带等核心指标数值\n");
        report.append("3. 重点关注最近几天的数据准确性\n");
        report.append("4. 如发现较大差异，请检查参数设置\n");
        
        return report.toString();
    }
    
    /**
     * 生成简要报告
     */
    public String generateSummaryReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("技术指标验证报告 - ").append(symbol).append("\n");
        report.append("验证时间: ").append(validationTime.format(FORMATTER)).append("\n");
        report.append("数据天数: ").append(totalDays).append("\n\n");
        
        if (errorMessage != null) {
            report.append("错误: ").append(errorMessage).append("\n");
            return report.toString();
        }
        
        if (summary != null) {
            report.append(summary);
        }
        
        return report.toString();
    }
    
    /**
     * 生成增强CSV格式报告（包含所有新功能）
     */
    public String generateEnhancedCsvReport() {
        StringBuilder csv = new StringBuilder();
        
        // CSV头部信息
        csv.append("# 技术指标验证报告（增强版）\n");
        csv.append("# 股票代码: ").append(symbol).append("\n");
        csv.append("# 验证时间: ").append(validationTime.format(FORMATTER)).append("\n");
        csv.append("# 数据天数: ").append(totalDays).append("\n");
        csv.append("# \n");
        csv.append("# 📊 数据说明：\n");
        csv.append("# - 所有价格数据单位为港币(HKD)\n");
        csv.append("# - 成交量单位为股\n");
        csv.append("# - 技术指标参数根据时间周期动态调整\n");
        csv.append("# - N/A表示数据不可用或尚未计算\n");
        csv.append("# \n");
        csv.append("# ⚠️ 警告级别说明：\n");
        csv.append("#   INFO: 信息提示 ℹ️\n");
        csv.append("#   WARN: 需要关注 🟡\n");
        csv.append("#   ERROR: 严重警告 🔴\n");
        csv.append("# \n");
        csv.append("# 🎯 警告标记说明：\n");
        csv.append("#   RSI!: RSI极值(< 20或> 80)\n");
        csv.append("#   K=0!: Stochastic K值触底\n");
        csv.append("#   Stoch!: Stochastic极值(< 5或> 95)\n");
        csv.append("#   WR!: Williams %R极值(触底-100或触顶0)\n");
        csv.append("#   CCI!: CCI极值(< -200或> 200)\n");
        csv.append("#   MFI!: MFI极值(< 10或> 90)\n");
        csv.append("#   BB!: 价格突破布林带\n");
        csv.append("#   Vol!: 成交量异常\n");
        csv.append("# \n");
        csv.append("# 📡 交易信号说明：\n");
        csv.append("#   多头: 看涨信号确认\n");
        csv.append("#   空头: 看跌信号确认\n");
        csv.append("#   中性: 无明确方向\n");
        csv.append("#   冲突: 指标信号矛盾\n");
        csv.append("# \n");
        csv.append("# 📊 健康度评分：\n");
        csv.append("#   ✅ 90-100: 优秀\n");
        csv.append("#   👍 75-90: 良好\n");
        csv.append("#   👌 60-75: 一般\n");
        csv.append("#   ⚠️ 40-60: 较差\n");
        csv.append("#   ❌ 0-40: 危险\n");
        csv.append("# \n");
        
        if (errorMessage != null) {
            csv.append("# 错误: ").append(errorMessage).append("\n");
            return csv.toString();
        }
        
        // 添加增强版CSV标题
        csv.append(ValidationDataPoint.getCsvHeaderEnhanced()).append("\n");
        
        // 数据行（包含所有分析结果）
        ValidationDataPoint previousPoint = null;
        for (int i = 0; i < dataPoints.size(); i++) {
            ValidationDataPoint point = dataPoints.get(i);
            
            // 为每个数据点执行完整分析
            if (previousPoint != null) {
                point.performFullAnalysis(
                    "30m", // 默认时间周期，实际应从数据获取
                    previousPoint.getClose(),
                    previousPoint.getRsi(),
                    previousPoint.getMacdLine(),
                    previousPoint.getStochK(),
                    dataPoints.size()
                );
            } else {
                // 第一个点没有历史数据
                point.performFullAnalysis("30m", null, null, null, null, dataPoints.size());
            }
            
            csv.append(point.toCsvRowEnhanced()).append("\n");
            previousPoint = point;
        }
        
        // 添加总结部分
        csv.append("# \n");
        csv.append("# ========== 分析总结 ==========\n");
        csv.append("# 最新数据点分析：\n");
        if (!dataPoints.isEmpty()) {
            ValidationDataPoint latest = dataPoints.get(dataPoints.size() - 1);
            if (latest.getCorrelation() != null) {
                csv.append("# 趋势对齐: ").append(latest.getCorrelation().getTrendAlignment()).append("\n");
                csv.append("# 相关性得分: ").append(String.format("%.1f", latest.getCorrelation().getCorrelationScore())).append("\n");
                if (!latest.getCorrelation().getFindings().isEmpty()) {
                    csv.append("# 发现: ").append(String.join("; ", latest.getCorrelation().getFindings())).append("\n");
                }
            }
        }
        csv.append("# =============================\n");
        
        return csv.toString();
    }
    
    /**
     * 生成CSV格式报告（兼容旧版）
     */
    public String generateCsvReport() {
        return generateEnhancedCsvReport();
    }
    
    /**
     * 获取最新数据点
     */
    public ValidationDataPoint getLatestDataPoint() {
        if (dataPoints.isEmpty()) {
            return null;
        }
        return dataPoints.get(dataPoints.size() - 1);
    }
    
    /**
     * 创建空报告
     */
    public static ValidationReport empty(String symbol) {
        return ValidationReport.builder()
            .symbol(symbol)
            .validationTime(LocalDateTime.now())
            .totalDays(0)
            .dataPoints(new ArrayList<>())
            .summary("无可用数据")
            .build();
    }
    
    /**
     * 创建错误报告
     */
    public static ValidationReport error(String symbol, String errorMessage) {
        return ValidationReport.builder()
            .symbol(symbol)
            .validationTime(LocalDateTime.now())
            .totalDays(0)
            .dataPoints(new ArrayList<>())
            .errorMessage(errorMessage)
            .summary("验证失败: " + errorMessage)
            .build();
    }
    
    /**
     * 检查报告是否有效
     */
    public boolean isValid() {
        return errorMessage == null && !dataPoints.isEmpty();
    }
}