package com.trading.validation;

import com.trading.domain.entity.MarketData;
import com.trading.service.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CSV输出格式验证测试
 * 验证改进的CSV输出格式是否正确
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("CSV输出格式验证测试")
public class CsvOutputValidationTest {

    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired
    private IndicatorValidationService validationService;

    @Test
    @DisplayName("测试增强版CSV输出格式")
    public void testEnhancedCsvFormat() {
        log.info("====== 测试增强版CSV输出格式 ======");
        
        try {
            String symbol = "00700.HK";
            String timeframe = "30m";
            
            // 计算时间范围（30分钟K线需要更长时间范围才能获取足够数据）
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(10);  // 增加到10天以获取更多30分钟K线
            
            log.info("获取{}的{}周期数据", symbol, timeframe);
            
            // 获取K线数据
            CompletableFuture<List<MarketData>> dataFuture = marketDataService.getOhlcvData(
                symbol,
                timeframe,
                startTime,
                endTime,
                200  // 增加到200条以确保有足够数据计算指标
            );
            
            List<MarketData> marketDataList = dataFuture.get(30, TimeUnit.SECONDS);
            
            assertNotNull(marketDataList, "市场数据不应为空");
            assertFalse(marketDataList.isEmpty(), "市场数据列表不应为空");
            
            log.info("成功获取{}条K线数据", marketDataList.size());
            
            // 生成验证报告
            ValidationReport report = validationService.generateValidationReport(symbol, marketDataList);
            
            assertNotNull(report, "验证报告不应为空");
            assertTrue(report.isValid(), "验证报告应该有效");
            
            // 生成增强版CSV内容
            String csvContent = report.generateEnhancedCsvReport();
            assertNotNull(csvContent, "CSV内容不应为空");
            
            // 验证增强版CSV格式
            String[] lines = csvContent.split("\n");
            assertTrue(lines.length > 10, "CSV应包含标题行和数据行");
            
            // 验证新增的分析字段
            assertTrue(csvContent.contains("# =============== 分析评估 ==============="), "应包含分析评估分组");
            assertTrue(csvContent.contains("警告级别"), "应包含警告级别列");
            assertTrue(csvContent.contains("健康度"), "应包含健康度列");
            assertTrue(csvContent.contains("相关性"), "应包含相关性列");
            assertTrue(csvContent.contains("交易信号"), "应包含交易信号列");
            assertTrue(csvContent.contains("建议"), "应包含建议列");
            
            // 验证警告级别说明
            assertTrue(csvContent.contains("⚠️ 警告级别说明"), "应包含警告级别说明");
            assertTrue(csvContent.contains("INFO: 信息提示"), "应包含INFO级别说明");
            assertTrue(csvContent.contains("WARN: 需要关注"), "应包含WARN级别说明");
            assertTrue(csvContent.contains("ERROR: 严重警告"), "应包含ERROR级别说明");
            
            // 验证交易信号说明
            assertTrue(csvContent.contains("📡 交易信号说明"), "应包含交易信号说明");
            
            // 验证健康度评分说明
            assertTrue(csvContent.contains("📊 健康度评分"), "应包含健康度评分说明");
            
            // 验证分析总结
            assertTrue(csvContent.contains("========== 分析总结 =========="), "应包含分析总结部分");
            
            // 保存CSV文件
            File outputDir = new File("output");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            String filename = String.format("output/validation_%s_%s_%s.csv", 
                symbol.replace(".", "_"), 
                timeframe,
                LocalDateTime.now().toString().replace(":", "-").substring(0, 19));
            
            try (FileWriter writer = new FileWriter(filename)) {
                writer.write(csvContent);
                log.info("CSV文件已保存到: {}", filename);
            }
            
            // 验证文件存在
            File csvFile = new File(filename);
            assertTrue(csvFile.exists(), "CSV文件应该存在");
            assertTrue(csvFile.length() > 0, "CSV文件不应为空");
            
            // 输出部分CSV内容用于验证
            log.info("CSV内容预览（前20行）:");
            int lineCount = 0;
            for (String line : lines) {
                if (lineCount++ < 20) {
                    log.info(line);
                } else {
                    break;
                }
            }
            
            // 验证数据点
            assertFalse(report.getDataPoints().isEmpty(), "应包含验证数据点");
            
            ValidationDataPoint firstPoint = report.getDataPoints().get(0);
            assertNotNull(firstPoint.getClose(), "收盘价不应为空");
            assertNotNull(firstPoint.getVolume(), "成交量不应为空");
            
            log.info("✅ CSV格式验证成功！");
            log.info("- 总行数: {}", lines.length);
            log.info("- 数据点数: {}", report.getDataPoints().size());
            log.info("- 文件大小: {} bytes", csvFile.length());
            
        } catch (Exception e) {
            log.error("测试失败", e);
            fail("测试失败: " + e.getMessage());
        }
    }
    
    @Test
    @DisplayName("测试日线CSV输出格式")
    public void testDailyCsvFormat() {
        log.info("====== 测试日线CSV输出格式 ======");
        
        try {
            String symbol = "00700.HK";
            String timeframe = "1d";
            
            // 计算时间范围（获取更多数据以满足指标计算需求）
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(200);  // 获取200天数据
            
            log.info("获取{}的{}周期数据", symbol, timeframe);
            
            // 获取K线数据
            CompletableFuture<List<MarketData>> dataFuture = marketDataService.getOhlcvData(
                symbol,
                timeframe,
                startTime,
                endTime,
                150  // 获取150条数据以满足计算需求
            );
            
            List<MarketData> marketDataList = dataFuture.get(30, TimeUnit.SECONDS);
            
            assertNotNull(marketDataList, "市场数据不应为空");
            assertFalse(marketDataList.isEmpty(), "市场数据列表不应为空");
            
            log.info("成功获取{}条日线数据", marketDataList.size());
            
            // 生成验证报告
            ValidationReport report = validationService.generateValidationReport(symbol, marketDataList);
            
            // 生成CSV内容
            String csvContent = report.generateCsvReport();
            
            // 保存CSV文件
            File outputDir = new File("output");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            String filename = String.format("output/validation_%s_daily_%s.csv", 
                symbol.replace(".", "_"), 
                LocalDateTime.now().toString().replace(":", "-").substring(0, 19));
            
            try (FileWriter writer = new FileWriter(filename)) {
                writer.write(csvContent);
                log.info("日线CSV文件已保存到: {}", filename);
            }
            
            // 验证最后一个数据点的指标
            if (!report.getDataPoints().isEmpty()) {
                ValidationDataPoint lastPoint = report.getDataPoints().get(report.getDataPoints().size() - 1);
                log.info("最新数据点 ({}):", lastPoint.getDateTime());
                log.info("- 收盘价: {}", lastPoint.getClose());
                log.info("- RSI: {}", lastPoint.getRsi());
                log.info("- MACD: {}", lastPoint.getMacdLine());
                log.info("- 布林带上轨: {}", lastPoint.getBollingerUpper());
                log.info("- CCI: {}", lastPoint.getCci());
                log.info("- MFI: {}", lastPoint.getMfi());
            }
            
            log.info("✅ 日线CSV格式验证成功！");
            
        } catch (Exception e) {
            log.error("测试失败", e);
            fail("测试失败: " + e.getMessage());
        }
    }
}