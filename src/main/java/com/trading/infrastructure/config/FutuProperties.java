package com.trading.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * FUTU OpenAPI Configuration Properties
 * Based on the Python implementation architecture from FUTU-API.MD
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading.futu")
public class FutuProperties {

    /**
     * FUTU OpenD connection settings (corresponds to Python connection config)
     */
    private Connection connection = new Connection();

    /**
     * Trading environment settings
     */
    private Environment environment = new Environment();

    /**
     * Market data subscription settings
     */
    private MarketData marketData = new MarketData();

    /**
     * Trading account settings
     */
    private Account account = new Account();
    
    /**
     * 行情订阅配置
     */
    private Quote quote = new Quote();
    
    /**
     * 交易配置
     */
    private Trade trade = new Trade();
    
    /**
     * API限流配置
     */
    private RateLimit rateLimit = new RateLimit();

    /**
     * Connection configuration
     */
    @Data
    public static class Connection {
        /**
         * OpenD host address (default: 127.0.0.1)
         */
        private String host = "127.0.0.1";

        /**
         * OpenD port (default: 11111)
         */
        private int port = 11111;

        /**
         * Connection timeout in milliseconds
         */
        private int connectionTimeout = 10000;

        /**
         * Read timeout in milliseconds
         */
        private int readTimeout = 30000;

        /**
         * Maximum retry attempts for connection
         */
        private int maxRetryAttempts = 3;

        /**
         * Retry delay in milliseconds
         */
        private int retryDelayMs = 5000;

        /**
         * Enable connection keep-alive
         */
        private boolean keepAlive = true;

        /**
         * Heartbeat interval in milliseconds
         */
        private int heartbeatInterval = 30000;
    }

    /**
     * Environment configuration
     */
    @Data
    public static class Environment {
        /**
         * Trading environment: SIMULATE or REAL
         */
        private TradingEnvironment type = TradingEnvironment.SIMULATE;

        /**
         * Enable paper trading mode (corresponds to Python simulate mode)
         */
        private boolean paperTrading = true;

        /**
         * Market region (HK, US, CN_A)
         */
        private String marketRegion = "HK";

        /**
         * Currency for trading
         */
        private String currency = "HKD";
    }

    /**
     * Market data configuration
     */
    @Data
    public static class MarketData {
        /**
         * Enable real-time market data subscription
         */
        private boolean enableRealTime = true;

        /**
         * Maximum number of subscribed symbols
         */
        private int maxSubscriptions = 100;

        /**
         * Supported timeframes for historical data
         */
        private List<String> supportedTimeframes = List.of("1m", "5m", "15m", "30m", "60m", "1d");

        /**
         * Default timeframe for real-time data
         */
        private String defaultTimeframe = "30m";

        /**
         * Maximum number of historical records to fetch in one request
         */
        private int maxHistoricalRecords = 1000;

        /**
         * Enable automatic data validation
         */
        private boolean enableDataValidation = true;

        /**
         * Cache expiry time for real-time data (in seconds)
         */
        private int realTimeCacheExpiry = 60;
    }

    /**
     * Account configuration
     */
    @Data
    public static class Account {
        /**
         * Trading account ID (optional, auto-detect if not specified)
         */
        private String accountId;

        /**
         * Trading password for real environment
         */
        private String tradingPassword;

        /**
         * Enable automatic account selection (simulate vs real)
         */
        private boolean autoSelectAccount = true;

        /**
         * Maximum positions allowed
         */
        private int maxPositions = 10;

        /**
         * Maximum order value per transaction
         */
        private double maxOrderValue = 50000.0;

        /**
         * Enable risk control
         */
        private boolean enableRiskControl = true;

        /**
         * Daily loss limit
         */
        private double dailyLossLimit = 0.05; // 5%

        /**
         * Position size limit per symbol
         */
        private double positionSizeLimit = 0.3; // 30% of total capital
    }

    /**
     * Trading environment enumeration
     */
    public enum TradingEnvironment {
        SIMULATE, REAL
    }

    /**
     * Market region enumeration
     */
    public enum MarketRegion {
        HK, US, CN_A
    }

    /**
     * Get full connection URL
     */
    public String getConnectionUrl() {
        return String.format("http://%s:%d", connection.getHost(), connection.getPort());
    }

    /**
     * Check if running in simulation mode
     */
    public boolean isSimulationMode() {
        return environment.getType() == TradingEnvironment.SIMULATE || environment.isPaperTrading();
    }

    /**
     * Get market region as enum
     */
    public MarketRegion getMarketRegionEnum() {
        try {
            return MarketRegion.valueOf(environment.getMarketRegion());
        } catch (IllegalArgumentException e) {
            return MarketRegion.HK; // default to HK
        }
    }
    
    /**
     * 行情订阅配置
     */
    @Data
    public static class Quote {
        /**
         * 最大订阅数量
         */
        private int maxSubscriptions = 500;
        
        /**
         * 批量处理大小
         */
        private int batchSize = 100;
        
        /**
         * 是否启用推送
         */
        private boolean pushEnabled = true;
    }
    
    /**
     * 交易配置
     */
    @Data
    public static class Trade {
        /**
         * 账户ID
         */
        private String accountId;
        
        /**
         * 解锁密码（通过环境变量设置）
         */
        private String unlockPassword;
        
        /**
         * 交易环境：SIMULATE或REAL
         */
        private String environment = "SIMULATE";
        
        /**
         * 市场：HK|US|CN
         */
        private String market = "HK";
    }
    
    /**
     * API限流配置
     */
    @Data
    public static class RateLimit {
        /**
         * 行情请求每秒限制
         */
        private int quotePerSecond = 30;
        
        /**
         * 交易请求每秒限制
         */
        private int tradePerSecond = 10;
        
        /**
         * 查询请求每秒限制
         */
        private int queryPerSecond = 20;
    }
}