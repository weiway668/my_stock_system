package com.trading.service;

import com.trading.infrastructure.cache.CacheService;
import com.trading.infrastructure.futu.FutuConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-Time Data Subscriber
 * Manages real-time data subscriptions and processing pipeline
 * Integrates with FUTU API for market data streaming and Redis for caching
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.futu.connection.host")
public class RealTimeDataSubscriber {

    private final FutuConnectionManager connectionManager;
    private final CacheService cacheService;
    private final QuoteService quoteService;
    private final MarketDataService marketDataService;
    private final ApplicationEventPublisher eventPublisher;

    // Subscription management
    private final Map<String, DataSubscription> activeSubscriptions = new ConcurrentHashMap<>();
    private final ExecutorService subscriptionExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService monitoringExecutor = Executors.newScheduledThreadPool(2);
    
    // Connection state
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicInteger totalSubscriptions = new AtomicInteger(0);
    private final AtomicInteger activeConnectionCount = new AtomicInteger(0);
    
    // Monitoring tasks
    private ScheduledFuture<?> connectionHealthTask;
    private ScheduledFuture<?> subscriptionCleanupTask;

    // Configuration
    private static final int MAX_SUBSCRIPTIONS = 100;
    private static final int SUBSCRIPTION_TIMEOUT_SECONDS = 30;
    private static final int HEALTH_CHECK_INTERVAL_SECONDS = 60;
    private static final int CLEANUP_INTERVAL_MINUTES = 10;

    @PostConstruct
    public void initialize() {
        log.info("Initializing RealTimeDataSubscriber...");
        
        if (connectionManager.isQuoteConnected()) {
            startSubscriptionService();
        }
        
        startHealthMonitoring();
        startSubscriptionCleanup();
        
        isActive.set(true);
        log.info("RealTimeDataSubscriber initialized successfully");
    }

    /**
     * Subscribe to real-time data for multiple symbols
     */
    public CompletableFuture<SubscriptionResult> subscribeToSymbols(Set<String> symbols, DataType... dataTypes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isActive.get()) {
                    return SubscriptionResult.failure("Subscriber not active");
                }

                if (symbols.isEmpty()) {
                    return SubscriptionResult.failure("No symbols provided");
                }

                if (totalSubscriptions.get() + symbols.size() > MAX_SUBSCRIPTIONS) {
                    return SubscriptionResult.failure("Subscription limit exceeded");
                }

                log.info("Subscribing to {} symbols with data types: {}", symbols.size(), dataTypes);

                int successCount = 0;
                int failureCount = 0;
                StringBuilder errors = new StringBuilder();

                for (String symbol : symbols) {
                    try {
                        boolean success = subscribeToSymbol(symbol, dataTypes);
                        if (success) {
                            successCount++;
                        } else {
                            failureCount++;
                            errors.append(String.format("Failed to subscribe to %s; ", symbol));
                        }
                    } catch (Exception e) {
                        failureCount++;
                        errors.append(String.format("Error subscribing to %s: %s; ", symbol, e.getMessage()));
                        log.error("Error subscribing to symbol: {}", symbol, e);
                    }
                }

                if (successCount > 0) {
                    log.info("Successfully subscribed to {}/{} symbols", successCount, symbols.size());
                }

