package com.trading.validation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.trading.domain.entity.MarketData;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.service.MarketDataService;
import com.trading.strategy.TechnicalAnalysisService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 技术指标验证服务
 * 用于验证系统计算的技术指标准确性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorValidationService {

    private final MarketDataService marketDataService;
    private final TechnicalAnalysisService technicalAnalysisService;
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 使用提供的市场数据生成技术指标验证报告
     *
     * @param symbol 股票代码 (如: 00700.HK)
     * @param marketDataList 市场数据列表
     * @return 验证报告
     */
    public ValidationReport generateValidationReport(String symbol, List<MarketData> marketDataList) {
        try {
            log.info("使用提供的数据生成技术指标验证报告: symbol={}, dataSize={}", symbol, marketDataList.size());
            
            List<ValidationDataPoint> validationPoints = new ArrayList<>();
            
            // 获取timeframe（从第一条数据获取，如果没有则默认为1D）
            String timeframe = "1D";
            if (!marketDataList.isEmpty() && marketDataList.get(0).getTimeframe() != null) {
                timeframe = marketDataList.get(0).getTimeframe();
            }
            log.info("使用timeframe: {}", timeframe);
            
            // 使用动态预热配置计算预热期
            int warmupPeriod = com.trading.validation.IndicatorWarmupConfig.getMinimumBars(timeframe);
            log.info("使用{}条K线作为预热数据，timeframe={}", warmupPeriod, timeframe);
            
            // 验证数据充足性
            if (!com.trading.validation.IndicatorWarmupConfig.isDataSufficient(marketDataList.size(), timeframe)) {
                log.error("数据量不足：需要至少{}条K线，实际只有{}条", 
                         warmupPeriod + 10, marketDataList.size());
                return ValidationReport.error(symbol, 
                    String.format("数据量不足：需要至少%d条K线，实际只有%d条", 
                                  warmupPeriod + 10, marketDataList.size()));
            }
            
            // 输出预热信息
            String timeSpan = com.trading.validation.IndicatorWarmupConfig.getTimeSpanDescription(timeframe, warmupPeriod);
            log.info("预热数据覆盖时间跨度：{}", timeSpan);
            
            // 为每个数据点生成验证数据（从预热期后开始）
            for (int i = warmupPeriod; i < marketDataList.size(); i++) {
                List<MarketData> historicalData = marketDataList.subList(0, i + 1);
                MarketData currentData = marketDataList.get(i);
                
                // 直接使用历史数据计算技术指标
                TechnicalIndicators indicators = technicalAnalysisService.calculateIndicatorsFromData(
                    symbol, historicalData);
                
                if (indicators != null) {
                    // 创建验证数据点
                    ValidationDataPoint point = createValidationDataPoint(currentData, indicators);
                    validationPoints.add(point);
                } else {
                    log.warn("计算技术指标失败: date={}", 
                        currentData.getTimestamp());
                }
            }
            
            // 生成验证报告
            return ValidationReport.builder()
                .symbol(symbol)
                .validationTime(LocalDateTime.now())
                .totalDays(validationPoints.size())
                .dataPoints(validationPoints)
                .summary(String.format("为 %s 生成了 %d 个交易日的技术指标验证数据", 
                    symbol, validationPoints.size()))
                .build();
                
        } catch (Exception e) {
            log.error("生成验证报告失败: symbol={}, error={}", symbol, e.getMessage(), e);
            return ValidationReport.error(symbol, "生成验证报告失败: " + e.getMessage());
        }
    }

    /**
     * 为指定股票生成技术指标验证报告
     *
     * @param symbol 股票代码 (如: 00700.HK)
     * @param days 获取最近几天的数据
     * @return 验证报告
     */
    public CompletableFuture<ValidationReport> generateValidationReport(String symbol, int days) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("开始生成技术指标验证报告: symbol={}, days={}", symbol, days);
                
                // 计算时间范围
                LocalDateTime endTime = LocalDateTime.now();
                LocalDateTime startTime = endTime.minusDays(days + 10); // 多获取一些数据用于指标计算
                
                // 获取历史K线数据
                List<MarketData> historicalData = marketDataService.getOhlcvData(
                    symbol, "1d", startTime, endTime, days + 20
                ).get(30, TimeUnit.SECONDS);
                
                if (historicalData == null || historicalData.isEmpty()) {
                    log.warn("无法获取历史数据: symbol={}", symbol);
                    return ValidationReport.empty(symbol);
                }
                
                log.info("获取到{}条历史数据", historicalData.size());
                
                // 生成验证数据
                List<ValidationDataPoint> validationPoints = new ArrayList<>();
                
                // 取最近的days天数据
                int startIndex = Math.max(0, historicalData.size() - days);
                for (int i = startIndex; i < historicalData.size(); i++) {
                    MarketData currentData = historicalData.get(i);
                    
                    // 计算技术指标
                    TechnicalIndicators indicators = technicalAnalysisService.calculateIndicators(
                        symbol, "1d", currentData.getTimestamp()
                    ).get(10, TimeUnit.SECONDS);
                    
                    if (indicators != null) {
                        ValidationDataPoint point = ValidationDataPoint.builder()
                            // 基础数据
                            .date(currentData.getTimestamp().toLocalDate())
                            .dateTime(currentData.getTimestamp())
                            .open(currentData.getOpen())
                            .high(currentData.getHigh())
                            .low(currentData.getLow())
                            .close(currentData.getClose())
                            .volume(currentData.getVolume())
                            
                            // 移动平均线
                            .ma20(indicators.getSma20())
                            .ma50(indicators.getSma50())
                            .ema12(indicators.getEma12())
                            .ema26(indicators.getEma26())  // 修复：使用正确的getEma26()
                            
                            // 动量指标
                            .rsi(indicators.getRsi())
                            .macdLine(indicators.getMacdLine())
                            .macdSignal(indicators.getSignalLine())
                            .macdHistogram(indicators.getHistogram())
                            
                            // 布林带
                            .bollingerUpper(indicators.getUpperBand())
                            .bollingerMiddle(indicators.getMiddleBand())
                            .bollingerLower(indicators.getLowerBand())
                            .bollingerBandwidth(indicators.getBandwidth())
                            .bollingerPercentB(indicators.getPercentB())
                            
                            // 振荡器
                            .cci(indicators.getCci())
                            .mfi(indicators.getMfi())
                            .stochK(indicators.getStochK())
                            .stochD(indicators.getStochD())
                            .williamsR(indicators.getWilliamsR())  // 修复：使用实际计算的值
                            
                            // 趋势强度
                            .atr(indicators.getAtr())
                            .adx(indicators.getAdx())
                            .plusDI(indicators.getPlusDI())
                            .minusDI(indicators.getMinusDI())
                            
                            // 趋势反转
                            .parabolicSar(indicators.getParabolicSar())
                            
                            // 支撑阻力
                            .pivotPoint(indicators.getPivotPoint())
                            .resistance1(indicators.getResistance1())
                            .resistance2(indicators.getResistance2())
                            .resistance3(indicators.getResistance3())
                            .support1(indicators.getSupport1())
                            .support2(indicators.getSupport2())
                            .support3(indicators.getSupport3())
                            
                            // 成交量指标
                            .obv(indicators.getObv())
                            .volumeMA(indicators.getVolumeSma())
                            .volumeRatio(indicators.getVolumeRatio())
                            .vwap(indicators.getVwap())
                            .build();
                        
                        validationPoints.add(point);
                    }
                }
                
                // 生成验证报告
                ValidationReport report = ValidationReport.builder()
                    .symbol(symbol)
                    .validationTime(LocalDateTime.now())
                    .totalDays(validationPoints.size())
                    .dataPoints(validationPoints)
                    .summary(generateSummary(validationPoints))
                    .build();
                
                log.info("技术指标验证报告生成完成: symbol={}, 数据点数量={}", symbol, validationPoints.size());
                return report;
                
            } catch (Exception e) {
                log.error("生成验证报告失败: symbol={}", symbol, e);
                return ValidationReport.error(symbol, e.getMessage());
            }
        });
    }
    
    /**
     * 生成报告摘要
     */
    private String generateSummary(List<ValidationDataPoint> dataPoints) {
        if (dataPoints.isEmpty()) {
            return "无有效数据点";
        }
        
        ValidationDataPoint latest = dataPoints.get(dataPoints.size() - 1);
        
        StringBuilder summary = new StringBuilder();
        summary.append("最新数据 (").append(latest.getDateTime()).append("):\n");
        summary.append("收盘价: ").append(formatDecimal(latest.getClose())).append("\n");
        summary.append("RSI(14): ").append(formatDecimal(latest.getRsi())).append("\n");
        summary.append("MACD: ").append(formatDecimal(latest.getMacdLine())).append(" / ")
                .append(formatDecimal(latest.getMacdSignal())).append(" / ")
                .append(formatDecimal(latest.getMacdHistogram())).append("\n");
        summary.append("布林带: ").append(formatDecimal(latest.getBollingerUpper())).append(" / ")
                .append(formatDecimal(latest.getBollingerMiddle())).append(" / ")
                .append(formatDecimal(latest.getBollingerLower())).append("\n");
        summary.append("CCI: ").append(formatDecimal(latest.getCci())).append("\n");
        summary.append("MFI: ").append(formatDecimal(latest.getMfi())).append("\n");
        summary.append("SAR: ").append(formatDecimal(latest.getParabolicSar())).append("\n");
        
        return summary.toString();
    }
    
    /**
     * 格式化小数显示
     */
    private String formatDecimal(BigDecimal value) {
        if (value == null) {
            return "N/A";
        }
        return value.setScale(4, RoundingMode.HALF_UP).toString();
    }
    
    /**
     * 创建验证数据点
     */
    private ValidationDataPoint createValidationDataPoint(MarketData marketData, TechnicalIndicators indicators) {
        return ValidationDataPoint.builder()
            // 基础数据
            .date(marketData.getTimestamp().toLocalDate())
            .dateTime(marketData.getTimestamp())
            .open(marketData.getOpen())
            .high(marketData.getHigh())
            .low(marketData.getLow())
            .close(marketData.getClose())
            .volume(marketData.getVolume())
            
            // 移动平均线
            .ma20(indicators.getSma20())
            .ma50(indicators.getSma50())
            .ema12(indicators.getEma12())
            .ema26(indicators.getEma26())
            
            // 动量指标
            .rsi(indicators.getRsi())
            .macdLine(indicators.getMacdLine())
            .macdSignal(indicators.getSignalLine())
            .macdHistogram(indicators.getHistogram())
            
            // 布林带
            .bollingerUpper(indicators.getUpperBand())
            .bollingerMiddle(indicators.getMiddleBand())
            .bollingerLower(indicators.getLowerBand())
            .bollingerBandwidth(indicators.getBandwidth())
            .bollingerPercentB(indicators.getPercentB())
            
            // 振荡器
            .cci(indicators.getCci())
            .mfi(indicators.getMfi())
            .stochK(indicators.getStochK())
            .stochD(indicators.getStochD())
            .williamsR(indicators.getWilliamsR())
            
            // 趋势强度
            .atr(indicators.getAtr())
            .adx(indicators.getAdx())
            .plusDI(indicators.getPlusDI())
            .minusDI(indicators.getMinusDI())
            
            // 趋势反转
            .parabolicSar(indicators.getParabolicSar())
            
            // 支撑阻力
            .pivotPoint(indicators.getPivotPoint())
            .resistance1(indicators.getResistance1())
            .resistance2(indicators.getResistance2())
            .resistance3(indicators.getResistance3())
            .support1(indicators.getSupport1())
            .support2(indicators.getSupport2())
            .support3(indicators.getSupport3())
            
            // 成交量指标
            .obv(indicators.getObv())
            .volumeMA(indicators.getVolumeSma())
            .volumeRatio(indicators.getVolumeRatio())
            .vwap(indicators.getVwap())
            .build();
    }

    /**
     * 快速验证单个指标
     */
    public CompletableFuture<String> quickValidation(String symbol) {
        return generateValidationReport(symbol, 5)
            .thenApply(report -> {
                if (report.getDataPoints().isEmpty()) {
                    return "验证失败：无法获取数据";
                }
                
                return "快速验证结果:\n" + report.getSummary();
            });
    }
}