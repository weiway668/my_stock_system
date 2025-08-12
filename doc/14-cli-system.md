# CLI命令行交易系统

## 14.1 CLI架构设计

### 14.1.1 命令行入口

```java
@Component
@CommandLineRunner
public class TradingCLI implements CommandLineRunner {
    
    @Autowired
    private CommandRegistry commandRegistry;
    
    @Autowired
    private InteractiveShell interactiveShell;
    
    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            // 无参数时进入交互式Shell
            interactiveShell.start();
            return;
        }
        
        String commandName = args[0];
        
        // 检查是否为帮助命令
        if ("help".equals(commandName) || "--help".equals(commandName)) {
            printHelp();
            return;
        }
        
        // 获取并执行命令
        Command command = commandRegistry.getCommand(commandName);
        if (command == null) {
            System.err.println("未知命令: " + commandName);
            System.err.println("使用 'help' 查看可用命令");
            System.exit(1);
        }
        
        try {
            String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);
            command.execute(commandArgs);
        } catch (Exception e) {
            System.err.println("命令执行失败: " + e.getMessage());
            if (isDebugMode()) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }
    
    private void printHelp() {
        System.out.println("港股程序化交易系统 CLI v1.0");
        System.out.println("=====================================");
        System.out.println("\n可用命令:");
        
        commandRegistry.getAllCommands().forEach(cmd -> {
            System.out.printf("  %-15s %s\n", cmd.getName(), cmd.getDescription());
        });
        
        System.out.println("\n使用示例:");
        System.out.println("  java -jar trading.jar backtest --strategy MACD --symbol 00700.HK");
        System.out.println("  java -jar trading.jar trade --mode paper --capital 100000");
        System.out.println("  java -jar trading.jar shell  # 进入交互式模式");
    }
}
```

### 14.1.2 命令接口与注册器

```java
// 命令接口
public interface Command {
    String getName();
    String getDescription();
    void execute(String[] args) throws CommandException;
    void printUsage();
    List<String> getAliases();
}

// 命令基类
public abstract class AbstractCommand implements Command {
    
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    
    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }
    
    protected CommandLine parseArgs(String[] args, Options options) {
        CommandLineParser parser = new DefaultParser();
        try {
            return parser.parse(options, args);
        } catch (ParseException e) {
            throw new CommandException("参数解析失败: " + e.getMessage());
        }
    }
    
    protected void validateRequired(CommandLine cmd, String... options) {
        for (String opt : options) {
            if (!cmd.hasOption(opt)) {
                throw new CommandException("缺少必需参数: --" + opt);
            }
        }
    }
}

// 命令注册器
@Component
public class CommandRegistry {
    
    private final Map<String, Command> commands = new HashMap<>();
    private final Map<String, Command> aliases = new HashMap<>();
    
    @Autowired
    public CommandRegistry(List<Command> commandList) {
        commandList.forEach(this::register);
    }
    
    private void register(Command command) {
        commands.put(command.getName(), command);
        
        // 注册别名
        command.getAliases().forEach(alias -> 
            aliases.put(alias, command));
    }
    
    public Command getCommand(String name) {
        Command cmd = commands.get(name);
        return cmd != null ? cmd : aliases.get(name);
    }
    
    public Collection<Command> getAllCommands() {
        return commands.values();
    }
}
```

## 14.2 核心命令实现

### 14.2.1 回测命令

