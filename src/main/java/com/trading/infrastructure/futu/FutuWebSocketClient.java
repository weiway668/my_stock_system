package com.trading.infrastructure.futu;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.futu.openapi.FTAPI;
import com.futu.openapi.FTAPI_Conn;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.FTAPI_Conn_Trd;
import com.futu.openapi.FTSPI_Conn;
import com.futu.openapi.FTSPI_Qot;
import com.futu.openapi.ProtoID;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetBasicQot;
import com.futu.openapi.pb.QotGetOrderBook;
import com.futu.openapi.pb.QotRequestHistoryKL;
import com.futu.openapi.pb.QotRequestRehab;
import com.futu.openapi.pb.QotRequestTradeDate;
import com.futu.openapi.pb.QotSub;
import com.futu.openapi.pb.QotUpdateBasicQot;
import com.futu.openapi.pb.QotUpdateBroker;
import com.futu.openapi.pb.QotUpdateKL;
import com.futu.openapi.pb.QotUpdateOrderBook;
import com.futu.openapi.pb.QotUpdateRT;
import com.futu.openapi.pb.QotUpdateTicker;
import com.google.protobuf.GeneratedMessageV3;
import com.trading.infrastructure.futu.model.FutuOrderBook;
import com.trading.infrastructure.futu.model.FutuQuote;

import lombok.extern.slf4j.Slf4j;

/**
 * FUTU WebSocket客户端
 * 基于官方示例代码DemoBase实现的同步连接机制
 */
@Component
@Slf4j
public class FutuWebSocketClient implements FTSPI_Qot, FTSPI_Conn, FutuConnection {

    // ========== 连接状态枚举（参考官方示例） ==========
    enum ConnStatus {
        DISCONNECT,
        CONNECTING,
        READY
    }

    // ========== 请求信息类（参考官方示例） ==========
    static class ReqInfo {
        int protoID;
        Object syncEvent;
        GeneratedMessageV3 rsp;

        ReqInfo(int protoID, Object syncEvent) {
            this.protoID = protoID;
            this.syncEvent = syncEvent;
        }
    }

    // ========== 同步锁对象（参考官方示例） ==========
    protected final Object qotLock = new Object();
    protected final Object trdLock = new Object();

    // ========== 连接配置 ==========
    private String host = "127.0.0.1";
    private int port = 11111;
    private boolean useSSL = false;

    // ========== FUTU连接对象 ==========
    protected FTAPI_Conn_Qot qotClient = new FTAPI_Conn_Qot();
    protected FTAPI_Conn_Trd trdClient = new FTAPI_Conn_Trd();

    // ========== 连接状态（参考官方示例） ==========
    protected volatile ConnStatus qotConnStatus = ConnStatus.DISCONNECT;
    protected volatile ConnStatus trdConnStatus = ConnStatus.DISCONNECT;

    // ========== 请求映射（参考官方示例） ==========
    protected HashMap<Integer, ReqInfo> qotReqInfoMap = new HashMap<>();
    protected HashMap<Integer, ReqInfo> trdReqInfoMap = new HashMap<>();

    // ========== 状态跟踪 ==========
    private final AtomicReference<LocalDateTime> lastConnectTime = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastDisconnectTime = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    // ========== 订阅管理 ==========
    private final Set<String> subscribedQuoteSymbols = ConcurrentHashMap.newKeySet();
    private final Set<String> subscribedOrderBookSymbols = ConcurrentHashMap.newKeySet();
    private final Map<String, FutuMarketDataService.QuoteListener> quoteListeners = new ConcurrentHashMap<>();
    private final Map<String, FutuMarketDataService.OrderBookListener> orderBookListeners = new ConcurrentHashMap<>();

    // ========== 连接状态监听器 ==========
    private final Set<ConnectionListener> connectionListeners = ConcurrentHashMap.newKeySet();

    // ========== 重连管理 ==========
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> reconnectTask;
    private volatile int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 10;
    private static final int RECONNECT_DELAY_SECONDS = 5;

