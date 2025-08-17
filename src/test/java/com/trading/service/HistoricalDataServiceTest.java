package com.trading.service;

import com.trading.domain.entity.CorporateActionEntity;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.repository.CorporateActionRepository;
import com.trading.repository.HistoricalDataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
class HistoricalDataServiceTest {

    @Autowired
    private HistoricalDataService historicalDataService;

    @MockBean
    private HistoricalDataRepository historicalDataRepository;

    @MockBean
    private CorporateActionRepository corporateActionRepository;

    @Test
    void testGetHistoricalKLine_WithBackwardAdjustment() {
        // Arrange
        String symbol = "HK.00700";
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 10);

        // 1. 准备原始K线数据
        HistoricalKLineEntity klineBeforeAction = HistoricalKLineEntity.builder()
                .timestamp(LocalDateTime.of(2024, 1, 4, 16, 0))
                .close(BigDecimal.valueOf(100.0))
                .open(BigDecimal.valueOf(98.0))
                .high(BigDecimal.valueOf(101.0))
                .low(BigDecimal.valueOf(97.0))
                .build();

        HistoricalKLineEntity klineAfterAction = HistoricalKLineEntity.builder()
                .timestamp(LocalDateTime.of(2024, 1, 8, 16, 0))
                .close(BigDecimal.valueOf(105.0))
                .open(BigDecimal.valueOf(104.0))
                .high(BigDecimal.valueOf(106.0))
                .low(BigDecimal.valueOf(103.0))
                .build();

        List<HistoricalKLineEntity> rawKLines = List.of(klineBeforeAction, klineAfterAction);
        when(historicalDataRepository.findBySymbolAndTimeRange(eq(symbol), any(), eq(RehabType.NONE), any(), any()))
                .thenReturn(rawKLines);

        // 2. 准备复权因子数据
        CorporateActionEntity corporateAction = CorporateActionEntity.builder()
                .stockCode(symbol)
                .exDividendDate(LocalDate.of(2024, 1, 5))
                .backwardAdjFactor(0.98) // 假设复权因子为0.98
                .build();

        List<CorporateActionEntity> actions = List.of(corporateAction);
        when(corporateActionRepository.findByStockCodeOrderByExDividendDateDesc(symbol)).thenReturn(actions);

        // Act
        List<HistoricalKLineEntity> adjustedKLines = historicalDataService.getHistoricalKLine(symbol, startDate, endDate, KLineType.K_DAY, RehabType.BACKWARD);

        // Assert
        assertThat(adjustedKLines).hasSize(2);

        // 验证复权日之前的K线价格是否被调整
        HistoricalKLineEntity adjustedKlineBefore = adjustedKLines.stream()
                .filter(k -> k.getTimestamp().equals(klineBeforeAction.getTimestamp()))
                .findFirst().orElseThrow();
        
        assertThat(adjustedKlineBefore.getClose()).isEqualByComparingTo(new BigDecimal("98.000")); // 100.0 * 0.98
        assertThat(adjustedKlineBefore.getOpen()).isEqualByComparingTo(new BigDecimal("96.040"));  // 98.0 * 0.98
        assertThat(adjustedKlineBefore.getHigh()).isEqualByComparingTo(new BigDecimal("98.980"));  // 101.0 * 0.98
        assertThat(adjustedKlineBefore.getLow()).isEqualByComparingTo(new BigDecimal("95.060"));   // 97.0 * 0.98
        assertThat(adjustedKlineBefore.getRehabType()).isEqualTo(RehabType.BACKWARD);

        // 验证复权日之后的K线价格是否保持不变
        HistoricalKLineEntity adjustedKlineAfter = adjustedKLines.stream()
                .filter(k -> k.getTimestamp().equals(klineAfterAction.getTimestamp()))
                .findFirst().orElseThrow();

        assertThat(adjustedKlineAfter.getClose()).isEqualByComparingTo(klineAfterAction.getClose()); // 保持不变
    }
}
