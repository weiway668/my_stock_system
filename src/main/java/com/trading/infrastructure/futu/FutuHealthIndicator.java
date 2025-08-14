package com.trading.infrastructure.futu;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FUTU Health Indicator
 * Provides health check information for FUTU OpenD connections
 * Integrates with Spring Boot Actuator health endpoints
 * 
 * TEMPORARILY DISABLED due to actuator dependency issues
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.futu.connection.host")
public class FutuHealthIndicator implements HealthIndicator {

    private final FutuConnection futuConnection;

    private static final int HEARTBEAT_TIMEOUT_MINUTES = 2;

    @Override
    public Health health() {
        try {
            if (futuConnection == null) {
                return Health.down()
                    .withDetail("error", "FUTU connection not available")
                    .build();
            }
            
            FutuConnection.ConnectionStatus status = futuConnection.getConnectionStatus();

            // Check overall connection health
            if (status.isConnected()) {
                return createHealthyStatus(status);
            } else {
                return createUnhealthyStatus(status);
            }

        } catch (Exception e) {
            log.error("Error checking FUTU health", e);
            return Health.down()
                    .withDetail("error", "Failed to check FUTU connection health")
                    .withDetail("exception", e.getMessage())
                    .build();
        }
    }

    /**
     * Create healthy status response
     */
    private Health createHealthyStatus(FutuConnection.ConnectionStatus status) {
        Map<String, Object> details = createStatusDetails(status);
        details.put("status", "All connections healthy");

        return Health.up()
                .withDetails(details)
                .build();
    }

    /**
     * Create unhealthy status response
     */
    private Health createUnhealthyStatus(FutuConnection.ConnectionStatus status) {
        Map<String, Object> details = createStatusDetails(status);

        // Determine specific issues
        String statusMessage = determineStatusMessage(status);
        details.put("status", statusMessage);

        return Health.down()
                .withDetails(details)
                .build();
    }

    /**
     * Create status details map
     */
    private Map<String, Object> createStatusDetails(FutuConnection.ConnectionStatus status) {
        Map<String, Object> details = new LinkedHashMap<>();

        // Basic connection status
        details.put("connected", status.isConnected());
        details.put("connecting", status.isConnecting());

        // Timing information
        if (status.getLastConnectTime() != null) {
            details.put("lastConnectTime", status.getLastConnectTime().toString());
            details.put("connectAge", getHeartbeatAge(status.getLastConnectTime()));
        }

        if (status.getLastDisconnectTime() != null) {
            details.put("lastDisconnectTime", status.getLastDisconnectTime().toString());
            details.put("disconnectAge", getHeartbeatAge(status.getLastDisconnectTime()));
        }

        // Retry and error information
        details.put("retryCount", status.getRetryCount());
        if (status.getErrorMessage() != null) {
            details.put("errorMessage", status.getErrorMessage());
        }

        // Uptime
        details.put("uptime", status.getUptime() + " seconds");

        return details;
    }

    /**
     * Determine status message based on connection state
     */
    private String determineStatusMessage(FutuConnection.ConnectionStatus status) {
        if (status.isConnecting()) {
            return "Connection in progress (attempt " + status.getRetryCount() + ")";
        }

        if (!status.isConnected()) {
            return "FUTU connection down";
        }

        if (status.getErrorMessage() != null) {
            return "Connection error: " + status.getErrorMessage();
        }

        return "Connection status unknown";
    }

    // 注意：以下方法暂时注释掉，因为当前ConnectionStatus接口暂未包含详细的心跳信息
    // 在Phase 4中根据需要可以扩展ConnectionStatus接口来支持更详细的连接状态信息
    
    /*
     * Check if quote connection is healthy based on heartbeat
     * 待Phase 4中根据实际需求实现
     */
    /*
    private boolean isQuoteConnectionHealthy(FutuConnection.ConnectionStatus status) {
        // 暂时简化为检查基本连接状态
        return status.isConnected();
    }
    */

    /*
     * Check if trade connection is healthy based on heartbeat  
     * 待Phase 4中根据实际需求实现
     */
    /*
    private boolean isTradeConnectionHealthy(FutuConnection.ConnectionStatus status) {
        // 暂时简化为检查基本连接状态
        return status.isConnected();
    }
    */

    /**
     * Get heartbeat age in human readable format
     */
    private String getHeartbeatAge(LocalDateTime heartbeatTime) {
        long seconds = ChronoUnit.SECONDS.between(heartbeatTime, LocalDateTime.now());

        if (seconds < 60) {
            return seconds + " seconds ago";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            return minutes + " minute" + (minutes != 1 ? "s" : "") + " ago";
        } else {
            long hours = seconds / 3600;
            return hours + " hour" + (hours != 1 ? "s" : "") + " ago";
        }
    }
}