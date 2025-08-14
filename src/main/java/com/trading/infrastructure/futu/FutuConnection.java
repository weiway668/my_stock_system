package com.trading.infrastructure.futu;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * FUTU连接管理接口
 * 定义连接状态管理和生命周期操作
 */
public interface FutuConnection {

    /**
     * 初始化连接
     * @return 连接是否成功建立
     */
    CompletableFuture<Boolean> connect();

    /**
     * 断开连接
     * @return 断开是否成功
     */
    CompletableFuture<Boolean> disconnect();

    /**
     * 检查连接状态
     * @return true=已连接, false=未连接
     */
    boolean isConnected();

    /**
     * 检查是否正在连接中
     * @return true=连接中, false=未连接中
     */
    boolean isConnecting();

    /**
     * 获取连接状态详情
     * @return 连接状态信息
     */
    ConnectionStatus getConnectionStatus();

    /**
     * 添加连接状态监听器
     * @param listener 状态变更回调
     */
    void addConnectionListener(ConnectionListener listener);

    /**
     * 移除连接状态监听器
     * @param listener 要移除的监听器
     */
    void removeConnectionListener(ConnectionListener listener);

    /**
     * 连接状态信息
     */
    interface ConnectionStatus {
        boolean isConnected();
        boolean isConnecting();
        LocalDateTime getLastConnectTime();
        LocalDateTime getLastDisconnectTime();
        int getRetryCount();
        String getErrorMessage();
        long getUptime();
    }

    /**
     * 连接状态监听器
     */
    @FunctionalInterface
    interface ConnectionListener {
        /**
         * 连接状态变更回调
         * @param connected 是否已连接
         * @param error 错误信息（如有）
         */
        void onConnectionChanged(boolean connected, String error);
    }
}