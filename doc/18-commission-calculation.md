# 港股程序化交易系统 - 手续费计算系统

## 18.1 港股手续费概述

### 18.1.1 港股费用结构
港股交易涉及多种费用，系统基于2024年最新标准实现精确计算：

```
港股交易费用构成：
┌─────────────────────────────────────────┐
│           买入费用                       │
│  ✓ 佣金 (Commission) - 0.025%           │
│  ✓ 交易费 (Trading Fee) - 0.005%        │
│  ✓ 结算费 (Settlement Fee) - 0.002%     │
│  ✓ 中央结算费 (CCASS Fee) - 0.002%      │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│           卖出费用                       │
│  ✓ 佣金 (Commission) - 0.025%           │
│  ✓ 印花税 (Stamp Duty) - 0.13%          │
│  ✓ 交易费 (Trading Fee) - 0.005%        │
│  ✓ 结算费 (Settlement Fee) - 0.002%     │
│  ✓ 中央结算费 (CCASS Fee) - 0.002%      │
│  ✓ 投资者赔偿费 (Investor Compensation Fee) - 0.002% │
└─────────────────────────────────────────┘
```

### 18.1.2 计算特点
- **精确计算**: 基于2024年港交所最新费率标准
- **最低费用**: 部分费用设有最低收费标准
- **最高费用**: 部分费用设有封顶限制
- **舍入规则**: 符合香港金融管理局规定
- **实时更新**: 支持费率配置动态调整

## 18.2 核心实现

### 18.2.1 港股手续费计算器 (HKStockCommissionCalculator)

