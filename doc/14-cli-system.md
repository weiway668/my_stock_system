# 港股程序化交易系统 - CLI系统文档

## 14.1 CLI架构概述

### 14.1.1 设计原则
CLI系统采用命令优先的设计理念，提供专业级量化交易体验：

```
┌─────────────────────────────────────────┐
│     TradingSystemStarter                │
│  - CommandLineRunner 入口               │
│  - 系统初始化和欢迎界面                  │
│  - 命令路由和异常处理                    │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│     CommandRegistry                     │
│  - 命令注册和发现                        │
│  - 别名支持                             │
│  - Spring自动装配命令                    │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│     AbstractCommand 基类                │
│  - 彩色输出和进度显示                    │
│  - 中文界面支持                          │
│  - 参数解析和验证                        │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│     具体命令实现                         │
│  - BacktestCommand                      │
│  - HelpCommand                          │
│  - 未来扩展命令...                       │
└─────────────────────────────────────────┘
```

### 14.1.2 核心特性
- **Spring Boot 集成**: 与系统核心服务无缝集成
- **中文界面**: 完整本地化支持，适合中文用户
- **彩色输出**: 彩色文字和 Emoji 增强视觉体验
- **进度显示**: 实时进度条显示长时间操作
- **专业报告**: 多格式输出，匹配 Python 版本标准
- **参数验证**: 全面的输入验证和错误处理

## 14.2 核心组件实现

### 14.2.1 主入口 (TradingSystemStarter)

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class TradingSystemStarter implements CommandLineRunner {
    
    private final CommandRegistry commandRegistry;
    
    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            printSystemInfo();
            printAvailableCommands();
            return;
        }
        
        String commandName = args[0];
        
        // 处理内置命令
        if (isBuiltinCommand(commandName)) {
            handleBuiltinCommand(commandName, args);
            return;
        }
        
        // 查找注册的命令
        Command command = commandRegistry.findCommand(commandName);
        if (command == null) {
            printError("❌ 未知命令: " + commandName);
            printInfo("💡 使用 'help' 查看可用命令");
            return;
        }
        
        // 执行命令
        try {
            String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);
            command.execute(commandArgs);
            
        } catch (CommandException e) {
            printError("❌ 命令执行失败: " + e.getMessage());
            if (e.getCause() != null) {
                log.debug("命令执行异常详情", e.getCause());
            }
            
        } catch (Exception e) {
            printError("❌ 系统错误: " + e.getMessage());
            log.error("CLI系统异常", e);
        }
    }
    
    private void printSystemInfo() {
        System.out.println(ANSI_CYAN + "港股程序化交易系统 CLI v1.0" + ANSI_RESET);
        System.out.println(ANSI_YELLOW + "🚀 专业量化交易回测分析平台" + ANSI_RESET);
        System.out.println();
        
        // 显示系统状态
        System.out.printf("📅 系统时间: %s%n", LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.printf("💻 Java版本: %s%n", System.getProperty("java.version"));
        System.out.printf("🗂️  工作目录: %s%n", System.getProperty("user.dir"));
        System.out.println();
    }
    
    private void printAvailableCommands() {
        System.out.println(ANSI_BOLD + "📋 可用命令:" + ANSI_RESET);
        System.out.println();
        
        commandRegistry.getAllCommands().stream()
            .sorted(Comparator.comparing(Command::getName))
            .forEach(cmd -> {
                String aliases = cmd.getAliases().isEmpty() ? 
                    "" : " (" + String.join(", ", cmd.getAliases()) + ")";
                
                System.out.printf("  %s%-12s%s %s%s%n",
                    ANSI_GREEN, cmd.getName() + aliases, ANSI_RESET,
                    cmd.getDescription(),
                    ANSI_RESET);
            });
        
        System.out.println();
        System.out.println(ANSI_BOLD + "💡 使用示例:" + ANSI_RESET);
        System.out.println("  java -jar trading.jar backtest --strategy MACD --symbol 02800.HK --from 2024-01-01");
        System.out.println("  java -jar trading.jar help backtest");
        System.out.println();
    }
}
```

### 14.2.2 命令注册器 (CommandRegistry)

```java
@Component
@Slf4j
public class CommandRegistry {
    
    private final Map<String, Command> commands = new ConcurrentHashMap<>();
    private final Map<String, Command> aliases = new ConcurrentHashMap<>();
    
