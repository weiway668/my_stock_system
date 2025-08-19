package com.trading.cli.commands;

import com.trading.cli.AbstractCommand;
import com.trading.cli.CommandException;
import com.trading.domain.entity.CorporateActionEntity;
import com.trading.domain.entity.CorporateActionEntity.CorporateActionType;
import com.trading.repository.CorporateActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 显示指定股票的本地复权数据命令
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShowRehabCommand extends AbstractCommand {

    private final CorporateActionRepository corporateActionRepository;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getName() {
        return "data:show-rehab";
    }

    @Override
    public String getDescription() {
        return "查询并显示指定股票存储在本地数据库中的公司行动（复权）数据";
    }

    @Override
    public List<String> getAliases() {
        return List.of("show-rehab");
    }

    @Override
    public List<String> getExamples() {
        return List.of("data:show-rehab --symbol HK.9988");
    }

    @Override
    public void execute(String[] args) throws CommandException {
        try {
            Options options = createOptions();
            CommandLine cmd = parseArgs(args, options);

            if (shouldShowHelp(cmd) || !cmd.hasOption("symbol")) {
                printUsage();
                return;
            }

            String symbol = cmd.getOptionValue("symbol");
            printInfo(String.format("正在查询股票 %s 的本地复权数据...", symbol));
            printSeparator();

            List<CorporateActionEntity> actions = corporateActionRepository.findByStockCodeOrderByExDividendDateDesc(symbol);

            if (actions.isEmpty()) {
                printWarning("在本地数据库中未找到该股票的任何复权数据。");
                printInfo("您可以尝试使用 'sync-rehab --symbol " + symbol + "' 命令来同步数据。");
                printSeparator();
                return;
            }

            printSuccess("查询到 " + actions.size() + " 条复权记录:");
            // Manually print table header
            System.out.println(String.format("%-12s | %-12s | %-15s | %-15s | %s", 
                "除权日期", "类型", "前复权因子", "后复权因子", "详情"));
            System.out.println(String.format("%-12s | %-12s | %-15s | %-15s | %s", 
                "------------", "------------", "---------------_", "---------------_", "---------------------------------"));

            for (CorporateActionEntity action : actions) {
                String details = formatActionDetails(action);
                // Manually print table row
                System.out.println(String.format("%-12s | %-12s | %-15.6f | %-15.6f | %s",
                        action.getExDividendDate().format(DATE_FORMATTER),
                        action.getActionType().name(),
                        action.getForwardAdjFactor(),
                        action.getBackwardAdjFactor(),
                        details
                ));
            }

            printSeparator();
            printSuccess("查询完成。");

        } catch (Exception e) {
            log.error("查询复权数据命令执行失败", e);
            throw CommandException.executionFailed(getName(), args, e.getMessage(), e);
        }
    }

    private String formatActionDetails(CorporateActionEntity action) {
        switch (action.getActionType()) {
            case DIVIDEND:
                return String.format("每股派息: %.4f", action.getDividend());
            case SPLIT:
                return String.format("拆股: %.2f 拆为 %.2f", action.getSplitBase(), action.getSplitErt());
            case MERGE:
                return String.format("合股: %.2f 合为 %.2f", action.getJoinBase(), action.getJoinErt());
            case BONUS:
                return String.format("送股: 每 %.2f 股送 %.2f 股", action.getBonusBase(), action.getBonusErt());
            case TRANSFER:
                return String.format("转赠: 每 %.2f 股转赠 %.2f 股", action.getTransferBase(), action.getTransferErt());
            case RIGHTS_ISSUE:
                return String.format("配股: 每 %.2f 股配 %.2f 股 @ %.3f", action.getAllotBase(), action.getAllotErt(), action.getAllotPrice());
            case ADD_ISSUE:
                 return String.format("增发: 每 %.2f 股增发 %.2f 股 @ %.3f", action.getAddBase(), action.getAddErt(), action.getAddPrice());
            case NONE:
            default:
                return "N/A";
        }
    }

    @Override
    public void printUsage() {
        printUsageHeader("java -jar trading.jar data:show-rehab --symbol <股票代码>");
        System.out.println(ANSI_BOLD + "描述:" + ANSI_RESET);
        System.out.println("  查询并显示指定股票存储在本地数据库中的公司行动（复权）数据。");
        System.out.println();
        printOptions(createOptions());
        printExamples();
    }

    protected Options createOptions() {
        Options options = createBaseOptions();
        options.addOption(createOption("sym", "symbol", "要查询的股票代码 (必需)", true));
        return options;
    }
}
