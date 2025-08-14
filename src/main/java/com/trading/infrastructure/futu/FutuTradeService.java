package com.trading.infrastructure.futu;

import com.trading.infrastructure.futu.model.FutuOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * FUTU交易服务接口
 * 定义所有交易相关操作
 */
public interface FutuTradeService {

    /**
     * 下单
     * @param symbol 股票代码
     * @param orderType 订单类型
     * @param orderSide 买卖方向
     * @param quantity 数量
     * @param price 价格 (市价单时可为null)
     * @return 订单ID
     */
    CompletableFuture<String> placeOrder(String symbol, 
                                        OrderType orderType, 
                                        OrderSide orderSide, 
                                        int quantity, 
                                        BigDecimal price);

    /**
     * 撤单
     * @param orderId 订单ID
     * @return 撤单是否成功
     */
    CompletableFuture<Boolean> cancelOrder(String orderId);

    /**
     * 修改订单
     * @param orderId 订单ID
     * @param newQuantity 新数量
     * @param newPrice 新价格
     * @return 修改是否成功
     */
    CompletableFuture<Boolean> modifyOrder(String orderId, 
                                          Integer newQuantity, 
                                          BigDecimal newPrice);

    /**
     * 查询订单详情
     * @param orderId 订单ID
     * @return 订单详情
     */
    CompletableFuture<FutuOrder> getOrderDetail(String orderId);

    /**
     * 查询当日订单列表
     * @param symbol 股票代码 (可选，为null时查询所有)
     * @return 订单列表
     */
    CompletableFuture<List<FutuOrder>> getTodayOrders(String symbol);

    /**
     * 查询历史订单
     * @param symbol 股票代码 (可选)
     * @param days 查询天数
     * @return 历史订单列表
     */
    CompletableFuture<List<FutuOrder>> getHistoryOrders(String symbol, int days);

    /**
     * 查询持仓信息
     * @param symbol 股票代码 (可选，为null时查询所有持仓)
     * @return 持仓列表
     */
    CompletableFuture<List<Position>> getPositions(String symbol);

    /**
     * 查询账户信息
     * @return 账户信息
     */
    CompletableFuture<AccountInfo> getAccountInfo();

    /**
     * 添加订单状态监听器
     * @param listener 订单状态变更监听器
     */
    void addOrderListener(OrderListener listener);

    /**
     * 移除订单状态监听器
     * @param listener 要移除的监听器
     */
    void removeOrderListener(OrderListener listener);

    /**
     * 检查交易服务是否可用
     * @return true=可用, false=不可用
     */
    boolean isServiceAvailable();

    /**
     * 订单类型枚举
     */
    enum OrderType {
        MARKET("市价单"),
        LIMIT("限价单"),
        STOP("止损单"),
        STOP_LIMIT("止损限价单");

        private final String description;

        OrderType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 买卖方向枚举
     */
    enum OrderSide {
        BUY("买入"),
        SELL("卖出");

        private final String description;

        OrderSide(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 持仓信息
     */
    interface Position {
        String getSymbol();
        int getQuantity();
        BigDecimal getAvgCost();
        BigDecimal getCurrentPrice();
        BigDecimal getUnrealizedPL();
        BigDecimal getMarketValue();
    }

    /**
     * 账户信息
     */
    interface AccountInfo {
        String getAccountId();
        BigDecimal getTotalAssets();
        BigDecimal getAvailableCash();
        BigDecimal getMarketValue();
        BigDecimal getUnrealizedPL();
        BigDecimal getRealizedPL();
    }

    /**
     * 订单状态监听器
     */
    @FunctionalInterface
    interface OrderListener {
        /**
         * 订单状态变更回调
         * @param order 订单信息
         */
        void onOrderUpdate(FutuOrder order);
    }
}