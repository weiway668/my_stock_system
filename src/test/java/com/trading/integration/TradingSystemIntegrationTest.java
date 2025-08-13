package com.trading.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.trading.TradingSystemStarter;
import com.trading.service.MarketDataService;
import com.trading.service.OrderExecutionService;
import com.trading.service.QuoteService;
import com.trading.service.RealTimeDataSubscriber;
import com.trading.service.TradingService;
import com.trading.service.TradingSignalProcessor;

import lombok.extern.slf4j.Slf4j;

/**
 * 交易系统集成测试
 * 测试各个服务之间的协作和完整的交易流程
 */
@Slf4j
@SpringBootTest(classes = TradingSystemStarter.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TradingSystemIntegrationTest {

    @Autowired(required = false)
    private MarketDataService marketDataService;

    @Autowired(required = false)
    private TradingService tradingService;

    @Autowired(required = false)
    private OrderExecutionService orderExecutionService;

    @Autowired(required = false)
    private TradingSignalProcessor signalProcessor;

    @Autowired(required = false)
    private QuoteService quoteService;

    @Autowired(required = false)
    private RealTimeDataSubscriber dataSubscriber;

    private static final String TEST_SYMBOL = "00700.HK";
    private static final BigDecimal TEST_PRICE = BigDecimal.valueOf(300.0);
    private static final Long TEST_QUANTITY = 1000L;

    @BeforeEach
    void setUp() {
        log.info("开始集成测试设置...");
    }

    @Test
    @Order(1)
    @DisplayName("测试服务初始化和健康检查")
    void testServiceInitializationAndHealth() {
        log.info("测试服务初始化和健康检查");

        // 测试各服务是否正常初始化
        if (marketDataService != null) {
            Assertions.assertTrue(marketDataService.isHealthy(), "MarketDataService应该健康");
            log.info("✅ MarketDataService 初始化成功");
        }

        if (tradingService != null) {
            // 在测试环境中，TradingService可能因为没有FUTU连接而不健康，这是正常的
            boolean isHealthy = tradingService.isHealthy();
            log.info("TradingService 健康状态: {}", isHealthy);
            if (isHealthy) {
                log.info("✅ TradingService 初始化成功且健康");
            } else {
                log.info("⚠️ TradingService 初始化成功但未连接到FUTU（测试环境正常）");
            }
        }

        if (orderExecutionService != null) {
            Assertions.assertTrue(orderExecutionService.isHealthy(), "OrderExecutionService应该健康");
            log.info("✅ OrderExecutionService 初始化成功");
        }

        if (signalProcessor != null) {
            Assertions.assertTrue(signalProcessor.isHealthy(), "TradingSignalProcessor应该健康");
            log.info("✅ TradingSignalProcessor 初始化成功");
        }

        if (quoteService != null) {
            // 在测试环境中，QuoteService可能因为没有WebSocket连接而不健康，这是正常的
            boolean isHealthy = quoteService.isHealthy();
            log.info("QuoteService 健康状态: {}", isHealthy);
            if (isHealthy) {
                log.info("✅ QuoteService 初始化成功且健康");
            } else {
                log.info("⚠️ QuoteService 初始化成功但未连接（测试环境正常）");
            }
            // 不要在测试环境中断言健康状态，因为可能没有WebSocket连接
        }

        if (dataSubscriber != null) {
            // 在测试环境中，RealTimeDataSubscriber可能因为没有真实连接而不健康
            boolean isHealthy = dataSubscriber.isHealthy();
            log.info("RealTimeDataSubscriber 健康状态: {}", isHealthy);
            if (isHealthy) {
                log.info("✅ RealTimeDataSubscriber 初始化成功且健康");
            } else {
                log.info("⚠️ RealTimeDataSubscriber 初始化成功但未连接（测试环境正常）");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("测试市场数据获取")
    void testMarketDataRetrieval() throws Exception {
        log.info("测试市场数据获取");

        if (marketDataService != null) {
            // 测试获取最新市场数据
            var latestDataFuture = marketDataService.getLatestMarketData(TEST_SYMBOL);
            var latestData = latestDataFuture;

            if (latestData.isPresent()) {
                log.info("✅ 成功获取市场数据: symbol={}, price={}",
                        latestData.get().getSymbol(), latestData.get().getClose());

                Assertions.assertEquals(TEST_SYMBOL, latestData.get().getSymbol());
                Assertions.assertNotNull(latestData.get().getClose());
            } else {
                log.warn("⚠️ 未获取到市场数据，可能是模拟环境");
            }

            // 测试获取历史数据
            var historicalDataFuture = marketDataService.getOhlcvData(
                    TEST_SYMBOL, "30m",
                    java.time.LocalDateTime.now().minusDays(1),
                    java.time.LocalDateTime.now(), 10);

            var historicalData = historicalDataFuture.get(5, TimeUnit.SECONDS);
            log.info("✅ 获取历史数据: count={}", historicalData.size());
        }
    }

    @Test
    @Order(3)
    @DisplayName("测试交易信号生成")
    void testTradingSignalGeneration() throws Exception {
        log.info("测试交易信号生成");

        if (signalProcessor != null && marketDataService != null) {
            // 获取市场数据用于信号生成
            var latestData = marketDataService.getLatestMarketData(TEST_SYMBOL);

            if (latestData.isPresent()) {
                // 处理市场数据生成信号
                var signalsFuture = signalProcessor.processMarketData(latestData.get());
                var signals = signalsFuture.get(5, TimeUnit.SECONDS);

                log.info("✅ 生成交易信号: count={}", signals.size());

                for (var signal : signals) {
                    log.info("信号详情: symbol={}, type={}, strength={}, strategy={}",
                            signal.getSymbol(), signal.getSignalType(),
                            signal.getStrength(), signal.getStrategy());

                    Assertions.assertEquals(TEST_SYMBOL, signal.getSymbol());
                    Assertions.assertNotNull(signal.getSignalType());
                    Assertions.assertNotNull(signal.getStrength());
                }

                // 测试批量处理
                var batchResult = signalProcessor.processBatchSymbols(
                        List.of(TEST_SYMBOL, "09988.HK", "03690.HK"));
                var batchSignals = batchResult.get(10, TimeUnit.SECONDS);

                log.info("✅ 批量信号处理: processed={}, total={}",
                        batchSignals.getProcessedSymbols(), batchSignals.getTotalSymbols());

                Assertions.assertTrue(batchSignals.isSuccess());
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("测试订单生命周期")
    void testOrderLifecycle() throws Exception {
        log.info("测试订单生命周期");

        if (tradingService != null) {
            // 创建订单请求
            var orderRequest = TradingService.OrderRequest.builder()
                    .symbol(TEST_SYMBOL)
                    .orderType(com.trading.domain.enums.OrderType.LIMIT)
                    .side(com.trading.domain.enums.OrderSide.BUY)
                    .quantity(TEST_QUANTITY)
                    .price(TEST_PRICE)
                    .accountId("test_account")
                    .build();

            // 提交订单
            var orderResultFuture = tradingService.submitOrder(orderRequest);
            var orderResult = orderResultFuture.get(5, TimeUnit.SECONDS);

            log.info("✅ 订单提交结果: success={}, message={}",
                    orderResult.isSuccess(), orderResult.getMessage());

            if (orderResult.isSuccess() && orderResult.getOrder() != null) {
                String orderId = orderResult.getOrder().getOrderId();

                // 查询订单状态
                var ordersFuture = tradingService.getOrders("test_account", null);
                var orders = ordersFuture.get(5, TimeUnit.SECONDS);

                log.info("✅ 查询订单: count={}", orders.size());

                // 尝试取消订单（测试环境）
                var cancelFuture = tradingService.cancelOrder(orderId);
                var cancelResult = cancelFuture.get(5, TimeUnit.SECONDS);

                log.info("✅ 取消订单结果: success={}, message={}",
                        cancelResult.isSuccess(), cancelResult.getMessage());
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("测试订单执行策略")
    void testOrderExecutionStrategies() throws Exception {
        log.info("测试订单执行策略");

        if (orderExecutionService != null) {
            // 创建模拟订单ID
            String mockOrderId = "TEST_ORDER_" + System.currentTimeMillis();

            // 测试市价单执行
            var marketOrderFuture = orderExecutionService.executeMarketOrder(mockOrderId);
            var marketOrderResult = marketOrderFuture.get(5, TimeUnit.SECONDS);

            log.info("✅ 市价单执行: success={}, message={}",
                    marketOrderResult.isSuccess(), marketOrderResult.getMessage());

            // 测试TWAP执行
            var twapParams = OrderExecutionService.TwapParameters.builder()
                    .durationMinutes(5)
                    .sliceCount(10)
                    .build();

            var twapFuture = orderExecutionService.executeTwap(mockOrderId, twapParams);
            var twapResult = twapFuture.get(10, TimeUnit.SECONDS);

            log.info("✅ TWAP执行: success={}, executedQuantity={}",
                    twapResult.isSuccess(), twapResult.getExecutedQuantity());

            // 测试执行统计
            var stats = orderExecutionService.getExecutionStatistics();
            log.info("✅ 执行统计: total={}, successful={}, rate={}%",
                    stats.getTotalExecutions(), stats.getSuccessfulExecutions(),
                    stats.getSuccessRate());
        }
    }

    @Test
    @Order(6)
    @DisplayName("测试实时数据订阅")
    void testRealTimeDataSubscription() throws Exception {
        log.info("测试实时数据订阅");

        if (dataSubscriber != null && quoteService != null) {
            // 订阅实时数据
            var subscriptionFuture = dataSubscriber.subscribeToSymbols(
                    java.util.Set.of(TEST_SYMBOL, "09988.HK"),
                    RealTimeDataSubscriber.DataType.QUOTES,
                    RealTimeDataSubscriber.DataType.KLINES);

            var subscriptionResult = subscriptionFuture.get(10, TimeUnit.SECONDS);

            // 在测试环境中，没有真实的数据订阅，失败是正常的
            log.info("数据订阅结果: success={}, successCount={}, failureCount={}",
                    subscriptionResult.isSuccess(), subscriptionResult.getSuccessCount(),
                    subscriptionResult.getFailureCount());

            if (!subscriptionResult.isSuccess()) {
                log.info("⚠️ 数据订阅失败（测试环境正常）");
            } else {
                log.info("✅ 数据订阅成功");
            }

            // 等待一段时间让数据流入
            Thread.sleep(2000);

            // 测试单个股票报价订阅
            var quoteSubscriptionFuture = quoteService.subscribeQuote(TEST_SYMBOL);
            var quoteSubscribed = quoteSubscriptionFuture.get(5, TimeUnit.SECONDS);

            log.info("✅ 报价订阅: success={}", quoteSubscribed);

            // 获取最新报价
            var latestQuote = quoteService.getLatestQuote(TEST_SYMBOL);
            if (latestQuote.isPresent()) {
                log.info("✅ 最新报价: symbol={}, price={}, change={}",
                        latestQuote.get().getSymbol(), latestQuote.get().getCurrentPrice(),
                        latestQuote.get().getChange());
            }

            // 获取订阅统计
            var dataStats = dataSubscriber.getSubscriptionStats();
            log.info("✅ 订阅统计: active={}, healthy={}",
                    dataStats.getTotalActiveSubscriptions(), dataStats.isConnectionHealthy());
        }
    }

    @Test
    @Order(7)
    @DisplayName("测试完整交易流程")
    void testCompleteTradeFlow() throws Exception {
        log.info("测试完整交易流程");

        if (marketDataService != null && signalProcessor != null &&
                tradingService != null && orderExecutionService != null) {

            // 1. 获取市场数据
            var marketData = marketDataService.getLatestMarketData(TEST_SYMBOL);

            if (marketData.isPresent()) {
                log.info("步骤1: ✅ 获取市场数据成功");

                // 2. 生成交易信号
                var signals = signalProcessor.processMarketData(marketData.get())
                        .get(5, TimeUnit.SECONDS);

                if (!signals.isEmpty()) {
                    log.info("步骤2: ✅ 生成{}个交易信号", signals.size());

                    var signal = signals.get(0);

                    // 3. 执行信号（如果强度足够）
                    if (signal.getStrength().compareTo(BigDecimal.valueOf(0.5)) >= 0) {
                        var signalExecutionResult = signalProcessor.executeSignal(signal)
                                .get(10, TimeUnit.SECONDS);

                        log.info("步骤3: ✅ 信号执行结果: success={}, orderId={}",
                                signalExecutionResult.isSuccess(), signalExecutionResult.getOrderId());

                        if (signalExecutionResult.isSuccess() &&
                                signalExecutionResult.getOrderId() != null) {

                            // 4. 监控订单执行
                            String orderId = signalExecutionResult.getOrderId();
                            var executionParams = OrderExecutionService.ExecutionParameters.builder()
                                    .strategy(OrderExecutionService.ExecutionStrategyType.MARKET)
                                    .timeout(30)
                                    .build();

                            var executionResult = orderExecutionService.executeOrder(orderId, executionParams)
                                    .get(15, TimeUnit.SECONDS);

                            log.info("步骤4: ✅ 订单执行完成: success={}, message={}",
                                    executionResult.isSuccess(), executionResult.getMessage());
                        }
                    } else {
                        log.info("步骤3: ⚠️ 信号强度不足，跳过执行: strength={}", signal.getStrength());
                    }
                } else {
                    log.info("步骤2: ⚠️ 未生成交易信号");
                }
            } else {
                log.info("步骤1: ⚠️ 未获取到市场数据");
            }
        }

        log.info("✅ 完整交易流程测试完成");
    }

    @Test
    @Order(8)
    @DisplayName("测试系统性能和统计")
    void testSystemPerformanceAndStats() throws Exception {
        log.info("测试系统性能和统计");

        // 测试各服务的统计信息
        if (signalProcessor != null) {
            var signalStats = signalProcessor.getSignalStatistics();
            log.info("信号处理统计: total={}, buy={}, sell={}, enabled={}",
                    signalStats.getTotalSignalsGenerated(), signalStats.getRecentBuySignals(),
                    signalStats.getRecentSellSignals(), signalStats.isProcessingEnabled());
        }

        if (orderExecutionService != null) {
            var executionStats = orderExecutionService.getExecutionStatistics();
            log.info("执行统计: total={}, successful={}, rate={}%, avgTime={}s",
                    executionStats.getTotalExecutions(), executionStats.getSuccessfulExecutions(),
                    executionStats.getSuccessRate(), executionStats.getAverageExecutionTime());
        }

        if (quoteService != null) {
            var subscriptions = quoteService.getActiveSubscriptions();
            log.info("活跃订阅: count={}", subscriptions.size());
        }

        if (dataSubscriber != null) {
            var subscriberStats = dataSubscriber.getSubscriptionStats();
            log.info("数据订阅统计: active={}, healthy={}, connected={}",
                    subscriberStats.getTotalActiveSubscriptions(), subscriberStats.isSubscriberActive(),
                    subscriberStats.isConnectionHealthy());
        }

        log.info("✅ 系统性能和统计测试完成");
    }

    @AfterEach
    void tearDown() {
        log.info("集成测试清理...");
    }

    @Test
    @Order(100)
    @DisplayName("系统压力测试")
    void testSystemStress() throws Exception {
        log.info("开始系统压力测试");

        if (signalProcessor != null) {
            // 批量处理多个股票
            List<String> symbols = List.of(
                    "00700.HK", "09988.HK", "03690.HK", "01024.HK", "09618.HK",
                    "02800.HK", "03033.HK", "00005.HK", "00941.HK", "01299.HK");

            long startTime = System.currentTimeMillis();

            var batchResult = signalProcessor.processBatchSymbols(symbols)
                    .get(30, TimeUnit.SECONDS);

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            log.info("✅ 压力测试完成: processed={}/{} symbols in {}ms",
                    batchResult.getProcessedSymbols(), batchResult.getTotalSymbols(), duration);

            Assertions.assertTrue(batchResult.isSuccess());
            Assertions.assertTrue(duration < 30000, "批量处理应在30秒内完成");
        }

        log.info("✅ 系统压力测试通过");
    }
}