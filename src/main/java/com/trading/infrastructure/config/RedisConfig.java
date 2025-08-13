package com.trading.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis Configuration for High-Performance Trading System
 */
@Slf4j
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisConfig {

    /**
     * Configure RedisTemplate with optimized serialization
     */
    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        log.info("Configuring Redis template with Jackson2JsonRedisSerializer");
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Create Jackson2JsonRedisSerializer for value serialization
        ObjectMapper objectMapper = createObjectMapper();
        Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);

        // String serializer for keys
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        // Configure serializers
        template.setKeySerializer(stringRedisSerializer);
        template.setHashKeySerializer(stringRedisSerializer);
        template.setValueSerializer(jackson2JsonRedisSerializer);
        template.setHashValueSerializer(jackson2JsonRedisSerializer);

        // Enable transaction support
        template.setEnableTransactionSupport(true);
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Configure Redis Cache Manager with specific cache configurations
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        log.info("Configuring Redis cache manager with custom TTL settings");
        
        // Create Jackson2JsonRedisSerializer
        ObjectMapper objectMapper = createObjectMapper();
        Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);

        // Default cache configuration
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))  // Default 1 hour TTL
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jackson2JsonRedisSerializer));

        // Custom cache configurations for different data types
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // Market data cache - short TTL for real-time data
        cacheConfigurations.put("marketData", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        
        // Technical indicators - medium TTL as they don't change frequently
        cacheConfigurations.put("technicalIndicators", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        
        // Strategy results - longer TTL for backtesting results
        cacheConfigurations.put("strategyResults", defaultConfig.entryTtl(Duration.ofHours(4)));
        
        // Configuration cache - very long TTL
        cacheConfigurations.put("configuration", defaultConfig.entryTtl(Duration.ofDays(1)));
        
        // Threshold cache - long TTL for optimization results
        cacheConfigurations.put("thresholds", defaultConfig.entryTtl(Duration.ofHours(12)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }

    /**
     * Create ObjectMapper with optimized settings for trading data
     */
    private ObjectMapper createObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        
        // Configure Jackson for better performance and compatibility
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        
        // Register Java 8 time module for LocalDateTime serialization
        objectMapper.registerModule(new JavaTimeModule());
        
        return objectMapper;
    }

    /**
     * Redis Template specifically for market data with String serialization
     * for better performance when dealing with simple key-value operations
     */
    @Bean(name = "customStringRedisTemplate")
    public RedisTemplate<String, String> customStringRedisTemplate(RedisConnectionFactory connectionFactory) {
        log.info("Configuring custom string Redis template for high-performance operations");
        
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializer for both keys and values for maximum performance
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringRedisSerializer);
        template.setValueSerializer(stringRedisSerializer);
        template.setHashKeySerializer(stringRedisSerializer);
        template.setHashValueSerializer(stringRedisSerializer);
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Configuration properties for Redis health monitoring
     */
    @Bean
    public RedisHealthIndicator redisHealthIndicator(RedisTemplate<String, String> customStringRedisTemplate) {
        return new RedisHealthIndicator(customStringRedisTemplate);
    }

    /**
     * Custom health indicator for Redis
     */
    public static class RedisHealthIndicator {
        private final RedisTemplate<String, String> redisTemplate;
        
        public RedisHealthIndicator(RedisTemplate<String, String> redisTemplate) {
            this.redisTemplate = redisTemplate;
        }
        
        public boolean isHealthy() {
            try {
                redisTemplate.opsForValue().set("health:check", "ok", Duration.ofSeconds(10));
                String result = redisTemplate.opsForValue().get("health:check");
                return "ok".equals(result);
            } catch (Exception e) {
                log.warn("Redis health check failed", e);
                return false;
            }
        }
    }
}