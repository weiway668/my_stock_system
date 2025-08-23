package com.trading.domain.vo;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Technical Indicators Value Object
 * Embedded in MarketData entity
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalIndicators {
    
    // Moving Averages
    private BigDecimal ema20;      // 20-period Exponential Moving Average
    private BigDecimal ema50;      // 50-period Exponential Moving Average
    private BigDecimal sma5;       // 5-period Simple Moving Average
    private BigDecimal sma20;      // 20-period Simple Moving Average
    private BigDecimal sma50;      // 50-period Simple Moving Average
    
    // MACD Indicators
    private BigDecimal macdLine;    // MACD line (12-day EMA - 26-day EMA)
    private BigDecimal signalLine;  // Signal line (9-day EMA of MACD)
    private BigDecimal histogram;   // MACD histogram (MACD - Signal)
    
    // Bollinger Bands
    private BigDecimal upperBand;   // Upper Bollinger Band
    private BigDecimal middleBand;  // Middle Band (20-day SMA)
    private BigDecimal lowerBand;   // Lower Bollinger Band
    private BigDecimal bandwidth;   // Band width indicator
    private BigDecimal percentB;    // %B indicator
    
    // Bollinger Band getters with aliases
    public BigDecimal getBollingerUpper() {
        return upperBand;
    }
    
    public BigDecimal getBollingerMiddle() {
        return middleBand;
    }
    
    public BigDecimal getBollingerLower() {
        return lowerBand;
    }
    
    // Momentum Indicators
    private BigDecimal rsi;         // Relative Strength Index
    private BigDecimal stochK;      // Stochastic %K
    private BigDecimal stochD;      // Stochastic %D
    
    // Volatility Indicators
    private BigDecimal atr;         // Average True Range
    private BigDecimal adx;         // Average Directional Index
    private BigDecimal plusDI;      // +DI (Positive Directional Indicator)
    private BigDecimal minusDI;     // -DI (Negative Directional Indicator)
    
    // Volume Indicators
    private BigDecimal volumeRatio; // Volume ratio compared to average
    private BigDecimal vwap;        // Volume Weighted Average Price
    private BigDecimal obv;         // On Balance Volume
    private BigDecimal volumeSma;   // Volume Simple Moving Average
    
    // Market Breadth
    private BigDecimal advanceDeclineRatio; // Advance/Decline ratio
    private BigDecimal mcclellanOscillator; // McClellan Oscillator
    
    // Additional indicators
    private BigDecimal ema12;       // 12-period Exponential Moving Average
    private BigDecimal stochasticK; // Stochastic K (alias for stochK)
    private BigDecimal stochasticD; // Stochastic D (alias for stochD)
    private BigDecimal ema26;       // 26-period Exponential Moving Average
    private BigDecimal williamsR;   // Williams %R
    
    // Additional getters for compatibility
    public BigDecimal getStochK() {
        return stochK != null ? stochK : stochasticK;
    }
    
    public BigDecimal getStochD() {
        return stochD != null ? stochD : stochasticD;
    }
    
    public BigDecimal getEma20() {
        return ema20;
    }
    
    public BigDecimal getBandwidth() {
        return bandwidth;
    }
    
    public BigDecimal getPercentB() {
        return percentB;
    }
    
    public BigDecimal getPlusDI() {
        return plusDI;
    }
    
    public BigDecimal getMinusDI() {
        return minusDI;
    }
    
    public BigDecimal getObv() {
        return obv;
    }
    
    public BigDecimal getVolumeSma() {
        return volumeSma;
    }
    
    public BigDecimal getVolumeRatio() {
        return volumeRatio;
    }
    
    public BigDecimal getVwap() {
        return vwap;
    }
    
    // 新增核心技术指标
    private BigDecimal cci;         // 商品通道指数 (Commodity Channel Index)
    private BigDecimal mfi;         // 资金流量指数 (Money Flow Index)  
    private BigDecimal parabolicSar; // 抛物线SAR (Parabolic Stop and Reverse)
    
    // Pivot Points 轴心点系统
    private BigDecimal pivotPoint;   // 轴心点 PP
    private BigDecimal resistance1;  // 第一阻力位 R1
    private BigDecimal resistance2;  // 第二阻力位 R2
    private BigDecimal resistance3;  // 第三阻力位 R3
    private BigDecimal support1;     // 第一支撑位 S1
    private BigDecimal support2;     // 第二支撑位 S2
    private BigDecimal support3;     // 第三支撑位 S3
    
    private java.time.LocalDateTime calculatedAt; // Timestamp of calculation
    
    // Business methods
    
    /**
     * Check if MACD is bullish (MACD above signal)
     */
    public boolean isMacdBullish() {
        if (macdLine == null || signalLine == null) {
            return false;
        }
        return macdLine.compareTo(signalLine) > 0;
    }
    
    /**
     * Check if price is above upper Bollinger Band
     */
    public boolean isAboveUpperBand(BigDecimal price) {
        if (upperBand == null || price == null) {
            return false;
        }
        return price.compareTo(upperBand) > 0;
    }
    
    /**
     * Check if price is below lower Bollinger Band
     */
    public boolean isBelowLowerBand(BigDecimal price) {
        if (lowerBand == null || price == null) {
            return false;
        }
        return price.compareTo(lowerBand) < 0;
    }
    
    /**
     * Check if RSI is overbought (> 70)
     */
    public boolean isOverbought() {
        if (rsi == null) {
            return false;
        }
        return rsi.compareTo(BigDecimal.valueOf(70)) > 0;
    }
    
    /**
     * Check if RSI is oversold (< 30)
     */
    public boolean isOversold() {
        if (rsi == null) {
            return false;
        }
        return rsi.compareTo(BigDecimal.valueOf(30)) < 0;
    }
    
    /**
     * Check if there's a bullish MACD crossover
     */
    public boolean hasBullishMacdCrossover(TechnicalIndicators previous) {
        if (previous == null || !hasValidMacd() || !previous.hasValidMacd()) {
            return false;
        }
        return previous.macdLine.compareTo(previous.signalLine) <= 0 && 
               this.macdLine.compareTo(this.signalLine) > 0;
    }
    
    /**
     * Check if there's a bearish MACD crossover
     */
    public boolean hasBearishMacdCrossover(TechnicalIndicators previous) {
        if (previous == null || !hasValidMacd() || !previous.hasValidMacd()) {
            return false;
        }
        return previous.macdLine.compareTo(previous.signalLine) >= 0 && 
               this.macdLine.compareTo(this.signalLine) < 0;
    }
    
    /**
     * Check if Bollinger Bands are squeezing
     */
    public boolean isBollingerSqueeze(BigDecimal threshold) {
        if (bandwidth == null || threshold == null) {
            return false;
        }
        return bandwidth.compareTo(threshold) < 0;
    }
    
    /**
     * Get trend strength based on ADX
     */
    public String getTrendStrength() {
        if (adx == null) {
            return "UNKNOWN";
        }
        if (adx.compareTo(BigDecimal.valueOf(50)) > 0) {
            return "VERY_STRONG";
        } else if (adx.compareTo(BigDecimal.valueOf(25)) > 0) {
            return "STRONG";
        } else {
            return "WEAK";
        }
    }
    
    private boolean hasValidMacd() {
        return macdLine != null && signalLine != null;
    }
    
    /**
     * 检查CCI是否超买 (> 100)
     */
    public boolean isCCIOverbought() {
        if (cci == null) {
            return false;
        }
        return cci.compareTo(BigDecimal.valueOf(100)) > 0;
    }
    
    /**
     * 检查CCI是否超卖 (< -100)
     */
    public boolean isCCIOversold() {
        if (cci == null) {
            return false;
        }
        return cci.compareTo(BigDecimal.valueOf(-100)) < 0;
    }
    
    /**
     * 检查MFI是否超买 (> 80)
     */
    public boolean isMFIOverbought() {
        if (mfi == null) {
            return false;
        }
        return mfi.compareTo(BigDecimal.valueOf(80)) > 0;
    }
    
    /**
     * 检查MFI是否超卖 (< 20)
     */
    public boolean isMFIOversold() {
        if (mfi == null) {
            return false;
        }
        return mfi.compareTo(BigDecimal.valueOf(20)) < 0;
    }
    
    /**
     * 检查价格是否在SAR上方（上升趋势）
     */
    public boolean isPriceAboveSAR(BigDecimal price) {
        if (parabolicSar == null || price == null) {
            return false;
        }
        return price.compareTo(parabolicSar) > 0;
    }
    
    /**
     * 检查价格是否在SAR下方（下降趋势）
     */
    public boolean isPriceBelowSAR(BigDecimal price) {
        if (parabolicSar == null || price == null) {
            return false;
        }
        return price.compareTo(parabolicSar) < 0;
    }
    
    /**
     * 获取CCI趋势强度
     */
    public String getCCITrend() {
        if (cci == null) {
            return "UNKNOWN";
        }
        if (cci.compareTo(BigDecimal.valueOf(100)) > 0) {
            return "STRONG_BULLISH";
        } else if (cci.compareTo(BigDecimal.valueOf(0)) > 0) {
            return "BULLISH";
        } else if (cci.compareTo(BigDecimal.valueOf(-100)) > 0) {
            return "BEARISH";
        } else {
            return "STRONG_BEARISH";
        }
    }
    
    /**
     * 获取MFI趋势强度
     */
    public String getMFITrend() {
        if (mfi == null) {
            return "UNKNOWN";
        }
        if (mfi.compareTo(BigDecimal.valueOf(80)) > 0) {
            return "OVERBOUGHT";
        } else if (mfi.compareTo(BigDecimal.valueOf(50)) > 0) {
            return "BULLISH";
        } else if (mfi.compareTo(BigDecimal.valueOf(20)) > 0) {
            return "BEARISH";
        } else {
            return "OVERSOLD";
        }
    }
    
    /**
     * 检查价格是否在轴心点上方
     */
    public boolean isPriceAbovePivot(BigDecimal price) {
        if (pivotPoint == null || price == null) {
            return false;
        }
        return price.compareTo(pivotPoint) > 0;
    }
    
    /**
     * 检查价格是否在轴心点下方
     */
    public boolean isPriceBelowPivot(BigDecimal price) {
        if (pivotPoint == null || price == null) {
            return false;
        }
        return price.compareTo(pivotPoint) < 0;
    }
    
    /**
     * 检查价格是否接近第一阻力位（在容差范围内）
     */
    public boolean isPriceNearResistance1(BigDecimal price, BigDecimal tolerance) {
        if (resistance1 == null || price == null || tolerance == null) {
            return false;
        }
        BigDecimal diff = price.subtract(resistance1).abs();
        return diff.compareTo(tolerance) <= 0;
    }
    
    /**
     * 检查价格是否接近第一支撑位（在容差范围内）
     */
    public boolean isPriceNearSupport1(BigDecimal price, BigDecimal tolerance) {
        if (support1 == null || price == null || tolerance == null) {
            return false;
        }
        BigDecimal diff = price.subtract(support1).abs();
        return diff.compareTo(tolerance) <= 0;
    }
    
    /**
     * 获取价格所在的轴心区间
     */
    public String getPivotLevel(BigDecimal price) {
        if (price == null) {
            return "UNKNOWN";
        }
        
        if (resistance3 != null && price.compareTo(resistance3) >= 0) {
            return "ABOVE_R3";
        } else if (resistance2 != null && price.compareTo(resistance2) >= 0) {
            return "R2_TO_R3";
        } else if (resistance1 != null && price.compareTo(resistance1) >= 0) {
            return "R1_TO_R2";
        } else if (pivotPoint != null && price.compareTo(pivotPoint) >= 0) {
            return "PIVOT_TO_R1";
        } else if (support1 != null && price.compareTo(support1) >= 0) {
            return "S1_TO_PIVOT";
        } else if (support2 != null && price.compareTo(support2) >= 0) {
            return "S2_TO_S1";
        } else if (support3 != null && price.compareTo(support3) >= 0) {
            return "S3_TO_S2";
        } else {
            return "BELOW_S3";
        }
    }
    
    /**
     * 检查价格是否突破阻力位
     */
    public boolean isPriceBreakingResistance(BigDecimal price) {
        if (price == null) {
            return false;
        }
        
        // 检查是否突破任何阻力位
        return (resistance1 != null && price.compareTo(resistance1) > 0) ||
               (resistance2 != null && price.compareTo(resistance2) > 0) ||
               (resistance3 != null && price.compareTo(resistance3) > 0);
    }
    
    /**
     * 检查价格是否跌破支撑位
     */
    public boolean isPriceBreakingSupport(BigDecimal price) {
        if (price == null) {
            return false;
        }
        
        // 检查是否跌破任何支撑位
        return (support1 != null && price.compareTo(support1) < 0) ||
               (support2 != null && price.compareTo(support2) < 0) ||
               (support3 != null && price.compareTo(support3) < 0);
    }
    
    /**
     * 获取最近的支撑位
     */
    public BigDecimal getNearestSupport(BigDecimal price) {
        if (price == null) {
            return null;
        }
        
        // 从高到低检查支撑位
        if (support1 != null && price.compareTo(support1) > 0) {
            return support1;
        } else if (support2 != null && price.compareTo(support2) > 0) {
            return support2;
        } else if (support3 != null && price.compareTo(support3) > 0) {
            return support3;
        }
        
        return null;
    }
    
    /**
     * 获取最近的阻力位
     */
    public BigDecimal getNearestResistance(BigDecimal price) {
        if (price == null) {
            return null;
        }
        
        // 从低到高检查阻力位
        if (resistance1 != null && price.compareTo(resistance1) < 0) {
            return resistance1;
        } else if (resistance2 != null && price.compareTo(resistance2) < 0) {
            return resistance2;
        } else if (resistance3 != null && price.compareTo(resistance3) < 0) {
            return resistance3;
        }
        
        return null;
    }
}