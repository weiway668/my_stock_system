package com.trading.backtest;

import com.trading.common.utils.BigDecimalUtils;
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
        long quantity = buyOrder.getQuantity();

        HKStockCommissionCalculator.CommissionBreakdown costs = commissionCalculator.calculateCommission(executionPrice, quantity, buyOrder.getSymbol(), false);
        BigDecimal totalCost = costs.getTotalCost();
        BigDecimal grossValue = executionPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal totalCharge = grossValue.add(totalCost);

        if (cash.compareTo(totalCharge) < 0) {
            log.warn("现金不足(含费用)，无法买入: 需要 {}, 可用 {}", totalCharge, cash);
            return;
        }

        cash = cash.subtract(totalCharge);
        Position currentPosition = positions.getOrDefault(buyOrder.getSymbol(), new Position(buyOrder.getSymbol(), 0, BigDecimal.ZERO, BigDecimal.ZERO));
        long newQuantity = currentPosition.quantity() + quantity;
        BigDecimal newTotalCost = currentPosition.averageCost().multiply(BigDecimal.valueOf(currentPosition.quantity())).add(totalCharge);
        BigDecimal newAverageCost = newTotalCost.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP); // 成本保留4位小数以提高精度

        positions.put(buyOrder.getSymbol(), new Position(buyOrder.getSymbol(), newQuantity, newAverageCost, BigDecimal.ZERO));
        updateAndRecordOrder(buyOrder, executionPrice, costs, null);
    }

    private void executeSell(Order sellOrder) {
        Position currentPosition = positions.get(sellOrder.getSymbol());
        if (currentPosition == null || currentPosition.quantity() < sellOrder.getQuantity()) {
            log.warn("持仓不足，无法卖出: {}, 需要 {}, 现有 {}", sellOrder.getSymbol(), sellOrder.getQuantity(), currentPosition != null ? currentPosition.quantity() : 0);
            return;
        }

        BigDecimal idealPrice = sellOrder.getPrice();
        BigDecimal executionPrice = idealPrice.subtract(idealPrice.multiply(this.slippageRate));
        long quantity = sellOrder.getQuantity();

        HKStockCommissionCalculator.CommissionBreakdown costs = commissionCalculator.calculateCommission(executionPrice, quantity, sellOrder.getSymbol(), true);
        BigDecimal totalCost = costs.getTotalCost();
        BigDecimal grossValue = executionPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal netProceeds = grossValue.subtract(totalCost);

        // 计算已实现盈亏
        BigDecimal realizedPnl = executionPrice.multiply(BigDecimal.valueOf(quantity))
                .subtract(currentPosition.averageCost().multiply(BigDecimal.valueOf(quantity)))
                .subtract(totalCost);

        cash = cash.add(netProceeds);
        long newQuantity = currentPosition.quantity() - quantity;
        if (newQuantity == 0) {
            positions.remove(sellOrder.getSymbol());
        } else {
            positions.put(sellOrder.getSymbol(), new Position(sellOrder.getSymbol(), newQuantity, currentPosition.averageCost(), BigDecimal.ZERO));
        }
        updateAndRecordOrder(sellOrder, executionPrice, costs, realizedPnl);
    }

    private void updateAndRecordOrder(Order order, BigDecimal executedPrice, HKStockCommissionCalculator.CommissionBreakdown costs, BigDecimal realizedPnl) {
        order.setExecutedPrice(BigDecimalUtils.scale(executedPrice));
        order.setCommission(costs.getCommission());
        order.setPlatformFee(costs.getPlatformFee());
        order.setSettlementFee(costs.getSettlementFee());
        order.setStampDuty(costs.getStampDuty());
        order.setTradingFee(costs.getTradingFee());
        order.setSfcLevy(costs.getSfcLevy());
        order.setFrcFee(costs.getFrcFee());
        order.setTotalCost(costs.getTotalCost());
        if (realizedPnl != null) {
            order.setRealizedPnl(BigDecimalUtils.scale(realizedPnl));
        }
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
        log.debug("记录每日权益快照: 日期 {}, 总权益 {}", currentDate, BigDecimalUtils.scale(totalEquity));
    }

    public BigDecimal calculateTotalEquity() {
        BigDecimal positionsValue = positions.values().stream()
                .map(Position::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return cash.add(positionsValue);
    }
}
