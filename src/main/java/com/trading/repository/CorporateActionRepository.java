package com.trading.repository;

import com.trading.domain.entity.CorporateActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 公司行动仓库
 */
@Repository
public interface CorporateActionRepository extends JpaRepository<CorporateActionEntity, Long> {

    /**
     * 根据股票代码查询，并按除权除息日降序排列
     *
     * @param stockCode 股票代码
     * @return 复权因子列表
     */
    List<CorporateActionEntity> findByStockCodeOrderByExDividendDateDesc(String stockCode);

    /**
     * 根据股票代码和日期范围查询
     *
     * @param stockCode 股票代码
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 复权因子列表
     */
    List<CorporateActionEntity> findByStockCodeAndExDividendDateBetween(String stockCode, LocalDate startDate, LocalDate endDate);
}
