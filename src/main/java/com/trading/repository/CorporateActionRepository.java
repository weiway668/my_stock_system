package com.trading.repository;

import com.trading.domain.entity.CorporateActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 公司行动数据仓库
 */
@Repository
public interface CorporateActionRepository extends JpaRepository<CorporateActionEntity, Long> {

    /**
     * 根据股票代码查找所有公司行动
     * @param stockCode 股票代码
     * @return 公司行动列表
     */
    List<CorporateActionEntity> findByStockCodeOrderByExDividendDateDesc(String stockCode);

    /**
     * 根据股票代码删除所有记录
     * @param stockCode 股票代码
     */
    void deleteAllByStockCode(String stockCode);
}