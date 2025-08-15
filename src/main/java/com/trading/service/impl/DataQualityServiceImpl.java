package com.trading.service.impl;

import com.trading.common.enums.MarketType;
import com.trading.common.utils.MarketHoursUtil;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.repository.HistoricalDataRepository;
import com.trading.service.DataQualityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DataQualityServiceImpl implements DataQualityService {

    private final HistoricalDataRepository historicalDataRepository;
    private final MarketHoursUtil marketHoursUtil;

    @Override
    public List<DataAnomaly> detectAnomalies(String symbol, KLineType klineType) {
        log.debug("开始检测异常数据: symbol={}, klineType={}", symbol, klineType);
        List<HistoricalKLineEntity> anomalousData = historicalDataRepository.findAnomalousData(symbol, klineType);

        return anomalousData.stream()
                .map(this::convertToDataAnomaly)
                .collect(Collectors.toList());
    }

    @Override
    public List<DataGap> findDataGaps(String symbol, KLineType klineType, LocalDate startDate, LocalDate endDate) {
        log.debug("开始查找数据缺口 (高级模式): symbol={}, klineType={}, period={}-{}", symbol, klineType, startDate, endDate);

        List<HistoricalKLineEntity> klines = historicalDataRepository.findBySymbolAndTimeRange(
                symbol,
                klineType,
                RehabType.NONE, // 使用不复权数据进行缺口检测
                startDate.atStartOfDay(),
                endDate.atTime(23, 59, 59)
        );

        if (klines == null || klines.size() < 2) {
            return Collections.emptyList();
        }

        List<DataGap> gaps = new ArrayList<>();
        MarketType market = MarketType.fromSymbol(symbol);

        for (int i = 0; i < klines.size() - 1; i++) {
            HistoricalKLineEntity currentKLine = klines.get(i);
            HistoricalKLineEntity actualNextKLine = klines.get(i + 1);

            LocalDateTime expectedNextTimestamp = marketHoursUtil.getNextExpectedTimestamp(
                    currentKLine.getTimestamp(),
                    klineType,
                    market
            );

            // 当数据提供商使用"周期结束"作为时间戳时，跨越非交易时段（午休/隔夜）的K线缺口检测需要特别处理
            // 此处通过判断预期时间与当前时间的间隔是否远大于单个K线周期，来识别是否发生了“跳跃”
            long intervalMinutes = klineType.getIntervalMinutes();
            if (intervalMinutes > 0 && java.time.Duration.between(currentKLine.getTimestamp(), expectedNextTimestamp).toMinutes() > intervalMinutes) {
                // 发生跳跃，说明进入了新的交易时段（如下午或第二天早上）
                // 预期时间戳需要从新时段的开始时间，再往后推一个K线周期，以匹配“周期结束”时间戳
                expectedNextTimestamp = expectedNextTimestamp.plusMinutes(intervalMinutes);
            }

            if (actualNextKLine.getTimestamp().isAfter(expectedNextTimestamp)) {
                gaps.add(DataGap.builder()
                        .gapStart(expectedNextTimestamp)
                        .gapEnd(actualNextKLine.getTimestamp())
                        .missingIntervals(java.time.Duration.between(expectedNextTimestamp, actualNextKLine.getTimestamp()).toMinutes())
                        .build());
            }
        }

        return gaps;
    }

    @Override
    public ConsistencyReport verifyConsistency(String symbol, KLineType klineType, LocalDate startDate, LocalDate endDate) {
        log.debug("开始验证数据一致性: symbol={}, klineType={}, period={}-{}", symbol, klineType, startDate, endDate);

        List<Object[]> duplicates = historicalDataRepository.findDuplicateTimestamps(symbol, klineType, RehabType.NONE);
        long duplicateCount = duplicates.stream().mapToLong(row -> (long) row[1] - 1).sum();

        long totalRecords = historicalDataRepository.countBySymbolAndTimeRange(
                symbol, klineType, RehabType.NONE,
                startDate.atStartOfDay(), endDate.atTime(23, 59, 59)
        );

        // 预期记录数估算 (简化版，未排除节假日)
        long expectedRecords = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (klineType != KLineType.K_DAY) {
            expectedRecords = -1; // 非日K线难以估算
        }

        double completeness = (expectedRecords > 0 && totalRecords > 0) ? (double) totalRecords / expectedRecords * 100 : 0;

        return ConsistencyReport.builder()
                .totalRecords(totalRecords)
                .expectedRecords(expectedRecords)
                .duplicateRecords(duplicateCount)
                .completenessRate(completeness)
                .isConsistent(duplicateCount == 0)
                .summary(String.format("找到 %d 条记录, %d 个重复项。完整度估算: %.2f%%", totalRecords, duplicateCount, completeness))
                .build();
    }

    @Override
    public ComprehensiveQualityReport generateComprehensiveReport(String symbol, KLineType klineType, LocalDate startDate, LocalDate endDate) {
        log.info("开始生成综合数据质量报告: symbol={}, klineType={}, period={}-{}", symbol, klineType, startDate, endDate);

        List<DataAnomaly> anomalies = detectAnomalies(symbol, klineType);
        List<DataGap> gaps = findDataGaps(symbol, klineType, startDate, endDate);
        ConsistencyReport consistency = verifyConsistency(symbol, klineType, startDate, endDate);

        // 综合评分 (简化逻辑)
        int score = 100;
        if (consistency.getDuplicateRecords() > 0) score -= 20;
        score -= anomalies.size() * 5; // 每个异常点扣5分
        score -= gaps.size() * 10; // 每个缺口扣10分
        if (consistency.getCompletenessRate() < 95) score -= 10;

        String summary = String.format("数据质量综合评分: %d。发现 %d 个异常点, %d 个数据缺口, %d 个重复项。",
                Math.max(0, score), anomalies.size(), gaps.size(), consistency.getDuplicateRecords());

        return ComprehensiveQualityReport.builder()
                .symbol(symbol)
                .klineType(klineType)
                .startDate(startDate)
                .endDate(endDate)
                .anomalies(anomalies)
                .gaps(gaps)
                .consistencyReport(consistency)
                .overallQualityScore(Math.max(0, score))
                .summary(summary)
                .build();
    }

    // ==================== 私有辅助方法 ====================

    private DataAnomaly convertToDataAnomaly(HistoricalKLineEntity entity) {
        // 此处可以添加更详细的异常判断逻辑
        return DataAnomaly.builder()
                .timestamp(entity.getTimestamp())
                .anomalyType("规则校验失败")
                .description("高低开收价格或成交量异常")
                .value(String.format("O:%.2f H:%.2f L:%.2f C:%.2f V:%d",
                        entity.getOpen(), entity.getHigh(), entity.getLow(), entity.getClose(), entity.getVolume()))
                .build();
    }

    
}
