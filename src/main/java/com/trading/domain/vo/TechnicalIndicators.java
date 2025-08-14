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
}