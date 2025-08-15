package com.trading.service;

import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据质量服务接口
 * 提供数据异常值检测、缺失值处理和一致性验证等功能
 */
public interface DataQualityService {

    /**
     * 检测指定股票的异常数据点
     * <p>
     * 异常规则包括：
     * - high < low
     * - high < open 或 high < close
     * - low > open 或 low > close
     * - volume < 0
     * - 价格或成交量出现极端离群值 (例如，超过5个标准差)
     * </p>
     *
     * @param symbol 股票代码
     * @param klineType K线类型
     * @return 异常数据点列表
     */
    List<DataAnomaly> detectAnomalies(String symbol, KLineType klineType);

    /**
     * 查找指定时间范围内的数据缺口
     *
     * @param symbol    股票代码
     * @param klineType K线类型
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 数据缺口信息列表
     */
    List<DataGap> findDataGaps(String symbol, KLineType klineType, LocalDate startDate, LocalDate endDate);

    /**
     * 验证数据一致性
     * <p>
     * 验证内容包括：
     * - 检查是否存在重复的时间戳
     * - 统计数据记录总数与预期交易日数是否匹配
     * </p>
     *
     * @param symbol    股票代码
     * @param klineType K线类型
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 数据一致性报告
     */
    ConsistencyReport verifyConsistency(String symbol, KLineType klineType, LocalDate startDate, LocalDate endDate);

    /**
     * 生成一份完整的数据质量报告
     *
     * @param symbol    股票代码
     * @param klineType K线类型
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 综合数据质量报告
     */
    ComprehensiveQualityReport generateComprehensiveReport(String symbol, KLineType klineType, LocalDate startDate, LocalDate endDate);


    // ==================== 数据结构定义 ====================

    /**
     * 数据异常点信息
     */
    @lombok.Data
    @lombok.Builder
    class DataAnomaly {
        private LocalDateTime timestamp;
        private String anomalyType;
        private String description;
        private String value;
    }

    /**
     * 数据缺口信息
     */
    @lombok.Data
    @lombok.Builder
    class DataGap {
        private LocalDateTime gapStart;
        private LocalDateTime gapEnd;
        private long missingIntervals;
    }

    /**
     * 数据一致性报告
     */
    @lombok.Data
    @lombok.Builder
    class ConsistencyReport {
        private long totalRecords;
        private long expectedRecords;
        private long duplicateRecords;
        private double completenessRate;
        private boolean isConsistent;
        private String summary;
    }

    /**
     * 综合数据质量报告
     */
    @lombok.Data
    @lombok.Builder
    class ComprehensiveQualityReport {
        private String symbol;
        private KLineType klineType;
        private LocalDate startDate;
        private LocalDate endDate;
        private List<DataAnomaly> anomalies;
        private List<DataGap> gaps;
        private ConsistencyReport consistencyReport;
        private int overallQualityScore; // 综合质量评分 (0-100)
        private String summary;
    }
}
