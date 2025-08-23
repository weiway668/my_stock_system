package com.trading.service.impl;

import com.trading.common.enums.MarketType;
import com.trading.domain.entity.CorporateActionEntity;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.domain.entity.HistoricalKLineEntity.DataSource;
import com.trading.domain.entity.HistoricalKLineEntity.DataStatus;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.repository.CorporateActionRepository;
import com.trading.repository.HistoricalDataRepository;
import com.trading.service.CorporateActionService;
import com.trading.service.HistoricalDataService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 历史数据服务实现类
 * 提供多市场、多周期的历史K线数据管理功能
 */
@Slf4j
@Service
public class HistoricalDataServiceImpl implements HistoricalDataService {

    private final HistoricalDataRepository historicalDataRepository;
    private final CorporateActionRepository corporateActionRepository;
    private final FutuMarketDataService futuMarketDataService;
    private CorporateActionService corporateActionService;

    public HistoricalDataServiceImpl(HistoricalDataRepository historicalDataRepository,
            CorporateActionRepository corporateActionRepository, FutuMarketDataService futuMarketDataService) {
        this.historicalDataRepository = historicalDataRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.futuMarketDataService = futuMarketDataService;
    }

    // 下载任务管理
    private final Map<String, CompletableFuture<Void>> downloadTasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> downloadProgress = new ConcurrentHashMap<>();

    // 线程池配置
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(5);

    // 配置参数
    @Value("${historical-data.download.batch-size:5}")
    private int batchDownloadSize;

    @Value("${historical-data.download.page-size:1000}")
    private int pageSize;

    @Value("${historical-data.download.retry-times:3}")
    private int retryTimes;

    @Value("${historical-data.download.retry-delay:5000}")
    private long retryDelay;

    @Autowired @Lazy
    public void setCorporateActionService(CorporateActionService corporateActionService) {
        this.corporateActionService = corporateActionService;
    }

    @Override
    public List<HistoricalKLineEntity> getHistoricalKLine(
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            KLineType kLineType,
            RehabType rehabType) {

        log.debug("从Futu获取K线数据: symbol={}, period={}-{}, kLineType={}, rehabType={}",
                symbol, startDate, endDate, kLineType, rehabType);

        // 直接从Futu服务获取可能已复权的数据
        List<FutuKLine> futuKLines = futuMarketDataService.getHistoricalKLine(
                symbol, startDate, endDate, convertToFutuKLineType(kLineType), convertToFutuRehabType(rehabType));

        if (futuKLines.isEmpty()) {
            log.warn("从Futu未获取到任何K线数据: symbol={}", symbol);
            return new ArrayList<>();
        }

        // 将Futu模型转换为数据库实体
        List<HistoricalKLineEntity> entities = convertToEntities(futuKLines, MarketType.fromSymbol(symbol), kLineType, rehabType);
        log.info("成功从Futu获取并转换了 {} 条K线数据", entities.size());

        return entities;
    }

    

