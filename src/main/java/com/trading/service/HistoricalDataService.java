package com.trading.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.trading.common.enums.MarketType;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;

/**
 * 历史数据服务接口
 * 提供历史K线数据的批量下载、增量更新、数据完整性校验等功能
 * 支持多市场（港股、A股、美股）和多K线周期
 */
public interface HistoricalDataService {
    
    /**
     * 批量下载历史K线数据
     * 支持多个股票同时下载，自动识别市场类型
     * 
     * @param symbols 股票代码列表（可包含市场后缀）
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param kLineType K线类型
     * @param rehabType 复权类型
     * @return 下载结果
     */
    CompletableFuture<BatchDownloadResult> downloadBatchHistoricalData(
            List<String> symbols,
            LocalDate startDate,
            LocalDate endDate,
            KLineType kLineType,
            RehabType rehabType
    );
    
    /**
     * 下载单个股票的历史数据
     * 
     * @param symbol 股票代码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param kLineType K线类型
     * @param rehabType 复权类型
     * @return 下载结果
     */
    CompletableFuture<DownloadResult> downloadHistoricalData(
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            KLineType kLineType,
            RehabType rehabType
    );
    
    /**
     * 增量更新数据
     * 自动检查本地最新数据，仅下载缺失部分
     * 
     * @param symbol 股票代码
     * @param kLineType K线类型
     * @return 更新结果
     */
    CompletableFuture<UpdateResult> incrementalUpdate(
            String symbol,
            KLineType kLineType
    );
    
    /**
     * 批量增量更新
     * 
     * @param symbols 股票代码列表
     * @param kLineType K线类型
     * @return 更新结果映射
     */
    CompletableFuture<Map<String, UpdateResult>> batchIncrementalUpdate(
            List<String> symbols,
            KLineType kLineType
    );
    
    /**
     * 数据完整性校验
     * 检查指定时间范围内的数据完整性
     * 
     * @param symbol 股票代码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param kLineType K线类型
     * @return 数据完整性报告
     */
    DataIntegrityReport validateDataIntegrity(
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            KLineType kLineType
    );
    
    /**
     * 获取数据质量报告
     * 分析数据质量，包括缺失率、异常值等
     * 
     * @param symbol 股票代码
     * @param kLineType K线类型
     * @return 数据质量报告
     */
    DataQualityReport getDataQualityReport(
            String symbol,
            KLineType kLineType
    );
    
    /**
     * 修复数据缺口
     * 自动检测并填补数据缺失
     * 
     * @param symbol 股票代码
     * @param kLineType K线类型
     * @return 修复结果
     */
    CompletableFuture<RepairResult> repairDataGaps(
            String symbol,
            KLineType kLineType
    );
    
    /**
     * 获取本地数据时间范围
     * 
     * @param symbol 股票代码
     * @param kLineType K线类型
     * @return 数据时间范围
     */
    DataTimeRange getLocalDataTimeRange(
            String symbol,
            KLineType kLineType
    );
    
    /**
     * 清理过期数据
     * 根据配置清理超过保留期限的数据
     * 
     * @param kLineType K线类型
     * @param keepDays 保留天数
     * @return 清理的记录数
     */
    int cleanExpiredData(KLineType kLineType, int keepDays);
    
    /**
     * 获取下载进度
     * 
     * @param taskId 任务ID
     * @return 下载进度（0-100）
     */
    int getDownloadProgress(String taskId);
    
    /**
     * 取消下载任务
     * 
     * @param taskId 任务ID
     * @return 是否成功取消
     */
    boolean cancelDownloadTask(String taskId);
    
    // ==================== 数据结构定义 ====================
    
    /**
     * 批量下载结果
     */
    record BatchDownloadResult(
            String taskId,
            int totalSymbols,
            int successCount,
            int failedCount,
            Map<String, DownloadResult> results,
            long totalTimeMs,
            String summary
    ) {}
    
    /**
     * 单个下载结果
     */
    record DownloadResult(
            String symbol,
            MarketType market,
            KLineType kLineType,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int downloadedCount,
            int savedCount,
            boolean success,
            String errorMessage,
            long timeMs
    ) {}
    
    /**
     * 增量更新结果
     */
    record UpdateResult(
            String symbol,
            KLineType kLineType,
            LocalDateTime lastLocalTime,
            LocalDateTime latestMarketTime,
            int newDataCount,
            boolean success,
            String message
    ) {}
    
    /**
     * 数据完整性报告
     */
    record DataIntegrityReport(
            String symbol,
            KLineType kLineType,
            LocalDate startDate,
            LocalDate endDate,
            int expectedCount,
            int actualCount,
            double completenessRate,
            List<DataGap> gaps,
            List<String> issues,
            boolean isComplete
    ) {
        /**
         * 获取中文摘要
         */
        public String getChineseSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("数据完整性报告 - %s (%s)\n", symbol, kLineType));
            sb.append(String.format("时间范围: %s 至 %s\n", startDate, endDate));
            sb.append(String.format("完整率: %.2f%%\n", completenessRate * 100));
            sb.append(String.format("预期/实际: %d/%d\n", expectedCount, actualCount));
            
            if (!gaps.isEmpty()) {
                sb.append(String.format("数据缺口: %d 处\n", gaps.size()));
            }
            
            if (!issues.isEmpty()) {
                sb.append("问题:\n");
                issues.forEach(issue -> sb.append("  - ").append(issue).append("\n"));
            }
            
            return sb.toString();
        }
    }
    
    /**
     * 数据缺口
     */
    record DataGap(
            LocalDateTime startTime,
            LocalDateTime endTime,
            int missingCount,
            String reason
    ) {}
    
    /**
     * 数据质量报告
     */
    record DataQualityReport(
            String symbol,
            KLineType kLineType,
            int totalRecords,
            int validRecords,
            double qualityScore,
            int priceAnomalies,
            int volumeAnomalies,
            int missingFields,
            Map<String, Integer> issueStats,
            List<DataAnomaly> anomalies,
            String recommendation
    ) {
        /**
         * 获取中文摘要
         */
        public String getChineseSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("数据质量报告 - %s (%s)\n", symbol, kLineType));
            sb.append(String.format("质量评分: %.2f/100\n", qualityScore));
            sb.append(String.format("有效率: %.2f%% (%d/%d)\n", 
                    (double)validRecords/totalRecords * 100, validRecords, totalRecords));
            
            if (priceAnomalies > 0) {
                sb.append(String.format("价格异常: %d 处\n", priceAnomalies));
            }
            if (volumeAnomalies > 0) {
                sb.append(String.format("成交量异常: %d 处\n", volumeAnomalies));
            }
            if (missingFields > 0) {
                sb.append(String.format("字段缺失: %d 处\n", missingFields));
            }
            
            if (recommendation != null && !recommendation.isEmpty()) {
                sb.append("建议: ").append(recommendation).append("\n");
            }
            
            return sb.toString();
        }
    }
    
    /**
     * 数据异常
     */
    record DataAnomaly(
            LocalDateTime timestamp,
            String field,
            String expectedValue,
            String actualValue,
            String anomalyType
    ) {}
    
    /**
     * 修复结果
     */
    record RepairResult(
            String symbol,
            KLineType kLineType,
            int gapsFound,
            int gapsRepaired,
            int recordsAdded,
            boolean success,
            List<String> repairDetails
    ) {}
    
    /**
     * 数据时间范围
     */
    record DataTimeRange(
            String symbol,
            KLineType kLineType,
            LocalDateTime earliest,
            LocalDateTime latest,
            int totalRecords,
            boolean hasData
    ) {}
}