package com.trading.backtest;

import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

    private final BigDecimal initialCash;
    private BigDecimal cash;
    private final Map<String, Position> positions;
    private final List<EquitySnapshot> dailyEquitySnapshots;
    private final List<Order> tradeHistory;
    private final BigDecimal slippageRate;
    private final HKStockCommissionCalculator commissionCalculator;

    public record Position(String symbol, long quantity, BigDecimal averageCost, BigDecimal marketValue) {
        public Position withMarketValue(BigDecimal newMarketValue) {
            return new Position(this.symbol, this.quantity, this.averageCost, newMarketValue);
        }
    }

    public record EquitySnapshot(LocalDate date, BigDecimal totalValue) {}

    public PortfolioManager(double initialCash, double slippageRate, HKStockCommissionCalculator commissionCalculator) {
        this.initialCash = BigDecimal.valueOf(initialCash);
        this.cash = BigDecimal.valueOf(initialCash);
        this.slippageRate = BigDecimal.valueOf(slippageRate);
        this.commissionCalculator = commissionCalculator;
        this.positions = new HashMap<>();
        this.dailyEquitySnapshots = new ArrayList<>();
        this.tradeHistory = new ArrayList<>();
    }

    public void processTransaction(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            executeBuy(order);
        } else if (order.getSide() == OrderSide.SELL) {
            executeSell(order);
        }
    }

    private void executeBuy(Order buyOrder) {
        BigDecimal idealPrice = buyOrder.getPrice();
        BigDecimal executionPrice = idealPrice.add(idealPrice.multiply(this.slippageRate));

        HKStockCommissionCalculator.CommissionBreakdown costs = commissionCalculator.calculateCommission(executionPrice, buyOrder.getQuantity(), buyOrder.getSymbol(), false);
        BigDecimal grossValue = executionPrice.multiply(BigDecimal.valueOf(buyOrder.getQuantity()));
        BigDecimal totalCharge = grossValue.add(costs.getTotalCost());

        if (cash.compareTo(totalCharge) < 0) {
            log.warn("现金不足(含费用)，无法买入: 需要 {}, 可用 {}", totalCharge, cash);
            return;
        }

        cash = cash.subtract(totalCharge);
        Position currentPosition = positions.getOrDefault(buyOrder.getSymbol(), new Position(buyOrder.getSymbol(), 0, BigDecimal.ZERO, BigDecimal.ZERO));
        long newQuantity = currentPosition.quantity() + buyOrder.getQuantity();
        BigDecimal newTotalCost = currentPosition.averageCost().multiply(BigDecimal.valueOf(currentPosition.quantity())).add(totalCharge);
        BigDecimal newAverageCost = newTotalCost.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP);

        positions.put(buyOrder.getSymbol(), new Position(buyOrder.getSymbol(), newQuantity, newAverageCost, BigDecimal.ZERO));
        updateAndRecordOrder(buyOrder, executionPrice, costs.getTotalCost());
    }

    private void executeSell(Order sellOrder) {
        Position currentPosition = positions.get(sellOrder.getSymbol());
        if (currentPosition == null || currentPosition.quantity() < sellOrder.getQuantity()) {
            log.warn("持仓不足，无法卖出: {}, 需要 {}, 现有 {}", sellOrder.getSymbol(), sellOrder.getQuantity(), currentPosition != null ? currentPosition.quantity() : 0);
            return;
        }

        BigDecimal idealPrice = sellOrder.getPrice();
        BigDecimal executionPrice = idealPrice.subtract(idealPrice.multiply(this.slippageRate));

        HKStockCommissionCalculator.CommissionBreakdown costs = commissionCalculator.calculateCommission(executionPrice, sellOrder.getQuantity(), sellOrder.getSymbol(), true);
        BigDecimal grossValue = executionPrice.multiply(BigDecimal.valueOf(sellOrder.getQuantity()));
        BigDecimal netProceeds = grossValue.subtract(costs.getTotalCost());

        cash = cash.add(netProceeds);
        long newQuantity = currentPosition.quantity() - sellOrder.getQuantity();
        if (newQuantity == 0) {
            positions.remove(sellOrder.getSymbol());
        } else {
            positions.put(sellOrder.getSymbol(), new Position(sellOrder.getSymbol(), newQuantity, currentPosition.averageCost(), BigDecimal.ZERO));
        }
        updateAndRecordOrder(sellOrder, executionPrice, costs.getTotalCost());
    }

    private void updateAndRecordOrder(Order order, BigDecimal executedPrice, BigDecimal commission) {
        order.setExecutedPrice(executedPrice);
        order.setCommission(commission);
        this.tradeHistory.add(order);
    }

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

    public void recordDailyEquity(LocalDate currentDate) {
        BigDecimal totalEquity = calculateTotalEquity();
        dailyEquitySnapshots.add(new EquitySnapshot(currentDate, totalEquity));
        log.debug("记录每日权益快照: 日期 {}, 总权益 {}", currentDate, totalEquity);
    }

    public BigDecimal calculateTotalEquity() {
        BigDecimal positionsValue = positions.values().stream()
                .map(Position::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return cash.add(positionsValue);
    }
}
