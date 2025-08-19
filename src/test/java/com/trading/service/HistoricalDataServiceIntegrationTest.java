package com.trading.service;

import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.repository.CorporateActionRepository;
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
import org.springframework.transaction.annotation.Transactional;



import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
@Transactional
@DisplayName("HistoricalDataService 集成测试")
class HistoricalDataServiceIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(HistoricalDataServiceIntegrationTest.class);

    @Autowired
    private HistoricalDataService historicalDataService;

    @Autowired
    private CorporateActionService corporateActionService;

    @Autowired
    private HistoricalDataRepository historicalDataRepository;

    @Autowired
    private CorporateActionRepository corporateActionRepository;

    private static final String SYMBOL_TENCENT = "HK.00700";

    @BeforeEach
    void setUp() {
        // 清理测试数据，避免测试间干扰
        historicalDataRepository.deleteBySymbol(SYMBOL_TENCENT);
        corporateActionRepository.deleteAllByStockCode(SYMBOL_TENCENT);
        logger.info("测试前清理数据: {}", SYMBOL_TENCENT);
    }

    @AfterEach
    void tearDown() {
        // 清理测试数据
        historicalDataRepository.deleteBySymbol(SYMBOL_TENCENT);
        corporateActionRepository.deleteAllByStockCode(SYMBOL_TENCENT);
        logger.info("测试后清理数据: {}", SYMBOL_TENCENT);
    }

    @Test
    @DisplayName("测试从Futu下载真实日K线并存入数据库")
    void testDownloadAndSaveRealDailyKLine() throws ExecutionException, InterruptedException {
        // 准备参数
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(10); // 获取最近10天的数据

        logger.info("开始下载 {} 从 {} 到 {} 的 {} 数据", SYMBOL_TENCENT, startDate, endDate, KLineType.K_DAY);

        // 1. 执行下载操作
        CompletableFuture<HistoricalDataService.DownloadResult> future =
                historicalDataService.downloadHistoricalData(SYMBOL_TENCENT, startDate, endDate, KLineType.K_DAY, RehabType.NONE);

        HistoricalDataService.DownloadResult result = future.get();

        // 2. 验证下载结果
        assertNotNull(result);
        assertTrue(result.success(), "数据下载应成功，返回信息: " + result.errorMessage());
        assertTrue(result.downloadedCount() > 0, "应下载到大于0条数据");
        assertEquals(result.downloadedCount(), result.savedCount(), "下载数量应与保存数量相等");
        logger.info("下载并保存了 {} 条数据", result.savedCount());

        // 3. 从数据库中查询已保存的数据进行验证
        List<HistoricalKLineEntity> savedEntities = historicalDataRepository.findBySymbol(SYMBOL_TENCENT);
        assertNotNull(savedEntities);
        assertEquals(result.savedCount(), savedEntities.size(), "数据库中保存的记录数应与下载数一致");

        // 4. 抽样验证数据正确性
        HistoricalKLineEntity firstRecord = savedEntities.get(0);
        assertEquals(SYMBOL_TENCENT, firstRecord.getSymbol());
        assertEquals(KLineType.K_DAY.name(), firstRecord.getKlineType().name());
        assertNotNull(firstRecord.getOpen());
        assertNotNull(firstRecord.getClose());
        assertNotNull(firstRecord.getHigh());
        assertNotNull(firstRecord.getLow());
        assertNotNull(firstRecord.getVolume());

        logger.info("数据已成功存入数据库并验证通过。第一条记录时间: {}", firstRecord.getTimestamp());
    }

    @Test
    @DisplayName("集成测试：使用腾讯控股真实数据验证后复权算法")
    void testBackwardAdjustment_WithRealData() throws ExecutionException, InterruptedException {
        // Arrange: 使用腾讯控股2024年5月17日的真实除权事件进行验证
        final String symbol = "HK.00700";
        final LocalDate exDividendDate = LocalDate.of(2024, 5, 17);
        final LocalDate startDate = exDividendDate.minusDays(5);
        final LocalDate endDate = exDividendDate.plusDays(5);
        final LocalDate verificationDate = LocalDate.of(2024, 5, 16); // 明确指定除权前一交易日

        // 1. 确保复权数据已下载
        logger.info("正在为 {} 同步复权数据...", symbol);
        corporateActionService.processAndSaveCorporateActionForStock(symbol);

        // 2. 确保K线数据已下载
        logger.info("正在为 {} 下载 {} 至 {} 的日K数据...", symbol, startDate, endDate);
        historicalDataService.downloadHistoricalData(symbol, startDate, endDate, KLineType.K_DAY, RehabType.NONE).get();

        // Act: 获取后复权K线数据
        logger.info("正在获取后复权K线数据...");
        List<HistoricalKLineEntity> adjustedKLines = historicalDataService.getHistoricalKLine(symbol, startDate, endDate, KLineType.K_DAY, RehabType.BACKWARD);

        // Assert & Verification
        assertNotNull(adjustedKLines, "复权K线列表不应为null");
        assertFalse(adjustedKLines.isEmpty(), "复权K线列表不应为空");

        // 找到除权日前一天的原始K线和复权后K线
        List<HistoricalKLineEntity> originalKLines = historicalDataService.getHistoricalKLine(symbol, startDate, endDate, KLineType.K_DAY, RehabType.NONE);
        
        Optional<HistoricalKLineEntity> originalKlineOpt = originalKLines.stream()
            .filter(k -> k.getTimestamp().toLocalDate().equals(verificationDate))
            .findFirst();
        
        Optional<HistoricalKLineEntity> adjustedKlineOpt = adjustedKLines.stream()
            .filter(k -> k.getTimestamp().toLocalDate().equals(verificationDate))
            .findFirst();

        assertTrue(originalKlineOpt.isPresent(), "未能找到原始K线数据于 " + verificationDate);
        assertTrue(adjustedKlineOpt.isPresent(), "未能找到复权后K线数据于 " + verificationDate);

        HistoricalKLineEntity originalKline = originalKlineOpt.get();
        HistoricalKLineEntity adjustedKline = adjustedKlineOpt.get();

        logger.info("========================= 人工验证 =========================");
        logger.info("股票代码: {}", symbol);
        logger.info("除权日期: {}", exDividendDate);
        logger.info("验证日期 (除权前一交易日): {}", verificationDate);
        logger.info("-----------------------------------------------------------");
        logger.info("原始收盘价: {}", originalKline.getClose());
        logger.info("计算后复权收盘价: {}", adjustedKline.getClose());
        logger.info("==========================================================");
        logger.info("请将以上'计算后复权收盘价'与富途牛牛等行情软件中该日的'后复权'收盘价进行比对。");

        assertNotEquals(originalKline.getClose(), adjustedKline.getClose(), "复权后的价格应与原始价格不同");
    }
}