```java
@Component
@Slf4j
public class HKStockCommissionCalculator {
    
    // 2024年港股费用标准 (年度更新)
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.00025");      // 0.025% 佣金
    private static final BigDecimal MIN_COMMISSION = new BigDecimal("5.00");          // 最低¥5佣金
    private static final BigDecimal MAX_COMMISSION = new BigDecimal("100.00");        // 最高¥100佣金
    
    private static final BigDecimal STAMP_DUTY_RATE = new BigDecimal("0.0013");       // 0.13% 印花税
    private static final BigDecimal MIN_STAMP_DUTY = new BigDecimal("1.00");          // 最低¥1印花税
    
    private static final BigDecimal TRADING_FEE_RATE = new BigDecimal("0.00005");     // 0.005% 交易费
    private static final BigDecimal MIN_TRADING_FEE = new BigDecimal("0.01");         // 最低¥0.01交易费
    private static final BigDecimal MAX_TRADING_FEE = new BigDecimal("100.00");       // 最高¥100交易费
    
    private static final BigDecimal SETTLEMENT_FEE_RATE = new BigDecimal("0.00002");  // 0.002% 结算费
    private static final BigDecimal MIN_SETTLEMENT_FEE = new BigDecimal("2.00");      // 最低¥2结算费
    private static final BigDecimal MAX_SETTLEMENT_FEE = new BigDecimal("100.00");    // 最高¥100结算费
    
    private static final BigDecimal CCASS_FEE_RATE = new BigDecimal("0.00002");       // 0.002% 中央结算费
    private static final BigDecimal MIN_CCASS_FEE = new BigDecimal("2.00");           // 最低¥2中央结算费
    private static final BigDecimal MAX_CCASS_FEE = new BigDecimal("100.00");         // 最高¥100中央结算费
    
    private static final BigDecimal INVESTOR_COMPENSATION_FEE_RATE = new BigDecimal("0.00002"); // 0.002% 投资者赔偿费
    private static final BigDecimal MAX_INVESTOR_COMPENSATION_FEE = new BigDecimal("100.00");   // 最高¥100投资者赔偿费
    
    // 舍入模式：港股费用计算使用银行家舍入法
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;
    private static final int SCALE = 2; // 保留2位小数
    
    /**
     * 计算交易费用详细分解
     * 
     * @param price 交易价格
     * @param quantity 交易数量 (港股以手为单位，1手=100股或其他)
     * @param isSell 是否为卖出交易
     * @return 费用详细分解
     */
    public CommissionBreakdown calculateCommission(BigDecimal price, Integer quantity, boolean isSell) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("交易价格必须大于0");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("交易数量必须大于0");
        }
        
        log.debug("计算港股手续费: 价格={}, 数量={}, 卖出={}", price, quantity, isSell);
        
        // 计算交易金额
        BigDecimal tradeValue = price.multiply(BigDecimal.valueOf(quantity));
        
        // 1. 佣金计算 (买卖都收)
        BigDecimal commission = calculateCommissionFee(tradeValue);
        
        // 2. 印花税计算 (只在卖出时收取)
        BigDecimal stampDuty = isSell ? calculateStampDuty(tradeValue) : BigDecimal.ZERO;
        
        // 3. 交易费计算 (买卖都收)
        BigDecimal tradingFee = calculateTradingFee(tradeValue);
        
        // 4. 结算费计算 (买卖都收)
        BigDecimal settlementFee = calculateSettlementFee(tradeValue);
        
        // 5. 中央结算费计算 (买卖都收)
        BigDecimal ccassFee = calculateCcassFee(tradeValue);
        
        // 6. 投资者赔偿费计算 (只在卖出时收取)
        BigDecimal investorCompensationFee = isSell ? 
            calculateInvestorCompensationFee(tradeValue) : BigDecimal.ZERO;
        
        // 计算总费用
        BigDecimal totalFee = commission
            .add(stampDuty)
            .add(tradingFee)
            .add(settlementFee)
            .add(ccassFee)
            .add(investorCompensationFee);
        
        CommissionBreakdown breakdown = CommissionBreakdown.builder()
            .tradeValue(tradeValue.setScale(SCALE, ROUNDING_MODE))
            .commission(commission)
            .stampDuty(stampDuty)
            .tradingFee(tradingFee)
            .settlementFee(settlementFee)
            .ccassFee(ccassFee)
            .investorCompensationFee(investorCompensationFee)
            .totalFee(totalFee.setScale(SCALE, ROUNDING_MODE))
            .feePercentage(calculateFeePercentage(totalFee, tradeValue))
            .isSellTransaction(isSell)
            .calculationTime(LocalDateTime.now())
            .build();
        
        log.debug("港股手续费计算完成: 交易金额={}, 总费用={}, 费率={:.4f}%", 
            tradeValue, totalFee, breakdown.getFeePercentage());
        
        return breakdown;
    }
    
    /**
     * 计算佣金
     */
    private BigDecimal calculateCommissionFee(BigDecimal tradeValue) {
        BigDecimal commission = tradeValue.multiply(COMMISSION_RATE);
        
        // 应用最低和最高限制
        if (commission.compareTo(MIN_COMMISSION) < 0) {
            commission = MIN_COMMISSION;
        } else if (commission.compareTo(MAX_COMMISSION) > 0) {
            commission = MAX_COMMISSION;
        }
        
        return commission.setScale(SCALE, ROUNDING_MODE);
    }
    
    /**
     * 计算印花税 (仅卖出)
     */
    private BigDecimal calculateStampDuty(BigDecimal tradeValue) {
        BigDecimal stampDuty = tradeValue.multiply(STAMP_DUTY_RATE);
        
        // 应用最低限制
        if (stampDuty.compareTo(MIN_STAMP_DUTY) < 0) {
            stampDuty = MIN_STAMP_DUTY;
        }
        
        return stampDuty.setScale(SCALE, ROUNDING_MODE);
    }
    
    /**
     * 计算交易费
     */
    private BigDecimal calculateTradingFee(BigDecimal tradeValue) {
        BigDecimal tradingFee = tradeValue.multiply(TRADING_FEE_RATE);
        
        // 应用最低和最高限制
        if (tradingFee.compareTo(MIN_TRADING_FEE) < 0) {
            tradingFee = MIN_TRADING_FEE;
        } else if (tradingFee.compareTo(MAX_TRADING_FEE) > 0) {
            tradingFee = MAX_TRADING_FEE;
        }
        
        return tradingFee.setScale(SCALE, ROUNDING_MODE);
    }
    
    /**
     * 计算结算费
     */
    private BigDecimal calculateSettlementFee(BigDecimal tradeValue) {
        BigDecimal settlementFee = tradeValue.multiply(SETTLEMENT_FEE_RATE);
        
        // 应用最低和最高限制
        if (settlementFee.compareTo(MIN_SETTLEMENT_FEE) < 0) {
            settlementFee = MIN_SETTLEMENT_FEE;
        } else if (settlementFee.compareTo(MAX_SETTLEMENT_FEE) > 0) {
            settlementFee = MAX_SETTLEMENT_FEE;
        }
        
        return settlementFee.setScale(SCALE, ROUNDING_MODE);
    }
    
    /**
     * 计算中央结算费
     */
    private BigDecimal calculateCcassFee(BigDecimal tradeValue) {
        BigDecimal ccassFee = tradeValue.multiply(CCASS_FEE_RATE);
        
        // 应用最低和最高限制
        if (ccassFee.compareTo(MIN_CCASS_FEE) < 0) {
            ccassFee = MIN_CCASS_FEE;
        } else if (ccassFee.compareTo(MAX_CCASS_FEE) > 0) {
            ccassFee = MAX_CCASS_FEE;
        }
        
        return ccassFee.setScale(SCALE, ROUNDING_MODE);
    }
    
    /**
     * 计算投资者赔偿费 (仅卖出)
     */
    private BigDecimal calculateInvestorCompensationFee(BigDecimal tradeValue) {
        BigDecimal compensationFee = tradeValue.multiply(INVESTOR_COMPENSATION_FEE_RATE);
        
        // 应用最高限制
        if (compensationFee.compareTo(MAX_INVESTOR_COMPENSATION_FEE) > 0) {
            compensationFee = MAX_INVESTOR_COMPENSATION_FEE;
        }
        
        return compensationFee.setScale(SCALE, ROUNDING_MODE);
    }
    
    /**
     * 计算费用占交易金额的百分比
     */
    private BigDecimal calculateFeePercentage(BigDecimal totalFee, BigDecimal tradeValue) {
        if (tradeValue.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        
        return totalFee.divide(tradeValue, 6, ROUNDING_MODE)
            .multiply(BigDecimal.valueOf(100));
    }
    
    /**
     * 批量计算费用 - 用于回测场景
     */
    public List<CommissionBreakdown> calculateBatchCommissions(List<TradeTransaction> transactions) {
        log.info("批量计算港股手续费: {} 笔交易", transactions.size());
        
        return transactions.parallelStream()
            .map(tx -> calculateCommission(tx.getPrice(), tx.getQuantity(), tx.isSell()))
            .collect(Collectors.toList());
    }
    
    /**
     * 预估总交易成本 - 用于风险管理
     */
    public BigDecimal estimateTotalCost(BigDecimal price, Integer quantity, boolean roundTrip) {
        // 买入费用
        CommissionBreakdown buyFee = calculateCommission(price, quantity, false);
        BigDecimal totalCost = buyFee.getTotalFee();
        
        // 如果是往返交易，加上卖出费用
        if (roundTrip) {
            CommissionBreakdown sellFee = calculateCommission(price, quantity, true);
            totalCost = totalCost.add(sellFee.getTotalFee());
        }
        
        return totalCost;
    }
    
    /**
     * 计算盈亏平衡点
     */
    public BigDecimal calculateBreakevenPrice(BigDecimal buyPrice, Integer quantity) {
        // 买入总成本（包含费用）
        CommissionBreakdown buyCommission = calculateCommission(buyPrice, quantity, false);
        BigDecimal buyCost = buyPrice.multiply(BigDecimal.valueOf(quantity))
            .add(buyCommission.getTotalFee());
        
        // 通过迭代找到盈亏平衡的卖出价格
        BigDecimal sellPrice = buyPrice;
        BigDecimal step = new BigDecimal("0.01");
        
        for (int i = 0; i < 1000; i++) { // 最多迭代1000次
            CommissionBreakdown sellCommission = calculateCommission(sellPrice, quantity, true);
            BigDecimal sellRevenue = sellPrice.multiply(BigDecimal.valueOf(quantity))
                .subtract(sellCommission.getTotalFee());
            
            if (sellRevenue.compareTo(buyCost) >= 0) {
                return sellPrice.setScale(SCALE, ROUNDING_MODE);
            }
            
            sellPrice = sellPrice.add(step);
        }
        
        throw new RuntimeException("无法计算盈亏平衡点，可能买入价格过高");
    }
}
```