```java
@Component
public class BacktestCommand extends AbstractCommand {
    
    @Autowired
    private BacktestEngine backtestEngine;
    
    @Autowired
    private DataService dataService;
    
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
        return Arrays.asList("bt", "test");
    }
    
    @Override
    public void execute(String[] args) {
        Options options = createOptions();
        CommandLine cmd = parseArgs(args, options);
        
        if (cmd.hasOption("help")) {
            printUsage();
            return;
        }
        
        // 解析参数
        BacktestConfig config = parseConfig(cmd);
        
        // 显示回测配置
        printConfig(config);
        
        // 加载历史数据
        System.out.println("\n加载历史数据...");
        List<MarketData> historicalData = dataService.loadHistoricalData(
            config.getSymbol(),
            config.getStartDate(),
            config.getEndDate(),
            config.getInterval()
        );
        
        if (historicalData.isEmpty()) {
            throw new CommandException("无可用历史数据");
        }
        
        System.out.printf("已加载 %d 条数据\n", historicalData.size());
        
        // 执行回测
        System.out.println("\n开始回测...");
        ProgressBar progressBar = new ProgressBar("回测进度", 100);
        
        BacktestResult result = backtestEngine.run(
            config,
            historicalData,
            progress -> progressBar.update((int)(progress * 100))
        );
        
        progressBar.close();
        
        // 输出结果
        printResults(result);
        
        // 生成报告
        if (cmd.hasOption("output")) {
            String outputPath = cmd.getOptionValue("output");
            generateReport(result, outputPath);
            System.out.println("\n报告已生成: " + outputPath);
        }
        
        // 参数优化
        if (cmd.hasOption("optimize")) {
            System.out.println("\n开始参数优化...");
            performOptimization(config, historicalData);
        }
    }
    
    private Options createOptions() {
        Options options = new Options();
        
        // 必需参数
        options.addOption("s", "strategy", true, "策略名称 (MACD/BOLL/VOLUME)");
        options.addOption("sym", "symbol", true, "交易标的 (如: 00700.HK)");
        
        // 可选参数
        options.addOption("from", true, "开始日期 (YYYY-MM-DD)");
        options.addOption("to", true, "结束日期 (YYYY-MM-DD)");
        options.addOption("c", "capital", true, "初始资金 (默认: 100000)");
        options.addOption("i", "interval", true, "K线周期 (1m/5m/30m/1h/1d)");
        options.addOption("o", "output", true, "报告输出路径");
        options.addOption("v", "verbose", false, "详细输出");
        options.addOption("opt", "optimize", false, "参数优化");
        options.addOption("h", "help", false, "显示帮助");
        
        return options;
    }
    
    private void printResults(BacktestResult result) {
        // 创建表格
        AsciiTable table = new AsciiTable();
        table.addRule();
        table.addRow("指标", "数值");
        table.addRule();
        
        // 收益指标
        table.addRow("总收益率", String.format("%.2f%%", result.getTotalReturn() * 100));
        table.addRow("年化收益", String.format("%.2f%%", result.getAnnualizedReturn() * 100));
        table.addRow("最大回撤", String.format("%.2f%%", result.getMaxDrawdown() * 100));
        
        // 风险指标
        table.addRow("夏普比率", String.format("%.2f", result.getSharpeRatio()));
        table.addRow("索提诺比率", String.format("%.2f", result.getSortinoRatio()));
        table.addRow("卡尔马比率", String.format("%.2f", result.getCalmarRatio()));
        
        // 交易统计
        table.addRow("总交易次数", String.valueOf(result.getTotalTrades()));
        table.addRow("胜率", String.format("%.2f%%", result.getWinRate() * 100));
        table.addRow("盈亏比", String.format("%.2f", result.getProfitFactor()));
        table.addRow("平均持仓天数", String.format("%.1f", result.getAvgHoldingDays()));
        
        table.addRule();
        
        System.out.println("\n回测结果:");
        System.out.println(table.render());
        
        // 绘制收益曲线（ASCII图表）
        if (result.getEquityCurve() != null) {
            drawEquityCurve(result.getEquityCurve());
        }
    }
    
    private void drawEquityCurve(List<BigDecimal> equityCurve) {
        System.out.println("\n收益曲线:");
        
        ASCIIGraph graph = new ASCIIGraph();
        graph.setWidth(80);
        graph.setHeight(20);
        
        double[] values = equityCurve.stream()
            .mapToDouble(BigDecimal::doubleValue)
            .toArray();
        
        System.out.println(graph.plot(values));
    }
}
```

