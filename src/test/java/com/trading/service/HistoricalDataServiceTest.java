package com.trading.service;

import com.trading.common.enums.MarketType;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.repository.HistoricalDataRepository;
import com.trading.service.impl.HistoricalDataServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HistoricalDataService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class HistoricalDataServiceTest {

    @Mock
    private HistoricalDataRepository historicalDataRepository;

    @Mock
    private FutuMarketDataService futuMarketDataService;

    private HistoricalDataService historicalDataService;

    @BeforeEach
    void setUp() {
        historicalDataService = new HistoricalDataServiceImpl(historicalDataRepository, futuMarketDataService);
        
        // 设置配置参数
        ReflectionTestUtils.setField(historicalDataService, "batchDownloadSize", 5);
        ReflectionTestUtils.setField(historicalDataService, "pageSize", 1000);
        ReflectionTestUtils.setField(historicalDataService, "retryTimes", 3);
        ReflectionTestUtils.setField(historicalDataService, "retryDelay", 5000L);
    }

    @Test
    void testMarketTypeFromSymbol() {
        // 测试市场识别功能
        assertEquals(MarketType.HK, MarketType.fromSymbol("00700.HK"));
        assertEquals(MarketType.CN_SH, MarketType.fromSymbol("600000.SH"));
        assertEquals(MarketType.CN_SZ, MarketType.fromSymbol("000001.SZ"));
        assertEquals(MarketType.US, MarketType.fromSymbol("AAPL.US"));
        
        // 测试不带后缀的代码
        assertEquals(MarketType.CN_SH, MarketType.fromSymbol("600000"));
        assertEquals(MarketType.CN_SZ, MarketType.fromSymbol("000001"));
        assertEquals(MarketType.HK, MarketType.fromSymbol("00700"));
    }

    @Test
    void testDownloadSingleHistoricalData_Success() throws Exception {
        // 准备测试数据
        String symbol = "00700.HK";
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        KLineType kLineType = KLineType.K_DAY;
        RehabType rehabType = RehabType.NONE;

        List<FutuKLine> mockKLines = createMockFutuKLines(symbol, 10);
        
        // Mock FUTU服务返回数据
        when(futuMarketDataService.getHistoricalKLine(
                eq(symbol), eq(startDate), eq(endDate), any()))
                .thenReturn(mockKLines);

        // Mock Repository保存操作
        when(historicalDataRepository.saveAll(anyList()))
                .thenReturn(Collections.emptyList());

        // 执行测试
        CompletableFuture<HistoricalDataService.DownloadResult> future = 
                historicalDataService.downloadHistoricalData(symbol, startDate, endDate, kLineType, rehabType);
        
        HistoricalDataService.DownloadResult result = future.get();

        // 验证结果
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals(symbol, result.symbol());
        assertEquals(MarketType.HK, result.market());
        assertEquals(kLineType, result.kLineType());
        assertEquals(10, result.downloadedCount());
        assertEquals(10, result.savedCount());

        // 验证Mock调用
        verify(futuMarketDataService).getHistoricalKLine(eq(symbol), eq(startDate), eq(endDate), any());
        verify(historicalDataRepository).saveAll(anyList());
    }

    @Test
    void testDownloadSingleHistoricalData_NoData() throws Exception {
        // 准备测试数据
        String symbol = "00700.HK";
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        KLineType kLineType = KLineType.K_DAY;
        RehabType rehabType = RehabType.NONE;

        // Mock FUTU服务返回空数据
        when(futuMarketDataService.getHistoricalKLine(
                eq(symbol), eq(startDate), eq(endDate), any()))
                .thenReturn(Collections.emptyList());

        // 执行测试
        CompletableFuture<HistoricalDataService.DownloadResult> future = 
                historicalDataService.downloadHistoricalData(symbol, startDate, endDate, kLineType, rehabType);
        
        HistoricalDataService.DownloadResult result = future.get();

        // 验证结果
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("未获取到历史数据", result.errorMessage());
        assertEquals(0, result.downloadedCount());
        assertEquals(0, result.savedCount());
    }

    @Test
    void testBatchDownloadHistoricalData() throws Exception {
        // 准备测试数据
        List<String> symbols = List.of("00700.HK", "00981.HK", "02800.HK");
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        KLineType kLineType = KLineType.K_DAY;
        RehabType rehabType = RehabType.NONE;

        // Mock FUTU服务为每个股票返回数据
        for (String symbol : symbols) {
            List<FutuKLine> mockKLines = createMockFutuKLines(symbol, 10);
            when(futuMarketDataService.getHistoricalKLine(
                    eq(symbol), eq(startDate), eq(endDate), any()))
                    .thenReturn(mockKLines);
        }

        // Mock Repository保存操作
        when(historicalDataRepository.saveAll(anyList()))
                .thenReturn(Collections.emptyList());

        // 执行测试
        CompletableFuture<HistoricalDataService.BatchDownloadResult> future = 
                historicalDataService.downloadBatchHistoricalData(symbols, startDate, endDate, kLineType, rehabType);
        
        HistoricalDataService.BatchDownloadResult result = future.get();

        // 验证结果
        assertNotNull(result);
        assertEquals(symbols.size(), result.totalSymbols());
        assertEquals(symbols.size(), result.successCount());
        assertEquals(0, result.failedCount());
        assertNotNull(result.results());
        assertEquals(symbols.size(), result.results().size());

        // 验证每个股票的结果
        for (String symbol : symbols) {
            assertTrue(result.results().containsKey(symbol));
            HistoricalDataService.DownloadResult symbolResult = result.results().get(symbol);
            assertTrue(symbolResult.success());
            assertEquals(10, symbolResult.downloadedCount());
        }
    }

    @Test
    void testIncrementalUpdate_WithExistingData() throws Exception {
        // 准备测试数据
        String symbol = "00700.HK";
        KLineType kLineType = KLineType.K_DAY;
        LocalDateTime lastLocalTime = LocalDateTime.of(2024, 1, 15, 16, 0);

        // Mock Repository返回最新数据时间
        when(historicalDataRepository.findLatestTimestamp(symbol, kLineType, RehabType.NONE))
                .thenReturn(Optional.of(lastLocalTime));

        // Mock FUTU服务返回增量数据
        List<FutuKLine> incrementalKLines = createMockFutuKLines(symbol, 5);
        when(futuMarketDataService.getHistoricalKLine(
                eq(symbol), any(LocalDate.class), any(LocalDate.class), any()))
                .thenReturn(incrementalKLines);

        // Mock Repository保存操作
        when(historicalDataRepository.saveAll(anyList()))
                .thenReturn(Collections.emptyList());

        // 执行测试
        CompletableFuture<HistoricalDataService.UpdateResult> future = 
                historicalDataService.incrementalUpdate(symbol, kLineType);
        
        HistoricalDataService.UpdateResult result = future.get();

        // 验证结果
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals(symbol, result.symbol());
        assertEquals(kLineType, result.kLineType());
        assertEquals(lastLocalTime, result.lastLocalTime());
        assertEquals(5, result.newDataCount());
    }

    @Test
    void testIncrementalUpdate_NoExistingData() throws Exception {
        // 准备测试数据
        String symbol = "00700.HK";
        KLineType kLineType = KLineType.K_DAY;

        // Mock Repository返回空的最新数据时间
        when(historicalDataRepository.findLatestTimestamp(symbol, kLineType, RehabType.NONE))
                .thenReturn(Optional.empty());

        // Mock FUTU服务返回历史数据
        List<FutuKLine> historicalKLines = createMockFutuKLines(symbol, 30);
        when(futuMarketDataService.getHistoricalKLine(
                eq(symbol), any(LocalDate.class), any(LocalDate.class), any()))
                .thenReturn(historicalKLines);

        // Mock Repository保存操作
        when(historicalDataRepository.saveAll(anyList()))
                .thenReturn(Collections.emptyList());

        // 执行测试
        CompletableFuture<HistoricalDataService.UpdateResult> future = 
                historicalDataService.incrementalUpdate(symbol, kLineType);
        
        HistoricalDataService.UpdateResult result = future.get();

        // 验证结果
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals(symbol, result.symbol());
        assertEquals(kLineType, result.kLineType());
        assertNull(result.lastLocalTime());
        assertEquals(30, result.newDataCount());
    }

    @Test
    void testValidateDataIntegrity() {
        // 准备测试数据
        String symbol = "00700.HK";
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        KLineType kLineType = KLineType.K_DAY;

        List<HistoricalKLineEntity> mockData = createMockHistoricalEntities(symbol, 20);

        // Mock Repository查询
        when(historicalDataRepository.findBySymbolAndTimeRange(
                eq(symbol), eq(kLineType), eq(RehabType.NONE),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(mockData);

        // 执行测试
        HistoricalDataService.DataIntegrityReport report = 
                historicalDataService.validateDataIntegrity(symbol, startDate, endDate, kLineType);

        // 验证结果
        assertNotNull(report);
        assertEquals(symbol, report.symbol());
        assertEquals(kLineType, report.kLineType());
        assertEquals(startDate, report.startDate());
        assertEquals(endDate, report.endDate());
        assertEquals(20, report.actualCount());
        assertTrue(report.completenessRate() > 0);
    }

    @Test
    void testGetDataQualityReport() {
        // 准备测试数据
        String symbol = "00700.HK";
        KLineType kLineType = KLineType.K_DAY;

        List<HistoricalKLineEntity> mockData = createMockHistoricalEntities(symbol, 100);

        // Mock Repository查询
        when(historicalDataRepository.findBySymbolAndTimeRange(
                eq(symbol), eq(kLineType), eq(RehabType.NONE),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(mockData);

        // 执行测试
        HistoricalDataService.DataQualityReport report = 
                historicalDataService.getDataQualityReport(symbol, kLineType);

        // 验证结果
        assertNotNull(report);
        assertEquals(symbol, report.symbol());
        assertEquals(kLineType, report.kLineType());
        assertEquals(100, report.totalRecords());
        assertTrue(report.qualityScore() >= 0 && report.qualityScore() <= 100);
        assertNotNull(report.recommendation());
    }

    @Test
    void testGetLocalDataTimeRange() {
        // 准备测试数据
        String symbol = "00700.HK";
        KLineType kLineType = KLineType.K_DAY;
        LocalDateTime earliest = LocalDateTime.of(2024, 1, 1, 9, 30);
        LocalDateTime latest = LocalDateTime.of(2024, 1, 31, 16, 0);

        // Mock Repository查询
        when(historicalDataRepository.findEarliestTimestamp(symbol, kLineType, RehabType.NONE))
                .thenReturn(Optional.of(earliest));
        when(historicalDataRepository.findLatestTimestamp(symbol, kLineType, RehabType.NONE))
                .thenReturn(Optional.of(latest));
        when(historicalDataRepository.countBySymbolAndTimeRange(
                eq(symbol), eq(kLineType), eq(RehabType.NONE),
                eq(earliest), eq(latest)))
                .thenReturn(31L);

        // 执行测试
        HistoricalDataService.DataTimeRange timeRange = 
                historicalDataService.getLocalDataTimeRange(symbol, kLineType);

        // 验证结果
        assertNotNull(timeRange);
        assertTrue(timeRange.hasData());
        assertEquals(symbol, timeRange.symbol());
        assertEquals(kLineType, timeRange.kLineType());
        assertEquals(earliest, timeRange.earliest());
        assertEquals(latest, timeRange.latest());
        assertEquals(31, timeRange.totalRecords());
    }

    @Test
    void testCleanExpiredData() {
        // 准备测试数据
        KLineType kLineType = KLineType.K_1MIN;
        int keepDays = 7;

        // Mock Repository删除操作
        when(historicalDataRepository.deleteExpiredData(eq(kLineType), any(LocalDateTime.class)))
                .thenReturn(100);

        // 执行测试
        int deletedCount = historicalDataService.cleanExpiredData(kLineType, keepDays);

        // 验证结果
        assertEquals(100, deletedCount);
        verify(historicalDataRepository).deleteExpiredData(eq(kLineType), any(LocalDateTime.class));
    }

    // ==================== 辅助方法 ====================

    private List<FutuKLine> createMockFutuKLines(String symbol, int count) {
        List<FutuKLine> kLines = new java.util.ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            FutuKLine kLine = FutuKLine.builder()
                    .code(MarketType.getPureCode(symbol))
                    .kLineType(FutuKLine.KLineType.K_DAY)
                    .timestamp(LocalDateTime.of(2024, 1, 1 + i, 16, 0))
                    .open(BigDecimal.valueOf(100.0 + i))
                    .high(BigDecimal.valueOf(105.0 + i))
                    .low(BigDecimal.valueOf(95.0 + i))
                    .close(BigDecimal.valueOf(102.0 + i))
                    .volume(1000000L + i * 10000)
                    .turnover(BigDecimal.valueOf(100000000L + i * 1000000))
                    .preClose(BigDecimal.valueOf(99.0 + i))
                    .changeValue(BigDecimal.valueOf(2.0))
                    .changeRate(BigDecimal.valueOf(2.0))
                    .turnoverRate(BigDecimal.valueOf(0.5))
                    .rehabType(RehabType.NONE)
                    .build();
            
            kLines.add(kLine);
        }
        
        return kLines;
    }

    private List<HistoricalKLineEntity> createMockHistoricalEntities(String symbol, int count) {
        List<HistoricalKLineEntity> entities = new java.util.ArrayList<>();
        MarketType market = MarketType.fromSymbol(symbol);
        
        for (int i = 0; i < count; i++) {
            HistoricalKLineEntity entity = HistoricalKLineEntity.builder()
                    .symbol(symbol)
                    .market(market)
                    .klineType(KLineType.K_DAY)
                    .rehabType(RehabType.NONE)
                    .timestamp(LocalDateTime.of(2024, 1, 1, 16, 0).plusDays(i))
                    .open(BigDecimal.valueOf(100.0 + i))
                    .high(BigDecimal.valueOf(105.0 + i))
                    .low(BigDecimal.valueOf(95.0 + i))
                    .close(BigDecimal.valueOf(102.0 + i))
                    .volume(1000000L + i * 10000)
                    .turnover(BigDecimal.valueOf(100000000L + i * 1000000))
                    .preClose(BigDecimal.valueOf(99.0 + i))
                    .changeValue(BigDecimal.valueOf(2.0))
                    .changeRate(BigDecimal.valueOf(2.0))
                    .turnoverRate(BigDecimal.valueOf(0.5))
                    .dataSource(HistoricalKLineEntity.DataSource.FUTU)
                    .dataStatus(HistoricalKLineEntity.DataStatus.DOWNLOADED)
                    .downloadTime(LocalDateTime.now())
                    .qualityScore(95)
                    .dataVersion(1)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            
            entity.generateId();
            entities.add(entity);
        }
        
        return entities;
    }
}