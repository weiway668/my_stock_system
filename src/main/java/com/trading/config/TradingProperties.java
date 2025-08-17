package com.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "trading")
public class TradingProperties {

    private List<Target> targets;

    @Data
    public static class Target {
        private String symbol;
        private String name;
        private String type;
        private double weight;
        private int lotSize;
    }
}