### 14.2.2 实盘交易命令

```java
@Component
public class TradeCommand extends AbstractCommand {
    
    @Autowired
    private TradingEngine tradingEngine;
    
    @Autowired
    private AccountService accountService;
    
    @Override
    public String getName() {
        return "trade";
    }
    
    @Override
    public String getDescription() {
        return "启动交易引擎";
    }
    
    @Override
    public void execute(String[] args) {
        Options options = createOptions();
        CommandLine cmd = parseArgs(args, options);
        
        // 解析交易模式
        TradeMode mode = TradeMode.valueOf(
            cmd.getOptionValue("mode", "PAPER").toUpperCase()
        );
        
        // 实盘交易确认
        if (mode == TradeMode.LIVE) {
            if (!confirmLiveTrading()) {
                System.out.println("已取消实盘交易");
                return;
            }
            
            // 检查账户状态
            checkAccountStatus();
        }
        
        // 构建交易配置
        TradingConfig config = buildTradingConfig(cmd, mode);
        
        // 启动交易引擎
        System.out.println("\n启动交易引擎...");
        System.out.println("模式: " + mode);
        System.out.println("策略: " + config.getStrategy());
        System.out.println("标的: " + String.join(", ", config.getSymbols()));
        System.out.println("资金: " + config.getCapital());
        System.out.println("=====================================\n");
        
        tradingEngine.start(config);
        
        // 启动监控界面
        TradingMonitor monitor = new TradingMonitor(tradingEngine);
        monitor.start();
        
        // 交互式命令处理
        handleInteractiveCommands();
    }
    
    private boolean confirmLiveTrading() {
        Console console = System.console();
        if (console == null) {
            throw new CommandException("无法获取控制台，请在终端中运行");
        }
        
        System.out.println("\n" + Colors.RED + "⚠️  警告：即将进行实盘交易！" + Colors.RESET);
        System.out.println("这将使用真实资金进行交易，可能导致资金损失。");
        System.out.println("\n请确认以下信息:");
        
        // 显示账户信息
        AccountInfo account = accountService.getAccountInfo();
        System.out.println("  账户: " + account.getAccountId());
        System.out.println("  可用资金: " + account.getAvailableBalance() + " HKD");
        System.out.println("  当前持仓: " + account.getPositionCount() + " 个");
        
        System.out.print("\n请输入 'YES' 确认开始实盘交易: ");
        String confirmation = console.readLine();
        
        return "YES".equals(confirmation);
    }
    
    private void handleInteractiveCommands() {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;
        
        System.out.println("\n输入命令 (help 查看帮助):");
        
        while (running) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            
            try {
                switch (input.toLowerCase()) {
                    case "stop":
                        tradingEngine.stop();
                        running = false;
                        break;
                        
                    case "pause":
                        tradingEngine.pause();
                        System.out.println("交易已暂停");
                        break;
                        
                    case "resume":
                        tradingEngine.resume();
                        System.out.println("交易已恢复");
                        break;
                        
                    case "status":
                        printStatus();
                        break;
                        
                    case "positions":
                        printPositions();
                        break;
                        
                    case "orders":
                        printOrders();
                        break;
                        
                    case "pnl":
                        printPnL();
                        break;
                        
                    case "help":
                        printInteractiveHelp();
                        break;
                        
                    default:
                        if (!input.isEmpty()) {
                            System.out.println("未知命令: " + input);
                        }
                }
            } catch (Exception e) {
                System.err.println("命令执行失败: " + e.getMessage());
            }
        }
        
        System.out.println("\n交易引擎已停止");
    }
}
```

### 14.2.3 数据管理命令

