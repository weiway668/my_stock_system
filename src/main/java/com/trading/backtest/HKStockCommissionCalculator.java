package com.trading.backtest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 港股手续费计算器
 * 根据香港交易所的费用结构计算交易成本
 * 
 * 费用结构（2024年标准）:
 * 1. 佣金：0.025%，最低5港币
 * 2. 印花税：0.13%（仅卖出时收取）
 * 3. 交易费：0.005%
 * 4.结算费：0.002%，最低2港币，最高100港币
 * 5. 交易系统费：0.5港币（每宗交易）
 */
@Slf4j
@Component
public class HKStockCommissionCalculator {
    
    // 费率常量
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.00025"); // 0.025%
    private static final BigDecimal STAMP_DUTY_RATE = new BigDecimal("0.0013");  // 0.13%
    private static final BigDecimal TRADING_FEE_RATE = new BigDecimal("0.00005"); // 0.005%
    private static final BigDecimal SETTLEMENT_FEE_RATE = new BigDecimal("0.00002"); // 0.002%
    
    // 费用上下限
    private static final BigDecimal MIN_COMMISSION = new BigDecimal("5.00");
    private static final BigDecimal MIN_SETTLEMENT_FEE = new BigDecimal("2.00");
    private static final BigDecimal MAX_SETTLEMENT_FEE = new BigDecimal("100.00");
    private static final BigDecimal TRADING_SYSTEM_FEE = new BigDecimal("0.50");
    
    /**
     * 计算买入总成本
     */
    public CommissionBreakdown calculateBuyCommission(BigDecimal price, Integer quantity) {
        return calculateCommission(price, quantity, false);
    }
    
    /**
     * 计算卖出总成本
     */
    public CommissionBreakdown calculateSellCommission(BigDecimal price, Integer quantity) {
        return calculateCommission(price, quantity, true);
    }
    
    /**
     * 计算交易成本详细分解
     * 
     * @param price 成交价格
     * @param quantity 交易数量
     * @param isSell 是否为卖出交易
     * @return 费用明细
     */
    public CommissionBreakdown calculateCommission(BigDecimal price, Integer quantity, boolean isSell) {
        if (price == null || quantity == null || quantity <= 0) {
            return CommissionBreakdown.builder()
                .tradeValue(BigDecimal.ZERO)
                .commission(BigDecimal.ZERO)
                .stampDuty(BigDecimal.ZERO)
                .tradingFee(BigDecimal.ZERO)
                .settlementFee(BigDecimal.ZERO)
                .tradingSystemFee(BigDecimal.ZERO)
                .totalCost(BigDecimal.ZERO)
                .build();
        }
        
        BigDecimal tradeValue = price.multiply(BigDecimal.valueOf(quantity));
        
        // 1. 佣金计算
        BigDecimal commission = tradeValue.multiply(COMMISSION_RATE);
        if (commission.compareTo(MIN_COMMISSION) < 0) {
            commission = MIN_COMMISSION;
        }
        
        // 2. 印花税（仅卖出时收取，四舍五入至最接近分）
        BigDecimal stampDuty = BigDecimal.ZERO;
        if (isSell) {
            stampDuty = tradeValue.multiply(STAMP_DUTY_RATE)
                .setScale(2, RoundingMode.HALF_UP);
        }
        
        // 3. 交易费（四舍五入至最接近分）
        BigDecimal tradingFee = tradeValue.multiply(TRADING_FEE_RATE)
            .setScale(2, RoundingMode.HALF_UP);
        
        // 4. 结算费（有上下限）
        BigDecimal settlementFee = tradeValue.multiply(SETTLEMENT_FEE_RATE);
        if (settlementFee.compareTo(MIN_SETTLEMENT_FEE) < 0) {
            settlementFee = MIN_SETTLEMENT_FEE;
        } else if (settlementFee.compareTo(MAX_SETTLEMENT_FEE) > 0) {
            settlementFee = MAX_SETTLEMENT_FEE;
        }
        
        // 5. 交易系统费
        BigDecimal tradingSystemFee = TRADING_SYSTEM_FEE;
        
        // 总成本
        BigDecimal totalCost = commission
            .add(stampDuty)
            .add(tradingFee)
            .add(settlementFee)
            .add(tradingSystemFee);
        
        CommissionBreakdown breakdown = CommissionBreakdown.builder()
            .tradeValue(tradeValue)
            .commission(commission)
            .stampDuty(stampDuty)
            .tradingFee(tradingFee)
            .settlementFee(settlementFee)
            .tradingSystemFee(tradingSystemFee)
            .totalCost(totalCost)
            .isSell(isSell)
            .build();
        
        log.debug("港股手续费计算: 交易金额={}, 类型={}, 总费用={}", 
            tradeValue, isSell ? "卖出" : "买入", totalCost);
        
        return breakdown;
    }
    
