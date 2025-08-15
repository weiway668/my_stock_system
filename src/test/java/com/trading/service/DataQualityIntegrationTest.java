package com.trading.service;

import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.repository.HistoricalDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据质量集成测试
 * <p>
 * 该测试验证 "下载真实数据 -> 进行数据质量校验" 的完整流程。
 * 请确保：
 * 1. Futu OpenD 正在运行。
 * 2. application-test.yml 中的数据库配置正确。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("数据质量集成测试")
public class DataQualityIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DataQualityIntegrationTest.class);

    @Autowired
    private HistoricalDataService historicalDataService;

    @Autowired
    private DataQualityService dataQualityService;

    @Autowired
    private HistoricalDataRepository historicalDataRepository;

    @Autowired(required = false)
    private FutuMarketDataService futuMarketDataService;

    private static final String TEST_SYMBOL = "00700.HK";

    @BeforeEach
    void setUp() {
        if (futuMarketDataService == null) {
            fail("FutuMarketDataService is not available. Check Futu OpenD connection.");
        }
        historicalDataRepository.deleteBySymbol(TEST_SYMBOL);
        logger.info("集成测试前清理数据: {}", TEST_SYMBOL);
    }

    @AfterEach
    void tearDown() {
        historicalDataRepository.deleteBySymbol(TEST_SYMBOL);
        logger.info("集成测试后清理数据: {}", TEST_SYMBOL);
    }

    @Test
    @DisplayName("下载真实数据并进行质量校验")
    void testDownloadAndValidateRealData() throws ExecutionException, InterruptedException {
        // 1. 下载真实数据
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(1); // 下载最近一个月的数据
        FutuMarketDataService.KLineType kLineType = FutuMarketDataService.KLineType.K_30MIN;

        logger.info("====== 步骤 1: 下载 {} 从 {} 到 {} 的 {} 数据 ======", TEST_SYMBOL, startDate, endDate, kLineType);

        HistoricalDataService.DownloadResult downloadResult = historicalDataService
                .downloadHistoricalData(TEST_SYMBOL, startDate, endDate, kLineType, FutuKLine.RehabType.NONE)
                .get();

        assertTrue(downloadResult.success(), "数据下载必须成功");
        assertTrue(downloadResult.savedCount() > 0, "应保存大于0条记录");
        logger.info("数据下载完成，共保存 {} 条记录", downloadResult.savedCount());

        // 2. 对下载的数据进行质量校验
        logger.info("\n====== 步骤 2: 对下载的数据进行质量校验 ======");

        DataQualityService.ComprehensiveQualityReport report = dataQualityService
                .generateComprehensiveReport(TEST_SYMBOL, kLineType, startDate, endDate);

        // 3. 验证并打印报告
        logger.info("\n====== 步骤 3: 质量报告结果 ======");
        assertNotNull(report, "质量报告不应为null");

        logger.info("股票代码: {}", report.getSymbol());
        logger.info("K线类型: {}", report.getKlineType());
        logger.info("时间范围: {} to {}", report.getStartDate(), report.getEndDate());
        logger.info("----------------------------------------");
        logger.info("一致性报告:");
        logger.info("  - 总记录数: {}", report.getConsistencyReport().getTotalRecords());
        logger.info("  - 预期记录数 (估算): {}", report.getConsistencyReport().getExpectedRecords());
        logger.info("  - 重复记录数: {}", report.getConsistencyReport().getDuplicateRecords());
        logger.info("  - 完整度 (估算): {}%", report.getConsistencyReport().getCompletenessRate());
        logger.info("异常数据点: {} 个", report.getAnomalies().size());
        report.getAnomalies().forEach(anomaly ->
                logger.warn("  - [异常] 时间: {}, 类型: {}, 描述: {}", anomaly.getTimestamp(), anomaly.getAnomalyType(), anomaly.getDescription()));
        logger.info("数据缺口: {} 个", report.getGaps().size());
        report.getGaps().forEach(gap ->
                logger.warn("  - [缺口] 开始: {}, 结束: {}", gap.getGapStart(), gap.getGapEnd()));
        logger.info("----------------------------------------");
        logger.info("综合质量评分: {}", report.getOverallQualityScore());
        logger.info("报告摘要: {}", report.getSummary());

        // 断言核心结果
        assertEquals(downloadResult.savedCount(), report.getConsistencyReport().getTotalRecords(), "报告中的记录数应与下载数一致");
        assertTrue(report.getOverallQualityScore() > 0, "数据质量评分应大于0");
    }
}