    /**
     * Spring自动装配所有Command实现
     */
    @Autowired
    public CommandRegistry(List<Command> commandList) {
        commandList.forEach(this::registerCommand);
        log.info("已注册 {} 个命令", commands.size());
    }
    
    private void registerCommand(Command command) {
        String name = command.getName();
        if (commands.containsKey(name)) {
            throw new IllegalStateException("命令名称冲突: " + name);
        }
        
        commands.put(name, command);
        log.debug("注册命令: {}", name);
        
        // 注册别名
        command.getAliases().forEach(alias -> {
            if (aliases.containsKey(alias) || commands.containsKey(alias)) {
                log.warn("别名冲突，跳过: {} -> {}", alias, name);
            } else {
                aliases.put(alias, command);
                log.debug("注册别名: {} -> {}", alias, name);
            }
        });
    }
    
    public Command findCommand(String name) {
        Command command = commands.get(name);
        return command != null ? command : aliases.get(name);
    }
    
    public Collection<Command> getAllCommands() {
        return Collections.unmodifiableCollection(commands.values());
    }
    
    public boolean hasCommand(String name) {
        return commands.containsKey(name) || aliases.containsKey(name);
    }
}
```

### 14.2.3 命令基类 (AbstractCommand)

```java
@Slf4j
public abstract class AbstractCommand implements Command {
    
