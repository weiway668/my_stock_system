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

    private final FutuConnectionManager connectionManager;

    private static final int HEARTBEAT_TIMEOUT_MINUTES = 2;

    @Override
    public Health health() {
        try {
            FutuConnectionManager.ConnectionStatus status = connectionManager.getConnectionStatus();

            // Check overall connection health
            if (status.isHealthy()) {
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
    private Health createHealthyStatus(FutuConnectionManager.ConnectionStatus status) {
        Map<String, Object> details = createStatusDetails(status);
        details.put("status", "All connections healthy");

        return Health.up()
                .withDetails(details)
                .build();
    }

    /**
     * Create unhealthy status response
     */
    private Health createUnhealthyStatus(FutuConnectionManager.ConnectionStatus status) {
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
    private Map<String, Object> createStatusDetails(FutuConnectionManager.ConnectionStatus status) {
        Map<String, Object> details = new LinkedHashMap<>();

        // Connection status
        details.put("quoteConnected", status.isQuoteConnected());
        details.put("tradeConnected", status.isTradeConnected());
        details.put("connecting", status.isConnecting());
        details.put("fullyConnected", status.isHealthy());

        // Retry information
        details.put("retryCount", status.getRetryCount());

        // Heartbeat information
        if (status.getLastQuoteHeartbeat() != null) {
            details.put("lastQuoteHeartbeat", status.getLastQuoteHeartbeat().toString());
            details.put("quoteHeartbeatAge", getHeartbeatAge(status.getLastQuoteHeartbeat()));
        }

        if (status.getLastTradeHeartbeat() != null) {
            details.put("lastTradeHeartbeat", status.getLastTradeHeartbeat().toString());
            details.put("tradeHeartbeatAge", getHeartbeatAge(status.getLastTradeHeartbeat()));
        }

        // Connection health assessment
        details.put("quoteConnectionHealthy", isQuoteConnectionHealthy(status));
        details.put("tradeConnectionHealthy", isTradeConnectionHealthy(status));

        return details;
    }

    /**
     * Determine status message based on connection state
     */
    private String determineStatusMessage(FutuConnectionManager.ConnectionStatus status) {
        if (status.isConnecting()) {
            return "Connection in progress (attempt " + status.getRetryCount() + ")";
        }

        if (!status.isQuoteConnected() && !status.isTradeConnected()) {
            return "Both quote and trade connections down";
        }

        if (!status.isQuoteConnected()) {
            return "Quote connection down";
        }

        if (!status.isTradeConnected()) {
            return "Trade connection down";
        }

        // Check heartbeat health
        if (!isQuoteConnectionHealthy(status)) {
            return "Quote connection heartbeat timeout";
        }

        if (!isTradeConnectionHealthy(status)) {
            return "Trade connection heartbeat timeout";
        }

        return "Connection status unknown";
    }

    /**
     * Check if quote connection is healthy based on heartbeat
     */
    private boolean isQuoteConnectionHealthy(FutuConnectionManager.ConnectionStatus status) {
        if (!status.isQuoteConnected()) {
            return false;
        }

        if (status.getLastQuoteHeartbeat() == null) {
            return false;
        }

        long minutesSinceHeartbeat = ChronoUnit.MINUTES.between(
                status.getLastQuoteHeartbeat(),
                LocalDateTime.now());

        return minutesSinceHeartbeat <= HEARTBEAT_TIMEOUT_MINUTES;
    }

    /**
     * Check if trade connection is healthy based on heartbeat
     */
    private boolean isTradeConnectionHealthy(FutuConnectionManager.ConnectionStatus status) {
        if (!status.isTradeConnected()) {
            return false;
        }

        if (status.getLastTradeHeartbeat() == null) {
            return false;
        }

        long minutesSinceHeartbeat = ChronoUnit.MINUTES.between(
                status.getLastTradeHeartbeat(),
                LocalDateTime.now());

        return minutesSinceHeartbeat <= HEARTBEAT_TIMEOUT_MINUTES;
    }

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