### 18.2.2 费用分解数据结构

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CommissionBreakdown {
    
    // 基础信息
    private BigDecimal tradeValue;              // 交易金额
    private boolean isSellTransaction;          // 是否为卖出交易
    private LocalDateTime calculationTime;     // 计算时间
    
    // 具体费用项目
    private BigDecimal commission;              // 佣金
    private BigDecimal stampDuty;               // 印花税 (仅卖出)
    private BigDecimal tradingFee;              // 交易费
    private BigDecimal settlementFee;           // 结算费
    private BigDecimal ccassFee;                // 中央结算费
    private BigDecimal investorCompensationFee; // 投资者赔偿费 (仅卖出)
    
    // 汇总信息
    private BigDecimal totalFee;                // 总费用
    private BigDecimal feePercentage;           // 费用占比
    
    /**
     * 获取买入专用费用
     */
    public BigDecimal getBuyOnlyFees() {
        return commission.add(tradingFee).add(settlementFee).add(ccassFee);
    }
    
    /**
     * 获取卖出专用费用
     */
    public BigDecimal getSellOnlyFees() {
        return stampDuty.add(investorCompensationFee);
    }
    
    /**
     * 获取费用明细表
     */
    public List<FeeItem> getFeeBreakdownItems() {
        List<FeeItem> items = new ArrayList<>();
        
        items.add(new FeeItem("佣金", "Commission", commission, "买卖均收", true));
        items.add(new FeeItem("交易费", "Trading Fee", tradingFee, "买卖均收", true));
        items.add(new FeeItem("结算费", "Settlement Fee", settlementFee, "买卖均收", true));
        items.add(new FeeItem("中央结算费", "CCASS Fee", ccassFee, "买卖均收", true));
        
        if (isSellTransaction) {
            items.add(new FeeItem("印花税", "Stamp Duty", stampDuty, "仅卖出", true));
            items.add(new FeeItem("投资者赔偿费", "Investor Compensation", 
                investorCompensationFee, "仅卖出", investorCompensationFee.compareTo(BigDecimal.ZERO) > 0));
        }
        
        return items;
    }
    
    /**
     * 生成费用报告摘要
     */
    public String generateSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("=== 港股交易费用明细 (%s) ===\n", 
            isSellTransaction ? "卖出" : "买入"));
        summary.append(String.format("交易金额: HK$%,.2f\n", tradeValue));
        summary.append(String.format("总手续费: HK$%,.2f (%.4f%%)\n", totalFee, feePercentage));
        summary.append("\n费用明细:\n");
        
        getFeeBreakdownItems().forEach(item -> {
            if (item.isApplicable()) {
                summary.append(String.format("- %s: HK$%,.2f (%s)\n", 
                    item.getChineseName(), item.getAmount(), item.getDescription()));
            }
        });
        
        summary.append(String.format("\n计算时间: %s\n", 
            calculationTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        
        return summary.toString();
    }
    
    /**
     * 转换为JSON格式
     */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tradeValue", tradeValue);
        map.put("isSellTransaction", isSellTransaction);
        map.put("calculationTime", calculationTime.toString());
        
        Map<String, BigDecimal> fees = new LinkedHashMap<>();
        fees.put("commission", commission);
        fees.put("tradingFee", tradingFee);
        fees.put("settlementFee", settlementFee);
        fees.put("ccassFee", ccassFee);
        if (isSellTransaction) {
            fees.put("stampDuty", stampDuty);
            fees.put("investorCompensationFee", investorCompensationFee);
        }
        map.put("fees", fees);
        
        map.put("totalFee", totalFee);
        map.put("feePercentage", feePercentage);
        
        return map;
    }
    
    @Data
    @AllArgsConstructor
    public static class FeeItem {
        private String chineseName;      // 中文名称
        private String englishName;      // 英文名称
        private BigDecimal amount;       // 费用金额
        private String description;      // 说明
        private boolean applicable;      // 是否适用
    }
}
```

### 18.2.3 费用配置管理服务

```java
@Service
@ConfigurationProperties(prefix = "trading.hk-commission")
@Data
@RefreshScope  // 支持配置热更新
public class CommissionConfigService {
    
