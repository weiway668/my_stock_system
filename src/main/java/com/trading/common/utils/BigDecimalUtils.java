package com.trading.common.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * BigDecimal Utility Class
 * Provides common operations for financial calculations
 */
public final class BigDecimalUtils {
    
    private static final int DEFAULT_SCALE = 2;
    private static final int PERCENT_SCALE = 4;
    private static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;
    
    private BigDecimalUtils() {
        // Prevent instantiation
    }
    
    /**
     * Safe conversion from Double to BigDecimal
     */
    public static BigDecimal valueOf(Double value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value);
    }
    
    /**
     * Safe conversion from String to BigDecimal
     */
    public static BigDecimal valueOf(String value) {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
    
    /**
     * Check if value is positive
     */
    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * Check if value is negative
     */
    public static boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }
    
    /**
     * Check if value is zero
     */
    public static boolean isZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) == 0;
    }
    
    /**
     * Safe addition
     */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        if (a == null) a = BigDecimal.ZERO;
        if (b == null) b = BigDecimal.ZERO;
        return a.add(b);
    }
    
    /**
     * Safe subtraction
     */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        if (a == null) a = BigDecimal.ZERO;
        if (b == null) b = BigDecimal.ZERO;
        return a.subtract(b);
    }
    
    /**
     * Safe multiplication
     */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return BigDecimal.ZERO;
        }
        return a.multiply(b);
    }
    
    /**
     * Safe division with scale
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor, int scale) {
        if (dividend == null || divisor == null || isZero(divisor)) {
            return BigDecimal.ZERO;
        }
        return dividend.divide(divisor, scale, DEFAULT_ROUNDING);
    }
    
    /**
     * Safe division with default scale
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        return divide(dividend, divisor, DEFAULT_SCALE);
    }
    
    /**
     * Calculate percentage
     */
    public static BigDecimal percentage(BigDecimal value, BigDecimal total) {
        if (value == null || total == null || isZero(total)) {
            return BigDecimal.ZERO;
        }
        return value.divide(total, PERCENT_SCALE + 2, DEFAULT_ROUNDING)
                   .multiply(BigDecimal.valueOf(100))
                   .setScale(PERCENT_SCALE, DEFAULT_ROUNDING);
    }
    
    /**
     * Calculate percentage change
     */
    public static BigDecimal percentageChange(BigDecimal newValue, BigDecimal oldValue) {
        if (newValue == null || oldValue == null || isZero(oldValue)) {
            return BigDecimal.ZERO;
        }
        BigDecimal change = newValue.subtract(oldValue);
        return change.divide(oldValue, PERCENT_SCALE + 2, DEFAULT_ROUNDING)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(PERCENT_SCALE, DEFAULT_ROUNDING);
    }
    
    /**
     * Round to specified decimal places
     */
    public static BigDecimal round(BigDecimal value, int scale) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(scale, DEFAULT_ROUNDING);
    }
    
    /**
     * Round to 2 decimal places (money)
     */
    public static BigDecimal roundMoney(BigDecimal value) {
        return round(value, DEFAULT_SCALE);
    }
    
    /**
     * Get minimum value
     */
    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }
    
    /**
     * Get maximum value
     */
    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }
    
    /**
     * Check if value is between min and max (inclusive)
     */
    public static boolean isBetween(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null || min == null || max == null) {
            return false;
        }
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
    
    /**
     * Format as percentage string
     */
    public static String formatPercentage(BigDecimal value) {
        if (value == null) {
            return "0.00%";
        }
        return value.setScale(2, DEFAULT_ROUNDING) + "%";
    }
    
    /**
     * Format as money string
     */
    public static String formatMoney(BigDecimal value, String currency) {
        if (value == null) {
            return currency + " 0.00";
        }
        return currency + " " + value.setScale(2, DEFAULT_ROUNDING);
    }
    
    /**
     * Calculate moving average
     */
    public static BigDecimal average(BigDecimal... values) {
        if (values == null || values.length == 0) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        
        for (BigDecimal value : values) {
            if (value != null) {
                sum = sum.add(value);
                count++;
            }
        }
        
        if (count == 0) {
            return BigDecimal.ZERO;
        }
        
        return sum.divide(BigDecimal.valueOf(count), DEFAULT_SCALE, DEFAULT_ROUNDING);
    }
}