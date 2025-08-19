package com.trading.backtest;

import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 投资组合管理器
 * 在回测过程中，负责实时跟踪和管理现金、持仓、总资产和每日权益快照。
 */
@Slf4j
@Getter
public class PortfolioManager {

    private final BigDecimal initialCash; // 初始资金
    private BigDecimal cash; // 当前现金
    private final Map<String, Position> positions; // 当前持仓
    private final List<EquitySnapshot> dailyEquitySnapshots; // 每日权益快照
    private final List<Order> tradeHistory; // 交易历史

    /**
     * 投资组合内部持仓记录
     * @param symbol 股票代码
     * @param quantity 持有数量
     * @param averageCost 平均成本
     * @param marketValue 当前市值
     */
    public record Position(String symbol, long quantity, BigDecimal averageCost, BigDecimal marketValue) {
        // 更新持仓市值
        public Position withMarketValue(BigDecimal newMarketValue) {
            return new Position(this.symbol, this.quantity, this.averageCost, newMarketValue);
        }
    }

    /**
     * 每日权益快照
     * @param date 日期
     * @param totalValue 当日总权益
     */
    public record EquitySnapshot(LocalDate date, BigDecimal totalValue) {}

    /**
     * 构造函数
     * @param initialCash 初始投入资金
     */
    public PortfolioManager(double initialCash) {
        this.initialCash = BigDecimal.valueOf(initialCash);
        this.cash = BigDecimal.valueOf(initialCash);
        this.positions = new HashMap<>();
        this.dailyEquitySnapshots = new ArrayList<>();
        this.tradeHistory = new ArrayList<>();
    }

    /**
     * 处理交易订单，更新投资组合状态
     * @param order 要执行的订单
     */
    public void processTransaction(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            executeBuy(order);
        } else if (order.getSide() == OrderSide.SELL) {
            executeSell(order);
        }
        this.tradeHistory.add(order);
    }

    /**
     * 执行买入操作
     * @param buyOrder 买入订单
     */
    private void executeBuy(Order buyOrder) {
        String symbol = buyOrder.getSymbol();
        long quantity = buyOrder.getQuantity();
        BigDecimal price = buyOrder.getPrice();
        BigDecimal transactionCost = price.multiply(BigDecimal.valueOf(quantity));

        if (cash.compareTo(transactionCost) < 0) {
            log.warn("现金不足，无法执行买入订单: {}", buyOrder);
            // 在真实场景中可以部分成交或失败，这里简化为完全失败
            return;
        }

        cash = cash.subtract(transactionCost);

        Position currentPosition = positions.getOrDefault(symbol, new Position(symbol, 0, BigDecimal.ZERO, BigDecimal.ZERO));
        
        long newQuantity = currentPosition.quantity() + quantity;
        BigDecimal newTotalCost = currentPosition.averageCost().multiply(BigDecimal.valueOf(currentPosition.quantity()))
                                    .add(transactionCost);
        BigDecimal newAverageCost = newTotalCost.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP);

        positions.put(symbol, new Position(symbol, newQuantity, newAverageCost, BigDecimal.ZERO)); // 市值将在下个tick更新
        log.debug("执行买入: {}, 当前现金: {}", buyOrder, cash);
    }

    /**
     * 执行卖出操作
     * @param sellOrder 卖出订单
     */
    private void executeSell(Order sellOrder) {
        String symbol = sellOrder.getSymbol();
        long quantity = sellOrder.getQuantity();
        BigDecimal price = sellOrder.getPrice();

        Position currentPosition = positions.get(symbol);
        if (currentPosition == null || currentPosition.quantity() < quantity) {
            log.warn("持仓不足，无法执行卖出订单: {}", sellOrder);
            // 在真实场景中可以部分成交或失败，这里简化为完全失败
            return;
        }

        BigDecimal transactionValue = price.multiply(BigDecimal.valueOf(quantity));
        cash = cash.add(transactionValue);

        long newQuantity = currentPosition.quantity() - quantity;
        if (newQuantity == 0) {
            positions.remove(symbol);
        } else {
            // 平均成本在卖出时保持不变
            positions.put(symbol, new Position(symbol, newQuantity, currentPosition.averageCost(), BigDecimal.ZERO));
        }
        log.debug("执行卖出: {}, 当前现金: {}", sellOrder, cash);
    }

    /**
     * 在每个时间点（tick）更新所有持仓的当前市值
     * @param marketPrices <股票代码, 当前价格> 的Map
     */
    public void updatePositionsMarketValue(Map<String, BigDecimal> marketPrices) {
        for (String symbol : positions.keySet()) {
            if (marketPrices.containsKey(symbol)) {
                Position currentPosition = positions.get(symbol);
                BigDecimal currentPrice = marketPrices.get(symbol);
                BigDecimal marketValue = currentPrice.multiply(BigDecimal.valueOf(currentPosition.quantity()));
                positions.put(symbol, currentPosition.withMarketValue(marketValue));
            }
        }
    }

    /**
     * 在每个交易日结束时，记录当日的总权益快照
     * @param currentDate 当前日期
     */
    public void recordDailyEquity(LocalDate currentDate) {
        BigDecimal totalEquity = calculateTotalEquity();
        dailyEquitySnapshots.add(new EquitySnapshot(currentDate, totalEquity));
        log.debug("记录每日权益快照: 日期 {}, 总权益 {}", currentDate, totalEquity);
    }

    /**
     * 计算当前总权益（现金 + 所有持仓的市值）
     * @return 总权益
     */
    public BigDecimal calculateTotalEquity() {
        BigDecimal positionsValue = positions.values().stream()
                .map(Position::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return cash.add(positionsValue);
    }
}
