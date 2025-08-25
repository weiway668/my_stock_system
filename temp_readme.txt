增强的卖出信号检测实现
private TradingSignal checkForEnhancedSellSignal(MarketData marketData, 
        TechnicalIndicators currentIndicators, TechnicalIndicators prevIndicators, 
        Position position, List<TechnicalIndicators> indicatorHistory) {
    
    TechnicalIndicators.BollingerBandSet bb = currentIndicators.getBollingerBands().get("default");
    BigDecimal price = marketData.getClose();
    BigDecimal entryPrice = position.getEntryPrice();
    BigDecimal positionGain = price.subtract(entryPrice).divide(entryPrice, 4, RoundingMode.HALF_UP);
    
    log.debug("--- 增强卖出信号检测 {} at {} ---", marketData.getSymbol(), marketData.getTimestamp());
    log.debug("持仓信息: 入场价={}, 当前价={}, 收益率={}%", entryPrice, price, positionGain.multiply(BigDecimal.valueOf(100)));
    
    // 1. 追踪止损检查
    Optional<TradingSignal> trailingStopSignal = checkTrailingStopLoss(position, marketData, currentIndicators);
    if (trailingStopSignal.isPresent()) {
        return trailingStopSignal.get();
    }
    
    // 2. 部分获利了结检查
    Optional<TradingSignal> partialProfitSignal = checkPartialProfitTaking(position, marketData, currentIndicators, indicatorHistory);
    if (partialProfitSignal.isPresent()) {
        return partialProfitSignal.get();
    }
    
    // 3. 动量衰竭检测
    Optional<TradingSignal> momentumExhaustionSignal = checkMomentumExhaustion(marketData, currentIndicators, prevIndicators, indicatorHistory);
    if (momentumExhaustionSignal.isPresent()) {
        return momentumExhaustionSignal.get();
    }
    
    // 4. 原有卖出条件
    return checkForSellSignal(marketData, currentIndicators, prevIndicators, position);
}

// 1. 追踪止损实现
private Optional<TradingSignal> checkTrailingStopLoss(Position position, MarketData marketData, 
        TechnicalIndicators currentIndicators) {
    
    BigDecimal price = marketData.getClose();
    BigDecimal entryPrice = position.getEntryPrice();
    BigDecimal atr = currentIndicators.getAtr();
    
    // 计算动态追踪止损
    BigDecimal trailingStopLevel;
    
    // 方法1: 基于ATR的追踪止损
    if (atr != null && atr.compareTo(BigDecimal.ZERO) > 0) {
        // 使用3倍ATR作为止损距离
        BigDecimal atrDistance = atr.multiply(BigDecimal.valueOf(config.getTrailingStopAtrMultiplier()));
        trailingStopLevel = price.subtract(atrDistance);
        
        log.debug("ATR追踪止损: 当前价={}, ATR={}, 止损水平={}", price, atr, trailingStopLevel);
    } 
    // 方法2: 基于百分比的追踪止损
    else {
        // 从最高点回撤一定百分比
        BigDecimal highestPrice = getPositionHighestPrice(position, marketData.getSymbol());
        BigDecimal percentageDistance = highestPrice.multiply(BigDecimal.valueOf(config.getTrailingStopPercentage()));
        trailingStopLevel = highestPrice.subtract(percentageDistance);
        
        log.debug("百分比追踪止损: 当前价={}, 最高价={}, 止损水平={}", price, highestPrice, trailingStopLevel);
    }
    
    // 检查是否触发止损
    if (price.compareTo(trailingStopLevel) <= 0) {
        String reason = String.format("触发追踪止损: 当前价%s ≤ 止损水平%s", price, trailingStopLevel);
        log.info("{} {}", marketData.getSymbol(), reason);
        return Optional.of(createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, 
                price, 1.0, reason));
    }
    
    return Optional.empty();
}

// 获取持仓期间的最高价
private BigDecimal getPositionHighestPrice(Position position, String symbol) {
    // 这里需要实现获取该持仓期间最高价的逻辑
    // 实际实现可能需要访问价格历史存储或缓存
    // 简化实现: 返回当前价格(实际中应该跟踪持仓期间的最高价)
    return position.getEntryPrice(); // 简化实现
}

