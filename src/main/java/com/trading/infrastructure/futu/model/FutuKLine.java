package com.trading.infrastructure.futu.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FUTU K线数据模型
 * 对应FUTU OpenAPI的Qot_Common.KLine
 */
@Data
@Builder
public class FutuKLine {
    
    // 股票代码
    private String code;
    
    // K线类型
    private KLineType kLineType;
    
    // 时间戳
    private LocalDateTime timestamp;
    
    // 开盘价
    private BigDecimal open;
    
    // 最高价
    private BigDecimal high;
    
    // 最低价
    private BigDecimal low;
    
    // 收盘价
    private BigDecimal close;
    
    // 成交量
    private Long volume;
    
    // 成交额
    private BigDecimal turnover;
    
    // 涨跌额
    private BigDecimal changeValue;
    
    // 涨跌幅
    private BigDecimal changeRate;
    
    // 换手率
    private BigDecimal turnoverRate;
    
    // 昨收价
    private BigDecimal preClose;
    
    // 复权类型
    private RehabType rehabType;
    
    /**
     * K线类型枚举
     */
    public enum KLineType {
        K_1M("1分钟"),
        K_3M("3分钟"),
        K_5M("5分钟"),
        K_15M("15分钟"),
        K_30M("30分钟"),
        K_60M("60分钟"),
        K_DAY("日K"),
        K_WEEK("周K"),
        K_MON("月K"),
        K_QUARTER("季K"),
        K_YEAR("年K");
        
        private final String desc;
        
        KLineType(String desc) {
            this.desc = desc;
        }
        
        public String getDesc() {
            return desc;
        }
        
        // 转换为FUTU协议值
        public int toFutuValue() {
            return switch (this) {
                case K_1M -> 1;
                case K_3M -> 2;
                case K_5M -> 3;
                case K_15M -> 4;
                case K_30M -> 5;
                case K_60M -> 6;
                case K_DAY -> 7;
                case K_WEEK -> 8;
                case K_MON -> 9;
                case K_QUARTER -> 10;
                case K_YEAR -> 11;
            };
        }
        
        // 从FUTU协议值转换
        public static KLineType fromFutuValue(int value) {
            return switch (value) {
                case 1 -> K_1M;
                case 2 -> K_3M;
                case 3 -> K_5M;
                case 4 -> K_15M;
                case 5 -> K_30M;
                case 6 -> K_60M;
                case 7 -> K_DAY;
                case 8 -> K_WEEK;
                case 9 -> K_MON;
                case 10 -> K_QUARTER;
                case 11 -> K_YEAR;
                default -> K_DAY;
            };
        }
    }
    
    /**
     * 复权类型枚举
     */
    public enum RehabType {
        NONE("不复权"),
        FORWARD("前复权"),
        BACKWARD("后复权");
        
        private final String desc;
        
        RehabType(String desc) {
            this.desc = desc;
        }
        
        public String getDesc() {
            return desc;
        }
        
        // 转换为FUTU协议值
        public int toFutuValue() {
            return switch (this) {
                case NONE -> 0;
                case FORWARD -> 1;
                case BACKWARD -> 2;
            };
        }
        
        // 从FUTU协议值转换
        public static RehabType fromFutuValue(int value) {
            return switch (value) {
                case 0 -> NONE;
                case 1 -> FORWARD;
                case 2 -> BACKWARD;
                default -> NONE;
            };
        }
    }
}