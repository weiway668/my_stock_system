package com.trading.backtest;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.trading.common.utils.BigDecimalUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 港股手续费计算器（根据用户提供的详细规则重构）
 */
@Slf4j
@Component
public class HKStockCommissionCalculator {

    // 费率和固定费用常量
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.0003");      // 佣金: 0.03%
    private static final BigDecimal MIN_COMMISSION = new BigDecimal("3.00");         // 最低佣金: 3 HKD
    private static final BigDecimal PLATFORM_FEE = new BigDecimal("15.00");        // 平台使用费: 15 HKD
    private static final BigDecimal SETTLEMENT_FEE_RATE = new BigDecimal("0.000042"); // 交收费: 0.0042%
    private static final BigDecimal STAMP_DUTY_RATE = new BigDecimal("0.001");       // 印花税: 0.1%
    private static final BigDecimal TRADING_FEE_RATE = new BigDecimal("0.0000565");  // 交易费: 0.00565%
    private static final BigDecimal MIN_TRADING_FEE = new BigDecimal("0.01");      // 最低交易费: 0.01 HKD
    private static final BigDecimal SFC_LEVY_RATE = new BigDecimal("0.000027");     // 证监会征费: 0.0027%
    private static final BigDecimal MIN_SFC_LEVY = new BigDecimal("0.01");         // 最低证监会征费: 0.01 HKD
    private static final BigDecimal FRC_LEVY_RATE = new BigDecimal("0.0000015");   // 财汇局征费: 0.00015%

    // 已知的ETF列表，用于豁免印花税
    private static final Set<String> ETF_SYMBOLS = Set.of("02800.HK", "03033.HK", "07500.HK");

    /**
     * 计算交易成本详细分解
     *
     * @param price    成交价格
     * @param quantity 交易数量
     * @param symbol   股票代码 (用于判断是否为ETF)
     * @param isSell   是否为卖出交易
     * @return 费用明细
     */
    public CommissionBreakdown calculateCommission(BigDecimal price, long quantity, String symbol, boolean isSell) {
        if (price == null || quantity <= 0 || symbol == null || symbol.isBlank()) {
            return CommissionBreakdown.builder().build(); // 返回空对象
        }

        BigDecimal tradeValue = price.multiply(BigDecimal.valueOf(quantity));

        // 1. 佣金
        BigDecimal commission = BigDecimalUtils.scale(tradeValue.multiply(COMMISSION_RATE));
        if (commission.compareTo(MIN_COMMISSION) < 0) {
            commission = MIN_COMMISSION;
        }

        // 2. 平台使用费
        BigDecimal platformFee = PLATFORM_FEE;

        // 3. 交收费
        BigDecimal settlementFee = BigDecimalUtils.scale(tradeValue.multiply(SETTLEMENT_FEE_RATE));

        // 4. 印花税 (特殊规则，不使用工具类)
        BigDecimal stampDuty = BigDecimal.ZERO;
        if (!isETF(symbol)) { 
            stampDuty = tradeValue.multiply(STAMP_DUTY_RATE);
            stampDuty = stampDuty.setScale(0, RoundingMode.CEILING);
        }

        // 5. 交易费
        BigDecimal tradingFee = BigDecimalUtils.scale(tradeValue.multiply(TRADING_FEE_RATE));
        if (tradingFee.compareTo(MIN_TRADING_FEE) < 0) {
            tradingFee = MIN_TRADING_FEE;
        }

        // 6. 证监会征费
        BigDecimal sfcLevy = BigDecimalUtils.scale(tradeValue.multiply(SFC_LEVY_RATE));
        if (sfcLevy.compareTo(MIN_SFC_LEVY) < 0) {
            sfcLevy = MIN_SFC_LEVY;
        }

        // 7. 财汇局征费
        BigDecimal frcFee = BigDecimalUtils.scale(tradeValue.multiply(FRC_LEVY_RATE));

        // 总成本
        BigDecimal totalCost = commission.add(platformFee).add(settlementFee).add(stampDuty).add(tradingFee).add(sfcLevy).add(frcFee);

        return CommissionBreakdown.builder()
                .tradeValue(tradeValue)
                .commission(commission)
                .platformFee(platformFee)
                .settlementFee(settlementFee)
                .stampDuty(stampDuty)
                .tradingFee(tradingFee)
                .sfcLevy(sfcLevy)
                .frcFee(frcFee)
                .totalCost(totalCost)
                .isSell(isSell)
                .build();
    }

    private boolean isETF(String symbol) {
        return ETF_SYMBOLS.contains(symbol.toUpperCase());
    }

    /**
     * 费用明细分解
     */
    @Builder
    @Data
    public static class CommissionBreakdown {
        private BigDecimal tradeValue;      // 交易金额
        private BigDecimal commission;      // 佣金
        private BigDecimal platformFee;     // 平台使用费
        private BigDecimal settlementFee;   // 交收费
        private BigDecimal stampDuty;       // 印花税
        private BigDecimal tradingFee;      // 交易费
        private BigDecimal sfcLevy;         // 证监会征费
        private BigDecimal frcFee;          // 财汇局征费
        private BigDecimal totalCost;       // 总费用
        private boolean isSell;             // 是否为卖出交易
    }
}