// 2. 部分获利了结实现
private Optional<TradingSignal> checkPartialProfitTaking(Position position, MarketData marketData,
        TechnicalIndicators currentIndicators, List<TechnicalIndicators> indicatorHistory) {
    
    BigDecimal price = marketData.getClose();
    BigDecimal entryPrice = position.getEntryPrice();
    BigDecimal gainPercentage = price.subtract(entryPrice).divide(entryPrice, 4, RoundingMode.HALF_UP);
    
    // 检查是否达到预设的获利了结水平
    for (ProfitTakingLevel level : config.getProfitTakingLevels()) {
        if (gainPercentage.compareTo(BigDecimal.valueOf(level.getGainPercentage())) >= 0) {
            // 检查是否已经执行过该级别的获利了结
            if (!hasTakenProfitAtLevel(position, level)) {
                String reason = String.format("部分获利了结: 收益率达到%.1f%%, 卖出%d%%仓位", 
                        gainPercentage.doubleValue() * 100, level.getSellPercentage());
                log.info("{} {}", marketData.getSymbol(), reason);
                
                // 创建部分卖出信号
                TradingSignal signal = createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, 
                        price, level.getSellPercentage() / 100.0, reason);
                
                // 标记该获利级别已执行
                markProfitTaken(position, level);
                return Optional.of(signal);
            }
        }
    }
    
    // 布林带上轨附近部分获利了结
    TechnicalIndicators.BollingerBandSet bb = currentIndicators.getBollingerBands().get("default");
    if (bb != null && bb.getUpperBand() != null) {
        BigDecimal upperBandProximity = price.divide(bb.getUpperBand(), 4, RoundingMode.HALF_UP);
        if (upperBandProximity.compareTo(BigDecimal.valueOf(0.95)) > 0) {
            // 价格接近上轨，考虑部分获利了结
            BigDecimal positionSize = BigDecimal.valueOf(position.getQuantity());
            BigDecimal partialSellSize = positionSize.multiply(BigDecimal.valueOf(0.3)); // 卖出30%
            
            String reason = String.format("布林带上轨附近部分获利了结: 价格接近上轨(%.2f%%)", 
                    upperBandProximity.multiply(BigDecimal.valueOf(100)).doubleValue());
            log.info("{} {}", marketData.getSymbol(), reason);
            
            // 创建部分卖出信号
            TradingSignal signal = createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, 
                    price, 0.3, reason);
            return Optional.of(signal);
        }
    }
    
    return Optional.empty();
}

// 检查是否已在某个获利级别执行了获利了结
private boolean hasTakenProfitAtLevel(Position position, ProfitTakingLevel level) {
    // 实现检查逻辑，可能需要扩展Position对象来记录获利了结历史
    // 简化实现: 总是返回false
    return false;
}

// 标记已在某个获利级别执行了获利了结
private void markProfitTaken(Position position, ProfitTakingLevel level) {
    // 实现标记逻辑，可能需要扩展Position对象来记录获利了结历史
}

// 3. 动量衰竭检测实现
private Optional<TradingSignal> checkMomentumExhaustion(MarketData marketData, 
        TechnicalIndicators currentIndicators, TechnicalIndicators prevIndicators,
        List<TechnicalIndicators> indicatorHistory) {
    
    BigDecimal price = marketData.getClose();
    
    // 3.1 RSI背离检测
    Optional<TradingSignal> rsiDivergenceSignal = checkRSIDivergence(marketData, currentIndicators, indicatorHistory);
    if (rsiDivergenceSignal.isPresent()) {
        return rsiDivergenceSignal;
    }
    
    // 3.2 MACD信号线交叉与背离
    Optional<TradingSignal> macdSignal = checkMACDSignals(currentIndicators, prevIndicators, indicatorHistory);
    if (macdSignal.isPresent()) {
        return macdSignal;
    }
    
    // 3.3 成交量与价格背离
    Optional<TradingSignal> volumeDivergenceSignal = checkVolumePriceDivergence(marketData, currentIndicators, indicatorHistory);
    if (volumeDivergenceSignal.isPresent()) {
        return volumeDivergenceSignal;
    }
    
    // 3.4 波动率扩张后的衰竭
    Optional<TradingSignal> volatilityExhaustionSignal = checkVolatilityExhaustion(currentIndicators, indicatorHistory);
    if (volatilityExhaustionSignal.isPresent()) {
        return volatilityExhaustionSignal;
    }
    
    return Optional.empty();
}

