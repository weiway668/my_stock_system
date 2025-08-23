package com.trading.domain.vo;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalIndicators {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BollingerBandSet {
        private BigDecimal upperBand;
        private BigDecimal middleBand;
        private BigDecimal lowerBand;
        private BigDecimal bandwidth;
        private BigDecimal percentB;
    }

    @Builder.Default
    @Transient // 告诉JPA不要将此字段映射到数据库
    private Map<String, BollingerBandSet> bollingerBands = new HashMap<>();

    private BigDecimal ema20, ema50, sma5, sma20, sma50;
    private BigDecimal macdLine, signalLine, histogram;
    private BigDecimal upperBand, middleBand, lowerBand, bandwidth, percentB;
    private BigDecimal rsi, stochK, stochD;
    private BigDecimal atr, adx, plusDI, minusDI;
    private BigDecimal volumeRatio, vwap, obv, volumeSma;
    private BigDecimal advanceDeclineRatio, mcclellanOscillator;
    private BigDecimal ema12, stochasticK, stochasticD, ema26, williamsR;
    private BigDecimal cci, mfi, parabolicSar;
    private BigDecimal pivotPoint, resistance1, resistance2, resistance3, support1, support2, support3;
    private java.time.LocalDateTime calculatedAt;

    public BigDecimal getBollingerUpper() { return upperBand; }
    public BigDecimal getBollingerMiddle() { return middleBand; }
    public BigDecimal getBollingerLower() { return lowerBand; }
    public BigDecimal getStochK() { return stochK != null ? stochK : stochasticK; }
    public BigDecimal getStochD() { return stochD != null ? stochD : stochasticD; }

    public boolean isMacdBullish() {
        return macdLine != null && signalLine != null && macdLine.compareTo(signalLine) > 0;
    }

    public boolean isAboveUpperBand(BigDecimal price) {
        return upperBand != null && price != null && price.compareTo(upperBand) > 0;
    }

    public boolean isBelowLowerBand(BigDecimal price) {
        return lowerBand != null && price != null && price.compareTo(lowerBand) < 0;
    }

    public boolean isOverbought() {
        return rsi != null && rsi.compareTo(BigDecimal.valueOf(70)) > 0;
    }

    public boolean isOversold() {
        return rsi != null && rsi.compareTo(BigDecimal.valueOf(30)) < 0;
    }

    private boolean hasValidMacd() {
        return macdLine != null && signalLine != null;
    }

    public boolean hasBullishMacdCrossover(TechnicalIndicators previous) {
        if (previous == null || !hasValidMacd() || !previous.hasValidMacd()) {
            return false;
        }
        return previous.macdLine.compareTo(previous.signalLine) <= 0 && this.macdLine.compareTo(this.signalLine) > 0;
    }

    public boolean hasBearishMacdCrossover(TechnicalIndicators previous) {
        if (previous == null || !hasValidMacd() || !previous.hasValidMacd()) {
            return false;
        }
        return previous.macdLine.compareTo(previous.signalLine) >= 0 && this.macdLine.compareTo(this.signalLine) < 0;
    }

    public boolean isBollingerSqueeze(BigDecimal threshold) {
        return bandwidth != null && threshold != null && bandwidth.compareTo(threshold) < 0;
    }

    public String getTrendStrength() {
        if (adx == null) return "UNKNOWN";
        if (adx.compareTo(BigDecimal.valueOf(50)) > 0) return "VERY_STRONG";
        if (adx.compareTo(BigDecimal.valueOf(25)) > 0) return "STRONG";
        return "WEAK";
    }
}