    // ANSI 颜色常量
    protected static final String ANSI_RESET = "\u001B[0m";
    protected static final String ANSI_BLACK = "\u001B[30m";
    protected static final String ANSI_RED = "\u001B[31m";
    protected static final String ANSI_GREEN = "\u001B[32m";
    protected static final String ANSI_YELLOW = "\u001B[33m";
    protected static final String ANSI_BLUE = "\u001B[34m";
    protected static final String ANSI_PURPLE = "\u001B[35m";
    protected static final String ANSI_CYAN = "\u001B[36m";
    protected static final String ANSI_WHITE = "\u001B[37m";
    protected static final String ANSI_BOLD = "\u001B[1m";
    protected static final String ANSI_UNDERLINE = "\u001B[4m";
    
    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }
    
    @Override
    public List<String> getExamples() {
        return Collections.emptyList();
    }
    
    // 输出工具方法
    protected void printSuccess(String message) {
        System.out.println(ANSI_GREEN + "✅ " + message + ANSI_RESET);
    }
    
    protected void printError(String message) {
        System.out.println(ANSI_RED + "❌ " + message + ANSI_RESET);
    }
    
    protected void printWarning(String message) {
        System.out.println(ANSI_YELLOW + "⚠️  " + message + ANSI_RESET);
    }
    
    protected void printInfo(String message) {
        System.out.println(ANSI_CYAN + "ℹ️  " + message + ANSI_RESET);
    }
    
    protected void printTableHeader(String title) {
        System.out.println();
        System.out.println(ANSI_BOLD + "=== " + title + " ===" + ANSI_RESET);
    }
    
    protected void printSeparator() {
        System.out.println("━".repeat(50));
    }
    
    protected void printUsageHeader(String usage) {
        System.out.println();
        System.out.println(ANSI_BOLD + "用法: " + ANSI_RESET + usage);
        System.out.println();
    }
    
    // Apache Commons CLI 工具方法
    protected CommandLine parseArgs(String[] args, Options options) throws CommandException {
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        
        try {
            return parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println(ANSI_RED + "参数解析错误: " + e.getMessage() + ANSI_RESET);
            System.err.println();
            
            // 显示使用帮助
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            formatter.printHelp(printWriter, 80, getName(), null, options, 2, 4, null);
            System.err.println(stringWriter.toString());
            
            throw CommandException.invalidArgument(getName(), args, e.getMessage());
        }
    }
    
    protected Option createOption(String opt, String longOpt, String description, boolean hasArg) {
        return Option.builder(opt)
            .longOpt(longOpt)
            .desc(description)
            .hasArg(hasArg)
            .build();
    }
    
    protected Option createRequiredOption(String opt, String longOpt, String description, boolean hasArg) {
        return Option.builder(opt)
            .longOpt(longOpt)
            .desc(description)
            .hasArg(hasArg)
            .required(true)
            .build();
    }
    
    protected Options createBaseOptions() {
        Options options = new Options();
        options.addOption("h", "help", false, "显示此帮助信息");
        options.addOption("v", "verbose", false, "详细输出");
        options.addOption("q", "quiet", false, "静默模式");
        return options;
    }
    
    protected void printOptions(Options options) {
        System.out.println(ANSI_BOLD + "选项:" + ANSI_RESET);
        HelpFormatter formatter = new HelpFormatter();
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        
        formatter.printOptions(printWriter, 80, options, 2, 4);
        System.out.println(stringWriter.toString());
    }
    
    protected void printExamples() {
        List<String> examples = getExamples();
        if (!examples.isEmpty()) {
            System.out.println(ANSI_BOLD + "示例:" + ANSI_RESET);
            examples.forEach(example -> System.out.println("  " + example));
            System.out.println();
        }
    }
    
    // 参数获取工具方法
    protected String getOptionValue(CommandLine cmd, String option, String defaultValue) {
        return cmd.hasOption(option) ? cmd.getOptionValue(option) : defaultValue;
    }
    
    protected boolean shouldShowHelp(CommandLine cmd) {
        return cmd.hasOption("help");
    }
    
    protected boolean isVerbose(CommandLine cmd) {
        return cmd.hasOption("verbose");
    }
    
    protected boolean isQuiet(CommandLine cmd) {
        return cmd.hasOption("quiet");
    }
    
    // 进度条工具
    protected void showProgress(String taskName, int current, int total) {
        int percentage = (current * 100) / total;
        int progressLength = 40;
        int filled = (current * progressLength) / total;
        
        String progressBar = "█".repeat(filled) + "░".repeat(progressLength - filled);
        
        System.out.printf("\r%s [%s] %d%% (%d/%d)",
            taskName, progressBar, percentage, current, total);
        
        if (current == total) {
            System.out.println(); // 完成后换行
        }
    }
}
```

### 14.2.4 回测命令实现 (BacktestCommand)

BacktestCommand 是系统的核心命令，提供完整的CLI回测功能：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestCommand extends AbstractCommand {
    
    private final BacktestEngine backtestEngine;
    private final BacktestReportGenerator reportGenerator;
    
    @Override
    public String getName() {
        return "backtest";
    }
    
    @Override
    public String getDescription() {
        return "执行策略回测";
    }
    
    @Override
    public List<String> getAliases() {
        return List.of("bt", "test");
    }
    
    @Override
    public List<String> getExamples() {
        return List.of(
            "backtest --strategy MACD --symbol 02800.HK --from 2024-01-01 --to 2024-12-31",
            "backtest -s MACD -sym 02800.HK --capital 100000 --output ./output",
            "bt --strategy MACD --symbol 02800.HK --from 2024-01-01 --verbose"
        );
    }
    
    @Override
    public void execute(String[] args) throws CommandException {
        long startTime = System.currentTimeMillis();
        
        try {
            // 解析参数
            Options options = createOptions();
            CommandLine cmd = parseArgs(args, options);
            
            if (shouldShowHelp(cmd)) {
                printUsage();
                return;
            }
            
            // 构建回测请求
            BacktestRequest request = parseBacktestRequest(cmd);
            request.validate();
            
            // 显示配置
            printBacktestConfig(request, isVerbose(cmd));
            
            // 执行回测
            printInfo("开始执行回测...");
            printSeparator();
            
            CompletableFuture<BacktestResult> future = backtestEngine.runBacktest(request);
            BacktestResult result = future.get();
            
            // 设置执行时间
            long executionTime = System.currentTimeMillis() - startTime;
            result.setExecutionTimeMs(executionTime);
            result.setReportGeneratedAt(LocalDateTime.now());
            
            // 显示结果
            printBacktestResult(result, isVerbose(cmd), isQuiet(cmd));
            
            // 生成报告
            if (request.isGenerateDetailedReport() && request.getOutputPath() != null) {
                generateReports(request, result);
            }
            
        } catch (CommandException e) {
            throw e;
        } catch (Exception e) {
            log.error("回测执行失败", e);
            throw CommandException.executionFailed(getName(), args, e.getMessage(), e);
        }
    }
    
    /**
     * 打印回测结果 - 专业格式输出
     */
    private void printBacktestResult(BacktestResult result, boolean verbose, boolean quiet) {
        if (quiet) {
            // 静默模式：仅关键指标，CSV格式
            System.out.printf("%.2f%%,%.2f%%,%.2f,%.1f%%,%d%n",
                result.getReturnRate(), result.getMaxDrawdown(), 
                result.getSharpeRatio(), result.getWinRate(), result.getTotalTrades());
            return;
        }
        
        if (!result.isSuccessful()) {
            printError("回测失败: " + result.getError());
            return;
        }
        
        printTableHeader("回测结果");
        
        // 基本信息
        System.out.printf("回测期间: %s 至 %s (%d天)%n", 
            result.getStartTime().toLocalDate(),
            result.getEndTime().toLocalDate(), 
            result.getBacktestDays());
        System.out.printf("执行耗时: %.1f秒%n", result.getExecutionTimeMs() / 1000.0);
        System.out.println();
        
        // 收益指标 - 彩色显示
        printSubHeader("📈 收益指标");
        System.out.printf("初始资金: %s¥%,.2f%s%n", ANSI_CYAN, result.getInitialCapital(), ANSI_RESET);
        System.out.printf("最终权益: %s¥%,.2f%s%n", ANSI_CYAN, result.getFinalEquity(), ANSI_RESET);
        System.out.printf("绝对收益: %s¥%,.2f%s%n", 
            result.getTotalReturn().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED,
            result.getTotalReturn(), ANSI_RESET);
        System.out.printf("总收益率: %s%.2f%%%s%n",
            result.getReturnRate().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED,
            result.getReturnRate(), ANSI_RESET);
        System.out.printf("年化收益: %s%.2f%%%s%n",
            result.getAnnualizedReturn().compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED,
            result.getAnnualizedReturn(), ANSI_RESET);
        System.out.println();
        
        // 风险指标
        printSubHeader("⚠️ 风险指标");
        System.out.printf("最大回撤: %s%.2f%%%s%n", ANSI_RED, result.getMaxDrawdown(), ANSI_RESET);
        System.out.printf("夏普比率: %.2f%n", result.getSharpeRatio());
        if (result.getSortinoRatio() != null) {
            System.out.printf("索提诺比率: %.2f%n", result.getSortinoRatio());
        }
        if (result.getCalmarRatio() != null) {
            System.out.printf("卡尔马比率: %.2f%n", result.getCalmarRatio());
        }
        System.out.println();
        
        // 交易统计
        printSubHeader("📊 交易统计");
        System.out.printf("总交易次数: %d%n", result.getTotalTrades());
        if (result.getTotalTrades() > 0) {
            System.out.printf("盈利交易: %s%d%s%n", ANSI_GREEN, result.getWinningTrades(), ANSI_RESET);
            System.out.printf("亏损交易: %s%d%s%n", ANSI_RED, result.getLosingTrades(), ANSI_RESET);
            System.out.printf("胜率: %.1f%%%n", result.getWinRate());
            System.out.printf("平均盈利: %s¥%.2f%s%n", ANSI_GREEN, result.getAvgWin(), ANSI_RESET);
            System.out.printf("平均亏损: %s¥%.2f%s%n", ANSI_RED, result.getAvgLoss(), ANSI_RESET);
            System.out.printf("盈亏比: %.2f%n", result.getProfitFactor());
        }
        System.out.println();
        
        // 目标达成分析
        printSubHeader("🎯 目标分析");
        boolean annualReturnTarget = result.getAnnualizedReturn().compareTo(new BigDecimal("15")) >= 0 &&
                                   result.getAnnualizedReturn().compareTo(new BigDecimal("20")) <= 0;
        boolean maxDrawdownTarget = result.getMaxDrawdown().compareTo(new BigDecimal("15")) < 0;
        
        System.out.printf("年化收益目标(15-20%%): %s%n", 
            annualReturnTarget ? "✅ 达成" : "❌ 未达成");
        System.out.printf("最大回撤目标(<15%%): %s%n", 
            maxDrawdownTarget ? "✅ 达成" : "❌ 超出");
        System.out.printf("综合评价: %s%n", 
            annualReturnTarget && maxDrawdownTarget ? 
            ANSI_GREEN + "优秀" + ANSI_RESET : 
            annualReturnTarget || maxDrawdownTarget ? 
            ANSI_YELLOW + "良好" + ANSI_RESET :
            ANSI_RED + "需要改进" + ANSI_RESET);
        
        printSeparator();
        printSuccess("回测完成");
    }
    
    private void printSubHeader(String title) {
        System.out.println(ANSI_BOLD + title + ANSI_RESET);
    }
}
```

