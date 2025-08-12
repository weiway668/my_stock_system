package com.trading.service;

import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.enums.OrderSide;
import com.trading.domain.enums.OrderStatus;
import com.trading.domain.enums.OrderType;
import com.trading.infrastructure.cache.CacheService;
import com.trading.infrastructure.futu.FutuConnectionManager;
import com.trading.repository.OrderRepository;
import com.trading.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trading Service
 * 统一的交易服务接口，对应Python的FutuBroker
 * 提供订单提交、取消、账户查询、持仓管理等核心交易功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.futu.connection.host")
public class TradingService {

    private final FutuConnectionManager connectionManager;
    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final CacheService cacheService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.trading.infrastructure.futu.protocol.FutuProtobufSerializer protobufSerializer;

    // 交易执行器和监控
    private final ScheduledExecutorService tradingExecutor = Executors.newScheduledThreadPool(4);
    private final AtomicBoolean tradingEnabled = new AtomicBoolean(false);
    private final AtomicLong orderSequence = new AtomicLong(1);

    // 账户和持仓缓存
    private final ConcurrentHashMap<String, AccountInfo> accountCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Position> positionCache = new ConcurrentHashMap<>();
    
    // 交易监控任务
    private java.util.concurrent.ScheduledFuture<?> orderMonitoringTask;
    private java.util.concurrent.ScheduledFuture<?> positionSyncTask;

    @PostConstruct
    public void initialize() {
        log.info("初始化交易服务...");
        
        if (connectionManager.isTradeConnected()) {
            startTradingService();
        }
        
        // 启动订单监控
        startOrderMonitoring();
        
        // 启动持仓同步
        startPositionSync();
        
        tradingEnabled.set(true);
        log.info("交易服务初始化完成");
    }

