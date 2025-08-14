package com.trading.repository;

import com.trading.common.enums.MarketType;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.domain.entity.HistoricalKLineEntity.DataStatus;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 历史数据Repository
 * 提供历史K线数据的CRUD操作和复杂查询功能
 */
@Repository
public interface HistoricalDataRepository extends JpaRepository<HistoricalKLineEntity, String> {
    
    // ==================== 基础查询方法 ====================
    
    /**
     * 根据股票代码、K线类型和复权类型查询最新数据时间
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @param rehabType 复权类型
     * @return 最新数据时间
     */
    @Query("SELECT MAX(h.timestamp) FROM HistoricalKLineEntity h " +
           "WHERE h.symbol = :symbol AND h.klineType = :klineType AND h.rehabType = :rehabType " +
           "AND h.dataStatus IN ('DOWNLOADED', 'VALIDATED', 'QUALITY_CHECK_PASSED')")
    Optional<LocalDateTime> findLatestTimestamp(@Param("symbol") String symbol,
                                                @Param("klineType") KLineType klineType,
                                                @Param("rehabType") RehabType rehabType);
    
    /**
     * 根据股票代码、K线类型和复权类型查询最早数据时间
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @param rehabType 复权类型
     * @return 最早数据时间
     */
    @Query("SELECT MIN(h.timestamp) FROM HistoricalKLineEntity h " +
           "WHERE h.symbol = :symbol AND h.klineType = :klineType AND h.rehabType = :rehabType " +
           "AND h.dataStatus IN ('DOWNLOADED', 'VALIDATED', 'QUALITY_CHECK_PASSED')")
    Optional<LocalDateTime> findEarliestTimestamp(@Param("symbol") String symbol,
                                                  @Param("klineType") KLineType klineType,
                                                  @Param("rehabType") RehabType rehabType);
    
    /**
     * 查询指定时间范围内的K线数据
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @param rehabType 复权类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return K线数据列表
     */
    @Query("SELECT h FROM HistoricalKLineEntity h " +
           "WHERE h.symbol = :symbol AND h.klineType = :klineType AND h.rehabType = :rehabType " +
           "AND h.timestamp BETWEEN :startTime AND :endTime " +
           "AND h.dataStatus IN ('DOWNLOADED', 'VALIDATED', 'QUALITY_CHECK_PASSED') " +
           "ORDER BY h.timestamp ASC")
    List<HistoricalKLineEntity> findBySymbolAndTimeRange(@Param("symbol") String symbol,
                                                         @Param("klineType") KLineType klineType,
                                                         @Param("rehabType") RehabType rehabType,
                                                         @Param("startTime") LocalDateTime startTime,
                                                         @Param("endTime") LocalDateTime endTime);
    
    /**
     * 统计指定时间范围内的数据数量
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @param rehabType 复权类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 数据数量
     */
    @Query("SELECT COUNT(h) FROM HistoricalKLineEntity h " +
           "WHERE h.symbol = :symbol AND h.klineType = :klineType AND h.rehabType = :rehabType " +
           "AND h.timestamp BETWEEN :startTime AND :endTime " +
           "AND h.dataStatus IN ('DOWNLOADED', 'VALIDATED', 'QUALITY_CHECK_PASSED')")
    long countBySymbolAndTimeRange(@Param("symbol") String symbol,
                                   @Param("klineType") KLineType klineType,
                                   @Param("rehabType") RehabType rehabType,
                                   @Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime);
    
    // ==================== 数据质量查询 ====================
    
    /**
     * 查询数据质量异常的记录
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @return 异常数据列表
     */
    @Query("SELECT h FROM HistoricalKLineEntity h " +
           "WHERE h.symbol = :symbol AND h.klineType = :klineType " +
           "AND (h.qualityScore IS NULL OR h.qualityScore < 60 " +
           "     OR h.high < h.low " +
           "     OR h.high < h.open OR h.high < h.close " +
           "     OR h.low > h.open OR h.low > h.close " +
           "     OR h.volume < 0) " +
           "ORDER BY h.timestamp ASC")
    List<HistoricalKLineEntity> findAnomalousData(@Param("symbol") String symbol,
                                                  @Param("klineType") KLineType klineType);
    
    /**
     * 统计各种状态的数据数量
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @return 状态统计结果
     */
    @Query("SELECT h.dataStatus, COUNT(h) FROM HistoricalKLineEntity h " +
           "WHERE h.symbol = :symbol AND h.klineType = :klineType " +
           "GROUP BY h.dataStatus")
    List<Object[]> countByStatus(@Param("symbol") String symbol,
                                @Param("klineType") KLineType klineType);
    
