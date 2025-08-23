package com.trading.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.domain.entity.HistoricalKLineEntity;
import com.trading.domain.entity.MarketData;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.infrastructure.cache.CacheService;
import com.trading.infrastructure.futu.FutuConnection;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.infrastructure.futu.FutuWebSocketClient;
import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.repository.MarketDataRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Market Data Service
 * Provides historical and real-time market data operations
 * Corresponds to Python FutuDataSource in the FUTU-API architecture
 */
@Slf4j
@Service
public class MarketDataService {

    @Autowired(required = false)
    private FutuConnection futuConnection;
    @Autowired(required = false)
    private FutuWebSocketClient futuWebSocketClient;
    @Autowired(required = false)
    private FutuMarketDataService futuMarketDataService;
    @Autowired
    private HistoricalDataService historicalDataService;
    @Autowired
    private MarketDataRepository marketDataRepository;
    @Autowired
    private CacheService cacheService;

    private static final DateTimeFormatter ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public CompletableFuture<List<MarketData>> getOhlcvData(
            String symbol,
            String timeframe,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int limit,
            RehabType rehabType) {

        log.info("Fetching OHLCV data for symbol: {}, timeframe: {}, period: {} to {}, rehab: {}",
                symbol, timeframe, startTime, endTime, rehabType);

        return CompletableFuture.supplyAsync(() -> {
            try {
                return getHistoricalDataFromDatabase(symbol, timeframe, startTime, endTime, limit, rehabType);
            } catch (Exception e) {
                log.error("Error fetching OHLCV data for symbol: {}", symbol, e);
                return new ArrayList<>();
            }
        });
    }

    /**
     * 从FUTU API获取历史K线数据
     */
    private List<MarketData> fetchOhlcvFromFutu(
            String symbol,
            String timeframe,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int limit,
            RehabType rehabType) {

        try {
            log.debug("从FUTU API获取K线数据: symbol={}, timeframe={}", symbol, timeframe);

            // 检查FUTU服务是否可用
            if (futuMarketDataService == null) {
                log.error("FutuMarketDataService未初始化");
                return new ArrayList<>();
            }

            // 检查连接状态
            if (futuConnection == null || !futuConnection.isConnected()) {
                log.error("FUTU未连接，无法获取真实数据");
                return new ArrayList<>();
            }

            // 转换K线类型
            FutuMarketDataService.KLineType klineType = convertToFutuKLineType(timeframe);

            // 调用真实的FUTU API获取K线数据
            log.info("调用FUTU API获取真实K线数据: symbol={}, start={}, end={}", symbol, startTime.toLocalDate(),
                    endTime.toLocalDate());
            List<FutuKLine> futuKLines = futuMarketDataService.getHistoricalKLine(
                    symbol,
                    startTime.toLocalDate(),
                    endTime.toLocalDate(),
                    klineType,
                    convertToFutuRehabType(rehabType));

            if (futuKLines == null || futuKLines.isEmpty()) {
                log.warn("FUTU API未返回数据: symbol={}", symbol);
                return new ArrayList<>();
            }

            log.info("获取到{}条K线数据，开始转换格式", futuKLines.size());

            // 转换FutuKLine为MarketData
            List<MarketData> marketDataList = convertFutuKLinesToMarketData(futuKLines, symbol, timeframe);

            // 限制返回数量
            if (marketDataList.size() > limit) {
                marketDataList = marketDataList.subList(marketDataList.size() - limit, marketDataList.size());
            }

            log.info("成功获取并转换{}条真实K线数据", marketDataList.size());
            return marketDataList;

        } catch (Exception e) {
            log.error("获取K线数据异常: symbol={}", symbol, e);
            return new ArrayList<>();
        }
    }

