package com.trading.service;

import com.trading.domain.entity.MarketData;
import com.trading.infrastructure.cache.CacheService;
import com.trading.infrastructure.futu.FutuConnectionManager;
import com.trading.infrastructure.futu.SimpleFutuMarketDataProvider;
import com.trading.repository.MarketDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MarketDataService单元测试
 */
@ExtendWith(MockitoExtension.class)
class MarketDataServiceTest {
    
    @Mock
    private FutuConnectionManager connectionManager;
    
    @Mock
    private MarketDataRepository marketDataRepository;
    
    @Mock
    private CacheService cacheService;
    
    @Mock
    private SimpleFutuMarketDataProvider futuDataProvider;
    
    @InjectMocks
    private MarketDataService marketDataService;
    
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String testSymbol = "02800.HK";
    
    @BeforeEach
    void setUp() {
        startTime = LocalDateTime.of(2024, 1, 1, 9, 0);
        endTime = LocalDateTime.of(2024, 1, 31, 16, 0);
    }
    
    /**
     * 测试从FUTU API获取市场数据
     */
    @Test
    void testMarketDataRetrieval() throws Exception {
        // 准备测试数据
        List<SimpleFutuMarketDataProvider.KLineData> mockKLineData = createMockKLineData();
        
        // 模拟FUTU连接状态
        when(connectionManager.isQuoteConnected()).thenReturn(true);
        when(futuDataProvider.isConnected()).thenReturn(true);
        
        // 模拟FUTU数据提供者返回数据
        when(futuDataProvider.getHistoricalData(
            eq(testSymbol), 
            any(LocalDate.class), 
            any(LocalDate.class)
        )).thenReturn(CompletableFuture.completedFuture(mockKLineData));
        
        // 执行测试
        CompletableFuture<List<MarketData>> future = marketDataService.getOhlcvData(
            testSymbol, "1d", startTime, endTime, 100
        );
        
        List<MarketData> result = future.get();
        
        // 验证结果
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
        assertThat(result.size()).isLessThanOrEqualTo(100);
        
        // 验证数据内容
        MarketData firstData = result.get(0);
        assertThat(firstData.getSymbol()).isEqualTo(testSymbol);
        assertThat(firstData.getOpen()).isNotNull();
        assertThat(firstData.getHigh()).isNotNull();
        assertThat(firstData.getLow()).isNotNull();
        assertThat(firstData.getClose()).isNotNull();
        assertThat(firstData.getVolume()).isGreaterThan(0);
        
        // 验证方法调用
        verify(futuDataProvider, times(1)).getHistoricalData(
            eq(testSymbol), 
            any(LocalDate.class), 
            any(LocalDate.class)
        );
        
        // 验证数据保存
        verify(marketDataRepository, times(1)).saveAll(anyList());
    }
    
    /**
     * 测试当FUTU未连接时使用模拟数据
     */
    @Test
    void testMarketDataRetrievalWithSimulatedData() throws Exception {
        // 模拟FUTU未连接
        when(connectionManager.isQuoteConnected()).thenReturn(false);
        when(futuDataProvider.isConnected()).thenReturn(false);
        
        // 执行测试
        CompletableFuture<List<MarketData>> future = marketDataService.getOhlcvData(
            testSymbol, "1d", startTime, endTime, 10
        );
        
        List<MarketData> result = future.get();
        
        // 验证返回模拟数据
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
        assertThat(result.size()).isLessThanOrEqualTo(10);
        
        // 验证是模拟数据（应该有合理的价格范围）
        MarketData firstData = result.get(0);
        assertThat(firstData.getSymbol()).isEqualTo(testSymbol);
        assertThat(firstData.getClose()).isBetween(
            BigDecimal.valueOf(20), 
            BigDecimal.valueOf(30)
        ); // ETF 02800的合理价格范围
        
        // 验证没有调用FUTU API
        verify(futuDataProvider, never()).getHistoricalData(
            anyString(), 
            any(LocalDate.class), 
            any(LocalDate.class)
        );
    }
    
    /**
     * 测试从缓存获取数据
     */
    @Test
    void testMarketDataRetrievalFromCache() throws Exception {
        // 准备缓存数据
        MarketData cachedData = createMockMarketData();
        
        // 模拟缓存命中
        when(connectionManager.isQuoteConnected()).thenReturn(true);
        when(cacheService.getConfiguration(anyString(), any())).thenReturn(List.of(cachedData));
        
        // 执行测试
        CompletableFuture<List<MarketData>> future = marketDataService.getOhlcvData(
            testSymbol, "1d", startTime, endTime, 1
        );
        
        List<MarketData> result = future.get();
        
        // 验证返回缓存数据
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(cachedData);
    }
    
