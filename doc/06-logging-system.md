# 日志管理系统

## 六、日志管理系统

### 6.1 结构化日志模型

```java
// 交易日志实体 (数据库表)
@Entity
@Table(name = "trading_logs", indexes = {
    @Index(name = "idx_timestamp", columnList = "timestamp"),
    @Index(name = "idx_level", columnList = "level"),
    @Index(name = "idx_category", columnList = "category"),
    @Index(name = "idx_symbol", columnList = "symbol")
})
public class TradingLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    @Column(length = 10)
    private String level; // INFO, WARN, ERROR, DEBUG
    
    @Column(length = 20)
    private String category; // MARKET, SIGNAL, ORDER, RISK, SYSTEM
    
    @Column(nullable = false)
    private LocalDateTime timestamp;
    
    @Column(length = 50)
    private String strategyName;
    
    @Column(length = 20)
    private String symbol;
    
    @Column(columnDefinition = "TEXT")
    private String message;
    
    // 业务相关字段（JSON存储）
    @Column(columnDefinition = "TEXT")
    @Convert(converter = BusinessContextConverter.class)
    private BusinessContext context;
    
    // 性能指标（JSON存储）
    @Column(columnDefinition = "TEXT")
    @Convert(converter = PerformanceMetricsConverter.class)
    private PerformanceMetrics metrics;
    
    // 异常信息（JSON存储）
    @Column(columnDefinition = "TEXT")
    @Convert(converter = ExceptionInfoConverter.class)
    private ExceptionInfo exception;
    
    @Data
    public static class BusinessContext {
        private String orderId;
        private String signalId;
        private BigDecimal price;
        private Integer quantity;
        private Map<String, Object> additionalData;
    }
    
    @Data
    public static class PerformanceMetrics {
        private Long executionTime;
        private Long responseTime;
        private Integer dataPoints;
        private Map<String, Long> timings;
    }
    
    @Data
    public static class ExceptionInfo {
        private String className;
        private String message;
        private String stackTrace;
        private Map<String, String> context;
    }
}

// 审计日志实体
@Entity
@Table(name = "audit_logs")
@Immutable
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String auditId;
    
    @Enumerated(EnumType.STRING)
    private AuditAction action; // CREATE, UPDATE, DELETE, EXECUTE
    
    @Enumerated(EnumType.STRING)
    private AuditEntity entityType; // ORDER, POSITION, CONFIG
    
    private String entityId;
    
    private String userId;
    private String userIp;
    
    @Column(columnDefinition = "TEXT")
    private String beforeState;
    
    @Column(columnDefinition = "TEXT")
    private String afterState;
    
    private LocalDateTime timestamp;
    
    private String description;
}
```

### 6.2 日志服务实现

```java
@Service
@Slf4j
public class TradingLogService {
    
    private final TradingLogRepository logRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final FileLogWriter fileLogWriter;
    
    // 异步记录日志
    @Async
    public void log(LogLevel level, LogCategory category, 
                   String message, LogContext context) {
        
        TradingLog log = TradingLog.builder()
            .id(UUID.randomUUID().toString())
            .level(level.name())
            .category(category.name())
            .timestamp(LocalDateTime.now())
            .message(message)
            .strategyName(context.getStrategyName())
            .symbol(context.getSymbol())
            .context(buildBusinessContext(context))
            .metrics(captureMetrics())
            .build();
        
        // 1. 写入数据库
        logRepository.save(log);
        
        // 2. 写入文件日志
        fileLogWriter.write(log);
        
        // 3. 发送到消息队列（可选）
        if (level == LogLevel.ERROR || level == LogLevel.WARN) {
            eventPublisher.publishEvent(new LogAlertEvent(log));
        }
    }
    
    // 记录异常
    public void logError(String message, Exception e, 
                        LogContext context) {
        
        TradingLog log = TradingLog.builder()
            .id(UUID.randomUUID().toString())
            .level("ERROR")
            .category("SYSTEM")
            .timestamp(LocalDateTime.now())
            .message(message)
            .exception(buildExceptionInfo(e))
            .context(buildBusinessContext(context))
            .build();
        
        // 保存到数据库
        logRepository.save(log);
        
        // 写入错误日志文件
        fileLogWriter.writeError(log);
        
        // 触发告警（简单邮件或钉钉通知）
        alertService.sendAlert(AlertLevel.ERROR, message, e);
    }
    
    // 记录性能日志
    public void logPerformance(String operation, 
                              long executionTime,
                              Map<String, Long> breakdowns) {
        
        PerformanceMetrics metrics = new PerformanceMetrics();
        metrics.setExecutionTime(executionTime);
        metrics.setTimings(breakdowns);
        
        TradingLog log = TradingLog.builder()
            .id(UUID.randomUUID().toString())
            .level("INFO")
            .category("PERFORMANCE")
            .timestamp(LocalDateTime.now())
            .message("Performance: " + operation)
            .metrics(metrics)
            .build();
        
        // 保存性能日志
        logRepository.save(log);
        fileLogWriter.writePerformance(log);
    }
}
```

### 6.3 文件日志写入器

