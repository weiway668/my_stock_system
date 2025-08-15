package com.trading.service;

import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.repository.HistoricalDataRepository;
import com.trading.service.impl.DataQualityServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataQualityService 单元测试")
class DataQualityServiceTest {

    @Mock
    private HistoricalDataRepository historicalDataRepository;

    @InjectMocks
    private DataQualityServiceImpl dataQualityService;

    private final String TEST_SYMBOL = "00700.HK";
    private final LocalDate START_DATE = LocalDate.of(2024, 1, 1);
    private final LocalDate END_DATE = LocalDate.of(2024, 1, 31);

    // 辅助方法，用于创建K线实体
    private HistoricalKLineEntity createKLine(LocalDateTime timestamp, double close, FutuMarketDataService.KLineType klineType) {
        HistoricalKLineEntity kline = new HistoricalKLineEntity();
        kline.setSymbol(TEST_SYMBOL);
        kline.setKlineType(klineType);
        kline.setTimestamp(timestamp);
        kline.setOpen(BigDecimal.valueOf(close - 1));
        kline.setHigh(BigDecimal.valueOf(close + 1));
        kline.setLow(BigDecimal.valueOf(close - 2));
        kline.setClose(BigDecimal.valueOf(close));
        kline.setVolume(1000L);
        kline.setRehabType(RehabType.NONE);
        return kline;
    }

    @Test
    @DisplayName("测试异常值检测")
    void testDetectAnomalies() {
        // 准备数据
        HistoricalKLineEntity anomaly = createKLine(LocalDateTime.now(), 100, FutuMarketDataService.KLineType.K_DAY);
        anomaly.setHigh(BigDecimal.valueOf(90)); // 异常: high < open

        // Mock Repository
        when(historicalDataRepository.findAnomalousData(TEST_SYMBOL, FutuMarketDataService.KLineType.K_DAY))
                .thenReturn(List.of(anomaly));

        // 执行测试
        List<DataQualityService.DataAnomaly> anomalies = dataQualityService.detectAnomalies(TEST_SYMBOL, FutuMarketDataService.KLineType.K_DAY);

        // 验证结果
        assertNotNull(anomalies);
        assertEquals(1, anomalies.size());
        assertEquals("规则校验失败", anomalies.get(0).getAnomalyType());
    }

