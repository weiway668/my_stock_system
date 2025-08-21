package com.trading.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用全局配置类
 * <p>
 * 用于定义整个应用共享的Bean，例如工具类或服务。
 * </p>
 */
@Configuration
public class AppConfig {

    /**
     * 提供一个全局可用的 ObjectMapper Bean。
     * <p>
     * 这个 ObjectMapper 用于JSON序列化和反序列化，特别是在将回测结果中的
     * 复杂数据结构（如交易历史、每日权益）转换为JSON字符串以存入数据库时。
     * </p>
     * <ul>
     *   <li>注册了 {@link JavaTimeModule} 以支持 Java 8 的日期和时间类型（例如 LocalDateTime）。</li>
     *   <li>禁用了将日期写为时间戳的特性，使其更具可读性 (e.g., "2025-08-21T14:30:00")。</li>
     * </ul>
     *
     * @return 一个配置好的 ObjectMapper 实例
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 注册JavaTimeModule以正确处理Java 8的日期时间API (LocalDate, LocalDateTime等)
        mapper.registerModule(new JavaTimeModule());
        // 为了更好的可读性，不将日期序列化为时间戳
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 在反序列化时，忽略在JSON中存在但Java对象中不存在的属性
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
