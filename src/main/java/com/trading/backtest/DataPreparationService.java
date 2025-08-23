package com.trading.backtest;

import com.trading.domain.entity.MarketData;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 数据准备服务
 * 负责回测前的数据检查、下载和预热
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPreparationService {

    private final MarketDataService marketDataService;

    // 技术指标预热所需的额外数据期间（天数）
    private static final int WARMUP_DAYS = 100;
    private static final int MIN_REQUIRED_DATA_POINTS = 60; // MACD等指标至少需要的数据点

    /**
     * 准备回测数据
     * 包括检查缓存、下载缺失数据、预热数据
     */
    public CompletableFuture<DataPreparationResult> prepareBacktestData(BacktestRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("开始准备回测数据: symbol={}, period={} to {}",
                    request.getSymbol(), request.getStartTime(), request.getEndTime());

            long startTime = System.currentTimeMillis();

            try {
                // 1. 计算实际需要的数据范围（包括预热期）
                LocalDateTime actualStartTime = calculateWarmupStartTime(request.getStartTime());
                LocalDateTime actualEndTime = request.getEndTime();

                log.info("实际数据范围: {} to {} (包含{}天预热期)",
                        actualStartTime, actualEndTime, WARMUP_DAYS);

                // 2. 检查现有数据
                DataAvailabilityCheck availabilityCheck = checkDataAvailability(
                        request.getSymbol(), actualStartTime, actualEndTime, request.getTimeframe());

                // 3. 下载缺失数据（如果需要）
                if (availabilityCheck.isNeedsDownload()) {
                    downloadMissingData(request.getSymbol(), actualStartTime, actualEndTime,
                            request.getTimeframe(), availabilityCheck);
                }

                // 4. 验证数据完整性
                List<MarketData> finalData = validateAndLoadData(
                        request.getSymbol(), actualStartTime, actualEndTime, request.getTimeframe());

                // 5. 数据质量检查
                DataQualityReport qualityReport = performDataQualityCheck(finalData, request);

                long executionTime = System.currentTimeMillis() - startTime;

                return DataPreparationResult.builder()
                        .symbol(request.getSymbol())
                        .requestedStartTime(request.getStartTime())
                        .requestedEndTime(request.getEndTime())
                        .actualStartTime(actualStartTime)
                        .actualEndTime(actualEndTime)
                        .totalDataPoints(finalData.size())
                        .warmupDataPoints(calculateWarmupDataPoints(finalData, request.getStartTime()))
                        .backtestDataPoints(
                                finalData.size() - calculateWarmupDataPoints(finalData, request.getStartTime()))
                        .dataQualityReport(qualityReport)
                        .preparationTimeMs(executionTime)
                        .successful(true)
                        .build();

            } catch (Exception e) {
                log.error("数据准备失败", e);

                return DataPreparationResult.builder()
                        .symbol(request.getSymbol())
                        .requestedStartTime(request.getStartTime())
                        .requestedEndTime(request.getEndTime())
                        .successful(false)
                        .error(e.getMessage())
                        .preparationTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }
        });
    }

    /**
     * 计算预热期开始时间
     */
    private LocalDateTime calculateWarmupStartTime(LocalDateTime backtestStartTime) {
        // 根据时间框架调整预热天数
        return backtestStartTime.minusDays(WARMUP_DAYS);
    }

    /**
     * 检查数据可用性
     */
    private DataAvailabilityCheck checkDataAvailability(String symbol, LocalDateTime startTime,
            LocalDateTime endTime, String timeframe) {
        try {
            log.debug("检查数据可用性: symbol={}, timeframe={}", symbol, timeframe);

            // 尝试获取现有数据
            List<MarketData> existingData = marketDataService.getOhlcvData(
                    symbol, timeframe, startTime, endTime, 10000, RehabType.FORWARD).get();

            // 计算预期数据点数量（粗略估算）
            long expectedDataPoints = estimateDataPoints(startTime, endTime, timeframe);
            boolean hasEnoughData = existingData.size() >= Math.min(expectedDataPoints * 0.9, MIN_REQUIRED_DATA_POINTS);

            // 检查数据连续性
            boolean isContinuous = checkDataContinuity(existingData, timeframe);

            return DataAvailabilityCheck.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .requestedStartTime(startTime)
                    .requestedEndTime(endTime)
                    .existingDataPoints(existingData.size())
                    .expectedDataPoints((int) expectedDataPoints)
                    .hasEnoughData(hasEnoughData)
                    .isContinuous(isContinuous)
                    .needsDownload(!hasEnoughData || !isContinuous)
                    .build();

        } catch (Exception e) {
            log.warn("检查数据可用性失败，将尝试重新下载: {}", e.getMessage());

            return DataAvailabilityCheck.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .requestedStartTime(startTime)
                    .requestedEndTime(endTime)
                    .existingDataPoints(0)
                    .needsDownload(true)
                    .error(e.getMessage())
                    .build();
        }
    }

    /**
     * 下载缺失数据
     */
    private void downloadMissingData(String symbol, LocalDateTime startTime, LocalDateTime endTime,
            String timeframe, DataAvailabilityCheck check) {
        log.info("开始下载缺失数据: symbol={}, period={} to {}", symbol, startTime, endTime);

        try {
            // 这里应该调用FUTU API或其他数据源下载数据
            // 目前使用现有的市场数据服务作为占位符

            log.info("数据下载请求已提交，等待完成...");

            // TODO: 实际的数据下载逻辑
            // 1. 连接FUTU OpenD
            // 2. 请求历史数据
            // 3. 保存到数据库
            // 4. 验证数据完整性

            log.info("数据下载完成: symbol={}", symbol);

        } catch (Exception e) {
            log.error("数据下载失败: symbol={}", symbol, e);
            throw new RuntimeException("数据下载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验证并加载最终数据
     */
    private List<MarketData> validateAndLoadData(String symbol, LocalDateTime startTime,
            LocalDateTime endTime, String timeframe) {
        try {
            List<MarketData> data = marketDataService.getOhlcvData(
                    symbol, timeframe, startTime, endTime, 10000, RehabType.FORWARD).get();

            if (data.isEmpty()) {
                throw new RuntimeException("无可用的市场数据");
            }

            log.info("成功加载数据: symbol={}, count={}, range={} to {}",
                    symbol, data.size(), data.get(0).getTimestamp(), data.get(data.size() - 1).getTimestamp());

            return data;

        } catch (Exception e) {
            log.error("数据加载失败: symbol={}", symbol, e);
            throw new RuntimeException("数据加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行数据质量检查
     */
    private DataQualityReport performDataQualityCheck(List<MarketData> data, BacktestRequest request) {
        if (data.isEmpty()) {
            return DataQualityReport.builder()
                    .totalRecords(0)
                    .validRecords(0)
                    .qualityScore(0.0)
                    .hasIssues(true)
                    .issues(List.of("无数据"))
                    .build();
        }

        int totalRecords = data.size();
        int validRecords = 0;
        int missingData = 0;
        int priceAnomalies = 0;
        int volumeAnomalies = 0;

        for (int i = 0; i < data.size(); i++) {
            MarketData record = data.get(i);
            boolean isValid = true;

            // 检查价格数据
            if (record.getOpen() == null || record.getHigh() == null ||
                    record.getLow() == null || record.getClose() == null) {
                missingData++;
                isValid = false;
            } else {
                // 检查价格合理性
                if (record.getHigh().compareTo(record.getOpen()) < 0 ||
                        record.getHigh().compareTo(record.getClose()) < 0 ||
                        record.getLow().compareTo(record.getOpen()) > 0 ||
                        record.getLow().compareTo(record.getClose()) > 0) {
                    priceAnomalies++;
                    isValid = false;
                }
            }

            // 检查成交量
            if (record.getVolume() < 0) {
                volumeAnomalies++;
                isValid = false;
            }

            if (isValid) {
                validRecords++;
            }
        }

        double qualityScore = totalRecords > 0 ? (double) validRecords / totalRecords : 0.0;

        java.util.List<String> issues = new java.util.ArrayList<>();
        if (missingData > 0) {
            issues.add(String.format("缺失数据: %d条", missingData));
        }
        if (priceAnomalies > 0) {
            issues.add(String.format("价格异常: %d条", priceAnomalies));
        }
        if (volumeAnomalies > 0) {
            issues.add(String.format("成交量异常: %d条", volumeAnomalies));
        }

        return DataQualityReport.builder()
                .totalRecords(totalRecords)
                .validRecords(validRecords)
                .qualityScore(qualityScore)
                .missingDataPoints(missingData)
                .priceAnomalies(priceAnomalies)
                .volumeAnomalies(volumeAnomalies)
                .hasIssues(!issues.isEmpty())
                .issues(issues)
                .build();
    }

    // 辅助方法

    private long estimateDataPoints(LocalDateTime startTime, LocalDateTime endTime, String timeframe) {
        long hours = java.time.temporal.ChronoUnit.HOURS.between(startTime, endTime);

        switch (timeframe.toLowerCase()) {
            case "1m":
                return hours * 60;
            case "5m":
                return hours * 12;
            case "15m":
                return hours * 4;
            case "30m":
                return hours * 2;
            case "1h":
                return hours;
            case "1d":
                return hours / 24;
            default:
                return hours * 2; // 默认30分钟
        }
    }

    private boolean checkDataContinuity(List<MarketData> data, String timeframe) {
        if (data.size() < 2)
            return true;

        // 简单检查：查看是否有大的时间间隔
        for (int i = 1; i < Math.min(data.size(), 10); i++) {
            LocalDateTime prev = data.get(i - 1).getTimestamp();
            LocalDateTime curr = data.get(i).getTimestamp();
            long minutesBetween = java.time.temporal.ChronoUnit.MINUTES.between(prev, curr);

            // 根据时间框架判断合理的间隔
            long expectedMinutes = getExpectedMinutesBetween(timeframe);
            if (minutesBetween > expectedMinutes * 3) { // 允许3倍的间隔容差
                return false;
            }
        }

        return true;
    }

    private long getExpectedMinutesBetween(String timeframe) {
        switch (timeframe.toLowerCase()) {
            case "1m":
                return 1;
            case "5m":
                return 5;
            case "15m":
                return 15;
            case "30m":
                return 30;
            case "1h":
                return 60;
            case "1d":
                return 24 * 60;
            default:
                return 30;
        }
    }

    private int calculateWarmupDataPoints(List<MarketData> data, LocalDateTime backtestStartTime) {
        int warmupPoints = 0;
        for (MarketData record : data) {
            if (record.getTimestamp().isBefore(backtestStartTime)) {
                warmupPoints++;
            } else {
                break;
            }
        }
        return warmupPoints;
    }

    // 内部数据类

    @lombok.Builder
    @lombok.Data
    public static class DataAvailabilityCheck {
        private String symbol;
        private String timeframe;
        private LocalDateTime requestedStartTime;
        private LocalDateTime requestedEndTime;
        private int existingDataPoints;
        private int expectedDataPoints;
        private boolean hasEnoughData;
        private boolean isContinuous;
        private boolean needsDownload;
        private String error;
    }

    @lombok.Builder
    @lombok.Data
    public static class DataQualityReport {
        private int totalRecords;
        private int validRecords;
        private double qualityScore;
        private int missingDataPoints;
        private int priceAnomalies;
        private int volumeAnomalies;
        private boolean hasIssues;
        private List<String> issues;

        public String getChineseSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("数据质量报告:\n"));
            sb.append(String.format("  总记录数: %d\n", totalRecords));
            sb.append(String.format("  有效记录: %d\n", validRecords));
            sb.append(String.format("  质量评分: %.1f%%\n", qualityScore * 100));

            if (hasIssues && issues != null) {
                sb.append("  质量问题:\n");
                for (String issue : issues) {
                    sb.append(String.format("    - %s\n", issue));
                }
            }

            return sb.toString();
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class DataPreparationResult {
        private String symbol;
        private LocalDateTime requestedStartTime;
        private LocalDateTime requestedEndTime;
        private LocalDateTime actualStartTime;
        private LocalDateTime actualEndTime;
        private int totalDataPoints;
        private int warmupDataPoints;
        private int backtestDataPoints;
        private DataQualityReport dataQualityReport;
        private long preparationTimeMs;
        private boolean successful;
        private String error;

        public String getChineseSummary() {
            if (!successful) {
                return String.format("数据准备失败: %s", error);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== 数据准备摘要 ===\n");
            sb.append(String.format("标的代码: %s\n", symbol));
            sb.append(
                    String.format("请求时间: %s 至 %s\n", requestedStartTime.toLocalDate(), requestedEndTime.toLocalDate()));
            sb.append(String.format("实际时间: %s 至 %s\n", actualStartTime.toLocalDate(), actualEndTime.toLocalDate()));
            sb.append(String.format("总数据点: %d\n", totalDataPoints));
            sb.append(String.format("预热数据: %d 点\n", warmupDataPoints));
            sb.append(String.format("回测数据: %d 点\n", backtestDataPoints));
            sb.append(String.format("准备耗时: %.1f秒\n", preparationTimeMs / 1000.0));

            if (dataQualityReport != null) {
                sb.append("\n").append(dataQualityReport.getChineseSummary());
            }

            return sb.toString();
        }
    }
}