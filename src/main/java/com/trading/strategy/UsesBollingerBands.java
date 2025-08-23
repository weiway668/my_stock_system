package com.trading.strategy;

/**
 * A marker interface for strategies that require multiple Bollinger Band parameter sets.
 * The backtest engine uses this interface to determine whether to pre-calculate multiple Bollinger Bands.
 */
public interface UsesBollingerBands {
}
