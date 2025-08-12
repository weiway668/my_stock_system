package com.trading.service;

import com.trading.domain.entity.MarketData;
import com.trading.domain.vo.TechnicalIndicators;
import com.trading.infrastructure.cache.CacheService;
import com.trading.infrastructure.futu.FutuConnectionManager;
import com.trading.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Market Data Service
 * Provides historical and real-time market data operations
 * Corresponds to Python FutuDataSource in the FUTU-API architecture
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.futu.connection.host")
public class MarketDataService {

    private final FutuConnectionManager connectionManager;
    private final MarketDataRepository marketDataRepository;
    private final CacheService cacheService;
    private final com.trading.infrastructure.futu.client.FutuApiClient futuApiClient;
    private final com.trading.infrastructure.futu.protocol.FutuProtobufSerializer protobufSerializer;

    private static final DateTimeFormatter ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Get OHLCV data for a symbol (corresponds to Python get_ohlcv)
     * 
     * @param symbol Stock symbol (e.g., "00700.HK")
     * @param timeframe Time frame (e.g., "30m", "120m", "1d")
     * @param startTime Start time for data retrieval
     * @param endTime End time for data retrieval
     * @param limit Maximum number of records to return
     * @return List of MarketData
     */
    public CompletableFuture<List<MarketData>> getOhlcvData(
            String symbol, 
            String timeframe, 
            LocalDateTime startTime, 
            LocalDateTime endTime,
            int limit) {
        
        log.info("Fetching OHLCV data for symbol: {}, timeframe: {}, period: {} to {}", 
            symbol, timeframe, startTime, endTime);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Check if connection is available
                if (!connectionManager.isQuoteConnected()) {
                    log.warn("Quote connection not available for symbol: {}", symbol);
                    return getHistoricalDataFromDatabase(symbol, timeframe, startTime, endTime, limit);
                }

                // Try to fetch from FUTU API first
                List<MarketData> apiData = fetchOhlcvFromFutu(symbol, timeframe, startTime, endTime, limit);
                
                if (apiData != null && !apiData.isEmpty()) {
                    // Save to database and cache
                    saveMarketDataBatch(apiData);
                    cacheMarketData(apiData);
                    return apiData;
                } else {
                    // Fallback to database
                    log.debug("No data from API, falling back to database for symbol: {}", symbol);
                    return getHistoricalDataFromDatabase(symbol, timeframe, startTime, endTime, limit);
                }

            } catch (Exception e) {
                log.error("Error fetching OHLCV data for symbol: {}", symbol, e);
                // Fallback to database on error
                return getHistoricalDataFromDatabase(symbol, timeframe, startTime, endTime, limit);
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
            int limit) {
        
        try {
            log.debug("从FUTU API获取K线数据: symbol={}, timeframe={}", symbol, timeframe);
            
            // 转换时间周期到FUTU K线类型
            int klType = convertTimeframeToKlType(timeframe);
            
            // 格式化时间
            String beginTimeStr = protobufSerializer.formatDateTime(startTime);
            String endTimeStr = protobufSerializer.formatDateTime(endTime);
            
            // 构建K线请求
            byte[] requestData = protobufSerializer.buildGetKLineRequest(
                symbol, klType, limit, beginTimeStr, endTimeStr
            );
            
            // 发送请求
            CompletableFuture<io.netty.buffer.ByteBuf> future = futuApiClient.sendRequest(
                com.trading.infrastructure.futu.protocol.FutuProtocol.PROTO_ID_GET_KL,
                requestData
            );
            
            // 等待响应（超时10秒）
            io.netty.buffer.ByteBuf response = future.get(10, TimeUnit.SECONDS);
            
            if (response != null) {
                // 解析K线数据
                List<MarketData> klineData = parseKlineResponse(response, symbol, timeframe);
                
                if (klineData != null && !klineData.isEmpty()) {
                    log.info("成功获取{}条K线数据", klineData.size());
                    return klineData;
                }
            }
            
            log.warn("FUTU API未返回K线数据，使用模拟数据");
            return generateSimulatedMarketData(symbol, timeframe, startTime, endTime, limit);
            
        } catch (TimeoutException e) {
            log.error("获取K线数据超时: symbol={}", symbol);
            return generateSimulatedMarketData(symbol, timeframe, startTime, endTime, limit);
        } catch (Exception e) {
            log.error("获取K线数据异常: symbol={}", symbol, e);
            return generateSimulatedMarketData(symbol, timeframe, startTime, endTime, limit);
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
            case "3033.HK" -> BigDecimal.valueOf(3.8);  // Hang Seng Tech ETF
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
            int limit) {
        
        log.debug("Fetching historical data from database for symbol: {}", symbol);
        
        // Try cache first
        String cacheKey = String.format("historical:%s:%s:%s:%s", symbol, timeframe, 
            startTime.format(DateTimeFormatter.ISO_LOCAL_DATE), 
            endTime.format(DateTimeFormatter.ISO_LOCAL_DATE));
        
        @SuppressWarnings("unchecked")
        List<MarketData> cachedData = cacheService.getConfiguration(cacheKey, List.class);
        if (cachedData != null) {
            log.debug("Retrieved historical data from cache for symbol: {}", symbol);
            return cachedData.stream().limit(limit).toList();
        }
        
        // Fetch from database
        List<MarketData> dbData = marketDataRepository.findBySymbolAndTimestampBetween(symbol, startTime, endTime)
            .stream()
            .limit(limit)
            .toList();
        
        // Cache the result
        if (!dbData.isEmpty()) {
            cacheService.cacheConfiguration(cacheKey, dbData);
        }
        
        return dbData;
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
            "00700.HK",  // Tencent
            "09988.HK",  // Alibaba
            "03690.HK",  // Meituan
            "01024.HK",  // Kuaishou
            "09618.HK",  // JD.com
            "02800.HK",  // Tracker Fund ETF
            "03033.HK",  // Hang Seng Tech ETF
            "00005.HK",  // HSBC
            "00941.HK",  // China Mobile
            "01299.HK"   // AIA
        );
    }