    /**
     * 同步连接到行情服务器（参考官方示例 initConnectQotSync）
     * 
     * @param timeoutSeconds 超时时间（秒）
     * @return 连接是否成功
     */
    public boolean connectQotSync(int timeoutSeconds) throws InterruptedException {
        qotClient.setConnSpi(this);
        qotClient.setQotSpi(this);

        synchronized (qotLock) {
            if (qotConnStatus == ConnStatus.READY) {
                log.info("FUTU行情连接已建立");
                return true;
            }

            qotConnStatus = ConnStatus.CONNECTING;
            log.info("开始连接FUTU行情服务器: {}:{}", host, port);

            // 初始化FUTU API环境
            FTAPI.init();

            // 设置客户端标识
            qotClient.setClientInfo("HKTradingSystem", 1);

            // 发起连接
            boolean ret = qotClient.initConnect(host, port, useSSL);
            if (!ret) {
                log.error("发起FUTU行情连接失败");
                qotConnStatus = ConnStatus.DISCONNECT;
                return false;
            }

            // 等待连接结果
            long waitTime = TimeUnit.SECONDS.toMillis(timeoutSeconds);
            qotLock.wait(waitTime);

            boolean success = qotConnStatus == ConnStatus.READY;
            if (success) {
                lastConnectTime.set(LocalDateTime.now());
                retryCount = 0;
            }

            return success;
        }
    }

    /**
     * 同步连接到交易服务器（参考官方示例 initConnectTrdSync）
     * 
     * @param timeoutSeconds 超时时间（秒）
     * @return 连接是否成功
     */
    public boolean connectTrdSync(int timeoutSeconds) throws InterruptedException {
        trdClient.setConnSpi(this);
        // trdClient.setTrdSpi(this); // 如果实现了交易接口，取消注释

        synchronized (trdLock) {
            if (trdConnStatus == ConnStatus.READY) {
                log.info("FUTU交易连接已建立");
                return true;
            }

            trdConnStatus = ConnStatus.CONNECTING;
            log.info("开始连接FUTU交易服务器: {}:{}", host, port);

            // 发起连接
            boolean ret = trdClient.initConnect(host, port, useSSL);
            if (!ret) {
                log.error("发起FUTU交易连接失败");
                trdConnStatus = ConnStatus.DISCONNECT;
                return false;
            }

            // 等待连接结果
            long waitTime = TimeUnit.SECONDS.toMillis(timeoutSeconds);
            trdLock.wait(waitTime);

            return trdConnStatus == ConnStatus.READY;
        }
    }

    /**
     * 默认同步连接方法（连接行情服务器）
     */
    public boolean connectSync() throws InterruptedException {
        return connectQotSync(30); // 默认30秒超时
    }

