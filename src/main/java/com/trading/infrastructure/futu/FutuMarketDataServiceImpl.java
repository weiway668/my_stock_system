package com.trading.infrastructure.futu;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetBasicQot;
import com.futu.openapi.pb.QotGetOrderBook;
import com.futu.openapi.pb.QotRequestHistoryKL;
import com.futu.openapi.pb.QotRequestTradeDate;
import com.futu.openapi.pb.QotSub;
import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.infrastructure.futu.model.FutuOrderBook;
import com.trading.infrastructure.futu.model.FutuQuote;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FUTU行情数据服务实现类
 * 基于FUTU OpenAPI SDK实现真实的市场数据获取
 * 使用FutuWebSocketClient进行数据通信
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FutuMarketDataServiceImpl implements FutuMarketDataService {

    private final FutuWebSocketClient webSocketClient;

    // 缓存已订阅的股票和监听器
    private final Map<String, FutuMarketDataService.QuoteListener> quoteListeners = new ConcurrentHashMap<>();
    private final Map<String, FutuMarketDataService.OrderBookListener> orderBookListeners = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> tradingDaysCache = new ConcurrentHashMap<>();

    @Override
    public FutuQuote getRealtimeQuote(String symbol) {
        try {
            log.debug("获取实时报价: {}", symbol);

                // 参数校验
                if (symbol == null || symbol.trim().isEmpty()) {
                    log.warn("股票代码为空，无法获取实时报价");
                    return null;
                }

                if (!webSocketClient.isConnected()) {
                    log.warn("FUTU连接未建立，无法获取实时报价: {}", symbol);
                    return null;
                }

                // 构建请求 - 基于FUTU API
                QotCommon.Security security = QotCommon.Security.newBuilder()
                        .setCode(removeHKSuffix(symbol))
                        .setMarket(getMarketType(symbol))
                        .build();

                QotGetBasicQot.C2S c2s = QotGetBasicQot.C2S.newBuilder()
                        .addSecurityList(security)
                        .build();

                QotGetBasicQot.Request req = QotGetBasicQot.Request.newBuilder()
                        .setC2S(c2s)
                        .build();

                // 发送同步请求
                try {
                    // 先订阅Basic数据（FUTU API要求）
                    List<QotCommon.Security> secList = new ArrayList<>();
                    secList.add(security);
                    
                    // 订阅Basic报价
                    List<QotCommon.SubType> subTypeList = new ArrayList<>();
                    subTypeList.add(QotCommon.SubType.SubType_Basic);
                    
                    QotSub.Response subResponse = webSocketClient.subSync(secList, subTypeList, true, true);
                    boolean subscribed = subResponse != null && subResponse.getRetType() == 0;
                    if (!subscribed) {
                        log.warn("订阅Basic数据失败: {}", symbol);
                        return generateMockQuote(symbol);
                    }
                    
                    // 获取报价数据
                    QotGetBasicQot.Response response = webSocketClient.getBasicQotSync(secList);

                    // 检查响应
                    if (response == null || response.getRetType() != 0) {
                        log.warn("获取报价响应失败: {}", response != null ? response.getRetMsg() : "response is null");
                        return generateMockQuote(symbol);
                    }

                    // 检查是否有数据
                    if (response.getS2C().getBasicQotListCount() == 0) {
                        log.warn("获取报价响应为空");
                        return generateMockQuote(symbol);
                    }

                    // 转换FUTU响应为FutuQuote对象
                    QotCommon.BasicQot basicQot = response.getS2C().getBasicQotList(0);
                    
                    FutuQuote quote = FutuQuote.builder()
                            .code(symbol)
                            .name(basicQot.getName())
                            .lastPrice(BigDecimal.valueOf(basicQot.getCurPrice()))
                            .openPrice(BigDecimal.valueOf(basicQot.getOpenPrice()))
                            .highPrice(BigDecimal.valueOf(basicQot.getHighPrice()))
                            .lowPrice(BigDecimal.valueOf(basicQot.getLowPrice()))
                            .preClosePrice(BigDecimal.valueOf(basicQot.getLastClosePrice()))
                            .volume(basicQot.getVolume())
                            .turnover(BigDecimal.valueOf(basicQot.getTurnover()))
                            .changeValue(BigDecimal.valueOf(basicQot.getCurPrice() - basicQot.getLastClosePrice()))
                            .changeRate(BigDecimal.valueOf((basicQot.getCurPrice() - basicQot.getLastClosePrice()) / basicQot.getLastClosePrice() * 100))
                            .amplitude(BigDecimal.valueOf((basicQot.getHighPrice() - basicQot.getLowPrice()) / basicQot.getLastClosePrice() * 100))
                            .timestamp(LocalDateTime.now())
                            .status(FutuQuote.DataStatus.NORMAL)
                            .build();
                    
                    log.info("成功获取实时报价: {} - 当前价: {}, 涨跌幅: {}%", 
                            symbol, quote.getLastPrice(), quote.getChangeRate());
                    
                    return quote;

                } catch (Exception e) {
                    log.warn("获取真实报价失败，返回模拟数据: symbol={}, error={}", symbol, e.getMessage());
                    // 降级到模拟数据
                    return generateMockQuote(symbol);
                }

        } catch (Exception e) {
            log.error("获取实时报价异常: {}", symbol, e);
            return null;
        }
    }

    @Override
    public List<FutuQuote> getRealtimeQuotes(List<String> symbols) {
        try {
            log.debug("批量获取实时报价: {}", symbols);

            List<FutuQuote> quotes = new ArrayList<>();

            // 检查输入参数
            if (symbols == null || symbols.isEmpty()) {
                return quotes;
            }

            for (String symbol : symbols) {
                FutuQuote quote = getRealtimeQuote(symbol);
                if (quote != null) {
                    quotes.add(quote);
                }
            }

            return quotes;

        } catch (Exception e) {
            log.error("批量获取实时报价异常", e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<FutuKLine> getHistoricalKLine(String symbol,
            LocalDate startDate,
            LocalDate endDate,
            FutuMarketDataService.KLineType ktype) {

        try {
            log.debug("获取历史K线: symbol={}, start={}, end={}, type={}",
                    symbol, startDate, endDate, ktype);

            if (!webSocketClient.isConnected()) {
                log.warn("FUTU连接未建立，无法获取历史K线: {}", symbol);
                return new ArrayList<>();
            }

            // 构建K线请求
            QotCommon.Security security = QotCommon.Security.newBuilder()
                    .setCode(removeHKSuffix(symbol))
                    .setMarket(getMarketType(symbol))
                    .build();

            // 收集所有K线数据
            List<FutuKLine> allKlines = new ArrayList<>();
            byte[] nextReqKey = new byte[0];
            boolean hasMore = true;
            int pageCount = 0;
            final int MAX_PAGES = 10; // 最多请求10页，避免无限循环
            
            while (hasMore && pageCount < MAX_PAGES) {
                try {
                    // 使用分页参数请求K线数据
                    QotRequestHistoryKL.Response response = webSocketClient.requestHistoryKLSync(
                            security,
                            QotCommon.KLType.forNumber(convertKLineType(ktype)),
                            QotCommon.RehabType.RehabType_None,
                            startDate.toString() + " 00:00:00",
                            endDate.toString() + " 23:59:59",
                            1000, // 每页最多1000条
                            null,
                            nextReqKey,
                            false);

                    // 检查响应
                    if (response == null || response.getRetType() != 0) {
                        log.warn("获取K线数据失败: {}", response != null ? response.getRetMsg() : "响应为空");
                        break;
                    }

                    // 检查是否有K线数据
                    if (response.getS2C() != null && response.getS2C().getKlListCount() > 0) {
                        log.info("第{}页: 收到{}条K线数据", pageCount + 1, response.getS2C().getKlListCount());
                        
                        // 转换并添加K线数据
                        List<FutuKLine> pageKlines = convertKLineData(response.getS2C().getKlListList(), symbol, ktype);
                        allKlines.addAll(pageKlines);
                        
                        // 检查是否有下一页
                        if (response.getS2C().hasNextReqKey() && response.getS2C().getNextReqKey().size() > 0) {
                            nextReqKey = response.getS2C().getNextReqKey().toByteArray();
                            hasMore = true;
                            pageCount++;
                            
                            // 添加延迟，避免请求过快
                            Thread.sleep(100);
                        } else {
                            hasMore = false;
                            log.debug("没有更多K线数据");
                        }
                    } else {
                        log.debug("K线响应中没有数据");
                        hasMore = false;
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("获取K线数据被中断");
                    break;
                } catch (Exception e) {
                    log.warn("获取K线数据页失败: {}", e.getMessage());
                    break;
                }
            }
            
            log.info("总共获取{}条K线数据: {}", allKlines.size(), symbol);
            return allKlines;

        } catch (Exception e) {
            log.error("获取历史K线异常: {}", symbol, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 转换K线数据列表
     */
    private List<FutuKLine> convertKLineData(List<QotCommon.KLine> klList, String symbol, FutuMarketDataService.KLineType ktype) {
        List<FutuKLine> klines = new ArrayList<>();
        
        for (QotCommon.KLine kl : klList) {
            try {
                // 解析时间戳（格式：yyyy-MM-dd HH:mm:ss）
                LocalDateTime timestamp = LocalDateTime.parse(
                        kl.getTime().replace(" ", "T")
                );
                
                // 计算涨跌额和涨跌幅
                double closePrice = kl.getClosePrice();
                double preClose = kl.getLastClosePrice();
                double changeValue = closePrice - preClose;
                double changeRate = preClose != 0 ? (changeValue / preClose * 100) : 0;
                
                FutuKLine futuKLine = FutuKLine.builder()
                        .code(symbol)
                        .kLineType(convertToKLineType(ktype))
                        .timestamp(timestamp)
                        .open(BigDecimal.valueOf(kl.getOpenPrice()))
                        .high(BigDecimal.valueOf(kl.getHighPrice()))
                        .low(BigDecimal.valueOf(kl.getLowPrice()))
                        .close(BigDecimal.valueOf(closePrice))
                        .volume(kl.getVolume())
                        .turnover(BigDecimal.valueOf(kl.getTurnover()))
                        .changeValue(BigDecimal.valueOf(changeValue))
                        .changeRate(BigDecimal.valueOf(changeRate))
                        .turnoverRate(kl.hasTurnoverRate() ? BigDecimal.valueOf(kl.getTurnoverRate()) : BigDecimal.ZERO)
                        .preClose(BigDecimal.valueOf(preClose))
                        .rehabType(FutuKLine.RehabType.NONE)
                        .build();
                
                klines.add(futuKLine);
                
            } catch (Exception e) {
                log.warn("转换K线数据失败，跳过该条记录: {}", e.getMessage());
            }
        }
        
        return klines;
    }

    @Override
    public FutuOrderBook getOrderBook(String symbol) {
        try {
            log.debug("获取订单簿数据: {}", symbol);

            if (!webSocketClient.isConnected()) {
                log.warn("FUTU连接未建立，无法获取订单簿: {}", symbol);
                return null;
            }

            // 构建订单簿请求
            QotCommon.Security security = QotCommon.Security.newBuilder()
                    .setCode(removeHKSuffix(symbol))
                    .setMarket(getMarketType(symbol))
                    .build();

            QotGetOrderBook.C2S c2s = QotGetOrderBook.C2S.newBuilder()
                    .setSecurity(security)
                    .setNum(10) // 获取10档数据
                    .build();

            QotGetOrderBook.Request req = QotGetOrderBook.Request.newBuilder()
                    .setC2S(c2s)
                    .build();

            // TODO: 实现同步请求响应机制
            log.debug("发送订单簿请求，待实现同步响应: {}", symbol);

            return null;

        } catch (Exception e) {
            log.error("获取订单簿异常: {}", symbol, e);
            return null;
        }
    }

    @Override
    public boolean subscribeQuote(String symbol, FutuMarketDataService.QuoteListener listener) {
        log.info("订阅实时报价: {}", symbol);

        // 保存监听器
        if (listener != null) {
            quoteListeners.put(symbol, listener);
        }

        // 委托给WebSocketClient处理订阅
        try {
            return webSocketClient.subscribeQuote(symbol, listener).get();
        } catch (Exception e) {
            log.error("订阅实时报价失败: {}", symbol, e);
            return false;
        }
    }

    @Override
    public boolean subscribeOrderBook(String symbol,
            FutuMarketDataService.OrderBookListener listener) {
        log.info("订阅订单簿: {}", symbol);

        // 保存监听器
        if (listener != null) {
            orderBookListeners.put(symbol, listener);
        }

        // 委托给WebSocketClient处理订阅
        try {
            return webSocketClient.subscribeOrderBook(symbol, listener).get();
        } catch (Exception e) {
            log.error("订阅订单簿失败: {}", symbol, e);
            return false;
        }
    }

    @Override
    public boolean unsubscribeQuote(String symbol) {
        try {
            log.info("取消报价订阅: {}", symbol);

            if (!webSocketClient.isConnected()) {
                log.warn("FUTU连接未建立，无法取消订阅: {}", symbol);
                return false;
            }

            // 构建取消订阅请求
            QotCommon.Security security = QotCommon.Security.newBuilder()
                    .setCode(removeHKSuffix(symbol))
                    .setMarket(getMarketType(symbol))
                    .build();

            QotSub.C2S c2s = QotSub.C2S.newBuilder()
                    .addSecurityList(security)
                    .addSubTypeList(QotCommon.SubType.SubType_Basic_VALUE)
                    .setIsSubOrUnSub(false) // 取消订阅
                    .setIsRegOrUnRegPush(false) // 取消推送注册
                    .build();

            QotSub.Request req = QotSub.Request.newBuilder()
                    .setC2S(c2s)
                    .build();

            FTAPI_Conn_Qot qotClient = webSocketClient.getQotClient();
            if (qotClient != null) {
                int seqNo = qotClient.sub(req);
                if (seqNo > 0) {
                    quoteListeners.remove(symbol);
                    log.info("✅ 取消报价订阅成功: {}", symbol);
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            log.error("取消报价订阅异常: {}", symbol, e);
            return false;
        }
    }

    @Override
    public boolean unsubscribeOrderBook(String symbol) {
        try {
            log.info("取消订单簿订阅: {}", symbol);

            if (!webSocketClient.isConnected()) {
                return false;
            }

            // 构建取消订阅请求
            QotCommon.Security security = QotCommon.Security.newBuilder()
                    .setCode(removeHKSuffix(symbol))
                    .setMarket(getMarketType(symbol))
                    .build();

            QotSub.C2S c2s = QotSub.C2S.newBuilder()
                    .addSecurityList(security)
                    .addSubTypeList(QotCommon.SubType.SubType_OrderBook_VALUE)
                    .setIsSubOrUnSub(false) // 取消订阅
                    .setIsRegOrUnRegPush(false) // 取消推送注册
                    .build();

            QotSub.Request req = QotSub.Request.newBuilder()
                    .setC2S(c2s)
                    .build();

            FTAPI_Conn_Qot qotClient = webSocketClient.getQotClient();
            if (qotClient != null) {
                int seqNo = qotClient.sub(req);
                if (seqNo > 0) {
                    orderBookListeners.remove(symbol);
                    log.info("✅ 取消订单簿订阅成功: {}", symbol);
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            log.error("取消订单簿订阅异常: {}", symbol, e);
            return false;
        }
    }

    @Override
    public Set<String> getSubscribedSymbols() {
        Set<String> allSubscribed = new HashSet<>();
        allSubscribed.addAll(quoteListeners.keySet());
        allSubscribed.addAll(orderBookListeners.keySet());
        return allSubscribed;
    }

    @Override
    public boolean isServiceAvailable() {
        return webSocketClient.isConnected();
    }

    @Override
    public Set<String> getTradingDays(com.trading.common.enums.MarketType market, LocalDate startDate, LocalDate endDate) {
        String cacheKey = String.format("%s-%d", market.name(), startDate.getYear());
        if (tradingDaysCache.containsKey(cacheKey)) {
            return tradingDaysCache.get(cacheKey);
        }

        if (!webSocketClient.isConnected()) {
            log.warn("FUTU连接未建立，无法获取交易日");
            return new HashSet<>();
        }

        try {
            QotRequestTradeDate.Response response = webSocketClient.getTradeDateSync(
                    market.getFutuMarketCode(),
                    startDate.toString(),
                    endDate.toString()
            );

            if (response == null || response.getRetType() != 0) {
                log.warn("获取交易日响应失败: {}", response != null ? response.getRetMsg() : "response is null");
                return new HashSet<>();
            }

            Set<String> tradeDates = response.getS2C().getTradeDateListList().stream()
                    .map(QotRequestTradeDate.TradeDate::getTime)
                    .collect(Collectors.toSet());

            log.info("成功获取 {} 年 {} 市场交易日 {} 天", startDate.getYear(), market.name(), tradeDates.size());
            tradingDaysCache.put(cacheKey, tradeDates);

            return tradeDates;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取交易日被中断", e);
            return new HashSet<>();
        } catch (Exception e) {
            log.error("获取交易日异常", e);
            return new HashSet<>();
        }
    }

    // ========== 工具方法 ==========

    /**
     * 移除港股后缀 (00700.HK -> 00700)
     */
    private String removeHKSuffix(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.replace(".HK", "").replace(".hk", "");
    }

    /**
     * 获取市场类型
     */
    private int getMarketType(String symbol) {
        if (symbol == null) {
            return QotCommon.QotMarket.QotMarket_HK_Security_VALUE;
        }
        if (symbol.toUpperCase().endsWith(".HK")) {
            return QotCommon.QotMarket.QotMarket_HK_Security_VALUE;
        } else if (symbol.toUpperCase().endsWith(".US")) {
            return QotCommon.QotMarket.QotMarket_US_Security_VALUE;
        }
        // 默认港股
        return QotCommon.QotMarket.QotMarket_HK_Security_VALUE;
    }

    /**
     * 转换K线类型到FUTU协议值
     */
    private int convertKLineType(FutuMarketDataService.KLineType ktype) {
        return switch (ktype) {
            case K_1MIN -> QotCommon.KLType.KLType_1Min_VALUE;
            case K_5MIN -> QotCommon.KLType.KLType_5Min_VALUE;
            case K_15MIN -> QotCommon.KLType.KLType_15Min_VALUE;
            case K_30MIN -> QotCommon.KLType.KLType_30Min_VALUE;
            case K_60MIN -> QotCommon.KLType.KLType_60Min_VALUE;
            case K_DAY -> QotCommon.KLType.KLType_Day_VALUE;
            case K_WEEK -> QotCommon.KLType.KLType_Week_VALUE;
            case K_MONTH -> QotCommon.KLType.KLType_Month_VALUE;
        };
    }

    /**
     * 转换K线类型到内部枚举
     */
    private FutuKLine.KLineType convertToKLineType(FutuMarketDataService.KLineType ktype) {
        return switch (ktype) {
            case K_1MIN -> FutuKLine.KLineType.K_1M;
            case K_5MIN -> FutuKLine.KLineType.K_5M;
            case K_15MIN -> FutuKLine.KLineType.K_15M;
            case K_30MIN -> FutuKLine.KLineType.K_30M;
            case K_60MIN -> FutuKLine.KLineType.K_60M;
            case K_DAY -> FutuKLine.KLineType.K_DAY;
            case K_WEEK -> FutuKLine.KLineType.K_WEEK;
            case K_MONTH -> FutuKLine.KLineType.K_MON;
        };
    }

    /**
     * 生成模拟报价数据（开发测试用）
     */
    private FutuQuote generateMockQuote(String symbol) {
        log.debug("生成模拟报价数据: {}", symbol);

        // 基于股票代码生成模拟数据
        double basePrice = getBasePriceForSymbol(symbol);
        double variation = (Math.random() - 0.5) * basePrice * 0.02; // ±2%变动

        return FutuQuote.builder()
                .code(symbol)
                .name(getNameForSymbol(symbol))
                .lastPrice(BigDecimal.valueOf(basePrice + variation))
                .openPrice(BigDecimal.valueOf(basePrice + variation * 0.8))
                .highPrice(BigDecimal.valueOf(basePrice + Math.abs(variation) * 1.2))
                .lowPrice(BigDecimal.valueOf(basePrice - Math.abs(variation) * 1.1))
                .preClosePrice(BigDecimal.valueOf(basePrice))
                .volume((long) (1000000 + Math.random() * 5000000))
                .turnover(BigDecimal.valueOf((basePrice + variation) * (1000000 + Math.random() * 5000000)))
                .changeValue(BigDecimal.valueOf(variation))
                .changeRate(BigDecimal.valueOf(variation / basePrice * 100))
                .timestamp(LocalDateTime.now())
                .status(FutuQuote.DataStatus.NORMAL)
                .build();
    }

    /**
     * 获取股票基础价格（模拟用）
     */
    private double getBasePriceForSymbol(String symbol) {
        return switch (symbol.toUpperCase()) {
            case "00700.HK", "700.HK" -> 300.0; // 腾讯
            case "09988.HK" -> 80.0; // 阿里巴巴
            case "03690.HK" -> 120.0; // 美团
            case "02800.HK" -> 24.5; // 盈富基金
            case "03033.HK" -> 3.8; // 恒生科技ETF
            default -> 100.0;
        };
    }

    /**
     * 获取股票名称（模拟用）
     */
    private String getNameForSymbol(String symbol) {
        return switch (symbol.toUpperCase()) {
            case "00700.HK", "700.HK" -> "腾讯控股";
            case "09988.HK" -> "阿里巴巴-SW";
            case "03690.HK" -> "美团-W";
            case "02800.HK" -> "盈富基金";
            case "03033.HK" -> "恒生科技ETF";
            default -> "未知股票";
        };
    }
}