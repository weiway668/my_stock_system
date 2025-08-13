package com.trading.cli;

import java.util.List;

/**
 * 命令接口
 * 定义CLI命令的基本规范
 */
public interface Command {
    
    /**
     * 获取命令名称
     */
    String getName();
    
    /**
     * 获取命令描述
     */
    String getDescription();
    
    /**
     * 执行命令
     * @param args 命令行参数
     * @throws CommandException 命令执行异常
     */
    void execute(String[] args) throws CommandException;
    
    /**
     * 打印命令使用说明
     */
    void printUsage();
    
    /**
     * 获取命令别名列表
     */
    default List<String> getAliases() {
        return List.of();
    }
    
    /**
     * 获取命令示例
     */
    default List<String> getExamples() {
        return List.of();
    }
    
    /**
     * 检查是否支持交互式模式
     */
    default boolean supportsInteractiveMode() {
        return false;
    }
}