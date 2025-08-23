package com.trading.validation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.trading.domain.entity.MarketData;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.service.MarketDataService;
import com.trading.strategy.TechnicalAnalysisService;

/**
 * 技术指标验证测试
 * 用于生成技术指标数据，便于与富途APP进行人工对比验证
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("技术指标验证测试")
public class TechnicalIndicatorValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(TechnicalIndicatorValidationTest.class);

    @Autowired
    private TechnicalAnalysisService technicalAnalysisService;

    @Autowired
    private IndicatorValidationService validationService;
    
    @Autowired
    private MarketDataService marketDataService;

    @Test
    @DisplayName("生成00700.HK技术指标验证数据 - 使用真实FUTU数据")
    public void testGenerateValidationDataFor00700() {
        logger.info("开始生成00700.HK技术指标验证数据（使用真实FUTU数据）...");
        
        try {
            // 配置K线周期
            String timeframe = "30m";
            String symbol = "00700.HK";
            
            // 使用动态预热配置计算数据量
            int dataSize = com.trading.validation.IndicatorWarmupConfig.getDataFetchSize(timeframe);
            logger.info("Timeframe: {}, 需要获取 {} 条K线数据", timeframe, dataSize);
            logger.info(com.trading.validation.IndicatorWarmupConfig.getConfigInfo(timeframe));
            
            // 从FUTU获取真实的历史K线数据
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusMonths(3); // 获取最近3个月数据
            
            CompletableFuture<List<MarketData>> dataFuture = marketDataService.getOhlcvData(
                symbol,
                timeframe,
                startTime,
                endTime,
                dataSize,  // 使用动态计算的数据量
                RehabType.FORWARD
            );
            
            List<MarketData> marketDataList = dataFuture.get(30, TimeUnit.SECONDS);
            
            if (marketDataList == null || marketDataList.isEmpty()) {
                logger.warn("未能从FUTU获取真实数据，请确保FUTU OpenD正在运行");
                return;
            }
            
            logger.info("成功获取{}条真实K线数据", marketDataList.size());
        
        // 生成验证报告
        ValidationReport report = validationService.generateValidationReport("00700.HK", marketDataList);
        
        // 输出简要报告
        logger.info("=== 技术指标验证报告 ===");
        logger.info(report.generateSummaryReport());
        
        // 输出详细数据点（最近5个交易日）
        logger.info("\n=== 最近5个交易日详细数据 ===");
        List<ValidationDataPoint> dataPoints = report.getDataPoints();
        int startIndex = Math.max(0, dataPoints.size() - 5);
        
        for (int i = startIndex; i < dataPoints.size(); i++) {
            ValidationDataPoint point = dataPoints.get(i);
            logger.info("\n--- {} ---", point.getDateTime());
            logger.info("收盘价: {}", formatValue(point.getClose()));
            logger.info("RSI(14): {}", formatValue(point.getRsi()));
            logger.info("MACD: {} | {} | {}", 
                formatValue(point.getMacdLine()),
                formatValue(point.getMacdSignal()),
                formatValue(point.getMacdHistogram()));
            logger.info("布林带: {} | {} | {}", 
                formatValue(point.getBollingerUpper()),
                formatValue(point.getBollingerMiddle()),
                formatValue(point.getBollingerLower()));
            logger.info("CCI(20): {}", formatValue(point.getCci()));
            logger.info("MFI(14): {}", formatValue(point.getMfi()));
            logger.info("SAR: {}", formatValue(point.getParabolicSar()));
            logger.info("轴心点: {}", formatValue(point.getPivotPoint()));
            logger.info("阻力位: {} | {} | {}", 
                formatValue(point.getResistance1()),
                formatValue(point.getResistance2()),
                formatValue(point.getResistance3()));
            logger.info("支撑位: {} | {} | {}", 
                formatValue(point.getSupport1()),
                formatValue(point.getSupport2()),
                formatValue(point.getSupport3()));
        }
        
        // 生成CSV文件供进一步分析
        logger.info("\n=== CSV格式数据 ===");
        logger.info("可将以下数据保存为CSV文件进行详细对比：");
        logger.info(report.generateCsvReport());
        
            
        } catch (Exception e) {
            logger.error("获取真实数据失败", e);
        }
    }

    @Test
    @DisplayName("验证特定日期的技术指标计算 - 使用真实FUTU数据")
    public void testSpecificDateValidation() {
        logger.info("验证特定日期技术指标计算（使用真实FUTU数据）...");
        
        try {
            // 从FUTU获取真实数据
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusMonths(2); // 获取最近2个月数据
            
            CompletableFuture<List<MarketData>> dataFuture = marketDataService.getOhlcvData(
                "00700.HK",
                "1d",
                startTime,
                endTime,
                60,  // 获取60条数据
                RehabType.FORWARD
            );
            
            List<MarketData> testData = dataFuture.get(30, TimeUnit.SECONDS);
            
            if (testData == null || testData.isEmpty()) {
                logger.warn("未能从FUTU获取真实数据");
                return;
            }
        
        if (testData.size() >= 50) { // 确保有足够数据计算指标
            MarketData latestData = testData.get(testData.size() - 1);
            
            // 创建简化的验证报告来获取技术指标
            ValidationReport report = validationService.generateValidationReport("00700.HK", testData);
            List<ValidationDataPoint> dataPoints = report.getDataPoints();
            
            if (!dataPoints.isEmpty()) {
                ValidationDataPoint latestPoint = dataPoints.get(dataPoints.size() - 1);
            
                logger.info("=== 特定日期技术指标验证 ===");
                logger.info("测试日期: {}", latestPoint.getDate());
                logger.info("收盘价: {}", formatValue(latestPoint.getClose()));
                logger.info("成交量: {}", latestPoint.getVolume());
                
                logger.info("\n--- 核心指标 ---");
                logger.info("RSI(14): {}", formatValue(latestPoint.getRsi()));
                logger.info("MACD线: {}", formatValue(latestPoint.getMacdLine()));
                logger.info("MACD信号: {}", formatValue(latestPoint.getMacdSignal()));
                logger.info("MACD柱: {}", formatValue(latestPoint.getMacdHistogram()));
                
                logger.info("\n--- 布林带 ---");
                logger.info("上轨: {}", formatValue(latestPoint.getBollingerUpper()));
                logger.info("中轨: {}", formatValue(latestPoint.getBollingerMiddle()));
                logger.info("下轨: {}", formatValue(latestPoint.getBollingerLower()));
                
                logger.info("\n--- 新增指标 ---");
                logger.info("CCI(20): {}", formatValue(latestPoint.getCci()));
                logger.info("MFI(14): {}", formatValue(latestPoint.getMfi()));
                logger.info("抛物线SAR: {}", formatValue(latestPoint.getParabolicSar()));
                
                logger.info("\n--- Pivot Points ---");
                logger.info("轴心点: {}", formatValue(latestPoint.getPivotPoint()));
                logger.info("R1: {}", formatValue(latestPoint.getResistance1()));
                logger.info("R2: {}", formatValue(latestPoint.getResistance2()));
                logger.info("R3: {}", formatValue(latestPoint.getResistance3()));
                logger.info("S1: {}", formatValue(latestPoint.getSupport1()));
                logger.info("S2: {}", formatValue(latestPoint.getSupport2()));
                logger.info("S3: {}", formatValue(latestPoint.getSupport3()));
            }
        }
        
        } catch (Exception e) {
            logger.error("获取真实数据失败", e);
        }
    }

    /**
     * @deprecated 不再使用模拟数据，改用真实FUTU数据
     * 保留此方法仅供参考，实际测试请使用真实数据
     */
    @Deprecated
    private List<MarketData> generateSample00700Data_DEPRECATED() {
        List<MarketData> dataList = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.of(2024, 6, 1, 9, 30);
        
        // 使用更接近真实的价格数据（2024年6月-9月，00700.HK价格区间约350-470港币）
        // 以下是模拟的100个交易日数据，从6月开始到9月
        BigDecimal[] closePrices = {
            // 6月初 - 价格在360-380之间
            new BigDecimal("365.00"), new BigDecimal("368.50"), new BigDecimal("372.00"), 
            new BigDecimal("369.80"), new BigDecimal("373.40"), new BigDecimal("377.20"),
            new BigDecimal("375.60"), new BigDecimal("378.90"), new BigDecimal("382.00"),
            new BigDecimal("380.50"), new BigDecimal("384.20"), new BigDecimal("387.00"),
            new BigDecimal("385.40"), new BigDecimal("388.80"), new BigDecimal("392.00"),
            // 6月中下旬 - 上涨到390-410
            new BigDecimal("394.50"), new BigDecimal("397.20"), new BigDecimal("400.00"),
            new BigDecimal("402.80"), new BigDecimal("405.60"), new BigDecimal("408.40"),
            new BigDecimal("406.00"), new BigDecimal("410.20"), new BigDecimal("413.50"),
            new BigDecimal("411.80"), new BigDecimal("415.40"), new BigDecimal("418.00"),
            new BigDecimal("416.20"), new BigDecimal("420.00"), new BigDecimal("423.40"),
            // 7月初 - 继续上涨到420-440
            new BigDecimal("425.80"), new BigDecimal("428.60"), new BigDecimal("431.00"),
            new BigDecimal("433.50"), new BigDecimal("436.20"), new BigDecimal("439.00"),
            new BigDecimal("437.40"), new BigDecimal("441.00"), new BigDecimal("444.20"),
            new BigDecimal("442.50"), new BigDecimal("446.00"), new BigDecimal("449.40"),
            new BigDecimal("447.80"), new BigDecimal("451.20"), new BigDecimal("454.00"),
            // 7月中旬 - 达到高点450-470
            new BigDecimal("456.50"), new BigDecimal("459.20"), new BigDecimal("462.00"),
            new BigDecimal("464.80"), new BigDecimal("467.50"), new BigDecimal("470.00"),
            new BigDecimal("468.20"), new BigDecimal("465.50"), new BigDecimal("463.00"),
            // 7月底到8月初 - 回调到440-460
            new BigDecimal("460.50"), new BigDecimal("458.00"), new BigDecimal("455.20"),
            new BigDecimal("452.80"), new BigDecimal("450.00"), new BigDecimal("447.50"),
            new BigDecimal("445.00"), new BigDecimal("442.80"), new BigDecimal("440.50"),
            // 8月中旬 - 震荡下跌到420-440
            new BigDecimal("438.20"), new BigDecimal("435.80"), new BigDecimal("433.00"),
            new BigDecimal("430.50"), new BigDecimal("428.00"), new BigDecimal("425.50"),
            new BigDecimal("423.20"), new BigDecimal("420.80"), new BigDecimal("418.50"),
            // 8月底到9月初 - 继续下跌到400-420
            new BigDecimal("416.00"), new BigDecimal("413.50"), new BigDecimal("411.20"),
            new BigDecimal("408.80"), new BigDecimal("406.50"), new BigDecimal("404.00"),
            new BigDecimal("401.80"), new BigDecimal("399.50"), new BigDecimal("397.20"),
            // 9月中旬 - 在400-415区间震荡
            new BigDecimal("395.00"), new BigDecimal("398.50"), new BigDecimal("401.00"),
            new BigDecimal("403.50"), new BigDecimal("406.20"), new BigDecimal("408.80"),
            new BigDecimal("411.50"), new BigDecimal("414.00"), new BigDecimal("412.20"),
            new BigDecimal("410.50"), new BigDecimal("408.00"), new BigDecimal("405.80"),
            new BigDecimal("407.50"), new BigDecimal("409.20"), new BigDecimal("411.00"),
            // 9月20日前后 - 收于411左右
            new BigDecimal("413.20"), new BigDecimal("415.50"), new BigDecimal("413.80"),
            new BigDecimal("411.50"), new BigDecimal("409.20"), new BigDecimal("407.00"),
            new BigDecimal("409.50"), new BigDecimal("411.80"), new BigDecimal("414.20"),
            new BigDecimal("412.50"), new BigDecimal("410.80"), new BigDecimal("411.10")
        };
        
        for (int i = 0; i < closePrices.length && i < 100; i++) {
            LocalDateTime timestamp = baseTime.plusDays(i);
            BigDecimal close = closePrices[i];
            
            // 基于收盘价生成合理的开高低价格
            // 开盘价在收盘价±2%范围内
            BigDecimal open = close.multiply(BigDecimal.valueOf(0.98 + Math.random() * 0.04));
            
            // 最高价比收盘价和开盘价都高，但不超过3%
            BigDecimal maxOpenClose = open.max(close);
            BigDecimal high = maxOpenClose.multiply(BigDecimal.valueOf(1.0 + Math.random() * 0.03));
            
            // 最低价比收盘价和开盘价都低，但不低于3%
            BigDecimal minOpenClose = open.min(close);
            BigDecimal low = minOpenClose.multiply(BigDecimal.valueOf(0.97 + Math.random() * 0.03));
            
            // 成交量在1500万-4000万股之间（接近真实成交量）
            long volume = (long)(15_000_000 + Math.random() * 25_000_000);
            
            MarketData data = MarketData.builder()
                .symbol("00700.HK")
                .timestamp(timestamp)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume)
                .build();
                
            dataList.add(data);
        }
        
        return dataList;
    }

    /**
     * @deprecated 不再使用模拟数据，改用真实FUTU数据
     * 创建特定日期的测试数据
     */
    @Deprecated
    private List<MarketData> createTestDataForSpecificDate_DEPRECATED() {
        List<MarketData> dataList = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.of(2024, 6, 1, 9, 30);
        
        // 创建60个交易日的数据确保指标计算准确
        BigDecimal[] closePrices = {
            new BigDecimal("380.00"), new BigDecimal("382.50"), new BigDecimal("385.00"), 
            new BigDecimal("387.50"), new BigDecimal("390.00"), new BigDecimal("392.50"),
            new BigDecimal("395.00"), new BigDecimal("397.50"), new BigDecimal("400.00"),
            new BigDecimal("402.50"), new BigDecimal("405.00"), new BigDecimal("407.50"),
            new BigDecimal("410.00"), new BigDecimal("408.00"), new BigDecimal("406.00"),
            new BigDecimal("404.00"), new BigDecimal("402.00"), new BigDecimal("400.00"),
            new BigDecimal("398.00"), new BigDecimal("396.00"), new BigDecimal("394.00"),
            new BigDecimal("392.00"), new BigDecimal("390.00"), new BigDecimal("388.00"),
            new BigDecimal("386.00"), new BigDecimal("384.00"), new BigDecimal("382.00"),
            new BigDecimal("384.00"), new BigDecimal("386.00"), new BigDecimal("388.00"),
            new BigDecimal("390.00"), new BigDecimal("392.00"), new BigDecimal("394.00"),
            new BigDecimal("396.00"), new BigDecimal("398.00"), new BigDecimal("400.00"),
            new BigDecimal("402.00"), new BigDecimal("404.00"), new BigDecimal("406.00"),
            new BigDecimal("408.00"), new BigDecimal("410.00"), new BigDecimal("412.00"),
            new BigDecimal("414.00"), new BigDecimal("416.00"), new BigDecimal("418.00"),
            new BigDecimal("420.00"), new BigDecimal("418.50"), new BigDecimal("417.00"),
            new BigDecimal("415.50"), new BigDecimal("414.00"), new BigDecimal("412.50"),
            new BigDecimal("411.00"), new BigDecimal("409.50"), new BigDecimal("408.00"),
            new BigDecimal("406.50"), new BigDecimal("405.00"), new BigDecimal("403.50"),
            new BigDecimal("402.00"), new BigDecimal("400.50"), new BigDecimal("399.00"),
            new BigDecimal("397.50")
        };
        
        for (int i = 0; i < closePrices.length; i++) {
            LocalDateTime timestamp = baseTime.plusDays(i);
            BigDecimal close = closePrices[i];
            
            // 基于收盘价生成其他价格
            BigDecimal open = close.multiply(BigDecimal.valueOf(0.998 + Math.random() * 0.004));
            BigDecimal high = close.multiply(BigDecimal.valueOf(1.005 + Math.random() * 0.01));
            BigDecimal low = close.multiply(BigDecimal.valueOf(0.995 - Math.random() * 0.01));
            
            long volume = (long)(15_000_000 + Math.random() * 30_000_000);
            
            MarketData data = MarketData.builder()
                .symbol("00700.HK")
                .timestamp(timestamp)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume)
                .build();
                
            dataList.add(data);
        }
        
        return dataList;
    }

    /**
     * 格式化数值显示
     */
    private String formatValue(BigDecimal value) {
        if (value == null) {
            return "N/A";
        }
        return String.format("%.4f", value.doubleValue());
    }
}