```java
@Component
public class DataCommand extends AbstractCommand {
    
    @Autowired
    private DataDownloader dataDownloader;
    
    @Autowired
    private DataManager dataManager;
    
    @Override
    public String getName() {
        return "data";
    }
    
    @Override
    public String getDescription() {
        return "数据管理（下载/更新/清理）";
    }
    
    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        
        String action = args[0];
        String[] actionArgs = Arrays.copyOfRange(args, 1, args.length);
        
        switch (action.toLowerCase()) {
            case "download":
                downloadData(actionArgs);
                break;
            case "update":
                updateData(actionArgs);
                break;
            case "clean":
                cleanData(actionArgs);
                break;
            case "export":
                exportData(actionArgs);
                break;
            case "import":
                importData(actionArgs);
                break;
            case "list":
                listData(actionArgs);
                break;
            default:
                throw new CommandException("未知操作: " + action);
        }
    }
    
    private void downloadData(String[] args) {
        Options options = new Options();
        options.addOption("m", "market", true, "市场 (HK/US/CN)");
        options.addOption("s", "symbols", true, "股票代码，逗号分隔");
        options.addOption("from", true, "开始日期");
        options.addOption("to", true, "结束日期");
        options.addOption("i", "interval", true, "K线周期");
        options.addOption("o", "output", true, "输出目录");
        options.addOption("f", "format", true, "数据格式 (csv/json/parquet)");
        
        CommandLine cmd = parseArgs(args, options);
        
        // 解析参数
        Market market = Market.valueOf(cmd.getOptionValue("market", "HK"));
        List<String> symbols = Arrays.asList(cmd.getOptionValue("symbols").split(","));
        LocalDate startDate = LocalDate.parse(cmd.getOptionValue("from"));
        LocalDate endDate = LocalDate.parse(cmd.getOptionValue("to", LocalDate.now().toString()));
        String interval = cmd.getOptionValue("interval", "1d");
        
        System.out.println("\n开始下载数据:");
        System.out.println("市场: " + market);
        System.out.println("标的: " + String.join(", ", symbols));
        System.out.println("时间: " + startDate + " 至 " + endDate);
        System.out.println("周期: " + interval);
        System.out.println();
        
        // 创建进度跟踪器
        MultiProgressBar progressBars = new MultiProgressBar();
        
        // 并行下载
        List<CompletableFuture<DownloadResult>> futures = symbols.stream()
            .map(symbol -> CompletableFuture.supplyAsync(() -> {
                ProgressBar bar = progressBars.addBar(symbol, 100);
                
                try {
                    return dataDownloader.download(
                        symbol,
                        market,
                        startDate,
                        endDate,
                        interval,
                        progress -> bar.update((int)(progress * 100))
                    );
                } finally {
                    bar.complete();
                }
            }))
            .collect(Collectors.toList());
        
        // 等待所有下载完成
        List<DownloadResult> results = futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
        
        progressBars.close();
        
        // 输出统计
        printDownloadSummary(results);
        
        // 保存数据
        if (cmd.hasOption("output")) {
            String outputDir = cmd.getOptionValue("output");
            String format = cmd.getOptionValue("format", "csv");
            saveData(results, outputDir, format);
        }
    }
    
    private void printDownloadSummary(List<DownloadResult> results) {
        System.out.println("\n下载完成:");
        
        AsciiTable table = new AsciiTable();
        table.addRule();
        table.addRow("股票代码", "数据条数", "开始时间", "结束时间", "状态");
        table.addRule();
        
        int totalRecords = 0;
        int successCount = 0;
        
        for (DownloadResult result : results) {
            table.addRow(
                result.getSymbol(),
                result.getRecordCount(),
                result.getStartTime(),
                result.getEndTime(),
                result.isSuccess() ? "✅ 成功" : "❌ 失败"
            );
            
            totalRecords += result.getRecordCount();
            if (result.isSuccess()) successCount++;
        }
        
        table.addRule();
        System.out.println(table.render());
        
        System.out.printf("\n总计: %d 个标的, %d 成功, %d 条记录\n",
            results.size(), successCount, totalRecords);
    }
}
```

