package com.trading.strategy;

import com.trading.domain.entity.MarketData;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.infrastructure.cache.CacheService;
import com.trading.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.*;
import org.ta4j.core.BaseBarBuilder;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 技术分析服务
 * 使用TA4J库计算各种技术指标
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TechnicalAnalysisService {

    private final MarketDataService marketDataService;
    private final CacheService cacheService;
    
    // 默认参数
    private static final int DEFAULT_RSI_PERIOD = 14;
    private static final int DEFAULT_MACD_SHORT = 12;
    private static final int DEFAULT_MACD_LONG = 26;
    private static final int DEFAULT_MACD_SIGNAL = 9;
    private static final int DEFAULT_BB_PERIOD = 20;
    private static final int DEFAULT_BB_DEVIATION = 2;
    private static final int DEFAULT_SMA_PERIOD = 20;
    private static final int DEFAULT_EMA_PERIOD = 12;
    
    /**
     * 计算技术指标
     */
    public CompletableFuture<TechnicalIndicators> calculateIndicators(
            String symbol, 
            String timeframe, 
            LocalDateTime endTime) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 尝试从缓存获取
                String cacheKey = String.format("indicators:%s:%s:%s", 
                    symbol, timeframe, endTime.toString());
                TechnicalIndicators cached = cacheService.getTechnicalIndicator(
                    symbol, cacheKey, TechnicalIndicators.class);
                if (cached != null) {
                    return cached;
                }
                
                // 获取历史数据（需要更多数据来计算指标）
                int dataPoints = 100; // 获取100个数据点
                LocalDateTime startTime = endTime.minusDays(dataPoints);
                
                List<MarketData> historicalData = marketDataService.getOhlcvData(
                    symbol, timeframe, startTime, endTime, dataPoints
                ).get(10, TimeUnit.SECONDS);
                
                if (historicalData == null || historicalData.size() < 30) {
                    log.warn("历史数据不足，无法计算技术指标: symbol={}", symbol);
                    return createEmptyIndicators();
                }
                
                // 构建TA4J序列
                BarSeries series = buildBarSeries(symbol, historicalData);
                
                // 计算各种技术指标
                TechnicalIndicators indicators = TechnicalIndicators.builder()
                    .macdLine(calculateMACD(series))
                    .signalLine(calculateMACDSignal(series))
                    .histogram(calculateMACDHistogram(series))
                    .upperBand(calculateBollingerUpper(series))
                    .middleBand(calculateBollingerMiddle(series))
                    .lowerBand(calculateBollingerLower(series))
                    .rsi(calculateRSI(series))
                    .sma20(calculateSMA(series, 20))
                    .ema12(calculateEMA(series, 12))
                    .volumeSma(calculateVolumeSMA(series))
                    .volumeRatio(calculateVolumeRatio(series))
                    .stochasticK(calculateStochasticK(series))
                    .stochasticD(calculateStochasticD(series))
                    .atr(calculateATR(series))
                    .adx(calculateADX(series))
                    .obv(calculateOBV(series))
                    .calculatedAt(LocalDateTime.now())
                    .build();
                
                // 缓存结果
                cacheService.cacheTechnicalIndicator(symbol, cacheKey, indicators);
                
                log.debug("技术指标计算完成: symbol={}, RSI={}, MACD={}", 
                    symbol, indicators.getRsi(), indicators.getMacdLine());
                
                return indicators;
                
            } catch (Exception e) {
                log.error("计算技术指标失败: symbol={}", symbol, e);
                return createEmptyIndicators();
            }
        });
    }
    
    /**
     * 构建TA4J Bar序列
     */
    private BarSeries buildBarSeries(String symbol, List<MarketData> dataList) {
        BarSeries series = new BaseBarSeries(symbol);
        
        for (MarketData data : dataList) {
            ZonedDateTime time = data.getTimestamp().atZone(ZoneId.systemDefault());
            
            Bar bar = BaseBar.builder()
                .timePeriod(java.time.Duration.ofMinutes(getTimeframeMinutes(data.getTimeframe())))
                .endTime(time)
                .openPrice(DecimalNum.valueOf(data.getOpen()))
                .highPrice(DecimalNum.valueOf(data.getHigh()))
                .lowPrice(DecimalNum.valueOf(data.getLow()))
                .closePrice(DecimalNum.valueOf(data.getClose()))
                .volume(DecimalNum.valueOf(data.getVolume()))
                .build();
            
            series.addBar(bar);
        }
        
        return series;
    }
    
    /**
     * 计算MACD主线
     */
    private BigDecimal calculateMACD(BarSeries series) {
        if (series.getBarCount() < DEFAULT_MACD_LONG) {
            return BigDecimal.ZERO;
        }
        
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, DEFAULT_MACD_SHORT, DEFAULT_MACD_LONG);
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(macd.getValue(lastIndex));
    }
    
    /**
     * 计算MACD信号线
     */
    private BigDecimal calculateMACDSignal(BarSeries series) {
        if (series.getBarCount() < DEFAULT_MACD_LONG + DEFAULT_MACD_SIGNAL) {
            return BigDecimal.ZERO;
        }
        
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, DEFAULT_MACD_SHORT, DEFAULT_MACD_LONG);
        EMAIndicator signal = new EMAIndicator(macd, DEFAULT_MACD_SIGNAL);
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(signal.getValue(lastIndex));
    }
    
    /**
     * 计算MACD柱状图
     */
    private BigDecimal calculateMACDHistogram(BarSeries series) {
        if (series.getBarCount() < DEFAULT_MACD_LONG + DEFAULT_MACD_SIGNAL) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal macd = calculateMACD(series);
        BigDecimal signal = calculateMACDSignal(series);
        
        return macd.subtract(signal);
    }
    
    /**
     * 计算布林带上轨
     */
    private BigDecimal calculateBollingerUpper(BarSeries series) {
        if (series.getBarCount() < DEFAULT_BB_PERIOD) {
            return BigDecimal.ZERO;
        }
        
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        BollingerBandsMiddleIndicator middle = new BollingerBandsMiddleIndicator(
            new SMAIndicator(closePrice, DEFAULT_BB_PERIOD));
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, DEFAULT_BB_PERIOD);
        BollingerBandsUpperIndicator upper = new BollingerBandsUpperIndicator(middle, stdDev, DecimalNum.valueOf(DEFAULT_BB_DEVIATION));
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(upper.getValue(lastIndex));
    }
    
    /**
     * 计算布林带中轨
     */
    private BigDecimal calculateBollingerMiddle(BarSeries series) {
        if (series.getBarCount() < DEFAULT_BB_PERIOD) {
            return BigDecimal.ZERO;
        }
        
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        BollingerBandsMiddleIndicator middle = new BollingerBandsMiddleIndicator(
            new SMAIndicator(closePrice, DEFAULT_BB_PERIOD)
        );
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(middle.getValue(lastIndex));
    }
    
    /**
     * 计算布林带下轨
     */
    private BigDecimal calculateBollingerLower(BarSeries series) {
        if (series.getBarCount() < DEFAULT_BB_PERIOD) {
            return BigDecimal.ZERO;
        }
        
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        BollingerBandsMiddleIndicator middle = new BollingerBandsMiddleIndicator(
            new SMAIndicator(closePrice, DEFAULT_BB_PERIOD));
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, DEFAULT_BB_PERIOD);
        BollingerBandsLowerIndicator lower = new BollingerBandsLowerIndicator(middle, stdDev, DecimalNum.valueOf(DEFAULT_BB_DEVIATION));
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(lower.getValue(lastIndex));
    }
    
    /**
     * 计算RSI
     */
    private BigDecimal calculateRSI(BarSeries series) {
        if (series.getBarCount() < DEFAULT_RSI_PERIOD) {
            return BigDecimal.valueOf(50); // 中性值
        }
        
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, DEFAULT_RSI_PERIOD);
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(rsi.getValue(lastIndex));
    }
    
    /**
     * 计算SMA
     */
    private BigDecimal calculateSMA(BarSeries series, int period) {
        if (series.getBarCount() < period) {
            return BigDecimal.ZERO;
        }
        
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, period);
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(sma.getValue(lastIndex));
    }
    
    /**
     * 计算EMA
     */
    private BigDecimal calculateEMA(BarSeries series, int period) {
        if (series.getBarCount() < period) {
            return BigDecimal.ZERO;
        }
        
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator ema = new EMAIndicator(closePrice, period);
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(ema.getValue(lastIndex));
    }
    
    /**
     * 计算成交量SMA
     */
    private BigDecimal calculateVolumeSMA(BarSeries series) {
        if (series.getBarCount() < DEFAULT_SMA_PERIOD) {
            return BigDecimal.ZERO;
        }
        
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSMA = new SMAIndicator(volume, DEFAULT_SMA_PERIOD);
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(volumeSMA.getValue(lastIndex));
    }
    
    /**
     * 计算成交量比率
     */
    private BigDecimal calculateVolumeRatio(BarSeries series) {
        if (series.getBarCount() < 2) {
            return BigDecimal.ONE;
        }
        
        int lastIndex = series.getEndIndex();
        Num currentVolume = series.getBar(lastIndex).getVolume();
        Num previousVolume = series.getBar(lastIndex - 1).getVolume();
        
        if (previousVolume.isZero()) {
            return BigDecimal.ONE;
        }
        
        return toBigDecimal(currentVolume.dividedBy(previousVolume));
    }
    
    /**
     * 计算随机指标K值
     */
    private BigDecimal calculateStochasticK(BarSeries series) {
        if (series.getBarCount() < 14) {
            return BigDecimal.valueOf(50);
        }
        
        StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(series, 14);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(stochK.getValue(lastIndex));
    }
    
    /**
     * 计算随机指标D值
     */
    private BigDecimal calculateStochasticD(BarSeries series) {
        if (series.getBarCount() < 14) {
            return BigDecimal.valueOf(50);
        }
        
        StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(series, 14);
        StochasticOscillatorDIndicator stochD = new StochasticOscillatorDIndicator(stochK);
        
        int lastIndex = series.getEndIndex();
        return toBigDecimal(stochD.getValue(lastIndex));
    }
    
    /**
     * 计算ATR (Average True Range)
     */
    private BigDecimal calculateATR(BarSeries series) {
        if (series.getBarCount() < 14) {
            return BigDecimal.ZERO;
        }
        
        ATRIndicator atr = new ATRIndicator(series, 14);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(atr.getValue(lastIndex));
    }
    
    /**
     * 计算ADX (Average Directional Index)
     */
    private BigDecimal calculateADX(BarSeries series) {
        if (series.getBarCount() < 14) {
            return BigDecimal.ZERO;
        }
        
        ADXIndicator adx = new ADXIndicator(series, 14);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(adx.getValue(lastIndex));
    }
    
    /**
     * 计算OBV (On Balance Volume)
     */
    private BigDecimal calculateOBV(BarSeries series) {
        if (series.getBarCount() < 2) {
            return BigDecimal.ZERO;
        }
        
        OnBalanceVolumeIndicator obv = new OnBalanceVolumeIndicator(series);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(obv.getValue(lastIndex));
    }
    
    /**
     * 转换Num到BigDecimal
     */
    private BigDecimal toBigDecimal(Num num) {
        if (num == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(num.doubleValue());
    }
    
    /**
     * 创建空指标对象
     */
    private TechnicalIndicators createEmptyIndicators() {
        return TechnicalIndicators.builder()
            .macdLine(BigDecimal.ZERO)
            .signalLine(BigDecimal.ZERO)
            .histogram(BigDecimal.ZERO)
            .upperBand(BigDecimal.ZERO)
            .middleBand(BigDecimal.ZERO)
            .lowerBand(BigDecimal.ZERO)
            .rsi(BigDecimal.valueOf(50))
            .sma20(BigDecimal.ZERO)
            .ema12(BigDecimal.ZERO)
            .volumeSma(BigDecimal.ZERO)
            .volumeRatio(BigDecimal.ONE)
            .calculatedAt(LocalDateTime.now())
            .build();
    }
    
    /**
     * 获取时间周期的分钟数
     */
    private int getTimeframeMinutes(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> 1;
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "60m", "1h" -> 60;
            case "1d" -> 1440;
            default -> 30;
        };
    }
    
    /**
     * 检查是否超买
     */
    public boolean isOverbought(BigDecimal rsi) {
        return rsi != null && rsi.compareTo(BigDecimal.valueOf(70)) > 0;
    }
    
    /**
     * 检查是否超卖
     */
    public boolean isOversold(BigDecimal rsi) {
        return rsi != null && rsi.compareTo(BigDecimal.valueOf(30)) < 0;
    }
    
    /**
     * 检查MACD金叉
     */
    public boolean isMACDGoldenCross(BigDecimal macdLine, BigDecimal signalLine) {
        return macdLine != null && signalLine != null && 
               macdLine.compareTo(signalLine) > 0;
    }
    
    /**
     * 检查MACD死叉
     */
    public boolean isMACDDeathCross(BigDecimal macdLine, BigDecimal signalLine) {
        return macdLine != null && signalLine != null && 
               macdLine.compareTo(signalLine) < 0;
    }
}