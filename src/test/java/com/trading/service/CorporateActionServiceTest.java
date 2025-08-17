package com.trading.service;

import com.trading.config.TradingProperties;
import com.trading.domain.entity.CorporateActionEntity;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.repository.CorporateActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class CorporateActionServiceTest {

    @Autowired
    private CorporateActionService corporateActionService;

    @Autowired
    private CorporateActionRepository corporateActionRepository;

    @MockBean
    private FutuMarketDataService futuMarketDataService;

    @Autowired
    private TradingProperties tradingProperties;

    @Test
    void testProcessAndSaveCorporateActionForStock() {
        // Arrange: 准备模拟数据和行为
        String stockCode = "HK.00700";
        List<CorporateActionEntity> mockActions = List.of(
                CorporateActionEntity.builder()
                        .stockCode(stockCode)
                        .actionType(CorporateActionEntity.CorporateActionType.DIVIDEND)
                        .exDividendDate(LocalDate.of(2024, 5, 15))
                        .dividend(1.2)
                        .forwardAdjFactor(1.0)
                        .backwardAdjFactor(0.98)
                        .build(),
                CorporateActionEntity.builder()
                        .stockCode(stockCode)
                        .actionType(CorporateActionEntity.CorporateActionType.SPLIT)
                        .exDividendDate(LocalDate.of(2023, 1, 10))
                        .splitBase(1.0)
                        .splitErt(2.0)
                        .forwardAdjFactor(0.5)
                        .backwardAdjFactor(1.0)
                        .build()
        );

        // 当调用mock的service时，返回我们准备好的数据
        when(futuMarketDataService.getRehab(stockCode)).thenReturn(mockActions);

        // Act: 执行被测试的方法
        corporateActionService.processAndSaveCorporateActionForStock(stockCode);

        // Assert: 验证结果
        List<CorporateActionEntity> savedActions = corporateActionRepository.findByStockCodeOrderByExDividendDateDesc(stockCode);

        assertThat(savedActions).isNotNull();
        assertThat(savedActions.size()).isEqualTo(2);

        CorporateActionEntity latestAction = savedActions.get(0);
        assertThat(latestAction.getActionType()).isEqualTo(CorporateActionEntity.CorporateActionType.DIVIDEND);
        assertThat(latestAction.getDividend()).isEqualTo(1.2);
        assertThat(latestAction.getExDividendDate()).isEqualTo(LocalDate.of(2024, 5, 15));
    }

    @Test
    void testProcessAndSaveAllCorporateActions() {
        // Arrange
        String stockCode1 = "HK.00700";
        String stockCode2 = "HK.09988";

        // 确保我们的测试股票在配置中
        tradingProperties.setTargets(List.of(
                createTarget(stockCode1),
                createTarget(stockCode2)
        ));

        List<CorporateActionEntity> mockActions1 = List.of(
                CorporateActionEntity.builder().stockCode(stockCode1).actionType(CorporateActionEntity.CorporateActionType.DIVIDEND).exDividendDate(LocalDate.now()).dividend(1.0).forwardAdjFactor(1.0).backwardAdjFactor(0.99).build()
        );
        List<CorporateActionEntity> mockActions2 = List.of(
                CorporateActionEntity.builder().stockCode(stockCode2).actionType(CorporateActionEntity.CorporateActionType.BONUS).exDividendDate(LocalDate.now()).bonusBase(10.0).bonusErt(1.0).forwardAdjFactor(1.0).backwardAdjFactor(0.95).build()
        );

        when(futuMarketDataService.getRehab(stockCode1)).thenReturn(mockActions1);
        when(futuMarketDataService.getRehab(stockCode2)).thenReturn(mockActions2);

        // Act
        corporateActionService.processAndSaveAllCorporateActions();

        // Assert
        List<CorporateActionEntity> savedActions1 = corporateActionRepository.findByStockCodeOrderByExDividendDateDesc(stockCode1);
        List<CorporateActionEntity> savedActions2 = corporateActionRepository.findByStockCodeOrderByExDividendDateDesc(stockCode2);

        assertThat(savedActions1).hasSize(1);
        assertThat(savedActions1.get(0).getActionType()).isEqualTo(CorporateActionEntity.CorporateActionType.DIVIDEND);

        assertThat(savedActions2).hasSize(1);
        assertThat(savedActions2.get(0).getActionType()).isEqualTo(CorporateActionEntity.CorporateActionType.BONUS);
    }

    private TradingProperties.Target createTarget(String symbol) {
        TradingProperties.Target target = new TradingProperties.Target();
        target.setSymbol(symbol);
        return target;
    }
}