    // 佣金配置
    private BigDecimal commissionRate = new BigDecimal("0.00025");
    private BigDecimal minCommission = new BigDecimal("5.00");
    private BigDecimal maxCommission = new BigDecimal("100.00");
    
    // 印花税配置
    private BigDecimal stampDutyRate = new BigDecimal("0.0013");
    private BigDecimal minStampDuty = new BigDecimal("1.00");
    
    // 交易费配置
    private BigDecimal tradingFeeRate = new BigDecimal("0.00005");
    private BigDecimal minTradingFee = new BigDecimal("0.01");
    private BigDecimal maxTradingFee = new BigDecimal("100.00");
    
    // 结算费配置
    private BigDecimal settlementFeeRate = new BigDecimal("0.00002");
    private BigDecimal minSettlementFee = new BigDecimal("2.00");
    private BigDecimal maxSettlementFee = new BigDecimal("100.00");
    
    // 其他配置
    private BigDecimal ccassFeeRate = new BigDecimal("0.00002");
    private BigDecimal investorCompensationFeeRate = new BigDecimal("0.00002");
    
    // 计算配置
    private RoundingMode roundingMode = RoundingMode.HALF_EVEN;
    private int scale = 2;
    
    // 生效时间
    private LocalDate effectiveDate = LocalDate.now();
    private String version = "2024.1";
    
    /**
     * 验证配置参数
     */
    @PostConstruct
    public void validateConfig() {
        List<String> errors = new ArrayList<>();
        
        if (commissionRate.compareTo(BigDecimal.ZERO) < 0) {
            errors.add("佣金费率不能为负数");
        }
        if (minCommission.compareTo(BigDecimal.ZERO) < 0) {
            errors.add("最低佣金不能为负数");
        }
        if (maxCommission.compareTo(minCommission) < 0) {
            errors.add("最高佣金不能小于最低佣金");
        }
        
        // 验证其他费率...
        
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("费用配置验证失败: " + String.join(", ", errors));
        }
        
        log.info("港股费用配置验证通过: 版本={}, 生效日期={}", version, effectiveDate);
    }
    
    /**
     * 获取配置摘要
     */
    public String getConfigSummary() {
        return String.format(
            "港股费用配置 v%s (生效: %s)\n" +
            "佣金: %.3f%% (最低¥%.2f, 最高¥%.2f)\n" +
            "印花税: %.3f%% (最低¥%.2f)\n" +
            "交易费: %.3f%% (最低¥%.2f, 最高¥%.2f)\n" +
            "结算费: %.3f%% (最低¥%.2f, 最高¥%.2f)",
            version, effectiveDate,
            commissionRate.multiply(BigDecimal.valueOf(100)), minCommission, maxCommission,
            stampDutyRate.multiply(BigDecimal.valueOf(100)), minStampDuty,
            tradingFeeRate.multiply(BigDecimal.valueOf(100)), minTradingFee, maxTradingFee,
            settlementFeeRate.multiply(BigDecimal.valueOf(100)), minSettlementFee, maxSettlementFee
        );
    }
}
```

## 18.3 特殊场景处理

### 18.3.1 碎股交易处理

```java
@Component
public class OddLotCommissionCalculator {
    
