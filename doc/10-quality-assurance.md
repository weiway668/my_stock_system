# 防止类膨胀的架构约束和质量保证

## 10.1 架构约束（强制执行）

### 10.1.1 类大小限制
```java
// 使用ArchUnit强制执行架构规则
@Test
public class ArchitectureTest {
    
    @Test
    public void classes_should_not_exceed_200_lines() {
        JavaClasses classes = new ClassFileImporter()
            .importPackages("com.trading");
        
        ArchRule rule = classes()
            .should(haveLinesLessThan(200))
            .because("类不应超过200行，防止类膨胀");
        
        rule.check(classes);
    }
    
    @Test
    public void methods_should_not_exceed_30_lines() {
        ArchRule rule = methods()
            .should(haveLinesLessThan(30))
            .because("方法不应超过30行，保持单一职责");
        
        rule.check(importedClasses);
    }
    
    @Test
    public void classes_should_have_single_responsibility() {
        ArchRule rule = classes()
            .that().arePublic()
            .should().haveOnlyFinalFields()
            .orShould().haveNameMatching(".*Service")
            .orShould().haveNameMatching(".*Repository")
            .orShould().haveNameMatching(".*Controller")
            .because("类应该有明确的单一职责");
        
        rule.check(importedClasses);
    }
}
```

### 10.1.2 复杂度限制
```java
// SonarQube质量门禁配置
sonar.qualitygate.conditions:
  - metric: cognitive_complexity
    op: GT
    error: 15  # 认知复杂度不能超过15
  - metric: file_complexity
    op: GT
    error: 200  # 文件复杂度不能超过200
  - metric: class_complexity
    op: GT
    error: 50  # 类复杂度不能超过50
  - metric: function_complexity
    op: GT
    error: 10  # 函数圈复杂度不能超过10
```

## 10.2 设计模式防止类膨胀

### 10.2.1 命令模式拆分交易逻辑
```java
// 命令接口
public interface TradingCommand {
    void execute();
    void undo();
    boolean canExecute();
}

// 具体命令实现
@Component
public class BuyOrderCommand implements TradingCommand {
    private final Order order;
    private final OrderRepository repository;
    private final RiskValidator riskValidator;
    
    @Override
    public boolean canExecute() {
        return riskValidator.validate(order).isPassed();
    }
    
    @Override
    public void execute() {
        // 执行买入逻辑（20-30行）
        validateOrder();
        calculatePosition();
        submitOrder();
        updatePortfolio();
    }
    
    @Override
    public void undo() {
        // 撤销逻辑
        cancelOrder();
        restorePortfolio();
    }
}

// 命令执行器（原本会膨胀的TradingService被拆分）
@Service
public class TradingCommandExecutor {
    private final Map<String, TradingCommand> commands;
    private final Queue<TradingCommand> executedCommands;
    
    public void executeCommand(String commandType, Order order) {
        TradingCommand command = commands.get(commandType);
        if (command.canExecute()) {
            command.execute();
            executedCommands.offer(command);
        }
    }
}
```

### 10.2.2 策略模式拆分信号逻辑
```java
// 策略接口
public interface SignalStrategy {
    Optional<TradingSignal> evaluate(MarketData data);
    BigDecimal getWeight();
    String getName();
}

// 具体策略实现（每个策略一个类，避免单个类过大）
@Component
public class VolatilityStrategy implements SignalStrategy {
    @Override
    public Optional<TradingSignal> evaluate(MarketData data) {
        // 仅包含波动率检查逻辑（30-40行）
        BigDecimal atr = data.getIndicators().getAtr();
        BigDecimal score = calculateVolatilityScore(atr);
        return score.compareTo(threshold) > 0 ? 
            Optional.of(createSignal(score)) : Optional.empty();
    }
}

@Component
public class VolumeStrategy implements SignalStrategy {
    @Override
    public Optional<TradingSignal> evaluate(MarketData data) {
        // 仅包含成交量检查逻辑（30-40行）
    }
}

// 策略组合器（避免StrategyService膨胀）
@Service
public class StrategyComposer {
    private final List<SignalStrategy> strategies;
    
    public Optional<TradingSignal> compose(MarketData data) {
        Map<String, BigDecimal> scores = strategies.stream()
            .collect(Collectors.toMap(
                SignalStrategy::getName,
                s -> s.evaluate(data)
                    .map(TradingSignal::getStrength)
                    .orElse(BigDecimal.ZERO)
            ));
        
        BigDecimal totalScore = calculateWeightedScore(scores);
        return totalScore.compareTo(threshold) > 0 ?
            Optional.of(createCompositeSignal(scores)) : Optional.empty();
    }
}
```

