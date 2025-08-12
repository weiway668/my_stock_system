package com.trading.service;

import com.trading.domain.entity.MarketData;
import com.trading.infrastructure.cache.CacheService;
import com.trading.infrastructure.futu.FutuConnectionManager;
import com.trading.infrastructure.futu.client.FutuApiClient;
import com.trading.infrastructure.futu.model.FutuQuote;
import com.trading.infrastructure.futu.protocol.FutuProtocol;
import com.trading.infrastructure.futu.protocol.FutuProtobufSerializer;
import com.trading.infrastructure.config.FutuProperties;
import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quote Service
 * Handles real-time quote data subscriptions and streaming
 * Corresponds to Python FutuQuoteClient in the FUTU-API architecture
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.futu.connection.host")
public class QuoteService {

    private final FutuConnectionManager connectionManager;
    private final FutuApiClient futuApiClient;
    private final CacheService cacheService;
    private final ApplicationEventPublisher eventPublisher;
    private final FutuProperties futuProperties;
    private final FutuProtobufSerializer protobufSerializer;

    // Real-time quote management
    private final Map<String, QuoteSubscription> activeSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, RealtimeQuote> latestQuotes = new ConcurrentHashMap<>();
    private final ExecutorService quoteProcessingExecutor = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService quoteMonitoringExecutor = Executors.newScheduledThreadPool(2);
    
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private ScheduledFuture<?> quoteCacheCleanupTask;

    @PostConstruct
    public void initialize() {
        if (connectionManager.isQuoteConnected()) {
            startQuoteService();
        }
        
        // Start cache cleanup task
        startCacheCleanupTask();
        
        isInitialized.set(true);
        log.info("QuoteService initialized successfully");
    }

    /**
     * Subscribe to real-time quotes for a symbol
     */
    public CompletableFuture<Boolean> subscribeQuote(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!connectionManager.isQuoteConnected()) {
                    log.warn("Quote connection not available for subscription: {}", symbol);
                    return false;
                }

                if (activeSubscriptions.containsKey(symbol)) {
                    log.debug("Already subscribed to symbol: {}", symbol);
                    return true;
                }

                log.info("Subscribing to real-time quotes for symbol: {}", symbol);
                
                // TODO: Replace with actual FUTU SDK subscription
                // Example: quoteContext.subscribe(symbol, SubscriptionType.QUOTE, true);
                boolean subscriptionResult = simulateQuoteSubscription(symbol);
                