    /**
     * 测试获取实时价格
     */
    @Test
    void testGetCurrentPrice() throws Exception {
        // 准备测试数据
        BigDecimal mockPrice = BigDecimal.valueOf(25.50);
        
        // 模拟FUTU连接并返回价格
        when(futuDataProvider.isConnected()).thenReturn(true);
        when(futuDataProvider.getRealtimePrice(testSymbol))
            .thenReturn(CompletableFuture.completedFuture(mockPrice));
        
        // 执行测试
        CompletableFuture<MarketDataService.PriceData> future = 
            marketDataService.getCurrentPrice(testSymbol);
        
        MarketDataService.PriceData result = future.get();
        
        // 验证结果
        assertThat(result).isNotNull();
        assertThat(result.getSymbol()).isEqualTo(testSymbol);
        assertThat(result.getPrice()).isEqualTo(mockPrice);
        assertThat(result.getTimestamp()).isNotNull();
        
        // 验证方法调用
        verify(futuDataProvider, times(1)).getRealtimePrice(testSymbol);
    }
    
    /**
     * 测试获取最新市场数据
     */
    @Test
    void testGetLatestMarketData() {
        // 准备测试数据
        MarketData latestData = createMockMarketData();
        
        // 模拟从数据库获取
        when(marketDataRepository.findTopBySymbolOrderByTimestampDesc(testSymbol))
            .thenReturn(Optional.of(latestData));
        
        // 执行测试
        Optional<MarketData> result = marketDataService.getLatestMarketData(testSymbol);
        
        // 验证结果
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(latestData);
        
        // 验证方法调用
        verify(marketDataRepository, times(1))
            .findTopBySymbolOrderByTimestampDesc(testSymbol);
    }
    
    /**
     * 测试批量获取历史数据（分页）
     */
    @Test
    void testGetHistoricalDataWithPagination() throws Exception {
        // 准备多页数据
        List<SimpleFutuMarketDataProvider.KLineData> page1 = createMockKLineData(5);
        List<SimpleFutuMarketDataProvider.KLineData> page2 = createMockKLineData(5);
        
        // 模拟FUTU连接
        when(connectionManager.isQuoteConnected()).thenReturn(true);
        when(futuDataProvider.isConnected()).thenReturn(true);
        
        // 模拟分页返回
        when(futuDataProvider.getHistoricalData(
            eq(testSymbol), 
            any(LocalDate.class), 
            any(LocalDate.class)
        ))
        .thenReturn(CompletableFuture.completedFuture(page1))
        .thenReturn(CompletableFuture.completedFuture(page2));
        
        // 执行测试
        CompletableFuture<List<MarketData>> future = 
            marketDataService.getHistoricalDataWithPagination(
                testSymbol, "1d", startTime, endTime, 5
            );
        
        List<MarketData> result = future.get();
        
        // 验证结果
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
        
        // 验证保存到数据库
        verify(marketDataRepository, atLeastOnce()).saveAll(anyList());
    }
    
    /**
     * 测试服务健康检查
     */
    @Test
    void testIsHealthy() {
        // 场景1: FUTU连接正常
        when(connectionManager.isQuoteConnected()).thenReturn(true);
        assertThat(marketDataService.isHealthy()).isTrue();
        
        // 场景2: FUTU未连接但数据库可用
        when(connectionManager.isQuoteConnected()).thenReturn(false);
        when(marketDataRepository.count()).thenReturn(100L);
        assertThat(marketDataService.isHealthy()).isTrue();
        
        // 场景3: 都不可用
        when(connectionManager.isQuoteConnected()).thenReturn(false);
        when(marketDataRepository.count()).thenThrow(new RuntimeException("DB Error"));
        assertThat(marketDataService.isHealthy()).isFalse();
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 创建模拟K线数据
     */
    private List<SimpleFutuMarketDataProvider.KLineData> createMockKLineData() {
        return createMockKLineData(10);
    }
    
    private List<SimpleFutuMarketDataProvider.KLineData> createMockKLineData(int count) {
        List<SimpleFutuMarketDataProvider.KLineData> klineList = new ArrayList<>();
        LocalDateTime time = startTime;
        
        for (int i = 0; i < count; i++) {
            SimpleFutuMarketDataProvider.KLineData kline = new SimpleFutuMarketDataProvider.KLineData();
            kline.setTime(time);
            kline.setOpen(BigDecimal.valueOf(24.5 + i * 0.1));
            kline.setHigh(BigDecimal.valueOf(24.8 + i * 0.1));
            kline.setLow(BigDecimal.valueOf(24.2 + i * 0.1));
            kline.setClose(BigDecimal.valueOf(24.6 + i * 0.1));
            kline.setVolume(1000000L + i * 10000);
            kline.setTurnover(BigDecimal.valueOf(24600000 + i * 100000));
            
            klineList.add(kline);
            time = time.plusDays(1);
        }
        
        return klineList;
    }
    
    /**
     * 创建模拟市场数据
     */
    private MarketData createMockMarketData() {
        return MarketData.builder()
            .id(testSymbol + "_20240101_090000")
            .symbol(testSymbol)
            .open(BigDecimal.valueOf(24.50))
            .high(BigDecimal.valueOf(24.80))
            .low(BigDecimal.valueOf(24.20))
            .close(BigDecimal.valueOf(24.60))
            .volume(1000000L)
            .turnover(BigDecimal.valueOf(24600000))
            .timestamp(startTime)
            .timeframe("1d")
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }
}