// 3.1 RSI背离检测
private Optional<TradingSignal> checkRSIDivergence(MarketData marketData, 
        TechnicalIndicators currentIndicators, List<TechnicalIndicators> indicatorHistory) {
    
    BigDecimal rsi = currentIndicators.getRsi();
    if (rsi == null) {
        return Optional.empty();
    }
    
    // 获取最近的价格和RSI数据点用于背离分析
    int lookbackPeriod = 10;
    if (indicatorHistory.size() < lookbackPeriod + 5) {
        return Optional.empty();
    }
    
    List<TechnicalIndicators> recentData = indicatorHistory.subList(
            indicatorHistory.size() - lookbackPeriod, indicatorHistory.size());
    
    // 寻找价格高点与RSI高点
    BigDecimal highestPrice = BigDecimal.ZERO;
    BigDecimal highestRSI = BigDecimal.ZERO;
    int highestPriceIndex = -1;
    int highestRSIIndex = -1;
    
    for (int i = 0; i < recentData.size(); i++) {
        TechnicalIndicators data = recentData.get(i);
        if (data.getClose().compareTo(highestPrice) > 0) {
            highestPrice = data.getClose();
            highestPriceIndex = i;
        }
        if (data.getRsi() != null && data.getRsi().compareTo(highestRSI) > 0) {
            highestRSI = data.getRsi();
            highestRSIIndex = i;
        }
    }
    
    // 检测看跌背离: 价格创新高但RSI未创新高
    if (highestPriceIndex == recentData.size() - 1 && 
        highestRSIIndex < recentData.size() - 1 &&
        highestRSI.compareTo(BigDecimal.valueOf(70)) > 0) {
        
        String reason = String.format("RSI看跌背离: 价格创新高%s但RSI未创新高%s", highestPrice, highestRSI);
        log.info("{} {}", marketData.getSymbol(), reason);
        return Optional.of(createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, 
                marketData.getClose(), 0.8, reason));
    }
    
    return Optional.empty();
}

// 3.2 MACD信号检测
private Optional<TradingSignal> checkMACDSignals(TechnicalIndicators currentIndicators, 
        TechnicalIndicators prevIndicators, List<TechnicalIndicators> indicatorHistory) {
    
    BigDecimal macd = currentIndicators.getMacd();
    BigDecimal macdSignal = currentIndicators.getMacdSignal();
    BigDecimal macdHistogram = currentIndicators.getMacdHistogram();
    
    if (macd == null || macdSignal == null || prevIndicators.getMacd() == null) {
        return Optional.empty();
    }
    
    // MACD死叉检测
    boolean bearishCross = prevIndicators.getMacd().compareTo(prevIndicators.getMacdSignal()) > 0 &&
                          macd.compareTo(macdSignal) <= 0;
    
    if (bearishCross && macd.compareTo(BigDecimal.ZERO) > 0) {
        String reason = "MACD死叉信号";
        log.info("{} {}", currentIndicators.getSymbol(), reason);
        return Optional.of(createSignal(currentIndicators.getSymbol(), TradingSignal.SignalType.SELL, 
                currentIndicators.getClose(), 0.7, reason));
    }
    
    // MACD柱状线背离检测
    if (macdHistogram != null && indicatorHistory.size() > 20) {
        // 获取最近几个周期的MACD柱状线
        List<BigDecimal> recentHistograms = new ArrayList<>();
        for (int i = indicatorHistory.size() - 1; i >= Math.max(0, indicatorHistory.size() - 8); i--) {
            if (indicatorHistory.get(i).getMacdHistogram() != null) {
                recentHistograms.add(indicatorHistory.get(i).getMacdHistogram());
            }
        }
        
        // 检测柱状线峰值递减（动量减弱）
        if (recentHistograms.size() >= 3) {
            boolean decreasingPeaks = true;
            for (int i = 1; i < recentHistograms.size(); i++) {
                if (recentHistograms.get(i).compareTo(recentHistograms.get(i-1)) > 0) {
                    decreasingPeaks = false;
                    break;
                }
            }
            
            if (decreasingPeaks && macd.compareTo(BigDecimal.ZERO) > 0) {
                String reason = "MACD柱状线峰值递减，动量衰竭";
                log.info("{} {}", currentIndicators.getSymbol(), reason);
                return Optional.of(createSignal(currentIndicators.getSymbol(), TradingSignal.SignalType.SELL, 
                        currentIndicators.getClose(), 0.6, reason));
            }
        }
    }
    
    return Optional.empty();
}