### 10.2.3 责任链模式处理风险验证
```java
// 抽象处理器
public abstract class RiskHandler {
    protected RiskHandler nextHandler;
    
    public void setNext(RiskHandler handler) {
        this.nextHandler = handler;
    }
    
    public RiskValidation handle(TradingSignal signal) {
        RiskValidation validation = validate(signal);
        if (!validation.isPassed()) {
            return validation;
        }
        return nextHandler != null ? 
            nextHandler.handle(signal) : validation;
    }
    
    protected abstract RiskValidation validate(TradingSignal signal);
}

// 具体处理器（每个风险检查一个类）
@Component
public class PositionLimitHandler extends RiskHandler {
    @Override
    protected RiskValidation validate(TradingSignal signal) {
        // 仅检查仓位限制（15-20行）
        BigDecimal currentPosition = getPosition(signal.getSymbol());
        if (currentPosition.compareTo(maxPosition) > 0) {
            return RiskValidation.failed("超过仓位限制");
        }
        return RiskValidation.passed();
    }
}

@Component
public class DailyLossHandler extends RiskHandler {
    @Override
    protected RiskValidation validate(TradingSignal signal) {
        // 仅检查日内损失（15-20行）
    }
}

// 风险链构建器
@Configuration
public class RiskChainConfig {
    @Bean
    public RiskHandler riskChain() {
        PositionLimitHandler positionHandler = new PositionLimitHandler();
        DailyLossHandler dailyLossHandler = new DailyLossHandler();
        ConsecutiveLossHandler consecutiveHandler = new ConsecutiveLossHandler();
        
        positionHandler.setNext(dailyLossHandler);
        dailyLossHandler.setNext(consecutiveHandler);
        
        return positionHandler;
    }
}
```

### 10.2.4 规则引擎模式
```java
// 规则接口
public interface TradingRule {
    boolean evaluate(RuleContext context);
    Action getAction();
    int getPriority();
}

// 规则实现（每个规则一个小类）
@Component
public class StopLossRule implements TradingRule {
    @Override
    public boolean evaluate(RuleContext context) {
        // 简单的止损规则（10-15行）
        return context.getCurrentPrice()
            .compareTo(context.getStopLossPrice()) <= 0;
    }
    
    @Override
    public Action getAction() {
        return Action.SELL_IMMEDIATELY;
    }
}

// 规则引擎（避免在Service中硬编码规则）
@Service
public class TradingRuleEngine {
    private final List<TradingRule> rules;
    
    public List<Action> evaluate(RuleContext context) {
        return rules.stream()
            .filter(rule -> rule.evaluate(context))
            .sorted(Comparator.comparing(TradingRule::getPriority))
            .map(TradingRule::getAction)
            .collect(Collectors.toList());
    }
}
```

## 10.3 插件化架构设计

### 10.3.1 插件接口定义
```java
// 插件接口
public interface TradingPlugin {
    String getName();
    String getVersion();
    void initialize(PluginContext context);
    void execute(MarketData data);
    void shutdown();
}

// 插件上下文
public class PluginContext {
    private final ApplicationContext springContext;
    private final Map<String, Object> configuration;
    private final EventPublisher eventPublisher;
    
    public <T> T getBean(Class<T> type) {
        return springContext.getBean(type);
    }
}

// 插件管理器
@Service
public class PluginManager {
    private final Map<String, TradingPlugin> plugins = new ConcurrentHashMap<>();
    private final PluginLoader loader;
    
    public void loadPlugin(String jarPath) {
        TradingPlugin plugin = loader.load(jarPath);
        plugin.initialize(createContext());
        plugins.put(plugin.getName(), plugin);
    }
    
    public void executePlugins(MarketData data) {
        plugins.values().parallelStream()
            .forEach(plugin -> plugin.execute(data));
    }
}
```

