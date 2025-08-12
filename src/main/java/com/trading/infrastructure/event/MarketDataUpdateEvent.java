package com.trading.infrastructure.event;

import com.trading.domain.entity.MarketData;
import lombok.Getter;

/**
 * Event fired when market data is updated
 * Used for non-critical path processing like analytics and logging
 */
@Getter
public class MarketDataUpdateEvent extends TradingApplicationEvent {
    
    private final MarketData marketData;
    private final String symbol;
    
    public MarketDataUpdateEvent(Object source, MarketData marketData) {
        super(source, "MARKET_DATA_UPDATE");
        this.marketData = marketData;
        this.symbol = marketData.getSymbol();
    }
}