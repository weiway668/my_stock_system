package com.trading.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import com.trading.infrastructure.futu.FutuWebSocketClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FUTU启动服务
 * 负责在应用启动时自动连接FUTU服务器
 * 只在配置启用时运行
 */
@Slf4j
@Service
@Order(1000) // 较低优先级，确保其他服务先启动
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.futu.connection.host")
public class FutuStartupService implements ApplicationRunner {

    private final FutuWebSocketClient futuWebSocketClient;

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始FUTU服务启动流程");

        try {
            if (args.containsOption("no-futu-connect") || args.containsOption("offline")) {
                log.info("检测到离线模式或禁用FUTU连接参数，跳过FUTU连接");
                return;
            }

            log.info("尝试同步连接到FUTU OpenD服务器...");
            boolean connected = futuWebSocketClient.connectSync();

            if (connected) {
                log.info("✅ FUTU连接成功！系统已准备就绪");
            } else {
                log.warn("❌ FUTU连接失败，系统可能无法获取实时数据");
                showConnectionTroubleshootingTips();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("FUTU连接过程中被中断", e);
            showConnectionTroubleshootingTips();
        } catch (Exception e) {
            log.error("FUTU连接过程中出现未知异常", e);
            showConnectionTroubleshootingTips();
        }
    }

    /**
     * 显示连接故障排除提示
     */
    private void showConnectionTroubleshootingTips() {
        log.info("=== FUTU连接故障排除提示 ===");
        log.info("1. 确保FUTU OpenD程序已启动");
        log.info("2. 检查FUTU OpenD是否运行在端口11111");
        log.info("3. 确认application-dev.yml中的连接配置正确");
        log.info("4. 检查防火墙设置");
        log.info("5. 如需离线开发，可使用参数: --no-futu-connect");
        log.info("==========================");
    }

    /**
     * 初始化默认订阅（可选）
     */
    private void initializeDefaultSubscriptions() {
        log.debug("初始化默认股票订阅");

        // 可以在这里订阅一些常用的港股股票
        String[] defaultSymbols = {
                "00700.HK", // 腾讯控股
                "02800.HK", // 盈富基金
                "03033.HK" // 恒生科技ETF
        };

        for (String symbol : defaultSymbols) {
            try {
                // 简单的报价订阅
                futuWebSocketClient.subscribeQuote(symbol, quote -> {
                    log.debug("收到{}的报价更新: 最新价={}", symbol,
                            quote != null ? quote.getLastPrice() : "N/A");
                });

                log.debug("已订阅股票: {}", symbol);

                // 避免订阅过快
                Thread.sleep(100);

            } catch (Exception e) {
                log.warn("订阅股票{}失败: {}", symbol, e.getMessage());
            }
        }

        log.info("默认股票订阅完成");
    }
}