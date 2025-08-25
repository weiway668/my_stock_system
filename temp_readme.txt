// 新增方法：波动率过滤 - 专业实现
private boolean isVolatilityAdequate(List<TechnicalIndicators> indicatorHistory) {
    if (indicatorHistory.size() < 50) {
        log.debug("历史数据不足50条，无法进行波动率分析");
        return true; // 数据不足时默认通过
    }
    
    TechnicalIndicators current = indicatorHistory.get(indicatorHistory.size() - 1);
    
    // 1. ATR绝对波动率过滤
    boolean atrFilter = checkATRVolatility(current);
    
    // 2. 布林带宽度过滤 (已在主逻辑中部分实现，这里增强)
    boolean bbWidthFilter = checkBollingerBandWidth(current);
    
    // 3. 历史波动率比较
    boolean historicalVolFilter = checkHistoricalVolatility(indicatorHistory);
    
    // 4. 波动率趋势确认
    boolean volatilityTrend = checkVolatilityTrend(indicatorHistory);
    
    // 综合判断波动率是否适宜
    return atrFilter && bbWidthFilter && historicalVolFilter && volatilityTrend;
}

// ATR绝对波动率过滤
private boolean checkATRVolatility(TechnicalIndicators current) {
    BigDecimal atr = current.getAtr();
    BigDecimal price = current.getClose();
    
    if (atr == null || price == null || price.compareTo(BigDecimal.ZERO) == 0) {
        log.debug("ATR或价格数据不可用，跳过ATR波动率检查");
        return true;
    }
    
    // 计算ATR百分比 (ATR/Price)
    BigDecimal atrPercentage = atr.divide(price, 4, RoundingMode.HALF_UP);
    
    // ATR百分比在0.5%到5%之间视为有效波动率
    // 可根据不同市场调整这些阈值
    boolean adequateVolatility = atrPercentage.compareTo(BigDecimal.valueOf(0.005)) > 0 && 
                                atrPercentage.compareTo(BigDecimal.valueOf(0.05)) < 0;
    
    log.debug("ATR百分比: {}, 适宜波动率: {}", atrPercentage, adequateVolatility);
    return adequateVolatility;
}

// 布林带宽度过滤
private boolean checkBollingerBandWidth(TechnicalIndicators current) {
    TechnicalIndicators.BollingerBandSet bb = current.getBollingerBands().get("default");
    
    if (bb == null || bb.getUpperBand() == null || 
        bb.getLowerBand() == null || bb.getMiddleBand() == null) {
        log.debug("布林带数据不可用，跳过带宽检查");
        return true;
    }
    
    // 计算布林带宽度百分比
    BigDecimal bandwidth = bb.getUpperBand().subtract(bb.getLowerBand())
                            .divide(bb.getMiddleBand(), 4, RoundingMode.HALF_UP);
    
    // 带宽在1.5%到8%之间视为有效
    boolean adequateBandwidth = bandwidth.compareTo(BigDecimal.valueOf(0.015)) > 0 && 
                               bandwidth.compareTo(BigDecimal.valueOf(0.08)) < 0;
    
    log.debug("布林带宽度百分比: {}, 适宜带宽: {}", bandwidth, adequateBandwidth);
    return adequateBandwidth;
}

// 历史波动率比较
private boolean checkHistoricalVolatility(List<TechnicalIndicators> indicatorHistory) {
    // 计算近期波动率与历史波动率的比值
    int shortPeriod = 20; // 短期窗口
    int longPeriod = 100; // 长期窗口
    
    if (indicatorHistory.size() < longPeriod) {
        log.debug("历史数据不足{}条，无法计算历史波动率比较", longPeriod);
        return true;
    }
    
    // 计算短期和长期波动率
    BigDecimal shortTermVol = calculateHistoricalVolatility(indicatorHistory, shortPeriod);
    BigDecimal longTermVol = calculateHistoricalVolatility(indicatorHistory, longPeriod);
    
    if (shortTermVol == null || longTermVol == null || 
        longTermVol.compareTo(BigDecimal.ZERO) == 0) {
        log.debug("波动率计算失败，跳过历史波动率比较");
        return true;
    }
    
    // 计算波动率比率
    BigDecimal volRatio = shortTermVol.divide(longTermVol, 4, RoundingMode.HALF_UP);
    
    // 短期波动率不低于长期波动率的70%
    boolean adequateRatio = volRatio.compareTo(BigDecimal.valueOf(0.7)) >= 0;
    
    log.debug("短期波动率: {}, 长期波动率: {}, 比率: {}, 适宜比率: {}", 
             shortTermVol, longTermVol, volRatio, adequateRatio);
    return adequateRatio;
}

