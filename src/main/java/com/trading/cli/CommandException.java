package com.trading.cli;

/**
 * 命令执行异常
 * 用于包装命令执行过程中的各种异常情况
 */
public class CommandException extends RuntimeException {
    
    private final String commandName;
    private final String[] args;
    
    public CommandException(String message) {
        super(message);
        this.commandName = null;
        this.args = null;
    }
    
    public CommandException(String message, Throwable cause) {
        super(message, cause);
        this.commandName = null;
        this.args = null;
    }
    
    public CommandException(String commandName, String[] args, String message) {
        super(String.format("命令 '%s' 执行失败: %s", commandName, message));
        this.commandName = commandName;
        this.args = args != null ? args.clone() : null;
    }
    
    public CommandException(String commandName, String[] args, String message, Throwable cause) {
        super(String.format("命令 '%s' 执行失败: %s", commandName, message), cause);
        this.commandName = commandName;
        this.args = args != null ? args.clone() : null;
    }
    
    public String getCommandName() {
        return commandName;
    }
    
    public String[] getArgs() {
        return args != null ? args.clone() : null;
    }
    
    /**
     * 参数解析错误
     */
    public static CommandException invalidArgument(String commandName, String[] args, String message) {
        return new CommandException(commandName, args, "参数错误: " + message);
    }
    
    /**
     * 缺少必需参数
     */
    public static CommandException missingRequired(String commandName, String[] args, String paramName) {
        return new CommandException(commandName, args, "缺少必需参数: " + paramName);
    }
    
    /**
     * 命令执行失败
     */
    public static CommandException executionFailed(String commandName, String[] args, String message) {
        return new CommandException(commandName, args, "执行失败: " + message);
    }
    
    /**
     * 命令执行失败（带异常）
     */
    public static CommandException executionFailed(String commandName, String[] args, String message, Throwable cause) {
        return new CommandException(commandName, args, "执行失败: " + message, cause);
    }
}