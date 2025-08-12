package com.trading.infrastructure.futu.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FUTU行情数据模型
 * 对应FUTU OpenAPI的Qot_Common.BasicQot
 */
@Data
@Builder
public class FutuQuote {
    
    // 股票代码
    private String code;
    
    // 股票名称
    private String name;
    
    // 最新价
    private BigDecimal lastPrice;
    
    // 开盘价
    private BigDecimal openPrice;
    
    // 最高价
    private BigDecimal highPrice;
    
    // 最低价
    private BigDecimal lowPrice;
    
    // 收盘价（前收盘）
    private BigDecimal preClosePrice;
    
    // 成交量
    private Long volume;
    
    // 成交额
    private BigDecimal turnover;
    
    // 涨跌额
    private BigDecimal changeValue;
    
    // 涨跌幅
    private BigDecimal changeRate;
    
    // 振幅
    private BigDecimal amplitude;
    
    // 买一价
    private BigDecimal bidPrice;
    
    // 买一量
    private Long bidVolume;
    
    // 卖一价
    private BigDecimal askPrice;
    
    // 卖一量
    private Long askVolume;
    
    // 市盈率
    private BigDecimal pe;
    
    // 市净率
    private BigDecimal pb;
    
    // 总市值
    private BigDecimal marketCap;
    
    // 流通市值
    private BigDecimal floatCap;
    
    // 52周最高
    private BigDecimal high52w;
    
    // 52周最低
    private BigDecimal low52w;
    
    // 每手股数
    private Integer lotSize;
    
    // 停牌状态（true: 停牌）
    private Boolean isSuspended;
    
    // 上市日期
    private LocalDateTime listDate;
    
    // 时间戳
    private LocalDateTime timestamp;
    
    // 数据状态
    @Builder.Default
    private DataStatus status = DataStatus.NORMAL;
    
    /**
     * 数据状态枚举
     */
    public enum DataStatus {
        NORMAL("正常"),
        DELAYED("延迟"),
        NO_DATA("无数据"),
        ERROR("错误");
        
        private final String desc;
        
        DataStatus(String desc) {
            this.desc = desc;
        }
        
        public String getDesc() {
            return desc;
        }
    }
}