package com.trading.cli;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.*;

import java.util.Collections;
import java.util.List;

/**
 * 抽象命令基类
 * 提供命令行参数解析和常用功能
 */
@Slf4j
public abstract class AbstractCommand implements Command {
    
    protected static final String ANSI_RESET = "\u001B[0m";
    protected static final String ANSI_GREEN = "\u001B[32m";
    protected static final String ANSI_RED = "\u001B[31m";
    protected static final String ANSI_YELLOW = "\u001B[33m";
    protected static final String ANSI_CYAN = "\u001B[36m";
    protected static final String ANSI_BOLD = "\u001B[1m";
    
    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }
    
    /**
     * 解析命令行参数
     * @param args 命令行参数
     * @param options 选项定义
     * @return 解析后的命令行对象
     * @throws CommandException 解析失败时抛出异常
     */
    protected CommandLine parseArgs(String[] args, Options options) throws CommandException {
        CommandLineParser parser = new DefaultParser();
        try {
            return parser.parse(options, args);
        } catch (ParseException e) {
            throw CommandException.invalidArgument(getName(), args, e.getMessage());
        }
    }
    
    /**
     * 验证必需参数
     * @param cmd 命令行对象
     * @param requiredOptions 必需的选项名称
     * @throws CommandException 缺少必需参数时抛出异常
     */
    protected void validateRequired(CommandLine cmd, String... requiredOptions) throws CommandException {
        for (String option : requiredOptions) {
            if (!cmd.hasOption(option)) {
                throw CommandException.missingRequired(getName(), null, "--" + option);
            }
        }
    }
    
    /**
     * 获取字符串选项值，如果不存在则返回默认值
     */
    protected String getOptionValue(CommandLine cmd, String option, String defaultValue) {
        return cmd.hasOption(option) ? cmd.getOptionValue(option) : defaultValue;
    }
    
    /**
     * 获取整数选项值，如果不存在则返回默认值
     */
    protected int getIntOptionValue(CommandLine cmd, String option, int defaultValue) {
        if (!cmd.hasOption(option)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(cmd.getOptionValue(option));
        } catch (NumberFormatException e) {
            throw CommandException.invalidArgument(getName(), null, 
                "选项 --" + option + " 必须是数字");
        }
    }
    
    /**
     * 获取长整型选项值，如果不存在则返回默认值
     */
    protected long getLongOptionValue(CommandLine cmd, String option, long defaultValue) {
        if (!cmd.hasOption(option)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(cmd.getOptionValue(option));
        } catch (NumberFormatException e) {
            throw CommandException.invalidArgument(getName(), null, 
                "选项 --" + option + " 必须是数字");
        }
    }
    
    /**
     * 获取双精度浮点选项值，如果不存在则返回默认值
     */
    protected double getDoubleOptionValue(CommandLine cmd, String option, double defaultValue) {
        if (!cmd.hasOption(option)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(cmd.getOptionValue(option));
        } catch (NumberFormatException e) {
            throw CommandException.invalidArgument(getName(), null, 
                "选项 --" + option + " 必须是数字");
        }
    }
    
    /**
     * 打印成功信息
     */
    protected void printSuccess(String message) {
        System.out.println(ANSI_GREEN + "✅ " + message + ANSI_RESET);
    }
    
    /**
     * 打印错误信息
     */
    protected void printError(String message) {
        System.err.println(ANSI_RED + "❌ " + message + ANSI_RESET);
    }
    
    /**
     * 打印警告信息
     */
    protected void printWarning(String message) {
        System.out.println(ANSI_YELLOW + "⚠️  " + message + ANSI_RESET);
    }
    
    /**
     * 打印信息
     */
    protected void printInfo(String message) {
        System.out.println(ANSI_CYAN + "ℹ️  " + message + ANSI_RESET);
    }
    
    /**
     * 打印表格标题
     */
    protected void printTableHeader(String title) {
        System.out.println();
        System.out.println(ANSI_BOLD + "=== " + title + " ===" + ANSI_RESET);
    }
    
    /**
     * 打印分隔线
     */
    protected void printSeparator() {
        System.out.println("─────────────────────────────────────────────────────────────");
    }
    
    /**
     * 显示进度条
     */
    protected void showProgress(String task, int current, int total) {
        if (total <= 0) return;
        
        int percentage = (current * 100) / total;
        int barLength = 40;
        int filledLength = (current * barLength) / total;
        
        StringBuilder bar = new StringBuilder();
        bar.append("[");
        for (int i = 0; i < barLength; i++) {
            if (i < filledLength) {
                bar.append("█");
            } else {
                bar.append("░");
            }
        }
        bar.append("]");
        
        System.out.printf("\r%s %s %3d%% (%d/%d)", 
            task, bar.toString(), percentage, current, total);
        
        if (current == total) {
            System.out.println(); // 完成后换行
        }
    }
    
    /**
     * 创建基础选项
     */
    protected Options createBaseOptions() {
        Options options = new Options();
        options.addOption("h", "help", false, "显示帮助信息");
        options.addOption("v", "verbose", false, "详细输出");
        options.addOption("q", "quiet", false, "静默模式");
        return options;
    }
    
    /**
     * 检查是否显示帮助
     */
    protected boolean shouldShowHelp(CommandLine cmd) {
        return cmd.hasOption("help");
    }
    
    /**
     * 检查是否为详细模式
     */
    protected boolean isVerbose(CommandLine cmd) {
        return cmd.hasOption("verbose");
    }
    
    /**
     * 检查是否为静默模式
     */
    protected boolean isQuiet(CommandLine cmd) {
        return cmd.hasOption("quiet");
    }
    
    /**
     * 创建选项
     */
    protected Option createOption(String shortName, String longName, String description, boolean hasArg) {
        return Option.builder(shortName)
            .longOpt(longName)
            .desc(description)
            .hasArg(hasArg)
            .build();
    }
    
    /**
     * 创建必需选项
     */
    protected Option createRequiredOption(String shortName, String longName, String description, boolean hasArg) {
        return Option.builder(shortName)
            .longOpt(longName)
            .desc(description)
            .hasArg(hasArg)
            .required(true)
            .build();
    }
    
    /**
     * 打印使用说明的通用格式
     */
    protected void printUsageHeader(String usage) {
        System.out.println(ANSI_BOLD + "用法:" + ANSI_RESET);
        System.out.println("  " + usage);
        System.out.println();
    }
    
    /**
     * 打印选项说明
     */
    protected void printOptions(Options options) {
        System.out.println(ANSI_BOLD + "选项:" + ANSI_RESET);
        HelpFormatter formatter = new HelpFormatter();
        formatter.printOptions(new java.io.PrintWriter(System.out, true), 80, options, 2, 2);
        System.out.println();
    }
    
    /**
     * 打印示例
     */
    protected void printExamples() {
        List<String> examples = getExamples();
        if (!examples.isEmpty()) {
            System.out.println(ANSI_BOLD + "示例:" + ANSI_RESET);
            for (String example : examples) {
                System.out.println("  " + example);
            }
            System.out.println();
        }
    }
}