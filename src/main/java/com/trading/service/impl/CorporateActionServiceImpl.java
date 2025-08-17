package com.trading.service.impl;

import com.trading.config.TradingProperties;
import com.trading.domain.entity.CorporateActionEntity;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.repository.CorporateActionRepository;
import com.trading.service.CorporateActionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CorporateActionServiceImpl implements CorporateActionService {

    private final FutuMarketDataService marketDataService;
    private final CorporateActionRepository corporateActionRepository;
    private final TradingProperties tradingProperties;

    @Override
    public void processAndSaveAllCorporateActions() {
        List<String> stockCodes = tradingProperties.getTargets().stream()
                .map(TradingProperties.Target::getSymbol)
                .toList();

        log.info("开始处理 {} 个股票的复权信息...", stockCodes.size());

        for (String stockCode : stockCodes) {
            try {
                processAndSaveCorporateActionForStock(stockCode);
                // 为避免API调用过于频繁，增加短暂延时
                Thread.sleep(500); // 500ms
            } catch (InterruptedException e) {
                log.error("处理复权信息时线程被中断", e);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("处理股票 {} 的复权信息时发生未知错误", stockCode, e);
            }
        }
        log.info("所有股票的复权信息处理完毕。");
    }

    @Override
    @Transactional
    public void processAndSaveCorporateActionForStock(String stockCode) {
        log.info("正在获取股票 {} 的复权信息...", stockCode);
        List<CorporateActionEntity> actions = marketDataService.getRehab(stockCode);

        if (actions == null || actions.isEmpty()) {
            log.info("股票 {} 没有获取到复权信息。", stockCode);
            return;
        }

        log.info("获取到 {} 条关于股票 {} 的复权记录，准备存入数据库。", actions.size(), stockCode);

        // 保证幂等性：先删除旧数据，再插入新数据
        corporateActionRepository.deleteAllByStockCode(stockCode);
        log.debug("已删除股票 {} 的旧复权数据。", stockCode);

        corporateActionRepository.saveAll(actions);
        log.info("已成功为股票 {} 存储 {} 条复权记录。", stockCode, actions.size());
    }
}
