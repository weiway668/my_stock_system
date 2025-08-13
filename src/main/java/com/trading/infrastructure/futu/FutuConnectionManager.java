package com.trading.infrastructure.futu;

import com.trading.infrastructure.config.FutuProperties;
import com.trading.infrastructure.futu.client.FutuApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FUTU Connection Manager
 * Manages connections to FUTU OpenD (Quote and Trade contexts)
 * Based on the Python implementation architecture from FUTU-API.MD
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.futu.connection.host")
public class FutuConnectionManager {

    private final FutuProperties futuProperties;
    private final FutuApiClient futuApiClient;
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();

    // Connection states
    private final AtomicBoolean quoteConnected = new AtomicBoolean(false);
    private final AtomicBoolean tradeConnected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicInteger retryCount = new AtomicInteger(0);

    // Connection objects (placeholders for actual FUTU SDK objects)
    private Object quoteContext; // Will be replaced with actual FUTU QuoteContext
    private Object tradeContext; // Will be replaced with actual FUTU TradeContext

    // Health monitoring
    private LocalDateTime lastQuoteHeartbeat;
    private LocalDateTime lastTradeHeartbeat;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> reconnectTask;

    @PostConstruct
    public void initialize() {
        log.info("Initializing FUTU Connection Manager with config: {}:{}", 
            futuProperties.getConnection().getHost(), 
            futuProperties.getConnection().getPort());
        
        // 不自动连接，只在需要时连接
        log.info("FUTU Connection Manager initialized - connections will be established on demand");
        
        // Start heartbeat monitoring only when connected
        // startHeartbeatMonitoring();
    }

