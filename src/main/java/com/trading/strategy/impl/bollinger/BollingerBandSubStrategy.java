package com.trading.strategy.impl.bollinger;

import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Position;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.strategy.TradingStrategy;

import java.util.List;
import java.util.Optional;

/**
 * 布林带子策略接口
 * <p>
 * 定义了基于布林带指标生成交易信号的通用协定。
 * 每个实现代表一种特定的交易逻辑（如均值回归、挤压突破等）。
 * </p>
 */
public interface BollingerBandSubStrategy {

    String getName();

    Optional<TradingStrategy.TradingSignal> generateSignal(MarketData marketData,
                                           List<TechnicalIndicators> indicatorHistory,
                                           List<Position> positions);
}