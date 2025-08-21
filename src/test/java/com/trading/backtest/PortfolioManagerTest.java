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
    private HKStockCommissionCalculator commissionCalculator;
    private static final double INITIAL_CAPITAL = 100000.0;
    private static final double TEST_SLIPPAGE_RATE = 0.001; // 0.1%
    private static final String TEST_STOCK_SYMBOL = "HK.00700";
    private static final String TEST_ETF_SYMBOL = "02800.HK";

    @BeforeEach
    void setUp() {
        commissionCalculator = new HKStockCommissionCalculator();
        portfolioManager = new PortfolioManager(INITIAL_CAPITAL, TEST_SLIPPAGE_RATE, commissionCalculator);
    }

    @Test
    @DisplayName("测试初始化状态是否正确")
    void testInitialState() {
        assertThat(portfolioManager.getInitialCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL));
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL));
        assertThat(portfolioManager.getPositions()).isEmpty();
    }

    @Test
    @DisplayName("测试买入股票时，滑点和所有交易成本是否正确计算")
    void testBuyStock_WithSlippageAndCommission() {
        // Arrange
        Order buyOrder = createOrder(TEST_STOCK_SYMBOL, OrderSide.BUY, 100, 300.0);
        BigDecimal idealPrice = buyOrder.getPrice();
        long quantity = buyOrder.getQuantity();

        // 手动计算预期结果
        BigDecimal executionPrice = idealPrice.add(idealPrice.multiply(BigDecimal.valueOf(TEST_SLIPPAGE_RATE)));
        HKStockCommissionCalculator.CommissionBreakdown expectedCosts = commissionCalculator.calculateCommission(executionPrice, quantity, TEST_STOCK_SYMBOL, false);
        BigDecimal totalCharge = executionPrice.multiply(BigDecimal.valueOf(quantity)).add(expectedCosts.getTotalCost());

        // Act
        portfolioManager.processTransaction(buyOrder);

        // Assert
        BigDecimal expectedCash = BigDecimal.valueOf(INITIAL_CAPITAL).subtract(totalCharge);
        assertThat(portfolioManager.getCash()).isBetween(expectedCash.subtract(new BigDecimal("0.001")), expectedCash.add(new BigDecimal("0.001")));
        
        PortfolioManager.Position position = portfolioManager.getPositions().get(TEST_STOCK_SYMBOL);
        assertThat(position).isNotNull();
        BigDecimal expectedAvgCost = totalCharge.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
        assertThat(position.averageCost()).isBetween(expectedAvgCost.subtract(new BigDecimal("0.001")), expectedAvgCost.add(new BigDecimal("0.001")));
    }

    @Test
    @DisplayName("测试卖出ETF时，印花税是否被豁免")
    void testSellEtf_StampDutyExemption() {
        // Arrange
        PortfolioManager.Position etfPosition = new PortfolioManager.Position(TEST_ETF_SYMBOL, 1000, new BigDecimal("20.0"), BigDecimal.ZERO);
        portfolioManager.getPositions().put(TEST_ETF_SYMBOL, etfPosition);
        portfolioManager = new PortfolioManager(INITIAL_CAPITAL - 20000, TEST_SLIPPAGE_RATE, commissionCalculator);
        portfolioManager.getPositions().put(TEST_ETF_SYMBOL, etfPosition);

        Order sellOrder = createOrder(TEST_ETF_SYMBOL, OrderSide.SELL, 1000, 22.0);
        BigDecimal executionPrice = sellOrder.getPrice().subtract(sellOrder.getPrice().multiply(BigDecimal.valueOf(TEST_SLIPPAGE_RATE)));

        // Act
        portfolioManager.processTransaction(sellOrder);

        // Assert
        Order executedOrder = portfolioManager.getTradeHistory().get(0);
        HKStockCommissionCalculator.CommissionBreakdown costs = commissionCalculator.calculateCommission(executedOrder.getExecutedPrice(), executedOrder.getQuantity(), TEST_ETF_SYMBOL, true);
        
        assertThat(costs.getStampDuty()).isEqualByComparingTo(BigDecimal.ZERO);
        BigDecimal netProceeds = executionPrice.multiply(BigDecimal.valueOf(sellOrder.getQuantity())).subtract(costs.getTotalCost());
        BigDecimal expectedCash = BigDecimal.valueOf(INITIAL_CAPITAL - 20000).add(netProceeds);
        assertThat(portfolioManager.getCash()).isBetween(expectedCash.subtract(new BigDecimal("0.001")), expectedCash.add(new BigDecimal("0.001")));
    }

    @Test
    @DisplayName("测试执行买入：资金不足（已考虑滑点和费用）")
    void testProcessTransaction_Buy_InsufficientFunds() {
        // Arrange
        // 创建一个肯定会超出资金的订单
        Order buyOrder = createOrder(TEST_STOCK_SYMBOL, OrderSide.BUY, 1000, INITIAL_CAPITAL);

        // Act
        portfolioManager.processTransaction(buyOrder);

        // Assert
        // 验证现金和持仓均未发生变化，且无交易历史记录
        assertThat(portfolioManager.getCash()).isEqualByComparingTo(BigDecimal.valueOf(INITIAL_CAPITAL));
        assertThat(portfolioManager.getPositions()).isEmpty();
        assertThat(portfolioManager.getTradeHistory()).isEmpty();
    }

    private Order createOrder(String symbol, OrderSide side, long quantity, double price) {
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