    /**
     * 异步连接方法（保留兼容性）
     */
    @Override
    public CompletableFuture<Boolean> connect() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return connectSync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("异步连接被中断", e);
                return false;
            }
        });
    }

    /**
     * 断开连接
     */
    @Override
    public CompletableFuture<Boolean> disconnect() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("断开FUTU WebSocket连接");

                // 取消重连任务
                if (reconnectTask != null) {
                    reconnectTask.cancel(false);
                }

                // 清理行情连接
                synchronized (qotLock) {
                    if (qotClient != null) {
                        qotClient.close();
                    }
                    qotConnStatus = ConnStatus.DISCONNECT;
                    qotReqInfoMap.clear();
                }

                // 清理交易连接
                synchronized (trdLock) {
                    if (trdClient != null) {
                        trdClient.close();
                    }
                    trdConnStatus = ConnStatus.DISCONNECT;
                    trdReqInfoMap.clear();
                }

                // 更新状态
                lastDisconnectTime.set(LocalDateTime.now());

                // 清理订阅
                subscribedQuoteSymbols.clear();
                subscribedOrderBookSymbols.clear();
                quoteListeners.clear();
                orderBookListeners.clear();

                log.info("FUTU WebSocket连接已断开");
                notifyConnectionListeners(false, "主动断开连接");
                return true;

            } catch (Exception e) {
                log.error("断开FUTU WebSocket连接异常", e);
                return false;
            }
        });
    }

    // ========== FUTU官方SDK回调接口实现（参考官方示例） ==========

    @Override
    public void onInitConnect(FTAPI_Conn client, long errCode, String desc) {
        log.info("FUTU连接回调: errCode={}, desc={}, connID={}",
                errCode, desc, client.getConnectID());

        if (client instanceof FTAPI_Conn_Qot) {
            synchronized (qotLock) {
                if (errCode == 0) {
                    qotConnStatus = ConnStatus.READY;
                    lastError.set(null);
                    log.info("✅ FUTU行情连接成功");
                    notifyConnectionListeners(true, null);
                } else {
                    qotConnStatus = ConnStatus.DISCONNECT;
                    lastError.set(desc);
                    log.error("❌ FUTU行情连接失败: {}", desc);
                    notifyConnectionListeners(false, desc);
                }
                qotLock.notifyAll(); // 唤醒等待的线程
            }
            return;
        }

        if (client instanceof FTAPI_Conn_Trd) {
            synchronized (trdLock) {
                if (errCode == 0) {
                    trdConnStatus = ConnStatus.READY;
                    log.info("✅ FUTU交易连接成功");
                } else {
                    trdConnStatus = ConnStatus.DISCONNECT;
                    log.error("❌ FUTU交易连接失败: {}", desc);
                }
                trdLock.notifyAll(); // 唤醒等待的线程
            }
        }
    }

    @Override
    public void onDisconnect(FTAPI_Conn client, long errCode) {
        log.warn("FUTU连接断开: errCode={}", errCode);

        if (client instanceof FTAPI_Conn_Qot) {
            synchronized (qotLock) {
                qotConnStatus = ConnStatus.DISCONNECT;
                lastDisconnectTime.set(LocalDateTime.now());
                lastError.set("连接断开，错误码: " + errCode);
            }
            notifyConnectionListeners(false, "连接断开");
            // 自动重连
            scheduleReconnect();
        }

        if (client instanceof FTAPI_Conn_Trd) {
            synchronized (trdLock) {
                trdConnStatus = ConnStatus.DISCONNECT;
            }
        }
    }

    // ========== 同步请求方法（参考官方示例） ==========

    /**
     * 同步获取基础报价（参考官方示例）
     */
    public QotGetBasicQot.Response getBasicQotSync(List<QotCommon.Security> secList)
            throws InterruptedException {
        ReqInfo reqInfo = null;
        Object syncEvent = new Object();

        synchronized (syncEvent) {
            synchronized (qotLock) {
                if (qotConnStatus != ConnStatus.READY) {
                    log.error("FUTU行情连接未就绪");
                    return null;
                }

                QotGetBasicQot.C2S c2s = QotGetBasicQot.C2S.newBuilder()
                        .addAllSecurityList(secList)
                        .build();
                QotGetBasicQot.Request req = QotGetBasicQot.Request.newBuilder()
                        .setC2S(c2s)
                        .build();

                int sn = qotClient.getBasicQot(req);
                if (sn == 0) {
                    log.error("发送基础报价请求失败");
                    return null;
                }

                log.debug("发送基础报价请求成功: seqNo={}", sn);
                reqInfo = new ReqInfo(ProtoID.QOT_GETBASICQOT, syncEvent);
                qotReqInfoMap.put(sn, reqInfo);
            }

            // 等待响应
            syncEvent.wait(10000); // 10秒超时
            return (QotGetBasicQot.Response) reqInfo.rsp;
        }
    }

    /**
     * 同步订阅（参考官方示例）
     */
    public QotSub.Response subSync(List<QotCommon.Security> secList,
            List<QotCommon.SubType> subTypeList,
            boolean isSub,
            boolean isRegPush) throws InterruptedException {
        ReqInfo reqInfo = null;
        Object syncEvent = new Object();

        synchronized (syncEvent) {
            synchronized (qotLock) {
                if (qotConnStatus != ConnStatus.READY) {
                    log.error("FUTU行情连接未就绪");
                    return null;
                }

                QotSub.C2S c2s = QotSub.C2S.newBuilder()
                        .addAllSecurityList(secList)
                        .addAllSubTypeList(subTypeList.stream()
                                .mapToInt(subType -> subType.getNumber())
                                .boxed()
                                .collect(Collectors.toList()))
                        .setIsSubOrUnSub(isSub)
                        .setIsRegOrUnRegPush(isRegPush)
                        .build();
                QotSub.Request req = QotSub.Request.newBuilder().setC2S(c2s).build();

                int sn = qotClient.sub(req);
                if (sn == 0) {
                    log.error("发送订阅请求失败");
                    return null;
                }

                log.debug("发送订阅请求成功: seqNo={}", sn);
                reqInfo = new ReqInfo(ProtoID.QOT_SUB, syncEvent);
                qotReqInfoMap.put(sn, reqInfo);
            }

            syncEvent.wait(10000); // 10秒超时
            return (QotSub.Response) reqInfo.rsp;
        }
    }

    /**
     * 同步获取订单簿（参考官方示例）
     */
    public QotGetOrderBook.Response getOrderBookSync(QotCommon.Security sec, int num)
            throws InterruptedException {
        ReqInfo reqInfo = null;
        Object syncEvent = new Object();

        synchronized (syncEvent) {
            synchronized (qotLock) {
                if (qotConnStatus != ConnStatus.READY) {
                    log.error("FUTU行情连接未就绪");
                    return null;
                }

                QotGetOrderBook.C2S c2s = QotGetOrderBook.C2S.newBuilder()
                        .setSecurity(sec)
                        .setNum(num)
                        .build();
                QotGetOrderBook.Request req = QotGetOrderBook.Request.newBuilder()
                        .setC2S(c2s)
                        .build();

                int sn = qotClient.getOrderBook(req);
                if (sn == 0) {
                    log.error("发送订单簿请求失败");
                    return null;
                }

                log.debug("发送订单簿请求成功: seqNo={}", sn);
                reqInfo = new ReqInfo(ProtoID.QOT_GETORDERBOOK, syncEvent);
                qotReqInfoMap.put(sn, reqInfo);
            }

            syncEvent.wait(10000); // 10秒超时
            return (QotGetOrderBook.Response) reqInfo.rsp;
        }
    }

    /**
     * 同步请求历史K线（参考官方示例）
     */
    public QotRequestHistoryKL.Response requestHistoryKLSync(
            QotCommon.Security sec,
            QotCommon.KLType klType,
            QotCommon.RehabType rehabType,
            String beginTime,
            String endTime,
            Integer count,
            Long klFields,
            byte[] nextReqKey,
            boolean extendedTime) throws InterruptedException {

        ReqInfo reqInfo = null;
        Object syncEvent = new Object();

        synchronized (syncEvent) {
            synchronized (qotLock) {
                if (qotConnStatus != ConnStatus.READY) {
                    log.error("FUTU行情连接未就绪");
                    return null;
                }

                QotRequestHistoryKL.C2S.Builder c2s = QotRequestHistoryKL.C2S.newBuilder()
                        .setSecurity(sec)
                        .setKlType(klType.getNumber())
                        .setRehabType(rehabType.getNumber())
                        .setBeginTime(beginTime)
                        .setEndTime(endTime)
                        .setExtendedTime(extendedTime);

                if (count != null) {
                    c2s.setMaxAckKLNum(count);
                }
                if (klFields != null) {
                    c2s.setNeedKLFieldsFlag(klFields);
                }
                if (nextReqKey != null && nextReqKey.length > 0) {
                    c2s.setNextReqKey(com.google.protobuf.ByteString.copyFrom(nextReqKey));
                }

                QotRequestHistoryKL.Request req = QotRequestHistoryKL.Request.newBuilder()
                        .setC2S(c2s)
                        .build();

                int sn = qotClient.requestHistoryKL(req);
                if (sn == 0) {
                    log.error("发送K线请求失败");
                    return null;
                }

                log.debug("发送K线请求成功: seqNo={}", sn);
                reqInfo = new ReqInfo(ProtoID.QOT_REQUESTHISTORYKL, syncEvent);
                qotReqInfoMap.put(sn, reqInfo);
            }

            syncEvent.wait(10000); // 10秒超时
            return (QotRequestHistoryKL.Response) reqInfo.rsp;
        }
    }

    /**
     * 同步获取交易日
     */
    public QotRequestTradeDate.Response getTradeDateSync(int market, String beginTime, String endTime)
            throws InterruptedException {
        ReqInfo reqInfo = null;
        Object syncEvent = new Object();

        synchronized (syncEvent) {
            synchronized (qotLock) {
                if (qotConnStatus != ConnStatus.READY) {
                    log.error("FUTU行情连接未就绪");
                    return null;
                }

                QotRequestTradeDate.C2S c2s = QotRequestTradeDate.C2S.newBuilder()
                        .setMarket(market)
                        .setBeginTime(beginTime)
                        .setEndTime(endTime)
                        .build();
                QotRequestTradeDate.Request req = QotRequestTradeDate.Request.newBuilder().setC2S(c2s).build();

                int sn = qotClient.requestTradeDate(req);
                if (sn == 0) {
                    log.error("发送交易日请求失败");
                    return null;
                }

                log.debug("发送交易日请求成功: seqNo={}", sn);
                reqInfo = new ReqInfo(ProtoID.QOT_GETTRADEDATE, syncEvent);
                qotReqInfoMap.put(sn, reqInfo);
            }

            syncEvent.wait(10000); // 10秒超时
            return (QotRequestTradeDate.Response) reqInfo.rsp;
        }
    }


    /**
     * 同步获取复权因子
     */
    public QotRequestRehab.Response requestRehabSync(QotCommon.Security sec) throws InterruptedException {
        ReqInfo reqInfo = null;
        Object syncEvent = new Object();

        synchronized (syncEvent) {
            synchronized (qotLock) {
                if (qotConnStatus != ConnStatus.READY) {
                    log.error("FUTU行情连接未就绪");
                    return null;
                }

                QotRequestRehab.C2S c2s = QotRequestRehab.C2S.newBuilder()
                        .setSecurity(sec)
                        .build();
                QotRequestRehab.Request req = QotRequestRehab.Request.newBuilder().setC2S(c2s).build();

                int sn = qotClient.requestRehab(req);
                if (sn == 0) {
                    log.error("发送获取复权因子请求失败");
                    return null;
                }

                log.debug("发送获取复权因子请求成功: seqNo={}", sn);
                reqInfo = new ReqInfo(ProtoID.QOT_REQUESTREHAB, syncEvent);
                qotReqInfoMap.put(sn, reqInfo);
            }

            syncEvent.wait(60000); // 10秒超时
            return (QotRequestRehab.Response) reqInfo.rsp;
        }
    }

    // ========== 响应回调处理（参考官方示例） ==========

    @Override
    public void onReply_GetBasicQot(FTAPI_Conn client, int nSerialNo, QotGetBasicQot.Response rsp) {
        handleQotOnReply(nSerialNo, ProtoID.QOT_GETBASICQOT, rsp);
    }

    @Override
    public void onReply_Sub(FTAPI_Conn client, int nSerialNo, QotSub.Response rsp) {
        handleQotOnReply(nSerialNo, ProtoID.QOT_SUB, rsp);
    }

    @Override
    public void onReply_GetOrderBook(FTAPI_Conn client, int nSerialNo, QotGetOrderBook.Response rsp) {
        handleQotOnReply(nSerialNo, ProtoID.QOT_GETORDERBOOK, rsp);
    }

    @Override
    public void onReply_RequestHistoryKL(FTAPI_Conn client, int nSerialNo, QotRequestHistoryKL.Response rsp) {
        handleQotOnReply(nSerialNo, ProtoID.QOT_REQUESTHISTORYKL, rsp);
    }

    @Override
    public void onReply_RequestTradeDate(FTAPI_Conn client, int nSerialNo, QotRequestTradeDate.Response rsp) {
        handleQotOnReply(nSerialNo, ProtoID.QOT_GETTRADEDATE, rsp);
    }

    public void onReply_RequestRehab(FTAPI_Conn client, int nSerialNo, QotRequestRehab.Response rsp) {
        handleQotOnReply(nSerialNo, ProtoID.QOT_GETREHAB, rsp);
    }


    /**
     * 获取行情请求信息
     */
    private ReqInfo getQotReqInfo(int serialNo, int protoID) {
        synchronized (qotLock) {
            ReqInfo info = qotReqInfoMap.get(serialNo);
            if (info != null && info.protoID == protoID) {
                qotReqInfoMap.remove(serialNo);
                return info;
            }
        }
        return null;
    }

    /**
     * 处理行情响应（参考官方示例）
     */
    private void handleQotOnReply(int serialNo, int protoID, GeneratedMessageV3 rsp) {
        ReqInfo reqInfo = getQotReqInfo(serialNo, protoID);
        if (reqInfo != null) {
            synchronized (reqInfo.syncEvent) {
                reqInfo.rsp = rsp;
                reqInfo.syncEvent.notifyAll(); // 唤醒等待的线程
            }
        }
    }

    // ========== 推送数据处理 ==========

    @Override
    public void onPush_UpdateBasicQuote(FTAPI_Conn client, QotUpdateBasicQot.Response rsp) {
        if (rsp.getRetType() != 0) {
            log.error("基础报价推送错误: {}", rsp.getRetMsg());
            return;
        }

        for (QotCommon.BasicQot basicQot : rsp.getS2C().getBasicQotListList()) {
            String symbol = basicQot.getSecurity().getCode();

            FutuQuote quote = FutuQuote.builder()
                    .code(symbol)
                    .lastPrice(BigDecimal.valueOf(basicQot.getCurPrice()))
                    .highPrice(BigDecimal.valueOf(basicQot.getHighPrice()))
                    .lowPrice(BigDecimal.valueOf(basicQot.getLowPrice()))
                    .openPrice(BigDecimal.valueOf(basicQot.getOpenPrice()))
                    .preClosePrice(BigDecimal.valueOf(basicQot.getLastClosePrice()))
                    .volume(basicQot.getVolume())
                    .turnover(BigDecimal.valueOf(basicQot.getTurnover()))
                    .timestamp(LocalDateTime.now())
                    .build();

            // 通知监听器
            FutuMarketDataService.QuoteListener listener = quoteListeners.get(symbol);
            if (listener != null) {
                listener.onQuoteUpdate(quote);
            }
        }
    }

    @Override
    public void onPush_UpdateOrderBook(FTAPI_Conn client, QotUpdateOrderBook.Response rsp) {
        if (rsp.getRetType() != 0) {
            log.error("订单簿推送错误: {}", rsp.getRetMsg());
            return;
        }

        String symbol = rsp.getS2C().getSecurity().getCode();

        // 准备买盘列表
        List<FutuOrderBook.OrderBookEntry> bidList = new ArrayList<>();
        for (QotCommon.OrderBook bid : rsp.getS2C().getOrderBookBidListList()) {
            bidList.add(FutuOrderBook.OrderBookEntry.builder()
                    .price(BigDecimal.valueOf(bid.getPrice()))
                    .volume(bid.getVolume())
                    .orderCount(bid.getOrederCount())
                    .build());
        }

        // 准备卖盘列表
        List<FutuOrderBook.OrderBookEntry> askList = new ArrayList<>();
        for (QotCommon.OrderBook ask : rsp.getS2C().getOrderBookAskListList()) {
            askList.add(FutuOrderBook.OrderBookEntry.builder()
                    .price(BigDecimal.valueOf(ask.getPrice()))
                    .volume(ask.getVolume())
                    .orderCount(ask.getOrederCount())
                    .build());
        }

        FutuOrderBook orderBook = FutuOrderBook.builder()
                .code(symbol)
                .bidList(bidList)
                .askList(askList)
                .timestamp(LocalDateTime.now())
                .build();

        // 通知监听器
        FutuMarketDataService.OrderBookListener listener = orderBookListeners.get(symbol);
        if (listener != null) {
            listener.onOrderBookUpdate(orderBook);
        }
    }

    @Override
    public void onPush_UpdateKL(FTAPI_Conn client, QotUpdateKL.Response rsp) {
        log.debug("收到K线推送: {}", rsp.getS2C().getSecurity().getCode());
        // TODO: 实现K线推送处理
    }

    @Override
    public void onPush_UpdateRT(FTAPI_Conn client, QotUpdateRT.Response rsp) {
        log.debug("收到分时推送: {}", rsp.getS2C().getSecurity().getCode());
        // TODO: 实现分时推送处理
    }

    @Override
    public void onPush_UpdateTicker(FTAPI_Conn client, QotUpdateTicker.Response rsp) {
        log.debug("收到逐笔推送: {}", rsp.getS2C().getSecurity().getCode());
        // TODO: 实现逐笔推送处理
    }

    @Override
    public void onPush_UpdateBroker(FTAPI_Conn client, QotUpdateBroker.Response rsp) {
        log.debug("收到经纪队列推送: {}", rsp.getS2C().getSecurity().getCode());
        // TODO: 实现经纪队列推送处理
    }

    // ========== 辅助方法 ==========

    /**
     * 构建证券对象
     */
    public static QotCommon.Security makeSec(QotCommon.QotMarket market, String code) {
        return QotCommon.Security.newBuilder()
                .setCode(code)
                .setMarket(market.getNumber())
                .build();
    }

    /**
     * 获取市场类型
     */
    private int getMarketType(String symbol) {
        if (symbol.endsWith(".HK")) {
            return QotCommon.QotMarket.QotMarket_HK_Security_VALUE;
        } else if (symbol.endsWith(".US")) {
            return QotCommon.QotMarket.QotMarket_US_Security_VALUE;
        }
        // 默认港股
        return QotCommon.QotMarket.QotMarket_HK_Security_VALUE;
    }

    /**
     * 检查连接状态
     */
    @Override
    public boolean isConnected() {
        synchronized (qotLock) {
            return qotConnStatus == ConnStatus.READY;
        }
    }

    /**
     * 检查是否正在连接
     */
    @Override
    public boolean isConnecting() {
        synchronized (qotLock) {
            return qotConnStatus == ConnStatus.CONNECTING;
        }
    }

    /**
     * 获取连接状态
     */
    @Override
    public ConnectionStatus getConnectionStatus() {
        return new ConnectionStatus() {
            @Override
            public boolean isConnected() {
                synchronized (qotLock) {
                    return qotConnStatus == ConnStatus.READY;
                }
            }

            @Override
            public boolean isConnecting() {
                synchronized (qotLock) {
                    return qotConnStatus == ConnStatus.CONNECTING;
                }
            }

            @Override
            public LocalDateTime getLastConnectTime() {
                return lastConnectTime.get();
            }

            @Override
            public LocalDateTime getLastDisconnectTime() {
                return lastDisconnectTime.get();
            }

            @Override
            public int getRetryCount() {
                return retryCount;
            }

            @Override
            public String getErrorMessage() {
                return lastError.get();
            }

            @Override
            public long getUptime() {
                LocalDateTime connectTime = lastConnectTime.get();
                if (connectTime != null && isConnected()) {
                    return java.time.Duration.between(connectTime, LocalDateTime.now()).getSeconds();
                }
                return 0;
            }
        };
    }

    /**
     * 添加连接监听器
     */
    @Override
    public void addConnectionListener(ConnectionListener listener) {
        connectionListeners.add(listener);
    }

    /**
     * 移除连接监听器
     */
    @Override
    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    /**
     * 通知连接监听器
     */
    private void notifyConnectionListeners(boolean connected, String message) {
        for (ConnectionListener listener : connectionListeners) {
            try {
                listener.onConnectionChanged(connected, message);
            } catch (Exception e) {
                log.error("通知连接监听器异常", e);
            }
        }
    }

    /**
     * 调度重连
     */
    private void scheduleReconnect() {
        if (retryCount >= MAX_RETRY_COUNT) {
            log.error("已达到最大重连次数: {}", MAX_RETRY_COUNT);
            return;
        }

        retryCount++;
        log.info("将在{}秒后进行第{}次重连", RECONNECT_DELAY_SECONDS, retryCount);

        reconnectTask = reconnectExecutor.schedule(() -> {
            try {
                if (connectSync()) {
                    log.info("第{}次重连成功", retryCount);
                    retryCount = 0;
                } else {
                    log.warn("第{}次重连失败", retryCount);
                    scheduleReconnect();
                }
            } catch (Exception e) {
                log.error("重连异常", e);
                scheduleReconnect();
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 设置连接参数
     */
    public void setConnectionParams(String host, int port, boolean useSSL) {
        this.host = host;
        this.port = port;
        this.useSSL = useSSL;
    }

    /**
     * 获取最后错误信息
     */
    public String getLastError() {
        return lastError.get();
    }

    /**
     * 获取连接时间
     */
    public LocalDateTime getLastConnectTime() {
        return lastConnectTime.get();
    }

    /**
     * 获取断开时间
     */
    public LocalDateTime getLastDisconnectTime() {
        return lastDisconnectTime.get();
    }

    // ========== 便捷的异步包装方法 ==========

    /**
     * 异步获取基础报价
     */
    public CompletableFuture<QotGetBasicQot.Response> getBasicQotAsync(List<QotCommon.Security> secList) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getBasicQotSync(secList);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("获取基础报价被中断", e);
                return null;
            }
        });
    }

    /**
     * 异步订阅
     */
    public CompletableFuture<QotSub.Response> subAsync(List<QotCommon.Security> secList,
            List<QotCommon.SubType> subTypeList,
            boolean isSub,
            boolean isRegPush) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return subSync(secList, subTypeList, isSub, isRegPush);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("订阅被中断", e);
                return null;
            }
        });
    }

    /**
     * 异步获取订单簿
     */
    public CompletableFuture<QotGetOrderBook.Response> getOrderBookAsync(QotCommon.Security sec, int num) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getOrderBookSync(sec, num);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("获取订单簿被中断", e);
                return null;
            }
        });
    }

    /**
     * 异步请求历史K线
     */
    public CompletableFuture<QotRequestHistoryKL.Response> requestHistoryKLAsync(
            QotCommon.Security sec,
            QotCommon.KLType klType,
            QotCommon.RehabType rehabType,
            String beginTime,
            String endTime,
            Integer count,
            Long klFields,
            byte[] nextReqKey,
            boolean extendedTime) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return requestHistoryKLSync(sec, klType, rehabType, beginTime, endTime,
                        count, klFields, nextReqKey, extendedTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("请求历史K线被中断", e);
                return null;
            }
        });
    }

    /**
     * 获取交易客户端（供其他服务使用）
     */
    public FTAPI_Conn_Trd getTrdClient() {
        return trdClient;
    }

    /**
     * 获取行情客户端（供其他服务使用）
     */
    public FTAPI_Conn_Qot getQotClient() {
        return qotClient;
    }

    /**
     * 订阅基础报价（兼容旧接口）
     */
    public CompletableFuture<Boolean> subscribeQuote(String symbol, FutuMarketDataService.QuoteListener listener) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 保存监听器
                if (listener != null) {
                    quoteListeners.put(symbol, listener);
                }

                // 准备订阅参数
                List<QotCommon.Security> secList = new ArrayList<>();
                secList.add(QotCommon.Security.newBuilder()
                        .setCode(symbol.replace(".HK", ""))
                        .setMarket(getMarketType(symbol))
                        .build());

                List<QotCommon.SubType> subTypeList = new ArrayList<>();
                subTypeList.add(QotCommon.SubType.SubType_Basic);

                // 执行订阅
                QotSub.Response response = subSync(secList, subTypeList, true, true);
                return response != null && response.getRetType() == 0;
            } catch (Exception e) {
                log.error("订阅基础报价失败: {}", symbol, e);
                return false;
            }
        });
    }

    /**
     * 订阅订单簿（兼容旧接口）
     */
    public CompletableFuture<Boolean> subscribeOrderBook(String symbol, FutuMarketDataService.OrderBookListener listener) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 保存监听器
                if (listener != null) {
                    orderBookListeners.put(symbol, listener);
                }

                // 准备订阅参数
                List<QotCommon.Security> secList = new ArrayList<>();
                secList.add(QotCommon.Security.newBuilder()
                        .setCode(symbol.replace(".HK", ""))
                        .setMarket(getMarketType(symbol))
                        .build());

                List<QotCommon.SubType> subTypeList = new ArrayList<>();
                subTypeList.add(QotCommon.SubType.SubType_OrderBook);

                // 执行订阅
                QotSub.Response response = subSync(secList, subTypeList, true, true);
                return response != null && response.getRetType() == 0;
            } catch (Exception e) {
                log.error("订阅订单簿失败: {}", symbol, e);
                return false;
            }
        });
    }

    /**
     * 取消订阅基础报价（兼容旧接口）
     */
    public CompletableFuture<Boolean> unsubscribeQuote(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 移除监听器
                quoteListeners.remove(symbol);

                // 准备取消订阅参数
                List<QotCommon.Security> secList = new ArrayList<>();
                secList.add(QotCommon.Security.newBuilder()
                        .setCode(symbol.replace(".HK", ""))
                        .setMarket(getMarketType(symbol))
                        .build());

                List<QotCommon.SubType> subTypeList = new ArrayList<>();
                subTypeList.add(QotCommon.SubType.SubType_Basic);

                // 执行取消订阅
                QotSub.Response response = subSync(secList, subTypeList, false, false);
                return response != null && response.getRetType() == 0;
            } catch (Exception e) {
                log.error("取消订阅基础报价失败: {}", symbol, e);
                return false;
            }
        });
    }

    /**
     * 取消订阅订单簿（兼容旧接口）
     */
    public CompletableFuture<Boolean> unsubscribeOrderBook(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 移除监听器
                orderBookListeners.remove(symbol);

                // 准备取消订阅参数
                List<QotCommon.Security> secList = new ArrayList<>();
                secList.add(QotCommon.Security.newBuilder()
                        .setCode(symbol.replace(".HK", ""))
                        .setMarket(getMarketType(symbol))
                        .build());

                List<QotCommon.SubType> subTypeList = new ArrayList<>();
                subTypeList.add(QotCommon.SubType.SubType_OrderBook);

                // 执行取消订阅
                QotSub.Response response = subSync(secList, subTypeList, false, false);
                return response != null && response.getRetType() == 0;
            } catch (Exception e) {
                log.error("取消订阅订单簿失败: {}", symbol, e);
                return false;
            }
        });
    }
}