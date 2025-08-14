package com.trading.config;

import com.trading.infrastructure.futu.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FUTU配置集成测试
 * 验证Spring配置是否正确加载和注入所有FUTU相关服务
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "trading.futu.connection.host=127.0.0.1",
    "trading.futu.connection.port=11111"
})
class FutuConfigurationTest {

    @Autowired(required = false)
    private FutuWebSocketClient futuWebSocketClient;

    @Autowired(required = false)
    private FutuMarketDataService futuMarketDataService;

    @Autowired(required = false)
    private FutuTradeService futuTradeService;

    @Autowired(required = false)
    private FutuConfiguration.FutuProperties futuProperties;

    @Test
    void shouldCreateFutuWebSocketClientBean() {
        assertThat(futuWebSocketClient)
            .as("FutuWebSocketClient应该被正确创建")
            .isNotNull();
    }

    @Test
    void shouldCreateFutuMarketDataServiceBean() {
        assertThat(futuMarketDataService)
            .as("FutuMarketDataService应该被正确创建")
            .isNotNull();
            
        assertThat(futuMarketDataService)
            .as("应该是FutuMarketDataServiceImpl的实例")
            .isInstanceOf(FutuMarketDataServiceImpl.class);
    }

    @Test
    void shouldCreateFutuTradeServiceBean() {
        assertThat(futuTradeService)
            .as("FutuTradeService应该被正确创建")
            .isNotNull();
            
        assertThat(futuTradeService)
            .as("应该是FutuTradeServiceImpl的实例")
            .isInstanceOf(FutuTradeServiceImpl.class);
    }

    @Test
    void shouldLoadFutuPropertiesCorrectly() {
        assertThat(futuProperties)
            .as("FUTU配置属性应该被正确加载")
            .isNotNull();

        assertThat(futuProperties.getConnection().getHost())
            .as("连接主机应该被正确设置")
            .isEqualTo("127.0.0.1");

        assertThat(futuProperties.getConnection().getPort())
            .as("连接端口应该被正确设置")
            .isEqualTo(11111);
    }

    @Test
    void shouldHaveCorrectServiceIntegration() {
        // 验证服务之间的依赖关系
        assertThat(futuMarketDataService.isServiceAvailable())
            .as("行情服务应该能够检查可用性")
            .isNotNull();
            
        assertThat(futuTradeService.isServiceAvailable())
            .as("交易服务应该能够检查可用性")
            .isNotNull();
    }

    @Test
    void shouldHandleDefaultConfigurationValues() {
        // 验证默认配置值
        assertThat(futuProperties.getQuote().getMaxSubscriptions())
            .as("最大订阅数量应该有默认值")
            .isGreaterThan(0);

        assertThat(futuProperties.getTrade().getEnvironment())
            .as("交易环境应该有默认值")
            .isNotEmpty();

        assertThat(futuProperties.getAccount().isEnableRiskControl())
            .as("风控应该默认启用")
            .isTrue();
    }

    /**
     * 测试配置类，用于提供测试特定的bean
     */
    @TestConfiguration
    static class TestConfig {
        // 可以在这里添加测试特定的bean配置
    }
}