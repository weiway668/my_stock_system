package com.trading.service.impl;

import com.futu.openapi.pb.QotCommon;
import com.trading.config.TradingProperties;
import com.trading.domain.entity.CorporateActionEntity;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.infrastructure.futu.FutuDataConverter;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.repository.CorporateActionRepository;
import com.trading.service.CorporateActionService;
import com.trading.service.HistoricalDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CorporateActionServiceImpl implements CorporateActionService {

    private final FutuMarketDataService marketDataService;
    private final HistoricalDataService historicalDataService;
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
        log.info("开始处理股票 {} 的复权信息...", stockCode);

        // 1. 从Futu获取原始复权数据列表
        List<QotCommon.Rehab> rawRehabList = marketDataService.getRehab(stockCode);
        if (rawRehabList.isEmpty()) {
            log.info("股票 {} 没有从Futu获取到复权信息。", stockCode);
            return;
        }

        List<CorporateActionEntity> finalActions = new ArrayList<>();

        // 2. 遍历每一条原始复权数据，获取前收盘价并计算正确的复权因子
        for (QotCommon.Rehab rehab : rawRehabList) {
            try {
                LocalDate exDate = LocalDate.parse(rehab.getTime());
                // 为获取前收盘价，查询除权日前5天到前1天的数据
                log.debug("为获取股票{}前收盘价，查询除权日{}前5天到前1天的数据", stockCode, exDate);
                List<HistoricalKLineEntity> klines = historicalDataService.getHistoricalKLine(
                        stockCode, exDate.minusDays(5), exDate.minusDays(1), 
                        FutuMarketDataService.KLineType.K_DAY, FutuKLine.RehabType.NONE);

                // 找到离除权日最近的一个交易日的收盘价
                Optional<HistoricalKLineEntity> preCloseKline = klines.stream()
                        .max(Comparator.comparing(HistoricalKLineEntity::getTimestamp));

                if (preCloseKline.isEmpty()) {
                    log.warn("未能找到股票 {} 在 {} 前的收盘价，无法计算该日的复权因子。", stockCode, exDate);
                    continue;
                }

                double preClose = preCloseKline.get().getClose().doubleValue();

                // 3. 使用获取到的前收盘价进行转换和计算
                List<CorporateActionEntity> calculatedActions = FutuDataConverter.convertToCorporateActionList(rehab, stockCode, preClose);
                finalActions.addAll(calculatedActions);

            } catch (Exception e) {
                log.error("处理单条复权数据时出错: symbol={}, rehabTime={}", stockCode, rehab.getTime(), e);
            }
        }

        if (finalActions.isEmpty()) {
            log.warn("股票 {} 所有复权信息都因故无法处理。", stockCode);
            return;
        }

        log.info("为股票 {} 计算得到 {} 条有效的复权记录，准备存入数据库。", stockCode, finalActions.size());

        // 4. 保证幂等性：先删除旧数据，再插入新数据
        corporateActionRepository.deleteAllByStockCode(stockCode);
        log.debug("已删除股票 {} 的旧复权数据。", stockCode);

        corporateActionRepository.saveAll(finalActions);
        log.info("已成功为股票 {} 存储 {} 条复权记录。", stockCode, finalActions.size());
    }
}
