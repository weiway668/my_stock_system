# 基础设施层设计

## 七、基础设施层设计

### 7.1 数据访问层

```java
// Spring Data JPA Repository
@Repository
public interface MarketDataRepository 
        extends JpaRepository<MarketData, String> {
    
    List<MarketData> findBySymbolAndTimestampBetween(
        String symbol, 
        LocalDateTime from, 
        LocalDateTime to
    );
    
    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol " +
           "ORDER BY m.timestamp DESC LIMIT 1")
    Optional<MarketData> findLatestBySymbol(String symbol);
}

@Repository
public interface OrderRepository 
        extends JpaRepository<Order, String> {
    
    List<Order> findByStatusAndCreateTimeBetween(
        OrderStatus status,
        LocalDateTime from,
        LocalDateTime to
    );
    
    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = 'FILLED' " +
           "AND o.realizedPnl < 0 AND o.createTime > :since")
    Integer countLossesSince(LocalDateTime since);
}

@Repository
public interface PositionRepository 
        extends JpaRepository<Position, String> {
    
    List<Position> findByQuantityGreaterThan(Integer quantity);
    
    @Query("SELECT SUM(p.marketValue) FROM Position p")
    BigDecimal getTotalMarketValue();
}

@Repository
public interface SignalRepository 
        extends JpaRepository<TradingSignal, String> {
    
    List<TradingSignal> findBySymbolAndGenerateTimeBetween(
        String symbol,
        LocalDateTime from,
        LocalDateTime to
    );
    
    List<TradingSignal> findByExecutedFalseAndGenerateTimeAfter(
        LocalDateTime time
    );
}
```

### 7.2 内存队列配置

