package com.trading.backtest;

import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PortfolioManager单元测试")
class PortfolioManagerTest {

    private PortfolioManager portfolioManager;
    private static final double INITIAL_CAPITAL = 100000.0;
    private static final double TEST_SLIPPAGE_RATE = 0.001; // 0.1%
    private static final String TEST_SYMBOL = "HK.00700";

    @BeforeEach
    void setUp() {
        // 每个测试都使用新的管理器实例，并传入滑点率
        portfolioManager = new PortfolioManager(INITIAL_CAPITAL, TEST_SLIPPAGE_RATE);
    }

    @Test
    @DisplayName("测试初始化状态是否正确")
    void testInitialState() {
        assertThat(portfolioManager.getInitialCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL));
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL));
        assertThat(portfolioManager.getSlippageRate()).isEqualByComparingTo(BigDecimal.valueOf(TEST_SLIPPAGE_RATE));
        assertThat(portfolioManager.getPositions()).isEmpty();
        assertThat(portfolioManager.getDailyEquitySnapshots()).isEmpty();
        assertThat(portfolioManager.getTradeHistory()).isEmpty();
    }

    @Test
    @DisplayName("测试买入时滑点是否正确应用（成交价更高）")
    void testSlippage_BuyPriceIsHigher() {
        // Arrange
        Order buyOrder = createOrder(TEST_SYMBOL, OrderSide.BUY, 100, 300.0);
        BigDecimal idealPrice = buyOrder.getPrice();
        BigDecimal expectedExecutionPrice = idealPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(TEST_SLIPPAGE_RATE)));
        BigDecimal expectedTransactionCost = expectedExecutionPrice.multiply(BigDecimal.valueOf(100));

        // Act
        portfolioManager.processTransaction(buyOrder);

        // Assert
        Order executedOrder = portfolioManager.getTradeHistory().get(0);
        assertThat(executedOrder.getExecutedPrice()).isNotNull();
        assertThat(executedOrder.getExecutedPrice()).isEqualByComparingTo(expectedExecutionPrice);
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL).subtract(expectedTransactionCost));
    }

    @Test
    @DisplayName("测试卖出时滑点是否正确应用（成交价更低）")
    void testSlippage_SellPriceIsLower() {
        // Arrange
        // 所有操作都在同一个portfolioManager实例上进行
        Order buyOrder = createOrder(TEST_SYMBOL, OrderSide.BUY, 100, 300.0);
        portfolioManager.processTransaction(buyOrder);

        Order sellOrder = createOrder(TEST_SYMBOL, OrderSide.SELL, 100, 350.0);
        BigDecimal idealSellPrice = sellOrder.getPrice();
        BigDecimal expectedSellExecutionPrice = idealSellPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(TEST_SLIPPAGE_RATE)));

        // Act
        portfolioManager.processTransaction(sellOrder);

        // Assert
        assertThat(portfolioManager.getTradeHistory()).hasSize(2);
        Order executedSellOrder = portfolioManager.getTradeHistory().get(1); // 卖出是第二笔交易
        assertThat(executedSellOrder.getExecutedPrice()).isNotNull();
        assertThat(executedSellOrder.getExecutedPrice()).isEqualByComparingTo(expectedSellExecutionPrice);

        // 精确计算两次滑点后的最终现金
        Order executedBuyOrder = portfolioManager.getTradeHistory().get(0);
        BigDecimal buyCost = executedBuyOrder.getExecutedPrice().multiply(BigDecimal.valueOf(buyOrder.getQuantity()));
        BigDecimal sellValue = executedSellOrder.getExecutedPrice().multiply(BigDecimal.valueOf(sellOrder.getQuantity()));
        BigDecimal expectedCash = BigDecimal.valueOf(INITIAL_CAPITAL).subtract(buyCost).add(sellValue);

        assertThat(portfolioManager.getCash()).isEqualByComparingTo(expectedCash);
    }

    // ... 保留之前的其他核心逻辑测试，并适配新的构造函数 ...

    @Test
    @DisplayName("测试执行买入：资金不足（已考虑滑点）")
    void testProcessTransaction_Buy_InsufficientFunds_WithSlippage() {
        // Arrange
        double highPrice = INITIAL_CAPITAL; // 价格很高，确保资金不足
        Order buyOrder = createOrder(TEST_SYMBOL, OrderSide.BUY, 1, highPrice);

        // Act
        portfolioManager.processTransaction(buyOrder);

        // Assert
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL));
        assertThat(portfolioManager.getPositions()).isEmpty();
        assertThat(portfolioManager.getTradeHistory()).isEmpty();
    }

    private Order createOrder(String symbol, OrderSide side, int quantity, double price) {
        return Order.builder()
                .orderId(UUID.randomUUID().toString())
                .symbol(symbol)
                .side(side)
                .type(OrderType.LIMIT)
                .quantity(quantity)
                .price(BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP))
                .status(OrderStatus.PENDING)
                .createTime(LocalDateTime.now())
                .build();
    }
}
