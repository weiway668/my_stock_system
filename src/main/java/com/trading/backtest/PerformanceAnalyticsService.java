package com.trading.backtest;

import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 性能分析服务
 * <p>
 * 接收每日权益序列和交易记录作为输入，计算核心性能指标，
 * 以便量化评估交易策略的风险和收益特征。
 * </p>
 */
@Service
public class PerformanceAnalyticsService {

    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final int SCALE = 8; // 统一小数精度
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    /**
     * 性能指标结果的封装类
     *
     * @param annualizedReturn      年化收益率
     * @param cumulativeReturn      累计收益率
     * @param sharpeRatio           夏普比率
     * @param sortinoRatio          索提诺比率
     * @param maxDrawdown           最大回撤
     * @param calmarRatio           Calmar比率
     * @param winRate               胜率
     * @param profitLossRatio       盈亏比
     * @param totalTrades           总交易次数
     * @param winningTrades         盈利交易次数
     * @param losingTrades          亏损交易次数
     * @param averageProfit         平均盈利
     * @param averageLoss           平均亏损
     */
    public record PerformanceMetrics(
            BigDecimal annualizedReturn,
            BigDecimal cumulativeReturn,
            BigDecimal sharpeRatio,
            BigDecimal sortinoRatio,
            BigDecimal maxDrawdown,
            BigDecimal calmarRatio,
            BigDecimal winRate,
            BigDecimal profitLossRatio,
            int totalTrades,
            int winningTrades,
            int losingTrades,
            BigDecimal averageProfit,
            BigDecimal averageLoss
    ) {
    }

    /**
     * 计算并返回所有性能指标
     *
     * @param tradeHistory 交易历史记录
     * @param dailyEquity  每日权益快照
     * @param riskFreeRate 无风险利率 (例如: 0.02 表示 2%)
     * @return PerformanceMetrics 包含所有计算出的指标
     */
    public PerformanceMetrics calculatePerformance(
            List<Order> tradeHistory,
            List<PortfolioManager.EquitySnapshot> dailyEquity,
            double riskFreeRate
    ) {
        if (dailyEquity == null || dailyEquity.size() < 2) {
            return createEmptyMetrics(); // 数据不足，无法计算
        }

        List<BigDecimal> equityCurve = dailyEquity.stream().map(PortfolioManager.EquitySnapshot::totalValue).collect(Collectors.toList());
        List<BigDecimal> dailyReturns = calculateDailyReturns(equityCurve);

        BigDecimal cumulativeReturn = calculateCumulativeReturn(equityCurve);
        BigDecimal annualizedReturn = calculateAnnualizedReturn(cumulativeReturn, dailyEquity.size());

        BigDecimal annualizedVolatility = calculateAnnualizedVolatility(dailyReturns);
        BigDecimal downsideVolatility = calculateDownsideVolatility(dailyReturns, riskFreeRate);

        BigDecimal sharpeRatio = calculateSharpeRatio(annualizedReturn, BigDecimal.valueOf(riskFreeRate), annualizedVolatility);
        BigDecimal sortinoRatio = calculateSortinoRatio(annualizedReturn, BigDecimal.valueOf(riskFreeRate), downsideVolatility);

        BigDecimal maxDrawdown = calculateMaxDrawdown(equityCurve);
        BigDecimal calmarRatio = calculateCalmarRatio(annualizedReturn, maxDrawdown);

        // 交易分析
        List<Order> completedTrades = tradeHistory.stream()
                .filter(o -> o.getSide() == OrderSide.SELL && o.getRealizedPnl() != null)
                .collect(Collectors.toList());

        int totalTrades = completedTrades.size();
        List<BigDecimal> profits = completedTrades.stream()
                .map(Order::getRealizedPnl)
                .filter(pnl -> pnl.compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
        List<BigDecimal> losses = completedTrades.stream()
                .map(Order::getRealizedPnl)
                .filter(pnl -> pnl.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .collect(Collectors.toList());

        int winningTrades = profits.size();
        int losingTrades = losses.size();

        BigDecimal winRate = (totalTrades == 0) ? BigDecimal.ZERO :
                BigDecimal.valueOf(winningTrades).divide(BigDecimal.valueOf(totalTrades), SCALE, ROUNDING_MODE);

        BigDecimal totalProfit = profits.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLoss = losses.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal averageProfit = (winningTrades == 0) ? BigDecimal.ZERO :
                totalProfit.divide(BigDecimal.valueOf(winningTrades), SCALE, ROUNDING_MODE);
        BigDecimal averageLoss = (losingTrades == 0) ? BigDecimal.ZERO :
                totalLoss.divide(BigDecimal.valueOf(losingTrades), SCALE, ROUNDING_MODE);

        BigDecimal profitLossRatio = (averageLoss.compareTo(BigDecimal.ZERO) == 0) ? BigDecimal.ZERO :
                averageProfit.divide(averageLoss, SCALE, ROUNDING_MODE);

        return new PerformanceMetrics(
                annualizedReturn, cumulativeReturn, sharpeRatio, sortinoRatio,
                maxDrawdown, calmarRatio, winRate, profitLossRatio,
                totalTrades, winningTrades, losingTrades, averageProfit, averageLoss
        );
    }

    private List<BigDecimal> calculateDailyReturns(List<BigDecimal> equityCurve) {
        List<BigDecimal> dailyReturns = new java.util.ArrayList<>();
        for (int i = 1; i < equityCurve.size(); i++) {
            BigDecimal previousEquity = equityCurve.get(i - 1);
            BigDecimal currentEquity = equityCurve.get(i);
            if (previousEquity.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal dailyReturn = currentEquity.divide(previousEquity, SCALE, ROUNDING_MODE).subtract(BigDecimal.ONE);
                dailyReturns.add(dailyReturn);
            }
        }
        return dailyReturns;
    }

    private BigDecimal calculateCumulativeReturn(List<BigDecimal> equityCurve) {
        BigDecimal initialEquity = equityCurve.get(0);
        BigDecimal finalEquity = equityCurve.get(equityCurve.size() - 1);
        if (initialEquity.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return finalEquity.divide(initialEquity, SCALE, ROUNDING_MODE).subtract(BigDecimal.ONE);
    }

    private BigDecimal calculateAnnualizedReturn(BigDecimal cumulativeReturn, int days) {
        if (days == 0) return BigDecimal.ZERO;
        double years = (double) days / TRADING_DAYS_PER_YEAR;
        if (years == 0) return BigDecimal.ZERO;
        // (1 + cumulativeReturn)^(1/years) - 1
        BigDecimal result = BigDecimal.valueOf(Math.pow(BigDecimal.ONE.add(cumulativeReturn).doubleValue(), 1.0 / years)).subtract(BigDecimal.ONE);
        return result.setScale(SCALE, ROUNDING_MODE);
    }

    private BigDecimal calculateAnnualizedVolatility(List<BigDecimal> dailyReturns) {
        if (dailyReturns.isEmpty()) return BigDecimal.ZERO;
        BigDecimal meanReturn = dailyReturns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), SCALE, ROUNDING_MODE);

        BigDecimal variance = dailyReturns.stream()
                .map(r -> r.subtract(meanReturn).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), SCALE, ROUNDING_MODE);

        BigDecimal dailyVolatility = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        return dailyVolatility.multiply(BigDecimal.valueOf(Math.sqrt(TRADING_DAYS_PER_YEAR))).setScale(SCALE, ROUNDING_MODE);
    }
    
