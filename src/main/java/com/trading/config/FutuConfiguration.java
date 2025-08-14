package com.trading.config;

import com.trading.infrastructure.futu.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * FUTU API配置类
 * 负责创建和配置所有FUTU相关的服务bean
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(FutuConfiguration.FutuProperties.class)
@ConditionalOnProperty(name = "trading.futu.connection.host")
public class FutuConfiguration {

    /**
     * 创建FUTU WebSocket客户端Bean
     */
    @Bean
    @Primary
    public FutuWebSocketClient futuWebSocketClient(FutuProperties futuProperties) {
        log.info("初始化FUTU WebSocket客户端: {}:{}", 
            futuProperties.getConnection().getHost(), 
            futuProperties.getConnection().getPort());
        
        FutuWebSocketClient client = new FutuWebSocketClient();
        
        // 设置连接参数
        client.setConnectionParams(
            futuProperties.getConnection().getHost(),
            futuProperties.getConnection().getPort(),
            false // 暂时不使用SSL
        );
        
        log.debug("FUTU WebSocket客户端配置完成");
        return client;
    }

    /**
     * 创建FUTU行情服务实现Bean
     */
    @Bean
    @Primary
    public FutuMarketDataService futuMarketDataService(FutuWebSocketClient futuWebSocketClient) {
        log.info("初始化FUTU行情数据服务");
        return new FutuMarketDataServiceImpl(futuWebSocketClient);
    }

    /**
     * 创建FUTU交易服务实现Bean
     */
    @Bean
    @Primary
    public FutuTradeService futuTradeService(FutuWebSocketClient futuWebSocketClient) {
        log.info("初始化FUTU交易服务");
        return new FutuTradeServiceImpl(futuWebSocketClient);
    }

    /**
     * 创建FUTU健康检查指示器Bean
     */
    @Bean
    @ConditionalOnProperty(name = "management.endpoints.web.exposure.include", havingValue = "*", matchIfMissing = true)
    public FutuHealthIndicator futuHealthIndicator(FutuConnection futuConnection) {
        log.info("初始化FUTU健康检查指示器");
        return new FutuHealthIndicator(futuConnection);
    }

    /**
     * FUTU配置属性类
     * 对应application.yml中的trading.futu配置节
     */
    @Data
    @ConfigurationProperties(prefix = "trading.futu")
    public static class FutuProperties {

        /**
         * 连接配置
         */
        private ConnectionConfig connection = new ConnectionConfig();

        /**
         * 行情配置
         */
        private QuoteConfig quote = new QuoteConfig();

        /**
         * 交易配置
         */
        private TradeConfig trade = new TradeConfig();

        /**
         * 账户配置
         */
        private AccountConfig account = new AccountConfig();

        @Data
        public static class ConnectionConfig {
            /**
             * FUTU OpenD服务器地址
             */
            private String host = "127.0.0.1";

            /**
             * FUTU OpenD服务器端口
             */
            private int port = 11111;

            /**
             * 连接超时时间(毫秒)
             */
            private int connectionTimeout = 10000;

            /**
             * 读取超时时间(毫秒)
             */
            private int readTimeout = 30000;

            /**
             * 心跳间隔(毫秒)
             */
            private int heartbeatInterval = 30000;

            /**
             * 最大重试次数
             */
            private int maxRetryAttempts = 3;

            /**
             * 重试延迟(毫秒)
             */
            private int retryDelayMs = 5000;
        }

        @Data
        public static class QuoteConfig {
            /**
             * 最大订阅数量
             */
            private int maxSubscriptions = 50;

            /**
             * 是否启用推送
             */
            private boolean pushEnabled = true;
        }

        @Data
        public static class TradeConfig {
            /**
             * 解锁密码
             */
            private String unlockPassword = "";

            /**
             * 交易环境：SIMULATE(模拟) 或 REAL(真实)
             */
            private String environment = "SIMULATE";

            /**
             * 交易市场：HK(港股)、US(美股)、CN(A股)
             */
            private String market = "HK";
        }

        @Data
        public static class AccountConfig {
            /**
             * 是否自动选择账户
             */
            private boolean autoSelectAccount = true;

            /**
             * 最大持仓数量
             */
            private int maxPositions = 10;

            /**
             * 最大单笔订单金额
             */
            private double maxOrderValue = 100000.0;

            /**
             * 是否启用风控
             */
            private boolean enableRiskControl = true;
        }
    }
}