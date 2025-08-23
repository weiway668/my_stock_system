package com.trading.validation;

import com.trading.domain.entity.MarketData;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.service.MarketDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 真实FUTU数据验证测试
 * 直接从FUTU API获取真实的00700.HK历史数据进行技术指标验证
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("FUTU真实数据验证测试")
public class FutuRealDataValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(FutuRealDataValidationTest.class);

    @Autowired(required = false)
    private FutuMarketDataService futuMarketDataService;
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired
    private IndicatorValidationService validationService;

    @Test
    @DisplayName("获取00700.HK真实历史K线数据")
    public void testGetRealHistoricalData() {
        logger.info("====== 开始获取00700.HK真实历史数据 ======");
        
        if (futuMarketDataService == null) {
            logger.error("FutuMarketDataService未初始化，请确保FUTU OpenD正在运行");
            return;
        }
        
        try {
            // 设置时间范围：获取最近3个月的数据
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusMonths(3);
            
            logger.info("获取K线数据: 00700.HK, {} 至 {}", startDate, endDate);
            
            // 获取日K线数据
            List<FutuKLine> kLines = futuMarketDataService.getHistoricalKLine(
                "00700.HK",
                startDate,
                endDate,
                FutuMarketDataService.KLineType.K_DAY
            );
            
            if (kLines == null || kLines.isEmpty()) {
                logger.warn("未获取到K线数据，请检查FUTU连接状态");
                return;
            }
            
            logger.info("成功获取{}条K线数据", kLines.size());
            
            // 显示最近10条数据
            logger.info("\n=== 最近10个交易日数据 ===");
            int startIndex = Math.max(0, kLines.size() - 10);
            for (int i = startIndex; i < kLines.size(); i++) {
                FutuKLine kline = kLines.get(i);
                logger.info("日期: {}, 开: {}, 高: {}, 低: {}, 收: {}, 量: {}", 
                    kline.getTimestamp().toLocalDate(),
                    kline.getOpen(),
                    kline.getHigh(),
                    kline.getLow(),
                    kline.getClose(),
                    kline.getVolume());
            }
            
        } catch (Exception e) {
            logger.error("获取历史数据失败", e);
        }
    }

    @Test
    @DisplayName("使用真实数据计算技术指标")
    public void testCalculateIndicatorsWithRealData() {
        logger.info("====== 使用真实数据计算技术指标 ======");
        
        try {
            // 设置时间范围
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusMonths(4); // 多获取一个月确保有足够数据
            
            logger.info("获取00700.HK历史数据并计算技术指标...");
            
            // 通过MarketDataService获取数据（已改为使用真实FUTU数据）
            CompletableFuture<List<MarketData>> dataFuture = marketDataService.getOhlcvData(
                "00700.HK",
                "1d",
                startTime,
                endTime,
                100,
                RehabType.FORWARD
            );
            
            List<MarketData> marketDataList = dataFuture.get(30, TimeUnit.SECONDS);
            
            if (marketDataList == null || marketDataList.isEmpty()) {
                logger.warn("未获取到市场数据");
                return;
            }
            
            logger.info("获取到{}条市场数据，生成验证报告...", marketDataList.size());
            
            // 生成验证报告
            ValidationReport report = validationService.generateValidationReport("00700.HK", marketDataList);
            
            // 输出报告摘要
            logger.info("\n=== 技术指标验证报告 ===");
            logger.info(report.generateSummaryReport());
            
            // 输出最近5天的详细数据
            List<ValidationDataPoint> dataPoints = report.getDataPoints();
            if (!dataPoints.isEmpty()) {
                logger.info("\n=== 最近5个交易日技术指标 ===");
                int start = Math.max(0, dataPoints.size() - 5);
                
                for (int i = start; i < dataPoints.size(); i++) {
                    ValidationDataPoint point = dataPoints.get(i);
                    logger.info("\n日期: {}", point.getDate());
                    logger.info("收盘: {} | RSI: {} | MACD: {}", 
                        formatValue(point.getClose()),
                        formatValue(point.getRsi()),
                        formatValue(point.getMacdLine()));
                    logger.info("布林带: {} / {} / {}", 
                        formatValue(point.getBollingerUpper()),
                        formatValue(point.getBollingerMiddle()),
                        formatValue(point.getBollingerLower()));
                    logger.info("CCI: {} | MFI: {} | SAR: {}", 
                        formatValue(point.getCci()),
                        formatValue(point.getMfi()),
                        formatValue(point.getParabolicSar()));
                    logger.info("轴心点: {} | R1: {} | S1: {}", 
                        formatValue(point.getPivotPoint()),
                        formatValue(point.getResistance1()),
                        formatValue(point.getSupport1()));
                }
            }
            
            // 输出CSV格式供详细对比
            logger.info("\n=== CSV数据（可复制到Excel对比） ===");
            String csvData = report.generateCsvReport();
            // 只输出前几行和最后几行
            String[] lines = csvData.split("\n");
            if (lines.length > 0) {
                logger.info(lines[0]); // 表头
                if (lines.length > 6) {
                    // 输出最后5行数据
                    for (int i = lines.length - 5; i < lines.length; i++) {
                        if (i > 0) logger.info(lines[i]);
                    }
                } else {
                    // 输出所有数据
                    for (String line : lines) {
                        logger.info(line);
                    }
                }
            }
            
            logger.info("\n请在富途APP中查看00700.HK对应日期的技术指标进行对比验证");
            
        } catch (Exception e) {
            logger.error("计算技术指标失败", e);
        }
    }

    @Test
    @DisplayName("验证最新交易日数据")
    public void testLatestTradingDayData() {
        logger.info("====== 验证最新交易日数据 ======");
        
        if (futuMarketDataService == null) {
            logger.error("FutuMarketDataService未初始化");
            return;
        }
        
        try {
            // 获取最近5个交易日的数据
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(10); // 考虑周末和假期
            
            List<FutuKLine> kLines = futuMarketDataService.getHistoricalKLine(
                "00700.HK",
                startDate,
                endDate,
                FutuMarketDataService.KLineType.K_DAY
            );
            
            if (kLines != null && !kLines.isEmpty()) {
                FutuKLine latestKLine = kLines.get(kLines.size() - 1);
                
                logger.info("\n=== 最新交易日数据 ===");
                logger.info("日期: {}", latestKLine.getTimestamp().toLocalDate());
                logger.info("开盘: {}", latestKLine.getOpen());
                logger.info("最高: {}", latestKLine.getHigh());
                logger.info("最低: {}", latestKLine.getLow());
                logger.info("收盘: {}", latestKLine.getClose());
                logger.info("成交量: {}", latestKLine.getVolume());
                logger.info("成交额: {}", latestKLine.getTurnover());
                logger.info("涨跌额: {}", latestKLine.getChangeValue());
                logger.info("涨跌幅: {}%", latestKLine.getChangeRate());
                
                logger.info("\n请在富途APP验证以上数据的准确性");
            }
            
        } catch (Exception e) {
            logger.error("获取最新交易日数据失败", e);
        }
    }
    
    private String formatValue(Object value) {
        if (value == null) {
            return "N/A";
        }
        if (value instanceof Number) {
            return String.format("%.4f", ((Number) value).doubleValue());
        }
        return value.toString();
    }
}