```java
// Disruptor配置（用于高性能交易路径）
@Configuration
public class DisruptorConfig {
    
    @Bean
    public Disruptor<TradingEvent> tradingDisruptor() {
        // 指定事件工厂
        EventFactory<TradingEvent> eventFactory = TradingEvent::new;
        
        // 环形缓冲区大小（必须是2的幂次）
        int bufferSize = 65536;
        
        // 创建Disruptor
        Disruptor<TradingEvent> disruptor = new Disruptor<>(
            eventFactory,
            bufferSize,
            r -> new Thread(r, "disruptor-thread"),
            ProducerType.MULTI,
            new BusySpinWaitStrategy() // 最低延迟策略
        );
        
        // 设置事件处理器
        disruptor.handleEventsWith(new TradingEventHandler());
        
        // 启动Disruptor
        disruptor.start();
        
        return disruptor;
    }
    
    @Bean
    public RingBuffer<TradingEvent> tradingRingBuffer(
            Disruptor<TradingEvent> disruptor) {
        return disruptor.getRingBuffer();
    }
}

// Spring Events配置（用于非关键路径）
@Configuration
@EnableAsync
public class EventConfig {
    
    @Bean
    public ApplicationEventMulticaster applicationEventMulticaster() {
        SimpleApplicationEventMulticaster eventMulticaster = 
            new SimpleApplicationEventMulticaster();
        
        // 设置异步执行器
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("event-");
        executor.initialize();
        
        eventMulticaster.setTaskExecutor(executor);
        return eventMulticaster;
    }
}

// 内存队列配置（用于批量处理）
@Configuration
public class QueueConfig {
    
    @Bean
    public BlockingQueue<MarketData> marketDataQueue() {
        // 使用LinkedBlockingQueue，容量10000
        return new LinkedBlockingQueue<>(10000);
    }
    
    @Bean
    public BlockingQueue<TradingSignal> signalQueue() {
        // 使用ArrayBlockingQueue，固定容量，性能更好
        return new ArrayBlockingQueue<>(5000);
    }
    
    @Bean
    public BlockingQueue<Order> orderQueue() {
        // 使用PriorityBlockingQueue，支持优先级
        return new PriorityBlockingQueue<>(1000);
    }
}

// 事件处理器
@Component
@Slf4j
public class TradingEventHandler implements EventHandler<TradingEvent> {
    
    private final TradingService tradingService;
    
    @Override
    public void onEvent(TradingEvent event, long sequence, boolean endOfBatch) {
        try {
            switch (event.getType()) {
                case SIGNAL:
                    tradingService.executeSignal(event.getSignal());
                    break;
                case ORDER:
                    tradingService.processOrder(event.getOrder());
                    break;
                case MARKET_DATA:
                    tradingService.handleMarketData(event.getMarketData());
                    break;
            }
        } catch (Exception e) {
            log.error("事件处理失败: {}", event, e);
        }
    }
}

// Spring事件监听器
@Component
@Slf4j
public class TradingEventListener {
    
    private final TradingService tradingService;
    private final StrategyService strategyService;
    
    @EventListener
    @Async
    public void handleSignal(TradingSignalEvent event) {
        log.info("收到交易信号: {}", event.getSignal());
        try {
            tradingService.executeSignal(event.getSignal());
        } catch (Exception e) {
            log.error("信号执行失败", e);
        }
    }
    
    @EventListener
    @Async
    public void handleMarketData(MarketDataEvent event) {
        log.debug("收到市场数据: {}", event.getData().getSymbol());
        // 触发策略评估
        strategyService.evaluateAll(event.getData());
    }
    
    @EventListener
    @Async
    public void handleOrderEvent(OrderEvent event) {
        log.info("收到订单事件: {}", event);
        // 处理订单事件
        processOrderEvent(event);
    }
}

// 消息持久化和恢复机制
@Component
@Slf4j
public class MessagePersistenceService {
    
    private final MessageEventRepository repository;
    private final RingBuffer<TradingEvent> ringBuffer;
    
    // 持久化关键消息
    @Transactional
    public void persistCriticalMessage(TradingEvent event) {
        MessageEvent entity = new MessageEvent();
        entity.setEventType(event.getType().name());
        entity.setPayload(serializeEvent(event));
        entity.setStatus(MessageStatus.PENDING);
        entity.setCreatedAt(LocalDateTime.now());
        
        repository.save(entity);
        
        // 发送到内存队列
        ringBuffer.publishEvent((e, sequence) -> {
            e.copyFrom(event);
            e.setMessageId(entity.getId());
        });
    }
    
    // 系统启动时恢复未处理的消息
    @PostConstruct
    public void recoverPendingMessages() {
        List<MessageEvent> pendingMessages = repository
            .findByStatusOrderByCreatedAt(MessageStatus.PENDING);
        
        log.info("恢复 {} 条未处理消息", pendingMessages.size());
        
        for (MessageEvent msg : pendingMessages) {
            try {
                TradingEvent event = deserializeEvent(msg.getPayload());
                ringBuffer.publishEvent((e, sequence) -> e.copyFrom(event));
                
                msg.setStatus(MessageStatus.RECOVERED);
                repository.save(msg);
            } catch (Exception e) {
                log.error("恢复消息失败: {}", msg.getId(), e);
                msg.setStatus(MessageStatus.FAILED);
                repository.save(msg);
            }
        }
    }
    
    // 标记消息为已处理
    @Async
    public void markAsProcessed(String messageId) {
        repository.findById(messageId).ifPresent(msg -> {
            msg.setStatus(MessageStatus.PROCESSED);
            msg.setProcessedAt(LocalDateTime.now());
            repository.save(msg);
        });
    }
}

// 消息事件实体
@Entity
@Table(name = "message_events")
public class MessageEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    private String eventType;
    
    @Column(columnDefinition = "TEXT")
    private String payload;
    
    @Enumerated(EnumType.STRING)
    private MessageStatus status;
    
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
    
    // getters and setters
}

enum MessageStatus {
    PENDING,
    PROCESSED,
    RECOVERED,
    FAILED
}
```

### 7.3 Redis缓存配置

```java
@Configuration
@EnableCaching
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = 
            new RedisTemplate<>();
        template.setConnectionFactory(factory);
        
        // 设置序列化
        Jackson2JsonRedisSerializer<Object> serializer = 
            new Jackson2JsonRedisSerializer<>(Object.class);
        
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
        serializer.setObjectMapper(om);
        
        template.setKeySerializer(
            new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(
            new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        
        template.afterPropertiesSet();
        return template;
    }
    
    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory factory) {
        RedisCacheConfiguration config = 
            RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeKeysWith(RedisSerializationContext
                    .SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext
                    .SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()));
        
        return RedisCacheManager.builder(factory)
            .cacheDefaults(config)
            .withCacheConfiguration("marketData",
                config.entryTtl(Duration.ofSeconds(10)))
            .withCacheConfiguration("strategyConfig",
                config.entryTtl(Duration.ofMinutes(30)))
            .build();
    }
}
```