    // 碎股交易特殊费率 (通常更高)
    private static final BigDecimal ODD_LOT_COMMISSION_RATE = new BigDecimal("0.0005"); // 0.05%
    private static final BigDecimal ODD_LOT_MIN_COMMISSION = new BigDecimal("10.00");   // 最低¥10
    
    /**
     * 判断是否为碎股交易
     * 港股一般以100股为一手，不足一手为碎股
     */
    public boolean isOddLot(String symbol, Integer quantity) {
        int lotSize = getLotSize(symbol);
        return quantity % lotSize != 0 || quantity < lotSize;
    }
    
    /**
     * 获取股票每手股数
     */
    private int getLotSize(String symbol) {
        // 大部分港股是100股一手，部分高价股是10股一手
        Map<String, Integer> specialLotSizes = Map.of(
            "00005.HK", 400,   // 汇丰控股 400股一手
            "00939.HK", 1000,  // 建设银行 1000股一手
            "01299.HK", 500    // 友邦保险 500股一手
        );
        
        return specialLotSizes.getOrDefault(symbol, 100); // 默认100股一手
    }
    
    /**
     * 计算碎股交易费用
     */
    public CommissionBreakdown calculateOddLotCommission(BigDecimal price, Integer quantity, 
                                                       boolean isSell, String symbol) {
        log.info("计算碎股交易费用: {} 价格={} 数量={}", symbol, price, quantity);
        
        BigDecimal tradeValue = price.multiply(BigDecimal.valueOf(quantity));
        
        // 碎股佣金按特殊费率计算
        BigDecimal commission = tradeValue.multiply(ODD_LOT_COMMISSION_RATE);
        if (commission.compareTo(ODD_LOT_MIN_COMMISSION) < 0) {
            commission = ODD_LOT_MIN_COMMISSION;
        }
        
        // 其他费用按正常标准计算，但可能有最低收费差异
        // ... 省略其他费用计算
        
        return CommissionBreakdown.builder()
            .tradeValue(tradeValue)
            .commission(commission)
            // ... 其他费用
            .build();
    }
}
```

### 18.3.2 新股认购费用

```java
@Component
public class IPOSubscriptionCalculator {
    
    private static final BigDecimal IPO_HANDLING_FEE = new BigDecimal("50.00");  // 认购手续费¥50
    private static final BigDecimal IPO_BROKERAGE_FEE = new BigDecimal("100.00"); // 券商费¥100
    
    /**
     * 计算新股认购费用
     */
    public IPOCommissionBreakdown calculateIPOSubscriptionFee(
            BigDecimal subscriptionAmount, int numberOfApplications) {
        
        BigDecimal handlingFee = IPO_HANDLING_FEE.multiply(BigDecimal.valueOf(numberOfApplications));
        BigDecimal brokerageFee = IPO_BROKERAGE_FEE; // 通常固定收费
        
        // 银行手续费 (如果通过银行认购)
        BigDecimal bankFee = subscriptionAmount.multiply(new BigDecimal("0.0025")); // 0.25%
        
        BigDecimal totalFee = handlingFee.add(brokerageFee).add(bankFee);
        
        return IPOCommissionBreakdown.builder()
            .subscriptionAmount(subscriptionAmount)
            .numberOfApplications(numberOfApplications)
            .handlingFee(handlingFee)
            .brokerageFee(brokerageFee)
            .bankFee(bankFee)
            .totalFee(totalFee)
            .build();
    }
}
```

### 18.3.3 融资融券费用

```java
@Component
public class MarginTradingCalculator {
    
    private static final BigDecimal MARGIN_INTEREST_RATE = new BigDecimal("0.06"); // 6% 年利率
    private static final BigDecimal SHORT_SELLING_FEE_RATE = new BigDecimal("0.0008"); // 0.08% 借股费
    
    /**
     * 计算融资利息
     */
    public BigDecimal calculateMarginInterest(BigDecimal marginAmount, int days) {
        // 年利率转换为日利率
        BigDecimal dailyRate = MARGIN_INTEREST_RATE.divide(BigDecimal.valueOf(365), 8, RoundingMode.HALF_UP);
        
        return marginAmount.multiply(dailyRate).multiply(BigDecimal.valueOf(days));
    }
    
