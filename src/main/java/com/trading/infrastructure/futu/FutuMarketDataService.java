package com.trading.infrastructure.futu;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.infrastructure.futu.model.FutuOrderBook;
import com.trading.infrastructure.futu.model.FutuQuote;

/**
 * FUTU行情数据服务接口
 * 定义所有市场数据相关操作
 */
public interface FutuMarketDataService {

    /**
     * 获取实时报价
     * 
     * @param symbol 股票代码 (如: 00700.HK)
     * @return 实时报价信息
     */
    FutuQuote getRealtimeQuote(String symbol);

    /**
     * 批量获取实时报价
     * 
     * @param symbols 股票代码列表
     * @return 实时报价信息列表
     */
    List<FutuQuote> getRealtimeQuotes(List<String> symbols);

    /**
     * 获取历史K线数据
     * 
     * @param symbol    股票代码
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @param ktype     K线类型 (日线、小时线等)
     * @return K线数据列表
     */
    List<FutuKLine> getHistoricalKLine(String symbol,
            LocalDate startDate,
            LocalDate endDate,
            KLineType ktype);

    /**
     * 获取订单簿数据
     * 
     * @param symbol 股票代码
     * @return 订单簿信息
     */
    FutuOrderBook getOrderBook(String symbol);

    /**
     * 订阅实时行情推送
     * 
     * @param symbol   股票代码
     * @param listener 数据推送监听器
     * @return 订阅是否成功
     */
    boolean subscribeQuote(String symbol, QuoteListener listener);

    /**
     * 订阅订单簿推送
     * 
     * @param symbol   股票代码
     * @param listener 订单簿推送监听器
     * @return 订阅是否成功
     */
    boolean subscribeOrderBook(String symbol, OrderBookListener listener);

    /**
     * 取消行情订阅
     * 
     * @param symbol 股票代码
     * @return 取消是否成功
     */
    boolean unsubscribeQuote(String symbol);

    /**
     * 取消订单簿订阅
     * 
     * @param symbol 股票代码
     * @return 取消是否成功
     */
    boolean unsubscribeOrderBook(String symbol);

    /**
     * 获取所有已订阅的股票
     * 
     * @return 已订阅股票代码集合
     */
    Set<String> getSubscribedSymbols();

    /**
     * 检查服务是否可用
     * 
     * @return true=可用, false=不可用
     */
    boolean isServiceAvailable();

    /**
     * K线类型枚举
     */
    enum KLineType {
        K_1MIN("1分钟"),
        K_5MIN("5分钟"),
        K_15MIN("15分钟"),
        K_30MIN("30分钟"),
        K_60MIN("60分钟"),
        K_DAY("日线"),
        K_WEEK("周线"),
        K_MONTH("月线");

        private final String description;

        KLineType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 实时报价监听器
     */
    @FunctionalInterface
    interface QuoteListener {
        /**
         * 报价数据更新回调
         * 
         * @param quote 最新报价数据
         */
        void onQuoteUpdate(FutuQuote quote);
    }

    /**
     * 订单簿监听器
     */
    @FunctionalInterface
    interface OrderBookListener {
        /**
         * 订单簿数据更新回调
         * 
         * @param orderBook 最新订单簿数据
         */
        void onOrderBookUpdate(FutuOrderBook orderBook);
    }
}