    private BigDecimal calculateDownsideVolatility(List<BigDecimal> dailyReturns, double riskFreeRate) {
        BigDecimal dailyRiskFreeRate = BigDecimal.valueOf(riskFreeRate / TRADING_DAYS_PER_YEAR);
        
        double sumOfSquares = dailyReturns.stream()
            .filter(r -> r.compareTo(dailyRiskFreeRate) < 0)
            .mapToDouble(r -> r.subtract(dailyRiskFreeRate).pow(2).doubleValue())
            .sum();

        if (dailyReturns.isEmpty()) return BigDecimal.ZERO;
        
        double downsideVariance = sumOfSquares / dailyReturns.size();
        double dailyDownsideDeviation = Math.sqrt(downsideVariance);
        
        return BigDecimal.valueOf(dailyDownsideDeviation * Math.sqrt(TRADING_DAYS_PER_YEAR)).setScale(SCALE, ROUNDING_MODE);
    }

    private BigDecimal calculateSharpeRatio(BigDecimal annualizedReturn, BigDecimal riskFreeRate, BigDecimal annualizedVolatility) {
        if (annualizedVolatility.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal excessReturn = annualizedReturn.subtract(riskFreeRate);
        return excessReturn.divide(annualizedVolatility, SCALE, ROUNDING_MODE);
    }
    
    private BigDecimal calculateSortinoRatio(BigDecimal annualizedReturn, BigDecimal riskFreeRate, BigDecimal downsideVolatility) {
        if (downsideVolatility.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal excessReturn = annualizedReturn.subtract(riskFreeRate);
        return excessReturn.divide(downsideVolatility, SCALE, ROUNDING_MODE);
    }

    private BigDecimal calculateMaxDrawdown(List<BigDecimal> equityCurve) {
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal peak = equityCurve.get(0);
        for (BigDecimal equity : equityCurve) {
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            BigDecimal drawdown = peak.subtract(equity).divide(peak, SCALE, ROUNDING_MODE);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }
        return maxDrawdown;
    }

    private BigDecimal calculateCalmarRatio(BigDecimal annualizedReturn, BigDecimal maxDrawdown) {
        if (maxDrawdown.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return annualizedReturn.divide(maxDrawdown, SCALE, ROUNDING_MODE);
    }

    private PerformanceMetrics createEmptyMetrics() {
        return new PerformanceMetrics(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO
        );
    }
}
