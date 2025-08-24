package com.trading.strategy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.CCIIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.ParabolicSarIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.pivotpoints.PivotLevel;
import org.ta4j.core.indicators.pivotpoints.PivotPointIndicator;
import org.ta4j.core.indicators.pivotpoints.StandardReversalIndicator;
import org.ta4j.core.indicators.pivotpoints.TimeLevel;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.MoneyFlowIndexIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import com.trading.config.BollingerBandConfig;
import com.trading.config.IndicatorParameters;
import com.trading.domain.entity.MarketData;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.infrastructure.cache.CacheService;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.service.MarketDataService;

import cn.hutool.core.date.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private final BollingerBandConfig bollingerBandConfig;

    // 默认参数
    private static final int DEFAULT_RSI_PERIOD = 14;
    private static final int DEFAULT_MACD_SHORT = 12;
    private static final int DEFAULT_MACD_LONG = 26;
    private static final int DEFAULT_MACD_SIGNAL = 9;
    private static final int DEFAULT_BB_PERIOD = 20;
    private static final int DEFAULT_BB_DEVIATION = 2;
    private static final int DEFAULT_SMA_PERIOD = 20;
    private static final int DEFAULT_EMA_PERIOD = 12;
    private static final int DEFAULT_CCI_PERIOD = 20;
    private static final int DEFAULT_MFI_PERIOD = 14;

    /**
     * 使用提供的BarSeries计算技术指标列表
     * 
     * @param series Bar序列
     * @return 技术指标列表
     */
    public List<TechnicalIndicators> calculateIndicators(BarSeries series) {
        if (series == null || series.isEmpty()) {
            return List.of();
        }

        // 计算各种指标
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma5 = new SMAIndicator(closePrice, 5);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, 20));
        StandardDeviationIndicator bbStdDev = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, bbStdDev);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, bbStdDev);
        ADXIndicator adx = new ADXIndicator(series, 14);
        ATRIndicator atr = new ATRIndicator(series, 14);

        java.util.ArrayList<TechnicalIndicators> results = new java.util.ArrayList<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            results.add(TechnicalIndicators.builder()
                    .sma5(toBigDecimal(sma5.getValue(i)))
                    .sma20(toBigDecimal(sma20.getValue(i)))
                    .macdLine(toBigDecimal(macd.getValue(i)))
                    .signalLine(toBigDecimal(macdSignal.getValue(i)))
                    .rsi(toBigDecimal(rsi.getValue(i)))
                    .upperBand(toBigDecimal(bbUpper.getValue(i)))
                    .lowerBand(toBigDecimal(bbLower.getValue(i)))
                    .middleBand(toBigDecimal(bbMiddle.getValue(i)))
                    .adx(toBigDecimal(adx.getValue(i)))
                    .atr(toBigDecimal(atr.getValue(i)))
                    .build());
        }
        return results;
    }

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
                        symbol, timeframe, startTime, endTime, dataPoints, RehabType.FORWARD).get(10, TimeUnit.SECONDS);

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
                        .cci(calculateCCI(series))
                        .mfi(calculateMFI(series))
                        .parabolicSar(calculateParabolicSAR(series))
                        .pivotPoint(calculatePivotPoint(series))
                        .resistance1(calculateResistance1(series))
                        .resistance2(calculateResistance2(series))
                        .resistance3(calculateResistance3(series))
                        .support1(calculateSupport1(series))
                        .support2(calculateSupport2(series))
                        .support3(calculateSupport3(series))
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
        BollingerBandsUpperIndicator upper = new BollingerBandsUpperIndicator(middle, stdDev,
                DecimalNum.valueOf(DEFAULT_BB_DEVIATION));

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
                new SMAIndicator(closePrice, DEFAULT_BB_PERIOD));

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
        BollingerBandsLowerIndicator lower = new BollingerBandsLowerIndicator(middle, stdDev,
                DecimalNum.valueOf(DEFAULT_BB_DEVIATION));

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
     * 计算随机指标K值（带动态参数和智能处理）
     */
    private BigDecimal calculateStochasticK(BarSeries series, String timeframe) {
        IndicatorParameters params = IndicatorParameters.forTimeframe(timeframe);

        if (series.getBarCount() < params.getStochasticKPeriod()) {
            return BigDecimal.valueOf(50);
        }

        StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(
                series, params.getStochasticKPeriod());
        int lastIndex = series.getEndIndex();
        BigDecimal kValue = toBigDecimal(stochK.getValue(lastIndex));

        // 智能处理K值接近0的情况
        if (kValue.compareTo(BigDecimal.valueOf(0.1)) < 0) {
            // 当K值非常接近0时，使用对数平滑
            // 这样可以保留超卖信号，但避免完全为0
            double smoothedValue = Math.log1p(kValue.doubleValue() * 10) / Math.log(11) * 5;
            // 返回范围在0.01-5之间
            kValue = BigDecimal.valueOf(Math.max(params.getStochasticMinValue(), smoothedValue));

            log.debug("Stochastic K值接近0，应用对数平滑: 原值={}, 平滑后={}",
                    stochK.getValue(lastIndex), kValue);
        }

        return kValue;
    }

    /**
     * 计算随机指标K值（使用默认参数）
     */
    private BigDecimal calculateStochasticK(BarSeries series) {
        return calculateStochasticK(series, "1d");
    }

    /**
     * 计算随机指标D值（带动态参数）
     */
    private BigDecimal calculateStochasticD(BarSeries series, String timeframe) {
        IndicatorParameters params = IndicatorParameters.forTimeframe(timeframe);

        if (series.getBarCount() < params.getStochasticKPeriod()) {
            return BigDecimal.valueOf(50);
        }

        StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(
                series, params.getStochasticKPeriod());
        StochasticOscillatorDIndicator stochD = new StochasticOscillatorDIndicator(stochK);

        int lastIndex = series.getEndIndex();
        return toBigDecimal(stochD.getValue(lastIndex));
    }

    /**
     * 计算随机指标D值（使用默认参数）
     */
    private BigDecimal calculateStochasticD(BarSeries series) {
        return calculateStochasticD(series, "1d");
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
     * 计算CCI (Commodity Channel Index)
     */
    private BigDecimal calculateCCI(BarSeries series) {
        if (series.getBarCount() < DEFAULT_CCI_PERIOD) {
            return BigDecimal.ZERO;
        }

        CCIIndicator cci = new CCIIndicator(series, DEFAULT_CCI_PERIOD);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(cci.getValue(lastIndex));
    }

    /**
     * 计算MFI (Money Flow Index)
     */
    private BigDecimal calculateMFI(BarSeries series) {
        if (series.getBarCount() < DEFAULT_MFI_PERIOD) {
            return BigDecimal.valueOf(50); // 中性值
        }

        MoneyFlowIndexIndicator mfi = new MoneyFlowIndexIndicator(series, DEFAULT_MFI_PERIOD);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(mfi.getValue(lastIndex));
    }

    /**
     * 计算Parabolic SAR
     */
    private BigDecimal calculateParabolicSAR(BarSeries series) {
        if (series.getBarCount() < 2) {
            return BigDecimal.ZERO;
        }

        ParabolicSarIndicator sar = new ParabolicSarIndicator(series);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(sar.getValue(lastIndex));
    }

    /**
     * 计算轴心点 (Pivot Point)
     */
    private BigDecimal calculatePivotPoint(BarSeries series) {
        if (series.getBarCount() < 1) {
            return BigDecimal.ZERO;
        }

        PivotPointIndicator pivot = new PivotPointIndicator(series, TimeLevel.DAY);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(pivot.getValue(lastIndex));
    }

    /**
     * 计算第一阻力位 (Resistance 1)
     */
    private BigDecimal calculateResistance1(BarSeries series) {
        if (series.getBarCount() < 1) {
            return BigDecimal.ZERO;
        }

        PivotPointIndicator pivot = new PivotPointIndicator(series, TimeLevel.DAY);
        StandardReversalIndicator r1 = new StandardReversalIndicator(
                pivot, PivotLevel.RESISTANCE_1);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(r1.getValue(lastIndex));
    }

    /**
     * 计算第二阻力位 (Resistance 2)
     */
    private BigDecimal calculateResistance2(BarSeries series) {
        if (series.getBarCount() < 1) {
            return BigDecimal.ZERO;
        }

        PivotPointIndicator pivot = new PivotPointIndicator(series, TimeLevel.DAY);
        StandardReversalIndicator r2 = new StandardReversalIndicator(
                pivot, PivotLevel.RESISTANCE_2);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(r2.getValue(lastIndex));
    }

    /**
     * 计算第三阻力位 (Resistance 3)
     */
    private BigDecimal calculateResistance3(BarSeries series) {
        if (series.getBarCount() < 1) {
            return BigDecimal.ZERO;
        }

        PivotPointIndicator pivot = new PivotPointIndicator(series, TimeLevel.DAY);
        StandardReversalIndicator r3 = new StandardReversalIndicator(
                pivot, PivotLevel.RESISTANCE_3);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(r3.getValue(lastIndex));
    }

    /**
     * 计算第一支撑位 (Support 1)
     */
    private BigDecimal calculateSupport1(BarSeries series) {
        if (series.getBarCount() < 1) {
            return BigDecimal.ZERO;
        }

        PivotPointIndicator pivot = new PivotPointIndicator(series, TimeLevel.DAY);
        StandardReversalIndicator s1 = new StandardReversalIndicator(
                pivot, PivotLevel.SUPPORT_1);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(s1.getValue(lastIndex));
    }

    /**
     * 计算第二支撑位 (Support 2)
     */
    private BigDecimal calculateSupport2(BarSeries series) {
        if (series.getBarCount() < 1) {
            return BigDecimal.ZERO;
        }

        PivotPointIndicator pivot = new PivotPointIndicator(series, TimeLevel.DAY);
        StandardReversalIndicator s2 = new StandardReversalIndicator(
                pivot, PivotLevel.SUPPORT_2);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(s2.getValue(lastIndex));
    }

    /**
     * 计算第三支撑位 (Support 3)
     */
    private BigDecimal calculateSupport3(BarSeries series) {
        if (series.getBarCount() < 1) {
            return BigDecimal.ZERO;
        }

        PivotPointIndicator pivot = new PivotPointIndicator(series, TimeLevel.DAY);
        StandardReversalIndicator s3 = new StandardReversalIndicator(
                pivot, PivotLevel.SUPPORT_3);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(s3.getValue(lastIndex));
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
                .cci(BigDecimal.ZERO)
                .mfi(BigDecimal.valueOf(50)) // MFI中性值
                .parabolicSar(BigDecimal.ZERO)
                .pivotPoint(BigDecimal.ZERO)
                .resistance1(BigDecimal.ZERO)
                .resistance2(BigDecimal.ZERO)
                .resistance3(BigDecimal.ZERO)
                .support1(BigDecimal.ZERO)
                .support2(BigDecimal.ZERO)
                .support3(BigDecimal.ZERO)
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

    /**
     * 计算布林带宽度
     */
    private BigDecimal calculateBollingerBandwidth(BarSeries series) {
        if (series.getBarCount() < DEFAULT_BB_PERIOD) {
            return BigDecimal.ZERO;
        }

        BigDecimal upper = calculateBollingerUpper(series);
        BigDecimal lower = calculateBollingerLower(series);
        BigDecimal middle = calculateBollingerMiddle(series);

        if (middle == null || middle.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // 带宽 = (上轨 - 下轨) / 中轨
        return upper.subtract(lower).divide(middle, 4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 计算布林带%B指标
     */
    private BigDecimal calculateBollingerPercentB(BarSeries series) {
        if (series.getBarCount() < DEFAULT_BB_PERIOD) {
            return BigDecimal.valueOf(0.5); // 中性值
        }

        int lastIndex = series.getEndIndex();
        BigDecimal close = toBigDecimal(series.getBar(lastIndex).getClosePrice());
        BigDecimal upper = calculateBollingerUpper(series);
        BigDecimal lower = calculateBollingerLower(series);

        BigDecimal range = upper.subtract(lower);
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(0.5);
        }

        // %B = (收盘价 - 下轨) / (上轨 - 下轨)
        return close.subtract(lower).divide(range, 4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 计算Williams %R（带动态参数和智能平滑）
     */
    private BigDecimal calculateWilliamsR(BarSeries series, String timeframe) {
        IndicatorParameters params = IndicatorParameters.forTimeframe(timeframe);

        if (series.getBarCount() < params.getWilliamsRPeriod()) {
            return BigDecimal.valueOf(-50); // 中性值
        }

        org.ta4j.core.indicators.WilliamsRIndicator williamsR = new org.ta4j.core.indicators.WilliamsRIndicator(series,
                params.getWilliamsRPeriod());

        int lastIndex = series.getEndIndex();
        BigDecimal wrValue = toBigDecimal(williamsR.getValue(lastIndex));

        // 处理极端值
        if (wrValue.compareTo(BigDecimal.valueOf(-100)) <= 0) {
            // 触底时稍微调整，避免完全-100
            wrValue = BigDecimal.valueOf(-99.99);
        } else if (wrValue.compareTo(BigDecimal.ZERO) >= 0) {
            // 触顶时稍微调整，避免完全为0
            wrValue = BigDecimal.valueOf(-0.01);
        }

        // 如果启用平滑，计算移动平均
        if (params.isWilliamsRSmoothing() && params.getWilliamsRSmoothPeriod() > 1) {
            // 计算最近N期的Williams %R平均值
            int smoothPeriod = Math.min(params.getWilliamsRSmoothPeriod(), series.getBarCount());
            BigDecimal sum = BigDecimal.ZERO;
            int count = 0;

            for (int i = 0; i < smoothPeriod && lastIndex - i >= 0; i++) {
                BigDecimal value = toBigDecimal(williamsR.getValue(lastIndex - i));
                if (value != null) {
                    // 处理历史极端值
                    if (value.compareTo(BigDecimal.valueOf(-100)) <= 0) {
                        value = BigDecimal.valueOf(-99.99);
                    } else if (value.compareTo(BigDecimal.ZERO) >= 0) {
                        value = BigDecimal.valueOf(-0.01);
                    }
                    sum = sum.add(value);
                    count++;
                }
            }

            if (count > 0) {
                wrValue = sum.divide(BigDecimal.valueOf(count), 4, BigDecimal.ROUND_HALF_UP);
            }
        }

        return wrValue;
    }

    /**
     * 计算Williams %R（使用默认参数）
     */
    private BigDecimal calculateWilliamsR(BarSeries series) {
        return calculateWilliamsR(series, "1d");
    }

    /**
     * 计算+DI (正向趋势指标)
     */
    private BigDecimal calculatePlusDI(BarSeries series) {
        if (series.getBarCount() < 14) {
            return BigDecimal.ZERO;
        }

        org.ta4j.core.indicators.adx.PlusDIIndicator plusDI = new org.ta4j.core.indicators.adx.PlusDIIndicator(series,
                14);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(plusDI.getValue(lastIndex));
    }

    /**
     * 计算-DI (负向趋势指标)
     */
    private BigDecimal calculateMinusDI(BarSeries series) {
        if (series.getBarCount() < 14) {
            return BigDecimal.ZERO;
        }

        org.ta4j.core.indicators.adx.MinusDIIndicator minusDI = new org.ta4j.core.indicators.adx.MinusDIIndicator(
                series, 14);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(minusDI.getValue(lastIndex));
    }

    /**
     * 计算VWAP (成交量加权平均价)
     */
    private BigDecimal calculateVWAP(BarSeries series) {
        if (series.getBarCount() < 1) {
            return BigDecimal.ZERO;
        }

        org.ta4j.core.indicators.volume.VWAPIndicator vwap = new org.ta4j.core.indicators.volume.VWAPIndicator(series,
                14);
        int lastIndex = series.getEndIndex();
        return toBigDecimal(vwap.getValue(lastIndex));
    }

    /**
     * 使用提供的历史数据直接计算技术指标
     * 适用于已经有历史数据的场景，避免重复查询数据库
     * 
     * @param symbol         股票代码
     * @param historicalData 历史市场数据（按时间升序排列）
     * @return 技术指标
     */
    public TechnicalIndicators calculateIndicatorsFromData(String symbol, List<MarketData> historicalData) {
        try {
            if (historicalData == null || historicalData.isEmpty()) {
                log.warn("历史数据为空，无法计算技术指标: symbol={}", symbol);
                return createEmptyIndicators();
            }

            int dataSize = historicalData.size();
            LocalDateTime timestamp = historicalData.get(dataSize - 1).getTimestamp();
            log.debug("开始计算技术指标: symbol={}, time={}, 使用{}条历史K线", symbol, DateUtil.formatLocalDateTime(timestamp),
                    dataSize);

            // 检查数据充足性并给出警告
            if (dataSize < DEFAULT_MACD_LONG + DEFAULT_MACD_SIGNAL) { // MACD需要35条
                log.warn("数据可能不足以准确计算MACD：需要{}条，实际{}条",
                        DEFAULT_MACD_LONG + DEFAULT_MACD_SIGNAL, dataSize);
            }
            if (dataSize < DEFAULT_BB_PERIOD) { // 布林带需要20条
                log.warn("数据可能不足以计算布林带：需要{}条，实际{}条",
                        DEFAULT_BB_PERIOD, dataSize);
            }
            if (dataSize < DEFAULT_RSI_PERIOD) { // RSI需要14条
                log.warn("数据可能不足以计算RSI：需要{}条，实际{}条",
                        DEFAULT_RSI_PERIOD, dataSize);
            }

            // 最少需要14条数据来计算基本指标
            if (dataSize < DEFAULT_RSI_PERIOD) {
                log.error("历史数据严重不足，无法计算基本技术指标: symbol={}, dataSize={}",
                        symbol, dataSize);
                return createEmptyIndicators();
            }

            // 构建TA4J序列
            BarSeries series = buildBarSeries(symbol, historicalData);
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

            // --- 新增：计算多套布林带参数 ---
            Map<String, TechnicalIndicators.BollingerBandSet> bollingerBandsMap = new HashMap<>();
            for (BollingerBandConfig.ParameterSet params : bollingerBandConfig.getParameterSets()) {
                if (dataSize >= params.getPeriod()) {
                    SMAIndicator sma = new SMAIndicator(closePrice, params.getPeriod());
                    BollingerBandsMiddleIndicator middleIndicator = new BollingerBandsMiddleIndicator(sma);
                    StandardDeviationIndicator stdDevIndicator = new StandardDeviationIndicator(closePrice, params.getPeriod());
                    Num stdDevMultiplier = DecimalNum.valueOf(params.getStdDev());

                    BollingerBandsUpperIndicator upperIndicator = new BollingerBandsUpperIndicator(middleIndicator, stdDevIndicator, stdDevMultiplier);
                    BollingerBandsLowerIndicator lowerIndicator = new BollingerBandsLowerIndicator(middleIndicator, stdDevIndicator, stdDevMultiplier);

                    BigDecimal upper = toBigDecimal(upperIndicator.getValue(series.getEndIndex()));
                    BigDecimal middle = toBigDecimal(middleIndicator.getValue(series.getEndIndex()));
                    BigDecimal lower = toBigDecimal(lowerIndicator.getValue(series.getEndIndex()));
                    BigDecimal bandwidth = BigDecimal.ZERO;
                    if (middle.compareTo(BigDecimal.ZERO) != 0) {
                        bandwidth = upper.subtract(lower).divide(middle, 4, BigDecimal.ROUND_HALF_UP);
                    }

                    BigDecimal percentB = BigDecimal.valueOf(0.5);
                    BigDecimal range = upper.subtract(lower);
                    if (range.compareTo(BigDecimal.ZERO) != 0) {
                        BigDecimal currentClose = toBigDecimal(series.getBar(series.getEndIndex()).getClosePrice());
                        percentB = currentClose.subtract(lower).divide(range, 4, BigDecimal.ROUND_HALF_UP);
                    }

                    TechnicalIndicators.BollingerBandSet bandSet = TechnicalIndicators.BollingerBandSet.builder()
                            .upperBand(upper)
                            .middleBand(middle)
                            .lowerBand(lower)
                            .bandwidth(bandwidth)
                            .percentB(percentB)
                            .build();
                    bollingerBandsMap.put(params.getKey(), bandSet);
                }
            }
            // --- 结束：计算多套布林带参数 ---

            // 获取时间周期（从第一条数据获取）
            String timeframeStr = "1d";
            if (!historicalData.isEmpty() && historicalData.get(0).getTimeframe() != null) {
                timeframeStr = historicalData.get(0).getTimeframe();
            }

            // 计算各种技术指标（使用动态参数）
            TechnicalIndicators indicators = TechnicalIndicators.builder()
                    .bollingerBands(bollingerBandsMap) // 设置多套布林带
                    .macdLine(calculateMACD(series))
                    .signalLine(calculateMACDSignal(series))
                    .histogram(calculateMACDHistogram(series))
                    .upperBand(calculateBollingerUpper(series)) // 保留默认布林带计算
                    .middleBand(calculateBollingerMiddle(series))
                    .lowerBand(calculateBollingerLower(series))
                    .bandwidth(calculateBollingerBandwidth(series))
                    .percentB(calculateBollingerPercentB(series))
                    .rsi(calculateRSI(series))
                    .sma20(calculateSMA(series, 20))
                    .sma50(calculateSMA(series, 50)) // 新增50日移动平均
                    .ema12(calculateEMA(series, 12))
                    .ema26(calculateEMA(series, 26)) // 新增26日指数移动平均
                    .volumeSma(calculateVolumeSMA(series))
                    .volumeRatio(calculateVolumeRatio(series))
                    .stochK(calculateStochasticK(series, timeframeStr)) // 使用动态参数
                    .stochD(calculateStochasticD(series, timeframeStr)) // 使用动态参数
                    .williamsR(calculateWilliamsR(series, timeframeStr)) // 使用动态参数
                    .atr(calculateATR(series))
                    .adx(calculateADX(series))
                    .plusDI(calculatePlusDI(series)) // 新增+DI
                    .minusDI(calculateMinusDI(series)) // 新增-DI
                    .obv(calculateOBV(series))
                    .vwap(calculateVWAP(series)) // 新增VWAP
                    .cci(calculateCCI(series))
                    .mfi(calculateMFI(series))
                    .parabolicSar(calculateParabolicSAR(series))
                    .pivotPoint(calculatePivotPoint(series))
                    .resistance1(calculateResistance1(series))
                    .resistance2(calculateResistance2(series))
                    .resistance3(calculateResistance3(series))
                    .support1(calculateSupport1(series))
                    .support2(calculateSupport2(series))
                    .support3(calculateSupport3(series))
                    .calculatedAt(LocalDateTime.now())
                    .build();

            log.debug("技术指标计算成功: symbol={}, time={}, dataSize={}, RSI={}, MACD={}",
                    symbol, DateUtil.formatLocalDateTime(timestamp), historicalData.size(), indicators.getRsi(),
                    indicators.getMacdLine());

            return indicators;

        } catch (Exception e) {
            log.error("技术指标计算失败: symbol={}", symbol, e);
            return createEmptyIndicators();
        }
    }
}