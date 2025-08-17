package com.trading.infrastructure.futu;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.trading.infrastructure.futu.model.FutuKLine;
import com.trading.infrastructure.futu.model.FutuOrderBook;
import com.trading.infrastructure.futu.model.FutuQuote;
import com.trading.domain.entity.CorporateActionEntity;

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
     * 获取指定市场在一段时间内的交易日
     *
     * @param market    市场类型
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 交易日集合 (格式: yyyy-MM-dd)
     */
    Set<String> getTradingDays(com.trading.common.enums.MarketType market, LocalDate startDate, LocalDate endDate);

    /**
     * 检查服务是否可用
     * 
     * @return true=可用, false=不可用
     */
    boolean isServiceAvailable();

    /**
     * 获取除权除息信息 (复权因子)
     *
     * @param symbol 股票代码
     * @return 公司行动实体列表
     */
    List<CorporateActionEntity> getRehab(String symbol);

    /**
     * K线类型枚举
     */
    enum KLineType {
        K_1MIN("1分钟", 1),
        K_5MIN("5分钟", 5),
        K_15MIN("15分钟", 15),
        K_30MIN("30分钟", 30),
        K_60MIN("60分钟", 60),
        K_DAY("日线", 24 * 60),
        K_WEEK("周线", 7 * 24 * 60),
        K_MONTH("月线", 30 * 24 * 60); // Approximate

        private final String description;
        private final long intervalMinutes;

        KLineType(String description, long intervalMinutes) {
            this.description = description;
            this.intervalMinutes = intervalMinutes;
        }

        public String getDescription() {
            return description;
        }

        public long getIntervalMinutes() {
            return intervalMinutes;
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