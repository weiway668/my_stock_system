package com.trading.service;

import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.infrastructure.cache.CacheService;
import com.trading.repository.OrderRepository;
import com.trading.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 风险管理服务
 * 实现仓位管理、风险控制、止损止盈等功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskManagementService {

    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final MarketDataService marketDataService;
    private final CacheService cacheService;
    
    // 风险参数配置
    @Value("${risk.max-position-size:0.1}")
    private BigDecimal maxPositionSize; // 单个仓位最大占比
    
    @Value("${risk.max-daily-loss:0.02}")
    private BigDecimal maxDailyLossRatio; // 每日最大亏损比例
    
    @Value("${risk.max-leverage:2.0}")
    private BigDecimal maxLeverage; // 最大杠杆倍数
    
    @Value("${risk.stop-loss-percentage:0.05}")
    private BigDecimal defaultStopLossPercentage; // 默认止损百分比
    
    @Value("${risk.take-profit-percentage:0.1}")
    private BigDecimal defaultTakeProfitPercentage; // 默认止盈百分比
    
    @Value("${risk.max-concentration:0.3}")
    private BigDecimal maxConcentration; // 单个标的最大集中度
    
    @Value("${risk.margin-call-ratio:0.25}")
    private BigDecimal marginCallRatio; // 追加保证金阈值
    
    // 实时风险监控缓存
    private final Map<String, RiskMetrics> riskMetricsCache = new ConcurrentHashMap<>();
    
    /**
     * 风险评估 - 订单提交前检查
     */
    @Transactional(readOnly = true)
    public RiskAssessment assessOrderRisk(Order order) {
        log.info("评估订单风险: orderId={}, symbol={}, quantity={}", 
            order.getOrderId(), order.getSymbol(), order.getQuantity());
        
        RiskAssessment assessment = new RiskAssessment();
        assessment.setOrderId(order.getOrderId());
        assessment.setSymbol(order.getSymbol());
        assessment.setTimestamp(LocalDateTime.now());
        
        // 1. 检查仓位限制
        boolean positionCheck = checkPositionLimit(order);
        assessment.setPositionLimitCheck(positionCheck);
        
        // 2. 检查每日亏损限制
        boolean dailyLossCheck = checkDailyLossLimit(order.getAccountId());
        assessment.setDailyLossLimitCheck(dailyLossCheck);
        
        // 3. 检查杠杆限制
        boolean leverageCheck = checkLeverageLimit(order);
        assessment.setLeverageCheck(leverageCheck);
        
        // 4. 检查集中度风险
        boolean concentrationCheck = checkConcentrationRisk(order);
        assessment.setConcentrationCheck(concentrationCheck);
        
        // 5. 检查流动性风险
        boolean liquidityCheck = checkLiquidityRisk(order);
        assessment.setLiquidityCheck(liquidityCheck);
        
        // 6. 计算风险评分
        BigDecimal riskScore = calculateRiskScore(assessment);
        assessment.setRiskScore(riskScore);
        
        // 7. 确定是否批准
        boolean approved = positionCheck && dailyLossCheck && leverageCheck 
            && concentrationCheck && liquidityCheck;
        assessment.setApproved(approved);
        
        if (!approved) {
            assessment.setRejectReason(buildRejectReason(assessment));
        }
        
        log.info("风险评估完成: orderId={}, approved={}, riskScore={}", 
            order.getOrderId(), approved, riskScore);
        
        return assessment;
    }
    
    /**
     * 检查仓位限制
     */
    private boolean checkPositionLimit(Order order) {
        try {
            // 获取账户总资产
            BigDecimal totalAssets = getTotalAssets(order.getAccountId());
            if (totalAssets.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("账户总资产为0: accountId={}", order.getAccountId());
                return false;
            }
            
            // 计算订单价值
            BigDecimal orderValue = order.getPrice()
                .multiply(BigDecimal.valueOf(order.getQuantity()));
            
            // 计算仓位占比
            BigDecimal positionRatio = orderValue.divide(totalAssets, 4, RoundingMode.HALF_UP);
            
            // 检查是否超过最大仓位限制
            boolean withinLimit = positionRatio.compareTo(maxPositionSize) <= 0;
            
            if (!withinLimit) {
                log.warn("仓位超限: symbol={}, positionRatio={}, maxAllowed={}", 
                    order.getSymbol(), positionRatio, maxPositionSize);
            }
            
            return withinLimit;
            
        } catch (Exception e) {
            log.error("检查仓位限制失败: orderId={}", order.getOrderId(), e);
            return false;
        }
    }
    
    /**
     * 检查每日亏损限制
     */
    private boolean checkDailyLossLimit(String accountId) {
        try {
            // 获取今日已实现亏损
            BigDecimal dailyLoss = calculateDailyLoss(accountId);
            
            // 获取账户总资产
            BigDecimal totalAssets = getTotalAssets(accountId);
            
            if (totalAssets.compareTo(BigDecimal.ZERO) <= 0) {
                return false;
            }
            
            // 计算亏损比例
            BigDecimal lossRatio = dailyLoss.abs()
                .divide(totalAssets, 4, RoundingMode.HALF_UP);
            
            // 检查是否超过每日最大亏损限制
            boolean withinLimit = lossRatio.compareTo(maxDailyLossRatio) <= 0;
            
            if (!withinLimit) {
                log.warn("每日亏损超限: accountId={}, lossRatio={}, maxAllowed={}", 
                    accountId, lossRatio, maxDailyLossRatio);
            }
            
            return withinLimit;
            
        } catch (Exception e) {
            log.error("检查每日亏损限制失败: accountId={}", accountId, e);
            return false;
        }
    }
    
    /**
     * 检查杠杆限制
     */
    private boolean checkLeverageLimit(Order order) {
        try {
            // 获取账户净值
            BigDecimal equity = getAccountEquity(order.getAccountId());
            
            // 获取持仓总价值
            BigDecimal positionValue = getTotalPositionValue(order.getAccountId());
            
            // 加上新订单价值
            BigDecimal newOrderValue = order.getPrice()
                .multiply(BigDecimal.valueOf(order.getQuantity()));
            BigDecimal totalValue = positionValue.add(newOrderValue);
            
            // 计算杠杆
            BigDecimal leverage = equity.compareTo(BigDecimal.ZERO) > 0 
                ? totalValue.divide(equity, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            
            // 检查是否超过最大杠杆限制
            boolean withinLimit = leverage.compareTo(maxLeverage) <= 0;
            
            if (!withinLimit) {
                log.warn("杠杆超限: accountId={}, leverage={}, maxAllowed={}", 
                    order.getAccountId(), leverage, maxLeverage);
            }
            
            return withinLimit;
            
        } catch (Exception e) {
            log.error("检查杠杆限制失败: orderId={}", order.getOrderId(), e);
            return false;
        }
    }
    
    /**
     * 检查集中度风险
     */
    private boolean checkConcentrationRisk(Order order) {
        try {
            // 获取账户总资产
            BigDecimal totalAssets = getTotalAssets(order.getAccountId());
            
            // 获取该标的现有持仓价值
            BigDecimal existingPosition = getSymbolPositionValue(
                order.getAccountId(), order.getSymbol());
            
            // 计算新订单价值
            BigDecimal newOrderValue = order.getPrice()
                .multiply(BigDecimal.valueOf(order.getQuantity()));
            
            // 计算该标的总价值
            BigDecimal totalSymbolValue = existingPosition.add(newOrderValue);
            
            // 计算集中度
            BigDecimal concentration = totalAssets.compareTo(BigDecimal.ZERO) > 0
                ? totalSymbolValue.divide(totalAssets, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            
            // 检查是否超过最大集中度限制
            boolean withinLimit = concentration.compareTo(maxConcentration) <= 0;
            
            if (!withinLimit) {
                log.warn("集中度超限: symbol={}, concentration={}, maxAllowed={}", 
                    order.getSymbol(), concentration, maxConcentration);
            }
            
            return withinLimit;
            
        } catch (Exception e) {
            log.error("检查集中度风险失败: orderId={}", order.getOrderId(), e);
            return false;
        }
    }
    
    /**
     * 检查流动性风险
     */
    private boolean checkLiquidityRisk(Order order) {
        try {
            // 获取该标的平均成交量
            BigDecimal avgVolume = getAverageVolume(order.getSymbol());
            
            if (avgVolume.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("无法获取平均成交量: symbol={}", order.getSymbol());
                return true; // 默认通过
            }
            
            // 计算订单占平均成交量的比例
            BigDecimal volumeRatio = BigDecimal.valueOf(order.getQuantity())
                .divide(avgVolume, 4, RoundingMode.HALF_UP);
            
            // 如果订单量超过平均成交量的10%，认为存在流动性风险
            boolean hasLiquidityRisk = volumeRatio.compareTo(BigDecimal.valueOf(0.1)) > 0;
            
            if (hasLiquidityRisk) {
                log.warn("流动性风险: symbol={}, orderQty={}, avgVolume={}, ratio={}", 
                    order.getSymbol(), order.getQuantity(), avgVolume, volumeRatio);
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("检查流动性风险失败: orderId={}", order.getOrderId(), e);
            return true; // 默认通过
        }
    }
    
    /**
     * 计算风险评分
     */
    private BigDecimal calculateRiskScore(RiskAssessment assessment) {
        BigDecimal score = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.valueOf(20); // 每项权重
        
        if (assessment.isPositionLimitCheck()) {
            score = score.add(weight);
        }
        if (assessment.isDailyLossLimitCheck()) {
            score = score.add(weight);
        }
        if (assessment.isLeverageCheck()) {
            score = score.add(weight);
        }
        if (assessment.isConcentrationCheck()) {
            score = score.add(weight);
        }
        if (assessment.isLiquidityCheck()) {
            score = score.add(weight);
        }
        
        return score;
    }
    
    /**
     * 构建拒绝原因
     */
    private String buildRejectReason(RiskAssessment assessment) {
        StringBuilder reason = new StringBuilder();
        
        if (!assessment.isPositionLimitCheck()) {
            reason.append("仓位超限; ");
        }
        if (!assessment.isDailyLossLimitCheck()) {
            reason.append("每日亏损超限; ");
        }
        if (!assessment.isLeverageCheck()) {
            reason.append("杠杆超限; ");
        }
        if (!assessment.isConcentrationCheck()) {
            reason.append("集中度超限; ");
        }
        if (!assessment.isLiquidityCheck()) {
            reason.append("流动性风险; ");
        }
        
        return reason.toString();
    }
    
    /**
     * 设置止损止盈
     */
    @Transactional
    public void setStopLossAndTakeProfit(Order order) {
        if (order.getPrice() == null || order.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("订单价格无效，无法设置止损止盈: orderId={}", order.getOrderId());
            return;
        }
        
        // 设置止损价格
        if (order.getStopLoss() == null) {
            BigDecimal stopLossPrice = calculateStopLossPrice(
                order.getPrice(), order.getSide(), defaultStopLossPercentage);
            order.setStopLoss(stopLossPrice);
            log.info("设置止损价格: orderId={}, stopLoss={}", 
                order.getOrderId(), stopLossPrice);
        }
        
        // 设置止盈价格
        if (order.getTakeProfit() == null) {
            BigDecimal takeProfitPrice = calculateTakeProfitPrice(
                order.getPrice(), order.getSide(), defaultTakeProfitPercentage);
            order.setTakeProfit(takeProfitPrice);
            log.info("设置止盈价格: orderId={}, takeProfit={}", 
                order.getOrderId(), takeProfitPrice);
        }
        
        orderRepository.save(order);
    }
    
    /**
     * 计算止损价格
     */
    private BigDecimal calculateStopLossPrice(BigDecimal entryPrice, 
                                             OrderSide side, 
                                             BigDecimal stopLossPercentage) {
        if (side == OrderSide.BUY) {
            // 买入时，止损价格 = 入场价格 * (1 - 止损百分比)
            return entryPrice.multiply(BigDecimal.ONE.subtract(stopLossPercentage));
        } else {
            // 卖出时，止损价格 = 入场价格 * (1 + 止损百分比)
            return entryPrice.multiply(BigDecimal.ONE.add(stopLossPercentage));
        }
    }
    
    /**
     * 计算止盈价格
     */
    private BigDecimal calculateTakeProfitPrice(BigDecimal entryPrice, 
                                               OrderSide side, 
                                               BigDecimal takeProfitPercentage) {
        if (side == OrderSide.BUY) {
            // 买入时，止盈价格 = 入场价格 * (1 + 止盈百分比)
            return entryPrice.multiply(BigDecimal.ONE.add(takeProfitPercentage));
        } else {
            // 卖出时，止盈价格 = 入场价格 * (1 - 止盈百分比)
            return entryPrice.multiply(BigDecimal.ONE.subtract(takeProfitPercentage));
        }
    }
    
    /**
     * 监控止损止盈触发
     */
    public void monitorStopLossAndTakeProfit() {
        try {
            // 获取所有活跃订单
            List<Order> activeOrders = orderRepository.findByStatus(OrderStatus.FILLED);
            
            for (Order order : activeOrders) {
                checkStopLossTrigger(order);
                checkTakeProfitTrigger(order);
            }
            
        } catch (Exception e) {
            log.error("监控止损止盈失败", e);
        }
    }
    
    /**
     * 检查止损触发
     */
    private void checkStopLossTrigger(Order order) {
        if (order.getStopLoss() == null) {
            return;
        }
        
        try {
            // 获取当前价格
            BigDecimal currentPrice = marketDataService.getCurrentPrice(order.getSymbol())
                .get().getPrice();
            
            boolean shouldTrigger = false;
            
            if (order.getSide() == OrderSide.BUY) {
                // 买入订单，当前价格低于止损价格时触发
                shouldTrigger = currentPrice.compareTo(order.getStopLoss()) <= 0;
            } else {
                // 卖出订单，当前价格高于止损价格时触发
                shouldTrigger = currentPrice.compareTo(order.getStopLoss()) >= 0;
            }
            
            if (shouldTrigger) {
                log.warn("止损触发: orderId={}, currentPrice={}, stopLoss={}", 
                    order.getOrderId(), currentPrice, order.getStopLoss());
                // 触发止损逻辑（创建平仓订单等）
                triggerStopLoss(order);
            }
            
        } catch (Exception e) {
            log.error("检查止损触发失败: orderId={}", order.getOrderId(), e);
        }
    }
    
    /**
     * 检查止盈触发
     */
    private void checkTakeProfitTrigger(Order order) {
        if (order.getTakeProfit() == null) {
            return;
        }
        
        try {
            // 获取当前价格
            BigDecimal currentPrice = marketDataService.getCurrentPrice(order.getSymbol())
                .get().getPrice();
            
            boolean shouldTrigger = false;
            
            if (order.getSide() == OrderSide.BUY) {
                // 买入订单，当前价格高于止盈价格时触发
                shouldTrigger = currentPrice.compareTo(order.getTakeProfit()) >= 0;
            } else {
                // 卖出订单，当前价格低于止盈价格时触发
                shouldTrigger = currentPrice.compareTo(order.getTakeProfit()) <= 0;
            }
            
            if (shouldTrigger) {
                log.info("止盈触发: orderId={}, currentPrice={}, takeProfit={}", 
                    order.getOrderId(), currentPrice, order.getTakeProfit());
                // 触发止盈逻辑（创建平仓订单等）
                triggerTakeProfit(order);
            }
            
        } catch (Exception e) {
            log.error("检查止盈触发失败: orderId={}", order.getOrderId(), e);
        }
    }
    
    /**
     * 触发止损
     */
    private void triggerStopLoss(Order order) {
        // TODO: 创建平仓订单
        log.info("执行止损平仓: orderId={}", order.getOrderId());
    }
    
    /**
     * 触发止盈
     */
    private void triggerTakeProfit(Order order) {
        // TODO: 创建平仓订单
        log.info("执行止盈平仓: orderId={}", order.getOrderId());
    }
    
    /**
     * 计算账户净值
     */
    private BigDecimal getAccountEquity(String accountId) {
        // TODO: 实际实现需要从账户服务获取
        return BigDecimal.valueOf(1000000); // 模拟值
    }
    
    /**
     * 获取账户总资产
     */
    private BigDecimal getTotalAssets(String accountId) {
        // TODO: 实际实现需要从账户服务获取
        return BigDecimal.valueOf(1000000); // 模拟值
    }
    
    /**
     * 获取持仓总价值
     */
    private BigDecimal getTotalPositionValue(String accountId) {
        List<Position> positions = positionRepository.findByAccountId(accountId);
        return positions.stream()
            .map(p -> p.getCurrentPrice().multiply(BigDecimal.valueOf(p.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * 获取特定标的持仓价值
     */
    private BigDecimal getSymbolPositionValue(String accountId, String symbol) {
        return positionRepository.findByAccountIdAndSymbol(accountId, symbol)
            .map(p -> p.getCurrentPrice().multiply(BigDecimal.valueOf(p.getQuantity())))
            .orElse(BigDecimal.ZERO);
    }
    
    /**
     * 计算每日亏损
     */
    private BigDecimal calculateDailyLoss(String accountId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        List<Order> todayOrders = orderRepository.findByAccountIdAndCreateTimeAfter(
            accountId, todayStart);
        
        return todayOrders.stream()
            .filter(o -> o.getRealizedPnl() != null && o.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0)
            .map(Order::getRealizedPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * 获取平均成交量
     */
    private BigDecimal getAverageVolume(String symbol) {
        // TODO: 从市场数据服务获取
        return BigDecimal.valueOf(1000000); // 模拟值
    }
    
    /**
     * 风险评估结果
     */
    public static class RiskAssessment {
        private String orderId;
        private String symbol;
        private LocalDateTime timestamp;
        private boolean positionLimitCheck;
        private boolean dailyLossLimitCheck;
        private boolean leverageCheck;
        private boolean concentrationCheck;
        private boolean liquidityCheck;
        private BigDecimal riskScore;
        private boolean approved;
        private String rejectReason;
        
        // Getters and setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
        
        public boolean isPositionLimitCheck() { return positionLimitCheck; }
        public void setPositionLimitCheck(boolean check) { this.positionLimitCheck = check; }
        
        public boolean isDailyLossLimitCheck() { return dailyLossLimitCheck; }
        public void setDailyLossLimitCheck(boolean check) { this.dailyLossLimitCheck = check; }
        
        public boolean isLeverageCheck() { return leverageCheck; }
        public void setLeverageCheck(boolean check) { this.leverageCheck = check; }
        
        public boolean isConcentrationCheck() { return concentrationCheck; }
        public void setConcentrationCheck(boolean check) { this.concentrationCheck = check; }
        
        public boolean isLiquidityCheck() { return liquidityCheck; }
        public void setLiquidityCheck(boolean check) { this.liquidityCheck = check; }
        
        public BigDecimal getRiskScore() { return riskScore; }
        public void setRiskScore(BigDecimal score) { this.riskScore = score; }
        
        public boolean isApproved() { return approved; }
        public void setApproved(boolean approved) { this.approved = approved; }
        
        public String getRejectReason() { return rejectReason; }
        public void setRejectReason(String reason) { this.rejectReason = reason; }
    }
    
    /**
     * 风险指标
     */
    public static class RiskMetrics {
        private String accountId;
        private BigDecimal totalExposure;
        private BigDecimal dailyPnl;
        private BigDecimal leverage;
        private BigDecimal marginUsage;
        private BigDecimal concentration;
        private LocalDateTime lastUpdate;
        
        // Getters and setters
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        
        public BigDecimal getTotalExposure() { return totalExposure; }
        public void setTotalExposure(BigDecimal exposure) { this.totalExposure = exposure; }
        
        public BigDecimal getDailyPnl() { return dailyPnl; }
        public void setDailyPnl(BigDecimal pnl) { this.dailyPnl = pnl; }
        
        public BigDecimal getLeverage() { return leverage; }
        public void setLeverage(BigDecimal leverage) { this.leverage = leverage; }
        
        public BigDecimal getMarginUsage() { return marginUsage; }
        public void setMarginUsage(BigDecimal usage) { this.marginUsage = usage; }
        
        public BigDecimal getConcentration() { return concentration; }
        public void setConcentration(BigDecimal concentration) { this.concentration = concentration; }
        
        public LocalDateTime getLastUpdate() { return lastUpdate; }
        public void setLastUpdate(LocalDateTime update) { this.lastUpdate = update; }
    }
}