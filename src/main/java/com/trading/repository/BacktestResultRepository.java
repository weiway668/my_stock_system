package com.trading.repository;

import com.trading.domain.entity.BacktestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 回测结果仓库
 * <p>
 * 提供对 BacktestResultEntity 的数据库操作接口，继承自 JpaRepository 以获得标准的CRUD方法。
 * </p>
 */
@Repository
public interface BacktestResultRepository extends JpaRepository<BacktestResultEntity, String> {

    /**
     * 根据策略名称查找回测结果，并按运行时间降序排序。
     * 这对于比较同一策略在不同时间的表现非常有用。
     *
     * @param strategyName 策略名称
     * @return 符合条件的已排序回测结果列表
     */
    List<BacktestResultEntity> findByStrategyNameOrderByBacktestRunTimeDesc(String strategyName);

}
