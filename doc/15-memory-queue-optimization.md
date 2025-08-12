# 内存队列性能优化配置

## 12.1 Disruptor性能调优

```java
@Configuration
@ConfigurationProperties(prefix = "disruptor")
public class DisruptorPerformanceConfig {
    
    private int ringBufferSize = 65536;
    private String waitStrategy = "BusySpinWaitStrategy";
    private ProducerType producerType = ProducerType.MULTI;
    private int workerThreads = 4;
    
    @Bean
    public Disruptor<TradingEvent> optimizedDisruptor() {
        // 选择等待策略
        WaitStrategy strategy = createWaitStrategy();
        
        // 创建线程工厂（绑定CPU核心）
        ThreadFactory threadFactory = new AffinityThreadFactory(
            "disruptor-worker",
            ThreadAffinityMode.THREAD_AFFINITY_ENABLE_PER_THREAD
        );
        
        Disruptor<TradingEvent> disruptor = new Disruptor<>(
            TradingEvent::new,
            ringBufferSize,
            threadFactory,
            producerType,
            strategy
        );
        
        // 并行事件处理（利用多核）
        EventHandler<TradingEvent>[] handlers = new EventHandler[workerThreads];
        for (int i = 0; i < workerThreads; i++) {
            handlers[i] = new PartitionedEventHandler(i, workerThreads);
        }
        disruptor.handleEventsWith(handlers);
        
        // 异常处理
        disruptor.setDefaultExceptionHandler(new IgnoreExceptionHandler());
        
        return disruptor;
    }
    
    private WaitStrategy createWaitStrategy() {
        switch (waitStrategy) {
            case "BusySpinWaitStrategy":
                // CPU占用高，延迟最低（<1μs）
                return new BusySpinWaitStrategy();
            case "YieldingWaitStrategy":
                // CPU占用中等，延迟很低（~1μs）
                return new YieldingWaitStrategy();
            case "BlockingWaitStrategy":
                // CPU占用低，延迟较高（~10μs）
                return new BlockingWaitStrategy();
            default:
                // 混合策略，平衡CPU和延迟
                return new PhasedBackoffWaitStrategy(
                    1, 100, TimeUnit.NANOSECONDS,
                    new BlockingWaitStrategy()
                );
        }
    }
}

// 分区事件处理器（避免竞争）
public class PartitionedEventHandler implements EventHandler<TradingEvent> {
    private final int partition;
    private final int totalPartitions;
    
    @Override
    public void onEvent(TradingEvent event, long sequence, boolean endOfBatch) {
        // 只处理属于自己分区的事件
        if (event.hashCode() % totalPartitions == partition) {
            processEvent(event);
        }
        
        // 批量处理优化
        if (endOfBatch) {
            flushBatch();
        }
    }
}
```

## 12.2 Spring Events性能优化

```java
@Configuration
public class EventPerformanceConfig {
    
    @Bean
    @Primary
    public ApplicationEventMulticaster optimizedEventMulticaster() {
        SimpleApplicationEventMulticaster multicaster = 
            new SimpleApplicationEventMulticaster();
        
        // 优化的线程池配置
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors());
        executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("event-");
        executor.setRejectedExecutionHandler(new CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        
        multicaster.setTaskExecutor(executor);
        
        // 设置错误处理器
        multicaster.setErrorHandler(new EventErrorHandler());
        
        return multicaster;
    }
}

// 事件批量处理器
@Component
public class BatchEventProcessor {
    
    private final List<TradingEvent> buffer = new ArrayList<>();
    private final int batchSize = 100;
    private final long maxLatency = 10; // ms
    private volatile long lastFlush = System.currentTimeMillis();
    
    @Scheduled(fixedDelay = 10)
    public void flushOnTime() {
        if (System.currentTimeMillis() - lastFlush > maxLatency) {
            flush();
        }
    }
    
    public synchronized void addEvent(TradingEvent event) {
        buffer.add(event);
        if (buffer.size() >= batchSize) {
            flush();
        }
    }
    
    private synchronized void flush() {
        if (!buffer.isEmpty()) {
            processBatch(new ArrayList<>(buffer));
            buffer.clear();
            lastFlush = System.currentTimeMillis();
        }
    }
}
```

## 12.3 内存队列监控

```java
@Component
@Slf4j
public class QueueMonitor {
    
    private final MeterRegistry meterRegistry;
    private final Map<String, QueueMetrics> metrics = new ConcurrentHashMap<>();
    
    @Scheduled(fixedDelay = 1000)
    public void monitorQueues() {
        // 监控Disruptor
        monitorDisruptor();
        
        // 监控BlockingQueue
        monitorBlockingQueues();
        
        // 监控线程池
        monitorThreadPools();
    }
    
    private void monitorDisruptor() {
        RingBuffer<?> ringBuffer = disruptor.getRingBuffer();
        
        long capacity = ringBuffer.getBufferSize();
        long remaining = ringBuffer.remainingCapacity();
        long used = capacity - remaining;
        
        meterRegistry.gauge("disruptor.usage", 
            (double) used / capacity * 100);
        meterRegistry.gauge("disruptor.remaining", remaining);
        
        if (used > capacity * 0.8) {
            log.warn("Disruptor使用率超过80%: {}/{}", used, capacity);
        }
    }
    
    private void monitorBlockingQueues() {
        queues.forEach((name, queue) -> {
            int size = queue.size();
            int remaining = queue.remainingCapacity();
            
            meterRegistry.gauge("queue." + name + ".size", size);
            meterRegistry.gauge("queue." + name + ".remaining", remaining);
            
            if (remaining < 100) {
                log.warn("队列 {} 即将满: 剩余容量 {}", name, remaining);
            }
        });
    }
}
```

## 12.4 性能测试基准

```java
@SpringBootTest
public class QueuePerformanceTest {
    
    @Test
    public void benchmarkDisruptor() {
        int messages = 1_000_000;
        
        long start = System.nanoTime();
        
        for (int i = 0; i < messages; i++) {
            ringBuffer.publishEvent((event, sequence) -> {
                event.setType(EventType.MARKET_DATA);
                event.setTimestamp(System.currentTimeMillis());
            });
        }
        
        long elapsed = System.nanoTime() - start;
        double throughput = messages / (elapsed / 1_000_000_000.0);
        double latency = elapsed / messages / 1000.0; // μs
        
        System.out.printf("Disruptor性能:\n");
        System.out.printf("  吞吐量: %.0f msg/s\n", throughput);
        System.out.printf("  平均延迟: %.2f μs\n", latency);
        
        assertTrue(throughput > 500_000); // 期望 > 50万/秒
        assertTrue(latency < 2); // 期望 < 2μs
    }
}
```