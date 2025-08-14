package com.trading.infrastructure.futu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * FUTU响应管理器
 * 管理异步请求的响应匹配和超时处理
 */
@Slf4j
@Component
public class FutuResponseManager {
    
    // 等待响应的请求Map，key为序列号，value为CompletableFuture
    private final ConcurrentHashMap<Integer, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();
    
    // 默认超时时间(秒)
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    
    /**
     * 注册一个等待响应的请求
     * 
     * @param seqNo 请求序列号
     * @param responseType 期望的响应类型
     * @param timeoutSeconds 超时时间(秒)
     * @return CompletableFuture，将在响应到达时完成
     */
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> registerRequest(int seqNo, Class<T> responseType, int timeoutSeconds) {
        if (seqNo <= 0) {
            CompletableFuture<T> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalArgumentException("无效的序列号: " + seqNo));
            return failedFuture;
        }
        
        CompletableFuture<T> future = new CompletableFuture<>();
        pendingRequests.put(seqNo, (CompletableFuture<Object>) future);
        
        log.debug("注册请求等待响应: seqNo={}, 超时时间={}秒", seqNo, timeoutSeconds);
        
        // 设置超时处理
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .whenComplete((result, throwable) -> {
                // 无论成功还是失败，都要清理
                pendingRequests.remove(seqNo);
                
                if (throwable != null) {
                    log.warn("请求响应异常或超时: seqNo={}, 错误={}", seqNo, throwable.getMessage());
                } else {
                    log.debug("请求响应成功: seqNo={}", seqNo);
                }
            });
        
        return future;
    }
    
    /**
     * 注册请求（使用默认超时时间）
     */
    public <T> CompletableFuture<T> registerRequest(int seqNo, Class<T> responseType) {
        return registerRequest(seqNo, responseType, DEFAULT_TIMEOUT_SECONDS);
    }
    
    /**
     * 完成一个等待中的请求
     * 
     * @param seqNo 请求序列号  
     * @param response 响应数据
     * @return 是否找到并完成了对应的请求
     */
    @SuppressWarnings("unchecked")
    public <T> boolean completeRequest(int seqNo, T response) {
        CompletableFuture<Object> future = pendingRequests.remove(seqNo);
        
        if (future != null) {
            try {
                future.complete(response);
                log.debug("完成请求响应: seqNo={}", seqNo);
                return true;
            } catch (Exception e) {
                log.error("完成请求响应时异常: seqNo={}", seqNo, e);
                future.completeExceptionally(e);
                return false;
            }
        } else {
            log.warn("未找到等待响应的请求: seqNo={}", seqNo);
            return false;
        }
    }
    
    /**
     * 使请求失败
     * 
     * @param seqNo 请求序列号
     * @param throwable 异常信息
     * @return 是否找到并失败了对应的请求
     */
    public boolean failRequest(int seqNo, Throwable throwable) {
        CompletableFuture<Object> future = pendingRequests.remove(seqNo);
        
        if (future != null) {
            future.completeExceptionally(throwable);
            log.debug("请求失败: seqNo={}, 错误={}", seqNo, throwable.getMessage());
            return true;
        } else {
            log.warn("未找到等待失败的请求: seqNo={}", seqNo);
            return false;
        }
    }
    
    /**
     * 取消一个等待中的请求
     * 
     * @param seqNo 请求序列号
     * @return 是否找到并取消了对应的请求
     */
    public boolean cancelRequest(int seqNo) {
        CompletableFuture<Object> future = pendingRequests.remove(seqNo);
        
        if (future != null) {
            boolean cancelled = future.cancel(true);
            log.debug("取消请求: seqNo={}, 结果={}", seqNo, cancelled);
            return cancelled;
        } else {
            log.warn("未找到要取消的请求: seqNo={}", seqNo);
            return false;
        }
    }
    
    /**
     * 清理所有等待中的请求
     */
    public void clearAllRequests() {
        log.info("清理所有等待中的请求, 数量: {}", pendingRequests.size());
        
        RuntimeException cancellationException = new RuntimeException("连接断开，所有请求被取消");
        
        pendingRequests.forEach((seqNo, future) -> {
            try {
                future.completeExceptionally(cancellationException);
            } catch (Exception e) {
                log.warn("清理请求时异常: seqNo={}", seqNo, e);
            }
        });
        
        pendingRequests.clear();
    }
    
    /**
     * 获取当前等待响应的请求数量
     */
    public int getPendingRequestCount() {
        return pendingRequests.size();
    }
    
    /**
     * 检查是否有指定序列号的等待请求
     */
    public boolean hasPendingRequest(int seqNo) {
        return pendingRequests.containsKey(seqNo);
    }
}