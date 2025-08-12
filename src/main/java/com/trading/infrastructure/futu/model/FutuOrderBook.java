package com.trading.infrastructure.futu.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * FUTU订单簿（买卖盘）数据模型
 * 对应FUTU OpenAPI的Qot_Common.OrderBook
 */
@Data
@Builder
public class FutuOrderBook {
    
    // 股票代码
    private String code;
    
    // 买盘列表
    private List<OrderBookEntry> bidList;
    
    // 卖盘列表
    private List<OrderBookEntry> askList;
    
    // 更新时间
    private LocalDateTime timestamp;
    
    /**
     * 订单簿条目
     */
    @Data
    @Builder
    public static class OrderBookEntry {
        // 价格
        private BigDecimal price;
        
        // 数量
        private Long volume;
        
        // 订单数
        private Integer orderCount;
        
        // 经纪商ID（港股）
        private List<Integer> brokerIds;
    }
    
    /**
     * 获取买一价
     */
    public BigDecimal getBidPrice1() {
        if (bidList != null && !bidList.isEmpty()) {
            return bidList.get(0).getPrice();
        }
        return null;
    }
    
    /**
     * 获取买一量
     */
    public Long getBidVolume1() {
        if (bidList != null && !bidList.isEmpty()) {
            return bidList.get(0).getVolume();
        }
        return null;
    }
    
    /**
     * 获取卖一价
     */
    public BigDecimal getAskPrice1() {
        if (askList != null && !askList.isEmpty()) {
            return askList.get(0).getPrice();
        }
        return null;
    }
    
    /**
     * 获取卖一量
     */
    public Long getAskVolume1() {
        if (askList != null && !askList.isEmpty()) {
            return askList.get(0).getVolume();
        }
        return null;
    }
    
    /**
     * 获取价差
     */
    public BigDecimal getSpread() {
        BigDecimal bid = getBidPrice1();
        BigDecimal ask = getAskPrice1();
        if (bid != null && ask != null) {
            return ask.subtract(bid);
        }
        return null;
    }
    
    /**
     * 获取中间价
     */
    public BigDecimal getMidPrice() {
        BigDecimal bid = getBidPrice1();
        BigDecimal ask = getAskPrice1();
        if (bid != null && ask != null) {
            return bid.add(ask).divide(BigDecimal.valueOf(2));
        }
        return null;
    }
    
    /**
     * 获取买盘深度（总量）
     */
    public Long getTotalBidVolume() {
        if (bidList == null) return 0L;
        return bidList.stream()
            .mapToLong(OrderBookEntry::getVolume)
            .sum();
    }
    
    /**
     * 获取卖盘深度（总量）
     */
    public Long getTotalAskVolume() {
        if (askList == null) return 0L;
        return askList.stream()
            .mapToLong(OrderBookEntry::getVolume)
            .sum();
    }
    
    /**
     * 获取买卖盘不平衡度
     * 正值表示买盘强，负值表示卖盘强
     */
    public BigDecimal getImbalance() {
        long bidVol = getTotalBidVolume();
        long askVol = getTotalAskVolume();
        long total = bidVol + askVol;
        
        if (total == 0) return BigDecimal.ZERO;
        
        return BigDecimal.valueOf(bidVol - askVol)
            .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }
}