package com.trading.cli;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令注册器
 * 管理所有CLI命令的注册和查找
 */
@Slf4j
@Component
public class CommandRegistry {
    
    private final Map<String, Command> commands = new ConcurrentHashMap<>();
    private final Map<String, Command> aliases = new ConcurrentHashMap<>();
    
    private final List<Command> commandList;
    
    @Autowired
    public CommandRegistry(List<Command> commandList) {
        this.commandList = commandList != null ? commandList : Collections.emptyList();
    }
    
    @PostConstruct
    public void initialize() {
        log.info("初始化命令注册器...");
        
        for (Command command : commandList) {
            register(command);
        }
        
        log.info("已注册 {} 个命令，{} 个别名", commands.size(), aliases.size());
        
        // 打印注册的命令列表（调试用）
        if (log.isDebugEnabled()) {
            commands.keySet().stream().sorted().forEach(name -> 
                log.debug("已注册命令: {}", name));
        }
    }
    
    /**
     * 注册命令
     */
    public void register(Command command) {
        if (command == null) {
            log.warn("尝试注册空命令，忽略");
            return;
        }
        
        String name = command.getName();
        if (name == null || name.trim().isEmpty()) {
            log.warn("命令名称为空，忽略: {}", command.getClass().getName());
            return;
        }
        
        // 检查命令名称冲突
        if (commands.containsKey(name)) {
            log.warn("命令名称冲突，覆盖原有命令: {}", name);
        }
        
        commands.put(name, command);
        log.debug("注册命令: {} -> {}", name, command.getClass().getSimpleName());
        
        // 注册别名
        List<String> commandAliases = command.getAliases();
        if (commandAliases != null) {
            for (String alias : commandAliases) {
                if (alias != null && !alias.trim().isEmpty()) {
                    if (aliases.containsKey(alias)) {
                        log.warn("别名冲突，覆盖原有别名: {} -> {}", alias, name);
                    }
                    aliases.put(alias, command);
                    log.debug("注册别名: {} -> {}", alias, name);
                }
            }
        }
    }
    
    /**
     * 注销命令
     */
    public void unregister(String commandName) {
        if (commandName == null) {
            return;
        }
        
        Command removed = commands.remove(commandName);
        if (removed != null) {
            log.debug("注销命令: {}", commandName);
            
            // 移除相关别名
            List<String> aliasesToRemove = new ArrayList<>();
            for (Map.Entry<String, Command> entry : aliases.entrySet()) {
                if (entry.getValue() == removed) {
                    aliasesToRemove.add(entry.getKey());
                }
            }
            
            for (String alias : aliasesToRemove) {
                aliases.remove(alias);
                log.debug("移除别名: {}", alias);
            }
        }
    }
    
    /**
     * 根据名称或别名获取命令
     */
    public Command getCommand(String name) {
        if (name == null) {
            return null;
        }
        
        // 首先查找命令名称
        Command command = commands.get(name);
        if (command != null) {
            return command;
        }
        
        // 然后查找别名
        return aliases.get(name);
    }
    
    /**
     * 检查命令是否存在
     */
    public boolean hasCommand(String name) {
        return getCommand(name) != null;
    }
    
    /**
     * 获取所有命令
     */
    public Collection<Command> getAllCommands() {
        return new ArrayList<>(commands.values());
    }
    
    /**
     * 获取所有命令名称
     */
    public Set<String> getAllCommandNames() {
        return new HashSet<>(commands.keySet());
    }
    
    /**
     * 获取所有别名
     */
    public Set<String> getAllAliases() {
        return new HashSet<>(aliases.keySet());
    }
    
    /**
     * 获取命令统计信息
     */
    public CommandRegistryStats getStats() {
        return CommandRegistryStats.builder()
            .totalCommands(commands.size())
            .totalAliases(aliases.size())
            .commandNames(new ArrayList<>(commands.keySet()))
            .aliasNames(new ArrayList<>(aliases.keySet()))
            .build();
    }
    
    /**
     * 查找匹配的命令（支持部分匹配）
     */
    public List<String> findMatchingCommands(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return new ArrayList<>(commands.keySet());
        }
        
        List<String> matches = new ArrayList<>();
        String lowerPrefix = prefix.toLowerCase();
        
        // 查找命令名称匹配
        for (String name : commands.keySet()) {
            if (name.toLowerCase().startsWith(lowerPrefix)) {
                matches.add(name);
            }
        }
        
        // 查找别名匹配
        for (String alias : aliases.keySet()) {
            if (alias.toLowerCase().startsWith(lowerPrefix) && !matches.contains(alias)) {
                matches.add(alias);
            }
        }
        
        Collections.sort(matches);
        return matches;
    }
    
    /**
     * 打印帮助信息
     */
    public void printHelp() {
        System.out.println("港股程序化交易系统 CLI v1.0");
        System.out.println("=====================================");
        System.out.println();
        System.out.println("可用命令:");
        
        // 按名称排序
        List<Command> sortedCommands = new ArrayList<>(commands.values());
        sortedCommands.sort(Comparator.comparing(Command::getName));
        
        for (Command command : sortedCommands) {
            String aliases = "";
            if (!command.getAliases().isEmpty()) {
                aliases = " (" + String.join(", ", command.getAliases()) + ")";
            }
            System.out.printf("  %-15s %s%s%n", 
                command.getName(), command.getDescription(), aliases);
        }
        
        System.out.println();
        System.out.println("使用示例:");
        System.out.println("  java -jar trading.jar backtest --strategy MACD --symbol 02800.HK");
        System.out.println("  java -jar trading.jar help <command>  # 查看特定命令的帮助");
        System.out.println("  java -jar trading.jar shell          # 进入交互式模式");
        System.out.println();
        System.out.println("获取更多帮助:");
        System.out.println("  java -jar trading.jar <command> --help");
    }
    
    /**
     * 命令注册器统计信息
     */
    @lombok.Builder
    @lombok.Data
    public static class CommandRegistryStats {
        private int totalCommands;
        private int totalAliases;
        private List<String> commandNames;
        private List<String> aliasNames;
    }
}