package com.trading.common.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * BigDecimal计算工具类
 * 提供统一的精度和舍入规则
 */
public final class BigDecimalUtils {

    private static final int DEFAULT_SCALE = 2;
    private static final RoundingMode DEFAULT_ROUNDING_MODE = RoundingMode.HALF_UP;

    /**
     * 私有构造函数，防止实例化
     */
    private BigDecimalUtils() {}

    /**
     * 将BigDecimal格式化为系统默认的精度和舍入模式
     * @param value 需要格式化的BigDecimal
     * @return 格式化后的BigDecimal
     */
    public static BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING_MODE);
        }
        return value.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING_MODE);
    }

    /**
     * 将double转换为BigDecimal并格式化
     * @param value 需要格式化的double
     * @return 格式化后的BigDecimal
     */
    public static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(DEFAULT_SCALE, DEFAULT_ROUNDING_MODE);
    }

    /**
     * 安全地比较两个BigDecimal值是否相等（处理null）
     * @param bd1 第一个值
     * @param bd2 第二个值
     * @return 如果数值相等则返回true
     */
    public static boolean isEqual(BigDecimal bd1, BigDecimal bd2) {
        if (bd1 == null && bd2 == null) {
            return true;
        }
        if (bd1 == null || bd2 == null) {
            return false;
        }
        return bd1.compareTo(bd2) == 0;
    }
}