## 14.3 命令异常处理

### 14.3.1 命令异常类 (CommandException)

```java
public class CommandException extends RuntimeException {
    
    private final String commandName;
    private final String[] args;
    private final ErrorType errorType;
    
    public CommandException(String commandName, String[] args, String message, ErrorType errorType) {
        super(message);
        this.commandName = commandName;
        this.args = args != null ? args.clone() : new String[0];
        this.errorType = errorType;
    }
    
    public CommandException(String commandName, String[] args, String message, Throwable cause, ErrorType errorType) {
        super(message, cause);
        this.commandName = commandName;
        this.args = args != null ? args.clone() : new String[0];
        this.errorType = errorType;
    }
    
    public static CommandException invalidArgument(String commandName, String[] args, String message) {
        return new CommandException(commandName, args, message, ErrorType.INVALID_ARGUMENT);
    }
    
    public static CommandException missingArgument(String commandName, String[] args, String message) {
        return new CommandException(commandName, args, message, ErrorType.MISSING_ARGUMENT);
    }
    
    public static CommandException executionFailed(String commandName, String[] args, String message, Throwable cause) {
        return new CommandException(commandName, args, message, cause, ErrorType.EXECUTION_FAILED);
    }
    
    public static CommandException resourceNotFound(String commandName, String[] args, String message) {
        return new CommandException(commandName, args, message, ErrorType.RESOURCE_NOT_FOUND);
    }
    
    public enum ErrorType {
        INVALID_ARGUMENT,
        MISSING_ARGUMENT,
        EXECUTION_FAILED,
        RESOURCE_NOT_FOUND,
        PERMISSION_DENIED,
        TIMEOUT
    }
    
    // Getters...
    public String getCommandName() { return commandName; }
    public String[] getArgs() { return args.clone(); }
    public ErrorType getErrorType() { return errorType; }
}
```