// 计算历史波动率 (简化实现)
private BigDecimal calculateHistoricalVolatility(List<TechnicalIndicators> history, int period) {
    // 获取最近period个数据点
    int startIndex = Math.max(0, history.size() - period);
    List<TechnicalIndicators> sublist = history.subList(startIndex, history.size());
    
    if (sublist.size() < 2) {
        return null;
    }
    
    // 计算收益率的标准差作为波动率估计
    List<BigDecimal> returns = new ArrayList<>();
    for (int i = 1; i < sublist.size(); i++) {
        BigDecimal prevClose = sublist.get(i-1).getClose();
        BigDecimal currClose = sublist.get(i).getClose();
        
        if (prevClose != null && currClose != null && 
            prevClose.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal returnValue = currClose.subtract(prevClose)
                                        .divide(prevClose, 4, RoundingMode.HALF_UP);
            returns.add(returnValue);
        }
    }
    
    if (returns.isEmpty()) {
        return null;
    }
    
    // 计算收益率平均值
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal r : returns) {
        sum = sum.add(r);
    }
    BigDecimal mean = sum.divide(BigDecimal.valueOf(returns.size()), 4, RoundingMode.HALF_UP);
    
    // 计算方差
    BigDecimal variance = BigDecimal.ZERO;
    for (BigDecimal r : returns) {
        BigDecimal diff = r.subtract(mean);
        variance = variance.add(diff.multiply(diff));
    }
    variance = variance.divide(BigDecimal.valueOf(returns.size()), 4, RoundingMode.HALF_UP);
    
    // 标准差作为波动率估计 (年化处理)
    BigDecimal stdDev = new BigDecimal(Math.sqrt(variance.doubleValue()));
    BigDecimal annualizedVol = stdDev.multiply(BigDecimal.valueOf(Math.sqrt(252))); // 252个交易日
    
    return annualizedVol;
}

// 波动率趋势确认
private boolean checkVolatilityTrend(List<TechnicalIndicators> indicatorHistory) {
    // 检查波动率是否处于上升或稳定状态，避免在波动率收缩末期入场
    
    int shortPeriod = 10;
    int mediumPeriod = 30;
    
    if (indicatorHistory.size() < mediumPeriod) {
        log.debug("历史数据不足{}条，无法计算波动率趋势", mediumPeriod);
        return true;
    }
    
    // 计算短期和中期波动率
    BigDecimal shortTermVol = calculateHistoricalVolatility(
        indicatorHistory.subList(indicatorHistory.size() - shortPeriod, indicatorHistory.size()), 
        shortPeriod);
    
    BigDecimal mediumTermVol = calculateHistoricalVolatility(
        indicatorHistory.subList(indicatorHistory.size() - mediumPeriod, indicatorHistory.size()), 
        mediumPeriod);
    
    if (shortTermVol == null || mediumTermVol == null || 
        mediumTermVol.compareTo(BigDecimal.ZERO) == 0) {
        log.debug("波动率计算失败，跳过波动率趋势检查");
        return true;
    }
    
    // 短期波动率不低于中期波动率的80%
    BigDecimal volRatio = shortTermVol.divide(mediumTermVol, 4, RoundingMode.HALF_UP);
    boolean increasingVolatility = volRatio.compareTo(BigDecimal.valueOf(0.8)) >= 0;
    
    log.debug("短期波动率: {}, 中期波动率: {}, 比率: {}, 波动率趋势向上: {}", 
             shortTermVol, mediumTermVol, volRatio, increasingVolatility);
    return increasingVolatility;
}