    @Test
    @DisplayName("测试午休时间不应报告数据缺口")
    void testFindDataGaps_NoGapDuringLunchBreak() {
        // 准备数据: 模拟港股午休时间 (12:00 - 13:00)
        List<HistoricalKLineEntity> klines = List.of(
                createKLine(LocalDateTime.of(2024, 8, 14, 11, 59), 100, FutuMarketDataService.KLineType.K_1MIN),
                createKLine(LocalDateTime.of(2024, 8, 14, 12, 0), 101, FutuMarketDataService.KLineType.K_1MIN),
                // 午休时间
                createKLine(LocalDateTime.of(2024, 8, 14, 13, 0), 102, FutuMarketDataService.KLineType.K_1MIN),
                createKLine(LocalDateTime.of(2024, 8, 14, 13, 1), 103, FutuMarketDataService.KLineType.K_1MIN)
        );

        // Mock Repository
        when(historicalDataRepository.findBySymbolAndTimeRange(
                eq(TEST_SYMBOL), eq(FutuMarketDataService.KLineType.K_1MIN), eq(RehabType.NONE), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(klines);

        // 执行测试
        List<DataQualityService.DataGap> gaps = dataQualityService.findDataGaps(TEST_SYMBOL, FutuMarketDataService.KLineType.K_1MIN, START_DATE, END_DATE);

        // 验证结果: 午休时间不应被视为缺口
        assertTrue(gaps.isEmpty(), "午休时间不应被报告为数据缺口");
    }

    @Test
    @DisplayName("测试有真实数据缺口时的查找")
    void testFindDataGaps_WithRealGap() {
        // 准备数据: 10:01 和 10:02 的数据缺失
        List<HistoricalKLineEntity> klinesWithGap = List.of(
                createKLine(LocalDateTime.of(2024, 8, 14, 10, 0), 100, FutuMarketDataService.KLineType.K_1MIN),
                createKLine(LocalDateTime.of(2024, 8, 14, 10, 3), 103, FutuMarketDataService.KLineType.K_1MIN) // 缺失 10:01, 10:02
        );

        // Mock Repository
        when(historicalDataRepository.findBySymbolAndTimeRange(
                eq(TEST_SYMBOL), eq(FutuMarketDataService.KLineType.K_1MIN), eq(RehabType.NONE), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(klinesWithGap);

        // 执行测试
        List<DataQualityService.DataGap> gaps = dataQualityService.findDataGaps(TEST_SYMBOL, FutuMarketDataService.KLineType.K_1MIN, START_DATE, END_DATE);

        // 验证结果
        assertEquals(1, gaps.size(), "应检测到一个数据缺口");
        DataQualityService.DataGap gap = gaps.get(0);
        // 缺口开始于最后一个有效K线的下一分钟
        assertEquals(LocalDateTime.of(2024, 8, 14, 10, 1), gap.getGapStart(), "缺口开始时间不正确");
        // 缺口结束于下一个K线的前一分钟
        assertEquals(LocalDateTime.of(2024, 8, 14, 10, 2), gap.getGapEnd(), "缺口结束时间不正确");
    }


    @Test
    @DisplayName("测试数据一致性验证")
    void testVerifyConsistency() {
        // 准备数据
        Object[] duplicateRow = new Object[]{LocalDateTime.now(), 2L}; // 1个重复项

        // Mock Repository
        when(historicalDataRepository.findDuplicateTimestamps(anyString(), any(FutuMarketDataService.KLineType.class), any(RehabType.class)))
                .thenReturn(Collections.singletonList(duplicateRow));
        when(historicalDataRepository.countBySymbolAndTimeRange(anyString(), any(FutuMarketDataService.KLineType.class), any(RehabType.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(30L);

        // 执行测试
        DataQualityService.ConsistencyReport report = dataQualityService.verifyConsistency(TEST_SYMBOL, FutuMarketDataService.KLineType.K_DAY, START_DATE, END_DATE);

        // 验证结果
        assertNotNull(report);
        assertEquals(1, report.getDuplicateRecords());
        assertEquals(30, report.getTotalRecords());
        assertFalse(report.isConsistent());
    }

    @Test
    @DisplayName("测试无异常时的综合报告")
    void testGenerateComprehensiveReport_NoIssues() {
        // Mock Repository (返回空列表)
        when(historicalDataRepository.findAnomalousData(anyString(), any(FutuMarketDataService.KLineType.class))).thenReturn(Collections.emptyList());
        // findDataGaps 现在依赖 findBySymbolAndTimeRange
        when(historicalDataRepository.findBySymbolAndTimeRange(anyString(), any(FutuMarketDataService.KLineType.class), any(RehabType.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());
        when(historicalDataRepository.findDuplicateTimestamps(anyString(), any(FutuMarketDataService.KLineType.class), any(RehabType.class))).thenReturn(Collections.emptyList());
        when(historicalDataRepository.countBySymbolAndTimeRange(anyString(), any(FutuMarketDataService.KLineType.class), any(RehabType.class), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(31L);

        // 执行测试
        DataQualityService.ComprehensiveQualityReport report = dataQualityService.generateComprehensiveReport(TEST_SYMBOL, FutuMarketDataService.KLineType.K_DAY, START_DATE, END_DATE);

        // 验证结果
        assertNotNull(report);
        assertEquals(100, report.getOverallQualityScore());
        assertTrue(report.getAnomalies().isEmpty());
        assertTrue(report.getGaps().isEmpty());
        assertEquals(0, report.getConsistencyReport().getDuplicateRecords());
        assertTrue(report.getSummary().contains("数据质量综合评分: 100"));
    }

    @Test
    @DisplayName("测试有多种问题时的综合报告")
    void testGenerateComprehensiveReport_WithIssues() {
        final FutuMarketDataService.KLineType klineType = FutuMarketDataService.KLineType.K_5MIN;
        // 准备数据
        HistoricalKLineEntity anomaly = createKLine(LocalDateTime.now(), 100, klineType);
        anomaly.setHigh(BigDecimal.valueOf(90)); // 异常

        // 准备有缺口的数据 (5分钟K线)
        List<HistoricalKLineEntity> klinesWithGap = List.of(
                createKLine(LocalDateTime.of(2024, 1, 8, 10, 0), 100, klineType),
                createKLine(LocalDateTime.of(2024, 1, 8, 10, 15), 103, klineType) // 缺失 10:05 和 10:10
        );

        Object[] duplicateRow = new Object[]{LocalDateTime.now(), 3L}; // 2个重复项

        // Mock Repository
        when(historicalDataRepository.findAnomalousData(anyString(), any(FutuMarketDataService.KLineType.class))).thenReturn(List.of(anomaly));
        // Mock findBySymbolAndTimeRange to return data with a gap
        when(historicalDataRepository.findBySymbolAndTimeRange(anyString(), any(FutuMarketDataService.KLineType.class), any(RehabType.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(klinesWithGap);
        when(historicalDataRepository.findDuplicateTimestamps(anyString(), any(FutuMarketDataService.KLineType.class), any(RehabType.class))).thenReturn(Collections.singletonList(duplicateRow));
        when(historicalDataRepository.countBySymbolAndTimeRange(anyString(), any(FutuMarketDataService.KLineType.class), any(RehabType.class), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(28L);

        // 执行测试
        DataQualityService.ComprehensiveQualityReport report = dataQualityService.generateComprehensiveReport(TEST_SYMBOL, klineType, START_DATE, END_DATE);

        // 验证结果
        assertNotNull(report);
        assertEquals(1, report.getAnomalies().size());
        assertEquals(1, report.getGaps().size(), "应报告一个数据缺口");
        assertEquals(2, report.getConsistencyReport().getDuplicateRecords());
        assertTrue(report.getOverallQualityScore() < 100);
        assertTrue(report.getSummary().contains("1 个异常点"));
        assertTrue(report.getSummary().contains("1 个数据缺口"));
        assertTrue(report.getSummary().contains("2 个重复项"));
    }
}