## 14.3 交互式Shell模式

```java
@Component
public class InteractiveShell {
    
    @Autowired
    private CommandRegistry commandRegistry;
    
    @Autowired
    private TradingContext tradingContext;
    
    private Terminal terminal;
    private LineReader reader;
    private History history;
    
    public void start() {
        try {
            // 初始化终端
            terminal = TerminalBuilder.builder()
                .system(true)
                .build();
            
            // 创建历史记录
            history = new DefaultHistory();
            
            // 创建行读取器
            reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new TradingCompleter())
                .highlighter(new TradingHighlighter())
                .history(history)
                .parser(new DefaultParser())
                .build();
            
            // 显示欢迎信息
            printWelcome();
            
            // 主循环
            String prompt = getPrompt();
            
            while (true) {
                try {
                    String line = reader.readLine(prompt);
                    
                    if (line == null || "exit".equals(line.trim()) || "quit".equals(line.trim())) {
                        break;
                    }
                    
                    if (!line.trim().isEmpty()) {
                        processCommand(line);
                    }
                    
                } catch (UserInterruptException e) {
                    // Ctrl+C
                    System.out.println("\n使用 'exit' 或 'quit' 退出");
                } catch (EndOfFileException e) {
                    // Ctrl+D
                    break;
                } catch (Exception e) {
                    System.err.println("错误: " + e.getMessage());
                    if (isDebugMode()) {
                        e.printStackTrace();
                    }
                }
            }
            
            // 保存历史
            saveHistory();
            
            System.out.println("\n再见！");
            
        } catch (IOException e) {
            throw new RuntimeException("无法初始化终端", e);
        }
    }
    
    private String getPrompt() {
        StringBuilder prompt = new StringBuilder();
        
        // 添加状态信息
        if (tradingContext.isConnected()) {
            prompt.append(Colors.GREEN).append("●").append(Colors.RESET);
        } else {
            prompt.append(Colors.RED).append("●").append(Colors.RESET);
        }
        
        // 添加当前模式
        prompt.append(" [").append(tradingContext.getMode()).append("]");
        
        // 添加提示符
        prompt.append(" trading> ");
        
        return prompt.toString();
    }
    
    private void processCommand(String line) {
        // 解析命令
        String[] parts = line.trim().split("\\s+");
        String commandName = parts[0];
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        
        // 处理内置命令
        if (handleBuiltinCommand(commandName, args)) {
            return;
        }
        
        // 执行注册的命令
        Command command = commandRegistry.getCommand(commandName);
        if (command != null) {
            try {
                command.execute(args);
            } catch (Exception e) {
                System.err.println("命令执行失败: " + e.getMessage());
            }
        } else {
            System.err.println("未知命令: " + commandName);
            System.err.println("输入 'help' 查看可用命令");
        }
    }
    
    // 自动补全器
    private class TradingCompleter implements Completer {
        
        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            String buffer = line.word();
            
            if (line.wordIndex() == 0) {
                // 补全命令
                commandRegistry.getAllCommands().stream()
                    .map(Command::getName)
                    .filter(name -> name.startsWith(buffer))
                    .map(name -> new Candidate(name))
                    .forEach(candidates::add);
                
                // 补全内置命令
                Arrays.asList("clear", "history", "set", "get", "connect", "disconnect")
                    .stream()
                    .filter(cmd -> cmd.startsWith(buffer))
                    .map(Candidate::new)
                    .forEach(candidates::add);
                    
            } else {
                // 补全参数
                completeArguments(line, candidates);
            }
        }
    }
    
    // 语法高亮器
    private class TradingHighlighter implements Highlighter {
        
        @Override
        public AttributedString highlight(LineReader reader, String buffer) {
            AttributedStringBuilder sb = new AttributedStringBuilder();
            
            String[] parts = buffer.split("\\s+", 2);
            if (parts.length > 0) {
                // 高亮命令
                String cmd = parts[0];
                if (commandRegistry.getCommand(cmd) != null) {
                    sb.append(cmd, AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
                } else if (isBuiltinCommand(cmd)) {
                    sb.append(cmd, AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
                } else {
                    sb.append(cmd, AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
                }
                
                // 添加剩余部分
                if (parts.length > 1) {
                    sb.append(" ");
                    sb.append(parts[1]);
                }
            }
            
            return sb.toAttributedString();
        }
    }
}
```

