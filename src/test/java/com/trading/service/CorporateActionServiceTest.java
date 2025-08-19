package com.trading.service;

import com.futu.openapi.pb.QotCommon;
import com.trading.domain.entity.CorporateActionEntity;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.repository.CorporateActionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class CorporateActionServiceTest {

    @Autowired
    private CorporateActionService corporateActionService;

    @MockBean
    private CorporateActionRepository corporateActionRepository;

    @MockBean
    private FutuMarketDataService futuMarketDataService;

    @MockBean
    private HistoricalDataService historicalDataService;

    @Test
    void testProcessAndSaveCorporateActionForStock() {
        // Arrange: 准备模拟数据和行为
        String stockCode = "HK.00700";
        LocalDate exDate = LocalDate.of(2024, 5, 17);
        double dividend = 3.4;
        double preClose = 397.0;

        // 1. 模拟Futu API返回的原始复权数据
        QotCommon.Rehab rawRehab = QotCommon.Rehab.newBuilder()
                .setTime(exDate.toString())
                .setDividend(dividend)
                .build();
        when(futuMarketDataService.getRehab(stockCode)).thenReturn(List.of(rawRehab));

        // 2. 模拟HistoricalDataService返回除权日前一天的K线
        HistoricalKLineEntity preCloseKline = HistoricalKLineEntity.builder()
                .close(BigDecimal.valueOf(preClose))
                .timestamp(LocalDateTime.of(2024, 5, 16, 16, 0))
                .build();
        when(historicalDataService.getHistoricalKLine(anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(preCloseKline));

        // Act: 执行被测试的方法
        corporateActionService.processAndSaveCorporateActionForStock(stockCode);

        // Assert: 验证结果
        // 使用ArgumentCaptor捕获传递给saveAll方法的参数
        ArgumentCaptor<List<CorporateActionEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(corporateActionRepository).saveAll(captor.capture());

        List<CorporateActionEntity> savedActions = captor.getValue();

        assertThat(savedActions).isNotNull();
        assertThat(savedActions).hasSize(1);

        CorporateActionEntity savedAction = savedActions.get(0);
        assertThat(savedAction.getActionType()).isEqualTo(CorporateActionEntity.CorporateActionType.DIVIDEND);
        assertThat(savedAction.getDividend()).isEqualTo(dividend);
        assertThat(savedAction.getExDividendDate()).isEqualTo(exDate);

        // 验证核心逻辑：复权因子是否被正确计算
        double expectedFactor = (preClose - dividend) / preClose;
        assertThat(savedAction.getBackwardAdjFactor()).isEqualTo(expectedFactor);
    }

    @Test
    void testProcessAndSave_NoRehabData() {
        // Arrange
        String stockCode = "HK.00700";
        when(futuMarketDataService.getRehab(stockCode)).thenReturn(Collections.emptyList());

        // Act
        corporateActionService.processAndSaveCorporateActionForStock(stockCode);

        // Assert
        // 验证deleteAllByStockCode被调用，但saveAll从未被调用
        verify(corporateActionRepository).deleteAllByStockCode(stockCode);
        verify(corporateActionRepository, never()).saveAll(any());
    }
}