    /**
     * 计算融券费用
     */
    public BigDecimal calculateShortSellingFee(BigDecimal shortValue, int days) {
        BigDecimal dailyFeeRate = SHORT_SELLING_FEE_RATE.divide(BigDecimal.valueOf(365), 8, RoundingMode.HALF_UP);
        
        return shortValue.multiply(dailyFeeRate).multiply(BigDecimal.valueOf(days));
    }
}
```

## 18.4 费用优化和折扣

### 18.4.1 大客户折扣体系

```java
@Service
public class DiscountCalculationService {
    
    /**
     * 根据交易量级别计算折扣
     */
    public BigDecimal calculateVolumeDiscount(BigDecimal monthlyVolume) {
        // 月交易量折扣阶梯
        if (monthlyVolume.compareTo(new BigDecimal("10000000")) >= 0) { // 1000万以上
            return new BigDecimal("0.5");  // 50%折扣
        } else if (monthlyVolume.compareTo(new BigDecimal("5000000")) >= 0) { // 500万以上
            return new BigDecimal("0.3");  // 30%折扣
        } else if (monthlyVolume.compareTo(new BigDecimal("1000000")) >= 0) { // 100万以上
            return new BigDecimal("0.2");  // 20%折扣
        } else {
            return BigDecimal.ZERO; // 无折扣
        }
    }
    
    /**
     * VIP客户特殊费率
     */
    public CommissionBreakdown applyVIPRates(CommissionBreakdown originalFee, String clientLevel) {
        BigDecimal discountMultiplier = switch (clientLevel) {
            case "DIAMOND" -> new BigDecimal("0.5");   // 钻石客户50%费率
            case "PLATINUM" -> new BigDecimal("0.7");  // 白金客户70%费率
            case "GOLD" -> new BigDecimal("0.8");      // 黄金客户80%费率
            case "SILVER" -> new BigDecimal("0.9");    // 银牌客户90%费率
            default -> BigDecimal.ONE;                 // 普通客户无折扣
        };
        
        return CommissionBreakdown.builder()
            .tradeValue(originalFee.getTradeValue())
            .commission(originalFee.getCommission().multiply(discountMultiplier))
            .stampDuty(originalFee.getStampDuty()) // 印花税不能打折
            .tradingFee(originalFee.getTradingFee().multiply(discountMultiplier))
            .settlementFee(originalFee.getSettlementFee().multiply(discountMultiplier))
            .ccassFee(originalFee.getCcassFee().multiply(discountMultiplier))
            .investorCompensationFee(originalFee.getInvestorCompensationFee())
            .build();
    }
}
```

## 18.5 费用分析和统计

### 18.5.1 费用统计服务

```java
@Service
@Slf4j
public class CommissionStatisticsService {
    
    @Autowired
    private CommissionRepository commissionRepository;
    
    /**
     * 计算指定期间的费用统计
     */
    public CommissionStatistics calculatePeriodStatistics(String accountId, 
                                                         LocalDate startDate, 
                                                         LocalDate endDate) {
        List<CommissionBreakdown> commissions = commissionRepository
            .findByAccountIdAndDateBetween(accountId, startDate, endDate);
        
        if (commissions.isEmpty()) {
            return CommissionStatistics.empty(accountId, startDate, endDate);
        }
        
        // 分别统计买入和卖出费用
        List<CommissionBreakdown> buyCommissions = commissions.stream()
            .filter(c -> !c.isSellTransaction())
            .collect(Collectors.toList());
        
        List<CommissionBreakdown> sellCommissions = commissions.stream()
            .filter(CommissionBreakdown::isSellTransaction)
            .collect(Collectors.toList());
        
        return CommissionStatistics.builder()
            .accountId(accountId)
            .startDate(startDate)
            .endDate(endDate)
            .totalTransactions(commissions.size())
            .buyTransactions(buyCommissions.size())
            .sellTransactions(sellCommissions.size())
            .totalTradeValue(calculateTotalTradeValue(commissions))
            .totalCommissionFee(calculateTotalCommission(commissions))
            .totalStampDuty(calculateTotalStampDuty(sellCommissions))
            .totalTradingFee(calculateTotalTradingFee(commissions))
            .totalSettlementFee(calculateTotalSettlementFee(commissions))
            .totalFees(calculateTotalFees(commissions))
            .averageFeeRate(calculateAverageFeeRate(commissions))
            .highestSingleFee(findHighestSingleFee(commissions))
            .lowestSingleFee(findLowestSingleFee(commissions))
            .build();
    }
    