## 14.4 批处理与脚本支持

```java
@Component
public class ScriptRunner {
    
    @Autowired
    private CommandRegistry commandRegistry;
    
    @Autowired
    private ScriptParser scriptParser;
    
    public void runScript(String scriptFile) throws IOException {
        Path scriptPath = Paths.get(scriptFile);
        if (!Files.exists(scriptPath)) {
            throw new FileNotFoundException("脚本文件不存在: " + scriptFile);
        }
        
        System.out.println("执行脚本: " + scriptFile);
        System.out.println("=====================================");
        
        List<String> lines = Files.readAllLines(scriptPath);
        ScriptContext context = new ScriptContext();
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            
            // 跳过空行和注释
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            try {
                // 解析变量替换
                line = scriptParser.substituteVariables(line, context);
                
                // 处理特殊指令
                if (line.startsWith("@")) {
                    handleDirective(line, context);
                    continue;
                }
                
                // 执行命令
                System.out.println("\n> " + line);
                executeCommand(line, context);
                
                // 处理延迟
                if (context.hasDelay()) {
                    Thread.sleep(context.getDelay());
                    context.clearDelay();
                }
                
            } catch (Exception e) {
                System.err.println("第 " + (i + 1) + " 行执行失败: " + e.getMessage());
                
                if (!context.isContinueOnError()) {
                    throw new ScriptException("脚本执行中断", e);
                }
            }
        }
        
        System.out.println("\n=====================================");
        System.out.println("脚本执行完成");
    }
    
    private void handleDirective(String directive, ScriptContext context) {
        if (directive.startsWith("@set ")) {
            // 设置变量
            String[] parts = directive.substring(5).split("=", 2);
            if (parts.length == 2) {
                context.setVariable(parts[0].trim(), parts[1].trim());
            }
            
        } else if (directive.startsWith("@delay ")) {
            // 设置延迟
            int seconds = Integer.parseInt(directive.substring(7).trim());
            context.setDelay(seconds * 1000);
            
        } else if (directive.equals("@continue-on-error")) {
            // 错误继续
            context.setContinueOnError(true);
            
        } else if (directive.startsWith("@if ")) {
            // 条件执行
            handleConditional(directive, context);
            
        } else if (directive.startsWith("@loop ")) {
            // 循环执行
            handleLoop(directive, context);
        }
    }
    
    private void executeCommand(String line, ScriptContext context) {
        // 支持管道
        if (line.contains("|")) {
            executePipeline(line, context);
            return;
        }
        
        // 支持重定向
        String outputFile = null;
        if (line.contains(">")) {
            String[] parts = line.split(">", 2);
            line = parts[0].trim();
            outputFile = parts[1].trim();
        }
        
        // 解析命令
        String[] parts = line.split("\\s+");
        String commandName = parts[0];
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        
        // 执行命令
        Command command = commandRegistry.getCommand(commandName);
        if (command == null) {
            throw new ScriptException("未知命令: " + commandName);
        }
        
        // 捕获输出
        if (outputFile != null) {
            captureOutput(() -> command.execute(args), outputFile);
        } else {
            command.execute(args);
        }
    }
}

// 脚本示例文件: daily-trading.script
/*
# 每日交易脚本
# 设置变量
@set MARKET=HK
@set CAPITAL=100000
@set STRATEGY=MACD

# 更新数据
data update --market ${MARKET} --symbols 00700.HK,2800.HK,3033.HK

# 延迟5秒
@delay 5

# 运行回测
backtest --strategy ${STRATEGY} --symbol 00700.HK --from 2024-01-01 --capital ${CAPITAL} > backtest-result.txt

# 如果回测成功，启动模拟交易
@if LAST_COMMAND_SUCCESS
  trade --mode paper --strategy ${STRATEGY} --capital ${CAPITAL}
@endif

# 生成日报
report daily --output ./reports/daily-${DATE}.pdf
*/
```