```java
@Component
@Slf4j
public class FileLogWriter {
    
    private final String logDirectory = "logs/trading";
    private final ObjectMapper objectMapper;
    private final DateTimeFormatter formatter = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    @PostConstruct
    public void init() {
        // 创建日志目录
        Path path = Paths.get(logDirectory);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                log.error("创建日志目录失败", e);
            }
        }
    }
    
    // 写入普通日志
    public void write(TradingLog log) {
        String filename = String.format("%s/trading-%s.log", 
            logDirectory, LocalDate.now().format(formatter));
        writeToFile(filename, formatLog(log));
    }
    
    // 写入错误日志
    public void writeError(TradingLog log) {
        String filename = String.format("%s/error-%s.log", 
            logDirectory, LocalDate.now().format(formatter));
        writeToFile(filename, formatErrorLog(log));
    }
    
    // 写入性能日志
    public void writePerformance(TradingLog log) {
        String filename = String.format("%s/performance-%s.log", 
            logDirectory, LocalDate.now().format(formatter));
        writeToFile(filename, formatPerformanceLog(log));
    }
    
    private void writeToFile(String filename, String content) {
        try (FileWriter writer = new FileWriter(filename, true);
             BufferedWriter bufferedWriter = new BufferedWriter(writer)) {
            bufferedWriter.write(content);
            bufferedWriter.newLine();
        } catch (IOException e) {
            log.error("写入日志文件失败: {}", filename, e);
        }
    }
    
    private String formatLog(TradingLog log) {
        return String.format("[%s] [%s] [%s] %s - %s | %s",
            log.getTimestamp(),
            log.getLevel(),
            log.getCategory(),
            log.getStrategyName(),
            log.getMessage(),
            toJson(log.getContext()));
    }
    
    private String formatErrorLog(TradingLog log) {
        return String.format("[%s] [ERROR] %s\nException: %s\nStack: %s\nContext: %s",
            log.getTimestamp(),
            log.getMessage(),
            log.getException().getClassName(),
            log.getException().getStackTrace(),
            toJson(log.getContext()));
    }
    
    private String formatPerformanceLog(TradingLog log) {
        return String.format("[%s] [PERF] %s | Execution: %dms | Details: %s",
            log.getTimestamp(),
            log.getMessage(),
            log.getMetrics().getExecutionTime(),
            toJson(log.getMetrics().getTimings()));
    }
    
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
```

### 6.4 日志切面（AOP）

```java
@Aspect
@Component
@Slf4j
public class TradingLogAspect {
    
    private final TradingLogService logService;
    
    // 记录所有交易操作
    @Around("@annotation(Loggable)")
    public Object logExecution(ProceedingJoinPoint joinPoint, 
                              Loggable loggable) throws Throwable {
        
        long startTime = System.currentTimeMillis();
        String operation = loggable.operation();
        
        LogContext context = extractContext(joinPoint);
        
        try {
            // 记录开始
            logService.log(LogLevel.INFO, loggable.category(),
                "开始执行: " + operation, context);
            
            // 执行方法
            Object result = joinPoint.proceed();
            
            // 记录成功
            long executionTime = System.currentTimeMillis() - startTime;
            logService.log(LogLevel.INFO, loggable.category(),
                "执行成功: " + operation + " 耗时: " + executionTime + "ms",
                context);
            
            // 记录性能
            if (executionTime > loggable.slowThreshold()) {
                logService.logPerformance(operation, executionTime,
                    Collections.emptyMap());
            }
            
            return result;
            
        } catch (Exception e) {
            // 记录失败
            logService.logError(
                "执行失败: " + operation, e, context);
            throw e;
        }
    }
    
    // 记录订单状态变化
    @AfterReturning(
        pointcut = "execution(* com.trading.service.OrderService.updateStatus(..))",
        returning = "order"
    )
    public void logOrderStatusChange(JoinPoint joinPoint, Order order) {
        LogContext context = LogContext.builder()
            .orderId(order.getOrderId())
            .symbol(order.getSymbol())
            .build();
        
        logService.log(LogLevel.INFO, LogCategory.ORDER,
            String.format("订单状态变更: %s -> %s",
                joinPoint.getArgs()[1], order.getStatus()),
            context);
    }
    
    // 记录信号生成
    @AfterReturning(
        pointcut = "execution(* com.trading.service.StrategyService.generateSignal(..))",
        returning = "signal"
    )
    public void logSignalGeneration(JoinPoint joinPoint, 
                                   Optional<TradingSignal> signal) {
        if (signal.isPresent()) {
            TradingSignal s = signal.get();
            LogContext context = LogContext.builder()
                .signalId(s.getSignalId())
                .symbol(s.getSymbol())
                .strategyName(s.getStrategyName())
                .additionalData(Map.of(
                    "type", s.getType(),
                    "strength", s.getStrength(),
                    "totalScore", s.getScores().getTotalScore()
                ))
                .build();
            
            logService.log(LogLevel.INFO, LogCategory.SIGNAL,
                "生成交易信号: " + s.getType(), context);
        }
    }
}

// 自定义注解
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Loggable {
    String operation();
    LogCategory category() default LogCategory.SYSTEM;
    long slowThreshold() default 1000; // ms
}
```