    @Override
    public CompletableFuture<BatchDownloadResult> downloadBatchHistoricalData(
            List<String> symbols,
            LocalDate startDate,
            LocalDate endDate,
            KLineType kLineType,
            RehabType rehabType) {

        String taskId = generateTaskId(symbols, kLineType);
        log.info("开始批量下载历史数据: taskId={}, symbols={}, period={}-{}, kLineType={}",
                taskId, symbols, startDate, endDate, kLineType);

        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            Map<String, DownloadResult> results = new ConcurrentHashMap<>();

            try {
                // 初始化进度跟踪
                downloadProgress.put(taskId, 0);

                // 分批处理股票列表
                List<List<String>> batches = createBatches(symbols, batchDownloadSize);
                int totalBatches = batches.size();
                int completedBatches = 0;

                for (List<String> batch : batches) {
                    // 并行下载当前批次的股票
                    List<CompletableFuture<DownloadResult>> batchTasks = batch.stream()
                            .map(symbol -> downloadSingleSymbol(symbol, startDate, endDate, kLineType, rehabType))
                            .collect(Collectors.toList());

                    // 等待当前批次完成
                    CompletableFuture.allOf(batchTasks.toArray(new CompletableFuture[0])).join();

                    // 收集结果
                    for (CompletableFuture<DownloadResult> task : batchTasks) {
                        try {
                            DownloadResult result = task.get();
                            results.put(result.symbol(), result);
                        } catch (Exception e) {
                            log.warn("获取下载结果失败: {}", e.getMessage());
                        }
                    }

                    // 更新进度
                    completedBatches++;
                    int progress = (completedBatches * 100) / totalBatches;
                    downloadProgress.put(taskId, progress);

                    log.info("批次进度: {}/{}, 总进度: {}%", completedBatches, totalBatches, progress);
                }

                // 完成下载
                downloadProgress.put(taskId, 100);

                // 统计结果
                long successCount = results.values().stream().mapToLong(r -> r.success() ? 1 : 0).sum();
                long failedCount = results.size() - successCount;
                long totalTime = System.currentTimeMillis() - startTime;

                String summary = String.format("批量下载完成: 成功=%d, 失败=%d, 耗时=%.1fs",
                        successCount, failedCount, totalTime / 1000.0);

                log.info(summary);

                return new BatchDownloadResult(
                        taskId,
                        symbols.size(),
                        (int) successCount,
                        (int) failedCount,
                        results,
                        totalTime,
                        summary);

            } catch (Exception e) {
                log.error("批量下载异常: taskId={}", taskId, e);
                downloadProgress.put(taskId, -1); // 标记为失败
                throw new RuntimeException("批量下载失败: " + e.getMessage(), e);
            }
        }, downloadExecutor);
    }

    @Override
    public CompletableFuture<DownloadResult> downloadHistoricalData(
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            KLineType kLineType,
            RehabType rehabType) {

        return downloadSingleSymbol(symbol, startDate, endDate, kLineType, rehabType);
    }

    private CompletableFuture<DownloadResult> downloadSingleSymbol(
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            KLineType kLineType,
            RehabType rehabType) {

        return CompletableFuture.supplyAsync(() -> {
            log.debug("开始下载单个股票数据: symbol={}, period={}-{}", symbol, startDate, endDate);

            long startTime = System.currentTimeMillis();
            MarketType market = MarketType.fromSymbol(symbol);

            try {
                // 获取FUTU历史数据
                List<FutuKLine> futuKLines = futuMarketDataService.getHistoricalKLine(
                        symbol, startDate, endDate, convertToFutuKLineType(kLineType), convertToFutuRehabType(rehabType));

                if (futuKLines.isEmpty()) {
                    log.warn("未获取到历史数据: symbol={}", symbol);
                    return new DownloadResult(
                            symbol, market, kLineType,
                            startDate.atStartOfDay(), endDate.atTime(23, 59, 59),
                            0, 0, false,
                            "未获取到历史数据", System.currentTimeMillis() - startTime);
                }

                // 转换并保存数据，强制使用不复权类型
                List<HistoricalKLineEntity> entities = convertToEntities(futuKLines, market, kLineType, rehabType);
                int savedCount = saveHistoricalData(entities);

                log.info("下载完成: symbol={}, downloaded={}, saved={}",
                        symbol, futuKLines.size(), savedCount);

                return new DownloadResult(
                        symbol, market, kLineType,
                        startDate.atStartOfDay(), endDate.atTime(23, 59, 59),
                        futuKLines.size(), savedCount, true,
                        null, System.currentTimeMillis() - startTime);

            } catch (Exception e) {
                log.error("下载股票数据失败: symbol={}", symbol, e);
                return new DownloadResult(
                        symbol, market, kLineType,
                        startDate.atStartOfDay(), endDate.atTime(23, 59, 59),
                        0, 0, false,
                        e.getMessage(), System.currentTimeMillis() - startTime);
            }
        }, downloadExecutor);
    }

    @Override
    public CompletableFuture<UpdateResult> incrementalUpdate(String symbol, KLineType kLineType) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("开始增量更新: symbol={}, kLineType={}", symbol, kLineType);

            try {
                // 获取本地最新数据时间
                Optional<LocalDateTime> lastLocalTime = historicalDataRepository
                        .findLatestTimestamp(symbol, kLineType, RehabType.NONE);

                LocalDateTime updateStartTime;
                if (lastLocalTime.isPresent()) {
                    // 从最后一条数据的下一个周期开始更新
                    updateStartTime = calculateNextPeriod(lastLocalTime.get(), kLineType);
                } else {
                    // 如果没有历史数据，从30天前开始
                    updateStartTime = LocalDateTime.now().minusDays(30);
                }

                LocalDateTime updateEndTime = LocalDateTime.now();

                // 检查是否需要更新
                if (!updateStartTime.isBefore(updateEndTime)) {
                    log.debug("数据已是最新: symbol={}", symbol);
                    return new UpdateResult(
                            symbol, kLineType,
                            lastLocalTime.orElse(null), updateEndTime,
                            0, true, "数据已是最新");
                }

                // 执行增量下载
                DownloadResult downloadResult = downloadSingleSymbol(
                        symbol,
                        updateStartTime.toLocalDate(),
                        updateEndTime.toLocalDate(),
                        kLineType,
                        RehabType.NONE).get();

                return new UpdateResult(
                        symbol, kLineType,
                        lastLocalTime.orElse(null), updateEndTime,
                        downloadResult.savedCount(), downloadResult.success(),
                        downloadResult.success() ? "增量更新完成" : downloadResult.errorMessage());

            } catch (Exception e) {
                log.error("增量更新失败: symbol={}", symbol, e);
                return new UpdateResult(
                        symbol, kLineType,
                        null, LocalDateTime.now(),
                        0, false, "增量更新失败: " + e.getMessage());
            }
        }, downloadExecutor);
    }

    @Override
    public CompletableFuture<Map<String, UpdateResult>> batchIncrementalUpdate(
            List<String> symbols, KLineType kLineType) {

        return CompletableFuture.supplyAsync(() -> {
            Map<String, UpdateResult> results = new ConcurrentHashMap<>();

            // 并行执行增量更新
            List<CompletableFuture<UpdateResult>> updateTasks = symbols.stream()
                    .map(symbol -> incrementalUpdate(symbol, kLineType))
                    .collect(Collectors.toList());

            // 等待所有任务完成并收集结果
            for (int i = 0; i < updateTasks.size(); i++) {
                try {
                    UpdateResult result = updateTasks.get(i).get();
                    results.put(symbols.get(i), result);
                } catch (Exception e) {
                    log.warn("批量增量更新中的单个任务失败: symbol={}", symbols.get(i), e);
                    results.put(symbols.get(i), new UpdateResult(
                            symbols.get(i), kLineType, null, LocalDateTime.now(),
                            0, false, "更新任务异常: " + e.getMessage()));
                }
            }

            return results;
        }, downloadExecutor);
    }

    @Override
    public DataIntegrityReport validateDataIntegrity(
            String symbol, LocalDate startDate, LocalDate endDate, KLineType kLineType) {

        log.debug("开始数据完整性校验: symbol={}, period={}-{}", symbol, startDate, endDate);

        try {
            // 查询实际数据
            List<HistoricalKLineEntity> actualData = historicalDataRepository
                    .findBySymbolAndTimeRange(symbol, kLineType, RehabType.NONE,
                            startDate.atStartOfDay(), endDate.atTime(23, 59, 59));

            // 计算预期数据点数量
            int expectedCount = calculateExpectedDataPoints(startDate, endDate, kLineType,
                    MarketType.fromSymbol(symbol));

            // 分析数据缺口
            List<DataGap> gaps = findDataGaps(actualData, kLineType);

            // 检查数据问题
            List<String> issues = validateDataQuality(actualData);

            // 计算完整率
            double completenessRate = expectedCount > 0 ? (double) actualData.size() / expectedCount : 1.0;

            boolean isComplete = completenessRate >= 0.95 && gaps.isEmpty() && issues.isEmpty();

            log.debug("完整性校验完成: symbol={}, completeness={}%, gaps={}, issues={}",
                    symbol, completenessRate * 100, gaps.size(), issues.size());

            return new DataIntegrityReport(
                    symbol, kLineType, startDate, endDate,
                    expectedCount, actualData.size(), completenessRate,
                    gaps, issues, isComplete);

        } catch (Exception e) {
            log.error("数据完整性校验异常: symbol={}", symbol, e);
            return new DataIntegrityReport(
                    symbol, kLineType, startDate, endDate,
                    0, 0, 0.0,
                    Collections.emptyList(),
                    List.of("校验异常: " + e.getMessage()),
                    false);
        }
    }

    @Override
    public DataQualityReport getDataQualityReport(String symbol, KLineType kLineType) {
        log.debug("生成数据质量报告: symbol={}, kLineType={}", symbol, kLineType);

        try {
            // 获取所有数据
            List<HistoricalKLineEntity> allData = historicalDataRepository
                    .findBySymbolAndTimeRange(symbol, kLineType, RehabType.NONE,
                            LocalDateTime.now().minusYears(1), LocalDateTime.now());

            if (allData.isEmpty()) {
                return new DataQualityReport(
                        symbol, kLineType, 0, 0, 0.0,
                        0, 0, 0, Map.of(),
                        Collections.emptyList(), "无数据");
            }

            // 数据质量分析
            int totalRecords = allData.size();
            int validRecords = 0;
            int priceAnomalies = 0;
            int volumeAnomalies = 0;
            int missingFields = 0;
            List<DataAnomaly> anomalies = new ArrayList<>();

            for (HistoricalKLineEntity entity : allData) {
                boolean isValid = true;

                // 检查字段完整性
                if (entity.getOpen() == null || entity.getHigh() == null ||
                        entity.getLow() == null || entity.getClose() == null) {
                    missingFields++;
                    isValid = false;
                    anomalies.add(new DataAnomaly(
                            entity.getTimestamp(), "OHLC", "非空", "缺失", "字段缺失"));
                }

                // 检查价格合理性
                if (entity.getOpen() != null && entity.getHigh() != null &&
                        entity.getLow() != null && entity.getClose() != null) {
                    if (!entity.isValidKLine()) {
                        priceAnomalies++;
                        isValid = false;
                        anomalies.add(new DataAnomaly(
                                entity.getTimestamp(), "价格关系", "High≥Low≥Close/Open",
                                String.format("H=%.2f,L=%.2f,O=%.2f,C=%.2f",
                                        entity.getHigh(), entity.getLow(),
                                        entity.getOpen(), entity.getClose()),
                                "价格异常"));
                    }
                }

                // 检查成交量
                if (entity.getVolume() != null && entity.getVolume() < 0) {
                    volumeAnomalies++;
                    isValid = false;
                    anomalies.add(new DataAnomaly(
                            entity.getTimestamp(), "成交量", "≥0",
                            entity.getVolume().toString(), "成交量异常"));
                }

                if (isValid) {
                    validRecords++;
                }
            }

            // 计算质量分数
            double qualityScore = totalRecords > 0 ? (double) validRecords / totalRecords * 100 : 0;

            // 问题统计
            Map<String, Integer> issueStats = Map.of(
                    "字段缺失", missingFields,
                    "价格异常", priceAnomalies,
                    "成交量异常", volumeAnomalies);

            // 生成建议
            String recommendation = generateQualityRecommendation(qualityScore, issueStats);

            return new DataQualityReport(
                    symbol, kLineType, totalRecords, validRecords, qualityScore,
                    priceAnomalies, volumeAnomalies, missingFields,
                    issueStats, anomalies, recommendation);

        } catch (Exception e) {
            log.error("生成数据质量报告异常: symbol={}", symbol, e);
            return new DataQualityReport(
                    symbol, kLineType, 0, 0, 0.0,
                    0, 0, 0, Map.of(),
                    Collections.emptyList(), "报告生成失败: " + e.getMessage());
        }
    }

    @Override
    public CompletableFuture<RepairResult> repairDataGaps(String symbol, KLineType kLineType) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("开始修复数据缺口: symbol={}, kLineType={}", symbol, kLineType);

            try {
                // 获取数据时间范围
                DataTimeRange timeRange = getLocalDataTimeRange(symbol, kLineType);
                if (!timeRange.hasData()) {
                    return new RepairResult(
                            symbol, kLineType, 0, 0, 0, true,
                            List.of("无历史数据，无需修复"));
                }

                // 检查数据完整性
                DataIntegrityReport integrityReport = validateDataIntegrity(
                        symbol, timeRange.earliest().toLocalDate(),
                        timeRange.latest().toLocalDate(), kLineType);

                List<String> repairDetails = new ArrayList<>();
                int gapsFound = integrityReport.gaps().size();
                int gapsRepaired = 0;
                int recordsAdded = 0;

                if (gapsFound == 0) {
                    repairDetails.add("未发现数据缺口");
                } else {
                    // 修复每个缺口
                    for (DataGap gap : integrityReport.gaps()) {
                        try {
                            DownloadResult repairResult = downloadSingleSymbol(
                                    symbol,
                                    gap.startTime().toLocalDate(),
                                    gap.endTime().toLocalDate(),
                                    kLineType,
                                    RehabType.NONE).get();

                            if (repairResult.success()) {
                                gapsRepaired++;
                                recordsAdded += repairResult.savedCount();
                                repairDetails.add(String.format("修复缺口: %s - %s, 添加%d条记录",
                                        gap.startTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                                        gap.endTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                                        repairResult.savedCount()));
                            } else {
                                repairDetails.add(String.format("修复失败: %s - %s, 原因: %s",
                                        gap.startTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                                        gap.endTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                                        repairResult.errorMessage()));
                            }
                        } catch (Exception e) {
                            log.warn("修复单个缺口失败: {}", e.getMessage());
                            repairDetails.add(String.format("修复异常: %s", e.getMessage()));
                        }
                    }
                }

                log.info("数据缺口修复完成: symbol={}, 发现缺口={}, 修复成功={}, 添加记录={}",
                        symbol, gapsFound, gapsRepaired, recordsAdded);

                return new RepairResult(
                        symbol, kLineType, gapsFound, gapsRepaired, recordsAdded,
                        gapsRepaired == gapsFound, repairDetails);

            } catch (Exception e) {
                log.error("修复数据缺口异常: symbol={}", symbol, e);
                return new RepairResult(
                        symbol, kLineType, 0, 0, 0, false,
                        List.of("修复异常: " + e.getMessage()));
            }
        }, downloadExecutor);
    }

    @Override
    public DataTimeRange getLocalDataTimeRange(String symbol, KLineType kLineType) {
        try {
            Optional<LocalDateTime> earliest = historicalDataRepository
                    .findEarliestTimestamp(symbol, kLineType, RehabType.NONE);
            Optional<LocalDateTime> latest = historicalDataRepository
                    .findLatestTimestamp(symbol, kLineType, RehabType.NONE);

            if (earliest.isEmpty() || latest.isEmpty()) {
                return new DataTimeRange(symbol, kLineType, null, null, 0, false);
            }

            long totalRecords = historicalDataRepository
                    .countBySymbolAndTimeRange(symbol, kLineType, RehabType.NONE,
                            earliest.get(), latest.get());

            return new DataTimeRange(
                    symbol, kLineType,
                    earliest.get(), latest.get(),
                    (int) totalRecords, true);

        } catch (Exception e) {
            log.warn("获取数据时间范围失败: symbol={}", symbol, e);
            return new DataTimeRange(symbol, kLineType, null, null, 0, false);
        }
    }

    @Override
    public int cleanExpiredData(KLineType kLineType, int keepDays) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(keepDays);
        log.info("清理过期数据: kLineType={}, cutoffTime={}", kLineType, cutoffTime);

        try {
            int deletedCount = historicalDataRepository.deleteExpiredData(kLineType, cutoffTime);
            log.info("清理完成: kLineType={}, 删除记录数={}", kLineType, deletedCount);
            return deletedCount;
        } catch (Exception e) {
            log.error("清理过期数据异常: kLineType={}", kLineType, e);
            return 0;
        }
    }

    @Override
    public int getDownloadProgress(String taskId) {
        return downloadProgress.getOrDefault(taskId, -1);
    }

    @Override
    public boolean cancelDownloadTask(String taskId) {
        CompletableFuture<Void> task = downloadTasks.get(taskId);
        if (task != null && !task.isDone()) {
            boolean cancelled = task.cancel(true);
            if (cancelled) {
                downloadProgress.put(taskId, -2); // 标记为已取消
                downloadTasks.remove(taskId);
                log.info("下载任务已取消: taskId={}", taskId);
            }
            return cancelled;
        }
        return false;
    }

    // ==================== 私有辅助方法 ====================

    private String generateTaskId(List<String> symbols, KLineType kLineType) {
        return String.format("batch_%s_%d_%d",
                kLineType.name(), symbols.hashCode(), System.currentTimeMillis());
    }

    private List<List<String>> createBatches(List<String> items, int batchSize) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(items.subList(i, Math.min(i + batchSize, items.size())));
        }
        return batches;
    }

    private com.futu.openapi.pb.QotCommon.RehabType convertToFutuRehabType(RehabType rehabType) {
        return switch (rehabType) {
            case FORWARD -> com.futu.openapi.pb.QotCommon.RehabType.RehabType_Forward;
            case BACKWARD -> com.futu.openapi.pb.QotCommon.RehabType.RehabType_Backward;
            default -> com.futu.openapi.pb.QotCommon.RehabType.RehabType_None;
        };
    }

    private FutuMarketDataService.KLineType convertToFutuKLineType(KLineType kLineType) {
        return switch (kLineType) {
            case K_1MIN -> FutuMarketDataService.KLineType.K_1MIN;
            case K_5MIN -> FutuMarketDataService.KLineType.K_5MIN;
            case K_15MIN -> FutuMarketDataService.KLineType.K_15MIN;
            case K_30MIN -> FutuMarketDataService.KLineType.K_30MIN;
            case K_60MIN -> FutuMarketDataService.KLineType.K_60MIN;
            case K_DAY -> FutuMarketDataService.KLineType.K_DAY;
            case K_WEEK -> FutuMarketDataService.KLineType.K_WEEK;
            case K_MONTH -> FutuMarketDataService.KLineType.K_MONTH;
        };
    }

    private List<HistoricalKLineEntity> convertToEntities(
            List<FutuKLine> futuKLines, MarketType market,
            KLineType kLineType, RehabType rehabType) {

        return futuKLines.stream().map(futuKLine -> {
            HistoricalKLineEntity entity = HistoricalKLineEntity.builder()
                    .symbol(futuKLine.getCode())
                    .market(market)
                    .klineType(kLineType)
                    .rehabType(rehabType)
                    .timestamp(futuKLine.getTimestamp())
                    .open(futuKLine.getOpen())
                    .high(futuKLine.getHigh())
                    .low(futuKLine.getLow())
                    .close(futuKLine.getClose())
                    .volume(futuKLine.getVolume())
                    .turnover(futuKLine.getTurnover())
                    .preClose(futuKLine.getPreClose())
                    .changeValue(futuKLine.getChangeValue())
                    .changeRate(futuKLine.getChangeRate())
                    .turnoverRate(futuKLine.getTurnoverRate())
                    .dataSource(DataSource.FUTU)
                    .dataStatus(DataStatus.DOWNLOADED)
                    .downloadTime(LocalDateTime.now())
                    .qualityScore(calculateQualityScore(futuKLine))
                    .dataVersion(1)
                    .build();

            entity.generateId();
            return entity;
        }).collect(Collectors.toList());
    }

    @Transactional
    private int saveHistoricalData(List<HistoricalKLineEntity> entities) {
        try {
            // 使用批量保存提高性能
            historicalDataRepository.saveAll(entities);
            log.debug("批量保存历史数据: count={}", entities.size());
            return entities.size();
        } catch (Exception e) {
            log.warn("批量保存失败，尝试单条保存: {}", e.getMessage());

            int savedCount = 0;
            for (HistoricalKLineEntity entity : entities) {
                try {
                    historicalDataRepository.save(entity);
                    savedCount++;
                } catch (Exception ex) {
                    log.debug("保存单条数据失败: {}", entity.getDescription(), ex);
                }
            }
            return savedCount;
        }
    }

    private LocalDateTime calculateNextPeriod(LocalDateTime lastTime, KLineType kLineType) {
        return switch (kLineType) {
            case K_1MIN -> lastTime.plusMinutes(1);
            case K_5MIN -> lastTime.plusMinutes(5);
            case K_15MIN -> lastTime.plusMinutes(15);
            case K_30MIN -> lastTime.plusMinutes(30);
            case K_60MIN -> lastTime.plusHours(1);
            case K_DAY -> lastTime.plusDays(1);
            case K_WEEK -> lastTime.plusWeeks(1);
            case K_MONTH -> lastTime.plusMonths(1);
        };
    }

    private int calculateExpectedDataPoints(LocalDate startDate, LocalDate endDate,
            KLineType kLineType, MarketType market) {
        // 简化的计算逻辑，实际应考虑交易日历
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;

        // 假设每周5个交易日
        long tradingDays = days * 5 / 7;

        return switch (kLineType) {
            case K_1MIN -> (int) (tradingDays * 240); // 每日240根1分钟K线
            case K_5MIN -> (int) (tradingDays * 48); // 每日48根5分钟K线
            case K_15MIN -> (int) (tradingDays * 16); // 每日16根15分钟K线
            case K_30MIN -> (int) (tradingDays * 8); // 每日8根30分钟K线
            case K_60MIN -> (int) (tradingDays * 4); // 每日4根60分钟K线
            case K_DAY -> (int) tradingDays; // 每日1根日K线
            case K_WEEK -> (int) (tradingDays / 5); // 每周1根周K线
            case K_MONTH -> (int) (tradingDays / 22); // 每月1根月K线
        };
    }

    private List<DataGap> findDataGaps(List<HistoricalKLineEntity> data, KLineType kLineType) {
        if (data.size() < 2) {
            return Collections.emptyList();
        }

        List<DataGap> gaps = new ArrayList<>();
        data.sort(Comparator.comparing(HistoricalKLineEntity::getTimestamp));

        for (int i = 1; i < data.size(); i++) {
            HistoricalKLineEntity prev = data.get(i - 1);
            HistoricalKLineEntity curr = data.get(i);

            LocalDateTime expectedNextTime = calculateNextPeriod(prev.getTimestamp(), kLineType);

            // 如果当前时间与预期下一个时间点之间有间隔，说明存在缺口
            if (curr.getTimestamp().isAfter(expectedNextTime)) {
                int missingCount = calculateMissingPeriods(
                        expectedNextTime, curr.getTimestamp(), kLineType);

                if (missingCount > 0) {
                    gaps.add(new DataGap(
                            expectedNextTime,
                            curr.getTimestamp().minus(getPeriodDuration(kLineType)),
                            missingCount,
                            "数据时间序列中断"));
                }
            }
        }

        return gaps;
    }

    private int calculateMissingPeriods(LocalDateTime start, LocalDateTime end, KLineType kLineType) {
        return switch (kLineType) {
            case K_1MIN -> (int) java.time.temporal.ChronoUnit.MINUTES.between(start, end);
            case K_5MIN -> (int) (java.time.temporal.ChronoUnit.MINUTES.between(start, end) / 5);
            case K_15MIN -> (int) (java.time.temporal.ChronoUnit.MINUTES.between(start, end) / 15);
            case K_30MIN -> (int) (java.time.temporal.ChronoUnit.MINUTES.between(start, end) / 30);
            case K_60MIN -> (int) java.time.temporal.ChronoUnit.HOURS.between(start, end);
            case K_DAY -> (int) java.time.temporal.ChronoUnit.DAYS.between(start, end);
            case K_WEEK -> (int) java.time.temporal.ChronoUnit.WEEKS.between(start, end);
            case K_MONTH -> (int) java.time.temporal.ChronoUnit.MONTHS.between(start, end);
        };
    }

    private java.time.temporal.TemporalAmount getPeriodDuration(KLineType kLineType) {
        return switch (kLineType) {
            case K_1MIN -> java.time.Duration.ofMinutes(1);
            case K_5MIN -> java.time.Duration.ofMinutes(5);
            case K_15MIN -> java.time.Duration.ofMinutes(15);
            case K_30MIN -> java.time.Duration.ofMinutes(30);
            case K_60MIN -> java.time.Duration.ofHours(1);
            case K_DAY -> java.time.Duration.ofDays(1);
            case K_WEEK -> java.time.Duration.ofDays(7);
            case K_MONTH -> java.time.Duration.ofDays(30);
        };
    }

    private List<String> validateDataQuality(List<HistoricalKLineEntity> data) {
        List<String> issues = new ArrayList<>();

        for (HistoricalKLineEntity entity : data) {
            if (!entity.isValidKLine()) {
                issues.add(String.format("价格数据异常: %s", entity.getTimestamp()));
            }
        }

        return issues;
    }

    private int calculateQualityScore(FutuKLine futuKLine) {
        int score = 100;

        // 检查必要字段
        if (futuKLine.getOpen() == null || futuKLine.getHigh() == null ||
                futuKLine.getLow() == null || futuKLine.getClose() == null) {
            score -= 50;
        }

        // 检查价格合理性
        if (futuKLine.getHigh() != null && futuKLine.getLow() != null &&
                futuKLine.getHigh().compareTo(futuKLine.getLow()) < 0) {
            score -= 30;
        }

        // 检查成交量
        if (futuKLine.getVolume() == null || futuKLine.getVolume() < 0) {
            score -= 20;
        }

        return Math.max(0, score);
    }

    private String generateQualityRecommendation(double qualityScore, Map<String, Integer> issueStats) {
        if (qualityScore >= 95) {
            return "数据质量优秀，无需特殊处理";
        } else if (qualityScore >= 80) {
            return "数据质量良好，建议定期校验";
        } else if (qualityScore >= 60) {
            return "数据质量一般，建议重新下载部分数据";
        } else {
            return "数据质量较差，建议重新下载全部数据";
        }
    }

    @Override
    public List<HistoricalKLineEntity> getRecordsForDate(String symbol, LocalDate date) {
        return getRecordsForDateRange(symbol, date, date);
    }

    @Override
    public List<HistoricalKLineEntity> getRecordsForDateRange(String symbol, LocalDate fromDate, LocalDate toDate) {
        LocalDateTime startOfDay = fromDate.atStartOfDay();
        // 包含当天，所以结束时间是 toDate 的后一天的开始
        LocalDateTime endOfDay = toDate.plusDays(1).atStartOfDay();
        log.debug("正在从数据库查询符号 {} 在 {} (含) 和 {} (不含) 之间的数据", symbol, startOfDay, endOfDay);
        return historicalDataRepository.findBySymbolAndTimestampBetweenOrderByTimestampAsc(symbol, startOfDay, endOfDay);
    }

    @Override
    @Transactional
    public void deleteHistoricalData(String symbol, LocalDate fromDate, LocalDate toDate) {
        LocalDateTime startOfDay = fromDate.atStartOfDay();
        LocalDateTime endOfDay = toDate.plusDays(1).atStartOfDay();
        log.info("正在删除符号 {} 在 {} (含) 和 {} (不含) 之间的数据", symbol, startOfDay, endOfDay);
        historicalDataRepository.deleteBySymbolAndTimestampBetween(symbol, startOfDay, endOfDay);
    }
}