## 14.4 CLI输出格式标准

### 14.4.1 输出目录结构

CLI系统生成的报告严格按照Python版本的目录结构约定：

```
output/
└── hk_macd_v1_02800_20250812_143022/
    ├── summary.json                 # 核心指标摘要 (JSON格式)
    ├── trades.csv                   # 详细交易记录 (CSV格式)
    ├── equity_curve.csv             # 权益曲线数据 (CSV格式)
    ├── performance_metrics.json     # 完整性能分析 (JSON格式)
    ├── backtest_report.html         # 可视化HTML报告
    └── chinese_summary.txt          # 中文分析摘要
```

### 14.4.2 目录命名规则

```java
/**
 * 创建输出目录名称
 * 格式: hk_{strategy}_v1_{symbol}_{timestamp}
 */
private String createOutputDirectoryName(BacktestRequest request, BacktestResult result) {
    String strategy = request.getStrategyName().toLowerCase();
    String cleanSymbol = request.getSymbol().replace(".HK", "").replace(".", "");
    String timestamp = result.getReportGeneratedAt().format(
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    
    return String.format("hk_%s_v1_%s_%s", strategy, cleanSymbol, timestamp);
}
```

### 14.4.3 彩色输出标准

```java
public class ColorScheme {
    // 基础颜色
    public static final String SUCCESS = "\u001B[32m";  // 绿色 - 成功状态
    public static final String ERROR = "\u001B[31m";    // 红色 - 错误状态
    public static final String WARNING = "\u001B[33m";  // 黄色 - 警告信息
    public static final String INFO = "\u001B[36m";     // 青色 - 信息提示
    
    // 功能颜色
    public static final String PROFIT = "\u001B[32m";   // 绿色 - 盈利数据
    public static final String LOSS = "\u001B[31m";     // 红色 - 亏损数据
    public static final String NEUTRAL = "\u001B[37m";  // 白色 - 中性数据
    
    // 格式化
    public static final String BOLD = "\u001B[1m";      // 粗体
    public static final String UNDERLINE = "\u001B[4m"; // 下划线
    public static final String RESET = "\u001B[0m";     // 重置
}
```

## 14.5 CLI使用指南

### 14.5.1 基本使用模式

```bash
# 1. 显示系统信息和可用命令
java -jar trading.jar

# 2. 显示特定命令帮助
java -jar trading.jar help backtest
java -jar trading.jar backtest --help

# 3. 执行基本回测
java -jar trading.jar backtest --strategy MACD --symbol 02800.HK --from 2024-01-01 --to 2024-12-31

# 4. 使用命令别名
java -jar trading.jar bt -s MACD -sym 02800.HK --from 2024-01-01
```

### 14.5.2 高级参数使用

