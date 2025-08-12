package com.trading.service;

import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;
import com.trading.infrastructure.cache.CacheService;
import com.trading.repository.OrderRepository;
import com.trading.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单执行服务
 * 负责订单的智能执行、风险控制、持仓管理和执行算法
 * 提供多种执行策略：TWAP、VWAP、市价、限价等
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final TradingService tradingService;
    private final MarketDataService marketDataService;
    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final CacheService cacheService;
    private final ApplicationEventPublisher eventPublisher;

    // 执行器和状态管理
    private final ScheduledExecutorService executionScheduler = Executors.newScheduledThreadPool(6);
    private final ExecutorService executionWorker = Executors.newCachedThreadPool();
    private final AtomicBoolean executionEnabled = new AtomicBoolean(false);
    private final AtomicLong executionCounter = new AtomicLong(0);

    // 执行策略缓存
    private final ConcurrentHashMap<String, ExecutionStrategy> activeStrategies = new ConcurrentHashMap<>();
    
    // 监控任务
    private ScheduledFuture<?> executionMonitorTask;
    private ScheduledFuture<?> riskMonitorTask;

    @PostConstruct
    public void initialize() {
        log.info("初始化订单执行服务...");
        
        // 启动执行监控
        startExecutionMonitoring();
        
        // 启动风险监控
        startRiskMonitoring();
        
        executionEnabled.set(true);
        log.info("订单执行服务初始化完成");
    }

    /**
     * 智能执行订单 - 根据订单类型和市场条件选择最佳执行策略
     */
    @Transactional
    public CompletableFuture<ExecutionResult> executeOrder(String orderId, ExecutionParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("开始执行订单: orderId={}, strategy={}", orderId, params.getStrategy());

                // 获取订单信息
                Optional<Order> orderOpt = orderRepository.findById(orderId);
                if (orderOpt.isEmpty()) {
                    return ExecutionResult.failure("订单不存在: " + orderId);
                }

                Order order = orderOpt.get();
                
                // 验证订单状态
                if (!canExecuteOrder(order)) {
                    return ExecutionResult.failure("订单状态不允许执行: " + order.getStatus());
                }

                // 风险检查
                RiskAssessment riskAssessment = assessOrderRisk(order, params);
                if (!riskAssessment.isApproved()) {
                    return ExecutionResult.failure("风险评估未通过: " + riskAssessment.getReason());
                }

                // 选择执行策略
                ExecutionStrategy strategy = selectExecutionStrategy(order, params);
                
                // 执行订单
                ExecutionResult result = executeOrderWithStrategy(order, strategy, params);
                
                // 更新执行统计
                executionCounter.incrementAndGet();
                
                log.info("订单执行完成: orderId={}, result={}", orderId, result.isSuccess());
                return result;

            } catch (Exception e) {
                log.error("订单执行异常: orderId={}", orderId, e);
                return ExecutionResult.failure("订单执行异常: " + e.getMessage());
            }
        }, executionWorker);
    }

    /**
     * 批量执行订单
     */
    public CompletableFuture<BatchExecutionResult> executeBatchOrders(List<String> orderIds, 
                                                                      ExecutionParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("开始批量执行订单: count={}", orderIds.size());

                int successCount = 0;
                int failureCount = 0;
                StringBuilder errors = new StringBuilder();

                // 并行执行订单
                List<CompletableFuture<ExecutionResult>> futures = orderIds.stream()
                    .map(orderId -> executeOrder(orderId, params))
                    .toList();

                // 等待所有执行完成
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));

                allFutures.get(params.getTimeout(), TimeUnit.SECONDS);

                // 统计结果
                for (CompletableFuture<ExecutionResult> future : futures) {
                    try {
                        ExecutionResult result = future.get();
                        if (result.isSuccess()) {
                            successCount++;
                        } else {
                            failureCount++;
                            errors.append(result.getMessage()).append("; ");
                        }
                    } catch (Exception e) {
                        failureCount++;
                        errors.append("执行异常: ").append(e.getMessage()).append("; ");
                    }
                }

                return BatchExecutionResult.builder()
                    .totalCount(orderIds.size())
                    .successCount(successCount)
                    .failureCount(failureCount)
                    .errors(errors.toString())
                    .success(successCount > 0)
                    .build();

            } catch (Exception e) {
                log.error("批量执行订单异常", e);
                return BatchExecutionResult.builder()
                    .totalCount(orderIds.size())
                    .failureCount(orderIds.size())
                    .errors("批量执行异常: " + e.getMessage())
                    .success(false)
                    .build();
            }
        }, executionWorker);
    }

    /**
     * TWAP执行策略 - 时间加权平均价格
     */
    public CompletableFuture<ExecutionResult> executeTwap(String orderId, TwapParameters twapParams) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("执行TWAP策略: orderId={}, duration={}分钟", orderId, twapParams.getDurationMinutes());

                Optional<Order> orderOpt = orderRepository.findById(orderId);
                if (orderOpt.isEmpty()) {
                    return ExecutionResult.failure("订单不存在");
                }

                Order order = orderOpt.get();
                
                // 计算分片参数
                int totalSlices = twapParams.getDurationMinutes();
                int quantityPerSlice = order.getQuantity() / totalSlices;
                int remainingQuantity = order.getQuantity();

                log.debug("TWAP分片: 总分片={}, 每片数量={}", totalSlices, quantityPerSlice);

                // 分片执行
                int executedQuantity = 0;
                BigDecimal totalValue = BigDecimal.ZERO;

                for (int i = 0; i < totalSlices && remainingQuantity > 0; i++) {
                    int currentSliceQuantity = Math.min(quantityPerSlice, remainingQuantity);
                    
                    // 执行当前分片
                    SliceExecutionResult sliceResult = executeSlice(order, currentSliceQuantity);
                    
                    if (sliceResult.isSuccess()) {
                        executedQuantity += sliceResult.getExecutedQuantity();
                        totalValue = totalValue.add(sliceResult.getExecutedValue());
                        remainingQuantity -= sliceResult.getExecutedQuantity();
                        
                        log.debug("TWAP分片{}执行成功: 数量={}, 价格={}", 
                            i + 1, sliceResult.getExecutedQuantity(), sliceResult.getExecutedPrice());
                    } else {
                        log.warn("TWAP分片{}执行失败: {}", i + 1, sliceResult.getErrorMessage());
                    }

                    // 间隔等待（除了最后一片）
                    if (i < totalSlices - 1 && remainingQuantity > 0) {
                        try {
                            Thread.sleep(60000); // 等待1分钟
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                // 更新订单执行结果
                updateOrderExecution(order, executedQuantity, totalValue);

                BigDecimal avgPrice = executedQuantity > 0 ? 
                    totalValue.divide(BigDecimal.valueOf(executedQuantity), 4, RoundingMode.HALF_UP) : 
                    BigDecimal.ZERO;

                return ExecutionResult.builder()
                    .success(executedQuantity > 0)
                    .orderId(orderId)
                    .executedQuantity(executedQuantity)
                    .executedPrice(avgPrice)
                    .executedValue(totalValue)
                    .message(String.format("TWAP执行完成: 执行数量=%d, 平均价格=%.4f", executedQuantity, avgPrice))
                    .build();

            } catch (Exception e) {
                log.error("TWAP执行异常: orderId={}", orderId, e);
                return ExecutionResult.failure("TWAP执行异常: " + e.getMessage());
            }
        }, executionWorker);
    }

    /**
     * VWAP执行策略 - 成交量加权平均价格
     */
    public CompletableFuture<ExecutionResult> executeVwap(String orderId, VwapParameters vwapParams) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("执行VWAP策略: orderId={}, 参考周期={}分钟", orderId, vwapParams.getLookbackMinutes());

                // TODO: 实现VWAP算法
                // 1. 获取历史成交量数据
                // 2. 计算成交量分布曲线
                // 3. 按成交量比例分配执行
                // 4. 动态调整执行节奏

                return ExecutionResult.builder()
                    .success(true)
                    .orderId(orderId)
                    .message("VWAP执行策略（待完善）")
                    .build();

            } catch (Exception e) {
                log.error("VWAP执行异常: orderId={}", orderId, e);
                return ExecutionResult.failure("VWAP执行异常: " + e.getMessage());
            }
        }, executionWorker);
    }

    /**
     * 市价单立即执行
     */
    public CompletableFuture<ExecutionResult> executeMarketOrder(String orderId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("执行市价单: orderId={}", orderId);

                Optional<Order> orderOpt = orderRepository.findById(orderId);
                if (orderOpt.isEmpty()) {
                    return ExecutionResult.failure("订单不存在");
                }

                Order order = orderOpt.get();

                // 获取当前市价
                BigDecimal marketPrice = getCurrentMarketPrice(order.getSymbol(), order.getSide());
                if (marketPrice == null) {
                    return ExecutionResult.failure("无法获取市场价格");
                }

                // 立即执行
                SliceExecutionResult sliceResult = executeSliceAtPrice(order, order.getQuantity(), marketPrice);
                
                if (sliceResult.isSuccess()) {
                    // 更新订单
                    updateOrderExecution(order, sliceResult.getExecutedQuantity(), sliceResult.getExecutedValue());
                    
                    return ExecutionResult.builder()
                        .success(true)
                        .orderId(orderId)
                        .executedQuantity(sliceResult.getExecutedQuantity())
                        .executedPrice(sliceResult.getExecutedPrice())
                        .executedValue(sliceResult.getExecutedValue())
                        .message("市价单执行成功")
                        .build();
                } else {
                    return ExecutionResult.failure("市价单执行失败: " + sliceResult.getErrorMessage());
                }

            } catch (Exception e) {
                log.error("市价单执行异常: orderId={}", orderId, e);
                return ExecutionResult.failure("市价单执行异常: " + e.getMessage());
            }
        }, executionWorker);
    }

    /**
     * 限价单条件执行
     */
    public CompletableFuture<ExecutionResult> executeLimitOrder(String orderId, LimitOrderParameters limitParams) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("执行限价单: orderId={}, 限价={}", orderId, limitParams.getLimitPrice());

                Optional<Order> orderOpt = orderRepository.findById(orderId);
                if (orderOpt.isEmpty()) {
                    return ExecutionResult.failure("订单不存在");
                }

                Order order = orderOpt.get();
                BigDecimal limitPrice = limitParams.getLimitPrice();

                // 检查价格条件
                BigDecimal currentPrice = getCurrentMarketPrice(order.getSymbol(), order.getSide());
                if (currentPrice == null) {
                    return ExecutionResult.failure("无法获取市场价格");
                }

                boolean canExecute = checkLimitPriceCondition(order.getSide(), currentPrice, limitPrice);
                if (!canExecute) {
                    return ExecutionResult.builder()
                        .success(false)
                        .orderId(orderId)
                        .message(String.format("限价条件未满足: 当前价格=%.4f, 限价=%.4f", currentPrice, limitPrice))
                        .build();
                }

                // 执行订单
                SliceExecutionResult sliceResult = executeSliceAtPrice(order, order.getQuantity(), limitPrice);
                
                if (sliceResult.isSuccess()) {
                    updateOrderExecution(order, sliceResult.getExecutedQuantity(), sliceResult.getExecutedValue());
                    
                    return ExecutionResult.builder()
                        .success(true)
                        .orderId(orderId)
                        .executedQuantity(sliceResult.getExecutedQuantity())
                        .executedPrice(sliceResult.getExecutedPrice())
                        .executedValue(sliceResult.getExecutedValue())
                        .message("限价单执行成功")
                        .build();
                } else {
                    return ExecutionResult.failure("限价单执行失败: " + sliceResult.getErrorMessage());
                }

            } catch (Exception e) {
                log.error("限价单执行异常: orderId={}", orderId, e);
                return ExecutionResult.failure("限价单执行异常: " + e.getMessage());
            }
        }, executionWorker);
    }

    /**
     * 获取执行统计
     */
    public ExecutionStatistics getExecutionStatistics() {
        try {
            // 从数据库统计数据
            List<Order> recentOrders = orderRepository.findRecentOrders(LocalDateTime.now().minusDays(1));
            
            long totalExecutions = executionCounter.get();
            long successfulExecutions = recentOrders.stream()
                .mapToLong(order -> order.getStatus() == OrderStatus.FILLED ? 1 : 0)
                .sum();
            
            double successRate = totalExecutions > 0 ? 
                (double) successfulExecutions / totalExecutions * 100 : 0.0;

            return ExecutionStatistics.builder()
                .totalExecutions(totalExecutions)
                .successfulExecutions(successfulExecutions)
                .successRate(successRate)
                .activeStrategies(activeStrategies.size())
                .averageExecutionTime(calculateAverageExecutionTime())
                .build();

        } catch (Exception e) {
            log.error("获取执行统计异常", e);
            return ExecutionStatistics.builder().build();
        }
    }

    // 私有辅助方法

    /**
     * 检查订单是否可以执行
     */
    private boolean canExecuteOrder(Order order) {
        return order.getStatus() == OrderStatus.PENDING && 
               executionEnabled.get();
    }

    /**
     * 订单风险评估
     */
    private RiskAssessment assessOrderRisk(Order order, ExecutionParameters params) {
        try {
            // 基础风险检查
            if (order.getQuantity() <= 0) {
                return RiskAssessment.rejected("订单数量无效");
            }

            // 价格合理性检查
            BigDecimal currentPrice = getCurrentMarketPrice(order.getSymbol(), order.getSide());
            if (currentPrice != null && order.getPrice() != null) {
                BigDecimal priceDeviation = order.getPrice()
                    .subtract(currentPrice)
                    .divide(currentPrice, 4, RoundingMode.HALF_UP)
                    .abs();
                
                if (priceDeviation.compareTo(BigDecimal.valueOf(0.1)) > 0) { // 10%偏离
                    return RiskAssessment.rejected("订单价格偏离市价过大: " + priceDeviation);
                }
            }

            // TODO: 更多风险检查
            // 1. 持仓集中度检查
            // 2. 单日交易限额检查
            // 3. 市场波动率检查
            // 4. 流动性检查

            return RiskAssessment.approved();

        } catch (Exception e) {
            log.error("风险评估异常", e);
            return RiskAssessment.rejected("风险评估系统异常");
        }
    }

    /**
     * 选择执行策略
     */
    private ExecutionStrategy selectExecutionStrategy(Order order, ExecutionParameters params) {
        ExecutionStrategyType strategyType = params.getStrategy() != null ? 
            params.getStrategy() : 
            determineOptimalStrategy(order);

        return ExecutionStrategy.builder()
            .type(strategyType)
            .parameters(params)
            .order(order)
            .build();
    }

    /**
     * 确定最优执行策略
     */
    private ExecutionStrategyType determineOptimalStrategy(Order order) {
        // 根据订单特征选择策略
        if (order.getType() == OrderType.MARKET) {
            return ExecutionStrategyType.MARKET;
        } else if (order.getType() == OrderType.LIMIT) {
            return ExecutionStrategyType.LIMIT;
        } else if (order.getQuantity() > 10000) { // 大单使用TWAP
            return ExecutionStrategyType.TWAP;
        } else {
            return ExecutionStrategyType.MARKET;
        }
    }

    /**
     * 使用策略执行订单
     */
    private ExecutionResult executeOrderWithStrategy(Order order, ExecutionStrategy strategy, 
                                                   ExecutionParameters params) {
        try {
            return switch (strategy.getType()) {
                case MARKET -> executeMarketOrder(order.getOrderId()).get();
                case LIMIT -> executeLimitOrder(order.getOrderId(), 
                    LimitOrderParameters.builder().limitPrice(order.getPrice()).build()).get();
                case TWAP -> executeTwap(order.getOrderId(), 
                    TwapParameters.builder().durationMinutes(params.getDurationMinutes()).build()).get();
                case VWAP -> executeVwap(order.getOrderId(), 
                    VwapParameters.builder().lookbackMinutes(30).build()).get();
                default -> ExecutionResult.failure("不支持的执行策略: " + strategy.getType());
            };

        } catch (Exception e) {
            log.error("策略执行异常", e);
            return ExecutionResult.failure("策略执行异常: " + e.getMessage());
        }
    }

    /**
     * 执行订单分片
     */
    private SliceExecutionResult executeSlice(Order order, int quantity) {
        BigDecimal currentPrice = getCurrentMarketPrice(order.getSymbol(), order.getSide());
        if (currentPrice == null) {
            return SliceExecutionResult.failure("无法获取市场价格");
        }
        return executeSliceAtPrice(order, quantity, currentPrice);
    }

    /**
     * 按指定价格执行订单分片
     */
    private SliceExecutionResult executeSliceAtPrice(Order order, int quantity, BigDecimal price) {
        try {
            // TODO: 调用实际的交易API
            // 这里模拟执行逻辑
            
            log.debug("模拟执行分片: symbol={}, quantity={}, price={}", 
                order.getSymbol(), quantity, price);

            // 模拟执行延迟
            Thread.sleep(100);

            // 模拟95%成功率
            boolean success = Math.random() < 0.95;
            
            if (success) {
                BigDecimal executedValue = price.multiply(BigDecimal.valueOf(quantity));
                return SliceExecutionResult.builder()
                    .success(true)
                    .executedQuantity(quantity)
                    .executedPrice(price)
                    .executedValue(executedValue)
                    .build();
            } else {
                return SliceExecutionResult.failure("模拟执行失败");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SliceExecutionResult.failure("执行中断");
        } catch (Exception e) {
            log.error("分片执行异常", e);
            return SliceExecutionResult.failure("分片执行异常: " + e.getMessage());
        }
    }

    /**
     * 获取当前市场价格
     */
    private BigDecimal getCurrentMarketPrice(String symbol, OrderSide side) {
        try {
            // 从行情服务获取最新价格
            // 这里使用模拟价格
            return switch (symbol) {
                case "00700.HK" -> BigDecimal.valueOf(300.0 + (Math.random() - 0.5) * 10);
                case "09988.HK" -> BigDecimal.valueOf(85.0 + (Math.random() - 0.5) * 5);
                case "03690.HK" -> BigDecimal.valueOf(120.0 + (Math.random() - 0.5) * 8);
                default -> BigDecimal.valueOf(100.0 + (Math.random() - 0.5) * 5);
            };

        } catch (Exception e) {
            log.error("获取市场价格异常: symbol={}", symbol, e);
            return null;
        }
    }

    /**
     * 检查限价条件
     */
    private boolean checkLimitPriceCondition(OrderSide side, BigDecimal currentPrice, BigDecimal limitPrice) {
        if (side == OrderSide.BUY) {
            // 买入：当前价格 <= 限价
            return currentPrice.compareTo(limitPrice) <= 0;
        } else {
            // 卖出：当前价格 >= 限价
            return currentPrice.compareTo(limitPrice) >= 0;
        }
    }

    /**
     * 更新订单执行结果
     */
    @Transactional
    private void updateOrderExecution(Order order, int executedQuantity, BigDecimal executedValue) {
        try {
            if (executedQuantity > 0) {
                BigDecimal avgPrice = executedValue.divide(
                    BigDecimal.valueOf(executedQuantity), 4, RoundingMode.HALF_UP);
                
                order.setExecutedQuantity(order.getExecutedQuantity() + executedQuantity);
                order.setExecutedPrice(avgPrice);
                order.setUpdateTime(LocalDateTime.now());
                
                // 更新订单状态
                if (order.getExecutedQuantity() >= order.getQuantity()) {
                    order.setStatus(OrderStatus.FILLED);
                } else {
                    order.setStatus(OrderStatus.PARTIAL_FILLED);
                }
                
                orderRepository.save(order);
                
                // 更新缓存
                cacheService.cache("order:" + order.getOrderId(), order, 3600);
                
                log.debug("订单执行更新: orderId={}, 执行数量={}, 平均价格={}", 
                    order.getOrderId(), executedQuantity, avgPrice);
            }

        } catch (Exception e) {
            log.error("更新订单执行结果异常", e);
        }
    }

    /**
     * 启动执行监控
     */
    private void startExecutionMonitoring() {
        executionMonitorTask = executionScheduler.scheduleWithFixedDelay(() -> {
            try {
                monitorActiveExecutions();
            } catch (Exception e) {
                log.error("执行监控异常", e);
            }
        }, 5, 5, TimeUnit.SECONDS);

        log.debug("执行监控任务已启动");
    }

    /**
     * 启动风险监控
     */
    private void startRiskMonitoring() {
        riskMonitorTask = executionScheduler.scheduleWithFixedDelay(() -> {
            try {
                monitorRiskMetrics();
            } catch (Exception e) {
                log.error("风险监控异常", e);
            }
        }, 10, 30, TimeUnit.SECONDS);

        log.debug("风险监控任务已启动");
    }

    /**
     * 监控活跃执行
     */
    private void monitorActiveExecutions() {
        // TODO: 监控长时间未完成的执行
        // TODO: 监控执行性能指标
        // TODO: 监控执行异常情况
        log.trace("执行监控检查完成");
    }

    /**
     * 监控风险指标
     */
    private void monitorRiskMetrics() {
        // TODO: 监控持仓集中度
        // TODO: 监控交易频率
        // TODO: 监控异常价格波动
        log.trace("风险监控检查完成");
    }

    /**
     * 计算平均执行时间
     */
    private double calculateAverageExecutionTime() {
        // TODO: 从历史数据计算平均执行时间
        return 2.5; // 模拟值：2.5秒
    }

    /**
     * 检查服务健康状态
     */
    public boolean isHealthy() {
        return executionEnabled.get() && 
               executionScheduler != null && 
               !executionScheduler.isShutdown() &&
               executionWorker != null && 
               !executionWorker.isShutdown();
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭订单执行服务...");
        
        executionEnabled.set(false);
        
        try {
            // 取消监控任务
            if (executionMonitorTask != null) {
                executionMonitorTask.cancel(false);
            }
            if (riskMonitorTask != null) {
                riskMonitorTask.cancel(false);
            }
            
            // 关闭执行器
            executionScheduler.shutdown();
            executionWorker.shutdown();
            
            if (!executionScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                executionScheduler.shutdownNow();
            }
            if (!executionWorker.awaitTermination(10, TimeUnit.SECONDS)) {
                executionWorker.shutdownNow();
            }
            
            log.info("订单执行服务关闭完成");
            
        } catch (Exception e) {
            log.error("关闭订单执行服务异常", e);
        }
    }

    // 数据类和枚举定义

    public enum ExecutionStrategyType {
        MARKET,    // 市价单
        LIMIT,     // 限价单
        TWAP,      // 时间加权平均价格
        VWAP       // 成交量加权平均价格
    }

    @lombok.Builder
    @lombok.Data
    public static class ExecutionParameters {
        private ExecutionStrategyType strategy;
        private Integer durationMinutes;
        private Integer timeout;
        private BigDecimal priceLimit;
        private Boolean aggressive;
    }

    @lombok.Builder
    @lombok.Data
    public static class ExecutionResult {
        private boolean success;
        private String orderId;
        private Integer executedQuantity;
        private BigDecimal executedPrice;
        private BigDecimal executedValue;
        private String message;
        
        public static ExecutionResult failure(String message) {
            return ExecutionResult.builder()
                .success(false)
                .message(message)
                .build();
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class BatchExecutionResult {
        private int totalCount;
        private int successCount;
        private int failureCount;
        private String errors;
        private boolean success;
    }

    @lombok.Builder
    @lombok.Data
    public static class TwapParameters {
        private Integer durationMinutes;
        private Integer sliceCount;
    }

    @lombok.Builder
    @lombok.Data
    public static class VwapParameters {
        private Integer lookbackMinutes;
        private Double participationRate;
    }

    @lombok.Builder
    @lombok.Data
    public static class LimitOrderParameters {
        private BigDecimal limitPrice;
        private Integer timeoutMinutes;
    }

    @lombok.Builder
    @lombok.Data
    public static class ExecutionStrategy {
        private ExecutionStrategyType type;
        private ExecutionParameters parameters;
        private Order order;
    }

    @lombok.Builder
    @lombok.Data
    public static class SliceExecutionResult {
        private boolean success;
        private Integer executedQuantity;
        private BigDecimal executedPrice;
        private BigDecimal executedValue;
        private String errorMessage;
        
        public static SliceExecutionResult failure(String errorMessage) {
            return SliceExecutionResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class RiskAssessment {
        private boolean approved;
        private String reason;
        
        public static RiskAssessment approved() {
            return RiskAssessment.builder()
                .approved(true)
                .build();
        }
        
        public static RiskAssessment rejected(String reason) {
            return RiskAssessment.builder()
                .approved(false)
                .reason(reason)
                .build();
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class ExecutionStatistics {
        private long totalExecutions;
        private long successfulExecutions;
        private double successRate;
        private int activeStrategies;
        private double averageExecutionTime;
    }
}