### 10.3.2 扩展点机制
```java
// 扩展点接口
public interface ExtensionPoint {
    String getExtensionPointId();
}

// 信号过滤扩展点
public interface SignalFilterExtension extends ExtensionPoint {
    TradingSignal filter(TradingSignal signal);
}

// 风险检查扩展点
public interface RiskCheckExtension extends ExtensionPoint {
    RiskValidation check(Order order);
}

// 扩展点注册器
@Component
public class ExtensionRegistry {
    private final Map<String, List<ExtensionPoint>> extensions = new ConcurrentHashMap<>();
    
    public void register(ExtensionPoint extension) {
        extensions.computeIfAbsent(
            extension.getExtensionPointId(), 
            k -> new CopyOnWriteArrayList<>()
        ).add(extension);
    }
    
    @SuppressWarnings("unchecked")
    public <T extends ExtensionPoint> List<T> getExtensions(String pointId) {
        return (List<T>) extensions.getOrDefault(pointId, Collections.emptyList());
    }
}
```

## 10.4 质量保证工具集成

### 10.4.1 Maven配置
```xml
<build>
    <plugins>
        <!-- SpotBugs静态代码分析 -->
        <plugin>
            <groupId>com.github.spotbugs</groupId>
            <artifactId>spotbugs-maven-plugin</artifactId>
            <version>4.7.3</version>
            <configuration>
                <effort>Max</effort>
                <threshold>Low</threshold>
                <failOnError>true</failOnError>
            </configuration>
        </plugin>
        
        <!-- 代码覆盖率 -->
        <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>0.8.10</version>
            <configuration>
                <rules>
                    <rule>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </plugin>
        
        <!-- 强制架构规则 -->
        <plugin>
            <groupId>com.societegenerale.commons</groupId>
            <artifactId>arch-unit-maven-plugin</artifactId>
            <version>3.0.0</version>
            <configuration>
                <rules>
                    <preConfiguredRules>
                        <rule>com.societegenerale.commons.plugin.rules.NoPublicFieldRuleTest</rule>
                        <rule>com.societegenerale.commons.plugin.rules.NoAutowiredFieldTest</rule>
                    </preConfiguredRules>
                </rules>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 10.4.2 CI/CD质量门禁
```yaml
# .gitlab-ci.yml 或 Jenkins Pipeline
quality-check:
  stage: test
  script:
    # 代码行数检查
    - find src -name "*.java" -exec wc -l {} \; | awk '$1 > 200 {print $2 " exceeds 200 lines"}'
    
    # 圈复杂度检查
    - mvn com.github.spotbugs:spotbugs-maven-plugin:check
    
    # 架构规则检查
    - mvn arch-unit:arch-unit-maven-plugin:arch-test
    
    # 代码覆盖率检查
    - mvn jacoco:check
    
    # SonarQube扫描
    - mvn sonar:sonar -Dsonar.projectKey=trading-system
    
  only:
    - merge_requests
    - develop
    - master
```

## 10.5 重构指南（处理遗留代码）

### 10.5.1 大类拆分步骤
```java
// 原始膨胀类示例
public class TradingService {
    // 1000+ 行代码，承担了太多职责
    public Order createOrder() { /* 100行 */ }
    public void validateOrder() { /* 80行 */ }
    public void calculatePosition() { /* 120行 */ }
    public void checkRisk() { /* 150行 */ }
    public void executeOrder() { /* 100行 */ }
    // ... 更多方法
}

// 重构后：按职责拆分
@Service
public class OrderCreationService {
    public Order createOrder() { /* 专注订单创建 */ }
}

@Service
public class OrderValidationService {
    public ValidationResult validate(Order order) { /* 专注验证 */ }
}

@Service
public class PositionCalculator {
    public Position calculate(Order order) { /* 专注计算 */ }
}

@Service
public class RiskChecker {
    public RiskResult check(Order order) { /* 专注风险检查 */ }
}

@Service
public class OrderExecutor {
    public ExecutionResult execute(Order order) { /* 专注执行 */ }
}

// 门面模式聚合
@Service
public class TradingFacade {
    private final OrderCreationService creationService;
    private final OrderValidationService validationService;
    private final PositionCalculator positionCalculator;
    private final RiskChecker riskChecker;
    private final OrderExecutor executor;
    
    public Order processOrder(OrderRequest request) {
        Order order = creationService.createOrder(request);
        validationService.validate(order);
        positionCalculator.calculate(order);
        riskChecker.check(order);
        return executor.execute(order);
    }
}
```