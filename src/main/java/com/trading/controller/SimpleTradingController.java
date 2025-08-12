package com.trading.controller;

import com.trading.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 简化版交易控制器
 * 提供基本的REST API接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/trading")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SimpleTradingController {

    private final MarketDataService marketDataService;
    private final TradingService tradingService;
    private final TradingSignalProcessor signalProcessor;
    private final QuoteService quoteService;

    /**
     * 系统健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        try {
            Map<String, Object> health = Map.of(
                "status", "UP",
                "timestamp", System.currentTimeMillis(),
                "services", Map.of(
                    "marketData", marketDataService != null && marketDataService.isHealthy(),
                    "trading", tradingService != null && tradingService.isHealthy(),
                    "signalProcessor", signalProcessor != null && signalProcessor.isHealthy(),
                    "quote", quoteService != null && quoteService.isHealthy()
                )
            );
            
            return ResponseEntity.ok(health);
            
        } catch (Exception e) {
            log.error("健康检查异常", e);
            return ResponseEntity.status(500).body(Map.of(
                "status", "DOWN",
                "error", e.getMessage()
            ));
        }
    }

    /**
     * 获取股票最新行情
     */
    @GetMapping("/market-data/{symbol}/latest")
    public ResponseEntity<?> getLatestMarketData(@PathVariable String symbol) {
        try {
            var optionalData = marketDataService.getLatestMarketData(symbol);
            
            if (optionalData.isPresent()) {
                return ResponseEntity.ok(optionalData.get());
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("获取最新行情异常: symbol={}", symbol, e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取历史K线数据
     */
    @GetMapping("/market-data/{symbol}/history")
    public CompletableFuture<ResponseEntity<?>> getHistoricalData(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "30m") String timeframe,
            @RequestParam(defaultValue = "100") int limit) {
        
        try {
            java.time.LocalDateTime endTime = java.time.LocalDateTime.now();
            java.time.LocalDateTime startTime = endTime.minusDays(1);
            
            return marketDataService.getOhlcvData(symbol, timeframe, startTime, endTime, limit)
                .<ResponseEntity<?>>thenApply(data -> ResponseEntity.ok(data))
                .exceptionally(throwable -> {
                    log.error("获取K线数据异常: symbol={}", symbol, throwable);
                    return ResponseEntity.status(500).body(Map.of("error", throwable.getMessage()));
                });
                
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(Map.of("error", "参数解析错误: " + e.getMessage())));
        }
    }

    /**
     * 手动触发信号分析
     */
    @PostMapping("/signals/analyze/{symbol}")
    public CompletableFuture<ResponseEntity<?>> analyzeSignals(@PathVariable String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            var optionalData = marketDataService.getLatestMarketData(symbol);
            if (optionalData.isPresent()) {
                return signalProcessor.processMarketData(optionalData.get())
                    .<ResponseEntity<?>>thenApply(signals -> ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "signalCount", signals.size(),
                        "signals", signals
                    )));
            } else {
                return CompletableFuture.<ResponseEntity<?>>completedFuture(
                    ResponseEntity.notFound().build()
                );
            }
        })
        .thenCompose(future -> future)
        .exceptionally(throwable -> {
            log.error("信号分析异常: symbol={}", symbol, throwable);
            return ResponseEntity.status(500).body(Map.of("error", throwable.getMessage()));
        });
    }

    /**
     * 获取实时报价
     */
    @GetMapping("/quotes/{symbol}")
    public ResponseEntity<?> getLatestQuote(@PathVariable String symbol) {
        try {
            var quote = quoteService.getLatestQuote(symbol);
            if (quote.isPresent()) {
                return ResponseEntity.ok(quote.get());
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("获取报价异常: symbol={}", symbol, e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取账户信息
     */
    @GetMapping("/account/{accountId}")
    public CompletableFuture<ResponseEntity<?>> getAccountInfo(@PathVariable String accountId) {
        return tradingService.getAccountInfo(accountId)
            .<ResponseEntity<?>>thenApply(accountInfo -> ResponseEntity.ok(accountInfo))
            .exceptionally(throwable -> {
                log.error("获取账户信息异常: accountId={}", accountId, throwable);
                return ResponseEntity.status(500).body(Map.of("error", throwable.getMessage()));
            });
    }

    /**
     * 获取持仓信息
     */
    @GetMapping("/positions/{accountId}")
    public CompletableFuture<ResponseEntity<?>> getPositions(@PathVariable String accountId) {
        return tradingService.getPositions(accountId)
            .<ResponseEntity<?>>thenApply(positions -> ResponseEntity.ok(positions))
            .exceptionally(throwable -> {
                log.error("获取持仓异常: accountId={}", accountId, throwable);
                return ResponseEntity.status(500).body(Map.of("error", throwable.getMessage()));
            });
    }
}