// 3.3 成交量与价格背离检测
private Optional<TradingSignal> checkVolumePriceDivergence(MarketData marketData, 
        TechnicalIndicators currentIndicators, List<TechnicalIndicators> indicatorHistory) {
    
    Long currentVolume = currentIndicators.getVolume();
    BigDecimal currentPrice = marketData.getClose();
    
    if (currentVolume == null || indicatorHistory.size() < 10) {
        return Optional.empty();
    }
    
    // 获取最近5个周期的价格和成交量
    List<BigDecimal> recentPrices = new ArrayList<>();
    List<Long> recentVolumes = new ArrayList<>();
    
    for (int i = indicatorHistory.size() - 1; i >= Math.max(0, indicatorHistory.size() - 5); i--) {
        TechnicalIndicators data = indicatorHistory.get(i);
        recentPrices.add(data.getClose());
        recentVolumes.add(data.getVolume());
    }
    
    // 检查价格创新高但成交量下降
    if (recentPrices.size() >= 3) {
        BigDecimal highestPrice = Collections.max(recentPrices);
        Long highestVolume = Collections.max(recentVolumes);
        
        boolean priceMakingNewHigh = currentPrice.compareTo(highestPrice) >= 0;
        boolean volumeDecreasing = currentVolume < highestVolume && 
                                  currentVolume < recentVolumes.get(recentVolumes.size() - 2);
        
        if (priceMakingNewHigh && volumeDecreasing) {
            String reason = "量价背离: 价格创新高但成交量下降";
            log.info("{} {}", marketData.getSymbol(), reason);
            return Optional.of(createSignal(marketData.getSymbol(), TradingSignal.SignalType.SELL, 
                    currentPrice, 0.75, reason));
        }
    }
    
    return Optional.empty();
}

// 3.4 波动率扩张后的衰竭检测
private Optional<TradingSignal> checkVolatilityExhaustion(TechnicalIndicators currentIndicators, 
        List<TechnicalIndicators> indicatorHistory) {
    
    TechnicalIndicators.BollingerBandSet bb = currentIndicators.getBollingerBands().get("default");
    if (bb == null) {
        return Optional.empty();
    }
    
    BigDecimal bandwidth = bb.getUpperBand().subtract(bb.getLowerBand())
                            .divide(bb.getMiddleBand(), 4, RoundingMode.HALF_UP);
    
    // 检查带宽是否从极端扩张状态回落
    if (indicatorHistory.size() > 20) {
        // 计算近期带宽的百分位数
        List<BigDecimal> recentBandwidths = new ArrayList<>();
        for (int i = indicatorHistory.size() - 1; i >= Math.max(0, indicatorHistory.size() - 20); i--) {
            TechnicalIndicators data = indicatorHistory.get(i);
            TechnicalIndicators.BollingerBandSet dataBB = data.getBollingerBands().get("default");
            if (dataBB != null) {
                BigDecimal dataBandwidth = dataBB.getUpperBand().subtract(dataBB.getLowerBand())
                                            .divide(dataBB.getMiddleBand(), 4, RoundingMode.HALF_UP);
                recentBandwidths.add(dataBandwidth);
            }
        }
        
        if (!recentBandwidths.isEmpty()) {
            // 计算带宽的80%百分位数
            Collections.sort(recentBandwidths);
            int index = (int) Math.round(recentBandwidths.size() * 0.8) - 1;
            index = Math.max(0, Math.min(index, recentBandwidths.size() - 1));
            BigDecimal bandwidth80Percentile = recentBandwidths.get(index);
            
            // 检查当前带宽是否从极端高位回落
            if (bandwidth.compareTo(bandwidth80Percentile) < 0) {
                // 获取近期最高带宽
                BigDecimal maxBandwidth = Collections.max(recentBandwidths);
                
                // 如果带宽从极端高位回落超过20%，认为是波动率衰竭
                BigDecimal bandwidthDecline = maxBandwidth.subtract(bandwidth).divide(maxBandwidth, 4, RoundingMode.HALF_UP);
                if (bandwidthDecline.compareTo(BigDecimal.valueOf(0.2)) > 0) {
                    String reason = String.format("波动率扩张后衰竭: 带宽从%s回落至%s", maxBandwidth, bandwidth);
                    log.info("{} {}", currentIndicators.getSymbol(), reason);
                    return Optional.of(createSignal(currentIndicators.getSymbol(), TradingSignal.SignalType.SELL, 
                            currentIndicators.getClose(), 0.65, reason));
                }
            }
        }
    }
    
    return Optional.empty();
}


// 增强的配置类
@Data
public class EnhancedBollingerFilterConfig {
    // 追踪止损参数
    private double trailingStopAtrMultiplier = 3.0;
    private double trailingStopPercentage = 0.02; // 2%
    
    // 部分获利了结参数
    private List<ProfitTakingLevel> profitTakingLevels = Arrays.asList(
        new ProfitTakingLevel(0.10, 25), // 10%收益时卖出25%
        new ProfitTakingLevel(0.20, 35), // 20%收益时再卖出35%
        new ProfitTakingLevel(0.30, 40)  // 30%收益时卖出剩余40%
    );
    
    // 动量衰竭检测参数
    private double rsiOverbought = 70;
    private double macdSignalThreshold = 0.001;
    
    @Data
    @AllArgsConstructor
    public static class ProfitTakingLevel {
        private double gainPercentage;
        private int sellPercentage;
    }
}