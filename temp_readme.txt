// 新增方法：趋势过滤 - 专业实现
private boolean isTrendFavorable(MarketData marketData, List<TechnicalIndicators> indicatorHistory) {
    if (indicatorHistory.size() < 200) {
        log.debug("历史数据不足200条，无法进行趋势分析");
        return true; // 数据不足时默认通过，避免过度过滤
    }
    
    TechnicalIndicators current = indicatorHistory.get(indicatorHistory.size() - 1);
    
    // 1. 多时间框架趋势确认
    boolean multiTimeframeTrend = checkMultiTimeframeTrend(indicatorHistory);
    
    // 2. 移动平均线排列确认
    boolean maAlignment = checkMAAlignment(current);
    
    // 3. 趋势强度确认 (ADX)
    boolean trendStrength = checkTrendStrength(current);
    
    // 4. 价格与趋势关系确认
    boolean pricePosition = checkPricePosition(marketData, current);
    
    // 综合判断趋势是否有利
    return multiTimeframeTrend && maAlignment && trendStrength && pricePosition;
}

// 多时间框架趋势确认
private boolean checkMultiTimeframeTrend(List<TechnicalIndicators> indicatorHistory) {
    TechnicalIndicators current = indicatorHistory.get(indicatorHistory.size() - 1);
    
    // 获取不同周期的移动平均线
    BigDecimal maShort = current.getMovingAverages().get("ma20");
    BigDecimal maMedium = current.getMovingAverages().get("ma50");
    BigDecimal maLong = current.getMovingAverages().get("ma200");
    
    if (maShort == null || maMedium == null || maLong == null) {
        log.debug("移动平均线数据不全，跳过多时间框架趋势检查");
        return true;
    }
    
    // 多头排列: 短周期 > 中周期 > 长周期
    boolean bullishAlignment = maShort.compareTo(maMedium) > 0 && 
                              maMedium.compareTo(maLong) > 0;
    
    // 空头排列: 短周期 < 中周期 < 长周期
    boolean bearishAlignment = maShort.compareTo(maMedium) < 0 && 
                              maMedium.compareTo(maLong) < 0;
    
    // 当前策略适用于多头市场，所以只接受多头排列
    return bullishAlignment;
}

// 移动平均线斜率确认
private boolean checkMAAlignment(TechnicalIndicators current) {
    // 检查主要移动平均线的斜率（方向）
    // 这里使用EMA的斜率作为趋势方向确认
    
    BigDecimal ema12 = current.getEma().get("ema12");
    BigDecimal ema26 = current.getEma().get("ema26");
    
    if (ema12 == null || ema26 == null) {
        log.debug("EMA数据不全，跳过移动平均线排列检查");
        return true;
    }
    
    // EMA12 > EMA26 且两者都向上倾斜（通过比较当前和前期值）
    // 注意：这里需要访问前期的指标值，实际实现可能需要调整参数传递
    return ema12.compareTo(ema26) > 0;
}

// 趋势强度确认 (使用ADX)
private boolean checkTrendStrength(TechnicalIndicators current) {
    BigDecimal adx = current.getAdx();
    
    if (adx == null) {
        log.debug("ADX数据不可用，跳过趋势强度检查");
        return true;
    }
    
    // ADX > 25表示趋势强劲
    return adx.compareTo(BigDecimal.valueOf(25)) > 0;
}

// 价格与趋势关系确认
private boolean checkPricePosition(MarketData marketData, TechnicalIndicators current) {
    BigDecimal price = marketData.getClose();
    BigDecimal ma200 = current.getMovingAverages().get("ma200");
    
    if (ma200 == null) {
        log.debug("200日均线数据不可用，跳过价格位置检查");
        return true;
    }
    
    // 价格位于200日均线之上，确认处于长期上升趋势中
    return price.compareTo(ma200) > 0;
}