## 14.5 CLI配置文件

```yaml
# cli-config.yml
cli:
  # 默认设置
  defaults:
    market: HK
    interval: 30m
    capital: 100000
    output-format: table  # table, json, csv
    color-output: true
    
  # 命令别名
  aliases:
    bt: backtest
    t: trade
    d: data
    dl: "data download"
    pos: position
    pnl: "report pnl"
    q: quit
    
  # 自动补全
  autocomplete:
    enabled: true
    history-size: 1000
    history-file: ~/.trading-cli-history
    
  # 输出设置
  output:
    colors:
      success: green
      error: red
      warning: yellow
      info: cyan
    progress-bar:
      style: unicode  # ascii, unicode
      width: 40
    table:
      border-style: double  # single, double, ascii
      
  # 快捷键绑定
  keybindings:
    ctrl-c: interrupt
    ctrl-d: exit
    ctrl-l: clear
    ctrl-r: search-history
    f1: help
    f2: status
    f3: positions
    f4: orders
    f5: refresh
    
  # 交易确认
  trading:
    require-confirmation:
      live: true
      paper: false
    confirmation-timeout: 30  # 秒
    
  # 数据源配置
  data-sources:
    futu:
      host: 127.0.0.1
      port: 11111
    yahoo:
      api-key: ${YAHOO_API_KEY}
    
  # 日志设置
  logging:
    level: INFO  # DEBUG, INFO, WARN, ERROR
    file: ./logs/cli.log
    rotate-size: 10MB
    max-files: 5
```

## 14.6 使用示例

```bash
# 1. 基础命令示例

# 查看帮助
java -jar trading.jar help

# 回测MACD策略
java -jar trading.jar backtest \
  --strategy MACD \
  --symbol 00700.HK \
  --from 2024-01-01 \
  --to 2024-12-31 \
  --capital 100000 \
  --output ./reports/backtest.html

# 参数优化
java -jar trading.jar backtest \
  --strategy MACD \
  --symbol 2800.HK \
  --optimize \
  --param-grid "fast:10-15:1,slow:20-30:2,signal:5-10:1" \
  --metric sharpe

# 2. 数据管理

# 下载港股数据
java -jar trading.jar data download \
  --market HK \
  --symbols 00700.HK,2800.HK,3033.HK \
  --from 2023-01-01 \
  --interval 30m \
  --format parquet

# 下载美股数据
java -jar trading.jar data download \
  --market US \
  --symbols AAPL,GOOGL,MSFT,TSLA \
  --from 2023-01-01 \
  --interval 1h \
  --output ./data/us-stocks

# 更新数据
java -jar trading.jar data update --all

# 清理旧数据
java -jar trading.jar data clean --before 2022-01-01

# 3. 交易执行

# 模拟交易
java -jar trading.jar trade \
  --mode paper \
  --strategy BOLL \
  --symbols 2800.HK,3033.HK \
  --capital 100000 \
  --risk-per-trade 0.02

# 实盘交易（需要确认）
java -jar trading.jar trade \
  --mode live \
  --strategy MACD \
  --symbol 00700.HK \
  --capital 50000 \
  --max-positions 3

# 4. 查询与监控

# 查看当前持仓
java -jar trading.jar position list --detail

# 查看历史订单
java -jar trading.jar orders \
  --from 2024-01-01 \
  --status filled

# 查看盈亏报告
java -jar trading.jar report pnl \
  --period month \
  --format pdf

# 实时监控
java -jar trading.jar monitor \
  --refresh 5 \
  --alerts true

# 5. 策略管理

# 列出所有策略
java -jar trading.jar strategy list

# 查看策略详情
java -jar trading.jar strategy info MACD

# 设置策略参数
java -jar trading.jar strategy set MACD \
  --param fast=12 \
  --param slow=26 \
  --param signal=9

# 启用/禁用策略
java -jar trading.jar strategy enable MACD
java -jar trading.jar strategy disable BOLL

# 6. 交互式Shell模式

# 进入交互式模式
java -jar trading.jar shell

# 在Shell中执行命令
trading> backtest -s MACD -sym 00700.HK
trading> status
trading> positions
trading> set capital 200000
trading> connect
trading> trade --mode paper
trading> stop
trading> exit

# 7. 批处理脚本

# 执行脚本文件
java -jar trading.jar script run daily-trading.script

# 定时执行（配合cron）
0 9 * * 1-5 java -jar trading.jar script run morning-check.script
0 15 * * 1-5 java -jar trading.jar script run afternoon-trade.script

# 8. 高级用法

# 管道组合
java -jar trading.jar data list | grep "00700" | java -jar trading.jar backtest --stdin

# 并行回测
java -jar trading.jar backtest \
  --parallel 4 \
  --symbols 00700.HK,2800.HK,3033.HK,0005.HK \
  --strategy ALL

# 参数扫描
java -jar trading.jar optimize \
  --strategy MACD \
  --method grid \
  --params "fast:8-15,slow:20-35,signal:5-12" \
  --metric "sharpe,return,drawdown" \
  --workers 8

# 导出配置
java -jar trading.jar config export > my-config.yml

# 导入配置
java -jar trading.jar config import my-config.yml
```

