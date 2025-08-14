package com.trading.strategy;

import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.entity.Position;
import com.trading.domain.vo.TechnicalIndicators;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 交易策略接口
 * 定义所有交易策略的标准接口
 */
public interface TradingStrategy {
    
    /**
     * 策略初始化
     * @param config 策略配置参数
     */
    void initialize(Map<String, Object> config);
    
    /**
     * 策略名称
     * @return 策略名称
     */
    String getName();
    
    /**
     * 策略版本
     * @return 策略版本
     */
    String getVersion();
    
    /**
     * 策略描述
     * @return 策略描述
     */
    String getDescription();
    
    /**
     * 生成交易信号
     * @param marketData 当前市场数据
     * @param indicators 技术指标
     * @param positions 当前持仓
     * @return 交易信号
     */
    TradingSignal generateSignal(
        MarketData marketData, 
        TechnicalIndicators indicators,
        List<Position> positions
    );
    
    /**
     * 计算仓位大小
     * @param signal 交易信号
     * @param accountBalance 账户余额
     * @param currentPrice 当前价格
     * @return 建议的仓位大小
     */
    int calculatePositionSize(
        TradingSignal signal, 
        BigDecimal accountBalance,
        BigDecimal currentPrice
    );
    
    /**
     * 风险管理
     * @param order 订单
     * @param marketData 市场数据
     * @return 更新后的订单（含止损止盈）
     */
    Order applyRiskManagement(Order order, MarketData marketData);
    
    /**
     * 策略性能评估
     * @param orders 历史订单
     * @return 性能指标
     */
    PerformanceMetrics evaluatePerformance(List<Order> orders);
    
    /**
     * 策略是否启用
     * @return true如果策略启用
     */
    boolean isEnabled();
    
    /**
     * 启用策略
     */
    void enable();
    
    /**
     * 禁用策略
     */
    void disable();
    
    /**
     * 策略参数更新
     * @param parameters 新的参数
     */
    void updateParameters(Map<String, Object> parameters);
    
    /**
     * 交易信号
     */
    class TradingSignal {
        private SignalType type;
        private String symbol;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal confidence;
        private String reason;
        private LocalDateTime timestamp;
        private Map<String, Object> metadata;
        
        public enum SignalType {
            BUY,           // 买入信号
            SELL,          // 卖出信号
            HOLD,          // 持有信号
            CLOSE_LONG,    // 平多仓
            CLOSE_SHORT,   // 平空仓
            NO_ACTION      // 无操作
        }
        
        // Getters and Setters
        public SignalType getType() { return type; }
        public void setType(SignalType type) { this.type = type; }
        
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        
        public BigDecimal getConfidence() { return confidence; }
        public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
        
        // Alias for confidence
        public BigDecimal getStrength() { return confidence; }
        public void setStrength(BigDecimal strength) { this.confidence = strength; }
        
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
        
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
        
        // Builder pattern
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private TradingSignal signal = new TradingSignal();
            
            public Builder type(SignalType type) {
                signal.type = type;
                return this;
            }
            
            public Builder symbol(String symbol) {
                signal.symbol = symbol;
                return this;
            }
            
            public Builder price(BigDecimal price) {
                signal.price = price;
                return this;
            }
            
            public Builder quantity(Integer quantity) {
                signal.quantity = quantity;
                return this;
            }
            
            public Builder confidence(BigDecimal confidence) {
                signal.confidence = confidence;
                return this;
            }
            
            public Builder reason(String reason) {
                signal.reason = reason;
                return this;
            }
            
            public Builder timestamp(LocalDateTime timestamp) {
                signal.timestamp = timestamp;
                return this;
            }
            
            public Builder metadata(Map<String, Object> metadata) {
                signal.metadata = metadata;
                return this;
            }
            
            public TradingSignal build() {
                if (signal.timestamp == null) {
                    signal.timestamp = LocalDateTime.now();
                }
                return signal;
            }
        }
    }
    
    /**
     * 性能指标
     */
    class PerformanceMetrics {
        private BigDecimal totalReturn;
        private BigDecimal sharpeRatio;
        private BigDecimal maxDrawdown;
        private BigDecimal winRate;
        private Integer totalTrades;
        private Integer winningTrades;
        private Integer losingTrades;
        private BigDecimal averageWin;
        private BigDecimal averageLoss;
        private BigDecimal profitFactor;
        private LocalDateTime calculatedAt;
        
        // Getters and Setters
        public BigDecimal getTotalReturn() { return totalReturn; }
        public void setTotalReturn(BigDecimal totalReturn) { this.totalReturn = totalReturn; }
        
        public BigDecimal getSharpeRatio() { return sharpeRatio; }
        public void setSharpeRatio(BigDecimal sharpeRatio) { this.sharpeRatio = sharpeRatio; }
        
        public BigDecimal getMaxDrawdown() { return maxDrawdown; }
        public void setMaxDrawdown(BigDecimal maxDrawdown) { this.maxDrawdown = maxDrawdown; }
        
        public BigDecimal getWinRate() { return winRate; }
        public void setWinRate(BigDecimal winRate) { this.winRate = winRate; }
        
        public Integer getTotalTrades() { return totalTrades; }
        public void setTotalTrades(Integer totalTrades) { this.totalTrades = totalTrades; }
        
        public Integer getWinningTrades() { return winningTrades; }
        public void setWinningTrades(Integer winningTrades) { this.winningTrades = winningTrades; }
        
        public Integer getLosingTrades() { return losingTrades; }
        public void setLosingTrades(Integer losingTrades) { this.losingTrades = losingTrades; }
        
        public BigDecimal getAverageWin() { return averageWin; }
        public void setAverageWin(BigDecimal averageWin) { this.averageWin = averageWin; }
        
        public BigDecimal getAverageLoss() { return averageLoss; }
        public void setAverageLoss(BigDecimal averageLoss) { this.averageLoss = averageLoss; }
        
        public BigDecimal getProfitFactor() { return profitFactor; }
        public void setProfitFactor(BigDecimal profitFactor) { this.profitFactor = profitFactor; }
        
        public LocalDateTime getCalculatedAt() { return calculatedAt; }
        public void setCalculatedAt(LocalDateTime calculatedAt) { this.calculatedAt = calculatedAt; }
    }
}