package com.trading.cli.commands;

import com.trading.cli.AbstractCommand;
import com.trading.cli.CommandException;
import com.trading.infrastructure.futu.FutuTcpClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.springframework.stereotype.Component;

/**
 * 测试FUTU连接命令
 */
@Slf4j
@Component
public class TestConnectionCommand extends AbstractCommand {
    
    @Override
    public String getName() {
        return "test-connection";
    }
    
    @Override
    public String getDescription() {
        return "测试与FUTU OpenD的连接";
    }
    
    private Options createOptions() {
        Options options = new Options();
        options.addOption("h", "host", true, "FUTU OpenD主机地址 (默认: 127.0.0.1)");
        options.addOption("p", "port", true, "FUTU OpenD端口 (默认: 11111)");
        return options;
    }
    
    @Override
    public void execute(String[] args) throws CommandException {
        try {
            CommandLine cmd = parseArgs(args, createOptions());
            
            String host = cmd.getOptionValue("host", "127.0.0.1");
            int port = Integer.parseInt(cmd.getOptionValue("port", "11111"));
            
            printInfo("测试连接到FUTU OpenD...");
            printInfo("主机: " + host);
            printInfo("端口: " + port);
            printSeparator();
            
            FutuTcpClient client = new FutuTcpClient();
            
            if (client.connect(host, port)) {
                printSuccess("连接成功！");
                
                // 保持连接3秒
                Thread.sleep(3000);
                
                client.disconnect();
                printSuccess("连接已正常关闭");
            } else {
                printError("连接失败！");
                printInfo("请检查:");
                printInfo("1. FUTU OpenD是否已启动");
                printInfo("2. 端口是否正确（默认11111）");
                printInfo("3. 防火墙是否阻止了连接");
            }
            
        } catch (Exception e) {
            log.error("测试连接失败", e);
            throw new CommandException("测试连接失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void printUsage() {
        printUsageHeader("java -jar trading.jar test-connection [选项]");
        
        System.out.println(ANSI_BOLD + "描述:" + ANSI_RESET);
        System.out.println("  测试与FUTU OpenD的TCP连接是否正常。");
        System.out.println();
        
        printOptions(createOptions());
        
        System.out.println(ANSI_BOLD + "示例:" + ANSI_RESET);
        System.out.println("  java -jar trading.jar test-connection");
        System.out.println("  java -jar trading.jar test-connection --host 192.168.1.100 --port 11111");
    }
}