    /**
     * 提交订单 - 对应Python的submit_order()
     */
    @Transactional
    public CompletableFuture<OrderResult> submitOrder(OrderRequest orderRequest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("提交订单: 股票={}, 类型={}, 数量={}, 价格={}", 
                    orderRequest.getSymbol(), orderRequest.getOrderType(), 
                    orderRequest.getQuantity(), orderRequest.getPrice());

                // 验证交易环境
                if (!validateTradingEnvironment()) {
                    return OrderResult.failure("交易环境不可用");
                }

                // 风险检查
                RiskCheckResult riskCheck = performRiskCheck(orderRequest);
                if (!riskCheck.isApproved()) {
                    return OrderResult.failure("风险检查失败: " + riskCheck.getReason());
                }

                // 创建订单
                Order order = createOrder(orderRequest);
                order = orderRepository.save(order);

                // 提交到FUTU API
                boolean submitted = submitOrderToFutu(order);
                
                if (submitted) {
                    order.setStatus(OrderStatus.PENDING);
                    order.setUpdateTime(LocalDateTime.now());
                    order = orderRepository.save(order);
                    
                    // 缓存订单
                    cacheService.cache("order:" + order.getOrderId(), order, 3600);
                    
                    // 发布订单提交事件
                    publishOrderEvent(order, "ORDER_SUBMITTED");
                    
                    log.info("订单提交成功: ID={}", order.getOrderId());
                    return OrderResult.success(order);
                } else {
                    order.setStatus(OrderStatus.REJECTED);
                    order.setNotes("FUTU API提交失败");
                    orderRepository.save(order);
                    
                    log.error("订单提交失败: ID={}", order.getOrderId());
                    return OrderResult.failure("FUTU API提交失败");
                }

            } catch (Exception e) {
                log.error("提交订单时发生错误", e);
                return OrderResult.failure("提交订单异常: " + e.getMessage());
            }
        }, tradingExecutor);
    }

    /**
     * 取消订单 - 对应Python的cancel_order()
     */
    @Transactional
    public CompletableFuture<OrderResult> cancelOrder(String orderId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("取消订单: ID={}", orderId);

                Optional<Order> orderOpt = orderRepository.findById(orderId);
                if (orderOpt.isEmpty()) {
                    return OrderResult.failure("订单不存在");
                }

                Order order = orderOpt.get();
                
                // 检查订单状态
                if (!canCancelOrder(order)) {
                    return OrderResult.failure("订单状态不允许取消: " + order.getStatus());
                }

                // 调用FUTU API取消订单
                boolean cancelled = cancelOrderInFutu(order);
                
                if (cancelled) {
                    order.setStatus(OrderStatus.CANCELLED);
                    order.setUpdateTime(LocalDateTime.now());
                    order = orderRepository.save(order);
                    
                    // 更新缓存
                    cacheService.cache("order:" + order.getOrderId(), order, 3600);
                    
                    // 发布取消事件
                    publishOrderEvent(order, "ORDER_CANCELLED");
                    
                    log.info("订单取消成功: ID={}", orderId);
                    return OrderResult.success(order);
                } else {
                    log.error("FUTU API取消订单失败: ID={}", orderId);
                    return OrderResult.failure("FUTU API取消失败");
                }

            } catch (Exception e) {
                log.error("取消订单时发生错误: ID={}", orderId, e);
                return OrderResult.failure("取消订单异常: " + e.getMessage());
            }
        }, tradingExecutor);
    }

    /**
     * 获取账户信息 - 对应Python的get_account_info()
     */
    public CompletableFuture<AccountInfo> getAccountInfo(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 先从缓存获取
                AccountInfo cached = accountCache.get(accountId);
                if (cached != null && isAccountInfoFresh(cached)) {
                    return cached;
                }

                log.debug("从FUTU API获取账户信息: accountId={}", accountId);
                
                // 从FUTU API获取
                AccountInfo accountInfo = fetchAccountInfoFromFutu(accountId);
                
                if (accountInfo != null) {
                    // 更新缓存
                    accountCache.put(accountId, accountInfo);
                    cacheService.cache("account:" + accountId, accountInfo, 300);
                    
                    log.debug("账户信息获取成功: 可用资金={}", accountInfo.getAvailableCash());
                    return accountInfo;
                } else {
                    log.warn("获取账户信息失败: accountId={}", accountId);
                    return null;
                }

            } catch (Exception e) {
                log.error("获取账户信息异常: accountId={}", accountId, e);
                return null;
            }
        }, tradingExecutor);
    }

    /**
     * 获取持仓信息 - 对应Python的get_positions()
     */
    public CompletableFuture<List<Position>> getPositions(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("获取持仓信息: accountId={}", accountId);
                
                // 从FUTU API获取最新持仓
                List<Position> positions = fetchPositionsFromFutu(accountId);
                
                if (positions != null && !positions.isEmpty()) {
                    // 更新数据库
                    savePositionsToDatabase(positions);
                    
                    // 更新缓存
                    positions.forEach(pos -> {
                        positionCache.put(pos.getSymbol(), pos);
                        cacheService.cache("position:" + pos.getSymbol(), pos, 300);
                    });
                    
                    log.info("持仓信息获取成功: 持仓数量={}", positions.size());
                    return positions;
                } else {
                    // 如果API失败，尝试从数据库获取
                    List<Position> dbPositions = positionRepository.findByAccountId(accountId);
                    log.debug("从数据库获取持仓: 数量={}", dbPositions.size());
                    return dbPositions;
                }

            } catch (Exception e) {
                log.error("获取持仓信息异常: accountId={}", accountId, e);
                return List.of();
            }
        }, tradingExecutor);
    }

    /**
     * 获取订单状态
     */
    public CompletableFuture<List<Order>> getOrders(String accountId, OrderStatus status) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("查询订单: accountId={}, status={}", accountId, status);
                
                List<Order> orders;
                if (status != null) {
                    orders = orderRepository.findByStatus(status);
                } else {
                    orders = orderRepository.findAll();
                }
                
                // 同步最新状态
                syncOrderStatusFromFutu(orders);
                
                log.debug("订单查询完成: 数量={}", orders.size());
                return orders;

            } catch (Exception e) {
                log.error("查询订单异常: accountId={}", accountId, e);
                return List.of();
            }
        }, tradingExecutor);
    }

    /**
     * 检查交易权限和解锁状态
     */
    public CompletableFuture<Boolean> unlockTrade(String password) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("尝试解锁交易权限");
                
                if (!connectionManager.isTradeConnected()) {
                    log.warn("交易连接不可用");
                    return false;
                }

                // TODO: 调用FUTU API解锁交易
                // 例如: tradeContext.unlockTrade(password);
                boolean unlocked = simulateTradeUnlock(password);
                
                if (unlocked) {
                    tradingEnabled.set(true);
                    log.info("交易解锁成功");
                } else {
                    log.error("交易解锁失败");
                }
                
                return unlocked;

            } catch (Exception e) {
                log.error("交易解锁异常", e);
                return false;
            }
        }, tradingExecutor);
    }

    /**
     * 获取最大可买卖数量
     */
    public CompletableFuture<MaxTradeQuantity> getMaxTradeQuantity(String symbol, String accountId, 
                                                                  OrderType orderType, BigDecimal price) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("查询最大可交易数量: symbol={}, orderType={}, price={}", symbol, orderType, price);
                
                // TODO: 调用FUTU API获取最大可买卖数量
                MaxTradeQuantity maxQuantity = calculateMaxTradeQuantity(symbol, accountId, orderType, price);
                
                log.debug("最大可交易数量: 买入={}, 卖出={}", 
                    maxQuantity.getMaxBuyQuantity(), maxQuantity.getMaxSellQuantity());
                
                return maxQuantity;

            } catch (Exception e) {
                log.error("查询最大可交易数量异常", e);
                return MaxTradeQuantity.builder()
                    .maxBuyQuantity(0L)
                    .maxSellQuantity(0L)
                    .build();
            }
        }, tradingExecutor);
    }

    /**
     * 启动交易服务
     */
    private void startTradingService() {
        log.info("启动交易服务...");
        
        // TODO: 初始化FUTU交易上下文回调
        // 例如: setupTradeCallbacks();
        
        log.info("交易服务启动完成");
    }

    /**
     * 启动订单监控
     */
    private void startOrderMonitoring() {
        orderMonitoringTask = tradingExecutor.scheduleWithFixedDelay(() -> {
            try {
                monitorActiveOrders();
            } catch (Exception e) {
                log.error("订单监控异常", e);
            }
        }, 10, 10, TimeUnit.SECONDS);
        
        log.debug("订单监控任务已启动");
    }

    /**
     * 启动持仓同步
     */
    private void startPositionSync() {
        positionSyncTask = tradingExecutor.scheduleWithFixedDelay(() -> {
            try {
                syncAllPositions();
            } catch (Exception e) {
                log.error("持仓同步异常", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        log.debug("持仓同步任务已启动");
    }

    /**
     * 验证交易环境
     */
    private boolean validateTradingEnvironment() {
        return connectionManager.isTradeConnected() && tradingEnabled.get();
    }

    /**
     * 风险检查
     */
    private RiskCheckResult performRiskCheck(OrderRequest orderRequest) {
        try {
            // 基础风险检查
            if (orderRequest.getQuantity() <= 0) {
                return RiskCheckResult.rejected("订单数量必须大于0");
            }
            
            if (orderRequest.getPrice() != null && orderRequest.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                return RiskCheckResult.rejected("订单价格必须大于0");
            }
            
            // TODO: 实现更复杂的风险控制逻辑
            // 例如: 资金检查、持仓限制、价格偏离检查等
            
            return RiskCheckResult.approved();

        } catch (Exception e) {
            log.error("风险检查异常", e);
            return RiskCheckResult.rejected("风险检查系统异常");
        }
    }

    /**
     * 创建订单对象
     */
    private Order createOrder(OrderRequest request) {
        String orderId = generateOrderId();
        
        return Order.builder()
            .orderId(orderId)
            .symbol(request.getSymbol())
            .type(request.getOrderType())
            .side(request.getSide())
            .quantity(request.getQuantity().intValue())
            .price(request.getPrice())
            .status(OrderStatus.PENDING)
            .createTime(LocalDateTime.now())
            .updateTime(LocalDateTime.now())
            .build();
    }

    /**
     * 生成订单ID
     */
    private String generateOrderId() {
        long sequence = orderSequence.getAndIncrement();
        String timestamp = String.valueOf(System.currentTimeMillis());
        return "ORD_" + timestamp + "_" + String.format("%06d", sequence);
    }

    /**
     * 提交订单到FUTU API（模拟实现）
     */
    private boolean submitOrderToFutu(Order order) {
        try {
            log.debug("模拟提交订单到FUTU API: {}", order.getOrderId());
            
            // TODO: 替换为真实的FUTU API调用
            // 例如: tradeContext.placeOrder(orderRequest);
            
            // 模拟API调用延迟
            Thread.sleep(500);
            
            // 模拟95%成功率
            return Math.random() < 0.95;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("提交订单到FUTU API异常", e);
            return false;
        }
    }

    /**
     * 在FUTU API中取消订单（模拟实现）
     */
    private boolean cancelOrderInFutu(Order order) {
        try {
            log.debug("模拟在FUTU API中取消订单: {}", order.getOrderId());
            
            // TODO: 替换为真实的FUTU API调用
            // 例如: tradeContext.cancelOrder(order.getFutuOrderId());
            
            // 模拟API调用延迟
            Thread.sleep(300);
            
            // 模拟90%成功率
            return Math.random() < 0.90;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("在FUTU API中取消订单异常", e);
            return false;
        }
    }

    /**
     * 从FUTU API获取账户信息（模拟实现）
     */
    private AccountInfo fetchAccountInfoFromFutu(String accountId) {
        try {
            log.debug("模拟从FUTU API获取账户信息: {}", accountId);
            
            // TODO: 替换为真实的FUTU API调用
            // 例如: tradeContext.getAccountInfo(accountId);
            
            return AccountInfo.builder()
                .accountId(accountId)
                .totalAssets(BigDecimal.valueOf(1000000))
                .availableCash(BigDecimal.valueOf(500000))
                .marketValue(BigDecimal.valueOf(500000))
                .currency("HKD")
                .lastUpdated(LocalDateTime.now())
                .build();

        } catch (Exception e) {
            log.error("从FUTU API获取账户信息异常", e);
            return null;
        }
    }

    /**
     * 从FUTU API获取持仓（模拟实现）
     */
    private List<Position> fetchPositionsFromFutu(String accountId) {
        try {
            log.debug("模拟从FUTU API获取持仓: {}", accountId);
            
            // TODO: 替换为真实的FUTU API调用
            // 例如: tradeContext.getPositions(accountId);
            
            // 返回模拟持仓数据
            return List.of(
                Position.builder()
                    .id("POS_00700")
                    .symbol("00700.HK")
                    .accountId(accountId)
                    .quantity(1000)
                    .avgCost(BigDecimal.valueOf(300.50))
                    .currentPrice(BigDecimal.valueOf(302.00))
                    .marketValue(BigDecimal.valueOf(302000))
                    .unrealizedPnl(BigDecimal.valueOf(1500))
                    .realizedPnl(BigDecimal.ZERO)
                    .openTime(LocalDateTime.now().minusDays(5))
                    .lastUpdateTime(LocalDateTime.now())
                    .build()
            );

        } catch (Exception e) {
            log.error("从FUTU API获取持仓异常", e);
            return null;
        }
    }

    /**
     * 检查是否可以取消订单
     */
    private boolean canCancelOrder(Order order) {
        return order.getStatus() == OrderStatus.PENDING ||
               order.getStatus() == OrderStatus.PARTIAL_FILLED;
    }

    /**
     * 检查账户信息是否新鲜
     */
    private boolean isAccountInfoFresh(AccountInfo accountInfo) {
        return accountInfo.getLastUpdated() != null &&
               accountInfo.getLastUpdated().isAfter(LocalDateTime.now().minusMinutes(5));
    }

    /**
     * 模拟交易解锁
     */
    private boolean simulateTradeUnlock(String password) {
        // 模拟密码验证
        return "123456".equals(password) || "trading".equals(password);
    }

    /**
     * 计算最大可交易数量（模拟实现）
     */
    private MaxTradeQuantity calculateMaxTradeQuantity(String symbol, String accountId, 
                                                       OrderType orderType, BigDecimal price) {
        // 模拟计算逻辑
        if (orderType == OrderType.LIMIT) {
            // 模拟可买入数量基于可用资金
            long maxBuy = 10000L;
            return MaxTradeQuantity.builder()
                .maxBuyQuantity(maxBuy)
                .maxSellQuantity(0L)
                .build();
        } else {
            // 模拟可卖出数量基于持仓
            long maxSell = 5000L;
            return MaxTradeQuantity.builder()
                .maxBuyQuantity(0L)
                .maxSellQuantity(maxSell)
                .build();
        }
    }

    /**
     * 监控活跃订单
     */
    private void monitorActiveOrders() {
        try {
            List<Order> activeOrders = orderRepository.findByStatus(OrderStatus.PENDING);
            
            for (Order order : activeOrders) {
                // TODO: 查询FUTU API获取订单最新状态
                // 例如: updateOrderStatusFromFutu(order);
                
                log.trace("监控订单: {}", order.getOrderId());
            }

        } catch (Exception e) {
            log.error("监控活跃订单异常", e);
        }
    }

    /**
     * 同步所有持仓
     */
    private void syncAllPositions() {
        try {
            // 获取所有活跃账户的持仓
            String defaultAccount = "default_account";
            List<Position> positions = fetchPositionsFromFutu(defaultAccount);
            
            if (positions != null && !positions.isEmpty()) {
                savePositionsToDatabase(positions);
                log.trace("持仓同步完成: 数量={}", positions.size());
            }

        } catch (Exception e) {
            log.error("同步持仓异常", e);
        }
    }

    /**
     * 从FUTU同步订单状态
     */
    private void syncOrderStatusFromFutu(List<Order> orders) {
        try {
            for (Order order : orders) {
                // TODO: 查询FUTU API获取最新状态
                // 例如: Order.OrderStatus newStatus = queryOrderStatusFromFutu(order.getFutuOrderId());
                
                log.trace("同步订单状态: {}", order.getOrderId());
            }
        } catch (Exception e) {
            log.error("同步订单状态异常", e);
        }
    }

    /**
     * 保存持仓到数据库
     */
    @Transactional
    private void savePositionsToDatabase(List<Position> positions) {
        try {
            for (Position position : positions) {
                Optional<Position> existing = positionRepository.findBySymbolAndAccountId(
                    position.getSymbol(), position.getAccountId());
                
                if (existing.isPresent()) {
                    Position existingPos = existing.get();
                    existingPos.setQuantity(position.getQuantity());
                    existingPos.setCurrentPrice(position.getCurrentPrice());
                    existingPos.setMarketValue(position.getMarketValue());
                    existingPos.setUnrealizedPnl(position.getUnrealizedPnl());
                    existingPos.setUpdatedAt(LocalDateTime.now());
                    positionRepository.save(existingPos);
                } else {
                    positionRepository.save(position);
                }
            }
        } catch (Exception e) {
            log.error("保存持仓到数据库异常", e);
        }
    }

    /**
     * 发布订单事件
     */
    private void publishOrderEvent(Order order, String eventType) {
        try {
            // TODO: 创建并发布订单事件
            // eventPublisher.publishEvent(new OrderEvent(order, eventType));
            log.trace("发布订单事件: {} - {}", eventType, order.getOrderId());
        } catch (Exception e) {
            log.warn("发布订单事件异常", e);
        }
    }

    /**
     * 检查服务是否健康
     */
    public boolean isHealthy() {
        return connectionManager.isTradeConnected() && 
               tradingEnabled.get() && 
               tradingExecutor != null && 
               !tradingExecutor.isShutdown();
    }

    /**
     * 关闭服务
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("关闭交易服务...");
        
        tradingEnabled.set(false);
        
        try {
            // 取消监控任务
            if (orderMonitoringTask != null) {
                orderMonitoringTask.cancel(false);
            }
            if (positionSyncTask != null) {
                positionSyncTask.cancel(false);
            }
            
            // 关闭执行器
            tradingExecutor.shutdown();
            if (!tradingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                tradingExecutor.shutdownNow();
            }
            
            log.info("交易服务关闭完成");
            
        } catch (Exception e) {
            log.error("关闭交易服务异常", e);
        }
    }

    // 数据类定义
    @lombok.Builder
    @lombok.Data
    public static class OrderRequest {
        private String symbol;
        private com.trading.domain.enums.OrderType orderType;
        private com.trading.domain.enums.OrderSide side;
        private Long quantity;
        private BigDecimal price;
        private String accountId;
        private String clientOrderId;
    }

    @lombok.Builder
    @lombok.Data
    public static class OrderResult {
        private boolean success;
        private Order order;
        private String message;
        
        public static OrderResult success(Order order) {
            return OrderResult.builder()
                .success(true)
                .order(order)
                .message("订单操作成功")
                .build();
        }
        
        public static OrderResult failure(String message) {
            return OrderResult.builder()
                .success(false)
                .message(message)
                .build();
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class AccountInfo {
        private String accountId;
        private BigDecimal totalAssets;
        private BigDecimal availableCash;
        private BigDecimal marketValue;
        private String currency;
        private LocalDateTime lastUpdated;
    }

    @lombok.Builder
    @lombok.Data
    public static class MaxTradeQuantity {
        private Long maxBuyQuantity;
        private Long maxSellQuantity;
    }

    @lombok.Builder
    @lombok.Data
    public static class RiskCheckResult {
        private boolean approved;
        private String reason;
        
        public static RiskCheckResult approved() {
            return RiskCheckResult.builder()
                .approved(true)
                .build();
        }
        
        public static RiskCheckResult rejected(String reason) {
            return RiskCheckResult.builder()
                .approved(false)
                .reason(reason)
                .build();
        }
    }
    
    // 辅助方法
    
    /**
     * 转换订单方向
     */
    private int convertOrderSide(OrderSide side) {
        return switch (side) {
            case BUY -> 1;  // 买入
            case SELL -> 2; // 卖出
            default -> 0;   // 未知
        };
    }
    
    /**
     * 转换订单类型
     */
    private int convertOrderType(OrderType type) {
        return switch (type) {
            case MARKET -> 2;  // 市价单
            case LIMIT -> 3;   // 限价单
            default -> 1;      // 普通订单
        };
    }
    
    /**
     * 获取市场代码
     */
    private int getMarketCode(String symbol) {
        if (symbol.endsWith(".HK")) {
            return 1; // 香港市场
        } else if (symbol.endsWith(".US")) {
            return 11; // 美国市场
        } else if (symbol.endsWith(".SH")) {
            return 21; // 上海市场
        } else if (symbol.endsWith(".SZ")) {
            return 22; // 深圳市场
        }
        return 1; // 默认香港市场
    }
    
    /**
     * 获取默认账户ID
     */
    private String getDefaultAccountId() {
        // TODO: 从配置或API获取真实账户ID
        return "DEFAULT_ACCOUNT";
    }
    
    /**
     * 检查是否模拟环境
     */
    private boolean isSimulationMode() {
        // TODO: 从配置获取
        return true;
    }
    
    /**
     * 哈希密码
     */
    private String hashPassword(String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("密码哈希失败", e);
            return password;
        }
    }
    
    /**
     * 解析下单响应
     */
    @SuppressWarnings("unchecked")
    private boolean parseOrderResponse(io.netty.buffer.ByteBuf response, Order order) {
        try {
            byte[] responseData = new byte[response.readableBytes()];
            response.readBytes(responseData);
            
            Map<String, Object> responseMap = protobufSerializer.parseResponse(responseData);
            
            if (responseMap != null && responseMap.containsKey("s2c")) {
                Map<String, Object> s2c = (Map<String, Object>) responseMap.get("s2c");
                if (s2c.containsKey("orderID")) {
                    order.setFutuOrderId(s2c.get("orderID").toString());
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            log.error("解析下单响应失败", e);
            return false;
        }
    }
    
    /**
     * 解析取消响应
     */
    @SuppressWarnings("unchecked")
    private boolean parseCancelResponse(io.netty.buffer.ByteBuf response) {
        try {
            byte[] responseData = new byte[response.readableBytes()];
            response.readBytes(responseData);
            
            Map<String, Object> responseMap = new com.google.gson.Gson().fromJson(
                new String(responseData, java.nio.charset.StandardCharsets.UTF_8),
                Map.class
            );
            
            return responseMap != null && responseMap.containsKey("s2c");
        } catch (Exception e) {
            log.error("解析取消响应失败", e);
            return false;
        }
    }
    
    /**
     * 解析账户信息响应
     */
    @SuppressWarnings("unchecked")
    private AccountInfo parseAccountInfoResponse(io.netty.buffer.ByteBuf response, String accountId) {
        try {
            byte[] responseData = new byte[response.readableBytes()];
            response.readBytes(responseData);
            
            Map<String, Object> responseMap = new com.google.gson.Gson().fromJson(
                new String(responseData, java.nio.charset.StandardCharsets.UTF_8),
                Map.class
            );
            
            if (responseMap != null && responseMap.containsKey("s2c")) {
                Map<String, Object> s2c = (Map<String, Object>) responseMap.get("s2c");
                if (s2c.containsKey("funds")) {
                    Map<String, Object> funds = (Map<String, Object>) s2c.get("funds");
                    
                    return AccountInfo.builder()
                        .accountId(accountId)
                        .totalAssets(BigDecimal.valueOf(((Number) funds.getOrDefault("totalAssets", 0)).doubleValue()))
                        .availableCash(BigDecimal.valueOf(((Number) funds.getOrDefault("cash", 0)).doubleValue()))
                        .marketValue(BigDecimal.valueOf(((Number) funds.getOrDefault("marketVal", 0)).doubleValue()))
                        .currency(funds.getOrDefault("currency", "HKD").toString())
                        .lastUpdated(LocalDateTime.now())
                        .build();
                }
            }
            
            return null;
        } catch (Exception e) {
            log.error("解析账户信息响应失败", e);
            return null;
        }
    }
    
    /**
     * 解析持仓响应
     */
    @SuppressWarnings("unchecked")
    private List<Position> parsePositionsResponse(io.netty.buffer.ByteBuf response, String accountId) {
        try {
            byte[] responseData = new byte[response.readableBytes()];
            response.readBytes(responseData);
            
            Map<String, Object> responseMap = new com.google.gson.Gson().fromJson(
                new String(responseData, java.nio.charset.StandardCharsets.UTF_8),
                Map.class
            );
            
            if (responseMap != null && responseMap.containsKey("s2c")) {
                Map<String, Object> s2c = (Map<String, Object>) responseMap.get("s2c");
                if (s2c.containsKey("positionList")) {
                    List<Map<String, Object>> positionList = (List<Map<String, Object>>) s2c.get("positionList");
                    
                    List<Position> positions = new java.util.ArrayList<>();
                    for (Map<String, Object> pos : positionList) {
                        Position position = Position.builder()
                            .id("POS_" + pos.get("code"))
                            .symbol(pos.get("code") + ".HK")
                            .accountId(accountId)
                            .quantity(((Number) pos.getOrDefault("qty", 0)).intValue())
                            .avgCost(BigDecimal.valueOf(((Number) pos.getOrDefault("costPrice", 0)).doubleValue()))
                            .currentPrice(BigDecimal.valueOf(((Number) pos.getOrDefault("price", 0)).doubleValue()))
                            .marketValue(BigDecimal.valueOf(((Number) pos.getOrDefault("val", 0)).doubleValue()))
                            .unrealizedPnl(BigDecimal.valueOf(((Number) pos.getOrDefault("plVal", 0)).doubleValue()))
                            .realizedPnl(BigDecimal.ZERO)
                            .lastUpdateTime(LocalDateTime.now())
                            .build();
                        
                        positions.add(position);
                    }
                    
                    return positions;
                }
            }
            
            return List.of();
        } catch (Exception e) {
            log.error("解析持仓响应失败", e);
            return List.of();
        }
    }
    
    /**
     * 解析解锁响应
     */
    @SuppressWarnings("unchecked")
    private boolean parseUnlockResponse(io.netty.buffer.ByteBuf response) {
        try {
            byte[] responseData = new byte[response.readableBytes()];
            response.readBytes(responseData);
            
            Map<String, Object> responseMap = new com.google.gson.Gson().fromJson(
                new String(responseData, java.nio.charset.StandardCharsets.UTF_8),
                Map.class
            );
            
            return responseMap != null && responseMap.containsKey("s2c");
        } catch (Exception e) {
            log.error("解析解锁响应失败", e);
            return false;
        }
    }
}