    /**
     * Start connection to FUTU OpenD
     */
    public CompletableFuture<Boolean> startConnection() {
        if (connecting.get()) {
            log.debug("Connection already in progress");
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            connecting.set(true);
            try {
                log.info("Starting connection to FUTU OpenD at {}:{}", 
                    futuProperties.getConnection().getHost(),
                    futuProperties.getConnection().getPort());

                // Connect quote context
                boolean quoteConnected = connectQuoteContext();
                
                // Connect trade context (only if quote connection successful)
                boolean tradeConnected = false;
                if (quoteConnected) {
                    tradeConnected = connectTradeContext();
                }

                if (quoteConnected && tradeConnected) {
                    retryCount.set(0);
                    log.info("Successfully connected to FUTU OpenD");
                    return true;
                } else {
                    log.warn("Failed to establish full connection to FUTU OpenD");
                    scheduleReconnect();
                    return false;
                }
            } catch (Exception e) {
                log.error("Exception during FUTU connection", e);
                scheduleReconnect();
                return false;
            } finally {
                connecting.set(false);
            }
        }, connectionExecutor);
    }

    /**
     * Connect to quote context (market data)
     */
    private boolean connectQuoteContext() {
        try {
            log.debug("Connecting quote context...");
            
            // 使用FutuApiClient连接到FUTU OpenD
            String host = futuProperties.getConnection().getHost();
            int port = futuProperties.getConnection().getPort();
            
            boolean connected = futuApiClient.connect(host, port).get(5, TimeUnit.SECONDS);
            if (!connected) {
                throw new RuntimeException("连接FUTU OpenD失败");
            }
            
            this.quoteContext = futuApiClient; // 使用同一个连接
            
            quoteConnected.set(true);
            lastQuoteHeartbeat = LocalDateTime.now();
            log.info("Quote context connected successfully");
            return true;
            
        } catch (Exception e) {
            log.error("Failed to connect quote context", e);
            quoteConnected.set(false);
            return false;
        }
    }

    /**
     * Connect to trade context (trading operations)
     */
    private boolean connectTradeContext() {
        try {
            log.debug("Connecting trade context...");
            
            // 交易上下文也使用同一个连接（FUTU OpenD支持多协议）
            this.tradeContext = futuApiClient; // 复用同一个连接
            
            tradeConnected.set(true);
            lastTradeHeartbeat = LocalDateTime.now();
            log.info("Trade context connected successfully");
            return true;
            
        } catch (Exception e) {
            log.error("Failed to connect trade context", e);
            tradeConnected.set(false);
            return false;
        }
    }

    /**
     * Simulate connection for development (to be replaced with real FUTU SDK)
     */
    private void simulateConnection(String contextType) throws InterruptedException {
        log.debug("Simulating {} connection to {}:{}", 
            contextType, futuProperties.getConnection().getHost(), futuProperties.getConnection().getPort());
        
        // Simulate connection delay
        Thread.sleep(1000);
        
        // In real implementation, this would create actual FUTU SDK connections
        log.debug("{} simulation completed", contextType);
    }

    /**
     * Disconnect from FUTU OpenD
     */
    public void disconnect() {
        log.info("Disconnecting from FUTU OpenD");
        
        try {
            // Disconnect contexts
            disconnectQuoteContext();
            disconnectTradeContext();
            
            // 关闭FutuApiClient连接
            if (futuApiClient != null) {
                futuApiClient.disconnect();
            }
            
            // Stop monitoring tasks
            stopMonitoring();
            
            log.info("Disconnected from FUTU OpenD");
        } catch (Exception e) {
            log.error("Error during disconnect", e);
        }
    }

    /**
     * Disconnect quote context
     */
    private void disconnectQuoteContext() {
        if (quoteContext != null) {
            try {
                // 行情上下文断开（由于FutuApiClient是共享的，仅标记状态）
                log.debug("Quote context disconnected");
            } catch (Exception e) {
                log.warn("Error disconnecting quote context", e);
            } finally {
                quoteContext = null;
                quoteConnected.set(false);
            }
        }
    }

    /**
     * Disconnect trade context
     */
    private void disconnectTradeContext() {
        if (tradeContext != null) {
            try {
                // 交易上下文断开（实际断开在shutdown时执行）
                log.debug("Trade context disconnected");
            } catch (Exception e) {
                log.warn("Error disconnecting trade context", e);
            } finally {
                tradeContext = null;
                tradeConnected.set(false);
            }
        }
    }

    /**
     * Start heartbeat monitoring
     */
    private void startHeartbeatMonitoring() {
        if (heartbeatTask == null || heartbeatTask.isCancelled()) {
            int heartbeatInterval = futuProperties.getConnection().getHeartbeatInterval();
            heartbeatTask = scheduledExecutor.scheduleWithFixedDelay(
                this::performHeartbeat,
                heartbeatInterval,
                heartbeatInterval,
                TimeUnit.MILLISECONDS
            );
            log.debug("Started heartbeat monitoring with interval: {}ms", heartbeatInterval);
        }
    }

    /**
     * Perform heartbeat check
     */
    private void performHeartbeat() {
        try {
            boolean quoteHealthy = checkQuoteContextHealth();
            boolean tradeHealthy = checkTradeContextHealth();
            
            if (!quoteHealthy || !tradeHealthy) {
                log.warn("Heartbeat failed - Quote: {}, Trade: {}", quoteHealthy, tradeHealthy);
                scheduleReconnect();
            } else {
                log.trace("Heartbeat successful");
            }
        } catch (Exception e) {
            log.error("Error during heartbeat check", e);
        }
    }

    /**
     * Check quote context health
     */
    private boolean checkQuoteContextHealth() {
        if (!quoteConnected.get()) {
            return false;
        }
        
        // 使用FutuApiClient检查连接状态
        if (futuApiClient != null && futuApiClient.isConnected()) {
            lastQuoteHeartbeat = LocalDateTime.now();
            return true;
        }
        return false;
    }

    /**
     * Check trade context health
     */
    private boolean checkTradeContextHealth() {
        if (!tradeConnected.get()) {
            return false;
        }
        
        // 交易上下文也使用同一个连接
        if (futuApiClient != null && futuApiClient.isConnected()) {
            lastTradeHeartbeat = LocalDateTime.now();
            return true;
        }
        return false;
    }

    /**
     * Schedule reconnection attempt
     */
    private void scheduleReconnect() {
        int currentRetryCount = retryCount.incrementAndGet();
        int maxRetries = futuProperties.getConnection().getMaxRetryAttempts();
        
        if (currentRetryCount > maxRetries) {
            log.error("Max retry attempts ({}) exceeded for FUTU connection", maxRetries);
            return;
        }
        
        long retryDelay = futuProperties.getConnection().getRetryDelayMs() * currentRetryCount;
        log.info("Scheduling reconnect attempt {} in {}ms", currentRetryCount, retryDelay);
        
        if (reconnectTask == null || reconnectTask.isDone()) {
            reconnectTask = scheduledExecutor.schedule(
                () -> startConnection(),
                retryDelay,
                TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Stop monitoring tasks
     */
    private void stopMonitoring() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        
        if (reconnectTask != null && !reconnectTask.isCancelled()) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    /**
     * Get connection status
     */
    public ConnectionStatus getConnectionStatus() {
        return ConnectionStatus.builder()
            .quoteConnected(quoteConnected.get())
            .tradeConnected(tradeConnected.get())
            .connecting(connecting.get())
            .retryCount(retryCount.get())
            .lastQuoteHeartbeat(lastQuoteHeartbeat)
            .lastTradeHeartbeat(lastTradeHeartbeat)
            .build();
    }

    /**
     * Check if fully connected (both quote and trade)
     */
    public boolean isFullyConnected() {
        return quoteConnected.get() && tradeConnected.get();
    }

    /**
     * Check if quote connection is available
     */
    public boolean isQuoteConnected() {
        return quoteConnected.get();
    }

    /**
     * Check if trade connection is available
     */
    public boolean isTradeConnected() {
        return tradeConnected.get();
    }

    /**
     * Get quote context (for services to use)
     */
    public Object getQuoteContext() {
        return quoteConnected.get() ? quoteContext : null;
    }

    /**
     * Get trade context (for services to use)
     */
    public Object getTradeContext() {
        return tradeConnected.get() ? tradeContext : null;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down FUTU Connection Manager");
        
        disconnect();
        
        // Shutdown executors
        scheduledExecutor.shutdown();
        connectionExecutor.shutdown();
        
        try {
            if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
            if (!connectionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                connectionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Connection status data class
     */
    @lombok.Builder
    @lombok.Data
    public static class ConnectionStatus {
        private boolean quoteConnected;
        private boolean tradeConnected;
        private boolean connecting;
        private int retryCount;
        private LocalDateTime lastQuoteHeartbeat;
        private LocalDateTime lastTradeHeartbeat;
        
        public boolean isHealthy() {
            return quoteConnected && tradeConnected && !connecting;
        }
    }
}