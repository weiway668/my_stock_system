package com.trading.infrastructure.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Cache Service for Trading System
 * Provides high-level caching operations with specific business logic
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.data.redis.host")
public class CacheService {

    private final CacheManager cacheManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisTemplate<String, String> stringRedisTemplate;

    // Cache names
    public static final String MARKET_DATA_CACHE = "marketData";
    public static final String TECHNICAL_INDICATORS_CACHE = "technicalIndicators";
    public static final String STRATEGY_RESULTS_CACHE = "strategyResults";
    public static final String CONFIGURATION_CACHE = "configuration";
    public static final String THRESHOLDS_CACHE = "thresholds";

    /**
     * Cache market data with symbol and timestamp
     */
    public void cacheMarketData(String symbol, Object marketData) {
        String key = String.format("market:%s:%s", symbol, LocalDateTime.now().withSecond(0).withNano(0));
        try {
            Cache cache = cacheManager.getCache(MARKET_DATA_CACHE);
            if (cache != null) {
                cache.put(key, marketData);
                log.debug("Cached market data for symbol: {}", symbol);
            }
        } catch (Exception e) {
            log.warn("Failed to cache market data for symbol: {}", symbol, e);
        }
    }

    /**
     * Get cached market data
     */
    public <T> T getMarketData(String symbol, Class<T> type) {
        String key = String.format("market:%s:%s", symbol, LocalDateTime.now().withSecond(0).withNano(0));
        try {
            Cache cache = cacheManager.getCache(MARKET_DATA_CACHE);
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    Object value = wrapper.get();
                    return type.cast(value);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get cached market data for symbol: {}", symbol, e);
        }
        return null;
    }

    /**
     * Cache technical indicators
     */
    public void cacheTechnicalIndicator(String symbol, String indicator, Object data) {
        String key = String.format("indicator:%s:%s", symbol, indicator);
        try {
            Cache cache = cacheManager.getCache(TECHNICAL_INDICATORS_CACHE);
            if (cache != null) {
                cache.put(key, data);
                log.debug("Cached technical indicator {} for symbol: {}", indicator, symbol);
            }
        } catch (Exception e) {
            log.warn("Failed to cache technical indicator {} for symbol: {}", indicator, symbol, e);
        }
    }

    /**
     * Get cached technical indicator
     */
    public <T> T getTechnicalIndicator(String symbol, String indicator, Class<T> type) {
        String key = String.format("indicator:%s:%s", symbol, indicator);
        try {
            Cache cache = cacheManager.getCache(TECHNICAL_INDICATORS_CACHE);
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    Object value = wrapper.get();
                    return type.cast(value);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get cached technical indicator {} for symbol: {}", indicator, symbol, e);
        }
        return null;
    }

    /**
     * Cache strategy results
     */
    public void cacheStrategyResult(String strategy, String symbol, String period, Object result) {
        String key = String.format("strategy:%s:%s:%s", strategy, symbol, period);
        try {
            Cache cache = cacheManager.getCache(STRATEGY_RESULTS_CACHE);
            if (cache != null) {
                cache.put(key, result);
                log.debug("Cached strategy result for {}-{}-{}", strategy, symbol, period);
            }
        } catch (Exception e) {
            log.warn("Failed to cache strategy result for {}-{}-{}", strategy, symbol, period, e);
        }
    }

    /**
     * Get cached strategy result
     */
    public <T> T getStrategyResult(String strategy, String symbol, String period, Class<T> type) {
        String key = String.format("strategy:%s:%s:%s", strategy, symbol, period);
        try {
            Cache cache = cacheManager.getCache(STRATEGY_RESULTS_CACHE);
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    Object value = wrapper.get();
                    return type.cast(value);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get cached strategy result for {}-{}-{}", strategy, symbol, period, e);
        }
        return null;
    }

    /**
     * Cache configuration value
     */
    public void cacheConfiguration(String key, Object value) {
        try {
            Cache cache = cacheManager.getCache(CONFIGURATION_CACHE);
            if (cache != null) {
                cache.put(key, value);
                log.debug("Cached configuration: {}", key);
            }
        } catch (Exception e) {
            log.warn("Failed to cache configuration: {}", key, e);
        }
    }