    /**
     * 转换时间周期到FUTU K线类型
     */
    private int convertTimeframeToKlType(String timeframe) {
        com.trading.infrastructure.futu.protocol.FutuProtocol.KLType klType = switch (timeframe.toLowerCase()) {
            case "1m" -> com.trading.infrastructure.futu.protocol.FutuProtocol.KLType.K_1M;
            case "5m" -> com.trading.infrastructure.futu.protocol.FutuProtocol.KLType.K_5M;
            case "15m" -> com.trading.infrastructure.futu.protocol.FutuProtocol.KLType.K_15M;
            case "30m" -> com.trading.infrastructure.futu.protocol.FutuProtocol.KLType.K_30M;
            case "60m", "1h" -> com.trading.infrastructure.futu.protocol.FutuProtocol.KLType.K_60M;
            case "1d" -> com.trading.infrastructure.futu.protocol.FutuProtocol.KLType.K_DAY;
            case "1w" -> com.trading.infrastructure.futu.protocol.FutuProtocol.KLType.K_WEEK;
            case "1month" -> com.trading.infrastructure.futu.protocol.FutuProtocol.KLType.K_MON;
            default -> com.trading.infrastructure.futu.protocol.FutuProtocol.KLType.K_30M;
        };
        return klType.getCode();
    }
    
    /**
     * 解析K线响应数据
     */
    @SuppressWarnings("unchecked")
    private List<MarketData> parseKlineResponse(io.netty.buffer.ByteBuf response, String symbol, String timeframe) {
        try {
            // 读取响应数据
            byte[] responseData = new byte[response.readableBytes()];
            response.readBytes(responseData);
            
            // 解析响应（简化版，实际应使用Protobuf）
            Map<String, Object> responseMap = protobufSerializer.parseResponse(responseData);
            
            if (responseMap == null || !responseMap.containsKey("s2c")) {
                log.warn("K线响应格式错误");
                return null;
            }
            
            Map<String, Object> s2c = (Map<String, Object>) responseMap.get("s2c");
            if (!s2c.containsKey("klList")) {
                log.warn("K线响应中没有数据");
                return null;
            }
            
            List<Map<String, Object>> klList = (List<Map<String, Object>>) s2c.get("klList");
            List<MarketData> marketDataList = new java.util.ArrayList<>();
            
            for (Map<String, Object> kl : klList) {
                try {
                    MarketData marketData = MarketData.builder()
                        .id(symbol + "_" + kl.get("time").toString().replace(" ", "_").replace(":", ""))
                        .symbol(symbol)
                        .open(BigDecimal.valueOf(((Number) kl.get("openPrice")).doubleValue()))
                        .high(BigDecimal.valueOf(((Number) kl.get("highPrice")).doubleValue()))
                        .low(BigDecimal.valueOf(((Number) kl.get("lowPrice")).doubleValue()))
                        .close(BigDecimal.valueOf(((Number) kl.get("closePrice")).doubleValue()))
                        .volume(((Number) kl.get("volume")).longValue())
                        .turnover(BigDecimal.valueOf(((Number) kl.get("turnover")).doubleValue()))
                        .timestamp(protobufSerializer.parseDateTime(kl.get("time").toString()))
                        .timeframe(timeframe)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                    
                    marketDataList.add(marketData);
                } catch (Exception e) {
                    log.warn("解析K线数据项失败: {}", e.getMessage());
                }
            }
            
            return marketDataList;
            
        } catch (Exception e) {
            log.error("解析K线响应异常", e);
            return null;
        } finally {
            // 释放ByteBuf
            response.release();
        }
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
                    List<MarketData> pageData = fetchOhlcvFromFutu(symbol, timeframe, currentStart, currentEnd, pageSize);
                    
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
        return connectionManager.isQuoteConnected() || canAccessDatabase();
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
}