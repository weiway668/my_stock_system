# 港股程序化交易系统 - 核心领域模型

## 3.1 市场数据实体

```java
@Entity
@Table(name = "market_data")
public class MarketData {
    @Id
    private String id; // symbol_timestamp
    private String symbol;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
    private LocalDateTime timestamp;
    
    // 技术指标（嵌入式存储）
    @Embedded
    private TechnicalIndicators indicators;
    
    // 业务方法
    public BigDecimal getPriceChange() {
        return close.subtract(open);
    }
    
    public BigDecimal getChangePercent() {
        return getPriceChange()
            .divide(open, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    }
}
```

## 3.2 交易订单实体

```java
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String orderId;
    
    private String symbol;
    
    @Enumerated(EnumType.STRING)
    private OrderSide side; // BUY, SELL
    
    @Enumerated(EnumType.STRING)
    private OrderType type; // MARKET, LIMIT
    
    private BigDecimal price;
    private Integer quantity;
    
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    
    // 关联信号
    private String signalId;
    private String strategyName;
    
    // 执行信息
    private BigDecimal executedPrice;
    private Integer executedQuantity;
    private BigDecimal commission;
}
```

## 3.3 持仓实体

```java
@Entity
@Table(name = "positions")
public class Position {
    @Id
    private String symbol;
    
    private Integer quantity;
    private BigDecimal avgCost;
    private BigDecimal marketValue;
    private BigDecimal unrealizedPnl;
    private BigDecimal realizedPnl;
    
    private LocalDateTime openTime;
    private LocalDateTime lastUpdateTime;
    
    // 计算方法
    public BigDecimal calculatePnl(BigDecimal currentPrice) {
        return currentPrice.subtract(avgCost)
            .multiply(BigDecimal.valueOf(quantity));
    }
    
    public void addPosition(Integer qty, BigDecimal price) {
        BigDecimal totalCost = avgCost.multiply(BigDecimal.valueOf(quantity))
            .add(price.multiply(BigDecimal.valueOf(qty)));
        quantity += qty;
        avgCost = totalCost.divide(BigDecimal.valueOf(quantity), 
            2, RoundingMode.HALF_UP);
    }
}
```

## 3.4 交易信号实体

```java
@Entity
@Table(name = "trading_signals")
public class TradingSignal {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String signalId;
    
    private String symbol;
    private String strategyName;
    
    @Enumerated(EnumType.STRING)
    private SignalType type; // BUY, SELL, HOLD
    
    private BigDecimal strength; // 0-100
    private BigDecimal suggestedPrice;
    private Integer suggestedQuantity;
    
    // 四层评分详情
    @Embedded
    private SignalScores scores;
    
    private LocalDateTime generateTime;
    private Boolean executed;
    
    @Embedded
    public static class SignalScores {
        private BigDecimal volatilityScore;
        private BigDecimal volumeScore;
        private BigDecimal trendScore;
        private BigDecimal confirmationScore;
        private BigDecimal totalScore;
    }
}
```

## 3.5 值对象设计

```java
// 技术指标值对象
@Embeddable
public class TechnicalIndicators {
    // 移动平均
    private BigDecimal ema20;
    private BigDecimal ema50;
    private BigDecimal sma20;
    
    // MACD
    private BigDecimal macdLine;
    private BigDecimal signalLine;
    private BigDecimal histogram;
    
    // 布林带
    private BigDecimal upperBand;
    private BigDecimal middleBand;
    private BigDecimal lowerBand;
    
    // 其他指标
    private BigDecimal rsi;
    private BigDecimal atr;
    private BigDecimal adx;
    private BigDecimal volumeRatio;
}

// 风险指标值对象
@Embeddable
public class RiskMetrics {
    private BigDecimal maxDrawdown;
    private BigDecimal sharpeRatio;
    private BigDecimal winRate;
    private BigDecimal profitFactor;
    private BigDecimal averageWin;
    private BigDecimal averageLoss;
    private Integer consecutiveLosses;
}
```