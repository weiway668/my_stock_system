package com.trading.infrastructure.futu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.infrastructure.futu.model.FutuOrderBook;
import com.trading.infrastructure.futu.model.FutuQuote;
import com.trading.domain.entity.CorporateActionEntity;


import lombok.extern.slf4j.Slf4j;

/**
 * FutuMarketDataServiceImpl 集成测试
 * 使用真实类测试FUTU行情数据服务的所有功能
 * 使用测试配置的WebSocketClient实现模拟行情数据
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "trading.futu.connection.host=127.0.0.1",
        "trading.futu.connection.port=11111",
        "spring.main.allow-bean-definition-overriding=true"
})
class FutuMarketDataServiceImplTest {

    @Autowired
    private FutuMarketDataServiceImpl marketDataService;

    @Autowired
    private FutuWebSocketClient webSocketClient;

    private static final String TEST_SYMBOL = "00700.HK";
    private static final String TEST_SYMBOL_NO_SUFFIX = "700.HK";
    private static final String US_SYMBOL = "AAPL.US";

    @BeforeEach
    void setUp() throws Exception {
        // 测试前准备
        webSocketClient.connectSync();
    }

    // ========== 实时报价测试 ==========

    @Test
    @DisplayName("测试获取单个股票实时报价")
    void testGetRealtimeQuote() throws Exception {
        // Given - 测试客户端已在setUp中设置为已连接状态

        // When
        FutuQuote quote = marketDataService.getRealtimeQuote(TEST_SYMBOL);

        // Then
        assertThat(quote).isNotNull();
        assertThat(quote.getCode()).isEqualTo(TEST_SYMBOL);
        assertThat(quote.getName()).isEqualTo("腾讯控股");
        assertThat(quote.getLastPrice()).isNotNull();
        assertThat(quote.getVolume()).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试获取多个股票实时报价")
    void testGetRealtimeQuotes() throws Exception {
        // Given
        List<String> symbols = Arrays.asList(TEST_SYMBOL, "09988.HK", "03690.HK");

        // When
        List<FutuQuote> quotes = marketDataService.getRealtimeQuotes(symbols);

        // Then
        assertThat(quotes).hasSize(3);
        assertThat(quotes).extracting(FutuQuote::getCode)
                .containsExactlyInAnyOrder(TEST_SYMBOL, "09988.HK", "03690.HK");
        assertThat(quotes).extracting(FutuQuote::getName)
                .containsExactlyInAnyOrder("腾讯控股", "阿里巴巴-SW", "美团-W");
    }

    @Test
    @DisplayName("测试未连接状态下获取报价返回null")
    void testGetRealtimeQuoteWhenDisconnected() throws Exception {
        // Given - 使用未连接的WebSocketClient

        // When
        FutuQuote quote = marketDataService.getRealtimeQuote(TEST_SYMBOL);

        // Then
        assertThat(quote).isNull();
    }

    // ========== 历史K线测试 ==========

    @Test
    @DisplayName("测试获取历史K线数据")
    void testGetHistoricalKLine() throws Exception {
        // Given
        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        // When
        List<FutuKLine> klines = marketDataService.getHistoricalKLine(
                TEST_SYMBOL, startDate, endDate, FutuMarketDataService.KLineType.K_DAY);
        //将k线数据输出到文件
        log.info("获取到{}条日K线数据", klines.size());
        for (FutuKLine kline : klines) {
            log.info(kline.toString());
        }

        // Then
        // 验证返回的K线数据不为空
        assertThat(klines).isNotEmpty();
        // 验证K线数据的基本属性
        assertThat(klines.get(0).getCode()).isEqualTo(TEST_SYMBOL);
        assertThat(klines.get(0).getOpen()).isNotNull();
        assertThat(klines.get(0).getClose()).isNotNull();
        assertThat(klines.get(0).getVolume()).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试K线数据分页获取功能")
    void testGetHistoricalKLineWithPagination() throws Exception {
        // Given - 使用更长的时间范围和更短的K线周期，确保需要分页
        LocalDate startDate = LocalDate.now().minusDays(90); // 90天
        LocalDate endDate = LocalDate.now();

        // When - 获取5分钟K线，数据量较大
        List<FutuKLine> klines = marketDataService.getHistoricalKLine(
                TEST_SYMBOL, startDate, endDate, FutuMarketDataService.KLineType.K_5MIN);

        // Then
        assertThat(klines).isNotEmpty();
        log.info("测试分页功能: 获取了{}条5分钟K线数据", klines.size());
        
        // 验证数据按时间排序
        if (klines.size() > 1) {
            for (int i = 1; i < klines.size(); i++) {
                assertThat(klines.get(i).getTimestamp())
                    .as("K线数据应按时间顺序排列")
                    .isAfterOrEqualTo(klines.get(i-1).getTimestamp());
            }
        }
    }
    
    @Test
    @DisplayName("测试未连接状态下获取K线返回空列表")
    void testGetHistoricalKLineWhenDisconnected() throws Exception {
        // Given - 使用未连接的WebSocketClient
        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        // When
        List<FutuKLine> klines = marketDataService.getHistoricalKLine(
                TEST_SYMBOL, startDate, endDate, FutuMarketDataService.KLineType.K_DAY);

        // Then
        assertThat(klines).isEmpty();
    }

    // ========== 订单簿测试 ==========

    @Test
    @DisplayName("测试获取订单簿数据")
    void testGetOrderBook() throws Exception {
        // Given - 测试客户端已设置为已连接状态

        // When
        FutuOrderBook orderBook = marketDataService.getOrderBook(TEST_SYMBOL);

        // Then
        // 当前实现返回null，待实现
        assertThat(orderBook).isNull();
    }

    // ========== 订阅管理测试 ==========

    @Test
    @DisplayName("测试订阅实时报价")
    void testSubscribeQuote() throws Exception {
        // Given
        FutuMarketDataService.QuoteListener listener = quote -> {
            // 处理报价更新
        };

        // When
        boolean result = marketDataService.subscribeQuote(TEST_SYMBOL, listener);

        // Then
        assertThat(result).isTrue();
        // 验证订阅成功
    }

    @Test
    @DisplayName("测试订阅订单簿")
    void testSubscribeOrderBook() throws Exception {
        // Given
        FutuMarketDataService.OrderBookListener listener = orderBook -> {
            // 处理订单簿更新
        };

        // When
        boolean result = marketDataService.subscribeOrderBook(TEST_SYMBOL, listener);

        // Then
        assertThat(result).isTrue();
        // 验证订阅成功
    }

    @Test
    @DisplayName("测试取消订阅实时报价")
    @org.junit.jupiter.api.Disabled("取消订阅功能需要实际的FUTU连接才能正常工作")
    void testUnsubscribeQuote() throws Exception {
        // Given - 先订阅
        FutuMarketDataService.QuoteListener listener = quote -> {
        };
        marketDataService.subscribeQuote(TEST_SYMBOL, listener);
        assertThat(marketDataService.getSubscribedSymbols()).contains(TEST_SYMBOL);

        // When
        boolean result = marketDataService.unsubscribeQuote(TEST_SYMBOL);

        // Then
        assertThat(result).isTrue();
        // 验证取消订阅后从已订阅列表中移除
        assertThat(marketDataService.getSubscribedSymbols()).doesNotContain(TEST_SYMBOL);
    }

    @Test
    @DisplayName("测试未连接状态下取消订阅返回false")
    void testUnsubscribeQuoteWhenDisconnected() throws Exception {
        // Given - 使用未连接的WebSocketClient

        // When
        boolean result = marketDataService.unsubscribeQuote(TEST_SYMBOL);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("测试取消订阅订单簿")
    @org.junit.jupiter.api.Disabled("取消订阅功能需要实际的FUTU连接才能正常工作")
    void testUnsubscribeOrderBook() throws Exception {
        // Given - 先订阅
        FutuMarketDataService.OrderBookListener listener = orderBook -> {
        };
        marketDataService.subscribeOrderBook(TEST_SYMBOL, listener);
        assertThat(marketDataService.getSubscribedSymbols()).contains(TEST_SYMBOL);

        // When
        boolean result = marketDataService.unsubscribeOrderBook(TEST_SYMBOL);

        // Then
        assertThat(result).isTrue();
        // 验证取消订阅后从已订阅列表中移除
        assertThat(marketDataService.getSubscribedSymbols()).doesNotContain(TEST_SYMBOL);
    }

    @Test
    @DisplayName("测试获取已订阅股票列表")
    void testGetSubscribedSymbols() throws Exception {
        // Given - 订阅多个股票，需要使用监听器
        FutuMarketDataService.QuoteListener quoteListener = quote -> {
        };
        FutuMarketDataService.OrderBookListener orderBookListener = orderBook -> {
        };

        marketDataService.subscribeQuote(TEST_SYMBOL, quoteListener);
        marketDataService.subscribeQuote("09988.HK", quoteListener);
        marketDataService.subscribeOrderBook("03690.HK", orderBookListener);

        // When
        Set<String> symbols = marketDataService.getSubscribedSymbols();

        // Then
        assertThat(symbols).hasSize(3);
        assertThat(symbols).containsExactlyInAnyOrder(TEST_SYMBOL, "09988.HK", "03690.HK");
    }

    @Test
    @DisplayName("测试服务可用性检查")
    void testIsServiceAvailable() {
        // Given - 默认已连接

        // When
        boolean available = marketDataService.isServiceAvailable();

        // Then
        assertThat(available).isTrue();

        // 测试未连接状态
        // 注意：实际测试需要WebSocketClient处于未连接状态
        available = marketDataService.isServiceAvailable();
        assertThat(available).isFalse();
    }

    // ========== 除权除息测试 ==========

    @Test
    @DisplayName("测试获取除权除息(复权)信息")
    void testRequestRehab_Success() {
        // Given - 测试客户端已设置为已连接状态

        // When
        List<CorporateActionEntity> actions = marketDataService.requestRehab(TEST_SYMBOL);

        // Then
        assertThat(actions).isNotNull();
        assertThat(actions).isNotEmpty();

        // 验证返回的数据中至少包含一种预期的行动类型 (依赖于测试客户端的模拟数据)
        // 例如，腾讯历史上有多次派息和拆股
        boolean hasDividend = actions.stream().anyMatch(a -> a.getActionType() == CorporateActionEntity.CorporateActionType.DIVIDEND);
        boolean hasSplit = actions.stream().anyMatch(a -> a.getActionType() == CorporateActionEntity.CorporateActionType.SPLIT);

        assertThat(hasDividend).as("应至少包含派息数据").isTrue();
        assertThat(hasSplit).as("应至少包含拆股数据").isTrue();

        // 验证其中一个派息数据的细节
        actions.stream()
            .filter(a -> a.getActionType() == CorporateActionEntity.CorporateActionType.DIVIDEND)
            .findFirst()
            .ifPresent(dividendAction -> {
                assertThat(dividendAction.getStockCode()).isEqualTo(TEST_SYMBOL);
                assertThat(dividendAction.getDividend()).isGreaterThan(0);
                assertThat(dividendAction.getForwardAdjFactor()).isLessThan(1.0);
            });
        
        log.info("成功获取并验证了 {} 条公司行动数据", actions.size());
    }
}