    // ==================== 市场和批量查询 ====================
    
    /**
     * 根据市场类型查询所有股票代码
     * 
     * @param market 市场类型
     * @return 股票代码列表
     */
    @Query("SELECT DISTINCT h.symbol FROM HistoricalKLineEntity h WHERE h.market = :market")
    List<String> findDistinctSymbolsByMarket(@Param("market") MarketType market);
    
    /**
     * 根据市场类型和K线类型查询所有股票代码
     * 
     * @param market 市场类型
     * @param klineType K线类型
     * @return 股票代码列表
     */
    @Query("SELECT DISTINCT h.symbol FROM HistoricalKLineEntity h " +
           "WHERE h.market = :market AND h.klineType = :klineType")
    List<String> findDistinctSymbolsByMarketAndKLineType(@Param("market") MarketType market,
                                                         @Param("klineType") KLineType klineType);
    
    /**
     * 查询需要更新的股票（根据最后更新时间）
     * 
     * @param klineType K线类型
     * @param cutoffTime 截止时间（早于此时间的数据需要更新）
     * @return 需要更新的股票代码列表
     */
    @Query("SELECT DISTINCT h.symbol FROM HistoricalKLineEntity h " +
           "WHERE h.klineType = :klineType " +
           "AND h.updatedAt < :cutoffTime " +
           "GROUP BY h.symbol " +
           "HAVING MAX(h.timestamp) < :cutoffTime")
    List<String> findSymbolsNeedingUpdate(@Param("klineType") KLineType klineType,
                                          @Param("cutoffTime") LocalDateTime cutoffTime);
    
    // ==================== 数据缺口检测 ====================
    
    /**
     * 查找数据缺口
     * 通过查询时间序列中的间断来识别缺失的数据
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @param rehabType 复权类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param expectedIntervalMinutes 预期时间间隔（分钟）
     * @return 缺口时间点列表
     */
    @Query(value = "SELECT h1.timestamp as gap_start, " +
                   "       (SELECT MIN(h2.timestamp) FROM historical_kline h2 " +
                   "        WHERE h2.symbol = :symbol AND h2.kline_type = :klineType " +
                   "        AND h2.rehab_type = :rehabType AND h2.timestamp > h1.timestamp " +
                   "        AND h2.data_status IN ('DOWNLOADED', 'VALIDATED', 'QUALITY_CHECK_PASSED')) as gap_end " +
                   "FROM historical_kline h1 " +
                   "WHERE h1.symbol = :symbol AND h1.kline_type = :klineType " +
                   "AND h1.rehab_type = :rehabType " +
                   "AND h1.timestamp BETWEEN :startTime AND :endTime " +
                   "AND h1.data_status IN ('DOWNLOADED', 'VALIDATED', 'QUALITY_CHECK_PASSED') " +
                   "AND NOT EXISTS (SELECT 1 FROM historical_kline h2 " +
                   "               WHERE h2.symbol = h1.symbol AND h2.kline_type = h1.kline_type " +
                   "               AND h2.rehab_type = h1.rehab_type " +
                   "               AND h2.timestamp = DATEADD('MINUTE', :expectedIntervalMinutes, h1.timestamp) " +
                   "               AND h2.data_status IN ('DOWNLOADED', 'VALIDATED', 'QUALITY_CHECK_PASSED')) " +
                   "ORDER BY h1.timestamp", 
           nativeQuery = true)
    List<Object[]> findDataGaps(@Param("symbol") String symbol,
                               @Param("klineType") String klineType,
                               @Param("rehabType") String rehabType,
                               @Param("startTime") LocalDateTime startTime,
                               @Param("endTime") LocalDateTime endTime,
                               @Param("expectedIntervalMinutes") int expectedIntervalMinutes);
    
    // ==================== 批量操作方法 ====================
    
