package com.trading.cli.commands;

import com.trading.cli.AbstractCommand;
import com.trading.cli.CommandException;
import com.trading.service.CorporateActionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 同步复权信息命令
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRehabCommand extends AbstractCommand {

    private final CorporateActionService corporateActionService;

    @Override
    public String getName() {
        return "system:sync-rehab";
    }

    @Override
    public String getDescription() {
        return "同步股票的复权信息";
    }

    @Override
    public List<String> getAliases() {
        return List.of("sync-rehab");
    }

    public List<String> getExamples() {
        return List.of(
            "system:sync-rehab                # 同步所有配置的目标股票",
            "system:sync-rehab --symbol HK.00700  # 只同步腾讯控股一只股票"
        );
    }

    @Override
    public void execute(String[] args) throws CommandException {
        try {
            Options options = createOptions();
            CommandLine cmd = parseArgs(args, options);

            if (shouldShowHelp(cmd)) {
                printUsage();
                return;
            }

            String symbol = cmd.getOptionValue("symbol");

            if (symbol != null && !symbol.trim().isEmpty()) {
                // 同步指定股票
                printInfo(String.format("开始为股票 %s 执行复权信息同步...", symbol));
                printSeparator();
                corporateActionService.processAndSaveCorporateActionForStock(symbol);
            } else {
                // 同步所有股票
                printInfo("开始为所有目标股票执行复权信息同步...");
                printSeparator();
                corporateActionService.processAndSaveAllCorporateActions();
            }

            printSeparator();
            printSuccess("复权信息同步完成。");

        } catch (Exception e) {
            log.error("复权信息同步失败", e);
            throw CommandException.executionFailed(getName(), args, e.getMessage(), e);
        }
    }

    @Override
    public void printUsage() {
        printUsageHeader("java -jar trading.jar system:sync-rehab [选项]");
        System.out.println(ANSI_BOLD + "描述:" + ANSI_RESET);
        System.out.println("  从Futu API获取历史复权信息（如分红、拆股等）并保存到本地数据库。");
        System.out.println("  可以同步所有在配置文件中定义的目标股票，或通过选项指定单个股票。");
        System.out.println();
        printOptions(createOptions());
        printExamples();
    }

    protected Options createOptions() {
        Options options = createBaseOptions(); // 使用基础选项如 --help
        options.addOption(createOption("sym", "symbol", "指定要同步的单个股票代码 (如: HK.00700)", true));
        return options;
    }
}
