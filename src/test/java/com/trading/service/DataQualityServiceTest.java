package com.trading.service;

import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.repository.HistoricalDataRepository;
import com.trading.service.impl.DataQualityServiceImpl;
import org.junit.jupiter.api.BeforeEach;
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
    private final KLineType TEST_KLINE_TYPE = KLineType.K_DAY;
    private final LocalDate START_DATE = LocalDate.of(2024, 1, 1);
    private final LocalDate END_DATE = LocalDate.of(2024, 1, 31);

    @Test
    @DisplayName("测试异常值检测")
    void testDetectAnomalies() {
        // 准备数据
        HistoricalKLineEntity anomaly = new HistoricalKLineEntity();
        anomaly.setTimestamp(LocalDateTime.now());
        anomaly.setOpen(BigDecimal.valueOf(100));
        anomaly.setHigh(BigDecimal.valueOf(90)); // 异常: high < open
        anomaly.setLow(BigDecimal.valueOf(80));
        anomaly.setClose(BigDecimal.valueOf(85));
        anomaly.setVolume(1000L);

        // Mock Repository
        when(historicalDataRepository.findAnomalousData(TEST_SYMBOL, TEST_KLINE_TYPE))
                .thenReturn(List.of(anomaly));

        // 执行测试
        List<DataQualityService.DataAnomaly> anomalies = dataQualityService.detectAnomalies(TEST_SYMBOL, TEST_KLINE_TYPE);

        // 验证结果
        assertNotNull(anomalies);
        assertEquals(1, anomalies.size());
        assertEquals("规则校验失败", anomalies.get(0).getAnomalyType());
    }

    @Test
    @DisplayName("测试数据缺口查找")
    void testFindDataGaps() {
        // 准备数据
        Object[] gapRow = new Object[]{LocalDateTime.of(2024, 1, 5, 16, 0), LocalDateTime.of(2024, 1, 8, 16, 0)};

        // Mock Repository
        when(historicalDataRepository.findDataGaps(anyString(), anyString(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(gapRow));

        // 执行测试
        List<DataQualityService.DataGap> gaps = dataQualityService.findDataGaps(TEST_SYMBOL, TEST_KLINE_TYPE, START_DATE, END_DATE);

        // 验证结果
        assertNotNull(gaps);
        assertEquals(1, gaps.size());
        assertEquals(LocalDateTime.of(2024, 1, 5, 16, 0), gaps.get(0).getGapStart());
    }

    @Test
    @DisplayName("测试数据一致性验证")
    void testVerifyConsistency() {
        // 准备数据
        Object[] duplicateRow = new Object[]{LocalDateTime.now(), 2L}; // 1个重复项

        // Mock Repository
        when(historicalDataRepository.findDuplicateTimestamps(anyString(), any(KLineType.class), any()))
                .thenReturn(List.of(duplicateRow));
        when(historicalDataRepository.countBySymbolAndTimeRange(anyString(), any(KLineType.class), any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(30L);

        // 执行测试
        DataQualityService.ConsistencyReport report = dataQualityService.verifyConsistency(TEST_SYMBOL, TEST_KLINE_TYPE, START_DATE, END_DATE);

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
        when(historicalDataRepository.findAnomalousData(anyString(), any(KLineType.class))).thenReturn(Collections.emptyList());
        when(historicalDataRepository.findDataGaps(anyString(), anyString(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class), anyInt())).thenReturn(Collections.emptyList());
        when(historicalDataRepository.findDuplicateTimestamps(anyString(), any(KLineType.class), any())).thenReturn(Collections.emptyList());
        when(historicalDataRepository.countBySymbolAndTimeRange(anyString(), any(KLineType.class), any(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(31L);

        // 执行测试
        DataQualityService.ComprehensiveQualityReport report = dataQualityService.generateComprehensiveReport(TEST_SYMBOL, TEST_KLINE_TYPE, START_DATE, END_DATE);

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
        // 准备数据
        HistoricalKLineEntity anomaly = new HistoricalKLineEntity();
        anomaly.setTimestamp(LocalDateTime.now());
        anomaly.setOpen(BigDecimal.valueOf(100));
        anomaly.setHigh(BigDecimal.valueOf(90));
        anomaly.setLow(BigDecimal.valueOf(80));
        anomaly.setClose(BigDecimal.valueOf(85));
        anomaly.setVolume(1000L);

        Object[] gapRow = new Object[]{LocalDateTime.of(2024, 1, 5, 16, 0), LocalDateTime.of(2024, 1, 8, 16, 0)};
        Object[] duplicateRow = new Object[]{LocalDateTime.now(), 3L}; // 2个重复项

        // Mock Repository
        when(historicalDataRepository.findAnomalousData(anyString(), any(KLineType.class))).thenReturn(List.of(anomaly));
        when(historicalDataRepository.findDataGaps(anyString(), anyString(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class), anyInt())).thenReturn(List.of(gapRow));
        when(historicalDataRepository.findDuplicateTimestamps(anyString(), any(KLineType.class), any())).thenReturn(List.of(duplicateRow));
        when(historicalDataRepository.countBySymbolAndTimeRange(anyString(), any(KLineType.class), any(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(28L);

        // 执行测试
        DataQualityService.ComprehensiveQualityReport report = dataQualityService.generateComprehensiveReport(TEST_SYMBOL, TEST_KLINE_TYPE, START_DATE, END_DATE);

        // 验证结果
        assertNotNull(report);
        assertEquals(1, report.getAnomalies().size());
        assertEquals(1, report.getGaps().size());
        assertEquals(2, report.getConsistencyReport().getDuplicateRecords());
        assertTrue(report.getOverallQualityScore() < 100);
        assertTrue(report.getSummary().contains("1 个异常点"));
        assertTrue(report.getSummary().contains("1 个数据缺口"));
        assertTrue(report.getSummary().contains("2 个重复项"));
    }
}
