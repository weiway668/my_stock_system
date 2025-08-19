package com.trading.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;

@DisplayName("PortfolioManager单元测试")
class PortfolioManagerTest {

    private PortfolioManager portfolioManager;
    private static final double INITIAL_CAPITAL = 100000.0;
    private static final String TEST_SYMBOL = "HK.00700";

    @BeforeEach
    void setUp() {
        portfolioManager = new PortfolioManager(INITIAL_CAPITAL);
    }

    @Test
    @DisplayName("测试初始化状态是否正确")
    void testInitialState() {
        assertThat(portfolioManager.getInitialCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL));
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL));
        assertThat(portfolioManager.getPositions()).isEmpty();
        assertThat(portfolioManager.getDailyEquitySnapshots()).isEmpty();
        assertThat(portfolioManager.getTradeHistory()).isEmpty();
    }

    @Test
    @DisplayName("测试执行买入：创建新仓位")
    void testProcessTransaction_Buy_NewPosition() {
        // Arrange
        Order buyOrder = createOrder(TEST_SYMBOL, OrderSide.BUY, 100, 300.0);
        BigDecimal transactionCost = buyOrder.getPrice().multiply(BigDecimal.valueOf(buyOrder.getQuantity()));

        // Act
        portfolioManager.processTransaction(buyOrder);

        // Assert
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL).subtract(transactionCost));
        assertThat(portfolioManager.getPositions()).hasSize(1);
        assertThat(portfolioManager.getTradeHistory()).hasSize(1);

        PortfolioManager.Position position = portfolioManager.getPositions().get(TEST_SYMBOL);
        assertThat(position.symbol()).isEqualTo(TEST_SYMBOL);
        assertThat(position.quantity()).isEqualTo(100);
        assertThat(position.averageCost()).isEqualByComparingTo(BigDecimal.valueOf(300.0));
    }

    @Test
    @DisplayName("测试执行买入：增加现有仓位")
    void testProcessTransaction_Buy_AddToExistingPosition() {
        // Arrange
        Order firstBuy = createOrder(TEST_SYMBOL, OrderSide.BUY, 100, 300.0);
        portfolioManager.processTransaction(firstBuy);

        Order secondBuy = createOrder(TEST_SYMBOL, OrderSide.BUY, 100, 320.0);
        BigDecimal secondTransactionCost = secondBuy.getPrice().multiply(BigDecimal.valueOf(secondBuy.getQuantity()));

        // Act
        portfolioManager.processTransaction(secondBuy);

        // Assert
        BigDecimal expectedCash = BigDecimal.valueOf(INITIAL_CAPITAL)
                .subtract(firstBuy.getPrice().multiply(BigDecimal.valueOf(100)))
                .subtract(secondTransactionCost);
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(expectedCash);
        assertThat(portfolioManager.getPositions()).hasSize(1);

        PortfolioManager.Position position = portfolioManager.getPositions().get(TEST_SYMBOL);
        assertThat(position.quantity()).isEqualTo(200);
        // 验证平均成本计算: (100*300 + 100*320) / 200 = 310
        assertThat(position.averageCost()).isEqualByComparingTo(BigDecimal.valueOf(310.0));
    }

    @Test
    @DisplayName("测试执行买入：资金不足")
    void testProcessTransaction_Buy_InsufficientFunds() {
        // Arrange
        Order buyOrder = createOrder(TEST_SYMBOL, OrderSide.BUY, 1000, 300.0); // 300,000 > 100,000

        // Act
        portfolioManager.processTransaction(buyOrder);

        // Assert
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL));
        assertThat(portfolioManager.getPositions()).isEmpty();
        assertThat(portfolioManager.getTradeHistory()).isEmpty(); // 交易失败，不应记录
    }

    @Test
    @DisplayName("测试执行卖出：部分减仓")
    void testProcessTransaction_Sell_Partial() {
        // Arrange
        Order buyOrder = createOrder(TEST_SYMBOL, OrderSide.BUY, 200, 300.0);
        portfolioManager.processTransaction(buyOrder);

        Order sellOrder = createOrder(TEST_SYMBOL, OrderSide.SELL, 100, 350.0);
        BigDecimal transactionValue = sellOrder.getPrice().multiply(BigDecimal.valueOf(sellOrder.getQuantity()));

        // Act
        portfolioManager.processTransaction(sellOrder);

        // Assert
        BigDecimal expectedCash = BigDecimal.valueOf(INITIAL_CAPITAL)
                .subtract(buyOrder.getPrice().multiply(BigDecimal.valueOf(200)))
                .add(transactionValue);
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(expectedCash);
        assertThat(portfolioManager.getPositions()).hasSize(1);

        PortfolioManager.Position position = portfolioManager.getPositions().get(TEST_SYMBOL);
        assertThat(position.quantity()).isEqualTo(100);
        assertThat(position.averageCost()).isEqualByComparingTo(BigDecimal.valueOf(300.0)); // 卖出不影响成本
    }

    @Test
    @DisplayName("测试执行卖出：全部平仓")
    void testProcessTransaction_Sell_ClosePosition() {
        // Arrange
        Order buyOrder = createOrder(TEST_SYMBOL, OrderSide.BUY, 100, 300.0);
        portfolioManager.processTransaction(buyOrder);

        Order sellOrder = createOrder(TEST_SYMBOL, OrderSide.SELL, 100, 350.0);

        // Act
        portfolioManager.processTransaction(sellOrder);

        // Assert
        assertThat(portfolioManager.getPositions()).isEmpty();
    }

    @Test
    @DisplayName("测试执行卖出：持仓不足")
    void testProcessTransaction_Sell_InsufficientPosition() {
        // Arrange
        Order buyOrder = createOrder(TEST_SYMBOL, OrderSide.BUY, 100, 300.0);
        portfolioManager.processTransaction(buyOrder);

        Order sellOrder = createOrder(TEST_SYMBOL, OrderSide.SELL, 200, 350.0); // 卖出200股，但只有100股

        // Act
        portfolioManager.processTransaction(sellOrder);

        // Assert
        assertThat(portfolioManager.getPositions()).hasSize(1); // 仓位不变
        assertThat(portfolioManager.getPositions().get(TEST_SYMBOL).quantity()).isEqualTo(100);
        assertThat(portfolioManager.getTradeHistory()).hasSize(1); // 只有买入成功，卖出失败
    }

    private Order createOrder(String symbol, OrderSide side, long quantity, double price) {
        return Order.builder()
                .orderId(UUID.randomUUID().toString())
                .symbol(symbol)
                .side(side)
                .type(OrderType.LIMIT)
                .quantity((int)quantity)
                .price(BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP))
                .status(OrderStatus.PENDING) // 初始状态
                .createTime(LocalDateTime.now())
                .build();
    }
}
