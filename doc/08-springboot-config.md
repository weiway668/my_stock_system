# Spring Boot主配置

## 八、Spring Boot主配置

### 8.1 应用主类

```java
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableCaching
@EnableJpaAuditing
public class TradingSystemApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(
            TradingSystemApplication.class, args);
    }
    
    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = 
            new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("trading-");
        executor.initialize();
        return executor;
    }
    
    @Bean
    public ScheduledExecutorService scheduledExecutorService() {
        return Executors.newScheduledThreadPool(2);
    }
}
```

### 8.2 应用配置文件

```yaml
# application.yml
spring:
  application:
    name: trading-system
    
  # H2数据库配置（嵌入式）
  datasource:
    # 内存模式（开发环境）
    # url: jdbc:h2:mem:trading;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    # 文件模式（持久化）
    url: jdbc:h2:file:./data/trading;MODE=MySQL;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: 
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  
  # H2控制台（开发环境可开启）
  h2:
    console:
      enabled: true
      path: /h2-console
      settings:
        web-allow-others: false
  
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
        format_sql: true
  
  redis:
    host: localhost
    port: 6379
    timeout: 2000ms
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 2
  
  # 内存队列配置（替代RabbitMQ）
  async:
    thread-pool:
      core-size: 4
      max-size: 8
      queue-capacity: 1000
      thread-name-prefix: event-
    
  # Disruptor配置
  disruptor:
    ring-buffer-size: 65536  # 必须是2的幂次
    wait-strategy: BusySpinWaitStrategy  # 最低延迟策略
    producer-type: MULTI  # 多生产者模式

# 港股交易系统配置
trading:
  # 市场数据配置
  market-data:
    provider: futu
    host: 127.0.0.1
    port: 11111
    primary-timeframe: 30  # 主周期30分钟
    confirmation-timeframe: 120  # 确认周期120分钟
    
  # 交易执行配置
  execution:
    mode: futu # futu | mock
    t0-enabled: true  # 启用T+0交易
    lot-size: 100  # 港股一手100股
    
  # 交易标的配置
  targets:
    etf:
      - symbol: "2800.HK"
        name: "盈富基金"
        min-volume: 10000000
        max-spread: 0.002
      - symbol: "3033.HK"
        name: "恒生科技ETF"
        min-volume: 5000000
        max-spread: 0.003
    stocks:
      - symbol: "00700.HK"
        name: "腾讯控股"
        min-volume: 20000000
        max-spread: 0.001
    
  # 风险管理配置
  risk:
    total-capital: 100000  # 总资金10万港币
    max-single-position: 50000  # 单笔上限5万
    max-loss-per-trade: 0.03  # 单笔最大亏损3%
    max-daily-loss: 0.05  # 日内最大亏损5%
    max-drawdown: 0.15  # 最大回撤15%
    consecutive-loss-limit: 3  # 连续亏损限制
    atr-multiplier: 1.5  # ATR止损倍数
    trailing-stop-percent: 0.03  # 追踪止损3%
    trailing-stop-trigger: 0.05  # 盈利5%启动追踪
    
  # 策略配置
  strategy:
    # MACD趋势跟踪策略
    macd-trend:
      enabled: true
      weight: 0.35
      macd-fast: 12
      macd-slow: 26
      macd-signal: 9
      target-profit: 0.08  # 目标收益8%
      
    # BOLL均值回归策略
    boll-reversion:
      enabled: true
      weight: 0.25
      boll-period: 20
      boll-std-dev: 2.0
      target-profit: 0.05  # 目标收益5%
      
    # 量价突破策略
    volume-breakout:
      enabled: true
      weight: 0.25
      volume-surge-ratio: 1.5
      breakout-confirmation: 2.0
      target-profit: 0.06  # 目标收益6%
      
    # 综合配置
    overall-threshold: 70  # 总体信号阈值
    enable-hot-reload: true  # 启用热加载
    require-multi-timeframe: true  # 要求多周期确认
    
  # 交易时段配置（港股）
  session:
    morning:
      open: "09:30"
      close: "12:00"
    afternoon:
      open: "13:00"
      close: "16:00"
    restrictions:
      no-entry-after: "15:30"  # 15:30后不开新仓
      force-close-time: "15:55"  # 15:55强制平仓
      auction-period:  # 竞价时段
        morning: "09:00-09:30"
        closing: "16:00-16:10"
    
  # 持仓周期管理
  position-period:
    intraday:
      max-hours: 6
      force-close: true
    short-term:
      min-days: 1
      max-days: 5
    medium-term:
      min-days: 5
      max-days: 15
      
  # 手续费配置
  commission:
    stock-rate: 0.00025  # 股票佣金率0.025%
    etf-rate: 0.00025  # ETF佣金率0.025%
    min-charge: 15  # 最低收费5港币
    stamp-duty: 0.0013  # 印花税0.13%
    trading-fee: 0.00005  # 交易费0.005%
    settlement-fee: 0.00002  # 结算费0.002%

# 日志配置
logging:
  level:
    root: INFO
    com.trading: DEBUG
    org.springframework.web: INFO
    org.hibernate: WARN
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
    file: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
  file:
    name: logs/trading.log
    max-size: 100MB
    max-history: 30

# 监控配置（Spring Boot Actuator）
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,loggers,threaddump
      base-path: /actuator
  endpoint:
    health:
      show-details: always
  metrics:
    enable:
      jvm: true
      system: true
      tomcat: true
```

### 8.3 Logback配置

```xml
<!-- logback-spring.xml -->
<configuration>
    <!-- 控制台输出 -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- 文件输出 -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/trading.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/trading-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
                <maxFileSize>100MB</maxFileSize>
            </timeBasedFileNamingAndTriggeringPolicy>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- JSON格式输出 (用于ELK) -->
    <appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/trading-json.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/trading-json-%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"trading-system","env":"${spring.profiles.active}"}</customFields>
        </encoder>
    </appender>
    
    <!-- 异步日志 -->
    <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="FILE"/>
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
    </appender>
    
    <!-- 日志级别配置 -->
    <logger name="com.trading" level="INFO"/>
    <logger name="com.trading.service" level="DEBUG"/>
    <logger name="com.trading.repository" level="WARN"/>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC"/>
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```