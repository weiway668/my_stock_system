# 港股程序化交易系统 - 数据准备服务

## 17.1 数据准备概述

### 17.1.1 设计理念
数据准备服务 (DataPreparationService) 是回测系统的重要组件，确保技术指标计算的准确性和数据质量。通过预热机制，避免因数据不足导致的指标失真问题。

### 17.1.2 核心功能
- **数据预热**: 回测开始前预加载100个交易日的历史数据
- **质量检查**: 全面验证数据完整性和有效性
- **指标预计算**: 在预热期间预先计算技术指标
- **内存优化**: 有效管理大量历史数据的内存占用
- **错误处理**: 完善的数据异常检测和处理机制

## 17.2 核心实现

### 17.2.1 数据准备服务主体

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class DataPreparationService {
    
    private static final int WARMUP_DAYS = 100;  // 预热天数
    private static final int MIN_REQUIRED_DATA_POINTS = 60;  // 最少数据点
    private static final int MAX_MISSING_DATA_POINTS = 5;   // 最大允许缺失数据点
    private static final double MAX_PRICE_CHANGE_THRESHOLD = 0.5; // 最大价格变动阈值(50%)
    
    private final TradingSystemRepository repository;
    private final DataValidationService dataValidationService;
    private final IndicatorPreComputeService indicatorService;
    
    /**
     * 为回测准备数据，包括预热数据和质量检查
     */
    public PreparedData prepareBacktestData(String symbol, LocalDateTime startTime, LocalDateTime endTime) {
        log.info("🔄 开始准备回测数据: {} {} -> {}", symbol, startTime.toLocalDate(), endTime.toLocalDate());
        
        StopWatch stopWatch = StopWatch.createStarted();
        
        try {
            // 1. 计算预热开始时间
            LocalDateTime warmupStartTime = calculateWarmupStartTime(symbol, startTime);
            log.debug("预热开始时间: {}", warmupStartTime.toLocalDate());
            
            // 2. 加载包含预热期的历史数据
            List<MarketData> allData = loadHistoricalDataWithRetry(symbol, warmupStartTime, endTime);
            log.info("📊 加载历史数据: {} 条", allData.size());
            
            // 3. 数据质量验证
            DataQualityReport qualityReport = validateDataQuality(allData, symbol, startTime, endTime);
            if (!qualityReport.isDataUsable()) {
                throw new DataPreparationException("数据质量不符合要求: " + qualityReport.getSummary());
            }
            
            // 4. 计算预热结束位置
            int warmupEndIndex = findWarmupEndIndex(allData, startTime);
            if (warmupEndIndex < MIN_REQUIRED_DATA_POINTS) {
                throw new DataPreparationException(
                    String.format("预热数据不足，需要至少%d个数据点，实际只有%d个", 
                        MIN_REQUIRED_DATA_POINTS, warmupEndIndex));
            }
            
            // 5. 预计算技术指标
            precomputeIndicators(allData, warmupEndIndex);
            
            // 6. 构建准备结果
            PreparedData result = PreparedData.builder()
                .symbol(symbol)
                .marketData(Collections.unmodifiableList(allData))
                .warmupStartTime(warmupStartTime)
                .backtestStartTime(startTime)
                .backtestEndTime(endTime)
                .warmupEndIndex(warmupEndIndex)
                .dataQualityReport(qualityReport)
                .preparationTimeMs(stopWatch.getTime())
                .build();
            
            stopWatch.stop();
            log.info("✅ 数据准备完成，耗时: {}ms", stopWatch.getTime());
            
            return result;
            
        } catch (Exception e) {
            log.error("❌ 数据准备失败: {}", e.getMessage(), e);
            throw new DataPreparationException("数据准备失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 计算预热开始时间
     * 向前推算足够的交易日，确保包含所需的历史数据
     */
    private LocalDateTime calculateWarmupStartTime(String symbol, LocalDateTime backtestStart) {
        // 港股交易日历：周一到周五，排除节假日
        LocalDate current = backtestStart.toLocalDate();
        int tradingDaysCount = 0;
        int maxSearchDays = 200; // 最多向前查找200天，防止无限循环
        int searchDays = 0;
        
        while (tradingDaysCount < WARMUP_DAYS && searchDays < maxSearchDays) {
            current = current.minusDays(1);
            searchDays++;
            
            if (isHKTradingDay(current)) {
                tradingDaysCount++;
            }
        }
        
        if (tradingDaysCount < WARMUP_DAYS) {
            log.warn("⚠️ 预热数据可能不足，实际找到{}个交易日，需要{}个", 
                tradingDaysCount, WARMUP_DAYS);
        }
        
        return current.atStartOfDay();
    }
    
    /**
     * 判断是否为港股交易日
     */
    private boolean isHKTradingDay(LocalDate date) {
        // 排除周末
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        
        // 排除港股公共假期
        return !isHKPublicHoliday(date);
    }
    
    /**
     * 检查是否为港股公共假期
     */
    private boolean isHKPublicHoliday(LocalDate date) {
        Set<LocalDate> holidays = getHKPublicHolidays(date.getYear());
        return holidays.contains(date);
    }
    
    /**
     * 获取指定年份的港股公共假期
     */
    private Set<LocalDate> getHKPublicHolidays(int year) {
        Set<LocalDate> holidays = new HashSet<>();
        
        // 固定假期
        holidays.add(LocalDate.of(year, 1, 1));   // 元旦
        holidays.add(LocalDate.of(year, 5, 1));   // 劳动节
        holidays.add(LocalDate.of(year, 7, 1));   // 香港特别行政区成立纪念日
        holidays.add(LocalDate.of(year, 10, 1));  // 国庆节
        holidays.add(LocalDate.of(year, 12, 25)); // 圣诞节
        holidays.add(LocalDate.of(year, 12, 26)); // 节礼日
        
        // 农历假期（需要根据年份计算）
        // 春节、清明节、佛诞、端午节、中秋节等
        holidays.addAll(calculateLunarHolidays(year));
        
        return holidays;
    }
    
    /**
     * 带重试机制的数据加载
     */
    private List<MarketData> loadHistoricalDataWithRetry(String symbol, LocalDateTime startTime, LocalDateTime endTime) {
        int maxRetries = 3;
        int retryDelay = 1000; // 1秒
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                List<MarketData> data = repository.findMarketDataBySymbolAndTimeBetween(
                    symbol, startTime, endTime);
                
                if (!data.isEmpty()) {
                    return data.stream()
                        .sorted(Comparator.comparing(MarketData::getTimestamp))
                        .collect(Collectors.toList());
                }
                
                log.warn("⚠️ 数据为空，尝试第{}次重新加载", attempt);
                
            } catch (Exception e) {
                log.warn("⚠️ 数据加载失败(第{}次尝试): {}", attempt, e.getMessage());
                
                if (attempt == maxRetries) {
                    throw new DataPreparationException("数据加载失败，已重试" + maxRetries + "次", e);
                }
                
                // 等待后重试
                try {
                    Thread.sleep(retryDelay * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new DataPreparationException("数据加载被中断", ie);
                }
            }
        }
        
        throw new DataPreparationException("无法加载数据: " + symbol);
    }
    
    /**
     * 查找预热结束的索引位置
     */
    private int findWarmupEndIndex(List<MarketData> data, LocalDateTime backtestStart) {
        for (int i = 0; i < data.size(); i++) {
            if (!data.get(i).getTimestamp().isBefore(backtestStart)) {
                return i;
            }
        }
        return data.size();
    }
}
```

### 17.2.2 数据质量验证服务

```java
@Service
@Slf4j
public class DataValidationService {
    
    private static final BigDecimal MIN_PRICE = BigDecimal.valueOf(0.001);  // 最小价格
    private static final BigDecimal MAX_PRICE = BigDecimal.valueOf(10000);  // 最大价格
    private static final BigDecimal MIN_VOLUME = BigDecimal.ZERO;           // 最小成交量
    private static final double MAX_DAILY_CHANGE = 0.3; // 最大日涨跌幅30%
    
    /**
     * 验证数据质量
     */
    public DataQualityReport validateDataQuality(List<MarketData> data, String symbol, 
                                               LocalDateTime startTime, LocalDateTime endTime) {
        
        log.debug("🔍 开始数据质量验证: {} 条数据", data.size());
        
        DataQualityReport.Builder reportBuilder = DataQualityReport.builder()
            .symbol(symbol)
            .totalDataPoints(data.size())
            .validationTime(LocalDateTime.now());
        
        if (data.isEmpty()) {
            return reportBuilder
                .dataUsable(false)
                .summary("数据为空")
                .build();
        }
        
        // 1. 基础数据验证
        List<String> issues = new ArrayList<>();
        int invalidPriceCount = 0;
        int invalidVolumeCount = 0;
        int suspiciousChangeCount = 0;
        int duplicateCount = 0;
        
        Set<LocalDateTime> timestamps = new HashSet<>();
        MarketData prevData = null;
        
        for (int i = 0; i < data.size(); i++) {
            MarketData current = data.get(i);
            
            // 检查价格有效性
            if (isPriceInvalid(current)) {
                invalidPriceCount++;
                if (invalidPriceCount <= 5) { // 只记录前5个错误
                    issues.add(String.format("无效价格: %s 时间=%s 开盘=%.3f 收盘=%.3f", 
                        symbol, current.getTimestamp(), current.getOpen(), current.getClose()));
                }
            }
            
            // 检查成交量有效性
            if (current.getVolume().compareTo(MIN_VOLUME) < 0) {
                invalidVolumeCount++;
                if (invalidVolumeCount <= 3) {
                    issues.add(String.format("无效成交量: %s 时间=%s 成交量=%.0f", 
                        symbol, current.getTimestamp(), current.getVolume()));
                }
            }
            
            // 检查重复时间戳
            if (!timestamps.add(current.getTimestamp())) {
                duplicateCount++;
                if (duplicateCount <= 3) {
                    issues.add(String.format("重复时间戳: %s 时间=%s", symbol, current.getTimestamp()));
                }
            }
            
            // 检查异常价格变动
            if (prevData != null) {
                double changeRate = calculateChangeRate(prevData.getClose(), current.getClose());
                if (Math.abs(changeRate) > MAX_DAILY_CHANGE) {
                    suspiciousChangeCount++;
                    if (suspiciousChangeCount <= 3) {
                        issues.add(String.format("异常价格变动: %s 时间=%s 变动=%.2f%%", 
                            symbol, current.getTimestamp(), changeRate * 100));
                    }
                }
            }
            
            prevData = current;
        }
        
        // 2. 时间连续性检查
        int missingDataPoints = checkTimeContinuity(data);
        if (missingDataPoints > 0) {
            issues.add(String.format("可能缺失的数据点: %d个", missingDataPoints));
        }
        
        // 3. 数据分布检查
        DataDistribution distribution = analyzeDataDistribution(data);
        if (distribution.hasAnomalies()) {
            issues.addAll(distribution.getAnomalies());
        }
        
        // 4. 综合评估
        boolean dataUsable = evaluateDataUsability(
            data.size(), invalidPriceCount, invalidVolumeCount, 
            suspiciousChangeCount, duplicateCount, missingDataPoints
        );
        
        String summary = createQualitySummary(
            data.size(), invalidPriceCount, invalidVolumeCount, 
            suspiciousChangeCount, duplicateCount, missingDataPoints, dataUsable
        );
        
        DataQualityReport report = reportBuilder
            .dataUsable(dataUsable)
            .summary(summary)
            .issues(issues)
            .invalidPriceCount(invalidPriceCount)
            .invalidVolumeCount(invalidVolumeCount)
            .suspiciousChangeCount(suspiciousChangeCount)
            .duplicateCount(duplicateCount)
            .missingDataPoints(missingDataPoints)
            .dataDistribution(distribution)
            .build();
        
        log.info("📋 数据质量报告: {} - {}", symbol, summary);
        if (!issues.isEmpty()) {
            log.warn("⚠️ 发现 {} 个数据质量问题", issues.size());
            issues.forEach(issue -> log.debug("  - {}", issue));
        }
        
        return report;
    }
    
    /**
     * 检查价格是否无效
     */
    private boolean isPriceInvalid(MarketData data) {
        return isInvalidPrice(data.getOpen()) ||
               isInvalidPrice(data.getHigh()) ||
               isInvalidPrice(data.getLow()) ||
               isInvalidPrice(data.getClose()) ||
               data.getHigh().compareTo(data.getLow()) < 0 ||  // 最高价小于最低价
               data.getOpen().compareTo(data.getLow()) < 0 ||  // 开盘价小于最低价
               data.getOpen().compareTo(data.getHigh()) > 0 || // 开盘价大于最高价
               data.getClose().compareTo(data.getLow()) < 0 || // 收盘价小于最低价
               data.getClose().compareTo(data.getHigh()) > 0;  // 收盘价大于最高价
    }
    
    private boolean isInvalidPrice(BigDecimal price) {
        return price == null || 
               price.compareTo(MIN_PRICE) < 0 || 
               price.compareTo(MAX_PRICE) > 0 ||
               price.scale() > 4; // 价格精度不超过4位小数
    }
    
    /**
     * 计算价格变动率
     */
    private double calculateChangeRate(BigDecimal oldPrice, BigDecimal newPrice) {
        if (oldPrice.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return newPrice.subtract(oldPrice)
            .divide(oldPrice, 6, RoundingMode.HALF_UP)
            .doubleValue();
    }
    
    /**
     * 检查时间连续性
     */
    private int checkTimeContinuity(List<MarketData> data) {
        if (data.size() < 2) {
            return 0;
        }
        
        int missingCount = 0;
        for (int i = 1; i < data.size(); i++) {
            LocalDateTime prev = data.get(i - 1).getTimestamp();
            LocalDateTime current = data.get(i).getTimestamp();
            
            // 计算预期的下一个时间点（30分钟间隔）
            LocalDateTime expected = prev.plusMinutes(30);
            
            // 跳过非交易时间
            while (!isInTradingHours(expected)) {
                expected = expected.plusMinutes(30);
            }
            
            // 如果实际时间与预期时间不符，可能缺失数据
            if (!current.equals(expected) && current.isAfter(expected)) {
                missingCount++;
            }
        }
        
        return missingCount;
    }
    
    /**
     * 检查是否在交易时间内
     */
    private boolean isInTradingHours(LocalDateTime dateTime) {
        LocalTime time = dateTime.toLocalTime();
        DayOfWeek dayOfWeek = dateTime.getDayOfWeek();
        
        // 港股交易时间：
        // 上午: 09:30-12:00
        // 下午: 13:00-16:00
        // 周一至周五
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        
        return (time.isAfter(LocalTime.of(9, 29)) && time.isBefore(LocalTime.of(12, 1))) ||
               (time.isAfter(LocalTime.of(12, 59)) && time.isBefore(LocalTime.of(16, 1)));
    }
    
    /**
     * 评估数据可用性
     */
    private boolean evaluateDataUsability(int totalCount, int invalidPriceCount, 
                                        int invalidVolumeCount, int suspiciousChangeCount,
                                        int duplicateCount, int missingDataPoints) {
        
        // 数据量要求
        if (totalCount < MIN_REQUIRED_DATA_POINTS) {
            return false;
        }
        
        // 质量要求
        double invalidPriceRate = (double) invalidPriceCount / totalCount;
        double invalidVolumeRate = (double) invalidVolumeCount / totalCount;
        double suspiciousChangeRate = (double) suspiciousChangeCount / totalCount;
        double duplicateRate = (double) duplicateCount / totalCount;
        double missingRate = (double) missingDataPoints / totalCount;
        
        // 设置质量阈值
        return invalidPriceRate <= 0.05 &&        // 无效价格率 ≤ 5%
               invalidVolumeRate <= 0.1 &&        // 无效成交量率 ≤ 10%
               suspiciousChangeRate <= 0.02 &&    // 异常变动率 ≤ 2%
               duplicateRate <= 0.01 &&           // 重复率 ≤ 1%
               missingRate <= 0.1;                // 缺失率 ≤ 10%
    }
    
    /**
     * 创建质量摘要
     */
    private String createQualitySummary(int totalCount, int invalidPriceCount, 
                                      int invalidVolumeCount, int suspiciousChangeCount,
                                      int duplicateCount, int missingDataPoints, 
                                      boolean dataUsable) {
        
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("数据总量:%d", totalCount));
        
        if (invalidPriceCount > 0) {
            summary.append(String.format(" 无效价格:%d", invalidPriceCount));
        }
        if (invalidVolumeCount > 0) {
            summary.append(String.format(" 无效成交量:%d", invalidVolumeCount));
        }
        if (suspiciousChangeCount > 0) {
            summary.append(String.format(" 异常变动:%d", suspiciousChangeCount));
        }
        if (duplicateCount > 0) {
            summary.append(String.format(" 重复数据:%d", duplicateCount));
        }
        if (missingDataPoints > 0) {
            summary.append(String.format(" 缺失数据:%d", missingDataPoints));
        }
        
        summary.append(String.format(" 状态:%s", dataUsable ? "可用" : "不可用"));
        
        return summary.toString();
    }
}
```

### 17.2.3 技术指标预计算服务

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class IndicatorPreComputeService {
    
    private final IndicatorCalculatorService indicatorCalculator;
    private final RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 预计算技术指标
     */
    public void precomputeIndicators(List<MarketData> data, int warmupEndIndex, String symbol) {
        log.info("🧮 开始预计算技术指标: {} 数据点", warmupEndIndex);
        
        StopWatch stopWatch = StopWatch.createStarted();
        
        try {
            // 并行计算多种技术指标
            CompletableFuture<Void> macdFuture = CompletableFuture.runAsync(() -> 
                precomputeMACDIndicator(data, warmupEndIndex, symbol));
            
            CompletableFuture<Void> bollFuture = CompletableFuture.runAsync(() ->
                precomputeBollingerBandsIndicator(data, warmupEndIndex, symbol));
            
            CompletableFuture<Void> rsiFuture = CompletableFuture.runAsync(() ->
                precomputeRSIIndicator(data, warmupEndIndex, symbol));
            
            CompletableFuture<Void> volumeFuture = CompletableFuture.runAsync(() ->
                precomputeVolumeIndicators(data, warmupEndIndex, symbol));
            
            // 等待所有计算完成
            CompletableFuture.allOf(macdFuture, bollFuture, rsiFuture, volumeFuture).join();
            
            stopWatch.stop();
            log.info("✅ 技术指标预计算完成，耗时: {}ms", stopWatch.getTime());
            
        } catch (Exception e) {
            log.error("❌ 技术指标预计算失败", e);
            throw new DataPreparationException("技术指标预计算失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 预计算MACD指标
     */
    private void precomputeMACDIndicator(List<MarketData> data, int warmupEndIndex, String symbol) {
        log.debug("计算MACD指标...");
        
        // MACD需要26个数据点才能开始计算
        int startIndex = Math.max(26, 0);
        
        for (int i = startIndex; i <= warmupEndIndex; i++) {
            try {
                List<MarketData> window = data.subList(0, i + 1);
                MACDIndicator macd = indicatorCalculator.calculateMACD(window, 12, 26, 9);
                
                // 缓存计算结果
                String cacheKey = String.format("indicator:macd:%s:%d", symbol, i);
                redisTemplate.opsForValue().set(cacheKey, macd, Duration.ofHours(24));
                
            } catch (Exception e) {
                log.warn("MACD计算失败 at index {}: {}", i, e.getMessage());
            }
        }
        
        log.debug("MACD指标计算完成: {} 个数据点", warmupEndIndex - startIndex + 1);
    }
    
    /**
     * 预计算布林带指标
     */
    private void precomputeBollingerBandsIndicator(List<MarketData> data, int warmupEndIndex, String symbol) {
        log.debug("计算布林带指标...");
        
        // 布林带通常使用20周期
        int period = 20;
        double stdDev = 2.0;
        int startIndex = Math.max(period - 1, 0);
        
        for (int i = startIndex; i <= warmupEndIndex; i++) {
            try {
                List<MarketData> window = data.subList(0, i + 1);
                BollingerBandsIndicator boll = indicatorCalculator.calculateBollingerBands(
                    window, period, stdDev);
                
                // 缓存计算结果
                String cacheKey = String.format("indicator:boll:%s:%d", symbol, i);
                redisTemplate.opsForValue().set(cacheKey, boll, Duration.ofHours(24));
                
            } catch (Exception e) {
                log.warn("布林带计算失败 at index {}: {}", i, e.getMessage());
            }
        }
        
        log.debug("布林带指标计算完成: {} 个数据点", warmupEndIndex - startIndex + 1);
    }
    
    /**
     * 预计算RSI指标
     */
    private void precomputeRSIIndicator(List<MarketData> data, int warmupEndIndex, String symbol) {
        log.debug("计算RSI指标...");
        
        int period = 14;  // RSI通常使用14周期
        int startIndex = Math.max(period, 0);
        
        for (int i = startIndex; i <= warmupEndIndex; i++) {
            try {
                List<MarketData> window = data.subList(0, i + 1);
                RSIIndicator rsi = indicatorCalculator.calculateRSI(window, period);
                
                // 缓存计算结果
                String cacheKey = String.format("indicator:rsi:%s:%d", symbol, i);
                redisTemplate.opsForValue().set(cacheKey, rsi, Duration.ofHours(24));
                
            } catch (Exception e) {
                log.warn("RSI计算失败 at index {}: {}", i, e.getMessage());
            }
        }
        
        log.debug("RSI指标计算完成: {} 个数据点", warmupEndIndex - startIndex + 1);
    }
    
    /**
     * 预计算成交量指标
     */
    private void precomputeVolumeIndicators(List<MarketData> data, int warmupEndIndex, String symbol) {
        log.debug("计算成交量指标...");
        
        // 成交量移动平均线
        int[] periods = {5, 10, 20};
        
        for (int period : periods) {
            int startIndex = Math.max(period - 1, 0);
            
            for (int i = startIndex; i <= warmupEndIndex; i++) {
                try {
                    List<MarketData> window = data.subList(0, i + 1);
                    VolumeIndicator volumeMA = indicatorCalculator.calculateVolumeMA(window, period);
                    
                    // 缓存计算结果
                    String cacheKey = String.format("indicator:volume_ma_%d:%s:%d", period, symbol, i);
                    redisTemplate.opsForValue().set(cacheKey, volumeMA, Duration.ofHours(24));
                    
                } catch (Exception e) {
                    log.warn("成交量指标计算失败 at index {}: {}", i, e.getMessage());
                }
            }
        }
        
        log.debug("成交量指标计算完成");
    }
}
```

## 17.3 数据结构定义

### 17.3.1 准备数据结果类

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreparedData {
    
    private String symbol;
    private List<MarketData> marketData;
    private LocalDateTime warmupStartTime;
    private LocalDateTime backtestStartTime;
    private LocalDateTime backtestEndTime;
    private int warmupEndIndex;
    private DataQualityReport dataQualityReport;
    private long preparationTimeMs;
    
    /**
     * 获取预热期数据
     */
    public List<MarketData> getWarmupData() {
        return marketData.subList(0, warmupEndIndex);
    }
    
    /**
     * 获取回测期数据
     */
    public List<MarketData> getBacktestData() {
        return marketData.subList(warmupEndIndex, marketData.size());
    }
    
    /**
     * 获取指定索引的数据窗口（包含预热数据）
     */
    public List<MarketData> getDataWindow(int endIndex) {
        if (endIndex < 0 || endIndex >= marketData.size()) {
            throw new IllegalArgumentException("索引超出范围: " + endIndex);
        }
        return marketData.subList(0, endIndex + 1);
    }
    
    /**
     * 检查数据是否满足回测要求
     */
    public boolean isDataSufficientForBacktest() {
        return dataQualityReport.isDataUsable() && 
               warmupEndIndex >= 60 &&  // 至少60个预热数据点
               getBacktestData().size() >= 30;  // 至少30个回测数据点
    }
    
    /**
     * 获取数据统计信息
     */
    public String getStatisticsSummary() {
        return String.format(
            "数据统计 - 总计:%d 预热:%d 回测:%d 质量:%s 准备耗时:%dms",
            marketData.size(),
            warmupEndIndex,
            marketData.size() - warmupEndIndex,
            dataQualityReport.isDataUsable() ? "良好" : "不佳",
            preparationTimeMs
        );
    }
}
```

### 17.3.2 数据质量报告类

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DataQualityReport {
    
    private String symbol;
    private int totalDataPoints;
    private boolean dataUsable;
    private String summary;
    private List<String> issues;
    private LocalDateTime validationTime;
    
    // 详细统计
    private int invalidPriceCount;
    private int invalidVolumeCount;
    private int suspiciousChangeCount;
    private int duplicateCount;
    private int missingDataPoints;
    private DataDistribution dataDistribution;
    
    /**
     * 获取数据质量评分 (0-100)
     */
    public int getQualityScore() {
        if (totalDataPoints == 0) {
            return 0;
        }
        
        double invalidPriceRate = (double) invalidPriceCount / totalDataPoints;
        double invalidVolumeRate = (double) invalidVolumeCount / totalDataPoints;
        double suspiciousChangeRate = (double) suspiciousChangeCount / totalDataPoints;
        double duplicateRate = (double) duplicateCount / totalDataPoints;
        double missingRate = (double) missingDataPoints / totalDataPoints;
        
        // 计算扣分
        double totalPenalty = invalidPriceRate * 40 +      // 无效价格扣分权重最高
                             invalidVolumeRate * 20 +      // 无效成交量
                             suspiciousChangeRate * 30 +   // 异常变动
                             duplicateRate * 20 +          // 重复数据
                             missingRate * 15;             // 缺失数据
        
        int score = Math.max(0, (int) (100 - totalPenalty * 100));
        return Math.min(100, score);
    }
    
    /**
     * 获取质量等级
     */
    public QualityGrade getQualityGrade() {
        int score = getQualityScore();
        if (score >= 90) return QualityGrade.EXCELLENT;
        if (score >= 80) return QualityGrade.GOOD;
        if (score >= 70) return QualityGrade.ACCEPTABLE;
        if (score >= 60) return QualityGrade.POOR;
        return QualityGrade.UNUSABLE;
    }
    
    public enum QualityGrade {
        EXCELLENT("优秀"),
        GOOD("良好"),
        ACCEPTABLE("可接受"),
        POOR("较差"),
        UNUSABLE("不可用");
        
        private final String description;
        
        QualityGrade(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
}
```

## 17.4 异常处理

### 17.4.1 数据准备异常类

```java
public class DataPreparationException extends RuntimeException {
    
    private final String symbol;
    private final LocalDateTime timestamp;
    private final ErrorType errorType;
    
    public DataPreparationException(String message) {
        super(message);
        this.symbol = null;
        this.timestamp = LocalDateTime.now();
        this.errorType = ErrorType.GENERAL;
    }
    
    public DataPreparationException(String message, Throwable cause) {
        super(message, cause);
        this.symbol = null;
        this.timestamp = LocalDateTime.now();
        this.errorType = ErrorType.GENERAL;
    }
    
    public DataPreparationException(String symbol, ErrorType errorType, String message) {
        super(message);
        this.symbol = symbol;
        this.timestamp = LocalDateTime.now();
        this.errorType = errorType;
    }
    
    public DataPreparationException(String symbol, ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.symbol = symbol;
        this.timestamp = LocalDateTime.now();
        this.errorType = errorType;
    }
    
    public enum ErrorType {
        DATA_NOT_FOUND("数据未找到"),
        INSUFFICIENT_DATA("数据不足"),
        INVALID_DATA_QUALITY("数据质量不合格"),
        CALCULATION_ERROR("计算错误"),
        CACHE_ERROR("缓存错误"),
        GENERAL("通用错误");
        
        private final String description;
        
        ErrorType(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    // Getters
    public String getSymbol() { return symbol; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public ErrorType getErrorType() { return errorType; }
}
```

## 17.5 性能优化策略

### 17.5.1 内存管理

```java
@Component
public class DataMemoryManager {
    
    private static final int MAX_CACHE_SIZE = 1000;
    private final Map<String, WeakReference<List<MarketData>>> dataCache = 
        new ConcurrentHashMap<>();
    
    /**
     * 智能数据缓存
     */
    public List<MarketData> getCachedData(String key, Supplier<List<MarketData>> loader) {
        WeakReference<List<MarketData>> ref = dataCache.get(key);
        List<MarketData> data = ref != null ? ref.get() : null;
        
        if (data == null) {
            data = loader.get();
            if (dataCache.size() < MAX_CACHE_SIZE) {
                dataCache.put(key, new WeakReference<>(data));
            }
        }
        
        return data;
    }
    
    /**
     * 清理过期缓存
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void cleanupCache() {
        dataCache.entrySet().removeIf(entry -> entry.getValue().get() == null);
    }
    
    /**
     * 内存压力处理
     */
    @EventListener
    public void handleMemoryPressure(MemoryPressureEvent event) {
        if (event.getSeverity() >= MemoryPressure.HIGH) {
            dataCache.clear();
            System.gc(); // 建议垃圾回收
        }
    }
}
```

### 17.5.2 并发优化

```java
@Component
public class ParallelDataProcessor {
    
    private final ForkJoinPool customThreadPool;
    
    public ParallelDataProcessor() {
        // 创建自定义线程池，避免影响其他任务
        this.customThreadPool = new ForkJoinPool(
            Math.min(4, Runtime.getRuntime().availableProcessors()),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null, false);
    }
    
    /**
     * 并行处理数据验证
     */
    public List<ValidationResult> validateDataInParallel(List<MarketData> data) {
        return data.parallelStream()
            .map(this::validateSingleDataPoint)
            .collect(Collectors.toList());
    }
    
    /**
     * 并行计算技术指标
     */
    public CompletableFuture<Map<String, Object>> calculateIndicatorsAsync(
            List<MarketData> data, Set<IndicatorType> indicators) {
        
        Map<IndicatorType, CompletableFuture<Object>> futures = indicators.stream()
            .collect(Collectors.toMap(
                Function.identity(),
                type -> CompletableFuture.supplyAsync(
                    () -> calculateIndicator(data, type), customThreadPool)
            ));
        
        return CompletableFuture.allOf(
            futures.values().toArray(new CompletableFuture[0])
        ).thenApply(v -> {
            Map<String, Object> results = new HashMap<>();
            futures.forEach((type, future) -> {
                try {
                    results.put(type.name(), future.get());
                } catch (Exception e) {
                    log.error("指标计算失败: {}", type, e);
                }
            });
            return results;
        });
    }
    
    @PreDestroy
    public void shutdown() {
        customThreadPool.shutdown();
        try {
            if (!customThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                customThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            customThreadPool.shutdownNow();
        }
    }
}
```

## 17.6 监控和诊断

### 17.6.1 数据准备监控

```java
@Component
public class DataPreparationMonitor {
    
    private final MeterRegistry meterRegistry;
    private final Counter dataPreparationCounter;
    private final Timer dataPreparationTimer;
    private final Gauge dataQualityGauge;
    
    public DataPreparationMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.dataPreparationCounter = Counter.builder("data_preparation_total")
            .description("数据准备总次数")
            .register(meterRegistry);
        this.dataPreparationTimer = Timer.builder("data_preparation_duration")
            .description("数据准备耗时")
            .register(meterRegistry);
        this.dataQualityGauge = Gauge.builder("data_quality_score")
            .description("数据质量评分")
            .register(meterRegistry, this, DataPreparationMonitor::getCurrentQualityScore);
    }
    
    /**
     * 记录数据准备指标
     */
    public void recordPreparation(String symbol, long durationMs, int qualityScore) {
        dataPreparationCounter.increment(
            Tags.of("symbol", symbol, "status", "success"));
        dataPreparationTimer.record(durationMs, TimeUnit.MILLISECONDS);
        
        // 记录质量分布
        meterRegistry.gauge("data_quality_score", 
            Tags.of("symbol", symbol), qualityScore);
    }
    
    /**
     * 记录数据准备失败
     */
    public void recordFailure(String symbol, String errorType) {
        dataPreparationCounter.increment(
            Tags.of("symbol", symbol, "status", "error", "error_type", errorType));
    }
    
    private double getCurrentQualityScore() {
        // 返回最近的数据质量评分
        return meterRegistry.find("data_quality_score")
            .gauge() != null ? meterRegistry.find("data_quality_score").gauge().value() : 0.0;
    }
}
```

## 17.7 使用示例

### 17.7.1 基本使用

```java
@Service
public class BacktestService {
    
    @Autowired
    private DataPreparationService dataPreparationService;
    
    public BacktestResult runBacktest(BacktestRequest request) {
        try {
            // 准备数据
            PreparedData preparedData = dataPreparationService.prepareBacktestData(
                request.getSymbol(), 
                request.getStartTime(), 
                request.getEndTime()
            );
            
            // 检查数据是否可用
            if (!preparedData.isDataSufficientForBacktest()) {
                throw new BacktestException(
                    "数据不满足回测要求: " + preparedData.getDataQualityReport().getSummary()
                );
            }
            
            // 执行回测
            return executeBacktest(request, preparedData);
            
        } catch (DataPreparationException e) {
            log.error("数据准备失败: {}", e.getMessage());
            return BacktestResult.failed(e.getMessage());
        }
    }
}
```

### 17.7.2 高级配置

```yaml
# application.yml
trading:
  data-preparation:
    warmup-days: 100
    min-required-points: 60
    max-missing-points: 5
    validation:
      enabled: true
      max-price-change-threshold: 0.5
      min-price: 0.001
      max-price: 10000
    cache:
      enabled: true
      ttl: 24h
      max-size: 1000
    parallel:
      enabled: true
      thread-pool-size: 4
    monitoring:
      enabled: true
      metrics-enabled: true
```

通过这个完善的数据准备服务，系统能够确保回测数据的质量和可靠性，为准确的策略回测提供坚实的数据基础。服务包含了全面的数据验证、智能缓存、并行处理和监控功能，满足了专业量化交易系统的需求。