```bash
# 自定义初始资金和输出目录
java -jar trading.jar backtest \
  --strategy MACD \
  --symbol 02800.HK \
  --from 2024-01-01 \
  --to 2024-12-31 \
  --capital 100000 \
  --output ./reports

# 详细模式输出
java -jar trading.jar backtest \
  --strategy MACD \
  --symbol 02800.HK \
  --from 2024-01-01 \
  --verbose

# 静默模式(仅关键指标)
java -jar trading.jar backtest \
  --strategy MACD \
  --symbol 02800.HK \
  --from 2024-01-01 \
  --quiet

# 自定义手续费率
java -jar trading.jar backtest \
  --strategy MACD \
  --symbol 02800.HK \
  --from 2024-01-01 \
  --commission 0.0002 \
  --slippage 0.0001

# 不生成HTML报告
java -jar trading.jar backtest \
  --strategy MACD \
  --symbol 02800.HK \
  --from 2024-01-01 \
  --no-html
```

### 14.5.3 输出示例

**标准模式输出**：
```
港股程序化交易系统 CLI v1.0
🚀 专业量化交易回测分析平台

📅 系统时间: 2025-01-12 14:30:22
💻 Java版本: 17.0.12
🗂️  工作目录: /Users/user/trading

=== 回测配置 ===
策略名称: MACD
交易标的: 02800.HK
时间范围: 2024-01-01 至 2024-12-31
初始资金: ¥100,000.00
K线周期: 30m

ℹ️  开始执行回测...
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

=== 回测结果 ===
回测期间: 2024-01-01 至 2024-12-31 (365天)
执行耗时: 2.3秒

📈 收益指标
初始资金: ¥100,000.00
最终权益: ¥118,650.00
绝对收益: ¥18,650.00
总收益率: 18.65%
年化收益: 18.65%

⚠️ 风险指标
最大回撤: 8.32%
夏普比率: 1.85
索提诺比率: 2.34
卡尔马比率: 2.24

📊 交易统计
总交易次数: 24
盈利交易: 15
亏损交易: 9
胜率: 62.5%
平均盈利: ¥2,150.00
平均亏损: ¥980.00
盈亏比: 2.19

🎯 目标分析
年化收益目标(15-20%): ✅ 达成
最大回撤目标(<15%): ✅ 达成
综合评价: 优秀

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ 回测完成
```

**静默模式输出**：
```
18.65%,8.32%,1.85,62.5%,24
```

## 14.6 扩展和定制

### 14.6.1 自定义命令开发

```java
@Component
public class CustomCommand extends AbstractCommand {
    
    @Override
    public String getName() {
        return "custom";
    }
    
    @Override
    public String getDescription() {
        return "自定义命令示例";
    }
    
    @Override
    public List<String> getAliases() {
        return List.of("c", "cust");
    }
    
    @Override
    public void execute(String[] args) throws CommandException {
        // 实现自定义逻辑
        printInfo("执行自定义命令");
        
        // 使用基类提供的工具方法
        printSuccess("操作成功");
        printTableHeader("结果展示");
        printSeparator();
    }
    
    @Override
    public void printUsage() {
        printUsageHeader("java -jar trading.jar custom [选项]");
        System.out.println("自定义命令的详细使用说明...");
    }
}
```

### 14.6.2 国际化支持

CLI系统支持多语言扩展，可以通过消息资源文件实现国际化：

```java
@Component
public class MessageService {
    
    private final MessageSource messageSource;
    private final Locale currentLocale;
    
    public MessageService() {
        this.messageSource = new ResourceBundleMessageSource();
        ((ResourceBundleMessageSource) messageSource).setBasename("messages");
        this.currentLocale = Locale.getDefault();
    }
    
    public String getMessage(String key, Object... args) {
        return messageSource.getMessage(key, args, currentLocale);
    }
}
```

### 14.6.3 插件机制

支持第三方插件扩展CLI功能：

```java
public interface CLIPlugin {
    String getName();
    String getVersion();
    List<Command> getCommands();
    void initialize();
}

@Component
public class PluginManager {
    
    @EventListener(ApplicationReadyEvent.class)
    public void loadPlugins() {
        // 扫描插件目录
        // 加载插件JAR文件
        // 注册插件命令
    }
}
```

## 14.7 性能优化

### 14.7.1 快速启动优化
- 延迟初始化非关键组件
- 命令执行时才加载相关服务
- 减少Spring容器启动时间

### 14.7.2 内存优化
- 大数据集分批处理
- 及时释放不用的对象引用
- 使用流式处理避免内存积累

### 14.7.3 并发优化
- 异步执行长时间操作
- 并行处理多个数据源
- 使用CompletableFuture提升响应性

通过这个完整的CLI系统，用户可以获得专业级的命令行交易体验，支持复杂的回测分析、丰富的输出格式和灵活的扩展机制。