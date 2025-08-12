# 策略配置管理系统

## 五、策略配置管理系统

### 5.1 策略配置实体

```java
@Entity
@Table(name = "strategy_configs")
@EntityListeners(AuditingEntityListener.class)
public class StrategyConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String configId;
    
    @Column(unique = true)
    private String strategyName;
    
    @Enumerated(EnumType.STRING)
    private StrategyStatus status; // ACTIVE, INACTIVE, TESTING
    
    @Version
    private Long version; // 乐观锁版本控制
    
    // 基础配置
    @Embedded
    private BasicConfig basicConfig;
    
    // 四层过滤器配置
    @Embedded
    private FilterConfig filterConfig;
    
    // 风险控制配置
    @Embedded
    private RiskConfig riskConfig;
    
    // 交易时段配置
    @Embedded
    private TradingSessionConfig sessionConfig;
    
    // JSON扩展配置
    @Column(columnDefinition = "jsonb")
    @Type(type = "jsonb")
    private Map<String, Object> extendedConfig;
    
    // 审计字段
    @CreatedDate
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    private LocalDateTime updatedAt;
    
    @CreatedBy
    private String createdBy;
    
    @LastModifiedBy
    private String modifiedBy;
    
    // 配置生效时间
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
}
```

### 5.2 配置嵌入对象

```java
// 基础配置
@Embeddable
public class BasicConfig {
    private String symbol;
    private BigDecimal initialCapital;
    private BigDecimal targetReturn;
    private BigDecimal maxDrawdown;
    private Integer maxPositions;
    private Integer tradingPeriod; // 分钟
}

// 四层过滤器配置
@Embeddable
public class FilterConfig {
    // 总体配置
    private BigDecimal overallThreshold;
    private Boolean requireAllLayersPass;
    
    // 波动率层 (15%)
    private BigDecimal volatilityWeight;
    private BigDecimal minAtrRatio;
    private BigDecimal maxAtrRatio;
    private Integer atrPeriod;
    
    // 成交量层 (25%)
    private BigDecimal volumeWeight;
    private BigDecimal minVolumeRatio;
    private BigDecimal volumeSurgeThreshold;
    private Integer volumePeriod;
    
    // 趋势层 (35%)
    private BigDecimal trendWeight;
    private BigDecimal adxThreshold;
    private Integer adxPeriod;
    private Integer emaShortPeriod;
    private Integer emaLongPeriod;
    
    // 确认层 (25%)
    private BigDecimal confirmationWeight;
    private Integer macdFast;
    private Integer macdSlow;
    private Integer macdSignal;
    private BigDecimal macdThreshold;
}

// 风险配置
@Embeddable
public class RiskConfig {
    private BigDecimal stopLossPercent;
    private BigDecimal takeProfitPercent;
    private BigDecimal trailingStopPercent;
    
    private BigDecimal maxPositionSize;
    private BigDecimal maxDailyLoss;
    private BigDecimal maxConsecutiveLosses;
    
    private BigDecimal atrMultiplier;
    private Boolean useAdaptiveStopLoss;
    private Boolean useTieredProfit;
}

// 交易时段配置
@Embeddable
public class TradingSessionConfig {
    private LocalTime morningOpenTime;
    private LocalTime morningCloseTime;
    private LocalTime afternoonOpenTime;
    private LocalTime afternoonCloseTime;
    
    private Boolean allowPreMarket;
    private Boolean allowAfterMarket;
    
    private LocalTime noEntryAfter; // 禁止开仓时间
    private LocalTime forceCloseTime; // 强制平仓时间
}
```

### 5.3 配置管理服务

```java
@Service
@Slf4j
public class StrategyConfigService {
    
    private final StrategyConfigRepository repository;
    private final ConfigChangeEventPublisher eventPublisher;
    private final ConfigValidator validator;
    private final ConfigCache configCache;
    
    // 获取配置（带缓存）
    @Cacheable(value = "strategyConfig", key = "#strategyName")
    public StrategyConfig getConfig(String strategyName) {
        return repository.findByStrategyNameAndStatus(
            strategyName, StrategyStatus.ACTIVE
        ).orElseThrow(() -> 
            new ConfigNotFoundException(strategyName)
        );
    }
    
    // 更新配置
    @Transactional
    @CacheEvict(value = "strategyConfig", key = "#strategyName")
    public StrategyConfig updateConfig(
            String strategyName, 
            StrategyConfigDTO dto) {
        
        StrategyConfig config = repository
            .findByStrategyName(strategyName)
            .orElseThrow();
        
        // 验证配置
        ValidationResult result = validator.validate(dto);
        if (!result.isValid()) {
            throw new InvalidConfigException(result.getErrors());
        }
        
        // 保存历史版本
        saveConfigHistory(config);
        
        // 更新配置
        updateConfigFields(config, dto);
        config.setVersion(config.getVersion() + 1);
        
        StrategyConfig saved = repository.save(config);
        
        // 发布配置变更事件
        eventPublisher.publishConfigChange(
            new ConfigChangeEvent(strategyName, saved)
        );
        
        log.info("策略配置已更新: {} v{}", 
            strategyName, saved.getVersion());
        
        return saved;
    }
    
    // 配置热加载
    @EventListener
    public void handleConfigReload(ConfigReloadEvent event) {
        String strategyName = event.getStrategyName();
        
        // 清除缓存
        configCache.evict(strategyName);
        
        // 重新加载
        StrategyConfig config = repository
            .findByStrategyNameAndStatus(
                strategyName, StrategyStatus.ACTIVE
            ).orElse(null);
        
        if (config != null) {
            // 通知策略引擎
            strategyEngine.reloadConfig(strategyName, config);
            log.info("策略配置已热加载: {}", strategyName);
        }
    }
    
    // 配置版本管理
    public List<ConfigVersion> getConfigHistory(
            String strategyName) {
        return configHistoryRepository
            .findByStrategyNameOrderByVersionDesc(strategyName);
    }
    
    // 配置回滚
    @Transactional
    public StrategyConfig rollbackConfig(
            String strategyName, 
            Long targetVersion) {
        
        ConfigVersion history = configHistoryRepository
            .findByStrategyNameAndVersion(strategyName, targetVersion)
            .orElseThrow();
        
        StrategyConfig current = repository
            .findByStrategyName(strategyName)
            .orElseThrow();
        
        // 保存当前版本到历史
        saveConfigHistory(current);
        
        // 恢复历史版本
        current.setFilterConfig(history.getFilterConfig());
        current.setRiskConfig(history.getRiskConfig());
        current.setVersion(current.getVersion() + 1);
        
        return repository.save(current);
    }
}
```