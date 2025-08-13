package com.trading;

import com.trading.cli.CommandRegistry;
import com.trading.cli.Command;
import com.trading.cli.CommandException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.Arrays;

/**
 * 港股程序化交易系统启动器
 * 支持CLI命令行模式和传统服务模式
 */
@Slf4j
@SpringBootApplication
@EntityScan(basePackages = "com.trading.domain.entity")
@EnableJpaRepositories(basePackages = "com.trading.repository")
@ComponentScan(
    basePackages = "com.trading",
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com.trading.controller.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com.trading.service.IntegratedTradingEventHandler"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com.trading.infrastructure.futu.FutuHealthIndicator")
    }
)
public class TradingSystemStarter implements CommandLineRunner {
    
    @Autowired
    private CommandRegistry commandRegistry;

    public static void main(String[] args) {
        SpringApplication.run(TradingSystemStarter.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            // 无参数时显示系统信息并进入服务模式
            printSystemInfo();
            printAvailableCommands();
            return;
        }
        
        String commandName = args[0];
        
        // 检查是否为帮助命令
        if ("help".equals(commandName) || "--help".equals(commandName) || "-h".equals(commandName)) {
            if (args.length > 1) {
                // 显示特定命令的帮助
                showCommandHelp(args[1]);
            } else {
                // 显示总体帮助
                commandRegistry.printHelp();
            }
            // CLI命令执行完成后退出应用
            System.exit(0);
            return;
        }
        
        // 获取并执行命令
        Command command = commandRegistry.getCommand(commandName);
        if (command == null) {
            System.err.println("❌ 未知命令: " + commandName);
            System.err.println("💡 使用 'help' 查看可用命令列表");
            suggestSimilarCommands(commandName);
            System.exit(1);
        }
        
        try {
            String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);
            command.execute(commandArgs);
            // CLI命令执行完成后正常退出
            System.exit(0);
        } catch (CommandException e) {
            System.err.println("❌ " + e.getMessage());
            if (isDebugMode(args)) {
                e.printStackTrace();
            }
            System.exit(1);
        } catch (Exception e) {
            System.err.println("❌ 系统错误: " + e.getMessage());
            log.error("命令执行异常", e);
            if (isDebugMode(args)) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }
    
    private void printSystemInfo() {
        System.out.println("=====================================");
        System.out.println("🚀 港股程序化交易系统 v1.0");
        System.out.println("=====================================");
        System.out.println("系统功能:");
        System.out.println("📈 市场数据服务 - FUTU OpenD实时和历史行情");
        System.out.println("🤖 智能交易策略 - MACD/BOLL/Volume多策略");
        System.out.println("📊 专业回测分析 - 详细性能报告和可视化");
        System.out.println("⚡ 风险管理系统 - 实时仓位和资金控制");
        System.out.println("📋 命令行界面 - 专业CLI操作体验");
        System.out.println("=====================================");
        System.out.println("🎯 目标标的: 02800.HK, 03033.HK, 00700.HK");
        System.out.println("💰 预期收益: 15-20% 年化，<15% 最大回撤");
        System.out.println("=====================================");
    }
    
    private void printAvailableCommands() {
        System.out.println("💡 可用命令:");
        
        commandRegistry.getAllCommands().stream()
            .sorted((c1, c2) -> c1.getName().compareTo(c2.getName()))
            .forEach(cmd -> {
                System.out.printf("  %-12s %s\n", cmd.getName(), cmd.getDescription());
            });
        
        System.out.println();
        System.out.println("📝 使用示例:");
        System.out.println("  java -jar trading.jar backtest --strategy MACD --symbol 02800.HK");
        System.out.println("  java -jar trading.jar help backtest");
        System.out.println();
        System.out.println("🔗 获取帮助: java -jar trading.jar help [command]");
        System.out.println("=====================================");
        System.out.println("✅ 系统已就绪，可以执行命令操作");
    }
    
    private void showCommandHelp(String commandName) {
        Command command = commandRegistry.getCommand(commandName);
        if (command == null) {
            System.err.println("❌ 未知命令: " + commandName);
            suggestSimilarCommands(commandName);
            return;
        }
        
        try {
            command.printUsage();
        } catch (Exception e) {
            System.err.println("❌ 无法显示命令帮助: " + e.getMessage());
        }
    }
    
    private void suggestSimilarCommands(String input) {
        var suggestions = commandRegistry.findMatchingCommands(input);
        if (!suggestions.isEmpty()) {
            System.out.println();
            System.out.println("🤔 您是否想要执行以下命令之一？");
            suggestions.stream()
                .limit(3)
                .forEach(cmd -> System.out.println("  " + cmd));
        }
    }
    
    private boolean isDebugMode(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> 
            "--debug".equals(arg) || "--verbose".equals(arg) || "-v".equals(arg));
    }
}