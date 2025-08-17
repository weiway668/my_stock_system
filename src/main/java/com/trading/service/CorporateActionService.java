package com.trading.service;

/**
 * 公司行动ETL服务接口
 */
public interface CorporateActionService {

    /**
     * 处理并保存所有目标股票的复权信息
     */
    void processAndSaveAllCorporateActions();

    /**
     * 处理并保存指定股票的复权信息
     * @param stockCode 股票代码
     */
    void processAndSaveCorporateActionForStock(String stockCode);
}