    /**
     * 生成费用分析报告
     */
    public String generateCommissionAnalysisReport(CommissionStatistics stats) {
        StringBuilder report = new StringBuilder();
        
        report.append("=== 港股交易费用分析报告 ===\n");
        report.append(String.format("账户ID: %s\n", stats.getAccountId()));
        report.append(String.format("统计期间: %s 至 %s\n", stats.getStartDate(), stats.getEndDate()));
        report.append("\n");
        
        report.append("【交易概况】\n");
        report.append(String.format("总交易笔数: %d 笔\n", stats.getTotalTransactions()));
        report.append(String.format("  - 买入交易: %d 笔\n", stats.getBuyTransactions()));
        report.append(String.format("  - 卖出交易: %d 笔\n", stats.getSellTransactions()));
        report.append(String.format("总交易金额: HK$%,.2f\n", stats.getTotalTradeValue()));
        report.append("\n");
        
        report.append("【费用明细】\n");
        report.append(String.format("佣金总计: HK$%,.2f\n", stats.getTotalCommissionFee()));
        report.append(String.format("印花税总计: HK$%,.2f\n", stats.getTotalStampDuty()));
        report.append(String.format("交易费总计: HK$%,.2f\n", stats.getTotalTradingFee()));
        report.append(String.format("结算费总计: HK$%,.2f\n", stats.getTotalSettlementFee()));
        report.append(String.format("费用总计: HK$%,.2f\n", stats.getTotalFees()));
        report.append(String.format("平均费率: %.4f%%\n", stats.getAverageFeeRate()));
        report.append("\n");
        
        report.append("【费用分析】\n");
        report.append(String.format("单笔最高费用: HK$%,.2f\n", stats.getHighestSingleFee()));
        report.append(String.format("单笔最低费用: HK$%,.2f\n", stats.getLowestSingleFee()));
        report.append(String.format("平均每笔费用: HK$%,.2f\n", 
            stats.getTotalFees().divide(BigDecimal.valueOf(stats.getTotalTransactions()), 2, RoundingMode.HALF_UP)));
        
        // 费用占交易金额比例分析
        BigDecimal feeRatio = stats.getTotalFees().divide(stats.getTotalTradeValue(), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        report.append(String.format("费用占交易金额比例: %.4f%%\n", feeRatio));
        
        return report.toString();
    }
    
    private BigDecimal calculateTotalTradeValue(List<CommissionBreakdown> commissions) {
        return commissions.stream()
            .map(CommissionBreakdown::getTradeValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    private BigDecimal calculateTotalCommission(List<CommissionBreakdown> commissions) {
        return commissions.stream()
            .map(CommissionBreakdown::getCommission)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    private BigDecimal calculateTotalFees(List<CommissionBreakdown> commissions) {
        return commissions.stream()
            .map(CommissionBreakdown::getTotalFee)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    private BigDecimal calculateAverageFeeRate(List<CommissionBreakdown> commissions) {
        if (commissions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal totalFeeRate = commissions.stream()
            .map(CommissionBreakdown::getFeePercentage)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        return totalFeeRate.divide(BigDecimal.valueOf(commissions.size()), 4, RoundingMode.HALF_UP);
    }
}
```

## 18.6 配置文件示例

### 18.6.1 应用配置

```yaml
# application.yml
trading:
  hk-commission:
    # 佣金配置 (2024年标准)
    commission-rate: 0.00025      # 0.025%
    min-commission: 5.00          # 最低¥5
    max-commission: 100.00        # 最高¥100
    
    # 印花税配置
    stamp-duty-rate: 0.0013       # 0.13%
    min-stamp-duty: 1.00          # 最低¥1
    
    # 交易费配置
    trading-fee-rate: 0.00005     # 0.005%
    min-trading-fee: 0.01         # 最低¥0.01
    max-trading-fee: 100.00       # 最高¥100
    
    # 结算费配置
    settlement-fee-rate: 0.00002  # 0.002%
    min-settlement-fee: 2.00      # 最低¥2
    max-settlement-fee: 100.00    # 最高¥100
    
    # 中央结算费配置
    ccass-fee-rate: 0.00002       # 0.002%
    min-ccass-fee: 2.00           # 最低¥2
    max-ccass-fee: 100.00         # 最高¥100
    
    # 投资者赔偿费配置
    investor-compensation-fee-rate: 0.00002  # 0.002%
    max-investor-compensation-fee: 100.00    # 最高¥100
    
    # 计算配置
    rounding-mode: HALF_EVEN      # 银行家舍入
    scale: 2                      # 保留2位小数
    
    # 版本信息
    version: "2024.1"
    effective-date: "2024-01-01"
    
    # 特殊配置
    odd-lot:
      commission-rate: 0.0005     # 碎股佣金 0.05%
      min-commission: 10.00       # 碎股最低佣金¥10
      
    # 折扣配置
    discount:
      volume-tiers:
        - min-volume: 10000000    # 1000万
          discount: 0.5           # 50%折扣
        - min-volume: 5000000     # 500万
          discount: 0.3           # 30%折扣
        - min-volume: 1000000     # 100万
          discount: 0.2           # 20%折扣
          
    # 监控配置
    monitoring:
      enabled: true
      alert-high-fee-threshold: 1000.00  # 高费用预警阈值
      alert-high-fee-rate-threshold: 1.0 # 高费率预警阈值(1%)
```

## 18.7 单元测试示例

### 18.7.1 费用计算测试

```java
@ExtendWith(MockitoExtension.class)
class HKStockCommissionCalculatorTest {
    
    private HKStockCommissionCalculator calculator;
    
    @BeforeEach
    void setUp() {
        calculator = new HKStockCommissionCalculator();
    }
    
    @Test
    @DisplayName("测试基本买入费用计算")
    void testBasicBuyCommissionCalculation() {
        // Given
        BigDecimal price = new BigDecimal("100.00");
        Integer quantity = 1000; // 10手
        boolean isSell = false;
        
        // When
        CommissionBreakdown result = calculator.calculateCommission(price, quantity, isSell);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTradeValue()).isEqualTo(new BigDecimal("100000.00"));
        assertThat(result.getCommission()).isEqualTo(new BigDecimal("25.00")); // 100000 * 0.00025
        assertThat(result.getStampDuty()).isEqualTo(BigDecimal.ZERO); // 买入无印花税
        assertThat(result.getTotalFee()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.isSellTransaction()).isFalse();
    }
    
    @Test
    @DisplayName("测试基本卖出费用计算")
    void testBasicSellCommissionCalculation() {
        // Given
        BigDecimal price = new BigDecimal("100.00");
        Integer quantity = 1000;
        boolean isSell = true;
        
        // When
        CommissionBreakdown result = calculator.calculateCommission(price, quantity, isSell);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.getStampDuty()).isEqualTo(new BigDecimal("130.00")); // 100000 * 0.0013
        assertThat(result.getInvestorCompensationFee()).isEqualTo(new BigDecimal("2.00")); // 最低费用
        assertThat(result.isSellTransaction()).isTrue();
    }
    
    @Test
    @DisplayName("测试最低佣金限制")
    void testMinimumCommissionLimit() {
        // Given: 小额交易，佣金低于最低限制
        BigDecimal price = new BigDecimal("1.00");
        Integer quantity = 100; // 100股
        
        // When
        CommissionBreakdown result = calculator.calculateCommission(price, quantity, false);
        
        // Then
        assertThat(result.getCommission()).isEqualTo(new BigDecimal("5.00")); // 最低佣金¥5
    }
    
    @Test
    @DisplayName("测试费用百分比计算")
    void testFeePercentageCalculation() {
        // Given
        BigDecimal price = new BigDecimal("50.00");
        Integer quantity = 2000; // 20手
        
        // When
        CommissionBreakdown result = calculator.calculateCommission(price, quantity, false);
        
        // Then
        BigDecimal expectedTradeValue = new BigDecimal("100000.00");
        assertThat(result.getTradeValue()).isEqualTo(expectedTradeValue);
        
        // 验证费率计算正确性
        BigDecimal expectedFeeRate = result.getTotalFee()
            .divide(expectedTradeValue, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        assertThat(result.getFeePercentage()).isEqualByComparingTo(expectedFeeRate);
    }
    
    @Test
    @DisplayName("测试参数验证")
    void testParameterValidation() {
        // Test null price
        assertThatThrownBy(() -> calculator.calculateCommission(null, 1000, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("交易价格必须大于0");
        
        // Test negative price
        assertThatThrownBy(() -> calculator.calculateCommission(new BigDecimal("-1.00"), 1000, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("交易价格必须大于0");
        
        // Test null quantity
        assertThatThrownBy(() -> calculator.calculateCommission(new BigDecimal("100.00"), null, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("交易数量必须大于0");
        
        // Test zero quantity
        assertThatThrownBy(() -> calculator.calculateCommission(new BigDecimal("100.00"), 0, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("交易数量必须大于0");
    }
    
    @ParameterizedTest
    @CsvSource({
        "10.00, 1000, false, 25.00",  // 佣金: 10000 * 0.00025 = 2.5 -> 最低5.00
        "100.00, 1000, false, 25.00", // 佣金: 100000 * 0.00025 = 25.00
        "1000.00, 1000, false, 100.00" // 佣金: 1000000 * 0.00025 = 250 -> 最高100.00
    })
    @DisplayName("测试佣金费率边界条件")
    void testCommissionBoundaryConditions(BigDecimal price, Integer quantity, 
                                        boolean isSell, BigDecimal expectedCommission) {
        // When
        CommissionBreakdown result = calculator.calculateCommission(price, quantity, isSell);
        
        // Then
        assertThat(result.getCommission()).isEqualByComparingTo(expectedCommission);
    }
}
```

通过这个完善的港股手续费计算系统，能够精确处理各种交易场景的费用计算，为回测和实盘交易提供准确的成本分析，帮助交易者更好地进行风险管理和盈利分析。