    /**
     * Generate simulated market data for development/testing
     */
    private List<MarketData> generateSimulatedMarketData(
            String symbol,
            String timeframe,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int limit) {

        log.debug("Generating simulated market data for symbol: {}", symbol);

        // Create sample data based on Hong Kong stocks
        BigDecimal basePrice = getBasePriceForSymbol(symbol);
        LocalDateTime current = startTime;
        List<MarketData> simulatedData = new java.util.ArrayList<>();

        for (int i = 0; i < Math.min(limit, 100); i++) {
            if (current.isAfter(endTime)) {
                break;
            }

            // Generate realistic OHLCV data
            BigDecimal variation = basePrice.multiply(BigDecimal.valueOf(Math.random() * 0.02 - 0.01));
            BigDecimal open = basePrice.add(variation);
            BigDecimal high = open.add(basePrice.multiply(BigDecimal.valueOf(Math.random() * 0.015)));
            BigDecimal low = open.subtract(basePrice.multiply(BigDecimal.valueOf(Math.random() * 0.015)));
            BigDecimal close = low.add(high.subtract(low).multiply(BigDecimal.valueOf(Math.random())));
            Long volume = (long) (1000000 + Math.random() * 5000000);

            MarketData marketData = MarketData.builder()
                    .id(symbol + "_" + current.format(ID_FORMATTER))
                    .symbol(symbol)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .turnover(close.multiply(BigDecimal.valueOf(volume)))
                    .timestamp(current)
                    .timeframe(timeframe)
                    .indicators(generateSimulatedIndicators())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            simulatedData.add(marketData);
            current = current.plusMinutes(getTimeframeMinutes(timeframe));
        }

        return simulatedData;
    }

    /**
     * Get base price for a symbol (for simulation)
     */
    private BigDecimal getBasePriceForSymbol(String symbol) {
        return switch (symbol) {
            case "00700.HK", "700.HK" -> BigDecimal.valueOf(300.0); // Tencent
            case "2800.HK" -> BigDecimal.valueOf(24.5); // Tracker Fund
            case "3033.HK" -> BigDecimal.valueOf(3.8); // Hang Seng Tech ETF
            default -> BigDecimal.valueOf(100.0);
        };
    }

    /**
     * Generate simulated technical indicators
     */
    private TechnicalIndicators generateSimulatedIndicators() {
        return TechnicalIndicators.builder()
                .macdLine(BigDecimal.valueOf(Math.random() * 2 - 1))
                .signalLine(BigDecimal.valueOf(Math.random() * 2 - 1))
                .histogram(BigDecimal.valueOf(Math.random() * 1 - 0.5))
                .upperBand(BigDecimal.valueOf(300 + Math.random() * 20))
                .middleBand(BigDecimal.valueOf(300))
                .lowerBand(BigDecimal.valueOf(300 - Math.random() * 20))
                .rsi(BigDecimal.valueOf(30 + Math.random() * 40))
                .volumeSma(BigDecimal.valueOf(2000000 + Math.random() * 3000000))
                .volumeRatio(BigDecimal.valueOf(0.8 + Math.random() * 0.4))
                .build();
    }