                if (failureCount > 0) {
                    log.warn("Failed to subscribe to {}/{} symbols: {}", failureCount, symbols.size(), errors.toString());
                    return SubscriptionResult.partial(successCount, failureCount, errors.toString());
                } else {
                    return SubscriptionResult.success(successCount);
                }

            } catch (Exception e) {
                log.error("Error in bulk subscription", e);
                return SubscriptionResult.failure("Bulk subscription failed: " + e.getMessage());
            }
        }, subscriptionExecutor);
    }

    /**
     * Subscribe to real-time data for a single symbol
     */
    private boolean subscribeToSymbol(String symbol, DataType... dataTypes) {
        try {
            if (!connectionManager.isQuoteConnected()) {
                log.warn("Quote connection not available for subscription: {}", symbol);
                return false;
            }

            String subscriptionKey = symbol + "_" + String.join(",", 
                dataTypes.length > 0 ? 
                    java.util.Arrays.stream(dataTypes).map(Enum::name).toArray(String[]::new) :
                    new String[]{DataType.QUOTES.name()});

            if (activeSubscriptions.containsKey(subscriptionKey)) {
                log.debug("Already subscribed to {}", subscriptionKey);
                return true;
            }

            // Subscribe to quote data
            boolean quoteSubscribed = false;
            for (DataType dataType : (dataTypes.length > 0 ? dataTypes : new DataType[]{DataType.QUOTES})) {
                switch (dataType) {
                    case QUOTES -> quoteSubscribed = subscribeToQuotes(symbol);
                    case KLINES -> quoteSubscribed = subscribeToKlines(symbol);
                    case ORDER_BOOK -> quoteSubscribed = subscribeToOrderBook(symbol);
                    case TRADES -> quoteSubscribed = subscribeToTrades(symbol);
                    default -> log.warn("Unsupported data type: {}", dataType);
                }
            }

            if (quoteSubscribed) {
                DataSubscription subscription = DataSubscription.builder()
                    .subscriptionKey(subscriptionKey)
                    .symbol(symbol)
                    .dataTypes(Set.of(dataTypes.length > 0 ? dataTypes : new DataType[]{DataType.QUOTES}))
                    .subscriptionTime(java.time.LocalDateTime.now())
                    .isActive(true)
                    .updateCount(0)
                    .build();

                activeSubscriptions.put(subscriptionKey, subscription);
                totalSubscriptions.incrementAndGet();
                
                log.info("Successfully subscribed to {} for data types: {}", symbol, 
                    java.util.Arrays.toString(dataTypes));
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Error subscribing to symbol: {}", symbol, e);
            return false;
        }
    }

    /**
     * Subscribe to quotes using QuoteService
     */
    private boolean subscribeToQuotes(String symbol) {
        try {
            CompletableFuture<Boolean> future = quoteService.subscribeQuote(symbol);
            return future.get(SUBSCRIPTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to subscribe to quotes for symbol: {}", symbol, e);
            return false;
        }
    }

    /**
     * Subscribe to K-line data (simulated)
     */
    private boolean subscribeToKlines(String symbol) {
        try {
            log.debug("Subscribing to K-line data for symbol: {}", symbol);
            
            // TODO: Replace with actual FUTU SDK K-line subscription
            // Example: quoteContext.subscribeKLine(symbol, KLType.K_1M, true);
            
            // For now, simulate successful subscription
            cacheService.cache("kline_sub:" + symbol, true, 3600);
            return true;
            
        } catch (Exception e) {
            log.error("Failed to subscribe to K-line data for symbol: {}", symbol, e);
            return false;
        }
    }

    /**
     * Subscribe to order book data (simulated)
     */
    private boolean subscribeToOrderBook(String symbol) {
        try {
            log.debug("Subscribing to order book for symbol: {}", symbol);
            
            // TODO: Replace with actual FUTU SDK order book subscription
            // Example: quoteContext.subscribeOrderBook(symbol, true);
            
            // For now, simulate successful subscription
            cacheService.cache("orderbook_sub:" + symbol, true, 3600);
            return true;
            
        } catch (Exception e) {
            log.error("Failed to subscribe to order book for symbol: {}", symbol, e);
            return false;
        }
    }

    /**
     * Subscribe to trade data (simulated)
     */
    private boolean subscribeToTrades(String symbol) {
        try {
            log.debug("Subscribing to trades for symbol: {}", symbol);
            
            // TODO: Replace with actual FUTU SDK trades subscription
            // Example: quoteContext.subscribeTrades(symbol, true);
            
            // For now, simulate successful subscription
            cacheService.cache("trade_sub:" + symbol, true, 3600);
            return true;
            
        } catch (Exception e) {
            log.error("Failed to subscribe to trades for symbol: {}", symbol, e);
            return false;
        }
    }

    /**
     * Unsubscribe from symbol
     */
    public CompletableFuture<Boolean> unsubscribeFromSymbol(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Unsubscribing from symbol: {}", symbol);

                // Find and remove active subscriptions for this symbol
                boolean removed = activeSubscriptions.entrySet().removeIf(entry -> {
                    DataSubscription subscription = entry.getValue();
                    if (subscription.getSymbol().equals(symbol)) {
                        subscription.setActive(false);
                        totalSubscriptions.decrementAndGet();
                        return true;
                    }
                    return false;
                });

                if (removed) {
                    // Unsubscribe from quote service
                    CompletableFuture<Boolean> quoteUnsubscribe = quoteService.unsubscribeQuote(symbol);
                    boolean success = quoteUnsubscribe.get(SUBSCRIPTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    
                    // Clean up cache entries
                    cleanupCacheForSymbol(symbol);
                    
                    log.info("Successfully unsubscribed from symbol: {}", symbol);
                    return success;
                } else {
                    log.debug("No active subscription found for symbol: {}", symbol);
                    return true;
                }

            } catch (Exception e) {
                log.error("Error unsubscribing from symbol: {}", symbol, e);
                return false;
            }
        }, subscriptionExecutor);
    }

    /**
     * Get subscription statistics
     */
    public SubscriptionStats getSubscriptionStats() {
        return SubscriptionStats.builder()
            .totalActiveSubscriptions(totalSubscriptions.get())
            .activeConnectionCount(activeConnectionCount.get())
            .connectionHealthy(connectionManager.isQuoteConnected())
            .subscriberActive(isActive.get())
            .subscriptionDetails(new java.util.HashMap<>(activeSubscriptions))
            .build();
    }

    /**
     * Start subscription service
     */
    private void startSubscriptionService() {
        log.info("Starting real-time data subscription service...");
        
        // TODO: Setup FUTU API callbacks for real-time data
        // Example: setupQuoteCallbacks(), setupKLineCallbacks(), etc.
        
        activeConnectionCount.set(1);
        log.info("Subscription service started successfully");
    }

    /**
     * Start health monitoring
     */
    private void startHealthMonitoring() {
        connectionHealthTask = monitoringExecutor.scheduleWithFixedDelay(() -> {
            try {
                checkConnectionHealth();
            } catch (Exception e) {
                log.error("Error during connection health check", e);
            }
        }, HEALTH_CHECK_INTERVAL_SECONDS, HEALTH_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

        log.debug("Started connection health monitoring");
    }

    /**
     * Start subscription cleanup
     */
    private void startSubscriptionCleanup() {
        subscriptionCleanupTask = monitoringExecutor.scheduleWithFixedDelay(() -> {
            try {
                cleanupInactiveSubscriptions();
            } catch (Exception e) {
                log.error("Error during subscription cleanup", e);
            }
        }, CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);

        log.debug("Started subscription cleanup task");
    }

    /**
     * Check connection health
     */
    private void checkConnectionHealth() {
        boolean isHealthy = connectionManager.isQuoteConnected();
        
        if (!isHealthy && isActive.get()) {
            log.warn("Connection health check failed - attempting recovery");
            activeConnectionCount.set(0);
            
            // TODO: Implement connection recovery logic
            // Example: reconnectAndResubscribe();
        } else if (isHealthy && activeConnectionCount.get() == 0) {
            log.info("Connection restored - reactivating subscriptions");
            activeConnectionCount.set(1);
            
            // TODO: Resubscribe to all active subscriptions
            // Example: resubscribeAll();
        }
    }

    /**
     * Clean up inactive subscriptions
     */
    private void cleanupInactiveSubscriptions() {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(1);
        
        int removedCount = 0;
        for (Map.Entry<String, DataSubscription> entry : activeSubscriptions.entrySet()) {
            DataSubscription subscription = entry.getValue();
            if (!subscription.isActive() || 
                (subscription.getLastUpdate() != null && subscription.getLastUpdate().isBefore(cutoff))) {
                
                activeSubscriptions.remove(entry.getKey());
                totalSubscriptions.decrementAndGet();
                removedCount++;
            }
        }
        
        if (removedCount > 0) {
            log.info("Cleaned up {} inactive subscriptions", removedCount);
        }
    }

    /**
     * Clean up cache entries for a symbol
     */
    private void cleanupCacheForSymbol(String symbol) {
        try {
            // Clean up quote cache
            cacheService.cache("quote:" + symbol, null, 1);
            
            // Clean up subscription cache
            cacheService.cache("kline_sub:" + symbol, null, 1);
            cacheService.cache("orderbook_sub:" + symbol, null, 1);
            cacheService.cache("trade_sub:" + symbol, null, 1);
            
            log.debug("Cleaned up cache entries for symbol: {}", symbol);
        } catch (Exception e) {
            log.warn("Error cleaning up cache for symbol: {}", symbol, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down RealTimeDataSubscriber...");
        
        isActive.set(false);
        
        try {
            // Cancel monitoring tasks
            if (connectionHealthTask != null) {
                connectionHealthTask.cancel(false);
            }
            if (subscriptionCleanupTask != null) {
                subscriptionCleanupTask.cancel(false);
            }
            
            // Unsubscribe from all active subscriptions
            Set<String> symbols = activeSubscriptions.values().stream()
                .map(DataSubscription::getSymbol)
                .collect(java.util.stream.Collectors.toSet());
            
            for (String symbol : symbols) {
                try {
                    unsubscribeFromSymbol(symbol).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Error unsubscribing from symbol during shutdown: {}", symbol, e);
                }
            }
            
            // Shutdown executors
            subscriptionExecutor.shutdown();
            monitoringExecutor.shutdown();
            
            if (!subscriptionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                subscriptionExecutor.shutdownNow();
            }
            if (!monitoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitoringExecutor.shutdownNow();
            }
            
            log.info("RealTimeDataSubscriber shutdown completed");
            
        } catch (Exception e) {
            log.error("Error during RealTimeDataSubscriber shutdown", e);
        }
    }

    // Data types enum
    public enum DataType {
        QUOTES,
        KLINES, 
        ORDER_BOOK,
        TRADES
    }

    // Data classes
    @lombok.Builder
    @lombok.Data
    public static class DataSubscription {
        private String subscriptionKey;
        private String symbol;
        private Set<DataType> dataTypes;
        private java.time.LocalDateTime subscriptionTime;
        private java.time.LocalDateTime lastUpdate;
        private boolean isActive;
        private int updateCount;
        
        public void recordUpdate() {
            this.updateCount++;
            this.lastUpdate = java.time.LocalDateTime.now();
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class SubscriptionResult {
        private boolean success;
        private int successCount;
        private int failureCount;
        private String message;
        
        public static SubscriptionResult success(int count) {
            return SubscriptionResult.builder()
                .success(true)
                .successCount(count)
                .failureCount(0)
                .message("Successfully subscribed to " + count + " symbols")
                .build();
        }
        
        public static SubscriptionResult failure(String message) {
            return SubscriptionResult.builder()
                .success(false)
                .successCount(0)
                .failureCount(1)
                .message(message)
                .build();
        }
        
        public static SubscriptionResult partial(int successCount, int failureCount, String message) {
            return SubscriptionResult.builder()
                .success(successCount > 0)
                .successCount(successCount)
                .failureCount(failureCount)
                .message(message)
                .build();
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class SubscriptionStats {
        private int totalActiveSubscriptions;
        private int activeConnectionCount;
        private boolean connectionHealthy;
        private boolean subscriberActive;
        private Map<String, DataSubscription> subscriptionDetails;
    }
    
    /**
     * Check if the service is healthy
     */
    public boolean isHealthy() {
        return isActive.get() && connectionManager.isQuoteConnected();
    }
}