package com.trading.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.service.MarketDataService;
import com.trading.strategy.TechnicalAnalysisService;
import com.trading.strategy.TradingStrategy;

@ExtendWith(MockitoExtension.class)
@DisplayName("BacktestEngine单元测试")
class BacktestEngineTest {

        @Mock
        private MarketDataService marketDataService;
        @Mock
        private TechnicalAnalysisService technicalAnalysisService;
        @Mock
        private HKStockCommissionCalculator commissionCalculator;
        @Mock
        private TradingStrategy mockStrategy;

        @InjectMocks
        private BacktestEngine backtestEngine;

        @Test
        @DisplayName("测试限价单在价格触及时正确执行")
        void testLimitOrderExecution() throws Exception {
                // Arrange
                // 1. 准备回测请求
                BacktestRequest request = BacktestRequest.builder()
                                .symbol("HK.00700")
                                .strategy(mockStrategy)
                                .startTime(LocalDateTime.of(2024, 1, 1, 9, 30))
                                .endTime(LocalDateTime.of(2024, 1, 2, 16, 0))
                                .initialCapital(new BigDecimal("100000"))
                                .slippageRate(BigDecimal.ZERO) // 为简化测试，禁用滑点
                                .build();

                // 2. 准备模拟市场数据
                MarketData bar1 = MarketData.builder().symbol("HK.00700").timestamp(request.getStartTime())
                                .low(new BigDecimal("291")).high(new BigDecimal("295")).close(new BigDecimal("294"))
                                .build();
                MarketData bar2 = MarketData.builder().symbol("HK.00700").timestamp(request.getEndTime())
                                .low(new BigDecimal("288")).high(new BigDecimal("292")).close(new BigDecimal("290"))
                                .build();
                when(marketDataService.getOhlcvData(anyString(), anyString(), any(LocalDateTime.class),
                                any(LocalDateTime.class), anyInt(), RehabType.FORWARD))
                                .thenReturn(CompletableFuture.completedFuture(List.of(bar1, bar2)));

                // 3. 准备模拟策略行为
                BigDecimal limitPrice = new BigDecimal("290");
                TradingStrategy.TradingSignal limitBuySignal = new TradingStrategy.TradingSignal();
                limitBuySignal.setType(TradingStrategy.TradingSignal.SignalType.BUY);
                limitBuySignal.setSymbol(request.getSymbol());
                limitBuySignal.setOrderType(OrderType.LIMIT);
                limitBuySignal.setPrice(limitPrice);

                TradingStrategy.TradingSignal noActionSignal = new TradingStrategy.TradingSignal();
                noActionSignal.setType(TradingStrategy.TradingSignal.SignalType.NO_ACTION);

                when(mockStrategy.generateSignal(any(MarketData.class), anyList(), anyList(), anyList()))
                                .thenReturn(limitBuySignal) // 第一次调用时，发出限价单
                                .thenReturn(noActionSignal); // 第二次调用时，无操作

                // 4. 模拟费用计算器
                when(commissionCalculator.calculateCommission(any(BigDecimal.class), anyLong(), anyString(),
                                anyBoolean()))
                                .thenReturn(HKStockCommissionCalculator.CommissionBreakdown.builder()
                                                .totalCost(BigDecimal.ZERO).build());

                // Act
                BacktestResult result = backtestEngine.runBacktest(request).get();

                // Assert
                assertThat(result.isSuccessful()).isTrue();
                assertThat(result.getTradeHistory()).hasSize(1);

                Order executedOrder = result.getTradeHistory().get(0);
                assertThat(executedOrder.getSide()).isEqualTo(OrderSide.BUY);
                assertThat(executedOrder.getType()).isEqualTo(OrderType.LIMIT);
                assertThat(executedOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
                // 验证成交价是否为我们的限价
                assertThat(executedOrder.getPrice()).isEqualByComparingTo(limitPrice);
                // 验证订单创建时间是否在第一个数据点
                assertThat(executedOrder.getCreateTime()).isEqualTo(bar1.getTimestamp());
                // 验证最终权益是否正确（现金+持仓市值），因为成交价=收盘价，所以权益不变
                BigDecimal finalPositionValue = limitPrice.multiply(new BigDecimal("100"));
                BigDecimal finalCash = new BigDecimal("100000").subtract(finalPositionValue);
                BigDecimal expectedFinalEquity = finalCash.add(finalPositionValue);
                assertThat(result.getFinalEquity()).isEqualByComparingTo(expectedFinalEquity);
                assertThat(result.getFinalEquity()).isEqualByComparingTo(new BigDecimal("100000"));
        }
}