    /**
     * Get cached configuration
     */
    public <T> T getConfiguration(String key, Class<T> type) {
        try {
            Cache cache = cacheManager.getCache(CONFIGURATION_CACHE);
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    Object value = wrapper.get();
                    return type.cast(value);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get cached configuration: {}", key, e);
        }
        return null;
    }

    /**
     * Cache threshold values
     */
    public void cacheThreshold(String symbol, String strategy, String threshold, Object value) {
        String key = String.format("threshold:%s:%s:%s", symbol, strategy, threshold);
        try {
            Cache cache = cacheManager.getCache(THRESHOLDS_CACHE);
            if (cache != null) {
                cache.put(key, value);
                log.debug("Cached threshold {}-{}-{}", symbol, strategy, threshold);
            }
        } catch (Exception e) {
            log.warn("Failed to cache threshold {}-{}-{}", symbol, strategy, threshold, e);
        }
    }

    /**
     * Get cached threshold
     */
    public <T> T getThreshold(String symbol, String strategy, String threshold, Class<T> type) {
        String key = String.format("threshold:%s:%s:%s", symbol, strategy, threshold);
        try {
            Cache cache = cacheManager.getCache(THRESHOLDS_CACHE);
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    Object value = wrapper.get();
                    return type.cast(value);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get cached threshold {}-{}-{}", symbol, strategy, threshold, e);
        }
        return null;
    }

    /**
     * Generic cache method with TTL (for QuoteService compatibility)
     */
    public void cache(String key, Object value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
            log.trace("Cached data with key: {} and TTL: {}s", key, ttlSeconds);
        } catch (Exception e) {
            log.warn("Failed to cache data with key: {}", key, e);
        }
    }

    /**
     * Generic get method (for QuoteService compatibility)
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            
            if (cached == null) {
                return null;
            }

            if (type.isInstance(cached)) {
                return (T) cached;
            } else {
                return type.cast(cached);
            }
        } catch (Exception e) {
            log.warn("Failed to get data from cache with key: {}", key, e);
            return null;
        }
    }

    /**
     * Set with expiration using raw Redis operations for high performance
     */
    public void setWithExpiration(String key, String value, Duration duration) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, duration);
            log.debug("Set key {} with expiration {}", key, duration);
        } catch (Exception e) {
            log.warn("Failed to set key {} with expiration", key, e);
        }
    }

    /**
     * Get from raw Redis operations
     */
    public String get(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Failed to get key {}", key, e);
            return null;
        }
    }

    /**
     * Delete specific cache entry
     */
    public void evict(String cacheName, String key) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(key);
                log.debug("Evicted cache entry {} from {}", key, cacheName);
            }
        } catch (Exception e) {
            log.warn("Failed to evict cache entry {} from {}", key, cacheName, e);
        }
    }

    /**
     * Clear entire cache
     */
    public void clear(String cacheName) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                log.info("Cleared cache: {}", cacheName);
            }
        } catch (Exception e) {
            log.warn("Failed to clear cache: {}", cacheName, e);
        }
    }

    /**
     * Get cache statistics
     */
    public CacheStats getCacheStats() {
        try {
            // Get connection info
            Set<String> keys = redisTemplate.keys("*");
            int totalKeys = keys != null ? keys.size() : 0;
            
            return CacheStats.builder()
                .totalKeys(totalKeys)
                .marketDataKeys(getKeysCount("market:*"))
                .indicatorKeys(getKeysCount("indicator:*"))
                .strategyKeys(getKeysCount("strategy:*"))
                .configKeys(getKeysCount("config:*"))
                .thresholdKeys(getKeysCount("threshold:*"))
                .build();
        } catch (Exception e) {
            log.warn("Failed to get cache statistics", e);
            return CacheStats.builder().totalKeys(0).build();
        }
    }

    private int getKeysCount(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.debug("Failed to count keys for pattern: {}", pattern);
            return 0;
        }
    }

    /**
     * Cache statistics data class
     */
    @lombok.Builder
    @lombok.Data
    public static class CacheStats {
        private int totalKeys;
        private int marketDataKeys;
        private int indicatorKeys;
        private int strategyKeys;
        private int configKeys;
        private int thresholdKeys;
    }
}