    /**
     * Get timeframe in minutes
     */
    private int getTimeframeMinutes(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> 1;
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "60m", "1h" -> 60;
            case "1d" -> 1440;
            default -> 30;
        };
    }

    /**
     * Get historical data from database
     */
    private List<MarketData> getHistoricalDataFromDatabase(
            String symbol,
            String timeframe,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int limit,
            RehabType rehabType) {

        log.debug("Fetching historical data from database for symbol: {}", symbol);

        FutuMarketDataService.KLineType klineType = convertToFutuKLineType(timeframe);

        List<HistoricalKLineEntity> klines = historicalDataService.getHistoricalKLine(
                symbol, startTime.toLocalDate(), endTime.toLocalDate(), klineType, rehabType);

        return klines.stream()
                .map(this::convertKlineToMarketData)
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Save market data batch to database
     */
    @Transactional
    public void saveMarketDataBatch(List<MarketData> marketDataList) {
        try {
            log.debug("Saving {} market data records to database", marketDataList.size());
            marketDataRepository.saveAll(marketDataList);
            log.info("Successfully saved {} market data records", marketDataList.size());
        } catch (Exception e) {
            log.error("Error saving market data batch", e);
            throw e;
        }
    }

    /**
     * Cache market data
     */
    private void cacheMarketData(List<MarketData> marketDataList) {
        try {
            for (MarketData data : marketDataList) {
                cacheService.cacheMarketData(data.getSymbol(), data);
            }
            log.debug("Cached {} market data records", marketDataList.size());
        } catch (Exception e) {
            log.warn("Error caching market data", e);
        }
    }

    /**
     * Get latest market data for a symbol
     */
    public Optional<MarketData> getLatestMarketData(String symbol) {
        try {
            // Try cache first
            MarketData cached = cacheService.getMarketData(symbol, MarketData.class);
            if (cached != null) {
                return Optional.of(cached);
            }

            // Try database
            return marketDataRepository.findTopBySymbolOrderByTimestampDesc(symbol);

        } catch (Exception e) {
            log.error("Error getting latest market data for symbol: {}", symbol, e);
            return Optional.empty();
        }
    }

    /**
     * Get symbols list (corresponds to Python get_symbols)
     */
    public CompletableFuture<List<String>> getSymbols(String marketRegion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("Fetching symbols for market region: {}", marketRegion);

                // TODO: Replace with actual FUTU SDK call
                // Example: futuQuoteContext.getSymbolList(marketRegion)

                // For now, return common Hong Kong symbols
                return getCommonHongKongSymbols();

            } catch (Exception e) {
                log.error("Error fetching symbols for region: {}", marketRegion, e);
                return getCommonHongKongSymbols();
            }
        });
    }

    /**
     * Get common Hong Kong symbols for development
     */
    private List<String> getCommonHongKongSymbols() {
        return List.of(
                "00700.HK", // Tencent
                "09988.HK", // Alibaba
                "03690.HK", // Meituan
                "01024.HK", // Kuaishou
                "09618.HK", // JD.com
                "02800.HK", // Tracker Fund ETF
                "03033.HK", // Hang Seng Tech ETF
                "00005.HK", // HSBC
                "00941.HK", // China Mobile
                "01299.HK" // AIA
        );
    }

    /**
     * 转换时间周期到FUTU K线类型
     */
    private int convertTimeframeToKlType(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> 1; // K_1M
            case "5m" -> 2; // K_5M
            case "15m" -> 3; // K_15M
            case "30m" -> 4; // K_30M
            case "60m", "1h" -> 5; // K_60M
            case "1d" -> 6; // K_DAY
            case "1w" -> 7; // K_WEEK
            case "1month" -> 8; // K_MON
            default -> 4; // K_30M
        };
    }

    /**
     * 批量获取历史数据（支持分页）
     */
    public CompletableFuture<List<MarketData>> getHistoricalDataWithPagination(
            String symbol,
            String timeframe,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int pageSize) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("批量获取历史数据: symbol={}, timeframe={}, 分页大小={}", symbol, timeframe, pageSize);

                List<MarketData> allData = new java.util.ArrayList<>();
                LocalDateTime currentEnd = endTime;
                int totalFetched = 0;

                while (currentEnd.isAfter(startTime) && totalFetched < 10000) { // 最多获取10000条
                    // 计算本次请求的时间范围
                    LocalDateTime currentStart = currentEnd.minusDays(calculateDaysForPageSize(timeframe, pageSize));
                    if (currentStart.isBefore(startTime)) {
                        currentStart = startTime;
                    }

                    // 获取数据
                    List<MarketData> pageData = fetchOhlcvFromFutu(symbol, timeframe, currentStart, currentEnd,
                            pageSize, RehabType.FORWARD);

                    if (pageData != null && !pageData.isEmpty()) {
                        allData.addAll(0, pageData); // 添加到开头，保持时间顺序
                        totalFetched += pageData.size();

                        // 更新下一页的结束时间
                        LocalDateTime oldestTime = pageData.stream()
                                .map(MarketData::getTimestamp)
                                .min(LocalDateTime::compareTo)
                                .orElse(currentStart);

                        currentEnd = oldestTime.minusSeconds(1);

                        // 避免请求过快
                        Thread.sleep(200);
                    } else {
                        break; // 没有更多数据
                    }

                    if (currentEnd.isBefore(startTime) || currentEnd.equals(startTime)) {
                        break;
                    }
                }

                log.info("批量获取完成: 总计{}条数据", allData.size());

                // 保存到数据库
                if (!allData.isEmpty()) {
                    saveMarketDataBatch(allData);
                    cacheMarketData(allData);
                }

                return allData;

            } catch (Exception e) {
                log.error("批量获取历史数据异常", e);
                return List.of();
            }
        });
    }

    /**
     * 计算分页所需的天数
     */
    private int calculateDaysForPageSize(String timeframe, int pageSize) {
        int minutesPerBar = getTimeframeMinutes(timeframe);
        int tradingMinutesPerDay = 6 * 60 + 30; // 6.5小时交易时间
        int barsPerDay = tradingMinutesPerDay / minutesPerBar;
        return Math.max(1, pageSize / barsPerDay);
    }

    /**
     * Check if market data service is healthy
     */
    public boolean isHealthy() {
        return (futuConnection != null && futuConnection.isConnected()) || canAccessDatabase();
    }

    /**
     * Check if database is accessible
     */
    private boolean canAccessDatabase() {
        try {
            marketDataRepository.count();
            return true;
        } catch (Exception e) {
            log.debug("Database access check failed", e);
            return false;
        }
    }

    /**
     * 获取当前价格
     */
    public CompletableFuture<PriceData> getCurrentPrice(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 尝试从缓存获取
                String cacheKey = "price:" + symbol;
                MarketData cached = cacheService.getMarketData(symbol, MarketData.class);

                if (cached != null && cached.getClose() != null) {
                    PriceData priceData = new PriceData();
                    priceData.setSymbol(symbol);
                    priceData.setPrice(cached.getClose());
                    priceData.setTimestamp(cached.getTimestamp());
                    return priceData;
                }

                // 暂时使用模拟价格，在Phase 4中实现真实的实时价格获取
                if (futuConnection != null && futuConnection.isConnected()) {
                    log.debug("FUTU已连接，但暂时使用模拟价格数据");
                }

                // 如果获取失败，返回模拟数据
                log.warn("获取实时价格失败，使用模拟数据: symbol={}", symbol);
                PriceData priceData = new PriceData();
                priceData.setSymbol(symbol);
                priceData.setPrice(BigDecimal.valueOf(100 + Math.random() * 10));
                priceData.setTimestamp(LocalDateTime.now());
                return priceData;

            } catch (Exception e) {
                log.error("获取当前价格失败: symbol={}", symbol, e);
                // 返回模拟数据
                PriceData priceData = new PriceData();
                priceData.setSymbol(symbol);
                priceData.setPrice(BigDecimal.valueOf(100));
                priceData.setTimestamp(LocalDateTime.now());
                return priceData;
            }
        });
    }

    /**
     * 价格数据
     */
    public static class PriceData {
        private String symbol;
        private BigDecimal price;
        private LocalDateTime timestamp;

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }
    }

    private com.futu.openapi.pb.QotCommon.RehabType convertToFutuRehabType(RehabType rehabType) {
        return switch (rehabType) {
            case FORWARD -> com.futu.openapi.pb.QotCommon.RehabType.RehabType_Forward;
            case BACKWARD -> com.futu.openapi.pb.QotCommon.RehabType.RehabType_Backward;
            default -> com.futu.openapi.pb.QotCommon.RehabType.RehabType_None;
        };
    }

    /**
     * 转换时间框架字符串为FUTU K线类型
     */
    private FutuMarketDataService.KLineType convertToFutuKLineType(String timeframe) {
        switch (timeframe.toLowerCase()) {
            case "1m":
                return FutuMarketDataService.KLineType.K_1MIN;
            case "5m":
                return FutuMarketDataService.KLineType.K_5MIN;
            case "15m":
                return FutuMarketDataService.KLineType.K_15MIN;
            case "30m":
                return FutuMarketDataService.KLineType.K_30MIN;
            case "60m":
            case "1h":
                return FutuMarketDataService.KLineType.K_60MIN;
            case "1d":
            case "day":
            default:
                return FutuMarketDataService.KLineType.K_DAY;
        }
    }

    /**
     * 转换FUTU K线数据为MarketData格式
     */
    private List<MarketData> convertFutuKLinesToMarketData(List<FutuKLine> futuKLines, String symbol,
            String timeframe) {
        List<MarketData> marketDataList = new ArrayList<>();

        for (FutuKLine kline : futuKLines) {
            try {
                // 创建MarketData对象
                MarketData marketData = MarketData.builder()
                        .symbol(symbol)
                        .timestamp(kline.getTimestamp())
                        .open(kline.getOpen())
                        .high(kline.getHigh())
                        .low(kline.getLow())
                        .close(kline.getClose())
                        .volume(kline.getVolume())
                        .timeframe(timeframe) // 使用传入的timeframe参数
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

                // 生成唯一ID
                String id = symbol + "_" + marketData.getTimestamp().format(ID_FORMATTER);
                marketData.setId(id);

                // 如果有成交额和换手率数据
                if (kline.getTurnover() != null) {
                    marketData.setTurnover(kline.getTurnover());
                }

                marketDataList.add(marketData);

            } catch (Exception e) {
                log.warn("转换K线数据失败: {}", e.getMessage());
            }
        }

        return marketDataList;
    }

    private MarketData convertKlineToMarketData(HistoricalKLineEntity kline) {
        return MarketData.builder()
                .symbol(kline.getSymbol())
                .timestamp(kline.getTimestamp())
                .open(kline.getOpen())
                .high(kline.getHigh())
                .low(kline.getLow())
                .close(kline.getClose())
                .volume(kline.getVolume())
                .turnover(kline.getTurnover())
                .timeframe(kline.getKlineType().toString())
                .build();
    }
}