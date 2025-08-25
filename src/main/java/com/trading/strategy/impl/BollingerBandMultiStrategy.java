package com.trading.strategy.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.config.BollingerBandConfig;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.strategy.impl.bollinger.BollingerBandSubStrategy;
import com.trading.strategy.impl.bollinger.MeanReversionSubStrategy;
import com.trading.strategy.impl.bollinger.SqueezeBreakoutSubStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component("BOLL")
public class BollingerBandMultiStrategy extends AbstractTradingStrategy {

    private final BollingerBandConfig config;
    private final List<BollingerBandSubStrategy> subStrategies = new ArrayList<>();
    private final ObjectMapper objectMapper;

    public BollingerBandMultiStrategy(BollingerBandConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.name = "布林带复合策略";
        this.version = "1.1";
    }

    @Override
    public List<BollingerBandConfig.ParameterSet> getRequiredBollingerBandSets() {
        // 本策略及其子策略依赖一套标准的"default"布林带参数
        BollingerBandConfig.ParameterSet defaultSet = new BollingerBandConfig.ParameterSet();
        defaultSet.setKey("default");
        defaultSet.setPeriod(20);
        defaultSet.setStdDev(2.0);
        return Collections.singletonList(defaultSet);
    }

    @Override
    public void initialize(Map<String, Object> parameters) {
        if (parameters != null && !parameters.isEmpty()) {
            log.info("发现动态参数，正在覆盖策略默认配置: {}", parameters);
            try {
                JsonNode paramsNode = objectMapper.valueToTree(parameters);
                objectMapper.readerForUpdating(this.config).readValue(paramsNode);
                log.info("动态参数应用后配置: {}", this.config);
            } catch (Exception e) {
                log.error("使用动态参数更新配置失败，将使用默认配置", e);
            }
        }
        super.initialize(parameters);
    }

    @Override
    protected void doInitialize() {
        log.info("执行布林带复合策略初始化逻辑...");
        subStrategies.clear();

        if (config.getMeanReversion().isEnabled()) {
            MeanReversionSubStrategy meanReversion = new MeanReversionSubStrategy(config.getMeanReversion());
            subStrategies.add(meanReversion);
            log.info("已加载子策略: {}", meanReversion.getName());
        }
        if (config.getSqueezeBreakout().isEnabled()) {
            SqueezeBreakoutSubStrategy squeezeBreakout = new SqueezeBreakoutSubStrategy(config.getSqueezeBreakout());
            subStrategies.add(squeezeBreakout);
            log.info("已加载子策略: {}", squeezeBreakout.getName());
        }
        if(!subStrategies.isEmpty()){
            this.enabled = true;
            log.info("布林带复合策略初始化完成，启用状态: {}", this.enabled);
        } else {
            this.enabled = false;
            log.warn("未启用任何子策略，布林带复合策略已禁用");
        }
    }

    @Override
    protected void doDestroy() {
        log.info("销毁布林带复合策略");
        subStrategies.clear();
    }

    @Override
    public TradingSignal generateSignal(MarketData marketData, List<TechnicalIndicators> indicatorHistory, List<Position> positions) {
        if (!this.isEnabled() || subStrategies.isEmpty() || indicatorHistory == null || indicatorHistory.isEmpty()) {
            return createNoActionSignal(marketData.getSymbol(),"策略未启用或无子策略或指标历史为空");
        }

        for (BollingerBandSubStrategy subStrategy : subStrategies) {
            try {
                Optional<TradingSignal> signal = subStrategy.generateSignal(marketData, indicatorHistory, positions);
                if (signal.isPresent() && signal.get().getType() != TradingSignal.SignalType.NO_ACTION) {
                    return signal.get();
                }
            } catch (Exception e) {
                log.error("执行子策略 [{}] 时发生错误: symbol={}, price={}",
                        subStrategy.getName(), marketData.getSymbol(), marketData.getClose(), e);
            }
        }

        return createNoActionSignal(marketData.getSymbol(),"所有布林带子策略均未触发");
    }

    @Override
    public int calculatePositionSize(TradingSignal signal, BigDecimal availableCash, BigDecimal currentPrice) {
        BigDecimal positionSizeRatio = config.getMeanReversion().isEnabled() ?
                config.getMeanReversion().getPositionSizeRatio() :
                new BigDecimal("0.2");

        if (signal.getType() == TradingSignal.SignalType.BUY) {
            BigDecimal maxInvestment = availableCash.multiply(positionSizeRatio);
            int shares = maxInvestment.divide(currentPrice, 0, RoundingMode.DOWN).intValue();
            shares = (shares / 100) * 100;
            return Math.max(shares, 100);
        } else if (signal.getType() == TradingSignal.SignalType.SELL) {
            return Integer.MAX_VALUE;
        }
        return 0;
    }

    @Override
    public Order applyRiskManagement(Order order, MarketData marketData) {
        return order;
    }


}