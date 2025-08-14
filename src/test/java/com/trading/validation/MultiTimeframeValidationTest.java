package com.trading.validation;

import com.trading.domain.entity.MarketData;
import com.trading.service.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 多时间周期技术指标验证测试
 * 测试不同timeframe的预热数据策略和指标计算
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("多时间周期技术指标验证测试")
public class MultiTimeframeValidationTest {

    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired
    private IndicatorValidationService validationService;

    @ParameterizedTest
    @ValueSource(strings = {"5m", "15m", "30m", "60m", "1d"})
    @DisplayName("测试不同timeframe的技术指标计算")
    public void testIndicatorsWithDifferentTimeframes(String timeframe) {
        log.info("====== 测试 {} 周期的技术指标计算 ======", timeframe);
        
        try {
            String symbol = "00700.HK";
            
            // 获取动态配置信息
            log.info("配置信息：\n{}", IndicatorWarmupConfig.getConfigInfo(timeframe));
            
            // 动态计算需要的数据量
            int dataSize = IndicatorWarmupConfig.getDataFetchSize(timeframe);
            int minBars = IndicatorWarmupConfig.getMinimumBars(timeframe);
            
            // 设置时间范围
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = calculateStartTime(endTime, timeframe, dataSize);
            
            log.info("获取数据：{} 至 {}, 共{}条", startTime, endTime, dataSize);
            
            // 获取K线数据
            CompletableFuture<List<MarketData>> dataFuture = marketDataService.getOhlcvData(
                symbol,
                timeframe,
                startTime,
                endTime,
                dataSize
            );
            
            List<MarketData> marketDataList = dataFuture.get(30, TimeUnit.SECONDS);
            
            if (marketDataList == null || marketDataList.isEmpty()) {
                log.warn("未能获取{}周期的数据", timeframe);
                return;
            }
            
            log.info("成功获取{}条{}周期K线数据", marketDataList.size(), timeframe);
            
            // 验证数据充足性
            if (IndicatorWarmupConfig.isDataSufficient(marketDataList.size(), timeframe)) {
                // 生成验证报告
                ValidationReport report = validationService.generateValidationReport(symbol, marketDataList);
                
                // 输出关键信息
                log.info("生成验证报告：");
                log.info("- 总数据点：{}", report.getTotalDays());
                log.info("- 预热数据：{}条", minBars);
                log.info("- 验证数据：{}条", report.getTotalDays());
                
                // 输出最后一个数据点的指标
                if (!report.getDataPoints().isEmpty()) {
                    ValidationDataPoint lastPoint = report.getDataPoints().get(report.getDataPoints().size() - 1);
                    log.info("最新数据点 {}:", lastPoint.getDateTime());
                    log.info("- 收盘价: {}", lastPoint.getClose());
                    log.info("- RSI: {}", lastPoint.getRsi());
                    log.info("- MACD: {}", lastPoint.getMacdLine());
                    log.info("- 布林带上轨: {}", lastPoint.getBollingerUpper());
                }
            } else {
                log.error("{}周期数据量不足，跳过验证", timeframe);
            }
            
        } catch (Exception e) {
            log.error("测试{}周期失败", timeframe, e);
        }
    }
    
    /**
     * 根据timeframe和数据量计算开始时间
     */
    private LocalDateTime calculateStartTime(LocalDateTime endTime, String timeframe, int dataSize) {
        // 简单估算，实际可能需要更多时间（考虑周末和节假日）
        return switch(timeframe.toLowerCase()) {
            case "1m" -> endTime.minusMinutes(dataSize * 2);  // 考虑非交易时间
            case "5m" -> endTime.minusMinutes(dataSize * 5 * 2);
            case "15m" -> endTime.minusMinutes(dataSize * 15 * 2);
            case "30m" -> endTime.minusDays(dataSize * 30 / 60 / 6);  // 每天约6小时交易
            case "60m", "1h" -> endTime.minusDays(dataSize / 6);
            case "1d", "day" -> endTime.minusDays(dataSize * 2);  // 考虑周末
            default -> endTime.minusMonths(3);
        };
    }
}