                if (subscriptionResult) {
                    QuoteSubscription subscription = QuoteSubscription.builder()
                        .symbol(symbol)
                        .subscriptionTime(LocalDateTime.now())
                        .isActive(true)
                        .build();
                    
                    activeSubscriptions.put(symbol, subscription);
                    
                    // Start quote data simulation/processing
                    startQuoteDataProcessing(symbol);
                    
                    log.info("Successfully subscribed to quotes for symbol: {}", symbol);
                    return true;
                } else {
                    log.error("Failed to subscribe to quotes for symbol: {}", symbol);
                    return false;
                }

            } catch (Exception e) {
                log.error("Error subscribing to quotes for symbol: {}", symbol, e);
                return false;
            }
        }, quoteProcessingExecutor);
    }

    /**
     * Unsubscribe from real-time quotes for a symbol
     */
    public CompletableFuture<Boolean> unsubscribeQuote(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                QuoteSubscription subscription = activeSubscriptions.get(symbol);
                if (subscription == null) {
                    log.debug("No active subscription found for symbol: {}", symbol);
                    return true;
                }

                log.info("Unsubscribing from real-time quotes for symbol: {}", symbol);
                
                // 使用FutuApiClient取消订阅
                boolean unsubscriptionResult = unsubscribeFromFutuQuote(symbol);
                
                if (unsubscriptionResult) {
                    // Mark subscription as inactive
                    subscription.setActive(false);
                    activeSubscriptions.remove(symbol);
                    
                    // Remove from latest quotes
                    latestQuotes.remove(symbol);
                    
                    log.info("Successfully unsubscribed from quotes for symbol: {}", symbol);
                    return true;
                } else {
                    log.error("Failed to unsubscribe from quotes for symbol: {}", symbol);
                    return false;
                }

            } catch (Exception e) {
                log.error("Error unsubscribing from quotes for symbol: {}", symbol, e);
                return false;
            }
        }, quoteProcessingExecutor);
    }

    /**
     * Get latest quote for a symbol
     */
    public Optional<RealtimeQuote> getLatestQuote(String symbol) {
        try {
            // Try memory cache first
            RealtimeQuote memoryQuote = latestQuotes.get(symbol);
            if (memoryQuote != null && isQuoteFresh(memoryQuote)) {
                return Optional.of(memoryQuote);
            }

            // Try Redis cache
            String cacheKey = "quote:" + symbol;
            RealtimeQuote cachedQuote = cacheService.get(cacheKey, RealtimeQuote.class);
            if (cachedQuote != null && isQuoteFresh(cachedQuote)) {
                // Update memory cache
                latestQuotes.put(symbol, cachedQuote);
                return Optional.of(cachedQuote);
            }

            log.debug("No fresh quote available for symbol: {}", symbol);
            return Optional.empty();

        } catch (Exception e) {
            log.error("Error getting latest quote for symbol: {}", symbol, e);
            return Optional.empty();
        }
    }

    /**
     * Get all active subscriptions
     */
    public Map<String, QuoteSubscription> getActiveSubscriptions() {
        return new HashMap<>(activeSubscriptions);
    }

    /**
     * Get subscription count
     */
    public int getSubscriptionCount() {
        return activeSubscriptions.size();
    }

    /**
     * Check if service is healthy
     */
    public boolean isHealthy() {
        return isInitialized.get() && 
               connectionManager.isQuoteConnected() && 
               quoteProcessingExecutor != null && 
               !quoteProcessingExecutor.isShutdown();
    }

    /**
     * Simulate quote subscription for development
     */
    private boolean simulateQuoteSubscription(String symbol) {
        try {
            log.debug("Simulating quote subscription for symbol: {}", symbol);
            
            // Simulate connection delay
            Thread.sleep(500);
            
            // In real implementation, this would call FUTU SDK
            // Example: return quoteContext.subscribe(symbol, SubscriptionType.QUOTE, true);
            
            return true; // Simulate successful subscription
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("Error in quote subscription simulation", e);
            return false;
        }
    }

    /**
     * Simulate quote unsubscription for development
     */
    private boolean simulateQuoteUnsubscription(String symbol) {
        try {
            log.debug("Simulating quote unsubscription for symbol: {}", symbol);
            
            // In real implementation, this would call FUTU SDK
            // Example: return quoteContext.unsubscribe(symbol, SubscriptionType.QUOTE);
            
            return true; // Simulate successful unsubscription
            
        } catch (Exception e) {
            log.error("Error in quote unsubscription simulation", e);
            return false;
        }
    }

    /**
     * Start quote data processing for a symbol
     */
    private void startQuoteDataProcessing(String symbol) {
        // Schedule periodic quote data updates (simulation)
        quoteMonitoringExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (activeSubscriptions.containsKey(symbol)) {
                    RealtimeQuote quote = generateSimulatedQuote(symbol);
                    processQuoteUpdate(quote);
                }
            } catch (Exception e) {
                log.error("Error processing quote data for symbol: {}", symbol, e);
            }
        }, 1, 2, TimeUnit.SECONDS); // Update every 2 seconds
    }

    /**
     * Process quote update
     */
    private void processQuoteUpdate(RealtimeQuote quote) {
        try {
            String symbol = quote.getSymbol();
            
            // Update memory cache
            latestQuotes.put(symbol, quote);
            
            // Update Redis cache
            String cacheKey = "quote:" + symbol;
            cacheService.cache(cacheKey, quote, 300); // 5 minutes expiry
            
            // Publish quote update event
            publishQuoteUpdateEvent(quote);
            
            log.trace("Processed quote update for symbol: {} at price: {}", 
                symbol, quote.getCurrentPrice());

        } catch (Exception e) {
            log.error("Error processing quote update", e);
        }
    }

    /**
     * Generate simulated real-time quote for development
     */
    private RealtimeQuote generateSimulatedQuote(String symbol) {
        // Get base price for the symbol
        BigDecimal basePrice = getBasePriceForSymbol(symbol);
        
        // Generate realistic price movement
        double changePercent = (Math.random() - 0.5) * 0.01; // ±0.5% change
        BigDecimal currentPrice = basePrice.multiply(BigDecimal.valueOf(1 + changePercent));
        BigDecimal change = currentPrice.subtract(basePrice);
        BigDecimal changePercentBd = change.divide(basePrice, 4, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        
        // Generate volume data
        long volume = (long) (1000 + Math.random() * 10000);
        BigDecimal turnover = currentPrice.multiply(BigDecimal.valueOf(volume));

        return RealtimeQuote.builder()
            .symbol(symbol)
            .currentPrice(currentPrice)
            .previousClose(basePrice)
            .change(change)
            .changePercent(changePercentBd)
            .high(currentPrice.multiply(BigDecimal.valueOf(1.005)))
            .low(currentPrice.multiply(BigDecimal.valueOf(0.995)))
            .volume(volume)
            .turnover(turnover)
            .timestamp(LocalDateTime.now())
            .build();
    }

    /**
     * Get base price for symbol (for simulation)
     */
    private BigDecimal getBasePriceForSymbol(String symbol) {
        return switch (symbol) {
            case "00700.HK", "700.HK" -> BigDecimal.valueOf(300.0); // Tencent
            case "2800.HK" -> BigDecimal.valueOf(24.5); // Tracker Fund
            case "3033.HK" -> BigDecimal.valueOf(3.8);  // Hang Seng Tech ETF
            case "09988.HK" -> BigDecimal.valueOf(85.0); // Alibaba
            case "03690.HK" -> BigDecimal.valueOf(120.0); // Meituan
            default -> BigDecimal.valueOf(100.0);
        };
    }

    /**
     * Check if quote is fresh (within acceptable time window)
     */
    private boolean isQuoteFresh(RealtimeQuote quote) {
        if (quote == null || quote.getTimestamp() == null) {
            return false;
        }
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime quoteTime = quote.getTimestamp();
        
        // Consider quote fresh if it's within last 5 minutes
        return quoteTime.isAfter(now.minusMinutes(5));
    }

    /**
     * Publish quote update event
     */
    private void publishQuoteUpdateEvent(RealtimeQuote quote) {
        try {
            // TODO: Create and publish QuoteUpdateEvent
            // eventPublisher.publishEvent(new QuoteUpdateEvent(quote));
            log.trace("Published quote update event for symbol: {}", quote.getSymbol());
        } catch (Exception e) {
            log.warn("Error publishing quote update event", e);
        }
    }

    /**
     * Start quote service
     */
    private void startQuoteService() {
        log.info("Starting quote service...");
        
        // TODO: Initialize FUTU quote context callbacks
        // Example: setupQuoteCallbacks();
        
        log.info("Quote service started successfully");
    }

    /**
     * Start cache cleanup task
     */
    private void startCacheCleanupTask() {
        quoteCacheCleanupTask = quoteMonitoringExecutor.scheduleWithFixedDelay(() -> {
            try {
                cleanupStaleQuotes();
            } catch (Exception e) {
                log.error("Error during quote cache cleanup", e);
            }
        }, 5, 5, TimeUnit.MINUTES); // Clean up every 5 minutes
    }

    /**
     * Clean up stale quotes from memory cache
     */
    private void cleanupStaleQuotes() {
        int initialSize = latestQuotes.size();
        
        latestQuotes.entrySet().removeIf(entry -> {
            RealtimeQuote quote = entry.getValue();
            return !isQuoteFresh(quote);
        });
        
        int removedCount = initialSize - latestQuotes.size();
        if (removedCount > 0) {
            log.debug("Cleaned up {} stale quotes from memory cache", removedCount);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down QuoteService...");
        
        try {
            // Cancel all tasks
            if (quoteCacheCleanupTask != null) {
                quoteCacheCleanupTask.cancel(false);
            }
            
            // Unsubscribe all active subscriptions
            List<CompletableFuture<Boolean>> unsubscribeFutures = activeSubscriptions.keySet()
                .stream()
                .map(this::unsubscribeQuote)
                .toList();
            
            // Wait for all unsubscriptions to complete
            CompletableFuture.allOf(unsubscribeFutures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);
            
            // Shutdown executors
            quoteProcessingExecutor.shutdown();
            quoteMonitoringExecutor.shutdown();
            
            if (!quoteProcessingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                quoteProcessingExecutor.shutdownNow();
            }
            if (!quoteMonitoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                quoteMonitoringExecutor.shutdownNow();
            }
            
            log.info("QuoteService shutdown completed");
            
        } catch (Exception e) {
            log.error("Error during QuoteService shutdown", e);
        }
    }

    /**
     * Quote Subscription data class
     */
    @lombok.Builder
    @lombok.Data
    public static class QuoteSubscription {
        private String symbol;
        private LocalDateTime subscriptionTime;
        private boolean isActive;
        private int updateCount;
        private LocalDateTime lastUpdate;
        
        public void incrementUpdateCount() {
            this.updateCount++;
            this.lastUpdate = LocalDateTime.now();
        }
    }

    /**
     * Realtime Quote data class
     */
    @lombok.Builder
    @lombok.Data
    public static class RealtimeQuote {
        private String symbol;
        private BigDecimal currentPrice;
        private BigDecimal previousClose;
        private BigDecimal change;
        private BigDecimal changePercent;
        private BigDecimal high;
        private BigDecimal low;
        private Long volume;
        private BigDecimal turnover;
        private LocalDateTime timestamp;
        
        public boolean isPriceUp() {
            return change != null && change.compareTo(BigDecimal.ZERO) > 0;
        }
        
        public boolean isPriceDown() {
            return change != null && change.compareTo(BigDecimal.ZERO) < 0;
        }
        
        public boolean isSignificantChange(BigDecimal threshold) {
            if (changePercent == null || threshold == null) {
                return false;
            }
            return changePercent.abs().compareTo(threshold) > 0;
        }
    }
    
    /**
     * 订阅FUTU行情数据
     */
    private boolean subscribeToFutuQuote(String symbol) {
        try {
            // 构建订阅请求数据
            byte[] subscribeData = buildSubscribeRequest(symbol);
            
            // 发送订阅请求
            CompletableFuture<ByteBuf> future = futuApiClient.sendRequest(
                FutuProtocol.PROTO_ID_SUB, 
                subscribeData
            );
            
            // 等待响应
            ByteBuf response = future.get(5, TimeUnit.SECONDS);
            
            // 解析响应（暂时简化处理）
            if (response != null) {
                log.debug("成功订阅行情: {}", symbol);
                return true;
            }
            
            return false;
        } catch (Exception e) {
            log.error("订阅行情失败: {}", symbol, e);
            return false;
        }
    }
    
    /**
     * 取消订阅FUTU行情数据
     */
    private boolean unsubscribeFromFutuQuote(String symbol) {
        try {
            // 构建取消订阅请求数据
            byte[] unsubscribeData = buildUnsubscribeRequest(symbol);
            
            // 发送取消订阅请求
            CompletableFuture<ByteBuf> future = futuApiClient.sendRequest(
                FutuProtocol.PROTO_ID_UNSUB, 
                unsubscribeData
            );
            
            // 等待响应
            ByteBuf response = future.get(5, TimeUnit.SECONDS);
            
            if (response != null) {
                log.debug("成功取消订阅行情: {}", symbol);
                return true;
            }
            
            return false;
        } catch (Exception e) {
            log.error("取消订阅行情失败: {}", symbol, e);
            return false;
        }
    }
    
    /**
     * 构建订阅请求
     */
    private byte[] buildSubscribeRequest(String symbol) {
        // 使用FutuProtobufSerializer构建请求
        return protobufSerializer.buildSubscribeRequest(symbol, FutuProtocol.SubType.QUOTE.getCode());
    }
    
    /**
     * 构建取消订阅请求
     */
    private byte[] buildUnsubscribeRequest(String symbol) {
        // 使用FutuProtobufSerializer构建请求
        return protobufSerializer.buildUnsubscribeRequest(symbol, FutuProtocol.SubType.QUOTE.getCode());
    }
    
    /**
     * 处理行情推送数据
     */
    public void handleQuotePush(String symbol, FutuQuote quote) {
        try {
            // 转换为内部格式
            RealtimeQuote realtimeQuote = RealtimeQuote.builder()
                .symbol(symbol)
                .currentPrice(quote.getLastPrice())
                .previousClose(quote.getPreClosePrice())
                .change(quote.getChangeValue())
                .changePercent(quote.getChangeRate())
                .high(quote.getHighPrice())
                .low(quote.getLowPrice())
                .volume(quote.getVolume())
                .turnover(quote.getTurnover())
                .timestamp(LocalDateTime.now())
                .build();
            
            // 更新缓存
            latestQuotes.put(symbol, realtimeQuote);
            cacheService.cacheQuote(symbol, realtimeQuote);
            
            // 更新订阅统计
            QuoteSubscription subscription = activeSubscriptions.get(symbol);
            if (subscription != null) {
                subscription.incrementUpdateCount();
            }
            
            // 发布事件
            publishQuoteUpdateEvent(realtimeQuote);
            
        } catch (Exception e) {
            log.error("处理行情推送失败: {}", symbol, e);
        }
    }
}