    /**
     * 批量保存数据（性能优化）
     * 
     * @param entities K线实体列表
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO historical_kline " +
                   "(id, symbol, market, kline_type, rehab_type, timestamp, " +
                   "open, high, low, close, volume, turnover, pre_close, " +
                   "change_value, change_rate, turnover_rate, " +
                   "data_source, data_status, download_time, quality_score, data_version, " +
                   "created_at, updated_at) " +
                   "VALUES (:#{#entity.id}, :#{#entity.symbol}, :#{#entity.market}, " +
                   ":#{#entity.klineType}, :#{#entity.rehabType}, :#{#entity.timestamp}, " +
                   ":#{#entity.open}, :#{#entity.high}, :#{#entity.low}, :#{#entity.close}, " +
                   ":#{#entity.volume}, :#{#entity.turnover}, :#{#entity.preClose}, " +
                   ":#{#entity.changeValue}, :#{#entity.changeRate}, :#{#entity.turnoverRate}, " +
                   ":#{#entity.dataSource}, :#{#entity.dataStatus}, :#{#entity.downloadTime}, " +
                   ":#{#entity.qualityScore}, :#{#entity.dataVersion}, " +
                   ":#{#entity.createdAt}, :#{#entity.updatedAt}) " +
                   "ON DUPLICATE KEY UPDATE " +
                   "open = VALUES(open), high = VALUES(high), low = VALUES(low), close = VALUES(close), " +
                   "volume = VALUES(volume), turnover = VALUES(turnover), " +
                   "change_value = VALUES(change_value), change_rate = VALUES(change_rate), " +
                   "turnover_rate = VALUES(turnover_rate), quality_score = VALUES(quality_score), " +
                   "data_version = VALUES(data_version), updated_at = VALUES(updated_at)",
           nativeQuery = true)
    void upsertEntity(@Param("entity") HistoricalKLineEntity entity);
    
    /**
     * 批量更新数据状态
     * 
     * @param ids ID列表
     * @param newStatus 新状态
     */
    @Modifying
    @Transactional
    @Query("UPDATE HistoricalKLineEntity h SET h.dataStatus = :newStatus, h.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE h.id IN :ids")
    void updateDataStatus(@Param("ids") List<String> ids, @Param("newStatus") DataStatus newStatus);
    
    /**
     * 删除过期数据
     * 
     * @param klineType K线类型
     * @param cutoffTime 截止时间（早于此时间的数据将被删除）
     * @return 删除的记录数
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM HistoricalKLineEntity h " +
           "WHERE h.klineType = :klineType AND h.timestamp < :cutoffTime")
    int deleteExpiredData(@Param("klineType") KLineType klineType,
                          @Param("cutoffTime") LocalDateTime cutoffTime);
    
    /**
     * 删除质量不合格的数据
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @param minQualityScore 最低质量分数
     * @return 删除的记录数
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM HistoricalKLineEntity h " +
           "WHERE h.symbol = :symbol AND h.klineType = :klineType " +
           "AND (h.qualityScore IS NULL OR h.qualityScore < :minQualityScore)")
    int deleteLowQualityData(@Param("symbol") String symbol,
                             @Param("klineType") KLineType klineType,
                             @Param("minQualityScore") int minQualityScore);
    
    // ==================== 统计查询 ====================
    
    /**
     * 获取数据存储统计信息
     * 
     * @return 统计信息 [market, kline_type, count]
     */
    @Query("SELECT h.market, h.klineType, COUNT(h) FROM HistoricalKLineEntity h " +
           "GROUP BY h.market, h.klineType " +
           "ORDER BY h.market, h.klineType")
    List<Object[]> getStorageStatistics();
    
    /**
     * 获取数据质量统计
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @return 质量统计信息 [quality_range, count]
     */
    @Query("SELECT " +
           "CASE " +
           "  WHEN h.qualityScore >= 90 THEN 'EXCELLENT' " +
           "  WHEN h.qualityScore >= 80 THEN 'GOOD' " +
           "  WHEN h.qualityScore >= 60 THEN 'FAIR' " +
           "  WHEN h.qualityScore < 60 THEN 'POOR' " +
           "  ELSE 'UNKNOWN' " +
           "END as quality_range, " +
           "COUNT(h) " +
           "FROM HistoricalKLineEntity h " +
           "WHERE h.symbol = :symbol AND h.klineType = :klineType " +
           "GROUP BY quality_range " +
           "ORDER BY quality_range")
    List<Object[]> getQualityStatistics(@Param("symbol") String symbol,
                                        @Param("klineType") KLineType klineType);
    
    /**
     * 检查是否存在重复数据
     * 
     * @param symbol 股票代码
     * @param klineType K线类型
     * @param rehabType 复权类型
     * @return 重复的时间戳列表
     */
    @Query("SELECT h.timestamp, COUNT(h) FROM HistoricalKLineEntity h " +
           "WHERE h.symbol = :symbol AND h.klineType = :klineType AND h.rehabType = :rehabType " +
           "GROUP BY h.timestamp " +
           "HAVING COUNT(h) > 1 " +
           "ORDER BY h.timestamp")
    List<Object[]> findDuplicateTimestamps(@Param("symbol") String symbol,
                                          @Param("klineType") KLineType klineType,
                                          @Param("rehabType") RehabType rehabType);
}