    /**
     * 简化版计算 - 仅返回总费用
     */
    public BigDecimal calculateTotalCost(BigDecimal price, Integer quantity, boolean isSell) {
        return calculateCommission(price, quantity, isSell).getTotalCost();
    }
    
    /**
     * 计算往返交易总费用（买入+卖出）
     */
    public BigDecimal calculateRoundTripCost(BigDecimal buyPrice, BigDecimal sellPrice, Integer quantity) {
        BigDecimal buyCost = calculateTotalCost(buyPrice, quantity, false);
        BigDecimal sellCost = calculateTotalCost(sellPrice, quantity, true);
        return buyCost.add(sellCost);
    }
    
    /**
     * 计算费用占交易金额的比例
     */
    public BigDecimal calculateCostRatio(BigDecimal price, Integer quantity, boolean isSell) {
        CommissionBreakdown breakdown = calculateCommission(price, quantity, isSell);
        if (breakdown.getTradeValue().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return breakdown.getTotalCost()
            .divide(breakdown.getTradeValue(), 6, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100")); // 转换为百分比
    }
    
    /**
     * 获取费率信息（用于配置显示）
     */
    public FeeRateInfo getFeeRateInfo() {
        return FeeRateInfo.builder()
            .commissionRate(COMMISSION_RATE.multiply(new BigDecimal("100")))
            .stampDutyRate(STAMP_DUTY_RATE.multiply(new BigDecimal("100")))
            .tradingFeeRate(TRADING_FEE_RATE.multiply(new BigDecimal("100")))
            .settlementFeeRate(SETTLEMENT_FEE_RATE.multiply(new BigDecimal("100")))
            .minCommission(MIN_COMMISSION)
            .minSettlementFee(MIN_SETTLEMENT_FEE)
            .maxSettlementFee(MAX_SETTLEMENT_FEE)
            .tradingSystemFee(TRADING_SYSTEM_FEE)
            .build();
    }
    
    /**
     * 费用明细分解
     */
    @lombok.Builder
    @lombok.Data
    public static class CommissionBreakdown {
        
        /** 交易金额 */
        private BigDecimal tradeValue;
        
        /** 佣金 */
        private BigDecimal commission;
        
        /** 印花税（仅卖出时收取） */
        private BigDecimal stampDuty;
        
        /** 交易费 */
        private BigDecimal tradingFee;
        
        /** 结算费 */
        private BigDecimal settlementFee;
        
        /** 交易系统费 */
        private BigDecimal tradingSystemFee;
        
        /** 总费用 */
        private BigDecimal totalCost;
        
        /** 是否为卖出交易 */
        private boolean isSell;
        
        /**
         * 获取费用占交易金额的比例
         */
        public BigDecimal getCostRatio() {
            if (tradeValue.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            return totalCost.divide(tradeValue, 6, RoundingMode.HALF_UP);
        }
        
        /**
         * 获取中文费用说明
         */
        public String getChineseBreakdown() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("交易金额: ¥%.2f\n", tradeValue));
            sb.append(String.format("佣金: ¥%.2f\n", commission));
            if (stampDuty.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(String.format("印花税: ¥%.2f\n", stampDuty));
            }
            sb.append(String.format("交易费: ¥%.2f\n", tradingFee));
            sb.append(String.format("结算费: ¥%.2f\n", settlementFee));
            sb.append(String.format("交易系统费: ¥%.2f\n", tradingSystemFee));
            sb.append(String.format("总费用: ¥%.2f (%.4f%%)", totalCost, getCostRatio().multiply(new BigDecimal("100"))));
            return sb.toString();
        }
    }
    
    /**
     * 费率信息
     */
    @lombok.Builder
    @lombok.Data
    public static class FeeRateInfo {
        private BigDecimal commissionRate;    // 佣金费率(%)
        private BigDecimal stampDutyRate;     // 印花税率(%)
        private BigDecimal tradingFeeRate;    // 交易费率(%)
        private BigDecimal settlementFeeRate; // 结算费率(%)
        private BigDecimal minCommission;     // 最低佣金
        private BigDecimal minSettlementFee;  // 最低结算费
        private BigDecimal maxSettlementFee;  // 最高结算费
        private BigDecimal tradingSystemFee;  // 交易系统费
    }
}