## 14.7 CLI扩展机制

```java
// 自定义命令插件接口
public interface CommandPlugin {
    String getName();
    String getVersion();
    List<Command> getCommands();
    void initialize(PluginContext context);
}

// 插件加载器
@Component
public class PluginLoader {
    
    private final List<CommandPlugin> plugins = new ArrayList<>();
    
    @PostConstruct
    public void loadPlugins() {
        // 扫描插件目录
        Path pluginDir = Paths.get("./plugins");
        if (!Files.exists(pluginDir)) {
            return;
        }
        
        try {
            Files.list(pluginDir)
                .filter(p -> p.toString().endsWith(".jar"))
                .forEach(this::loadPlugin);
                
            logger.info("已加载 {} 个插件", plugins.size());
            
        } catch (IOException e) {
            logger.error("加载插件失败", e);
        }
    }
    
    private void loadPlugin(Path jarPath) {
        try {
            URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                getClass().getClassLoader()
            );
            
            // 查找插件类
            ServiceLoader<CommandPlugin> loader = ServiceLoader.load(
                CommandPlugin.class, classLoader
            );
            
            for (CommandPlugin plugin : loader) {
                plugin.initialize(createPluginContext());
                plugins.add(plugin);
                
                // 注册插件命令
                plugin.getCommands().forEach(commandRegistry::register);
                
                logger.info("已加载插件: {} v{}", 
                    plugin.getName(), plugin.getVersion());
            }
            
        } catch (Exception e) {
            logger.error("加载插件失败: {}", jarPath, e);
        }
    }
}
```

这个CLI系统提供了完整的命令行交易功能，包括：

1. **全功能命令行界面** - 支持回测、交易、数据管理等所有核心功能
2. **交互式Shell模式** - 提供类似终端的交互体验，支持自动补全和语法高亮
3. **批处理脚本** - 支持脚本自动化执行，包括变量、条件和循环
4. **多市场支持** - 可以处理港股、美股、A股等多个市场的数据
5. **安全机制** - 实盘交易需要多重确认，防止误操作
6. **插件扩展** - 支持自定义命令插件，方便功能扩展
7. **丰富的输出格式** - 支持表格、图表、进度条等多种展示方式