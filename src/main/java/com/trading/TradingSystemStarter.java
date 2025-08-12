package com.trading;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 简化版交易系统启动器
 * 用于测试基础功能是否可以正常运行
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
public class TradingSystemStarter {

    public static void main(String[] args) {
        SpringApplication.run(TradingSystemStarter.class, args);
    }

    @Bean
    public CommandLineRunner runner() {
        return args -> {
            log.info("=====================================");
            log.info("香港股票算法交易系统已启动");
            log.info("=====================================");
            log.info("系统功能:");
            log.info("1. 市场数据服务 - 获取实时和历史行情");
            log.info("2. 交易服务 - 订单管理和执行");
            log.info("3. 信号处理 - 技术指标分析");
            log.info("4. 风险管理 - 仓位和资金控制");
            log.info("=====================================");
            log.info("系统已就绪，等待操作...");
        };
    }
}