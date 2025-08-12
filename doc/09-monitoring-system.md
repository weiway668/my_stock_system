# 轻量级监控系统

## 九、轻量级监控系统

### 9.1 自定义监控服务

```java
@Service
@Slf4j
public class MonitoringService {
    
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicDouble> gauges = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> histograms = new ConcurrentHashMap<>();
    
    // 计数器
    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }
    
    // 仪表
    public void updateGauge(String name, double value) {
        gauges.computeIfAbsent(name, k -> new AtomicDouble()).set(value);
    }
    
    // 直方图（记录响应时间等）
    public void recordTime(String name, long milliseconds) {
        histograms.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>())
            .add(milliseconds);
    }
    
    // 获取监控数据
    public MonitoringData getMonitoringData() {
        MonitoringData data = new MonitoringData();
        
        // 收集计数器
        counters.forEach((name, counter) -> 
            data.addCounter(name, counter.get()));
        
        // 收集仪表
        gauges.forEach((name, gauge) -> 
            data.addGauge(name, gauge.get()));
        
        // 收集直方图统计
        histograms.forEach((name, times) -> {
            if (!times.isEmpty()) {
                DoubleSummaryStatistics stats = times.stream()
                    .mapToDouble(Long::doubleValue)
                    .summaryStatistics();
                data.addHistogram(name, stats);
            }
        });
        
        // 添加系统指标
        addSystemMetrics(data);
        
        return data;
    }
    
    private void addSystemMetrics(MonitoringData data) {
        Runtime runtime = Runtime.getRuntime();
        
        // JVM内存
        data.addGauge("jvm.memory.used", 
            runtime.totalMemory() - runtime.freeMemory());
        data.addGauge("jvm.memory.free", runtime.freeMemory());
        data.addGauge("jvm.memory.total", runtime.totalMemory());
        data.addGauge("jvm.memory.max", runtime.maxMemory());
        
        // 线程
        data.addGauge("jvm.threads.count", 
            Thread.activeCount());
        
        // GC（简化版）
        List<GarbageCollectorMXBean> gcBeans = 
            ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            data.addCounter("jvm.gc.count." + gcBean.getName(), 
                gcBean.getCollectionCount());
            data.addCounter("jvm.gc.time." + gcBean.getName(), 
                gcBean.getCollectionTime());
        }
    }
    
    // 定期清理历史数据
    @Scheduled(fixedDelay = 3600000) // 每小时
    public void cleanupOldData() {
        histograms.forEach((name, times) -> {
            if (times.size() > 1000) {
                // 只保留最近1000条记录
                List<Long> recent = times.subList(
                    Math.max(0, times.size() - 1000), 
                    times.size()
                );
                times.clear();
                times.addAll(recent);
            }
        });
    }
}
```

### 9.2 监控数据模型

```java
@Data
public class MonitoringData {
    private Map<String, Long> counters = new HashMap<>();
    private Map<String, Double> gauges = new HashMap<>();
    private Map<String, HistogramStats> histograms = new HashMap<>();
    private LocalDateTime timestamp = LocalDateTime.now();
    
    public void addCounter(String name, long value) {
        counters.put(name, value);
    }
    
    public void addGauge(String name, double value) {
        gauges.put(name, value);
    }
    
    public void addHistogram(String name, DoubleSummaryStatistics stats) {
        histograms.put(name, new HistogramStats(stats));
    }
    
    @Data
    @AllArgsConstructor
    public static class HistogramStats {
        private double min;
        private double max;
        private double average;
        private long count;
        
        public HistogramStats(DoubleSummaryStatistics stats) {
            this.min = stats.getMin();
            this.max = stats.getMax();
            this.average = stats.getAverage();
            this.count = stats.getCount();
        }
    }
}
```

### 9.3 监控API

```java
@RestController
@RequestMapping("/api/v1/monitor")
public class MonitoringController {
    
    private final MonitoringService monitoringService;
    private final TradingService tradingService;
    private final OrderRepository orderRepository;
    
    // 获取实时监控数据
    @GetMapping("/metrics")
    public ResponseEntity<MonitoringData> getMetrics() {
        return ResponseEntity.ok(monitoringService.getMonitoringData());
    }
    
    // 获取交易统计
    @GetMapping("/trading/stats")
    public ResponseEntity<TradingStats> getTradingStats(
            @RequestParam(defaultValue = "1") Integer days) {
        
        LocalDateTime from = LocalDateTime.now().minusDays(days);
        
        TradingStats stats = new TradingStats();
        stats.setTotalOrders(orderRepository.countByCreateTimeAfter(from));
        stats.setSuccessfulOrders(orderRepository.countByStatusAndCreateTimeAfter(
            OrderStatus.FILLED, from));
        stats.setFailedOrders(orderRepository.countByStatusAndCreateTimeAfter(
            OrderStatus.REJECTED, from));
        
        // 计算盈亏
        BigDecimal totalPnl = orderRepository.sumPnlByCreateTimeAfter(from);
        stats.setTotalPnl(totalPnl);
        
        // 获取当前持仓
        stats.setActivePositions(positionRepository.countByQuantityGreaterThan(0));
        
        return ResponseEntity.ok(stats);
    }
    
    // 健康检查增强版
    @GetMapping("/health/detailed")
    public ResponseEntity<HealthStatus> getDetailedHealth() {
        HealthStatus health = new HealthStatus();
        
        // 检查数据库连接
        health.addComponent("database", checkDatabase());
        
        // 检查Redis连接
        health.addComponent("redis", checkRedis());
        
        // 检查内存队列状态
        health.addComponent("memoryQueue", checkMemoryQueues());
        
        // 检查市场数据连接
        health.addComponent("marketData", checkMarketData());
        
        health.setOverallStatus(health.isAllHealthy() ? "UP" : "DOWN");
        
        return ResponseEntity.ok(health);
    }
    
    // 获取系统信息
    @GetMapping("/system/info")
    public ResponseEntity<SystemInfo> getSystemInfo() {
        SystemInfo info = new SystemInfo();
        
        // JVM信息
        Runtime runtime = Runtime.getRuntime();
        info.setJvmVersion(System.getProperty("java.version"));
        info.setJvmVendor(System.getProperty("java.vendor"));
        info.setMaxMemory(runtime.maxMemory());
        info.setTotalMemory(runtime.totalMemory());
        info.setFreeMemory(runtime.freeMemory());
        info.setProcessors(runtime.availableProcessors());
        
        // 系统信息
        info.setOsName(System.getProperty("os.name"));
        info.setOsVersion(System.getProperty("os.version"));
        info.setOsArch(System.getProperty("os.arch"));
        
        // 应用信息
        info.setAppVersion(getClass().getPackage().getImplementationVersion());
        info.setStartupTime(applicationStartupTime);
        info.setUptime(Duration.between(applicationStartupTime, LocalDateTime.now()));
        
        return ResponseEntity.ok(info);
    }
}
```