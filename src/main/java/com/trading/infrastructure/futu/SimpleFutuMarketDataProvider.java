package com.trading.infrastructure.futu;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 简化的FUTU市场数据提供者
 * 用于测试，使用SimpleFutuConnector
 */
@Slf4j
@Component
public class SimpleFutuMarketDataProvider {

    @Value("${trading.futu.connection.host:127.0.0.1}")
    private String host;

    @Value("${trading.futu.connection.port:11111}")
    private int port;

    private SimpleFutuConnector connector;
    private AtomicBoolean isConnected = new AtomicBoolean(false);

    // 缓存
    private final Map<String, MarketData> priceCache = new ConcurrentHashMap<>();
    private final Map<String, List<KLineData>> klineCache = new ConcurrentHashMap<>();

    // 线程池
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    @PostConstruct
    public void init() {
        try {
            log.info("初始化简化FUTU市场数据提供者: {}:{}", host, port);
            connector = new SimpleFutuConnector(host, (short) port);

            if (connector.connect()) {
                isConnected.set(true);
                log.info("成功连接到FUTU OpenD (模拟)");

                // 启动心跳线程
                startHeartbeat();
            } else {
                log.error("连接FUTU OpenD失败");
            }
        } catch (Exception e) {
            log.error("初始化FUTU连接异常", e);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("关闭FUTU连接");
        isConnected.set(false);
        executor.shutdown();
        if (connector != null) {
            connector.disconnect();
        }
    }

    /**
     * 获取实时价格
     */
    public CompletableFuture<BigDecimal> getRealtimePrice(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 检查缓存
                MarketData cached = priceCache.get(symbol);
                if (cached != null && cached.isValid()) {
                    return cached.price;
                }

                // 获取快照
                SimpleFutuConnector.SnapshotResponse response = connector.getSecuritySnapshot(symbol);
                if (response != null) {
                    BigDecimal price = response.price;

                    // 更新缓存
                    priceCache.put(symbol, new MarketData(price, LocalDateTime.now()));

                    return price;
                }

                log.warn("无法获取实时价格: {}", symbol);
                return BigDecimal.ZERO;

            } catch (Exception e) {
                log.error("获取实时价格异常: {}", symbol, e);
                return BigDecimal.ZERO;
            }
        }, executor);
    }

    /**
     * 获取历史K线数据
     */
    public CompletableFuture<List<KLineData>> getHistoricalData(String symbol, LocalDate startDate, LocalDate endDate) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 检查缓存
                String cacheKey = String.format("%s_%s_%s", symbol, startDate, endDate);
                List<KLineData> cached = klineCache.get(cacheKey);
                if (cached != null && !cached.isEmpty()) {
                    return cached;
                }

                // 获取历史K线
                SimpleFutuConnector.KLineResponse response = connector.getHistoryKLine(symbol, startDate, endDate);

                if (response != null && response.klines != null) {
                    List<KLineData> klineList = new ArrayList<>();

                    for (SimpleFutuConnector.KLine kl : response.klines) {
                        KLineData data = new KLineData();
                        data.setTime(kl.time);
                        data.setOpen(kl.open);
                        data.setHigh(kl.high);
                        data.setLow(kl.low);
                        data.setClose(kl.close);
                        data.setVolume(kl.volume);
                        data.setTurnover(kl.turnover);
                        klineList.add(data);
                    }

                    // 缓存数据
                    if (!klineList.isEmpty()) {
                        klineCache.put(cacheKey, klineList);
                    }

                    log.info("获取到{}条K线数据: {} 从 {} 到 {}",
                            klineList.size(), symbol, startDate, endDate);

                    return klineList;
                }

                log.warn("未获取到K线数据: {}", symbol);
                return Collections.emptyList();

            } catch (Exception e) {
                log.error("获取历史数据异常: {}", symbol, e);
                return Collections.emptyList();
            }
        }, executor);
    }

    /**
     * 订阅实时行情
     */
    public CompletableFuture<Boolean> subscribeQuote(String symbol, QuoteCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 订阅股票
                boolean result = connector.subscribe(symbol, new String[] { "QUOTE", "TICKER" });

                if (result) {
                    // 注册回调
                    connector.registerCallback(symbol, (s, price, volume) -> {
                        callback.onQuote(s, price, volume);
                    });
                    log.info("成功订阅: {}", symbol);
                    return true;
                }

                log.warn("订阅失败: {}", symbol);
                return false;

            } catch (Exception e) {
                log.error("订阅异常: {}", symbol, e);
                return false;
            }
        }, executor);
    }

    /**
     * 获取市场快照
     */
    public CompletableFuture<Map<String, MarketSnapshot>> getMarketSnapshot(List<String> symbols) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, MarketSnapshot> snapshots = new HashMap<>();

                // 批量获取快照
                List<SimpleFutuConnector.SnapshotResponse> responses = connector.getSecuritySnapshots(symbols);

                for (SimpleFutuConnector.SnapshotResponse snapshot : responses) {
                    MarketSnapshot ms = new MarketSnapshot();
                    ms.setSymbol(snapshot.symbol);
                    ms.setPrice(snapshot.price);
                    ms.setOpen(snapshot.open);
                    ms.setHigh(snapshot.high);
                    ms.setLow(snapshot.low);
                    ms.setVolume(snapshot.volume);
                    ms.setTimestamp(LocalDateTime.now());

                    snapshots.put(snapshot.symbol, ms);
                }

                return snapshots;

            } catch (Exception e) {
                log.error("获取市场快照异常", e);
                return Collections.emptyMap();
            }
        }, executor);
    }

    /**
     * 启动心跳
     */
    private void startHeartbeat() {
        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FUTU-Heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (isConnected.get() && connector != null) {
                    connector.keepAlive();
                }
            } catch (Exception e) {
                log.error("心跳异常", e);
                isConnected.set(false);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 检查连接状态
     */
    public boolean isConnected() {
        return isConnected.get() && connector != null && connector.isConnected();
    }

    /**
     * K线数据
     */
    public static class KLineData {
        private LocalDateTime time;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private long volume;
        private BigDecimal turnover;

        // Getter和Setter
        public LocalDateTime getTime() {
            return time;
        }

        public void setTime(LocalDateTime time) {
            this.time = time;
        }

        public BigDecimal getOpen() {
            return open;
        }

        public void setOpen(BigDecimal open) {
            this.open = open;
        }

        public BigDecimal getHigh() {
            return high;
        }

        public void setHigh(BigDecimal high) {
            this.high = high;
        }

        public BigDecimal getLow() {
            return low;
        }

        public void setLow(BigDecimal low) {
            this.low = low;
        }

        public BigDecimal getClose() {
            return close;
        }

        public void setClose(BigDecimal close) {
            this.close = close;
        }

        public long getVolume() {
            return volume;
        }

        public void setVolume(long volume) {
            this.volume = volume;
        }

        public BigDecimal getTurnover() {
            return turnover;
        }

        public void setTurnover(BigDecimal turnover) {
            this.turnover = turnover;
        }
    }

    /**
     * 市场快照
     */
    public static class MarketSnapshot {
        private String symbol;
        private BigDecimal price;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private long volume;
        private BigDecimal turnover;
        private BigDecimal changeRate;
        private LocalDateTime timestamp;

        // Getter和Setter
        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }

        public BigDecimal getOpen() {
            return open;
        }

        public void setOpen(BigDecimal open) {
            this.open = open;
        }

        public BigDecimal getHigh() {
            return high;
        }

        public void setHigh(BigDecimal high) {
            this.high = high;
        }

        public BigDecimal getLow() {
            return low;
        }

        public void setLow(BigDecimal low) {
            this.low = low;
        }

        public long getVolume() {
            return volume;
        }

        public void setVolume(long volume) {
            this.volume = volume;
        }

        public BigDecimal getTurnover() {
            return turnover;
        }

        public void setTurnover(BigDecimal turnover) {
            this.turnover = turnover;
        }

        public BigDecimal getChangeRate() {
            return changeRate;
        }

        public void setChangeRate(BigDecimal changeRate) {
            this.changeRate = changeRate;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }
    }

    /**
     * 行情回调接口
     */
    public interface QuoteCallback {
        void onQuote(String symbol, BigDecimal price, long volume);
    }

    /**
     * 内部类：市场数据缓存
     */
    private static class MarketData {
        BigDecimal price;
        LocalDateTime timestamp;

        MarketData(BigDecimal price, LocalDateTime timestamp) {
            this.price = price;
            this.timestamp = timestamp;
        }

        boolean isValid() {
            // 缓存5秒
            return timestamp.plusSeconds(5).isAfter(LocalDateTime.now());
        }
    }
}