package com.trading.service;

import com.trading.domain.entity.HistoricalKLineEntity;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HistoricalDataService 集成测试
 * <p>
 * 该测试会连接真实的FUTU API和数据库，请确保：
 * 1. Futu OpenD 正在运行。
 * 2. application-test.yml 中的数据库配置正确。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("HistoricalDataService 集成测试")
class HistoricalDataServiceIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(HistoricalDataServiceIntegrationTest.class);

    @Autowired
    private HistoricalDataService historicalDataService;

    @Autowired
    private HistoricalDataRepository historicalDataRepository;

    @Autowired(required = false)
    private FutuMarketDataService futuMarketDataService;

    private static final String TEST_SYMBOL = "00700.HK";

    @BeforeEach
    void setUp() {
        // 确保FUTU服务可用
        if (futuMarketDataService == null) {
            logger.error("FutuMarketDataService 未注入，请确保FUTU OpenD已启动并配置正确。");
            // 在JUnit 5中，通常不建议用System.exit()，这里用fail()来中断测试
            fail("FutuMarketDataService is not available. Check Futu OpenD connection.");
        }
        // 清理测试数据，避免测试间干扰
        historicalDataRepository.deleteBySymbol(TEST_SYMBOL);
        logger.info("测试前清理数据: {}", TEST_SYMBOL);
    }

    @AfterEach
    void tearDown() {
        // 清理测试数据
        historicalDataRepository.deleteBySymbol(TEST_SYMBOL);
        logger.info("测试后清理数据: {}", TEST_SYMBOL);
    }

    @Test
    @DisplayName("测试从Futu下载真实日K线并存入数据库")
    void testDownloadAndSaveRealDailyKLine() throws ExecutionException, InterruptedException {
        // 准备参数
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(10); // 获取最近10天的数据
        FutuMarketDataService.KLineType kLineType = FutuMarketDataService.KLineType.K_DAY;
        FutuKLine.RehabType rehabType = FutuKLine.RehabType.NONE;

        logger.info("开始下载 {} 从 {} 到 {} 的 {} 数据", TEST_SYMBOL, startDate, endDate, kLineType);

        // 1. 执行下载操作
        CompletableFuture<HistoricalDataService.DownloadResult> future =
                historicalDataService.downloadHistoricalData(TEST_SYMBOL, startDate, endDate, kLineType, rehabType);

        HistoricalDataService.DownloadResult result = future.get();

        // 2. 验证下载结果
        assertNotNull(result);
        assertTrue(result.success(), "数据下载应成功，返回信息: " + result.errorMessage());
        assertTrue(result.downloadedCount() > 0, "应下载到大于0条数据");
        assertEquals(result.downloadedCount(), result.savedCount(), "下载数量应与保存数量相等");
        logger.info("下载并保存了 {} 条数据", result.savedCount());

        // 3. 从数据库中查询已保存的数据进行验证
        List<HistoricalKLineEntity> savedEntities = historicalDataRepository.findBySymbol(TEST_SYMBOL);
        assertNotNull(savedEntities);
        assertEquals(result.savedCount(), savedEntities.size(), "数据库中保存的记录数应与下载数一致");

        // 4. 抽样验证数据正确性
        HistoricalKLineEntity firstRecord = savedEntities.get(0);
        assertEquals(TEST_SYMBOL, firstRecord.getSymbol());
        assertEquals(kLineType.name(), firstRecord.getKlineType().name());
        assertNotNull(firstRecord.getOpen());
        assertNotNull(firstRecord.getClose());
        assertNotNull(firstRecord.getHigh());
        assertNotNull(firstRecord.getLow());
        assertNotNull(firstRecord.getVolume());

        logger.info("数据已成功存入数据库并验证通过。第一条记录时间: {}", firstRecord.getTimestamp());
    }
}
