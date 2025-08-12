package com.trading.infrastructure.futu.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FUTU订单数据模型
 * 对应FUTU OpenAPI的Trd_Common.Order
 */
@Data
@Builder
public class FutuOrder {
    
    // 订单ID
    private String orderId;
    
    // 股票代码
    private String code;
    
    // 股票名称
    private String name;
    
    // 交易方向
    private TrdSide trdSide;
    
    // 订单类型
    private OrderType orderType;
    
    // 订单状态
    private OrderStatus orderStatus;
    
    // 订单价格
    private BigDecimal price;
    
    // 订单数量
    private Long quantity;
    
    // 成交价格
    private BigDecimal fillPrice;
    
    // 成交数量
    private Long fillQuantity;
    
    // 成交金额
    private BigDecimal fillAmount;
    
    // 平均成交价
    private BigDecimal avgPrice;
    
    // 创建时间
    private LocalDateTime createTime;
    
    // 更新时间
    private LocalDateTime updateTime;
    
    // 备注
    private String remark;
    
    /**
     * 交易方向枚举
     */
    public enum TrdSide {
        UNKNOWN("未知"),
        BUY("买入"),
        SELL("卖出"),
        SELL_SHORT("卖空"),
        BUY_BACK("买回");
        
        private final String desc;
        
        TrdSide(String desc) {
            this.desc = desc;
        }
        
        public String getDesc() {
            return desc;
        }
        
        // 转换为FUTU协议值
        public int toFutuValue() {
            return switch (this) {
                case UNKNOWN -> 0;
                case BUY -> 1;
                case SELL -> 2;
                case SELL_SHORT -> 3;
                case BUY_BACK -> 4;
            };
        }
        
        // 从FUTU协议值转换
        public static TrdSide fromFutuValue(int value) {
            return switch (value) {
                case 0 -> UNKNOWN;
                case 1 -> BUY;
                case 2 -> SELL;
                case 3 -> SELL_SHORT;
                case 4 -> BUY_BACK;
                default -> UNKNOWN;
            };
        }
    }
    
    /**
     * 订单类型枚举
     */
    public enum OrderType {
        UNKNOWN("未知"),
        NORMAL("普通订单"),
        MARKET("市价单"),
        LIMIT("限价单"),
        ABSOLUTE_LIMIT("绝对限价单"),
        AUCTION("竞价单"),
        AUCTION_LIMIT("竞价限价单"),
        SPECIAL_LIMIT("特别限价单");
        
        private final String desc;
        
        OrderType(String desc) {
            this.desc = desc;
        }
        
        public String getDesc() {
            return desc;
        }
        
        // 转换为FUTU协议值
        public int toFutuValue() {
            return switch (this) {
                case UNKNOWN -> 0;
                case NORMAL -> 1;
                case MARKET -> 2;
                case LIMIT -> 3;
                case ABSOLUTE_LIMIT -> 4;
                case AUCTION -> 5;
                case AUCTION_LIMIT -> 6;
                case SPECIAL_LIMIT -> 7;
            };
        }
        
        // 从FUTU协议值转换
        public static OrderType fromFutuValue(int value) {
            return switch (value) {
                case 0 -> UNKNOWN;
                case 1 -> NORMAL;
                case 2 -> MARKET;
                case 3 -> LIMIT;
                case 4 -> ABSOLUTE_LIMIT;
                case 5 -> AUCTION;
                case 6 -> AUCTION_LIMIT;
                case 7 -> SPECIAL_LIMIT;
                default -> UNKNOWN;
            };
        }
    }
    
    /**
     * 订单状态枚举
     */
    public enum OrderStatus {
        UNKNOWN("未知"),
        SUBMITTED("已提交"),
        WAITING_SUBMIT("等待提交"),
        FILLED_ALL("全部成交"),
        FILLED_PART("部分成交"),
        CANCELLED_ALL("全部取消"),
        CANCELLED_PART("部分取消"),
        FAILED("失败"),
        DISABLED("已失效"),
        DELETED("已删除");
        
        private final String desc;
        
        OrderStatus(String desc) {
            this.desc = desc;
        }
        
        public String getDesc() {
            return desc;
        }
        
        // 转换为FUTU协议值
        public int toFutuValue() {
            return switch (this) {
                case UNKNOWN -> 0;
                case SUBMITTED -> 1;
                case WAITING_SUBMIT -> 2;
                case FILLED_ALL -> 3;
                case FILLED_PART -> 4;
                case CANCELLED_ALL -> 5;
                case CANCELLED_PART -> 6;
                case FAILED -> 7;
                case DISABLED -> 8;
                case DELETED -> 9;
            };
        }
        
        // 从FUTU协议值转换
        public static OrderStatus fromFutuValue(int value) {
            return switch (value) {
                case 0 -> UNKNOWN;
                case 1 -> SUBMITTED;
                case 2 -> WAITING_SUBMIT;
                case 3 -> FILLED_ALL;
                case 4 -> FILLED_PART;
                case 5 -> CANCELLED_ALL;
                case 6 -> CANCELLED_PART;
                case 7 -> FAILED;
                case 8 -> DISABLED;
                case 9 -> DELETED;
                default -> UNKNOWN;
            };
        }
    }
    
    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return orderStatus == OrderStatus.FILLED_ALL || 
               orderStatus == OrderStatus.CANCELLED_ALL ||
               orderStatus == OrderStatus.FAILED ||
               orderStatus == OrderStatus.DELETED;
    }
    
    /**
     * 是否部分成交
     */
    public boolean isPartiallyFilled() {
        return orderStatus == OrderStatus.FILLED_PART ||
               orderStatus == OrderStatus.CANCELLED_PART;
    }
    
    /**
     * 获取未成交数量
     */
    public Long getUnfilledQuantity() {
        if (quantity == null || fillQuantity == null) {
            return quantity;
        }
        return quantity - fillQuantity;
    }
}